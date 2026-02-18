package io.github.cvieirasp.api.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Configures JSON serialization for the application using kotlinx.serialization.
 */
fun Application.configureSerialization() {
    /** Install the ContentNegotiation plugin and configure it to use JSON serialization with specific settings:
     * - prettyPrint: Enables pretty printing of JSON output for better readability.
     * - isLenient: Allows lenient parsing of JSON input, which can be useful for handling non-standard JSON formats.
     * - ignoreUnknownKeys: Ignores unknown keys in the JSON input, preventing errors when the input contains fields that are not defined in the data classes.
     */
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}
