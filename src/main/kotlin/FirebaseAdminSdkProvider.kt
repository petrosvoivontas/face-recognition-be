package com.example

import aws.sdk.kotlin.services.secretsmanager.getSecretValue
import com.example.aws.clients.SecretsManagerClientProvider
import io.ktor.util.logging.*
import java.io.InputStream

// Resolves the Firebase Admin SDK service-account JSON for the running environment (prod/uat)
// from AWS Secrets Manager, so the same JAR serves both Elastic Beanstalk environments.
object FirebaseAdminSdkProvider {

    private val SECRET_ID = System.getenv("FIREBASE_ADMIN_SDK_SECRET_ID")
        ?: "uat/FaceMeBE/FirebaseAdminSdk"

    // Local/dev escape hatch: load a service-account json bundled in resources instead of
    // hitting Secrets Manager, mirrors StripeParamsProvider's env var override.
    private val localServiceAccountFilename = System.getenv("FIREBASE_ADMIN_SDK_FILENAME")

    suspend fun loadServiceAccountJson(logger: Logger): InputStream {
        localServiceAccountFilename?.let { filename ->
            logger.info("Using Firebase Admin SDK credentials from resources/$filename (local/dev override)")
            return FirebaseAdminSdkProvider::class.java.classLoader.getResourceAsStream(filename)
                ?: error("$filename not found in resources")
        }

        val client = SecretsManagerClientProvider.get()
        client.use { client ->
            val response = client.getSecretValue { secretId = SECRET_ID }
            logger.info("Loaded Firebase Admin SDK credentials from Secrets Manager (secretId=$SECRET_ID)")
            val value = response.secretString ?: error("Secret $SECRET_ID has no string value")
            return value.byteInputStream()
        }
    }
}
