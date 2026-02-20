package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.NotFoundException
import io.github.cvieirasp.api.UnauthorizedException
import io.github.cvieirasp.api.delivery.Delivery
import io.github.cvieirasp.api.delivery.DeliveryPublisher
import io.github.cvieirasp.api.delivery.DeliveryRepository
import io.github.cvieirasp.api.delivery.DeliveryStatus
import io.github.cvieirasp.api.destination.DestinationRepository
import io.github.cvieirasp.api.source.SourceRepository
import io.github.cvieirasp.shared.queue.DeliveryJob
import kotlinx.datetime.Clock
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IngestUseCase(
    private val sourceRepository: SourceRepository,
    private val eventRepository: EventRepository,
    private val destinationRepository: DestinationRepository,
    private val deliveryRepository: DeliveryRepository,
    private val deliveryPublisher: DeliveryPublisher,
) {
    /**
     * Ingests an event from a source. It performs the following steps:
     * 1. Validates the input parameters (event type must not be blank, signature must be present).
     * 2. Retrieves the source by name and checks if it is active.
     * 3. Computes the HMAC signature of the raw body using the source's secret and compares it to the provided signature.
     * 4. If the signature is valid, it creates a new Event object and saves it to the repository.
     * 5. If the event is new (not a duplicate), it retrieves and returns the list of destinations that should receive this event based on its source and type.
     *
     * This method ensures that only valid events from active sources are ingested, and it provides the necessary information for routing the event to the appropriate destinations.
     * @param sourceName The name of the source sending the event.
     * @param eventType The type of the event being ingested.
     * @param rawBody The raw payload of the event as a byte array.
     * @param signature The HMAC signature of the raw body, used for authentication.
     * @return A list of [Delivery] records created for this event (one per matching destination),
     *         or an empty list if the event was a duplicate.
     */
    fun ingest(sourceName: String, eventType: String, rawBody: ByteArray, signature: String): List<Delivery> {
        require(eventType.isNotBlank()) { "type must not be blank" }

        val source = sourceRepository.findByName(sourceName)
            ?: throw NotFoundException("source not found")

        if (!source.active) throw UnauthorizedException("source is inactive")

        if (signature.isBlank()) throw UnauthorizedException("missing signature")

        val computed = computeHmac(source.hmacSecret, rawBody)
        print("Computed signature: $computed")
        val match = MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
        if (!match) throw UnauthorizedException("invalid signature")

        val event = Event(
            id = UUID.randomUUID(),
            sourceName = sourceName,
            eventType = eventType,
            idempotencyKey = computeIdempotencyKey(sourceName, eventType, rawBody),
            payloadJson = rawBody.toString(Charsets.UTF_8),
            receivedAt = Clock.System.now(),
        )
        val isNew = eventRepository.save(event)
        if (!isNew) return emptyList()

        return destinationRepository.findBySourceNameAndEventType(sourceName, eventType).map { destination ->
            val delivery = Delivery(
                id = UUID.randomUUID(),
                eventId = event.id,
                destinationId = destination.id,
                status = DeliveryStatus.PENDING,
                createdAt = Clock.System.now(),
            )
            deliveryRepository.createPending(delivery)
            deliveryPublisher.publish(
                DeliveryJob(
                    deliveryId = delivery.id.toString(),
                    eventId = event.id.toString(),
                    destinationId = destination.id.toString(),
                    targetUrl = destination.targetUrl,
                    payloadJson = event.payloadJson,
                )
            )
            delivery
        }
    }

    /**
     * Computes HMAC-SHA256(secret, body) and returns the result as a hex string.
     */
    private fun computeHmac(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes a deterministic idempotency key for an event based on its source, type, and body.
     * This allows us to deduplicate events that have the same content, even if they are delivered multiple times.
     */
    private fun computeIdempotencyKey(sourceName: String, eventType: String, rawBody: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceName.toByteArray(Charsets.UTF_8))
        digest.update(eventType.toByteArray(Charsets.UTF_8))
        digest.update(rawBody)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
