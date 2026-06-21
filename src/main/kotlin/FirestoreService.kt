package com.example

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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

        private const val COLLECTION_PATH = "categorizations"

        private const val FIELD_COLLECTION_ID = "collectionId"
        private const val FIELD_PEOPLE = "people"
        private const val FIELD_TIMESTAMP = "timestamp"
    }

    override fun isHealthy(): Boolean {
        return INSTANCE != null
    }

    suspend fun hasCategorizationDocuments(collectionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(COLLECTION_PATH)
                .whereEqualTo(FIELD_COLLECTION_ID, collectionId)
                .limit(1)
                .get()
                .get()
            !snapshot.isEmpty
        }
    }

    suspend fun getCategorizationResults(collectionId: String): List<List<Person>> {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(COLLECTION_PATH)
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
            firestore.collection(COLLECTION_PATH)
                .document(documentId)
                .set(document)
                .get()
        }
    }
}
