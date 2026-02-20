package io.github.cvieirasp.api.delivery

import kotlinx.datetime.Instant
import java.util.UUID

data class Delivery(
    val id: UUID,
    val eventId: UUID,
    val destinationId: UUID,
    val status: DeliveryStatus,
    val attempts: Int = 0,
    val maxAttempts: Int = 5,
    val lastError: String? = null,
    val lastAttemptAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val createdAt: Instant,
)
