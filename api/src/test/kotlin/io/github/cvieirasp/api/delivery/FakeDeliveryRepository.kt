package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.shared.queue.DeliveryJob
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * A fake implementation of [DeliveryRepository] for testing purposes.
 */
class FakeDeliveryRepository : DeliveryRepository {
    val saved = mutableListOf<Delivery>()

    override fun createPending(delivery: Delivery): Delivery {
        saved.add(delivery)
        return delivery
    }
}

/**
 * A fake implementation of [DeliveryPublisher] for testing purposes.
 */
class FakeDeliveryPublisher : DeliveryPublisher {
    val published = mutableListOf<DeliveryJob>()

    override fun publish(job: DeliveryJob) {
        published.add(job)
    }
}

/**
 * A helper function to create a [Delivery] with default values for testing.
 */
fun aDelivery(
    id: UUID = UUID.randomUUID(),
    eventId: UUID = UUID.randomUUID(),
    destinationId: UUID = UUID.randomUUID(),
    status: DeliveryStatus = DeliveryStatus.PENDING,
    attempts: Int = 0,
    maxAttempts: Int = 5,
    lastError: String? = null,
    lastAttemptAt: Instant? = null,
    deliveredAt: Instant? = null,
    createdAt: Instant = Clock.System.now(),
) = Delivery(
    id = id,
    eventId = eventId,
    destinationId = destinationId,
    status = status,
    attempts = attempts,
    maxAttempts = maxAttempts,
    lastError = lastError,
    lastAttemptAt = lastAttemptAt,
    deliveredAt = deliveredAt,
    createdAt = createdAt,
)
