import DshCore
import SwiftUI

struct ContentView: View {
    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "network")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(AppIdentity.displayName)
                .font(.title2.weight(.semibold))
            Text("版本 \(appVersion)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("macOS 原生客户端工程骨架已就绪")
                .font(.callout)
                .foregroundStyle(.tertiary)
        }
        .padding(32)
        .frame(minWidth: 480, minHeight: 320)
    }
}
