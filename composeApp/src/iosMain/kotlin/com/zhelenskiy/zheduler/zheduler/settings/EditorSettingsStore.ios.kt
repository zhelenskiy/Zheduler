package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSHomeDirectory

actual fun createEditorSettingsStore(): KStore<EditorSettings> {
    val homeDir = NSHomeDirectory()
    val dataDir = "$homeDir/Library/Application Support/Zheduler"
    val dirPath = Path(dataDir)
    if (!SystemFileSystem.exists(dirPath)) {
        SystemFileSystem.createDirectories(dirPath)
    }
    val filePath = Path("$dataDir/editor_settings.json")
    return storeOf(filePath, default = EditorSettings())
}
