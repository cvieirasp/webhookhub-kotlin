package io.github.cvieirasp.api.destination

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.setupTestDatabase
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for [DestinationRepositoryImpl] using a real PostgreSQL database.
 * Covers the full destination aggregate: destinations and their rules in a single transaction.
 */
@Testcontainers
class DestinationIntegrationRepositoryTest {

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

    private val repository = DestinationRepositoryImpl()

    @AfterEach
    fun cleanup() {
        transaction {
            exec("DELETE FROM destination_rules")
            exec("DELETE FROM destinations")
        }
    }

    // region findAll

    @Test
    fun `findAll returns empty list when table is empty`() {
        assertTrue(repository.findAll().isEmpty())
    }

    @Test
    fun `findAll returns all persisted destinations`() {
        repository.create(aDestination(name = "service-a"))
        repository.create(aDestination(name = "service-b"))

        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `findAll returns destinations ordered by createdAt descending`() {
        val now = Clock.System.now()
        repository.create(aDestination(name = "older", createdAt = now.minus(1.minutes)))
        repository.create(aDestination(name = "newer", createdAt = now))

        val result = repository.findAll()
        assertEquals("newer", result.first().name)
        assertEquals("older", result.last().name)
    }

    @Test
    fun `findAll returns destinations with their rules`() {
        val destination = aDestination(name = "service-a")
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.create(destination.copy(rules = listOf(rule)))

        val result = repository.findAll()
        assertEquals(1, result.size)
        assertEquals(1, result.first().rules.size)
        with(result.first().rules.first()) {
            assertEquals(rule.id, id)
            assertEquals("github", sourceName)
            assertEquals("push", eventType)
        }
    }

    // endregion

    // region findById

    @Test
    fun `findById returns destination when it exists`() {
        val destination = aDestination(name = "service-a")
        repository.create(destination)

        val result = repository.findById(destination.id)
        assertNotNull(result)
        assertEquals(destination.id, result.id)
        assertEquals("service-a", result.name)
    }

    @Test
    fun `findById returns null when destination does not exist`() {
        assertNull(repository.findById(UUID.randomUUID()))
    }

    @Test
    fun `findById returns destination with its rules`() {
        val destination = aDestination(name = "service-a")
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.create(destination.copy(rules = listOf(rule)))

        val result = repository.findById(destination.id)
        assertNotNull(result)
        assertEquals(1, result.rules.size)
        assertEquals(rule.id, result.rules.first().id)
    }

    @Test
    fun `findById returns only rules belonging to that destination`() {
        val d1 = aDestination(name = "service-a")
        val d2 = aDestination(name = "service-b")
        val rule1 = aDestinationRule(destinationId = d1.id, sourceName = "github", eventType = "push")
        val rule2 = aDestinationRule(destinationId = d2.id, sourceName = "stripe", eventType = "charge.created")
        repository.create(d1.copy(rules = listOf(rule1)))
        repository.create(d2.copy(rules = listOf(rule2)))

        val result = repository.findById(d1.id)
        assertNotNull(result)
        assertEquals(1, result.rules.size)
        assertEquals(rule1.id, result.rules.first().id)
    }

    // endregion

    // region create

    @Test
    fun `create persists destination and findAll returns it`() {
        val destination = aDestination(name = "service-a")
        repository.create(destination)

        val result = repository.findAll()
        assertEquals(1, result.size)
        with(result.first()) {
            assertEquals(destination.id, id)
            assertEquals("service-a", name)
            assertEquals(destination.targetUrl, targetUrl)
            assertTrue(active)
        }
    }

    @Test
    fun `create returns the inserted destination unchanged`() {
        val destination = aDestination(name = "service-a")
        val result = repository.create(destination)
        assertEquals(destination, result)
    }

    @Test
    fun `create persists destination and rules atomically`() {
        val destination = aDestination(name = "service-a")
        val rule1 = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        val rule2 = aDestinationRule(destinationId = destination.id, sourceName = "stripe", eventType = "charge.created")
        repository.create(destination.copy(rules = listOf(rule1, rule2)))

        val result = repository.findById(destination.id)
        assertNotNull(result)
        assertEquals(2, result.rules.size)
        assertTrue(result.rules.any { it.id == rule1.id })
        assertTrue(result.rules.any { it.id == rule2.id })
    }

    // endregion

    // region findBySourceNameAndEventType

    @Test
    fun `findBySourceNameAndEventType returns matching active destinations`() {
        val destination = aDestination(name = "service-a", targetUrl = "https://example.com/hook")
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.create(destination.copy(rules = listOf(rule)))

        val result = repository.findBySourceNameAndEventType("github", "push")

        assertEquals(1, result.size)
        assertEquals(destination.id, result.first().id)
        assertEquals("https://example.com/hook", result.first().targetUrl)
    }

    @Test
    fun `findBySourceNameAndEventType ignores inactive destinations`() {
        val destination = aDestination(name = "service-a", active = false)
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.create(destination.copy(rules = listOf(rule)))

        val result = repository.findBySourceNameAndEventType("github", "push")

        assertTrue(result.isEmpty())
    }

    // endregion

    // region addRule

    @Test
    fun `addRule persists rule inside the destination`() {
        val destination = aDestination(name = "service-a")
        repository.create(destination)

        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.addRule(destination.id, rule)

        val result = repository.findById(destination.id)
        assertNotNull(result)
        assertEquals(1, result.rules.size)
        with(result.rules.first()) {
            assertEquals(rule.id, id)
            assertEquals(destination.id, destinationId)
            assertEquals("github", sourceName)
            assertEquals("push", eventType)
        }
    }

    @Test
    fun `addRule returns the inserted rule unchanged`() {
        val destination = aDestination(name = "service-a")
        repository.create(destination)

        val rule = aDestinationRule(destinationId = destination.id)
        val result = repository.addRule(destination.id, rule)
        assertEquals(rule, result)
    }

    @Test
    fun `addRule throws on duplicate destination_id + source_name + event_type`() {
        val destination = aDestination(name = "service-a")
        repository.create(destination)

        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        repository.addRule(destination.id, rule)

        assertFailsWith<Exception> {
            repository.addRule(destination.id, aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push"))
        }
    }

    // endregion
}
