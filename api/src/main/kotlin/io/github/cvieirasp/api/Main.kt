package io.github.cvieirasp.api

import io.github.cvieirasp.api.plugins.configureDatabase
import io.github.cvieirasp.api.plugins.configureRouting
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureStatusPages()
    configureDatabase()
    configureRouting()
}
