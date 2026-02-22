package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.delivery.DeliveryStatus
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class EventResponse(
    val id: String,
    val sourceName: String,
    val type: String,
    val idempotencyKey: String,
    val correlationId: String,
    val createdAt: String,
    val payload: String,
)

@Serializable
data class EventListResponse(
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
    val items: List<EventResponse>,
)

fun Route.eventRoutes(useCase: EventUseCase) {
    route("/events") {
        get {
            val sourceName = call.request.queryParameters["sourceName"]
            val eventType  = call.request.queryParameters["type"]
            val statusRaw  = call.request.queryParameters["status"]
            val dateFromRaw = call.request.queryParameters["dateFrom"]
            val dateToRaw   = call.request.queryParameters["dateTo"]
            val page     = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = (call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

            val status   = statusRaw?.let { DeliveryStatus.valueOf(it.uppercase()) }
            val dateFrom = dateFromRaw?.let { Instant.parse(it) }
            val dateTo   = dateToRaw?.let { Instant.parse(it) }

            val filter = EventFilter(
                sourceName = sourceName,
                eventType  = eventType,
                status     = status,
                dateFrom   = dateFrom,
                dateTo     = dateTo,
            )
            val (total, events) = withContext(Dispatchers.IO) { useCase.listEvents(filter, page, pageSize) }
            call.respond(HttpStatusCode.OK, EventListResponse(
                totalCount = total,
                page       = page,
                pageSize   = pageSize,
                items      = events.map { it.toResponse() },
            ))
        }

        get("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val event = withContext(Dispatchers.IO) { useCase.getEvent(id) }
            call.respond(HttpStatusCode.OK, event.toResponse())
        }
    }
}

private fun Event.toResponse() = EventResponse(
    id             = id.toString(),
    sourceName     = sourceName,
    type           = eventType,
    idempotencyKey = idempotencyKey,
    correlationId  = correlationId,
    createdAt      = receivedAt.toString(),
    payload        = payloadJson,
)
