package com.zhelenskiy.zheduler.zheduler.sync

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

actual fun createRemoteSpaceLinkStore(): KStore<RemoteSpaceLinks> =
    storeOf(Path("${syncDataDir()}/remote_spaces.json"), default = RemoteSpaceLinks())

actual fun createCredentialStore(): KStore<StoredCredentials> {
    val file = File(syncDataDir(), "remote_credentials.json")
    restrictToOwner(file)
    return storeOf(Path(file.path), default = StoredCredentials())
}

private fun syncDataDir(): String {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return dataDir
}

/**
 * Makes the credentials file readable by its owner alone.
 *
 * A desktop home directory is not private the way an app sandbox is — another account on the same
 * machine, or a backup tool running as someone else, can read a world-readable file. The file has
 * to exist before its mode can be set, so an empty one is created first; kstore writes into it
 * afterwards without changing the mode.
 *
 * Silently skipped where the filesystem has no POSIX permissions, which is every Windows install:
 * there the file inherits the user profile's own protection, and there is nothing better to ask
 * for without taking on a native dependency.
 */
private fun restrictToOwner(file: File) {
    runCatching {
        if (!file.exists()) file.createNewFile()
        val path = file.toPath()
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
