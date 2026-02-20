package io.github.cvieirasp.api.ingest

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val sourceName = call.parameters["sourceName"]!!
        val eventType = call.queryParameters["type"] ?: ""
        val signature = call.request.headers["X-Signature"] ?: ""
        val rawBody = call.receive<ByteArray>()

        withContext(Dispatchers.IO) {
            useCase.ingest(sourceName, eventType, rawBody, signature)
        }

        call.respond(HttpStatusCode.Accepted)
    }
}
