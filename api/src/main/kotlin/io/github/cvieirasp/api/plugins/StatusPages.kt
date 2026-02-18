package io.github.cvieirasp.api.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

/**
 * Configures status pages for the application to handle exceptions and specific HTTP status codes.
 */
fun Application.configureStatusPages() {
    /**
     * Install the StatusPages plugin to handle exceptions and specific HTTP status codes.
     * This configuration will catch BadRequestException, IllegalArgumentException, and any other Throwable,
     * responding with appropriate HTTP status codes and error messages.
     */
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
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
