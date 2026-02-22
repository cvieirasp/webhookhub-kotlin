package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.plugins.CORRELATION_ID_KEY
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

/**
 * Defines the webhook ingestion route.
 *
 * POST /ingest/{sourceName}?type={eventType}
 *
 * Reads the raw request body, validates the HMAC-SHA256 signature provided in
 * the X-Signature header, and returns 202 Accepted if the signature is valid.
 * Duplicate deliveries (same idempotency key) are silently accepted with 202.
 */
fun Route.ingestRoutes(useCase: IngestUseCase) {
    post("/ingest/{sourceName}") {
        val correlationId = UUID.randomUUID().toString()
        call.attributes.put(CORRELATION_ID_KEY, correlationId)
        val sourceName = call.parameters["sourceName"]!!
        val eventType = call.queryParameters["type"] ?: ""
        val signature = call.request.headers["X-Signature"] ?: ""
        val rawBody = call.receive<ByteArray>()

        withContext(Dispatchers.IO) {
            MDC.put("correlationId", correlationId)
            try {
                useCase.ingest(sourceName, eventType, rawBody, signature, correlationId)
            } finally {
                MDC.remove("correlationId")
            }
        }

        call.respond(HttpStatusCode.Accepted)
    }
}
