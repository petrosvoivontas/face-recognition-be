package com.example

import aws.sdk.kotlin.services.rekognition.RekognitionClient
import aws.sdk.kotlin.services.rekognition.listFaces
import aws.sdk.kotlin.services.rekognition.searchFaces
import com.example.model.Person
import com.example.model.Thumbnail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class CategorizationProgress(val processed: Int, val total: Int)

class CategorizePhotosUseCase(
    private val rekognitionClient: RekognitionClient
) {

    fun categorizeInCollection(collectionId: String): Flow<CategorizationEvent> = flow {
        val response = rekognitionClient.listFaces {
            this.collectionId = collectionId
        }
        val faces = response.faces ?: run {
            emit(CategorizationEvent.Complete(emptyList()))
            return@flow
        }
        val total = faces.size
        println("Found $total faces")

        val people = arrayListOf<Person>()
        val processFaceIds = hashSetOf<String>()
        var processed = 0

        for (face in faces) {
            val currentFaceId = face.faceId ?: continue
            val currentFaceExternalImageId = face.externalImageId ?: continue
            val currentFaceBoundingBox = face.boundingBox ?: continue
            if (currentFaceId in processFaceIds) {
                processed++
                emit(CategorizationEvent.Progress(processed, total))
                continue
            }
            val searchFacesResponse = rekognitionClient.searchFaces {
                this.collectionId = collectionId
                faceId = currentFaceId
            }

            val faceIds = arrayListOf(currentFaceId)
            val images = hashSetOf(face.externalImageId ?: "")

            searchFacesResponse.faceMatches?.forEach { faceMatch ->
                faceIds.add(faceMatch.face?.faceId ?: return@forEach)
                images.add(faceMatch.face?.externalImageId ?: return@forEach)
            }

            processFaceIds.addAll(faceIds)

            val person = Person(
                id = UUID.randomUUID().toString(),
                faceIds = faceIds,
                images = images.toList(),
                thumbnail = Thumbnail(
                    imageFilename = currentFaceExternalImageId,
                    boundingBox = currentFaceBoundingBox
                )
            )
            people.add(person)
            processed++
            emit(CategorizationEvent.Progress(processed, total, person))
        }

        println("Found ${people.size} people")
        emit(CategorizationEvent.Complete(people))
    }
}

@Serializable
sealed class CategorizationEvent {
    @Serializable
    data class Progress(val processed: Int, val total: Int, val person: Person? = null) : CategorizationEvent()

    @Serializable
    data class Complete(val people: List<Person>) : CategorizationEvent()
}