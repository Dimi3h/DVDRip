import DiskArbitration
import Foundation
import IOKit

public struct OpticalDrive: Equatable, Hashable, Sendable, Identifiable {
    public var id: String          // IORegistry path
    public var vendor: String
    public var product: String
    public var interconnect: String // "USB", "SATA", ...
    public var name: String { [vendor, product].filter { !$0.isEmpty }.joined(separator: " ") }
}

public struct OpticalMedia: Equatable, Hashable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case dvd, cd, bluray, other }
    public var bsdName: String     // "disk4"
    public var kind: Kind
    public var volumeName: String?
    public var volumePath: URL?
    public var deviceModel: String?
    public var id: String { bsdName }
    public var rawDevicePath: String { "/dev/r" + bsdName }
}

/// Watches optical drives (IOKit) and discs (DiskArbitration). macOS drives the hardware itself;
/// DiscRip reads the raw device so libdvdcss can authenticate with the drive.
@MainActor
public final class DriveMonitor {
    public private(set) var drives: [OpticalDrive] = []
    public private(set) var media: [OpticalMedia] = []
    public var onChange: (() -> Void)?

    private var session: DASession?
    private var timer: Timer?

    public init() {}

    public func start() {
        guard session == nil else { return }
        guard let s = DASessionCreate(kCFAllocatorDefault) else { return }
        session = s
        DASessionScheduleWithRunLoop(s, CFRunLoopGetMain(), CFRunLoopMode.defaultMode.rawValue)
        let ctx = Unmanaged.passUnretained(self).toOpaque()
        DARegisterDiskAppearedCallback(s, nil, { disk, ctx in
            guard let ctx else { return }
            let monitor = Unmanaged<DriveMonitor>.fromOpaque(ctx).takeUnretainedValue()
            MainActor.assumeIsolated { monitor.update(disk: disk, removed: false) }
        }, ctx)
        DARegisterDiskDisappearedCallback(s, nil, { disk, ctx in
            guard let ctx else { return }
            let monitor = Unmanaged<DriveMonitor>.fromOpaque(ctx).takeUnretainedValue()
            MainActor.assumeIsolated { monitor.update(disk: disk, removed: true) }
        }, ctx)
        DARegisterDiskDescriptionChangedCallback(s, nil, nil, { disk, _, ctx in
            guard let ctx else { return }
            let monitor = Unmanaged<DriveMonitor>.fromOpaque(ctx).takeUnretainedValue()
            MainActor.assumeIsolated { monitor.update(disk: disk, removed: false) }
        }, ctx)
        refreshDrives()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshDrives() }
        }
    }

    private func update(disk: DADisk, removed: Bool) {
        guard let desc = DADiskCopyDescription(disk) as? [CFString: Any],
              let bsd = desc[kDADiskDescriptionMediaBSDNameKey] as? String,
              (desc[kDADiskDescriptionMediaWholeKey] as? Bool) == true,
              let kindName = desc[kDADiskDescriptionMediaKindKey] as? String else { return }
        let kind: OpticalMedia.Kind
        switch kindName {
        case "IODVDMedia": kind = .dvd
        case "IOCDMedia": kind = .cd
        case "IOBDMedia": kind = .bluray
        default: return // not optical
        }
        media.removeAll { $0.bsdName == bsd }
        if !removed {
            media.append(OpticalMedia(
                bsdName: bsd, kind: kind,
                volumeName: desc[kDADiskDescriptionVolumeNameKey] as? String,
                volumePath: desc[kDADiskDescriptionVolumePathKey] as? URL,
                deviceModel: (desc[kDADiskDescriptionDeviceModelKey] as? String)?.trimmingCharacters(in: .whitespaces)))
        }
        onChange?()
    }

    public func refreshDrives() {
        let found = Self.enumerateDrives()
        if found != drives {
            drives = found
            onChange?()
        }
    }

    static func enumerateDrives() -> [OpticalDrive] {
        var result: [OpticalDrive] = []
        for cls in ["IODVDServices", "IOBDServices", "IOCompactDiscServices"] {
            var iter: io_iterator_t = 0
            guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching(cls), &iter) == KERN_SUCCESS else { continue }
            defer { IOObjectRelease(iter) }
            while case let svc = IOIteratorNext(iter), svc != 0 {
                defer { IOObjectRelease(svc) }
                var pathBuf = [CChar](repeating: 0, count: 512)
                IORegistryEntryGetPath(svc, kIOServicePlane, &pathBuf)
                let path = String(cString: pathBuf)
                if result.contains(where: { $0.id == path }) { continue }
                func prop(_ key: String) -> [String: Any]? {
                    IORegistryEntrySearchCFProperty(svc, kIOServicePlane, key as CFString, kCFAllocatorDefault,
                                                    IOOptionBits(kIORegistryIterateRecursively | kIORegistryIterateParents)) as? [String: Any]
                }
                let dev = prop("Device Characteristics") ?? [:]
                let proto = prop("Protocol Characteristics") ?? [:]
                result.append(OpticalDrive(
                    id: path,
                    vendor: (dev["Vendor Name"] as? String ?? "").trimmingCharacters(in: .whitespaces),
                    product: (dev["Product Name"] as? String ?? "").trimmingCharacters(in: .whitespaces),
                    interconnect: proto["Physical Interconnect"] as? String ?? ""))
            }
        }
        return result
    }

    /// Unmounts and ejects the disc.
    public func eject(_ m: OpticalMedia) async -> Bool {
        guard let s = session, let disk = DADiskCreateFromBSDName(kCFAllocatorDefault, s, m.bsdName) else { return false }
        let unmounted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            let box = Unmanaged.passRetained(ContinuationBox(cont)).toOpaque()
            DADiskUnmount(disk, DADiskUnmountOptions(kDADiskUnmountOptionWhole), { _, dissenter, ctx in
                let b = Unmanaged<ContinuationBox>.fromOpaque(ctx!).takeRetainedValue()
                b.cont.resume(returning: dissenter == nil)
            }, box)
        }
        guard unmounted || m.volumePath == nil else { return false }
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            let box = Unmanaged.passRetained(ContinuationBox(cont)).toOpaque()
            DADiskEject(disk, DADiskEjectOptions(kDADiskEjectOptionDefault), { _, dissenter, ctx in
                let b = Unmanaged<ContinuationBox>.fromOpaque(ctx!).takeRetainedValue()
                b.cont.resume(returning: dissenter == nil)
            }, box)
        }
    }
}

private final class ContinuationBox {
    let cont: CheckedContinuation<Bool, Never>
    init(_ c: CheckedContinuation<Bool, Never>) { cont = c }
}
