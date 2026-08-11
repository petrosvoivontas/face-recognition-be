package com.example

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class CollectionStatus {
    EMPTY,
    IN_PROGRESS,
    COMPLETE
}

@Serializable
data class CollectionInfo(
    val id: String,
    val displayName: String,
    val imagesCount: Int,
    val status: CollectionStatus
)

@Serializable
data class IndexedFace(
    val faceId: String?,
    val externalImageId: String?,
    val confidence: Float?
)

@Serializable
data class CategorizeRequest(val collectionId: String)

@Serializable
data class CreateCollectionRequest(val displayName: String)

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class SetupIntentResponse(val clientSecret: String)

@Serializable
data class SubscribeRequest(val paymentMethodId: String)

@Serializable
data class SubscribeResponse(val status: String)

@Serializable
data class BillingStatusResponse(
    val hasActiveSubscription: Boolean,
    val status: String?,
    val cardBrand: String? = null,
    val cardLast4: String? = null
)

@Serializable
data class UpdatePaymentMethodRequest(val paymentMethodId: String)

@Serializable
data class PaymentMethodResponse(val brand: String?, val last4: String?)

private val ACTIVE_SUBSCRIPTION_STATUSES = setOf("active", "trialing")

private val COLLECTION_ID_CHARSET = Regex("[^a-zA-Z0-9_.\\-]")

private fun slugify(input: String): String {
    val sanitized = input.trim().replace(COLLECTION_ID_CHARSET, "-").take(200)
    return sanitized.ifEmpty { "collection" }
}

private suspend fun ApplicationCall.requireUid(authService: AuthService): String? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    val uid = token?.takeIf { it.isNotEmpty() }?.let { authService.verify(it) }
    if (uid == null) {
        respond(HttpStatusCode.Unauthorized)
    }
    return uid
}

// Responds 404 for both "doesn't exist" and "belongs to someone else" so ownership can't be probed.
private suspend fun ApplicationCall.requireOwnedCollection(
    firestoreService: FirestoreService,
    uid: String,
    collectionId: String
): Boolean {
    val owner = firestoreService.getCollectionOwner(collectionId)
    if (owner != uid) {
        respond(HttpStatusCode.NotFound)
        return false
    }
    return true
}

private suspend fun ApplicationCall.requireActiveSubscription(firestoreService: FirestoreService, uid: String): Boolean {
    val status = firestoreService.getBillingProfile(uid)?.subscriptionStatus
    if (status !in ACTIVE_SUBSCRIPTION_STATUSES) {
        respond(HttpStatusCode.PaymentRequired, ErrorResponse("An active subscription is required."))
        return false
    }
    return true
}

suspend fun Application.configureRouting(authService: AuthService = FirebaseAuthService()) {
    val rekognition = RekognitionService()
    val firestoreService = FirestoreService()
    val stripeService = StripeService(firestoreService)

    rekognition.initialize()

    routing {
        get("/health") {
            val statusCode = if (rekognition.isHealthy() && firestoreService.isHealthy()) {
                HttpStatusCode.OK
            } else {
                HttpStatusCode.ServiceUnavailable
            }
            call.respond(statusCode)
        }

        get("/collections") {
            val uid = call.requireUid(authService) ?: return@get
            val owned = firestoreService.listCollectionsForOwner(uid)
            call.respond(rekognition.listCollections(firestoreService, owned))
        }

        get("/categorization-results") {
            val uid = call.requireUid(authService) ?: return@get
            val collectionId = call.request.queryParameters["collectionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing collectionId query parameter")
            if (!call.requireOwnedCollection(firestoreService, uid, collectionId)) return@get
            val results = firestoreService.getCategorizationResults(collectionId)
            call.respond(results)
        }

        post("/collections") {
            val uid = call.requireUid(authService) ?: return@post
            if (!call.requireActiveSubscription(firestoreService, uid)) return@post
            val request = call.receive<CreateCollectionRequest>()
            val displayName = request.displayName.trim()
            if (displayName.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Missing displayName")
            }

            val collectionId = "${uid}_${slugify(displayName)}"
            val created = firestoreService.createCollectionMeta(collectionId, uid, displayName)
            if (!created) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("You already have a collection named \"$displayName\"")
                )
            }

            val statusCode = try {
                rekognition.createCollection(collectionId)
            } catch (e: Exception) {
                firestoreService.deleteCollectionMeta(collectionId)
                return@post call.respond(HttpStatusCode.BadGateway, ErrorResponse("Failed to create collection: ${e.message}"))
            }
            if (statusCode !in 200..299) {
                firestoreService.deleteCollectionMeta(collectionId)
                return@post call.respond(HttpStatusCode.fromValue(statusCode))
            }

            call.respond(
                HttpStatusCode.Created,
                CollectionInfo(id = collectionId, displayName = displayName, imagesCount = 0, status = CollectionStatus.EMPTY)
            )
        }

        delete("/collections/{collectionId}") {
            val uid = call.requireUid(authService) ?: return@delete
            val collectionId = call.parameters["collectionId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing collectionId")
            if (!call.requireOwnedCollection(firestoreService, uid, collectionId)) return@delete

            val statusCode = try {
                rekognition.deleteCollection(collectionId)
            } catch (e: Exception) {
                HttpStatusCode.InternalServerError.value
            }
            firestoreService.deleteCollectionMeta(collectionId)
            call.respond(HttpStatusCode.fromValue(statusCode))
        }

        post("/indexFaces") {
            val uid = call.requireUid(authService) ?: return@post
            if (!call.requireActiveSubscription(firestoreService, uid)) return@post

            val multipart = call.receiveMultipart()
            var imageBytes: ByteArray? = null
            var imageFilename: String? = null
            var collectionId: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem if part.name == "image" -> {
                        imageFilename = part.originalFileName ?: part.name
                        imageBytes = part.streamProvider().readBytes()
                    }

                    is PartData.FormItem if part.name == "collectionId" -> {
                        collectionId = part.value
                    }

                    else -> {}
                }
                part.dispose()
            }

            val bytes = imageBytes ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing image")
            val filename =
                imageFilename ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing image filename")
            val collection = collectionId ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing collectionId")
            if (!call.requireOwnedCollection(firestoreService, uid, collection)) return@post

            val faces = rekognition.indexFaces(firestoreService, bytes, filename, collection, uid)
            call.respond(faces)
        }

        post("/categorize") {
            val uid = call.requireUid(authService) ?: return@post
            if (!call.requireActiveSubscription(firestoreService, uid)) return@post
            val request = call.receive<CategorizeRequest>()
            if (!call.requireOwnedCollection(firestoreService, uid, request.collectionId)) return@post

            call.response.headers.append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            call.respondBytesWriter {
                rekognition.categorizePhotos(firestoreService, request.collectionId).collect { event ->
                    when (event) {
                        is CategorizationEvent.Progress -> {
                            writeStringUtf8("event: progress\n")
                            writeStringUtf8(
                                "data: ${
                                    Json.encodeToString(
                                        CategorizationEvent.Progress.serializer(),
                                        event
                                    )
                                }\n\n"
                            )
                        }

                        is CategorizationEvent.Complete -> {
                            val documentId = firestoreService.saveCategorizationResult(request.collectionId, uid, event.people)
                            try {
                                val imagesProcessed = event.people.flatMap { it.images }.distinct().size
                                if (imagesProcessed > 0) {
                                    val customerId = firestoreService.getBillingProfile(uid)?.stripeCustomerId
                                    if (customerId != null) {
                                        stripeService.reportUsage(customerId, imagesProcessed, documentId)
                                    }
                                }
                            } catch (e: Exception) {
                                call.application.environment.log.error("Failed to report Stripe usage for $documentId", e)
                            }
                            writeStringUtf8("event: complete\n")
                            writeStringUtf8(
                                "data: ${
                                    Json.encodeToString(
                                        CategorizationEvent.Complete.serializer(),
                                        event
                                    )
                                }\n\n"
                            )
                        }
                    }
                    flush()
                }
            }
        }

        post("/billing/setup-intent") {
            val uid = call.requireUid(authService) ?: return@post
            val userInfo = lookupUserInfo(uid)
            val customerId = stripeService.getOrCreateCustomer(uid, userInfo?.email, userInfo?.displayName)
            val clientSecret = stripeService.createSetupIntent(customerId)
            call.respond(SetupIntentResponse(clientSecret))
        }

        post("/billing/subscribe") {
            val uid = call.requireUid(authService) ?: return@post
            val request = call.receive<SubscribeRequest>()
            val customerId = firestoreService.getBillingProfile(uid)?.stripeCustomerId
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("No Stripe customer on file. Call /billing/setup-intent first."))
            val subscription = stripeService.attachPaymentMethodAndSubscribe(uid, customerId, request.paymentMethodId)
            call.respond(SubscribeResponse(subscription.status))
        }

        get("/billing/status") {
            val uid = call.requireUid(authService) ?: return@get
            val profile = firestoreService.getBillingProfile(uid)
            val status = profile?.subscriptionStatus
            call.respond(
                BillingStatusResponse(
                    hasActiveSubscription = status in ACTIVE_SUBSCRIPTION_STATUSES,
                    status = status,
                    cardBrand = profile?.cardBrand,
                    cardLast4 = profile?.cardLast4
                )
            )
        }

        post("/billing/update-payment-method") {
            val uid = call.requireUid(authService) ?: return@post
            val request = call.receive<UpdatePaymentMethodRequest>()
            val profile = firestoreService.getBillingProfile(uid)
            val customerId = profile?.stripeCustomerId
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("No Stripe customer on file."))
            val paymentMethod = stripeService.updatePaymentMethod(uid, customerId, profile.stripeSubscriptionId, request.paymentMethodId)
            call.respond(PaymentMethodResponse(paymentMethod.card?.brand, paymentMethod.card?.last4))
        }

        post("/billing/cancel-subscription") {
            val uid = call.requireUid(authService) ?: return@post
            val subscriptionId = firestoreService.getBillingProfile(uid)?.stripeSubscriptionId
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("No subscription to cancel."))
            val subscription = stripeService.cancelSubscription(uid, subscriptionId)
            call.respond(
                BillingStatusResponse(
                    hasActiveSubscription = subscription.status in ACTIVE_SUBSCRIPTION_STATUSES,
                    status = subscription.status
                )
            )
        }

        post("/webhooks/stripe") {
            val payload = call.receiveText()
            val sigHeader = call.request.headers["Stripe-Signature"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing Stripe-Signature header")
            val verified = stripeService.handleWebhookEvent(payload, sigHeader)
            call.respond(if (verified) HttpStatusCode.OK else HttpStatusCode.BadRequest)
        }
    }
}
