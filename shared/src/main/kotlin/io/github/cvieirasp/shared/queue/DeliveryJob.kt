package io.github.cvieirasp.shared.queue

import kotlinx.serialization.Serializable

@Serializable
data class DeliveryJob(
    val deliveryId: String,
    val eventId: String,
    val destinationId: String,
    val targetUrl: String,
    val payloadJson: String,
    val attempt: Int = 1,
)
