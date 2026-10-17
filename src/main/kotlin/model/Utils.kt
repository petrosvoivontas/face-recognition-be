package com.example.model

import io.ktor.server.application.Application
import io.ktor.server.routing.RoutingContext
import io.ktor.util.logging.Logger

fun Application.getLogger(): Logger = environment.log
fun RoutingContext.getLogger(): Logger = call.application.getLogger()