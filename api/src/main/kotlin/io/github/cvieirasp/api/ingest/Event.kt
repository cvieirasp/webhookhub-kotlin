package io.github.cvieirasp.api.ingest

import kotlinx.datetime.Instant
import java.util.UUID

data class Event(
    val id: UUID,
    val sourceName: String,
    val eventType: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val receivedAt: Instant,
)
