package io.github.cvieirasp.worker

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.MessageProperties
import com.sun.net.httpserver.HttpServer
import io.github.cvieirasp.shared.config.AppJson
import io.github.cvieirasp.shared.queue.DeliveryJob
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import io.github.cvieirasp.worker.db.DatabaseFactory
import io.github.cvieirasp.worker.delivery.DeliveryRepositoryImpl
import io.github.cvieirasp.worker.http.HttpDeliveryClient
import io.github.cvieirasp.worker.queue.DeliveryConsumer
import kotlinx.serialization.encodeToString
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for the delivery retry flow.
 *
 * Every test runs against real infrastructure — PostgreSQL and RabbitMQ spin up
 * via TestContainers — with a lightweight JDK [HttpServer] acting as the
 * webhook target so response codes can be controlled per-attempt.
 *
 * The consumer is configured with a short [BASE_DELAY_MS] so the full retry
 * cycle (including broker-side TTL expiry and dead-letter routing) completes in
 * well under a second.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryRetryFlowIntegrationTest {

    // ── Infrastructure containers ─────────────────────────────────────────────
    // Declared as static (@JvmField in companion) so TestContainers starts them
    // once before @BeforeAll rather than per test method.

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.2-alpine").apply {
            withDatabaseName("webhookhub-test")
            withUsername("webhookhub")
            withPassword("webhookhub")
        }

        @Container
        @JvmField
        val rabbit = RabbitMQContainer("rabbitmq:4.2-management-alpine")

        /** Short base delay so the full retry cycle finishes in milliseconds. */
        private const val BASE_DELAY_MS = 100L

        /** Reduced attempt ceiling so the "exhausted" path is reached quickly. */
        private const val MAX_ATTEMPTS  = 3

        /** How long to wait for an async state change before failing the test. */
        private const val AWAIT_MS = 8_000L
        private const val POLL_MS  =    50L
    }

    // ── Shared test state ─────────────────────────────────────────────────────

    /** HTTP mock target — response code per request driven by [responseQueue]. */
    private val responseQueue = ConcurrentLinkedQueue<Int>()
    private lateinit var httpServer: HttpServer
    private lateinit var targetUrl: String

    /** Single HTTP client reused across consumer instances in all tests. */
    private lateinit var httpClient: HttpDeliveryClient

    /** Dedicated RabbitMQ connection / channel used only for test assertions. */
    private lateinit var connection: Connection
    private lateinit var ch: Channel

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    fun setup() {
        // Database — run full schema migration, then wire Exposed to the same pool
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

        // RabbitMQ — one connection for assertion operations; topology declared once
        connection = ConnectionFactory().apply {
            host        = rabbit.host
            port        = rabbit.getMappedPort(5672)
            username    = rabbit.adminUsername
            password    = rabbit.adminPassword
            virtualHost = "/"
        }.newConnection()
        ch = connection.createChannel()
        RabbitMQTopology.declare(ch)

        // HTTP mock server — one context, response code driven per test via responseQueue
        httpServer = HttpServer.create(InetSocketAddress(0), 0).also { srv ->
            srv.createContext("/webhook") { exchange ->
                val code = responseQueue.poll() ?: 200
                exchange.sendResponseHeaders(code, 0)
                exchange.responseBody.close()
            }
            srv.executor = Executors.newSingleThreadExecutor()
            srv.start()
        }
        targetUrl = "http://127.0.0.1:${httpServer.address.port}/webhook"

        httpClient = HttpDeliveryClient()
    }

    @AfterAll
    fun teardown() {
        httpClient.close()
        httpServer.stop(0)
        ch.close()
        connection.close()
        DatabaseFactory.close()
    }

    @BeforeEach
    fun reset() {
        // Drain all queues so messages from previous tests don't bleed through
        ch.queuePurge(RabbitMQTopology.QUEUE_DELIVERIES)
        ch.queuePurge(RabbitMQTopology.QUEUE_RETRY)
        ch.queuePurge(RabbitMQTopology.QUEUE_DLQ)
        responseQueue.clear()

        // Wipe delivery-related rows (respect FK order)
        transaction {
            exec("DELETE FROM deliveries")
            exec("DELETE FROM events")
            exec("DELETE FROM destinations")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Inserts the minimal set of rows needed so a delivery record can be
     * persisted (satisfying the FK constraints on events and destinations).
     */
    private fun insertDelivery(deliveryId: UUID): UUID {
        val eventId       = UUID.randomUUID()
        val destinationId = UUID.randomUUID()
        transaction {
            exec("""
                INSERT INTO destinations(id, name, target_url, created_at)
                VALUES ('$destinationId', 'test-destination', '$targetUrl', NOW())
            """.trimIndent())
            exec("""
                INSERT INTO events(id, source_name, event_type, idempotency_key, payload_json, received_at)
                VALUES ('$eventId', 'test-source', 'push', '${UUID.randomUUID()}', '{}', NOW())
            """.trimIndent())
            exec("""
                INSERT INTO deliveries(id, event_id, destination_id, status, attempts, max_attempts, created_at)
                VALUES ('$deliveryId', '$eventId', '$destinationId', 'PENDING'::delivery_status, 0, $MAX_ATTEMPTS, NOW())
            """.trimIndent())
        }
        return deliveryId
    }

    /** Publishes a [DeliveryJob] to the main exchange so the consumer picks it up. */
    private fun publishJob(deliveryId: UUID, attempt: Int = 1) {
        val job = DeliveryJob(
            deliveryId    = deliveryId.toString(),
            eventId       = UUID.randomUUID().toString(),
            destinationId = UUID.randomUUID().toString(),
            targetUrl     = targetUrl,
            payloadJson   = """{"test":true}""",
            attempt       = attempt,
        )
        ch.basicPublish(
            RabbitMQTopology.EXCHANGE,
            RabbitMQTopology.ROUTING_KEY_DELIVERY,
            MessageProperties.PERSISTENT_BASIC,
            AppJson.encodeToString(job).toByteArray(),
        )
    }

    private data class DeliveryState(val status: String, val attempts: Int, val lastError: String?)

    private fun queryDelivery(id: UUID): DeliveryState = transaction {
        exec("SELECT status, attempts, last_error FROM deliveries WHERE id = '$id'") { rs ->
            check(rs.next()) { "Delivery $id not found in database" }
            DeliveryState(
                status    = rs.getString("status"),
                attempts  = rs.getInt("attempts"),
                lastError = rs.getString("last_error"),
            )
        }!!
    }

    /**
     * Polls the database every [POLL_MS] ms until [deliveryId]'s status is one
     * of [expected], then returns the full row.  Fails the test after [AWAIT_MS].
     */
    private fun awaitDeliveryStatus(deliveryId: UUID, expected: Set<String>): DeliveryState {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = queryDelivery(deliveryId)
            if (state.status in expected) return state
            Thread.sleep(POLL_MS)
        }
        error("Timed out after ${AWAIT_MS}ms waiting for delivery $deliveryId to reach $expected " +
              "(current: ${queryDelivery(deliveryId).status})")
    }

    /**
     * Polls [queueName] every [POLL_MS] ms until a message is available, then
     * returns it without auto-acking.  Fails the test after [AWAIT_MS].
     */
    private fun awaitQueueMessage(queueName: String): com.rabbitmq.client.GetResponse {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val msg = ch.basicGet(queueName, false)
            if (msg != null) return msg
            Thread.sleep(POLL_MS)
        }
        error("Timed out after ${AWAIT_MS}ms waiting for a message in $queueName")
    }

    /**
     * Creates a fresh consumer channel and registers a [DeliveryConsumer] on it.
     * Returns the channel so the caller can cancel or close it when done.
     */
    private fun startConsumer(): Channel {
        val channel = connection.createChannel()
        channel.basicQos(1)
        channel.basicConsume(
            RabbitMQTopology.QUEUE_DELIVERIES,
            false, // manual ack
            DeliveryConsumer(
                channel      = channel,
                repository   = DeliveryRepositoryImpl(),
                httpClient   = httpClient,
                baseDelayMs  = BASE_DELAY_MS,
                maxAttempts  = MAX_ATTEMPTS,
            ),
        )
        return channel
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `retryable failure writes RETRYING to DB and publishes job to retry queue with correct expiration`() {
        val deliveryId = UUID.randomUUID()
        insertDelivery(deliveryId)
        responseQueue.add(500)  // single attempt → 500 Internal Server Error

        val consumerChannel = startConsumer()
        publishJob(deliveryId)

        try {
            // ── DB assertions ─────────────────────────────────────────────────
            val state = awaitDeliveryStatus(deliveryId, setOf("RETRYING"))

            assertEquals("RETRYING", state.status)
            assertEquals(2, state.attempts,
                "attempts should be the incremented next-attempt number")
            assertTrue(state.lastError?.contains("500") == true,
                "lastError should record the HTTP 500 response, was: ${state.lastError}")

            // ── Retry-queue assertions ────────────────────────────────────────
            // The message must be in deliveries.retry.q; the consumer does not
            // subscribe there so it will stay until the TTL expires.
            val response = awaitQueueMessage(RabbitMQTopology.QUEUE_RETRY)

            assertEquals(BASE_DELAY_MS.toString(), response.props.expiration,
                "AMQP expiration must equal the backoff delay for this attempt")

            val job = AppJson.decodeFromString<DeliveryJob>(String(response.body))
            assertEquals(deliveryId.toString(), job.deliveryId)
            assertEquals(2, job.attempt,
                "republished job must carry the incremented attempt number")

            // Ack to discard — we don't want the TTL to expire and route it back
            ch.basicAck(response.envelope.deliveryTag, false)
        } finally {
            consumerChannel.close()
        }
    }

    @Test
    fun `message published to retry queue is automatically routed back to main delivery queue after TTL expiry`() {
        // No consumer involved — this test exercises pure broker topology.
        val deliveryId = UUID.randomUUID()
        val job = DeliveryJob(
            deliveryId    = deliveryId.toString(),
            eventId       = UUID.randomUUID().toString(),
            destinationId = UUID.randomUUID().toString(),
            targetUrl     = targetUrl,
            payloadJson   = """{"test":true}""",
            attempt       = 2,
        )

        // Publish directly to the retry queue with a short TTL
        val props = MessageProperties.PERSISTENT_BASIC.builder()
            .expiration(BASE_DELAY_MS.toString())
            .build()
        ch.basicPublish("", RabbitMQTopology.QUEUE_RETRY, props, AppJson.encodeToString(job).toByteArray())

        // After TTL expiry the broker dead-letters the message back to the main
        // exchange (x-dead-letter-exchange = webhookhub, DLRK = delivery),
        // which routes it to webhookhub.deliveries.
        val response = awaitQueueMessage(RabbitMQTopology.QUEUE_DELIVERIES)
        ch.basicAck(response.envelope.deliveryTag, false)

        val routedBack = AppJson.decodeFromString<DeliveryJob>(String(response.body))
        assertEquals(deliveryId.toString(), routedBack.deliveryId)
        assertEquals(2, routedBack.attempt,
            "message body must be preserved intact through the retry-queue TTL round-trip")
    }

    @Test
    fun `retry cycle reaches DELIVERED when the HTTP endpoint succeeds on a subsequent attempt`() {
        val deliveryId = UUID.randomUUID()
        insertDelivery(deliveryId)
        // Attempt 1 → 500 (retryable); attempt 2 → 200 (success)
        responseQueue.addAll(listOf(500, 200))

        val consumerChannel = startConsumer()
        publishJob(deliveryId)

        try {
            val state = awaitDeliveryStatus(deliveryId, setOf("DELIVERED"))

            assertEquals("DELIVERED", state.status)
            assertEquals(2, state.attempts,
                "attempts should reflect the attempt number that succeeded")
            assertNull(state.lastError,
                "lastError must be cleared on successful delivery")
        } finally {
            consumerChannel.close()
        }
    }

    @Test
    fun `retry cycle reaches DEAD and publishes job to DLQ when all attempts are exhausted`() {
        val deliveryId = UUID.randomUUID()
        insertDelivery(deliveryId)
        // Every attempt returns 500; with MAX_ATTEMPTS = 3 the job is dead after attempt 3
        repeat(MAX_ATTEMPTS) { responseQueue.add(500) }

        val consumerChannel = startConsumer()
        publishJob(deliveryId)

        try {
            // ── DB assertions ─────────────────────────────────────────────────
            val state = awaitDeliveryStatus(deliveryId, setOf("DEAD"))

            assertEquals("DEAD", state.status)
            assertEquals(MAX_ATTEMPTS, state.attempts,
                "attempts must equal MAX_ATTEMPTS when all retries are exhausted")
            assertNotNull(state.lastError,
                "lastError must capture the final failure message")

            // ── DLQ assertions ────────────────────────────────────────────────
            val dlqResponse = awaitQueueMessage(RabbitMQTopology.QUEUE_DLQ)
            ch.basicAck(dlqResponse.envelope.deliveryTag, false)

            val dlqJob = AppJson.decodeFromString<DeliveryJob>(String(dlqResponse.body))
            assertEquals(deliveryId.toString(), dlqJob.deliveryId,
                "DLQ message must reference the correct delivery")
            assertEquals(MAX_ATTEMPTS, dlqJob.attempt,
                "DLQ message must carry the final attempt number")
        } finally {
            consumerChannel.close()
        }
    }
}
