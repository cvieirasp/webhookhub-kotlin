package io.github.cvieirasp.api.ingest

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

/**
 * JsonbColumnType is a custom column type for handling JSONB data in PostgreSQL using Exposed.
 * It extends StringColumnType and overrides the sqlType and setParameter methods to ensure that
 * the JSONB data is correctly handled when interacting with the database.
 */
private class JsonbColumnType : StringColumnType() {
    override fun sqlType() = "JSONB"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject().apply {
            type = "jsonb"
            this.value = value as? String
        }
        super.setParameter(stmt, index, obj)
    }
}

private fun Table.jsonb(name: String): Column<String> =
    registerColumn(name, JsonbColumnType())

object EventTable : Table("events") {
    val id = uuid("id")
    val sourceName = text("source_name")
    val eventType = text("event_type")
    val idempotencyKey = text("idempotency_key")
    val payloadJson = jsonb("payload_json")
    val receivedAt = timestamp("received_at")
    override val primaryKey = PrimaryKey(id)
}
