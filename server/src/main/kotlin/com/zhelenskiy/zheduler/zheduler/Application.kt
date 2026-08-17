package com.zhelenskiy.zheduler.zheduler

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    // Loopback while this is a stub with no authentication of any kind. Whoever gives it a real
    // endpoint can widen it deliberately, rather than discovering it was already listening to the
    // network.
    embeddedServer(Netty, port = SERVER_PORT, host = "127.0.0.1", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    routing {
        get("/") {
            call.respondText("Zheduler Server - API endpoints not yet implemented")
        }
    }
}
