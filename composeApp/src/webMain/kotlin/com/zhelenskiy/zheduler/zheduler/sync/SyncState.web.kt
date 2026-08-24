package com.zhelenskiy.zheduler.zheduler.sync

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf

actual fun createRemoteSpaceLinkStore(): KStore<SyncSettings> =
    storeOf(key = "remote_spaces", default = SyncSettings())

/**
 * The token, in the browser's local storage.
 *
 * There is nowhere better in a page: local storage is what the origin has, and it is readable by
 * any script the origin runs. That is why the token expires and can be revoked — a page cannot
 * keep a secret from itself, so the secret is made cheap to replace instead.
 */
actual fun createCredentialStore(): KStore<StoredCredentials> =
    storeOf(key = "remote_credentials", default = StoredCredentials())
