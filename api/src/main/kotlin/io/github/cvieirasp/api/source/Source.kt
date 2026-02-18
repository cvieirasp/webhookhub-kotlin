package io.github.cvieirasp.api.source

import kotlinx.datetime.Instant
import java.util.UUID

data class Source(
    val id: UUID,
    val name: String,
    val hmacSecret: String,
    val active: Boolean,
    val createdAt: Instant,
)
