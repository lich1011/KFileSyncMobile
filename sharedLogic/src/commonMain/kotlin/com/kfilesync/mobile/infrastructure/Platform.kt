package com.kfilesync.mobile

/**
 * Tiny `expect/actual` exposing a few host-OS strings to the shared layer.
 *
 * Used by the Phase 0 PoC screen to confirm both runtimes can reach into
 * platform-specific APIs through the KMP hierarchy. Real platform adapters
 * (KeyStore, Discovery, etc.) sit in their own parts - this exists purely
 * so the demo screen can render "running on Android 36" / "iOS 18.2".
 */
interface Platform {
    /** Human-readable label, e.g. "Android 36" or "iOS 18.2". */
    val name: String

    /** Stable identifier the wire protocol uses: "android" | "ios" | "desktop". */
    val platformId: String
}

expect fun currentPlatform(): Platform