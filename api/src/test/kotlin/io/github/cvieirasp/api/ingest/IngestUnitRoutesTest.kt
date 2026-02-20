package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.delivery.FakeDeliveryPublisher
import io.github.cvieirasp.api.delivery.FakeDeliveryRepository
import io.github.cvieirasp.api.destination.FakeDestinationRepository
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.source.FakeSourceRepository
import io.github.cvieirasp.api.source.aSource
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/**
 * Unit tests for [ingestRoutes].
 */
class IngestUnitRoutesTest {

    private fun computeHmac(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun testApp(
        repo: FakeSourceRepository = FakeSourceRepository(),
        eventRepo: FakeEventRepository = FakeEventRepository(),
        destinationRepo: FakeDestinationRepository = FakeDestinationRepository(),
        deliveryRepo: FakeDeliveryRepository = FakeDeliveryRepository(),
        deliveryPublisher: FakeDeliveryPublisher = FakeDeliveryPublisher(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { ingestRoutes(IngestUseCase(repo, eventRepo, destinationRepo, deliveryRepo, deliveryPublisher)) }
        }
        block()
    }

    // region POST /ingest/{sourceName}

    @Test
    fun `POST ingest returns 202 when signature is valid`() {
        val secret = "a".repeat(64)
        val body = "payload"
        val signature = computeHmac(secret, body.toByteArray())
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github", hmacSecret = secret))
        }
        testApp(repo) {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }
    }

    @Test
    fun `POST ingest returns 404 when source does not exist`() = testApp {
        val response = client.post("/ingest/unknown?type=push") {
            header("X-Signature", "any-signature")
            setBody("payload")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST ingest returns 401 when source is inactive`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github", active = false))
        }
        testApp(repo) {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", "any-signature")
                setBody("payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST ingest returns 401 when X-Signature header is missing`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github"))
        }
        testApp(repo) {
            val response = client.post("/ingest/github?type=push") {
                setBody("payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST ingest returns 401 when signature is invalid`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github", hmacSecret = "a".repeat(64)))
        }
        testApp(repo) {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", "wrong-signature")
                setBody("payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST ingest returns 400 when type query parameter is missing`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github"))
        }
        testApp(repo) {
            val response = client.post("/ingest/github") {
                header("X-Signature", "any-signature")
                setBody("payload")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST ingest returns 400 when type query parameter is blank`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github"))
        }
        testApp(repo) {
            val response = client.post("/ingest/github?type=") {
                header("X-Signature", "any-signature")
                setBody("payload")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST ingest returns 202 for duplicate delivery`() {
        val secret = "a".repeat(64)
        val body = "payload"
        val signature = computeHmac(secret, body.toByteArray())
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github", hmacSecret = secret))
        }
        val eventRepo = FakeEventRepository(rejectAsDuplicate = true)
        testApp(repo, eventRepo) {
            val response = client.post("/ingest/github?type=push") {
                header("X-Signature", signature)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }
    }

    // endregion
}
