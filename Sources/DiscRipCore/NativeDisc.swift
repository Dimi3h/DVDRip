import CDiscRip
import Foundation

/// A DVD-Video source opened by the native layer. Not thread-safe: use one instance per task.
public final class NativeDisc: @unchecked Sendable {
    private var handle: OpaquePointer?
    public let path: String

    public init(path: String, cssCacheDirectory: URL? = nil) throws {
        self.path = path
        var error: UnsafeMutablePointer<CChar>?
        let cache = cssCacheDirectory?.path
        if let cssCacheDirectory {
            try? FileManager.default.createDirectory(at: cssCacheDirectory, withIntermediateDirectories: true)
        }
        handle = dr_disc_open(path, cache, &error)
        if handle == nil {
            let message = error.map { String(cString: $0) } ?? ""
            dr_free(error)
            throw DiscRipError.notDVDVideo(detail: message)
        }
    }

    deinit {
        if let handle { dr_disc_close(handle) }
    }

    public func info() throws -> DiscInfo {
        guard let handle, let json = dr_disc_info_json(handle) else { throw DiscRipError.notDVDVideo(detail: "") }
        defer { dr_free(json) }
        let data = Data(bytes: json, count: strlen(json))
        return try JSONDecoder().decode(DiscInfo.self, from: data)
    }

    var rawHandle: OpaquePointer? { handle }

    public static func driveRegionState(rawDevicePath: String) -> Region.DriveState? {
        var mask: Int32 = 0, changes: Int32 = 0, scheme: Int32 = 0, type: Int32 = 0
        guard dr_drive_region_state(rawDevicePath, &mask, &changes, &scheme, &type) == 0 else { return nil }
        return Region.DriveState(disabledMask: Int(mask), userChangesLeft: Int(changes),
                                 rpcScheme: Int(scheme), typeCode: Int(type))
    }

    public struct HardwareEncoders: Sendable, Equatable {
        public var hevc: Bool
        public var h264: Bool
    }

    /// Probed once per launch (opening a VideoToolbox session takes a moment).
    public static let hardwareEncoders: HardwareEncoders = {
        let mask = dr_hw_encoders_available()
        return HardwareEncoders(hevc: mask & 1 != 0, h264: mask & 2 != 0)
    }()
}

/// Keeps C strings alive for the duration of a call.
final class CStringPool {
    private var pointers: [UnsafeMutablePointer<CChar>] = []

    func string(_ s: String) -> UnsafePointer<CChar> {
        let p = strdup(s)!
        pointers.append(p)
        return UnsafePointer(p)
    }

    /// NULL-terminated array of C strings.
    func array(_ strings: [String]) -> UnsafeMutablePointer<UnsafePointer<CChar>?> {
        let arr = UnsafeMutablePointer<UnsafePointer<CChar>?>.allocate(capacity: strings.count + 1)
        for (i, s) in strings.enumerated() { arr[i] = string(s) }
        arr[strings.count] = nil
        arrays.append(arr)
        return arr
    }

    func ints(_ values: [Int]) -> UnsafeMutablePointer<Int32> {
        let arr = UnsafeMutablePointer<Int32>.allocate(capacity: max(values.count, 1))
        for (i, v) in values.enumerated() { arr[i] = Int32(v) }
        intArrays.append(arr)
        return arr
    }

    private var arrays: [UnsafeMutablePointer<UnsafePointer<CChar>?>] = []
    private var intArrays: [UnsafeMutablePointer<Int32>] = []

    deinit {
        pointers.forEach { free($0) }
        arrays.forEach { $0.deallocate() }
        intArrays.forEach { $0.deallocate() }
    }
}
