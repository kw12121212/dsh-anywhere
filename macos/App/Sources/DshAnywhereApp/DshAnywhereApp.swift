import DshCore
import SwiftUI

@main
struct DshAnywhereApp: App {
    @State private var model = AppSessionModel()

    var body: some Scene {
        WindowGroup(AppIdentity.displayName) {
            ContentView(model: model)
        }
        .defaultSize(width: 960, height: 640)
    }
}
