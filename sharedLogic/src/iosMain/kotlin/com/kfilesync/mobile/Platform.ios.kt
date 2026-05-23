package com.kfilesync.mobile

import platform.UIKit.UIDevice

/** iOS `actual` for the platform `expect` in commonMain. */
private class IosPlatform : Platform {

    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    override val platformId: String = "ios"
}

actual fun currentPlatform(): Platform = IosPlatform()