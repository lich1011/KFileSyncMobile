import SwiftUI
import SharedUI

struct ContentView: View {
    @State private var showContent = false
    var body: some View {
        ComposeView().ignoresSafeArea(.keyboard)
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController{
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
