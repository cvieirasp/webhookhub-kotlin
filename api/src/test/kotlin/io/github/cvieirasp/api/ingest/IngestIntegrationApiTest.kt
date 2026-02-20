package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.delivery.DeliveryRepositoryImpl
import io.github.cvieirasp.api.delivery.DeliveryStatus
import io.github.cvieirasp.api.delivery.DeliveryTable
import io.github.cvieirasp.api.delivery.FakeDeliveryPublisher
import io.github.cvieirasp.api.destination.DestinationRepositoryImpl
import io.github.cvieirasp.api.destination.DestinationRuleTable
import io.github.cvieirasp.api.destination.DestinationTable
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.setupTestDatabase
import io.github.cvieirasp.api.source.SourceRepositoryImpl
import io.github.cvieirasp.api.source.SourceTable
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/**
 * Integration tests for [ingestRoutes] using a real PostgreSQL database.
 */
@Testcontainers
class IngestIntegrationApiTest {

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
            exec("DELETE FROM destination_rules")
            exec("DELETE FROM destinations")
            exec("DELETE FROM events")
            exec("DELETE FROM sources")
        }
    }

    private fun computeHmac(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun insertSource(name: String, secret: String, active: Boolean = true) {
        transaction {
            SourceTable.insert {
                it[id] = UUID.randomUUID()
                it[SourceTable.name] = name
                it[hmacSecret] = secret
                it[SourceTable.active] = active
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun testApp(
        deliveryPublisher: FakeDeliveryPublisher = FakeDeliveryPublisher(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                ingestRoutes(
                    IngestUseCase(
                        SourceRepositoryImpl(),
                        EventRepositoryImpl(),
                        DestinationRepositoryImpl(),
                        DeliveryRepositoryImpl(),
                        deliveryPublisher,
                    )
                )
            }
        }
        block()
    }

    // region POST /ingest/{sourceName}

    @Test
    fun `POST ingest returns 202 for valid signature against persisted source`() {
        val secret = "e0a2a9114f4890d9d4d1e695068437cf3d0f5cc3984b25a687a8fcdd76b07736"
        insertSource("github", secret)
        val body = """{"event":"push"}"""
        val signature = computeHmac(secret, body.toByteArray())

        testApp {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }
    }

    @Test
    fun `POST ingest returns 401 for invalid signature`() {
        insertSource("github", "a".repeat(64))

        testApp {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", "wrong-signature")
                setBody("webhook-payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST ingest returns 404 when source does not exist in DB`() = testApp {
        val response = client.post("/ingest/nonexistent?type=push") {
            header("X-Signature", "any-signature")
            setBody("payload")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST ingest returns 401 when source is inactive`() {
        insertSource("github", "a".repeat(64), active = false)

        testApp {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", "any-signature")
                setBody("payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST ingest returns 400 when type parameter is missing`() = testApp {
        val response = client.post("/ingest/github") {
            header("X-Signature", "any-signature")
            setBody("payload")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `duplicate POST ingest both return 202 and events table contains exactly one row`() {
        val secret = "a".repeat(64)
        insertSource("github", secret)
        val body = """{"event":"push"}"""
        val signature = computeHmac(secret, body.toByteArray())

        testApp {
            val first = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }
            val second = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }

            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.Accepted, second.status)
        }

        val count = transaction { EventTable.selectAll().count() }
        assertEquals(1L, count)
    }

    @Test
    fun `POST ingest creates PENDING delivery records for matching destinations`() {
        val secret = "a".repeat(64)
        insertSource("github", secret)
        val destinationId = UUID.randomUUID()
        transaction {
            DestinationTable.insert {
                it[id] = destinationId
                it[name] = "my-service"
                it[targetUrl] = "https://example.com/hook"
                it[active] = true
                it[createdAt] = Clock.System.now()
            }
            DestinationRuleTable.insert {
                it[id] = UUID.randomUUID()
                it[DestinationRuleTable.destinationId] = destinationId
                it[sourceName] = "github"
                it[eventType] = "push"
            }
        }
        val body = """{"event":"push"}"""
        val signature = computeHmac(secret, body.toByteArray())
        val publisher = FakeDeliveryPublisher()

        testApp(deliveryPublisher = publisher) {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

        val delivery = transaction { DeliveryTable.selectAll().single() }
        assertEquals(destinationId, delivery[DeliveryTable.destinationId])
        assertEquals(DeliveryStatus.PENDING, delivery[DeliveryTable.status])
        assertEquals(0, delivery[DeliveryTable.attempts])

        assertEquals(1, publisher.published.size)
        val job = publisher.published.first()
        assertEquals(destinationId.toString(), job.destinationId)
        assertEquals("https://example.com/hook", job.targetUrl)
        assertEquals(body, job.payloadJson)
    }

    // endregion
}
