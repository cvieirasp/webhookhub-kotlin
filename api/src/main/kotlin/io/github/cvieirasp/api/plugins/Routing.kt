package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.db.DatabaseFactory
import io.github.cvieirasp.api.db.DbPoolStats
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
 * This file is for configuring routing in the Ktor application.
 * It defines a health check endpoint at /health that checks the database connection and returns its status.
 * The response includes the overall status, database status, and connection pool statistics.
 */
fun Application.configureRouting() {
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
    }
}
