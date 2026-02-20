package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.destination.DestinationRepositoryImpl
import io.github.cvieirasp.api.destination.aDestination
import io.github.cvieirasp.api.ingest.Event
import io.github.cvieirasp.api.ingest.EventTable
import io.github.cvieirasp.api.setupTestDatabase
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for [DeliveryRepositoryImpl] using a real PostgreSQL database.
 */
@Testcontainers
class DeliveryIntegrationRepositoryTest {

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = TestPostgresContainer("postgres:18.2-alpine")
            .withDatabaseName("webhookhub-test")
            .withUsername("webhookhub")
            .withPassword("webhookhub")

        @JvmStatic
        @BeforeAll
        fun setup() { setupTestDatabase(postgres) }
    }

    private val repository = DeliveryRepositoryImpl()
    private val destinationRepository = DestinationRepositoryImpl()

    @AfterEach
    fun cleanup() {
        transaction {
            exec("DELETE FROM deliveries")
            exec("DELETE FROM destination_rules")
            exec("DELETE FROM destinations")
            exec("DELETE FROM events")
        }
    }

    private fun insertEvent(id: UUID = UUID.randomUUID()): UUID {
        transaction {
            EventTable.insert {
                it[EventTable.id] = id
                it[sourceName] = "github"
                it[eventType] = "push"
                it[idempotencyKey] = UUID.randomUUID().toString()
                it[payloadJson] = """{"ref":"main"}"""
                it[receivedAt] = Clock.System.now()
            }
        }
        return id
    }

    // region createPending

    @Test
    fun `createPending inserts delivery with PENDING status`() {
        val eventId = insertEvent()
        val destination = destinationRepository.create(aDestination())
        val delivery = aDelivery(eventId = eventId, destinationId = destination.id)

        repository.createPending(delivery)

        val row = transaction { DeliveryTable.selectAll().single() }
        assertEquals(delivery.id, row[DeliveryTable.id])
        assertEquals(eventId, row[DeliveryTable.eventId])
        assertEquals(destination.id, row[DeliveryTable.destinationId])
        assertEquals(DeliveryStatus.PENDING, row[DeliveryTable.status])
        assertEquals(0, row[DeliveryTable.attempts])
        assertEquals(5, row[DeliveryTable.maxAttempts])
        assertNull(row[DeliveryTable.lastError])
        assertNull(row[DeliveryTable.lastAttemptAt])
        assertNull(row[DeliveryTable.deliveredAt])
    }

    @Test
    fun `createPending returns the inserted delivery unchanged`() {
        val eventId = insertEvent()
        val destination = destinationRepository.create(aDestination())
        val delivery = aDelivery(eventId = eventId, destinationId = destination.id)

        val result = repository.createPending(delivery)

        assertEquals(delivery, result)
    }

    @Test
    fun `createPending throws on duplicate event_id + destination_id`() {
        val eventId = insertEvent()
        val destination = destinationRepository.create(aDestination())
        val delivery = aDelivery(eventId = eventId, destinationId = destination.id)
        repository.createPending(delivery)

        assertFailsWith<Exception> {
            repository.createPending(aDelivery(eventId = eventId, destinationId = destination.id))
        }
    }

    // endregion
}
