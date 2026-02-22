package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.delivery.DeliveryStatus
import io.github.cvieirasp.api.delivery.DeliveryTable
import io.github.cvieirasp.api.destination.DestinationTable
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.postgresql.util.PGobject
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
            exec("DELETE FROM destinations")
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

    // ── helpers for list tests ─────────────────────────────────────────────

    private fun insertDestination(id: UUID = UUID.randomUUID()): UUID {
        transaction {
            DestinationTable.insert {
                it[DestinationTable.id]        = id
                it[DestinationTable.name]      = "dest-$id"
                it[DestinationTable.targetUrl] = "https://example.com/hook"
                it[DestinationTable.active]    = true
                it[DestinationTable.createdAt] = Clock.System.now()
            }
        }
        return id
    }

    private fun insertDelivery(eventId: UUID, destinationId: UUID, status: DeliveryStatus) {
        transaction {
            DeliveryTable.insert {
                it[DeliveryTable.id]            = UUID.randomUUID()
                it[DeliveryTable.eventId]       = eventId
                it[DeliveryTable.destinationId] = destinationId
                it[DeliveryTable.status]        = status
                it[DeliveryTable.attempts]      = 0
                it[DeliveryTable.maxAttempts]   = 5
                it[DeliveryTable.createdAt]     = Clock.System.now()
            }
        }
    }

    // ── GET /events list tests ─────────────────────────────────────────────

    @Test
    fun `GET events returns 200 with all events when no filter`() {
        insertEvent(sourceName = "github", eventType = "push")
        insertEvent(sourceName = "stripe", eventType = "charge.created")

        testApp {
            val response = client.get("/events")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(1,  body["page"]!!.jsonPrimitive.content.toInt())
            assertEquals(20, body["pageSize"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET events filters by sourceName`() {
        insertEvent(sourceName = "github", eventType = "push")
        insertEvent(sourceName = "stripe", eventType = "charge.created")

        testApp {
            val response = client.get("/events?sourceName=github")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            val items = body["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("github", items[0].jsonObject["sourceName"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events filters by type`() {
        insertEvent(sourceName = "github", eventType = "push")
        insertEvent(sourceName = "github", eventType = "delete")

        testApp {
            val response = client.get("/events?type=push")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals("push", body["items"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events filters by status using EXISTS subquery`() {
        val destId       = insertDestination()
        val deliveredId  = insertEvent(sourceName = "github", eventType = "push",   idempotencyKey = "idem-delivered")
        val pendingId    = insertEvent(sourceName = "github", eventType = "delete",  idempotencyKey = "idem-pending")
        insertDelivery(deliveredId, destId, DeliveryStatus.DELIVERED)
        insertDelivery(pendingId,   destId, DeliveryStatus.PENDING)

        testApp {
            val response = client.get("/events?status=DELIVERED")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            val items = body["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(deliveredId.toString(), items[0].jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events returns paginated results with correct totalCount`() {
        repeat(5) { i -> insertEvent(sourceName = "github", eventType = "push", idempotencyKey = "idem-page-$i") }

        testApp {
            val response = client.get("/events?page=2&pageSize=2")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(5L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(2,  body["page"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["pageSize"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET events returns 400 for invalid status value`() = testApp {
        val response = client.get("/events?status=BOGUS")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET events returns 400 for invalid dateFrom value`() = testApp {
        val response = client.get("/events?dateFrom=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
