import DshCore
import SwiftUI

@main
struct DshAnywhereApp: App {
    var body: some Scene {
        WindowGroup(AppIdentity.displayName) {
            ContentView()
        }
        .defaultSize(width: 960, height: 640)
    }
}
