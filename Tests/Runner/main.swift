// Runs the Swift Testing suites without Xcode: with only the Command Line Tools, `swift test`
// does not discover Swift Testing tests, so this executable calls the Swift Testing entry point.
// Usage: swift run DiscRipTestRunner [--filter <name>]
import Testing
import DiscRipCoreTests
import DiscRipIntegrationTests

_ = DiscRipCoreTestsLinked.self
_ = DiscRipIntegrationTestsLinked.self
await Testing.__swiftPMEntryPoint() as Never
