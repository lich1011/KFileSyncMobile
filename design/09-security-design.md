# 9. Security Design

[< Back to Overview](00-overview.md)

---

## 9.1 Security Model Consistent with Desktop

The mobile client fully reuses the desktop's zero-trust security model:

| Layer | Mechanism | Mobile Implementation |
| --- | --- | --- |
| Transport Encryption | TLS 1.3 | Ktor + platform TLS engine |
| Device Authentication | Self-signed certificate + SHA-256 fingerprint pinning | Custom TrustManager / URLSession delegate |
| Pairing Authorization | PIN confirmation on both ends | UI dialog confirmation |
| Share Authorization | PolicyEnforcer unified authorization | Shared implementation in commonMain |
| Integrity Verification | Chunk-level BLAKE3 + file-level SHA-256 | expect/actual platform implementations |
| Anti-Replay | timestamp + nonce | Shared implementation in commonMain |

## 9.2 Mobile-Specific Security Considerations

| Consideration | Strategy |
| --- | --- |
| Application Sandbox | Leverages the native Android/iOS app sandbox for isolation; private keys and database reside within the sandbox |
| Key Storage | Android: AndroidKeyStore (hardware-backed, never extractable). iOS: Keychain with stable `kSecAttrApplicationTag` (Phase 1 ships Keychain-resident; opt-in Secure Enclave via `kSecAttrTokenID` is a Phase 5 hardening choice). |
| Biometric Authentication | Optional: first-time pairing / trust revocation operations can require fingerprint/Face ID confirmation |
| App Lock Screen | Optional: app-level PIN/biometric protection |
| Clipboard Security | Certificate fingerprints and PINs are never automatically copied to the clipboard |

## 9.3 TLS Configuration (Mobile)

Phase 1 ships *symmetric* zero-trust on both platforms — the trust-store data (paired-device PEMs and their SHA-256 fingerprints) lives in the common `devices` table, and both the client-side and server-side TLS machinery consults the same data to decide whether a handshake is acceptable.

### 9.3.1 Android — Ktor CIO `sslConnector` + custom `TrustManager`

```kotlin
// androidMain - custom TrustManager implementing certificate pinning.
// Used both for the OkHttp client engine (via SSLContext) and as a hook for
// future client-cert mTLS on the server side.
class PinningTrustManager(
    private val deviceRepository: DeviceRepository
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leafFingerprint = sha256Hex(chain[0].encoded)
        val pinned = pinnedFingerprints() // SHA-256 of every Paired device's cert PEM

        if (pinned.isEmpty()) {
            // Bootstrap window: no devices paired yet, so the pairing
            // handshake itself needs to go through. Window closes the moment
            // the first PairingCompleted event lands.
            return
        }
        if (leafFingerprint !in pinned) {
            throw CertificateException("Untrusted certificate: $leafFingerprint")
        }
    }

    // ...all other X509ExtendedTrustManager overloads delegate to the same check
}

// Android server-side TLS via Ktor CIO's sslConnector.
// The AndroidKeyStore-backed JVM KeyStore proxies signing calls to the TEE,
// so the private key never enters user space.
fun startHttpsServer(tlsConfig: HttpServerTlsConfig, port: Int, host: String) {
    embeddedServer(CIO, environment = applicationEnvironment {}, configure = {
        sslConnector(
            keyStore = tlsConfig.keyStore,                  // KeyStore.getInstance("AndroidKeyStore")
            keyAlias = tlsConfig.keyAlias,                  // "kfilesync:local"
            keyStorePassword = { CharArray(0) },            // no-op on AndroidKeyStore
            privateKeyPassword = { CharArray(0) }
        ) {
            this.host = host
            this.port = port
        }
    }, module = { /* install plugins + routing */ })
}

// Android client-side: wrap the OkHttp engine with the pinning TrustManager.
fun pinnedHttpClientEngine(deviceRepository: DeviceRepository): HttpClientEngineFactory<OkHttpConfig> {
    val trustManager = PinningTrustManager(deviceRepository)
    val sslContext = SSLContext.getInstance("TLSv1.3").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    return object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit) = OkHttp.create {
            block()
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
            }
        }
    }
}

```

---

### 9.3.2 iOS — `nw_listener` sidecar + Darwin `handleChallenge`

iOS server-side TLS uses a *sidecar* pattern: Ktor CIO Native binds plaintext on loopback (127.0.0.1:53318), and a Network.framework `nw_listener` terminates TLS on the public port (53317) with the Keychain-resident SecIdentity, then pumps decrypted bytes to the loopback engine. The reason: Ktor CIO Native doesn't expose `sslConnector`, and we must keep the private key Keychain/Enclave-resident — Apple's official path for that is `sec_protocol_options_set_local_identity`. Loopback is only reachable inside the app sandbox.

```kotlin
// iosMain - Darwin client-side challenge handler (mirrors PinningTrustManager).
@OptIn(ExperimentalForeignApi::class)
class IosPinningChallengeHandler(
    private val deviceRepository: DeviceRepository
) {
    fun handle(
        challenge: NSURLAuthenticationChallenge,
        completionHandler: (URLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
    ) {
        if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val trust = challenge.protectionSpace.serverTrust ?: run {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null); return
        }
        val leafDer = extractLeafDer(trust)              // SecTrustCopyCertificateChain + SecCertificateCopyData
        val leafFingerprint = sha256Hex(leafDer)
        val pinned = pinnedFingerprints()                 // blocking read from DeviceRepository.findPaired

        if (pinned.isEmpty() || leafFingerprint in pinned) {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
        } else {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }
}

// iosMain - wire the handler into Ktor Darwin's URLSessionDelegate hook.
fun pinnedHttpClientEngine(deviceRepository: DeviceRepository): HttpClientEngineFactory<DarwinClientEngineConfig> {
    val handler = IosPinningChallengeHandler(deviceRepository)
    return object : HttpClientEngineFactory<DarwinClientEngineConfig> {
        override fun create(block: DarwinClientEngineConfig.() -> Unit) = Darwin.create {
            block()
            handleChallenge { _, _, challenge, completionHandler ->
                handler.handle(challenge) { disposition, credential ->
                    completionHandler(disposition, credential)
                }
            }
        }
    }
}

// iosMain - iOS server-side TLS terminator.
// Bridges Keychain SecKey + cert PEM into a SecIdentity, then uses
// Network.framework's nw_listener for TLS 1.3 termination on the public port.
@OptIn(ExperimentalForeignApi::class)
class IosTlsListener(
    private val deviceRepository: DeviceRepository,
    private val identityProvider: LocalIdentityProvider,
    private val publicPort: Int = 53317,
    private val loopbackPort: Int = 53318
) {
    fun start() {
        val identity = identityProvider.current()
        val secIdentity = IosSecIdentityBridge.loadLocalIdentity(identity.certificatePem)
            ?: error("Cannot start TLS: no SecIdentity")

        val params = nw_parameters_create_secure_tcp(
            { tlsOpts ->
                sec_protocol_options_set_local_identity(tlsOpts, sec_identity_create(secIdentity))
                sec_protocol_options_set_min_tls_protocol_version(
                    tlsOpts, sec_protocol_version_t.tls_protocol_version_TLSv13
                )
                // Phase 2+: a sec_protocol_options_set_verify_block here gives us
                // client-cert mTLS once we want it. Phase 1 only authenticates the
                // server side.
            },
            null
        )

        nw_parameters_set_local_endpoint(params,
            nw_endpoint_create_host("0.0.0.0", publicPort.toString()))

        val listener = nw_listener_create(params)!!
        nw_listener_set_new_connection_handler(listener) { inbound ->
            // For each TLS-terminated inbound, open a loopback TCP connection
            // to the Ktor CIO engine and pump bidirectionally (64 KiB chunks).
            pumpToLoopback(inbound)
        }
        nw_listener_start(listener)
    }
}

// iosMain - bridges Keychain key + cert PEM into a SecIdentity.
// SecIdentity is a "handle pair" - the private key stays in the Keychain;
// only references travel.
object IosSecIdentityBridge {
    fun loadLocalIdentity(certificatePem: String): SecIdentityRef? {
        val privateKey = SecItemCopyMatching(
            query = mapOf(
                kSecClass to kSecClassKey,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                kSecAttrApplicationTag to LOCAL_IDENTITY_TAG.toNSData(),
                kSecAttrKeyType to kSecAttrKeyTypeEC,
                kSecReturnRef to true
            )
        ) as SecKeyRef? ?: return null

        val cert = SecCertificateCreateWithData(null, pemToDer(certificatePem).toNSData()) ?: return null
        return SecIdentityCreate(null, cert, privateKey)
    }
}

```

---

### 9.3.3 What both platforms guarantee

Externally, peers see a single TLS-1.3-only endpoint on port 53317 backed by the device's self-signed cert. The cert's SHA-256 is the `DeviceId`, which peers learn at pairing time (via the cert-exchange round-trip in `POST /pair/confirm`) and pin from then on.

| Direction | Android does | iOS does | Private key stays |
| --- | --- | --- | --- |
| Inbound TLS | Ktor CIO sslConnector + AndroidKeyStore-backed JVM KeyStore | nw_listener + sec_protocol_options_set_local_identity (sidecar to loopback Ktor) | TEE / Keychain |
| Outbound TLS | OkHttp + PinningTrustManager + SSLContext("TLSv1.3") | Darwin/URLSession + handleChallenge + IosPinningChallengeHandler | n/a (verifying peer) |
| Trust source | DeviceRepository.findPaired() PEM fingerprints | same | shared devices table |