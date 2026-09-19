import SwiftUI
import SharedUI

@main
struct iOSApp: App {

    init() {
        // Start the shared DI graph + servers (Koin, TLS listener, loopback
        // Ktor, discovery, heartbeat, crash recovery, BGTaskScheduler...).
        // Previously this was never called, so on iOS the app launched into a
        // template screen with no backend running at all.
        IosBootstrapKt.iosBootstrap()
        // Connect the Swift document-picker glue to the Kotlin picker bridge
        // (must run after iosBootstrap installed the request collectors).
        KFileSyncBridge.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}