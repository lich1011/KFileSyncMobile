package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.KeyStore

/**
 * Keychain-backed [KeyStore] adapter for iOS.
 *
 * Phase 1 (T1.1): uses `Security.framework` via cinterop:
 * storePrivateKey -> SecItemAdd with kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
 * loadPrivateKey  -> SecItemCopyMatching
 * deletePrivateKey -> SecItemDelete
 *
 * The device id is encoded as the kSecAttrApplicationLabel.
 */
class IosKeychainAdapter : KeyStore {

    override fun storePrivateKey(id: DeviceId, key: ByteArray): Unit = TODO("Phase 1 T1.1")

    override fun loadPrivateKey(id: DeviceId): ByteArray = TODO("Phase 1 T1.1")

    override fun deletePrivateKey(id: DeviceId): Unit = TODO("Phase 1 T1.1")
}