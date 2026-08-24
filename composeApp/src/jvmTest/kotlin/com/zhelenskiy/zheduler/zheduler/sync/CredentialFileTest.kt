package com.zhelenskiy.zheduler.zheduler.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The credentials file on desktop, as the app really makes it.
 *
 * This is the seam every other sync test steps over: the suites elsewhere hand the service an
 * in-memory store, so nothing exercised the file the desktop build actually opens. The first
 * sign-up on a real machine then failed on it — the file was created empty so its permissions
 * could be set, and an empty file is not JSON.
 */
class CredentialFileTest {

    private val temp: File = Files.createTempDirectory("zheduler-credentials").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** Exactly what the desktop app builds, over a file this test owns. */
    private fun storeOver(file: File) = credentialStoreOver(file)

    private fun roundTrip(file: File): StoredCredentials? = runBlocking {
        storeOver(file).update { current ->
            (current ?: StoredCredentials()).let {
                it.copy(tokensByAccount = it.tokensByAccount + ("server|ada" to "a-token"))
            }
        }
        // A second store over the same file, so what is asserted came off the disk rather than
        // out of the first store's cache.
        storeOver(file).get()
    }

    @Test
    fun `a token can be stored on a machine that has never had this file`() {
        val file = File(temp, "remote_credentials.json")
        assertEquals(mapOf("server|ada" to "a-token"), roundTrip(file)?.tokensByAccount)
    }

    @Test
    fun `a token can be stored when the file was left empty by an earlier version`() {
        // This is the state the bug left behind: created so its mode could be set, never written.
        // Reading it threw "Expected start of the object '{'", which is what a first sign-up hit.
        val file = File(temp, "remote_credentials.json")
        file.writeBytes(ByteArray(0))
        assertEquals(0, file.length())

        assertEquals(mapOf("server|ada" to "a-token"), roundTrip(file)?.tokensByAccount)
    }

    @Test
    fun `an existing file's tokens are not thrown away`() {
        val file = File(temp, "remote_credentials.json")
        runBlocking {
            storeOver(file).set(StoredCredentials(mapOf("server|bob" to "bob-token")))
        }

        // Building the store again is what every launch after the first does.
        assertEquals(
            mapOf("server|bob" to "bob-token"),
            runBlocking { storeOver(file).get() }?.tokensByAccount,
        )
    }

    @Test
    fun `the directory the token lives in is owner-only`() {
        // The file's own mode cannot be relied on: kstore writes a temp file and renames it over
        // the target, so the token always lands in a file created with the process umask. The
        // directory is what keeps other users away from both it and the temp file.
        val directory = File(temp, "data").also { it.mkdirs() }
        val file = File(directory, "remote_credentials.json")
        roundTrip(file)

        val path = directory.toPath()
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertTrue(
            Files.getPosixFilePermissions(path).none {
                it.name.startsWith("GROUP") || it.name.startsWith("OTHERS")
            },
            "others can enter the directory holding the token: " + Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun `the file is readable by its owner alone`() {
        val file = File(temp, "remote_credentials.json")
        prepareCredentialFile(file)

        val path = file.toPath()
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun `the token is still owner-only after it has been written`() {
        // The mode has to survive kstore's own writes, or the protection lasts until first use.
        val file = File(temp, "remote_credentials.json")
        roundTrip(file)

        val path = file.toPath()
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertTrue(
            Files.getPosixFilePermissions(path).none {
                it.name.startsWith("GROUP") || it.name.startsWith("OTHERS")
            },
            "the credentials file ended up readable by others: " + Files.getPosixFilePermissions(path),
        )
    }
}
