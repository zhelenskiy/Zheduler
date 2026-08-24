package com.zhelenskiy.zheduler.zheduler.sync

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import java.io.File

actual fun createRemoteSpaceLinkStore(): KStore<SyncSettings> =
    storeOf(Path("${syncDataDir()}/remote_spaces.json"), default = SyncSettings())

/**
 * The token file, in the app's own data directory.
 *
 * That directory is private to this app's user id — no other app can read it without root — which
 * is the protection a keystore would otherwise be adding to. What a keystore would add on top is
 * encryption at rest against someone with the device's storage in hand, and it costs a dependency
 * that has since been deprecated; the token expires and can be revoked from any other device,
 * which is the mitigation that matters for that case.
 */
actual fun createCredentialStore(): KStore<StoredCredentials> =
    storeOf(Path("${syncDataDir()}/remote_credentials.json"), default = StoredCredentials())

private fun syncDataDir(): String {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return dataDir
}
