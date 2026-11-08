import Foundation

/// DVD region handling. Both disc and drive use the convention "bit n set = region n+1 NOT allowed".
public enum Region {
    public static func regions(fromDisabledMask mask: Int) -> [Int] {
        (0..<8).filter { mask & (1 << $0) == 0 }.map { $0 + 1 }
    }

    /// RPC state reported by the drive (REPORT KEY format 0x08).
    public struct DriveState: Equatable, Sendable {
        public var disabledMask: Int
        public var userChangesLeft: Int
        public var rpcScheme: Int   // 0 = RPC-1 / unknown, 1 = RPC-2
        public var typeCode: Int    // 0 = no region set, 1 = region set, 2 = last change, 3 = permanent

        public init(disabledMask: Int, userChangesLeft: Int, rpcScheme: Int, typeCode: Int) {
            self.disabledMask = disabledMask
            self.userChangesLeft = userChangesLeft
            self.rpcScheme = rpcScheme
            self.typeCode = typeCode
        }

        public var isRPC2: Bool { rpcScheme == 1 }
        public var regionIsSet: Bool { typeCode != 0 && disabledMask != 0xff }
        public var regions: [Int] { Region.regions(fromDisabledMask: disabledMask) }
    }

    public enum Compatibility: Equatable, Sendable {
        case compatible
        case regionFree              // drive is RPC-1 or the disc plays everywhere
        case driveRegionNotSet
        case mismatch(drive: [Int], disc: [Int])
    }

    public static func compatibility(drive: DriveState?, discDisabledMask: Int) -> Compatibility {
        let disc = regions(fromDisabledMask: discDisabledMask)
        guard let drive, drive.isRPC2 else { return .regionFree }
        if disc.count == 8 { return .regionFree }
        if !drive.regionIsSet { return .driveRegionNotSet }
        let overlap = Set(drive.regions).intersection(disc)
        return overlap.isEmpty ? .mismatch(drive: drive.regions, disc: disc) : .compatible
    }
}
