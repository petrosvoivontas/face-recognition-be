package com.example

import aws.sdk.kotlin.services.rekognition.model.Pose
import com.example.model.BoundingBox
import com.example.model.Person
import com.example.model.Thumbnail
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.sqrt

data class FirestorePose(
    @SerialName("pitch")
    val pitch: Float,
    @SerialName("roll")
    val roll: Float,
    @SerialName("yaw")
    val yaw: Float,
    @SerialName("euclideanDistance")
    val euclideanDistance: Float
) {
    constructor(pose: Pose) : this(
        requireNotNull(pose.pitch),
        requireNotNull(pose.roll),
        requireNotNull(pose.yaw),
        sqrt(pose.pitch!!.pow(2) + pose.roll!!.pow(2) + pose.yaw!!.pow(2))
    )
}

class FirestoreService : HealthCheck {

    init {
        if (FirebaseApp.getApps().isEmpty()) {
            val serviceAccount = FirestoreService::class.java.classLoader
                .getResourceAsStream("faceme-prod-adminsdk.json")
                ?: error("faceme-prod-adminsdk.json not found in resources")
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()
            FirebaseApp.initializeApp(options)
        }
        INSTANCE = FirestoreClient.getFirestore()
    }

    companion object {
        private var INSTANCE: Firestore? = null
        val firestore: Firestore
            get() = INSTANCE!!

        private const val CATEGORIZATIONS_COLLECTION_PATH = "categorizations"
        private const val FACE_DETAILS_COLLECTION_PATH = "faceDetails"

        private const val FIELD_COLLECTION_ID = "collectionId"
        private const val FIELD_PEOPLE = "people"
        private const val FIELD_TIMESTAMP = "timestamp"

        private const val FIELD_POSE = "pose"
        private const val FIELD_POSE_EUCLIDEAN_DISTANCE = "euclideanDistance"
        private const val FIELD_PERSON_ID = "personId"
    }

    override fun isHealthy(): Boolean {
        return INSTANCE != null
    }

    suspend fun hasCategorizationDocuments(collectionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(CATEGORIZATIONS_COLLECTION_PATH)
                .whereEqualTo(FIELD_COLLECTION_ID, collectionId)
                .limit(1)
                .get()
                .get()
            !snapshot.isEmpty
        }
    }

    suspend fun getCategorizationResults(collectionId: String): List<List<Person>> {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(CATEGORIZATIONS_COLLECTION_PATH)
                .whereEqualTo(FIELD_COLLECTION_ID, collectionId)
                .get()
                .get()
            snapshot.documents.map { doc ->
                @Suppress("UNCHECKED_CAST")
                val rawPeople = doc.get(FIELD_PEOPLE) as? List<Map<String, Any>> ?: emptyList()
                rawPeople.map { p ->
                    @Suppress("UNCHECKED_CAST")
                    val thumbnailMap = p["thumbnail"] as? Map<String, Any> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val boxMap = thumbnailMap["boundingBox"] as? Map<String, Any> ?: emptyMap()
                    Person(
                        id = p["id"] as? String ?: "",
                        faceIds = (p["faceIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        images = (p["images"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        thumbnail = Thumbnail(
                            imageFilename = thumbnailMap["imageFileName"] as? String ?: "",
                            boundingBox = BoundingBox(
                                top = (boxMap["top"] as? Number)?.toFloat() ?: 0f,
                                left = (boxMap["left"] as? Number)?.toFloat() ?: 0f,
                                width = (boxMap["width"] as? Number)?.toFloat() ?: 0f,
                                height = (boxMap["height"] as? Number)?.toFloat() ?: 0f
                            )
                        )
                    )
                }
            }
        }
    }

    suspend fun saveCategorizationResult(collectionId: String, people: List<Person>) {
        withContext(Dispatchers.IO) {
            val datetime = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val documentId = "$collectionId-$datetime"
            val document = mapOf(
                FIELD_COLLECTION_ID to collectionId,
                FIELD_PEOPLE to people.map { person ->
                    mapOf(
                        "id" to person.id,
                        "faceIds" to person.faceIds,
                        "images" to person.images,
                        "thumbnail" to mapOf(
                            "imageFileName" to person.thumbnail.imageFilename,
                            "boundingBox" to person.thumbnail.boundingBox.let { box ->
                                mapOf(
                                    "top" to box.top,
                                    "left" to box.left,
                                    "width" to box.width,
                                    "height" to box.height
                                )
                            }
                        )
                    )
                },
                FIELD_TIMESTAMP to FieldValue.serverTimestamp()
            )
            firestore.collection(CATEGORIZATIONS_COLLECTION_PATH)
                .document(documentId)
                .set(document)
                .get()
        }
    }

    suspend fun saveFaceDetails(collectionId: String, faceId: String, pose: Pose) {
        withContext(Dispatchers.IO) {
            val poseField = try {
                FirestorePose(pose)
            } catch (_: IllegalArgumentException) {
                return@withContext
            }
            val document = mapOf(
                FIELD_COLLECTION_ID to collectionId,
                FIELD_POSE to poseField
            )

            firestore.batch()
                .commit()

            firestore.collection(FACE_DETAILS_COLLECTION_PATH)
                .document(faceId)
                .set(document)
                .get()
        }
    }

    suspend fun assignFaceDetailsToPerson(
        faceIds: List<String>,
        personId: String
    ) {
        withContext(Dispatchers.IO) {
            val writeBatch = firestore.batch()
            val faceDetailsCollection = firestore.collection(FACE_DETAILS_COLLECTION_PATH)

            for (faceId in faceIds) {
                val document = faceDetailsCollection
                    .document(faceId)

                writeBatch.update(
                    document,
                    mapOf(FIELD_PERSON_ID to personId)
                )
            }

            writeBatch.commit().get()
        }
    }

    suspend fun getFaceForThumbnail(collectionId: String, personId: String): String? {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(FACE_DETAILS_COLLECTION_PATH)
                .whereEqualTo(FIELD_COLLECTION_ID, collectionId)
                .whereEqualTo(FIELD_PERSON_ID, personId)
                .orderBy("$FIELD_POSE.$FIELD_POSE_EUCLIDEAN_DISTANCE")
                .limit(1)
                .get()
                .get()

            snapshot.documents.firstOrNull()?.id
        }
    }
}
