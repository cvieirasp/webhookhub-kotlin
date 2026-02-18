package io.github.cvieirasp.api.source

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Defines the database table for sources.
 */
object SourceTable : Table("sources") {
    val id = uuid("id")
    val name = text("name")
    val hmacSecret = text("hmac_secret")
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
