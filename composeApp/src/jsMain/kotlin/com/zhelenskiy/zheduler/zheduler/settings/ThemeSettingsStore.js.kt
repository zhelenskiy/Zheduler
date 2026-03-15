package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf

actual fun createThemeSettingsStore(): KStore<ThemeSettings> {
    return storeOf(key = "theme_settings", default = ThemeSettings())
}
