package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.db.DatabaseFactory
import io.github.cvieirasp.api.db.DbPoolStats
import io.github.cvieirasp.api.source.SourceRepositoryImpl
import io.github.cvieirasp.api.source.SourceUseCase
import io.github.cvieirasp.api.source.sourceRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val db: String, val pool: DbPoolStats)

/**
 * Configure routing for the Ktor application.
 */
fun Application.configureRouting() {
    val sourceUseCase = SourceUseCase(SourceRepositoryImpl())

    routing {
        get("/health") {
            val dbUp = withContext(Dispatchers.IO) { DatabaseFactory.ping() }
            val dbStatus = if (dbUp) "UP" else "DOWN"
            val httpStatus = if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(httpStatus, HealthResponse(
                status = dbStatus,
                db = dbStatus,
                pool = DatabaseFactory.poolStats(),
            ))
        }

        sourceRoutes(sourceUseCase)
    }
}
