package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf

actual fun createEditorSettingsStore(): KStore<EditorSettings> {
    return storeOf(key = "editor_settings", default = EditorSettings())
}
