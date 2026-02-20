package io.github.cvieirasp.api.delivery

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.postgresql.util.PGobject

/**
 * Defines the database table for deliveries.
 */
object DeliveryTable : Table("deliveries") {
    val id = uuid("id")
    val eventId = uuid("event_id")
    val destinationId = uuid("destination_id")
    val status = customEnumeration(
        name = "status",
        sql = "delivery_status",
        fromDb = { value ->
            val str = if (value is PGobject) value.value!! else value as String
            DeliveryStatus.valueOf(str)
        },
        toDb = { pgEnum -> PGobject().apply { type = "delivery_status"; value = pgEnum.name } },
    )
    val attempts = integer("attempts").default(0)
    val maxAttempts = integer("max_attempts").default(5)
    val lastError = text("last_error").nullable()
    val lastAttemptAt = timestamp("last_attempt_at").nullable()
    val deliveredAt = timestamp("delivered_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
