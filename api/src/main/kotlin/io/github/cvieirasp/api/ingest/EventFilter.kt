package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.delivery.DeliveryStatus
import kotlinx.datetime.Instant

data class EventFilter(
    val sourceName: String? = null,
    val eventType: String? = null,
    val status: DeliveryStatus? = null,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
)
