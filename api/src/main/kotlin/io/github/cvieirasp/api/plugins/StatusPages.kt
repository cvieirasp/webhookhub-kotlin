package io.github.cvieirasp.api.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

/**
 * Configures status pages for the application to handle exceptions and specific HTTP status codes.
 */
fun Application.configureStatusPages() {
    /** Install the StatusPages plugin to handle exceptions and specific HTTP status codes:
     * - exception<IllegalArgumentException>: Catches IllegalArgumentExceptions and responds with a 400 Bad Request status, including the error message in the response body.
     * - exception<Throwable>: Catches any other unhandled exceptions and responds with a 500 Internal Server Error status, including the error message in the response body.
     * - status(HttpStatusCode.NotFound): Handles 404 Not Found errors by responding with a 404 status and a "Not found" message in the response body.
     * - status(HttpStatusCode.MethodNotAllowed): Handles 405 Method Not Allowed errors by responding with a 405 status and a "Method not allowed" message in the response body.
     */
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorResponse("Method not allowed"))
        }
    }
}
