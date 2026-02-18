package io.github.cvieirasp.shared.config

import kotlinx.serialization.json.Json

val AppJson = Json {
    prettyPrint = false
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
