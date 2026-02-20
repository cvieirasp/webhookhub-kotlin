package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.NotFoundException
import io.github.cvieirasp.api.UnauthorizedException
import io.github.cvieirasp.api.delivery.DeliveryStatus
import io.github.cvieirasp.api.delivery.FakeDeliveryPublisher
import io.github.cvieirasp.api.delivery.FakeDeliveryRepository
import io.github.cvieirasp.api.destination.FakeDestinationRepository
import io.github.cvieirasp.api.destination.aDestination
import io.github.cvieirasp.api.destination.aDestinationRule
import io.github.cvieirasp.api.source.FakeSourceRepository
import io.github.cvieirasp.api.source.aSource
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/**
 * Unit tests for [IngestUseCase].
 */
class IngestUnitUseCaseTest {

    private lateinit var repository: FakeSourceRepository
    private lateinit var eventRepository: FakeEventRepository
    private lateinit var destinationRepository: FakeDestinationRepository
    private lateinit var deliveryRepository: FakeDeliveryRepository
    private lateinit var deliveryPublisher: FakeDeliveryPublisher
    private lateinit var useCase: IngestUseCase

    @BeforeTest
    fun setup() {
        repository = FakeSourceRepository()
        eventRepository = FakeEventRepository()
        destinationRepository = FakeDestinationRepository()
        deliveryRepository = FakeDeliveryRepository()
        deliveryPublisher = FakeDeliveryPublisher()
        useCase = IngestUseCase(repository, eventRepository, destinationRepository, deliveryRepository, deliveryPublisher)
    }

    private fun computeHmac(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    // region ingest — validation

    @Test
    fun `ingest throws when eventType is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.ingest("github", "", "body".toByteArray(), "sig")
        }
        assertEquals("type must not be blank", ex.message)
    }

    @Test
    fun `ingest throws NotFoundException when source does not exist`() {
        assertFailsWith<NotFoundException> {
            useCase.ingest("unknown", "push", "body".toByteArray(), "sig")
        }
    }

    @Test
    fun `ingest throws UnauthorizedException when source is inactive`() {
        repository.seed(aSource(name = "github", active = false))
        val ex = assertFailsWith<UnauthorizedException> {
            useCase.ingest("github", "push", "body".toByteArray(), "sig")
        }
        assertEquals("source is inactive", ex.message)
    }

    @Test
    fun `ingest throws UnauthorizedException when signature is blank`() {
        repository.seed(aSource(name = "github"))
        val ex = assertFailsWith<UnauthorizedException> {
            useCase.ingest("github", "push", "body".toByteArray(), "")
        }
        assertEquals("missing signature", ex.message)
    }

    @Test
    fun `ingest throws UnauthorizedException when signature is invalid`() {
        repository.seed(aSource(name = "github", hmacSecret = "a".repeat(64)))
        val ex = assertFailsWith<UnauthorizedException> {
            useCase.ingest("github", "push", "body".toByteArray(), "invalid-signature")
        }
        assertEquals("invalid signature", ex.message)
    }

    // endregion

    // region ingest — happy path

    @Test
    fun `ingest stores event and returns empty list when no destinations match`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))

        val result = useCase.ingest("github", "push", body, signature)

        assertEquals(emptyList(), result)
        assertEquals(1, eventRepository.saved.size)
        assertEquals("github", eventRepository.saved[0].sourceName)
        assertEquals("push", eventRepository.saved[0].eventType)
        assertTrue(deliveryRepository.saved.isEmpty())
        assertTrue(deliveryPublisher.published.isEmpty())
    }

    @Test
    fun `ingest returns empty list when event is a duplicate`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))
        val duplicateRepo = FakeEventRepository(rejectAsDuplicate = true)
        val duplicateUseCase = IngestUseCase(repository, duplicateRepo, destinationRepository, deliveryRepository, deliveryPublisher)

        val result = duplicateUseCase.ingest("github", "push", body, signature)

        assertEquals(emptyList(), result)
        assertTrue(deliveryRepository.saved.isEmpty())
        assertTrue(deliveryPublisher.published.isEmpty())
    }

    @Test
    fun `ingest succeeds with empty body and correct signature`() {
        val secret = "a".repeat(64)
        val body = ByteArray(0)
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))

        val result = useCase.ingest("github", "push", body, signature)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `ingest returns pending deliveries for matching destinations`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))
        val destId = java.util.UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        val destination = aDestination(id = destId, targetUrl = "https://example.com/hook", rules = listOf(rule))
        destinationRepository.seed(destination)

        val result = useCase.ingest("github", "push", body, signature)

        assertEquals(1, result.size)
        assertEquals(destId, result.first().destinationId)
        assertEquals(DeliveryStatus.PENDING, result.first().status)
    }

    @Test
    fun `ingest persists delivery record for each matching destination`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))
        val destId = java.util.UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        destinationRepository.seed(aDestination(id = destId, rules = listOf(rule)))

        useCase.ingest("github", "push", body, signature)

        assertEquals(1, deliveryRepository.saved.size)
        assertEquals(destId, deliveryRepository.saved.first().destinationId)
        assertEquals(DeliveryStatus.PENDING, deliveryRepository.saved.first().status)
    }

    @Test
    fun `ingest publishes a DeliveryJob for each matching destination`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))
        val destId = java.util.UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        destinationRepository.seed(aDestination(id = destId, targetUrl = "https://example.com/hook", rules = listOf(rule)))

        useCase.ingest("github", "push", body, signature)

        assertEquals(1, deliveryPublisher.published.size)
        with(deliveryPublisher.published.first()) {
            assertEquals(destId.toString(), destinationId)
            assertEquals("https://example.com/hook", targetUrl)
        }
    }

    @Test
    fun `ingest returns empty list when no rules match`() {
        val secret = "a".repeat(64)
        val body = "payload".toByteArray()
        val signature = computeHmac(secret, body)
        repository.seed(aSource(name = "github", hmacSecret = secret))
        val destId = java.util.UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "other-event")
        destinationRepository.seed(aDestination(id = destId, rules = listOf(rule)))

        val result = useCase.ingest("github", "push", body, signature)

        assertEquals(emptyList(), result)
        assertTrue(deliveryRepository.saved.isEmpty())
        assertTrue(deliveryPublisher.published.isEmpty())
    }

    // endregion
}
