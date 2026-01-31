package com.zhelenskiy.zheduler.zheduler

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform