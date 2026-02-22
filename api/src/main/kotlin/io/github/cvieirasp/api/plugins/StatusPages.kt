package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.DuplicateEventException
import io.github.cvieirasp.api.NotFoundException
import io.github.cvieirasp.api.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * Ktor call attribute key used to carry the per-request correlation ID so that
 * the error handler can include it in every error response body.
 */
val CORRELATION_ID_KEY = AttributeKey<String>("correlationId")

@Serializable
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val correlationId: String?,
    val timestamp: String,
)

private fun ApplicationCall.buildError(httpStatus: HttpStatusCode, message: String) = ErrorResponse(
    status        = httpStatus.value,
    error         = httpStatus.description,
    message       = message,
    correlationId = attributes.getOrNull(CORRELATION_ID_KEY),
    timestamp     = Clock.System.now().toString(),
)

/**
 * Configures status pages for the application to handle exceptions and specific
 * HTTP status codes, returning a consistent JSON error body for every error.
 *
 * Response body shape:
 * ```json
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "type must not be blank",
 *   "correlationId": "d4f1…",   // null when not set on the request
 *   "timestamp": "2026-02-22T10:30:00.000Z"
 * }
 * ```
 *
 * Handled mappings:
 * - [IllegalArgumentException] / [BadRequestException] → 400 Bad Request
 * - [UnauthorizedException]                            → 401 Unauthorized
 * - [NotFoundException]                                → 404 Not Found
 * - [DuplicateEventException]                          → 409 Conflict
 * - Any other [Throwable]                              → 500 Internal Server Error
 * - Ktor 404 / 405 status responses                   → same JSON shape
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, call.buildError(HttpStatusCode.BadRequest, cause.message ?: "Bad request"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, call.buildError(HttpStatusCode.BadRequest, cause.message ?: "Bad request"))
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, call.buildError(HttpStatusCode.Unauthorized, cause.message ?: "Unauthorized"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, call.buildError(HttpStatusCode.NotFound, cause.message ?: "Not found"))
        }
        exception<DuplicateEventException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, call.buildError(HttpStatusCode.Conflict, cause.message ?: "Conflict"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, call.buildError(HttpStatusCode.InternalServerError, cause.message ?: "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, call.buildError(status, "Not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, call.buildError(status, "Method not allowed"))
        }
    }
}
