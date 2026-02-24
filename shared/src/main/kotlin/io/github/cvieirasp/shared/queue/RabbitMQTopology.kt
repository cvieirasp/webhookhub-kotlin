package io.github.cvieirasp.shared.queue

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * Central definition of all RabbitMQ exchanges, queues, and bindings.
 *
 * This topology is designed to support a robust delivery lifecycle with retry and dead-lettering semantics:
 * - New jobs are published to the "webhookhub" exchange with routing key "delivery",
 * landing in the "webhookhub.deliveries" queue.
 * - The worker consumes from "webhookhub.deliveries". If a job fails with a retryable error
 * (e.g. 429 or a network failure), the worker republishes it to "deliveries.retry.q" with a
 * per-message TTL equal to the backoff delay. If a job fails with a non-retryable error
 * (e.g. 400), or exhausts all retries, the worker nacks it without requeueing, causing it to be
 * dead-lettered to "deliveries.dlx" and end up in "deliveries.dlq".
 * - The "deliveries.retry.q" has no consumer. When a message's TTL expires, RabbitMQ automatically
 * dead-letters it to the "webhookhub" exchange with routing key "delivery", making it available
 * again in "webhookhub.deliveries" for the next attempt.
 */
object RabbitMQTopology {

    const val EXCHANGE = "webhookhub"
    const val EXCHANGE_DLX = "deliveries.dlx"
    const val QUEUE_DELIVERIES = "webhookhub.deliveries"
    const val QUEUE_RETRY = "deliveries.retry.q"
    const val QUEUE_DLQ = "deliveries.dlq"
    const val ROUTING_KEY_DELIVERY = "delivery"

    /**
     * 30 minutes — messages not consumed within this window are dead-lettered.
     */
    private const val MESSAGE_TTL_MS = 1_800_000L

    /**
     * Idempotently declares all exchanges, queues, and bindings.
     *
     * RabbitMQ returns OK when a resource is re-declared with identical arguments,
     * so this is safe to call on every startup. A [com.rabbitmq.client.ShutdownSignalException]
     * will surface if an existing resource was declared with different arguments -
     * that signals a configuration mismatch and must be resolved manually.
     */
    fun declare(channel: Channel) {
        // Main exchange - producers publish DeliveryJob messages here
        channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.DIRECT, true)

        // Dedicated dead-letter exchange - fanout so the DLQ catches everything without a routing key.
        // Receives nacked messages from QUEUE_DELIVERIES: deliveries that exhausted all retries
        // or were classified as non-retryable.
        channel.exchangeDeclare(EXCHANGE_DLX, BuiltinExchangeType.FANOUT, true)

        // Dead-letter queue - terminal destination for permanently failed deliveries.
        // Bound to EXCHANGE_DLX with an empty routing key (fanout ignores it).
        channel.queueDeclare(QUEUE_DLQ, true, false, false, null)
        channel.queueBind(QUEUE_DLQ, EXCHANGE_DLX, "")

        // Main delivery queue with TTL and DLX forwarding
        channel.queueDeclare(
            QUEUE_DELIVERIES,
            true,
            false,
            false,
            mapOf(
                "x-message-ttl" to MESSAGE_TTL_MS,
                "x-dead-letter-exchange" to EXCHANGE_DLX,
            ),
        )
        channel.queueBind(QUEUE_DELIVERIES, EXCHANGE, ROUTING_KEY_DELIVERY)

        // Retry holding queue - has no consumer. The worker publishes failed jobs here
        // with a per-message expiration (x-expiration) equal to the backoff delay.
        // When the TTL expires RabbitMQ dead-letters the message using:
        //   x-dead-letter-exchange → EXCHANGE (webhookhub, direct)
        //   x-dead-letter-routing-key → ROUTING_KEY_DELIVERY (delivery)
        // The direct exchange routes "delivery" back to QUEUE_DELIVERIES, making the
        // message available for the next attempt without any additional binding.
        channel.queueDeclare(
            QUEUE_RETRY,
            true,
            false,
            false,
            mapOf(
                "x-dead-letter-exchange" to EXCHANGE,
                "x-dead-letter-routing-key" to ROUTING_KEY_DELIVERY,
            ),
        )
    }
}
