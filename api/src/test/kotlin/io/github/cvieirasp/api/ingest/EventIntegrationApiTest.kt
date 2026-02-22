package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.setupTestDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests for [eventRoutes] using a real PostgreSQL database.
 */
@Testcontainers
class EventIntegrationApiTest {

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

    @AfterEach
    fun cleanup() {
        transaction {
            exec("DELETE FROM deliveries")
            exec("DELETE FROM events")
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { eventRoutes(EventUseCase(EventRepositoryImpl())) }
        }
        block()
    }

    private fun insertEvent(
        id: UUID = UUID.randomUUID(),
        sourceName: String = "github",
        eventType: String = "push",
        idempotencyKey: String = UUID.randomUUID().toString(),
        payloadJson: String = """{"ref":"main"}""",
        correlationId: String = UUID.randomUUID().toString(),
    ): UUID {
        transaction {
            EventTable.insert {
                it[EventTable.id]             = id
                it[EventTable.sourceName]     = sourceName
                it[EventTable.eventType]      = eventType
                it[EventTable.idempotencyKey] = idempotencyKey
                it[EventTable.payloadJson]    = payloadJson
                it[EventTable.correlationId]  = correlationId
                it[EventTable.receivedAt]     = Clock.System.now()
            }
        }
        return id
    }

    @Test
    fun `GET events by id returns 200 with all fields for persisted event`() {
        val correlationId = UUID.randomUUID().toString()
        val id = insertEvent(
            sourceName    = "stripe",
            eventType     = "charge.created",
            idempotencyKey = "idem-abc",
            payloadJson   = """{"amount":100}""",
            correlationId = correlationId,
        )

        testApp {
            val response = client.get("/events/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(id.toString(),    body["id"]!!.jsonPrimitive.content)
            assertEquals("stripe",         body["sourceName"]!!.jsonPrimitive.content)
            assertEquals("charge.created", body["type"]!!.jsonPrimitive.content)
            assertEquals("idem-abc",       body["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(correlationId,    body["correlationId"]!!.jsonPrimitive.content)
            // JSONB normalises whitespace on write; compare as parsed JSON
            assertEquals(
                Json.parseToJsonElement("""{"amount":100}"""),
                Json.parseToJsonElement(body["payload"]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun `GET events by id returns 404 when event does not exist in DB`() = testApp {
        val response = client.get("/events/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
