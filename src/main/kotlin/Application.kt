package com.example

import io.ktor.server.application.*

suspend fun Application.module() {
    configureCors()
    configureSerialization()
    configureRouting()
}
