package com.zhelenskiy.zheduler.zheduler.settings

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import java.io.File

actual fun createEditorSettingsStore(): KStore<EditorSettings> {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    val filePath = Path("$dataDir/editor_settings.json")
    return storeOf(filePath, default = EditorSettings())
}
