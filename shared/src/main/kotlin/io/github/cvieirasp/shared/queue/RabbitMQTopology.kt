package io.github.cvieirasp.shared.queue

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * Central definition of all RabbitMQ exchanges, queues, and bindings.
 *
 * Topology:
 *
 *   [Producer] ──► [webhookhub (direct)] ──► [webhookhub.deliveries (TTL=30min, DLX→webhookhub.dlx)]
 *                                                        │ expired / nack
 *                                                        ▼
 *                                          [webhookhub.dlx (fanout)] ──► [webhookhub.dlq]
 */
object RabbitMQTopology {

    const val EXCHANGE = "webhookhub"
    const val EXCHANGE_DLX = "webhookhub.dlx"
    const val QUEUE_DELIVERIES = "webhookhub.deliveries"
    const val QUEUE_DLQ = "webhookhub.dlq"
    const val ROUTING_KEY_DELIVERY = "delivery"

    /** 30 minutes — messages not consumed within this window are dead-lettered. */
    private const val MESSAGE_TTL_MS = 1_800_000L

    /**
     * Idempotently declares all exchanges, queues, and bindings.
     *
     * RabbitMQ returns OK when a resource is re-declared with identical arguments,
     * so this is safe to call on every startup. A [com.rabbitmq.client.ShutdownSignalException]
     * will surface if an existing resource was declared with different arguments —
     * that signals a configuration mismatch and must be resolved manually.
     */
    fun declare(channel: Channel) {
        // Main exchange — producers publish DeliveryJob messages here
        channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.DIRECT, true)

        // Dead-letter exchange — fanout so the DLQ catches everything without a routing key
        channel.exchangeDeclare(EXCHANGE_DLX, BuiltinExchangeType.FANOUT, true)

        // Dead-letter queue bound to DLX (no routing key needed for fanout)
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
    }
}
