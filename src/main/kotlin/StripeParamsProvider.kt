package com.example

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.getSecretValue
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

private data class SecretValue(val value: String, val versionId: String?)

private data class WebhookSecrets(val current: SecretValue, val previous: SecretValue?)

// Reads STRIPE_SECRET_KEY / STRIPE_WEBHOOK_SECRET from AWS Secrets Manager and keeps them
// refreshed in memory so a key rotated in Secrets Manager is picked up without an app restart.
class StripeParamsProvider(private val logger: Logger) : HealthCheck {

    companion object {
        private val REGION = System.getenv("AWS_REGION") ?: "eu-central-1"
        private val SECRET_KEY_ID = System.getenv("STRIPE_SECRET_KEY_SECRET_ID")
            ?: "arn:aws:secretsmanager:eu-central-1:411055095438:secret:uat/FaceMeBE/StripeSecretKey-cKaBIo"
        private val WEBHOOK_SECRET_ID = System.getenv("STRIPE_WEBHOOK_SECRET_SECRET_ID")
            ?: "arn:aws:secretsmanager:eu-central-1:411055095438:secret:uat/FaceMeBE/StripeWHSecret-tTsRJz"
        private val REFRESH_INTERVAL_SECONDS =
            System.getenv("STRIPE_SECRET_REFRESH_INTERVAL_SECONDS")?.toLongOrNull() ?: 300L

        val meterEventName: String = System.getenv("STRIPE_METER_EVENT_NAME") ?: "photo_processed"

        // Local/dev escape hatch so the app can run without AWS credentials.
        private val envSecretKey = System.getenv("STRIPE_SECRET_KEY")
        private val envWebhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
    }

    private lateinit var client: SecretsManagerClient

    private val secretKeyRef = AtomicReference<SecretValue>()
    private val webhookSecretsRef = AtomicReference<WebhookSecrets>()
    private var refreshJob: Job? = null

    override fun isHealthy(): Boolean = secretKeyRef.get() != null && webhookSecretsRef.get() != null

    val secretKey: String
        get() = secretKeyRef.get()?.value ?: error("Stripe secret key has not been loaded yet")

    // Current webhook signing secret first, then the previous one, so events signed during
    // a Stripe-side rotation window (old + new secret both valid) still verify.
    val webhookSecrets: List<String>
        get() = webhookSecretsRef.get()?.let { listOfNotNull(it.current.value, it.previous?.value) } ?: emptyList()

    suspend fun initialize(scope: CoroutineScope) {
//        client = SecretsManagerClient { region = REGION }
        client = SecretsManagerClient.fromEnvironment {
            this.credentialsProvider = ProfileCredentialsProvider(profileName = PROFILE_NAME)
        }
        if (envSecretKey != null && envWebhookSecret != null) {
            secretKeyRef.set(SecretValue(envSecretKey, null))
            webhookSecretsRef.set(WebhookSecrets(SecretValue(envWebhookSecret, null), null))
            com.stripe.Stripe.apiKey = envSecretKey
            logger.info("Using Stripe credentials from environment variables (local/dev override)")
            return
        }

        refreshSecretKey()
        refreshWebhookSecret()

        refreshJob = scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_SECONDS * 1000)
                runCatching { refreshSecretKey() }.onFailure {
                    logger.error(
                        "Failed to refresh Stripe secret key, keeping previous value",
                        it
                    )
                }
                runCatching { refreshWebhookSecret() }.onFailure {
                    logger.error(
                        "Failed to refresh Stripe webhook secret, keeping previous value",
                        it
                    )
                }
            }
        }
    }

    fun shutdown() {
        refreshJob?.cancel()
        client.close()
    }

    private suspend fun refreshSecretKey() {
        val response = client.getSecretValue { secretId = SECRET_KEY_ID }
        val versionId = response.versionId
        if (secretKeyRef.get()?.versionId == versionId) {
            logger.debug("Secret $SECRET_KEY_ID hasn't changed - skipping")
            return
        }
        val value = response.secretString ?: error("Secret $SECRET_KEY_ID has no string value")
        secretKeyRef.set(SecretValue(value, versionId))
        com.stripe.Stripe.apiKey = value
        logger.info("Loaded Stripe secret key version=$versionId")
    }

    private suspend fun refreshWebhookSecret() {
        val response = client.getSecretValue { secretId = WEBHOOK_SECRET_ID }
        val versionId = response.versionId
        val existing = webhookSecretsRef.get()
        if (existing?.current?.versionId == versionId) {
            logger.debug("Secret $WEBHOOK_SECRET_ID hasn't changed - skipping")
            return
        }
        val value = response.secretString ?: error("Secret $WEBHOOK_SECRET_ID has no string value")
        webhookSecretsRef.set(WebhookSecrets(current = SecretValue(value, versionId), previous = existing?.current))
        logger.info("Loaded Stripe webhook secret version=$versionId")
    }
}
