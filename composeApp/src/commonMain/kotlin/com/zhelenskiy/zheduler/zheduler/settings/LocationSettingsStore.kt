package com.zhelenskiy.zheduler.zheduler.settings

import io.github.xxfast.kstore.KStore

/** Its own file, alongside the theme's, and created the same way on each platform. */
expect fun createLocationSettingsStore(): KStore<LocationSettings>
