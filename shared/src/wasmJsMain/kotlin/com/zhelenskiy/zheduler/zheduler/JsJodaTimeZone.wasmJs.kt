package com.zhelenskiy.zheduler.zheduler

import kotlin.js.JsModule

@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

// Initialize timezone support for kotlinx-datetime
private val jsJodaTz = JsJodaTimeZoneModule
