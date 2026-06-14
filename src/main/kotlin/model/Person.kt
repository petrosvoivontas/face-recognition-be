package com.example.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import aws.sdk.kotlin.services.rekognition.model.BoundingBox as AWSBoundingBox

@Serializable
data class BoundingBox(
    @SerialName("top")
    val top: Float,
    @SerialName("left")
    val left: Float,
    @SerialName("width")
    val width: Float,
    @SerialName("height")
    val height: Float
)

@Serializable
data class Thumbnail(
    @SerialName("imageFilename")
    val imageFilename: String,
    @SerialName("boundingBox")
    val boundingBox: BoundingBox
) {
    constructor(imageFilename: String, boundingBox: AWSBoundingBox) : this(
        imageFilename,
        BoundingBox(
            top = boundingBox.top ?: 0f,
            left = boundingBox.left ?: 0f,
            width = boundingBox.width ?: 0f,
            height = boundingBox.height ?: 0f
        )
    )
}

@Serializable
data class Person(
    @SerialName("id")
    val id: String,
    @SerialName("faceIds")
    val faceIds: List<String>,
    @SerialName("images")
    val images: List<String>,
    @SerialName("thumbnail")
    val thumbnail: Thumbnail
)