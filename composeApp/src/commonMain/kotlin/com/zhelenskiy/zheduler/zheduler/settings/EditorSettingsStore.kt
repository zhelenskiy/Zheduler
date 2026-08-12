package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore

/**
 * Creates a KStore for persisting editor settings.
 * Implementation differs by platform:
 * - Desktop/Android/iOS: Uses file-based storage via kstore-file
 * - Web (JS/WasmJS): Uses localStorage via kstore-storage
 */
expect fun createEditorSettingsStore(): KStore<EditorSettings>
