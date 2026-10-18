package com.example.aws.clients

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient

object SecretsManagerClientProvider {

    private const val AWS_PROFILE_NAME_ENV = "AWS_PROFILE_NAME"
    private const val AWS_REGION_ENV = "AWS_REGION"

    suspend fun get(): SecretsManagerClient {
        val profileNameEnv = System.getenv(AWS_PROFILE_NAME_ENV)
        val regionEnv = System.getenv(AWS_REGION_ENV) ?: "eu-central-1"
        return if (profileNameEnv != null) {
            SecretsManagerClient.fromEnvironment {
                this.credentialsProvider = ProfileCredentialsProvider(profileName = profileNameEnv)
            }
        } else {
            SecretsManagerClient { region = regionEnv }
        }
    }
}
