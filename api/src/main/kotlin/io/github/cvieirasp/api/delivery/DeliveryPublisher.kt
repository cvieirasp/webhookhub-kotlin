package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.api.plugins.RabbitMQFactory
import io.github.cvieirasp.shared.queue.DeliveryJob
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import kotlinx.serialization.json.Json

interface DeliveryPublisher {
    fun publish(job: DeliveryJob)
}

/**
 * Implementation of the DeliveryPublisher interface that publishes delivery jobs to a RabbitMQ exchange.
 * It uses the RabbitMQFactory to create a connection and channel, and then publishes the job as a JSON string.
 */
class RabbitMQDeliveryPublisher : DeliveryPublisher {
    override fun publish(job: DeliveryJob) {
        val body = Json.encodeToString(DeliveryJob.serializer(), job).toByteArray(Charsets.UTF_8)
        RabbitMQFactory.connection.createChannel().use { channel ->
            channel.basicPublish(
                RabbitMQTopology.EXCHANGE,
                RabbitMQTopology.ROUTING_KEY_DELIVERY,
                null,
                body,
            )
        }
    }
}
