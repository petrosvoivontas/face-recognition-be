package com.example.model

import io.ktor.server.routing.RoutingContext
import io.ktor.util.logging.Logger

fun RoutingContext.getLogger(): Logger = call.application.environment.log