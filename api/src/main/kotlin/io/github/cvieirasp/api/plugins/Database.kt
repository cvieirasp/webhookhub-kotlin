package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.db.DatabaseFactory
import io.ktor.server.application.*

/**
 * Configures the database for the application.
 */
fun Application.configureDatabase() {
    DatabaseFactory.init()
}
