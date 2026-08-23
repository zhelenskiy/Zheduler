package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf

actual fun createLocationSettingsStore(): KStore<LocationSettings> {
    return storeOf(key = "location_settings", default = LocationSettings())
}
