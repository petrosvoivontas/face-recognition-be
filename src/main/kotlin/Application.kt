package com.example

import io.grpc.LoadBalancerRegistry
import io.grpc.internal.PickFirstLoadBalancerProvider
import io.ktor.server.application.*

suspend fun Application.module() {
    LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())

    configureCors()
    configureSerialization()
    configureRouting()
}
