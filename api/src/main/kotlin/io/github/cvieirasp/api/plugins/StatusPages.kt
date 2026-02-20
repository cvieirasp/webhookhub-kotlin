package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.NotFoundException
import io.github.cvieirasp.api.UnauthorizedException
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
     * Catches specific exceptions and responds with appropriate HTTP status codes and error messages.
     *
     * - [UnauthorizedException]: Responds with 401 Unauthorized.
     * - [NotFoundException]: Responds with 404 Not Found.
     * - [BadRequestException] and [IllegalArgumentException]: Responds with 400 Bad Request.
     * - Any other [Throwable]: Responds with 500 Internal Server Error.
     *
     * Also handles specific HTTP status codes:
     * - 404 Not Found: Responds with a JSON error message.
     * - 405 Method Not Allowed: Responds with a JSON error message.
     */
    install(StatusPages) {
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(cause.message ?: "Unauthorized"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
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
