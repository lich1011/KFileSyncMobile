import SwiftUI
import UIKit
import UniformTypeIdentifiers
import SharedUI

/// Swift glue for the iOS file / folder pickers.
///
/// Registers presentation closures on the Kotlin `IosPickerBridge` (exported
/// in the `SharedUI` framework). When shared code calls `pickFiles()` /
/// `pickDirectory()`, the Kotlin facade drains its request flow and invokes
/// these closures on the main thread; we present the system document picker
/// and report the result back via `completeFiles` / `completeDirectory`.
///
/// Strategy:
/// - Files (send): present with `asCopy: true`. iOS hands us a temp copy
///   inside our own sandbox, so the resulting `file://` URL is freely
///   readable with no security-scoped-resource dance.
/// - Folder (share destination): present `.folder`. We must
///   `startAccessingSecurityScopedResource()` and *hold* that access for the
///   process lifetime (kept in `heldScopes`) so later writes succeed.
///
/// Interop notes (adjust if the Kotlin/Native bridge surfaces different
/// signatures): the Kotlin `var onPickFiles: ((Boolean) -> Unit)?` may appear
/// to Swift as `((KotlinBoolean) -> Void)?`; `completeFiles` takes a Kotlin
/// `List<PlatformFile>` which bridges from a Swift `[PlatformFile]`.
final class KFileSyncBridge: NSObject {

    static let shared = KFileSyncBridge()

    /// Folder URLs whose security scope we keep open for the process lifetime.
    private var heldScopes: [URL] = []
    /// Keep delegates alive while a picker is on screen.
    private var liveDelegates: [PickerDelegate] = []

    /// Call once from `iOSApp.init`, after `iosBootstrap()`.
    func register() {
        IosPickerBridge.shared.onPickFiles = { [weak self] allowMultiple in
            // `allowMultiple` may be `KotlinBoolean`; `.boolValue` unwraps it.
            let multi = (allowMultiple as? NSNumber)?.boolValue ?? true
            DispatchQueue.main.async { self?.presentFilePicker(allowMultiple: multi) }
        }
        IosPickerBridge.shared.onPickDirectory = { [weak self] in
            DispatchQueue.main.async { self?.presentFolderPicker() }
        }
    }

    // MARK: - File picker (send)

    private func presentFilePicker(allowMultiple: Bool) {
        guard let top = Self.topViewController() else {
            IosPickerBridge.shared.completeFiles(files: [])
            return
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = allowMultiple
        let delegate = PickerDelegate(
            onPicked: { [weak self] urls in
                let files = urls.map { url -> PlatformFile in
                    let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
                    return PlatformFile(
                        displayName: url.lastPathComponent,
                        sizeBytes: size ?? 0,
                        locator: url.absoluteString,
                        mimeType: nil
                    )
                }
                IosPickerBridge.shared.completeFiles(files: files)
                self?.retireDelegate()
            },
            onCancelled: { [weak self] in
                IosPickerBridge.shared.completeFiles(files: [])
                self?.retireDelegate()
            }
        )
        picker.delegate = delegate
        liveDelegates.append(delegate)
        top.present(picker, animated: true)
    }

    // MARK: - Folder picker (share destination)

    private func presentFolderPicker() {
        guard let top = Self.topViewController() else {
            IosPickerBridge.shared.completeDirectory(locator: nil)
            return
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.allowsMultipleSelection = false
        let delegate = PickerDelegate(
            onPicked: { [weak self] urls in
                guard let url = urls.first else {
                    IosPickerBridge.shared.completeDirectory(locator: nil)
                    self?.retireDelegate()
                    return
                }
                // Hold security scope for the process lifetime so subsequent
                // sync writes into this folder succeed.
                if url.startAccessingSecurityScopedResource() {
                    self?.heldScopes.append(url)
                }
                IosPickerBridge.shared.completeDirectory(locator: url.absoluteString)
                self?.retireDelegate()
            },
            onCancelled: { [weak self] in
                IosPickerBridge.shared.completeDirectory(locator: nil)
                self?.retireDelegate()
            }
        )
        picker.delegate = delegate
        liveDelegates.append(delegate)
        top.present(picker, animated: true)
    }

    private func retireDelegate() {
        if !liveDelegates.isEmpty { liveDelegates.removeFirst() }
    }

    // MARK: - Helpers

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
        let window = scenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var vc = window?.rootViewController
        while let presented = vc?.presentedViewController { vc = presented }
        return vc
    }
}

/// Reusable `UIDocumentPickerDelegate` that forwards to closures.
private final class PickerDelegate: NSObject, UIDocumentPickerDelegate {
    private let onPicked: ([URL]) -> Void
    private let onCancelled: () -> Void

    init(onPicked: @escaping ([URL]) -> Void, onCancelled: @escaping () -> Void) {
        self.onPicked = onPicked
        self.onCancelled = onCancelled
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        onPicked(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}