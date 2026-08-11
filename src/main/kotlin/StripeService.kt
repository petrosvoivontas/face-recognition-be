package com.example

import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Customer
import com.stripe.model.Subscription
import com.stripe.model.billing.MeterEvent
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerUpdateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.SetupIntentCreateParams
import com.stripe.param.SubscriptionCreateParams
import com.stripe.param.billing.MeterEventCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StripeService(private val firestoreService: FirestoreService) {

    init {
        com.stripe.Stripe.apiKey = StripeConfig.secretKey
    }

    suspend fun getOrCreateCustomer(uid: String, email: String?): String {
        return withContext(Dispatchers.IO) {
            val existing = firestoreService.getBillingProfile(uid)?.stripeCustomerId
            if (existing != null) return@withContext existing

            val params = CustomerCreateParams.builder()
                .putMetadata("firebaseUid", uid)
                .apply { email?.let { setEmail(it) } }
                .build()
            val customer = Customer.create(params)
            firestoreService.saveStripeCustomerId(uid, customer.id, email)
            customer.id
        }
    }

    suspend fun createSetupIntent(customerId: String): String {
        return withContext(Dispatchers.IO) {
            val params = SetupIntentCreateParams.builder()
                .setCustomer(customerId)
                .addPaymentMethodType("card")
                .build()
            com.stripe.model.SetupIntent.create(params).clientSecret
        }
    }

    suspend fun attachPaymentMethodAndSubscribe(uid: String, customerId: String, paymentMethodId: String): Subscription {
        return withContext(Dispatchers.IO) {
            val paymentMethod = com.stripe.model.PaymentMethod.retrieve(paymentMethodId)
            paymentMethod.attach(PaymentMethodAttachParams.builder().setCustomer(customerId).build())

            Customer.retrieve(customerId).update(
                CustomerUpdateParams.builder()
                    .setInvoiceSettings(
                        CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build()
                    )
                    .build()
            )

            val subscription = Subscription.create(
                SubscriptionCreateParams.builder()
                    .setCustomer(customerId)
                    .setDefaultPaymentMethod(paymentMethodId)
                    .addItem(
                        SubscriptionCreateParams.Item.builder()
                            .setPrice(StripeConfig.priceId)
                            .build()
                    )
                    .build()
            )

            firestoreService.saveSubscription(uid, subscription.id, subscription.status)
            subscription
        }
    }

    suspend fun reportUsage(customerId: String, quantity: Int, idempotencyKey: String) {
        withContext(Dispatchers.IO) {
            val params = MeterEventCreateParams.builder()
                .setEventName(StripeConfig.meterEventName)
                .setIdentifier(idempotencyKey)
                .putPayload("value", quantity.toString())
                .putPayload("stripe_customer_id", customerId)
                .build()
            MeterEvent.create(params)
        }
    }

    suspend fun handleWebhookEvent(payload: String, sigHeader: String): Boolean {
        return withContext(Dispatchers.IO) {
            val event = try {
                Webhook.constructEvent(payload, sigHeader, StripeConfig.webhookSecret)
            } catch (_: SignatureVerificationException) {
                return@withContext false
            }

            when (event.type) {
                "customer.subscription.created",
                "customer.subscription.updated",
                "customer.subscription.deleted" -> {
                    val subscription = event.dataObjectDeserializer.`object`.orElse(null) as? Subscription
                    if (subscription != null) {
                        firestoreService.updateSubscriptionStatusByCustomerId(subscription.customer, subscription.status)
                    }
                }
            }
            true
        }
    }
}
