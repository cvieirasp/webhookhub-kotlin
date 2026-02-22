package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.delivery.DeliveryTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.exists
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

    override fun findFiltered(filter: EventFilter, page: Int, pageSize: Int): Pair<Long, List<Event>> = transaction {
        fun buildQuery() = EventTable.selectAll().apply {
            filter.sourceName?.let { v -> andWhere { EventTable.sourceName eq v } }
            filter.eventType?.let { v -> andWhere { EventTable.eventType eq v } }
            filter.dateFrom?.let { v -> andWhere { EventTable.receivedAt greaterEq v } }
            filter.dateTo?.let { v -> andWhere { EventTable.receivedAt lessEq v } }
            filter.status?.let { s ->
                andWhere {
                    exists(
                        DeliveryTable
                            .selectAll()
                            .where { DeliveryTable.eventId eq EventTable.id }
                            .andWhere { DeliveryTable.status eq s }
                    )
                }
            }
        }
        val total = buildQuery().count()
        val items = buildQuery()
            .orderBy(EventTable.receivedAt to SortOrder.DESC)
            .limit(pageSize, ((page - 1) * pageSize).toLong())
            .map { it.toEvent() }
        total to items
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
