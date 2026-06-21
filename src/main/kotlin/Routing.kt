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
data class CollectionInfo(val id: String, val imagesCount: Int, val status: CollectionStatus)

@Serializable
data class IndexedFace(
    val faceId: String?,
    val externalImageId: String?,
    val confidence: Float?
)

@Serializable
data class CategorizeRequest(val collectionId: String)

@Serializable
data class CreateCollectionRequest(val collectionId: String)

suspend fun Application.configureRouting() {
    val rekognition = RekognitionService()
    val firestoreService = FirestoreService()

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
            call.respond(rekognition.listCollections(firestoreService))
        }

        get("/categorization-results") {
            val collectionId = call.request.queryParameters["collectionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing collectionId query parameter")
            val results = firestoreService.getCategorizationResults(collectionId)
            call.respond(results)
        }

        post("/collections") {
            val request = call.receive<CreateCollectionRequest>()

            val collectionCreationStatusCode = rekognition.createCollection(request.collectionId)

            call.respond(collectionCreationStatusCode)
        }

        post("/indexFaces") {
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

            val faces = rekognition.indexFaces(bytes, filename, collection)
            call.respond(faces)
        }

        post("/categorize") {
            val request = call.receive<CategorizeRequest>()

            call.response.headers.append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            call.respondBytesWriter {
                rekognition.categorizePhotos(request.collectionId).collect { event ->
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
                            firestoreService.saveCategorizationResult(request.collectionId, event.people)
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
    }
}
