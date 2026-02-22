package io.github.cvieirasp.api.ingest

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.SQLException
import java.util.UUID

class EventRepositoryImpl : EventRepository {
    /**
     * Saves an event to the database.
     * If an event with the same idempotency key already exists, it returns false.
     * Otherwise, it inserts the event and returns true.
     * It handles SQL exceptions to ensure that only unique events are saved.
     *
     * @param event The event to be saved.
     * @return true if the event was saved successfully, false if an event with the same idempotency key already exists.
     */
    override fun save(event: Event): Boolean = try {
        transaction {
            EventTable.insert {
                it[id] = event.id
                it[sourceName] = event.sourceName
                it[eventType] = event.eventType
                it[idempotencyKey] = event.idempotencyKey
                it[payloadJson] = event.payloadJson
                it[correlationId] = event.correlationId
                it[receivedAt] = event.receivedAt
            }
        }
        true
    } catch (e: ExposedSQLException) {
        if ((e.cause as? SQLException)?.sqlState == "23505") false else throw e
    }

    override fun findById(id: UUID): Event? = transaction {
        EventTable
            .selectAll()
            .where { EventTable.id eq id }
            .map { it.toEvent() }
            .singleOrNull()
    }

    private fun ResultRow.toEvent() = Event(
        id            = this[EventTable.id],
        sourceName    = this[EventTable.sourceName],
        eventType     = this[EventTable.eventType],
        idempotencyKey = this[EventTable.idempotencyKey],
        payloadJson   = this[EventTable.payloadJson],
        correlationId = this[EventTable.correlationId],
        receivedAt    = this[EventTable.receivedAt],
    )
}
