package com.example.aws.clients

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.services.rekognition.RekognitionClient

object RekognitionClientProvider {

    private const val AWS_PROFILE_NAME_ENV = "AWS_PROFILE_NAME"
    private const val AWS_REGION_ENV = "AWS_REGION"

    suspend fun get(): RekognitionClient {
        val profileNameEnv = System.getenv(AWS_PROFILE_NAME_ENV)
        val regionEnv = System.getenv(AWS_REGION_ENV) ?: "eu-central-1"
        return if (profileNameEnv != null) {
            RekognitionClient.fromEnvironment {
                this.credentialsProvider = ProfileCredentialsProvider(profileName = profileNameEnv)
            }
        } else {
            RekognitionClient { region = regionEnv }
        }
    }
}