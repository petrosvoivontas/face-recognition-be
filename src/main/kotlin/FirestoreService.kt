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
        private const val COLLECTIONS_COLLECTION_PATH = "collections"
        private const val BILLING_PROFILES_COLLECTION_PATH = "billingProfiles"
        private const val ACCOUNTS_COLLECTION_PATH = "accounts"

        private const val FIELD_UID = "uid"
        private const val FIELD_STATE = "state"

        private const val FIELD_COLLECTION_ID = "collectionId"
        private const val FIELD_PEOPLE = "people"
        private const val FIELD_TIMESTAMP = "timestamp"

        private const val FIELD_POSE = "pose"
        private const val FIELD_POSE_EUCLIDEAN_DISTANCE = "euclideanDistance"
        private const val FIELD_PERSON_ID = "personId"

        private const val FIELD_OWNER_ID = "ownerId"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_CREATED_AT = "createdAt"

        private const val FIELD_STRIPE_CUSTOMER_ID = "stripeCustomerId"
        private const val FIELD_STRIPE_SUBSCRIPTION_ID = "stripeSubscriptionId"
        private const val FIELD_SUBSCRIPTION_STATUS = "subscriptionStatus"
        private const val FIELD_EMAIL = "email"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_CARD_BRAND = "cardBrand"
        private const val FIELD_CARD_LAST4 = "cardLast4"
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

    suspend fun saveCategorizationResult(collectionId: String, ownerId: String, people: List<Person>): String {
        return withContext(Dispatchers.IO) {
            val datetime = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val documentId = "$collectionId-$datetime"
            val document = mapOf(
                FIELD_COLLECTION_ID to collectionId,
                FIELD_OWNER_ID to ownerId,
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
            documentId
        }
    }

    suspend fun saveFaceDetails(collectionId: String, ownerId: String, faceId: String, pose: Pose) {
        withContext(Dispatchers.IO) {
            val poseField = try {
                FirestorePose(pose)
            } catch (_: IllegalArgumentException) {
                return@withContext
            }
            val document = mapOf(
                FIELD_COLLECTION_ID to collectionId,
                FIELD_OWNER_ID to ownerId,
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

    suspend fun createCollectionMeta(id: String, ownerId: String, displayName: String): Boolean {
        return withContext(Dispatchers.IO) {
            val docRef = firestore.collection(COLLECTIONS_COLLECTION_PATH).document(id)
            firestore.runTransaction { tx ->
                if (tx.get(docRef).get().exists()) {
                    false
                } else {
                    tx.set(
                        docRef,
                        mapOf(
                            FIELD_OWNER_ID to ownerId,
                            FIELD_DISPLAY_NAME to displayName,
                            FIELD_CREATED_AT to FieldValue.serverTimestamp()
                        )
                    )
                    true
                }
            }.get()
        }
    }

    suspend fun getCollectionOwner(id: String): String? {
        return withContext(Dispatchers.IO) {
            firestore.collection(COLLECTIONS_COLLECTION_PATH).document(id).get().get()
                .getString(FIELD_OWNER_ID)
        }
    }

    suspend fun listCollectionsForOwner(ownerId: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(COLLECTIONS_COLLECTION_PATH)
                .whereEqualTo(FIELD_OWNER_ID, ownerId)
                .get()
                .get()
            snapshot.documents.associate { doc ->
                doc.id to (doc.getString(FIELD_DISPLAY_NAME) ?: doc.id)
            }
        }
    }

    suspend fun deleteCollectionMeta(id: String) {
        withContext(Dispatchers.IO) {
            firestore.collection(COLLECTIONS_COLLECTION_PATH).document(id).delete().get()
        }
    }

    suspend fun getAccountState(uid: String): AccountState {
        return withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(ACCOUNTS_COLLECTION_PATH)
                .whereEqualTo(FIELD_UID, uid)
                .limit(1)
                .get()
                .get()
            val state = snapshot.documents.firstOrNull()?.getString(FIELD_STATE)
            AccountState.entries.find { it.value == state } ?: AccountState.PENDING_ACTIVATION
        }
    }

    data class BillingProfile(
        val stripeCustomerId: String?,
        val stripeSubscriptionId: String?,
        val subscriptionStatus: String?,
        val cardBrand: String?,
        val cardLast4: String?
    )

    suspend fun getBillingProfile(uid: String): BillingProfile? {
        return withContext(Dispatchers.IO) {
            val doc = firestore.collection(BILLING_PROFILES_COLLECTION_PATH).document(uid).get().get()
            if (!doc.exists()) return@withContext null
            BillingProfile(
                stripeCustomerId = doc.getString(FIELD_STRIPE_CUSTOMER_ID),
                stripeSubscriptionId = doc.getString(FIELD_STRIPE_SUBSCRIPTION_ID),
                subscriptionStatus = doc.getString(FIELD_SUBSCRIPTION_STATUS),
                cardBrand = doc.getString(FIELD_CARD_BRAND),
                cardLast4 = doc.getString(FIELD_CARD_LAST4)
            )
        }
    }

    suspend fun saveStripeCustomerId(uid: String, stripeCustomerId: String, email: String?) {
        withContext(Dispatchers.IO) {
            val doc = mutableMapOf(
                FIELD_STRIPE_CUSTOMER_ID to stripeCustomerId,
                FIELD_UPDATED_AT to FieldValue.serverTimestamp()
            )
            if (email != null) doc[FIELD_EMAIL] = email
            firestore.collection(BILLING_PROFILES_COLLECTION_PATH).document(uid)
                .set(doc, com.google.cloud.firestore.SetOptions.merge())
                .get()
        }
    }

    suspend fun saveSubscription(uid: String, stripeSubscriptionId: String, status: String) {
        withContext(Dispatchers.IO) {
            firestore.collection(BILLING_PROFILES_COLLECTION_PATH).document(uid)
                .set(
                    mapOf(
                        FIELD_STRIPE_SUBSCRIPTION_ID to stripeSubscriptionId,
                        FIELD_SUBSCRIPTION_STATUS to status,
                        FIELD_UPDATED_AT to FieldValue.serverTimestamp()
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                )
                .get()
        }
    }

    suspend fun savePaymentMethodSummary(uid: String, brand: String?, last4: String?) {
        withContext(Dispatchers.IO) {
            val doc = mutableMapOf<String, Any>(FIELD_UPDATED_AT to FieldValue.serverTimestamp())
            if (brand != null) doc[FIELD_CARD_BRAND] = brand
            if (last4 != null) doc[FIELD_CARD_LAST4] = last4
            firestore.collection(BILLING_PROFILES_COLLECTION_PATH).document(uid)
                .set(doc, com.google.cloud.firestore.SetOptions.merge())
                .get()
        }
    }

    suspend fun updateSubscriptionStatus(uid: String, status: String) {
        withContext(Dispatchers.IO) {
            firestore.collection(BILLING_PROFILES_COLLECTION_PATH).document(uid)
                .set(
                    mapOf(
                        FIELD_SUBSCRIPTION_STATUS to status,
                        FIELD_UPDATED_AT to FieldValue.serverTimestamp()
                    ),
                    com.google.cloud.firestore.SetOptions.merge()
                )
                .get()
        }
    }

    suspend fun updateSubscriptionStatusByCustomerId(stripeCustomerId: String, status: String) {
        withContext(Dispatchers.IO) {
            val snapshot = firestore.collection(BILLING_PROFILES_COLLECTION_PATH)
                .whereEqualTo(FIELD_STRIPE_CUSTOMER_ID, stripeCustomerId)
                .limit(1)
                .get()
                .get()
            val doc = snapshot.documents.firstOrNull() ?: return@withContext
            doc.reference.set(
                mapOf(
                    FIELD_SUBSCRIPTION_STATUS to status,
                    FIELD_UPDATED_AT to FieldValue.serverTimestamp()
                ),
                com.google.cloud.firestore.SetOptions.merge()
            ).get()
        }
    }
}
