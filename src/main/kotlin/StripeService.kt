package com.example

import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Customer
import com.stripe.model.PaymentMethod
import com.stripe.model.Price
import com.stripe.model.Subscription
import com.stripe.model.billing.MeterEvent
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerUpdateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.SetupIntentCreateParams
import com.stripe.param.SubscriptionCreateParams
import com.stripe.param.SubscriptionUpdateParams
import com.stripe.param.billing.MeterEventCreateParams
import io.ktor.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StripeService(
    private val firestoreService: FirestoreService,
    private val stripeSecrets: StripeParamsProvider,
    private val logger: Logger
) {

    suspend fun getOrCreateCustomer(uid: String, email: String?, name: String?): String {
        return withContext(Dispatchers.IO) {
            val existing = firestoreService.getAccountStripeCustomerId(uid)
            if (existing != null) {
                logger.debug("found existing Stripe customer id=$existing")
                return@withContext existing
            }

            val params = CustomerCreateParams.builder()
                .putMetadata("firebaseUid", uid)
                .apply { email?.let { setEmail(it) } }
                .apply { name?.let { setName(it) } }
                .addPreferredLocale("el-GR")
                .build()
            val customer = Customer.create(params)
            firestoreService.saveStripeCustomerId(uid, customer.id, email)
            logger.debug("created new Stripe customer id=${customer.id}")
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

    // A price only skips payment-method collection when it's a flat, non-tiered $0 price.
    // Anything tiered/graduated or with a non-zero unit amount is treated as requiring a card,
    // including metered prices with a real per-unit rate (their base unit amount may read as
    // null/non-zero even though no usage has been billed yet).
    suspend fun priceRequiresPaymentMethod(priceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val price = Price.retrieve(priceId)
            !(price.billingScheme == "per_unit" && price.unitAmount == 0L)
        }
    }

    suspend fun subscribeWithoutPaymentMethod(uid: String, customerId: String, priceId: String): Subscription {
        return withContext(Dispatchers.IO) {
            val subscription = Subscription.create(
                SubscriptionCreateParams.builder()
                    .setCustomer(customerId)
                    .addItem(
                        SubscriptionCreateParams.Item.builder()
                            .setPrice(priceId)
                            .build()
                    )
                    .build()
            )
            firestoreService.saveSubscription(uid, subscription.id, subscription.status)
            subscription
        }
    }

    // Attaches the payment method to the customer and marks it as the default for future invoices.
    // Returns the attached PaymentMethod so callers can read its card brand/last4 without another API call.
    private fun attachAsDefaultPaymentMethod(customerId: String, paymentMethodId: String): PaymentMethod {
        val paymentMethod = PaymentMethod.retrieve(paymentMethodId)
        val attached = paymentMethod.attach(PaymentMethodAttachParams.builder().setCustomer(customerId).build())

        Customer.retrieve(customerId).update(
            CustomerUpdateParams.builder()
                .setInvoiceSettings(
                    CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build()
                )
                .build()
        )

        return attached
    }

    suspend fun attachPaymentMethodAndSubscribe(uid: String, customerId: String, priceId: String, paymentMethodId: String): Subscription {
        return withContext(Dispatchers.IO) {
            val paymentMethod = attachAsDefaultPaymentMethod(customerId, paymentMethodId)

            val subscription = Subscription.create(
                SubscriptionCreateParams.builder()
                    .setCustomer(customerId)
                    .setDefaultPaymentMethod(paymentMethodId)
                    .addItem(
                        SubscriptionCreateParams.Item.builder()
                            .setPrice(priceId)
                            .build()
                    )
                    .build()
            )

            firestoreService.saveSubscription(uid, subscription.id, subscription.status)
            firestoreService.savePaymentMethodSummary(uid, paymentMethod.card?.brand, paymentMethod.card?.last4)
            subscription
        }
    }

    suspend fun updatePaymentMethod(
        uid: String,
        customerId: String,
        subscriptionId: String?,
        paymentMethodId: String
    ): PaymentMethod {
        return withContext(Dispatchers.IO) {
            val paymentMethod = attachAsDefaultPaymentMethod(customerId, paymentMethodId)

            if (subscriptionId != null) {
                Subscription.retrieve(subscriptionId).update(
                    SubscriptionUpdateParams.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build()
                )
            }

            firestoreService.savePaymentMethodSummary(uid, paymentMethod.card?.brand, paymentMethod.card?.last4)
            paymentMethod
        }
    }

    suspend fun cancelSubscription(uid: String, subscriptionId: String): Subscription {
        return withContext(Dispatchers.IO) {
            val subscription = Subscription.retrieve(subscriptionId).cancel()
            firestoreService.updateSubscriptionStatus(uid, subscription.status)
            subscription
        }
    }

    suspend fun reportUsage(customerId: String, quantity: Int, idempotencyKey: String) {
        withContext(Dispatchers.IO) {
            val params = MeterEventCreateParams.builder()
                .setEventName(StripeParamsProvider.meterEventName)
                .setIdentifier(idempotencyKey)
                .putPayload("value", quantity.toString())
                .putPayload("stripe_customer_id", customerId)
                .build()
            MeterEvent.create(params)
        }
    }

    suspend fun handleWebhookEvent(payload: String, sigHeader: String): Boolean {
        return withContext(Dispatchers.IO) {
            // Try each cached webhook secret (current, then previous) so events signed during
            // a Stripe-side secret rotation still verify.
            val event = stripeSecrets.webhookSecrets.firstNotNullOfOrNull { secret ->
                try {
                    Webhook.constructEvent(payload, sigHeader, secret)
                } catch (_: SignatureVerificationException) {
                    null
                }
            } ?: return@withContext false

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
