package com.kfilesync.mobile

import android.os.Build

/** Android `actual` for the platform-id `expect` in commonMain. */
private class AndroidPlatform : Platform {

    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    override val platformId: String = "android"
}

actual fun currentPlatform(): Platform = AndroidPlatform()