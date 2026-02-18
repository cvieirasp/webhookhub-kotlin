package io.github.cvieirasp.api.source

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class CreateSourceRequest(val name: String)

@Serializable
data class SourceCreatedResponse(
    val id: String,
    val name: String,
    val hmacSecret: String,
    val active: Boolean,
    val createdAt: String,
)

@Serializable
data class SourceSummaryResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val createdAt: String,
)

/**
 * Defines the API routes for managing sources.
 *
 * @param useCase The use case for handling source-related operations.
 */
fun Route.sourceRoutes(useCase: SourceUseCase) {
    route("/sources") {
        get {
            val sources = withContext(Dispatchers.IO) { useCase.listSources() }
            call.respond(HttpStatusCode.OK, sources.map { it.toSummaryResponse() })
        }

        post {
            val request = call.receive<CreateSourceRequest>()
            val source = withContext(Dispatchers.IO) { useCase.createSource(request.name) }
            call.respond(HttpStatusCode.Created, source.toCreatedResponse())
        }
    }
}

private fun Source.toCreatedResponse() = SourceCreatedResponse(
    id = id.toString(),
    name = name,
    hmacSecret = hmacSecret,
    active = active,
    createdAt = createdAt.toString(),
)

private fun Source.toSummaryResponse() = SourceSummaryResponse(
    id = id.toString(),
    name = name,
    active = active,
    createdAt = createdAt.toString(),
)
