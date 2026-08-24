package com.zhelenskiy.zheduler.zheduler.sync

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSHomeDirectory

actual fun createRemoteSpaceLinkStore(): KStore<SyncSettings> =
    storeOf(Path("${syncDataDir()}/remote_spaces.json"), default = SyncSettings())

/**
 * The token file, in the app's Application Support directory.
 *
 * The sandbox keeps other apps out, and iOS Data Protection encrypts the file whenever the device
 * is locked, which covers the case a Keychain item would otherwise be covering. What the Keychain
 * would add is protection while the device is unlocked and a backup that does not carry the token
 * off the device; that is the next step here, not something this file pretends to do.
 */
actual fun createCredentialStore(): KStore<StoredCredentials> =
    storeOf(Path("${syncDataDir()}/remote_credentials.json"), default = StoredCredentials())

private fun syncDataDir(): String {
    val dataDir = "${NSHomeDirectory()}/Library/Application Support/Zheduler"
    val dirPath = Path(dataDir)
    if (!SystemFileSystem.exists(dirPath)) {
        SystemFileSystem.createDirectories(dirPath)
    }
    return dataDir
}
