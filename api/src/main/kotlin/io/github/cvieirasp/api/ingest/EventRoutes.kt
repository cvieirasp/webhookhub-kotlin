package io.github.cvieirasp.api.ingest

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun Route.eventRoutes(useCase: EventUseCase) {
    route("/events") {
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
