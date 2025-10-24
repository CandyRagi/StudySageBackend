package com.group_7

import com.group_7.routes.userRoutes  // ← lowercase 'u'
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        userRoutes()  // ← lowercase 'u'
    }
}