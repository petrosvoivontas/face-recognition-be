package com.example

import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.services.rekognition.RekognitionClient
import aws.sdk.kotlin.services.rekognition.createCollection
import aws.sdk.kotlin.services.rekognition.indexFaces
import aws.sdk.kotlin.services.rekognition.listFaces
import aws.sdk.kotlin.services.rekognition.model.Image
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

const val PROFILE_NAME = "AdministratorAccess-411055095438"

class RekognitionService {
    private lateinit var client: RekognitionClient

    private val categorizePhotosUseCase by lazy { CategorizePhotosUseCase(client) }

    suspend fun initialize() {
//        client = RekognitionClient.fromEnvironment {
//            this.credentialsProvider = ProfileCredentialsProvider(profileName = PROFILE_NAME)
//        }
        client = RekognitionClient { region = "eu-central-1" }
    }

    suspend fun createCollection(collectionId: String): Int {
        val response = client.createCollection { this.collectionId = collectionId }
        return response.statusCode ?: 500
    }

    suspend fun listCollections(firestoreService: FirestoreService): List<CollectionInfo> {
        val ids = client.listCollections().collectionIds ?: emptyList()
        val results = mutableListOf<CollectionInfo>()
        for (batch in ids.chunked(RekognitionConstants.LIST_FACES_TPS)) {
            val batchStart = System.currentTimeMillis()
            batch.forEach { id ->
                val isComplete = firestoreService.hasCategorizationDocuments(id)
                val imagesCount = client.listFaces { this.collectionId = id }.faces?.groupBy { it.externalImageId }?.size ?: 0
                val status = when {
                    isComplete -> CollectionStatus.COMPLETE
                    imagesCount == 0 -> CollectionStatus.EMPTY
                    else -> CollectionStatus.IN_PROGRESS
                }
                results.add(CollectionInfo(id = id, imagesCount = imagesCount, status = status))
            }
            val elapsed = System.currentTimeMillis() - batchStart
            if (elapsed < RekognitionConstants.QUOTA_WINDOW_MS) {
                delay((RekognitionConstants.QUOTA_WINDOW_MS - elapsed).milliseconds)
            }
        }
        return results
    }

    suspend fun indexFaces(imageBytes: ByteArray, imageFilename: String, collectionId: String): List<IndexedFace> {
        val response = client.indexFaces {
            this.collectionId = collectionId
            image = Image { bytes = imageBytes }
            externalImageId = imageFilename
        }
        return response.faceRecords?.mapNotNull { record ->
            record.face?.let { face ->
                IndexedFace(
                    faceId = face.faceId,
                    externalImageId = face.externalImageId,
                    confidence = face.confidence
                )
            }
        } ?: emptyList()
    }

    fun categorizePhotos(collectionId: String) = categorizePhotosUseCase.categorizeInCollection(collectionId)
}
