import SwiftUI

/// SwiftUI's `State` property wrapper under another name.
///
/// In the macOS 27 SDK `@State` resolves to a macro whose compiler plugin ships with Xcode only,
/// so with just the Command Line Tools `@State` does not compile. The property wrapper itself is
/// still there; referring to it through a typealias bypasses the macro. Works with Xcode too.
typealias ViewState<Value> = SwiftUI.State<Value>
