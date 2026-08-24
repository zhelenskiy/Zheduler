package com.zhelenskiy.zheduler.zheduler.sync

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.Codec
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.FileCodec
import io.github.xxfast.kstore.file.storeOf
import io.github.xxfast.kstore.storeOf
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

actual fun createRemoteSpaceLinkStore(): KStore<RemoteSpaceLinks> =
    storeOf(Path("${syncDataDir()}/remote_spaces.json"), default = RemoteSpaceLinks())

actual fun createCredentialStore(): KStore<StoredCredentials> =
    credentialStoreOver(File(syncDataDir(), "remote_credentials.json"))

/**
 * The credentials store over one particular file.
 *
 * Split out from [createCredentialStore] so a test can drive exactly this — the seeding, the
 * permissions and the codec — against a temporary file, rather than the app's real data directory.
 */
internal fun credentialStoreOver(file: File): KStore<StoredCredentials> {
    prepareCredentialFile(file)
    return storeOf(
        codec = OwnerOnlyFileCodec(FileCodec(Path(file.path)), file),
        default = StoredCredentials(),
    )
}

private fun syncDataDir(): String {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    val directory = File(dataDir)
    directory.mkdirs()
    // The directory, not just the token file. kstore writes through a temp file and renames it
    // into place, so the file the token lands in is always a freshly created one wearing the
    // process umask — its own mode cannot be made to stick, and the temp file holds the token
    // too. A directory nobody else may enter covers both.
    restrictToOwner(directory)
    return dataDir
}

/**
 * Makes sure the credentials file says something kstore can read, and is owner-only.
 *
 * The order matters and so does the seeding. Setting a file's mode needs the file to exist, so an
 * empty one was created — and an empty file is not JSON, so the next thing to open the store threw
 * `Expected start of the object '{'`. On a fresh machine that was the first sign-up: the account
 * was created on the server and the app reported a parse error.
 *
 * Seeding it with the empty value fixes that, and repairs a file an earlier build already left at
 * zero bytes rather than leaving those installs permanently unable to sign in.
 */
internal fun prepareCredentialFile(file: File) {
    runCatching {
        file.parentFile?.let { parent ->
            parent.mkdirs()
            restrictToOwner(parent)
        }
        if (!file.exists()) file.createNewFile()
        // Restricted before anything is written, so no secret is ever briefly world-readable.
        restrictToOwner(file)
        if (file.length() == 0L) file.writeText(EMPTY_CREDENTIALS_JSON)
    }
}

/**
 * What an empty [StoredCredentials] looks like on disk.
 *
 * Encoded rather than written out by hand, so a field added to the class cannot leave this seed
 * saying something the class can no longer read.
 */
private val EMPTY_CREDENTIALS_JSON: String = Json.encodeToString(StoredCredentials())

/**
 * Puts the owner-only mode back after every write.
 *
 * kstore encodes into `<file>.temp` and renames it over the target, so the token always ends up in
 * a file created moments earlier with the process umask — 0644 on a normal desktop. The directory
 * is what actually keeps other users out; this keeps the file itself right as well, so the
 * protection does not rest on one thing alone.
 */
private class OwnerOnlyFileCodec<T : @Serializable Any>(
    private val delegate: Codec<T>,
    private val file: File,
) : Codec<T> {

    override suspend fun decode(): T? = delegate.decode()

    override suspend fun encode(value: T?) {
        delegate.encode(value)
        restrictToOwner(file)
    }
}

/**
 * Makes a file or directory reachable by its owner alone.
 *
 * A desktop home directory is not private the way an app sandbox is — another account on the same
 * machine, or a backup tool running as someone else, can read a world-readable file.
 *
 * Silently skipped where the filesystem has no POSIX permissions, which is every Windows install:
 * there it inherits the user profile's own protection, and there is nothing better to ask for
 * without taking on a native dependency.
 */
private fun restrictToOwner(target: File) {
    runCatching {
        val path = target.toPath()
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return@runCatching
        val permissions = if (target.isDirectory) {
            // A directory also needs "execute" — that is what permits entering it at all.
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        Files.setPosixFilePermissions(path, permissions)
    }
}
