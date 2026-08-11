package com.example

object StripeConfig {
    val secretKey: String = System.getenv("STRIPE_SECRET_KEY")
        ?: error("STRIPE_SECRET_KEY environment variable is not set")

    val webhookSecret: String = System.getenv("STRIPE_WEBHOOK_SECRET")
        ?: error("STRIPE_WEBHOOK_SECRET environment variable is not set")

    val priceId: String = System.getenv("STRIPE_PRICE_ID") ?: "price_1U2x6CPr7rsWkomidCSRgP4S"

    val meterEventName: String = System.getenv("STRIPE_METER_EVENT_NAME") ?: "photo_processed"
}
