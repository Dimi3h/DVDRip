// swift-tools-version: 6.0
import Foundation
import PackageDescription

let nativeLib = Context.packageDirectory + "/native/out/lib"

// With only the Command Line Tools installed (no Xcode), SwiftPM does not find Swift Testing by
// itself: point the compiler at its framework and macro plugin explicitly.
let clt = "/Library/Developer/CommandLineTools"
let cltTesting = clt + "/Library/Developer/Frameworks"
let usesCLT = FileManager.default.fileExists(atPath: cltTesting + "/Testing.framework")
    && !FileManager.default.fileExists(atPath: "/Applications/Xcode.app")
let testSwiftSettings: [SwiftSetting] = [.swiftLanguageMode(.v5)] + (usesCLT ? [
    .unsafeFlags(["-F", cltTesting, "-plugin-path", clt + "/usr/lib/swift/host/plugins/testing"]),
] : [])
let testLinkerSettings: [LinkerSetting] = usesCLT ? [
    .unsafeFlags(["-F", cltTesting, "-framework", "Testing", "-Xlinker", "-rpath", "-Xlinker", cltTesting,
                  "-Xlinker", "-rpath", "-Xlinker", clt + "/Library/Developer/usr/lib"]),
] : []

let package = Package(
    name: "DiscRip",
    defaultLocalization: "nl",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "DiscRip", targets: ["DiscRip"]),
    ],
    targets: [
        .target(
            name: "CDiscRip",
            path: "Sources/CDiscRip",
            cSettings: [
                .headerSearchPath("../../native/out/include"),
                .unsafeFlags(["-O2", "-Wno-deprecated-declarations"]),
            ],
            linkerSettings: [
                .unsafeFlags(["-L" + nativeLib]),
                .linkedLibrary("dvdread"),
                .linkedLibrary("dvdcss"),
                .linkedLibrary("avformat"),
                .linkedLibrary("avfilter"),
                .linkedLibrary("avcodec"),
                .linkedLibrary("swscale"),
                .linkedLibrary("swresample"),
                .linkedLibrary("avutil"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreServices"),
                .linkedFramework("IOKit"),
            ]
        ),
        .target(
            name: "DiscRipCore",
            dependencies: ["CDiscRip"],
            path: "Sources/DiscRipCore",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("DiskArbitration"),
                .linkedFramework("NetFS"),
                .linkedFramework("Security"),
            ]
        ),
        .executableTarget(
            name: "DiscRip",
            dependencies: ["DiscRipCore"],
            path: "Sources/DiscRip",
            exclude: ["Resources"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "discrip-cli",
            dependencies: ["DiscRipCore"],
            path: "Sources/discrip-cli",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // Test suites are plain library targets so that DiscRipTestRunner can run them with only the
        // Command Line Tools installed (`swift run DiscRipTestRunner`).
        .target(
            name: "DiscRipCoreTests",
            dependencies: ["DiscRipCore"],
            path: "Tests/DiscRipCoreTests",
            resources: [.copy("Fixtures")],
            swiftSettings: testSwiftSettings,
            linkerSettings: testLinkerSettings
        ),
        .target(
            name: "DiscRipIntegrationTests",
            dependencies: ["DiscRipCore", "CDiscRip"],
            path: "Tests/DiscRipIntegrationTests",
            swiftSettings: testSwiftSettings,
            linkerSettings: testLinkerSettings
        ),
        .executableTarget(
            name: "DiscRipTestRunner",
            dependencies: ["DiscRipCoreTests", "DiscRipIntegrationTests"],
            path: "Tests/Runner",
            swiftSettings: testSwiftSettings,
            linkerSettings: testLinkerSettings
        ),
    ]
)
