# 5. Technology Choices

[< Back to Overview](00-overview.md)

| Layer | Choice | Rationale |
| :--- | :--- | :--- |
| Cross-Platform Framework | Kotlin Multiplatform (KMP) | Shares business logic layer; each platform can use independent UI or Compose Multiplatform. Kotlin coroutines are naturally suited for async network I/O. |
| UI Framework | Compose Multiplatform | JetBrains' officially recommended declarative UI solution for KMP; shares UI code across Android/iOS. Native Compose experience on Android; rendered via Compose for iOS on iOS. |
| Networking | Ktor Client + Ktor Server | Native KMP support. Ktor Server provides the mobile HTTPS endpoint (receives pairing/transfer/sync requests); Ktor Client issues requests to peers. |
| TLS | Ktor TLS + Platform APIs | Android: custom TrustManager + keyStore; iOS: Security.framework + custom URLSession delegate. |
| Local Database | SQLDelight | Type-safe SQL library with native KMP support; generates Kotlin data classes; supports WAL mode. |
| Dependency Injection | Koin | Lightweight KMP DI framework well-suited for assembling hexagonal architecture dependencies. |
| Serialization | kotlinx.serialization | Official KMP serialization solution for JSON encoding/decoding. |
| Date/Time | kotlinx-datetime | Cross-platform KMP date/time library. |
| Hashing | kotlinx-crypto (BLAKE3) + Platform SHA-256 | Chunk-level BLAKE3 via expect/actual wrapping platform implementations; file-level SHA-256 via java.security.MessageDigest / CommonCrypto. |
| Coroutines | kotlinx.coroutines | Native KMP async solution, replacing Rust's tokio. |
| File Selection | Platform APIs | Android: SAF (Storage Access Framework) / MediaStore; iOS: UIDocumentPickerViewController. |
| mDNS Discovery | Platform APIs | Android: NsdManager; iOS: NWBrowser (Network.framework). |
| File Watching | Platform APIs | Android: FileObserver / ContentObserver; iOS: DispatchSource.makeFileSystemObjectSource. |
| Background Tasks | Platform APIs | Android: WorkManager + Foreground Service; iOS: BGTaskScheduler + URLSession background transfer. |
| Key Storage | Platform APIs | Android: AndroidKeyStore; iOS: Keychain Services. |
| Logging | Napier | Cross-platform KMP logging library with structured logging support. |
| Image Loading | Coil 3 | Cross-platform KMP image loading library for file thumbnails. |
| Navigation | Compose Navigation (Voyager or Decompose) | Cross-platform KMP navigation framework supporting multi-stack navigation. |