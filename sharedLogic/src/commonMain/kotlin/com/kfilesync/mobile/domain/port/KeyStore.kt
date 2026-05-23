package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.model.DeviceId

/**
 * Platform-secured key storage.
 * Adapter: AndroidKeyStoreAdapter (androidMain) / IosKeychainAdapter (iosMain).
 */
interface KeyStore {
    fun storePrivateKey(id: DeviceId, key: ByteArray)
    fun loadPrivateKey(id: DeviceId): ByteArray
    fun deletePrivateKey(id: DeviceId)
}