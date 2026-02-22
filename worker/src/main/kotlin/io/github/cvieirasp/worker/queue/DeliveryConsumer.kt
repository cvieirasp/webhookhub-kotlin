package io.github.cvieirasp.worker.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.MessageProperties
import io.github.cvieirasp.shared.config.AppJson
import io.github.cvieirasp.shared.queue.DeliveryJob
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import io.github.cvieirasp.worker.delivery.DeliveryRepository
import io.github.cvieirasp.worker.delivery.DeliveryStatus
import io.github.cvieirasp.worker.http.HttpDeliveryClient
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * AMQP consumer for the `webhookhub.deliveries` queue.
 *
 * Guarantees:
 * - The delivery status is persisted to the database **before** the AMQP message
 *   is settled, so the outcome is always durable even if the broker restarts.
 * - DEAD deliveries (non-retryable error or exhausted attempts) are published
 *   explicitly to [RabbitMQTopology.EXCHANGE_DLX] and then acked, keeping the
 *   original message body intact in [RabbitMQTopology.QUEUE_DLQ].
 * - Unhandled exceptions nack (requeue=false) so the broker dead-letters the
 *   raw message for manual inspection.
 * - At most [DeliveryWorker.PREFETCH_COUNT] messages are unacknowledged at any
 *   time (enforced via `basicQos` in [DeliveryWorker]).
 */
class DeliveryConsumer(
    channel: Channel,
    private val repository: DeliveryRepository,
    private val httpClient: HttpDeliveryClient,
    /** Base delay for exponential back-off. Overridable in tests to keep suites fast. */
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    /** Maximum number of delivery attempts before the job is moved to the DLQ. */
    private val maxAttempts: Int  = DEFAULT_MAX_ATTEMPTS,
) : DefaultConsumer(channel) {

    private companion object {
        private const val DEFAULT_MAX_ATTEMPTS  = 5
        private const val DEFAULT_BASE_DELAY_MS = 5_000L       //  5 s  — delay after the first failure
        private const val MAX_DELAY_MS          = 1_800_000L  // 30 min — cap applied after repeated doublings
        private val logger = LoggerFactory.getLogger(DeliveryConsumer::class.java)
    }

    /**
     * Handles incoming delivery jobs from the queue.
     * The method is invoked by the RabbitMQ client library on a dedicated thread.
     */
    override fun handleDelivery(
        consumerTag: String,
        envelope: Envelope,
        properties: AMQP.BasicProperties,
        body: ByteArray,
    ) {
        val tag = envelope.deliveryTag
        try {
            val job = AppJson.decodeFromString<DeliveryJob>(body.toString(Charsets.UTF_8))
            MDC.put("correlationId", job.correlationId)
            logger.info("Processing delivery={} attempt={}/{}", job.deliveryId, job.attempt, maxAttempts)

            when (val result = httpClient.post(job.targetUrl, job.payloadJson)) {
                is HttpDeliveryClient.Result.Success -> {
                    // Timestamp captured after the HTTP call so delivered_at reflects
                    // when the 2xx response was actually received, not when processing started.
                    val deliveredAt = Clock.System.now()

                    // Persist DELIVERED status before acking.
                    repository.updateStatus(
                        deliveryId  = UUID.fromString(job.deliveryId),
                        status      = DeliveryStatus.DELIVERED,
                        attempts    = job.attempt,
                        deliveredAt = deliveredAt,
                    )
                    logger.info("Delivered delivery={}", job.deliveryId)
                }

                is HttpDeliveryClient.Result.Failure -> {
                    val lastAttemptAt = Clock.System.now()

                    if (result.retryable && job.attempt < maxAttempts) {
                        // Increment the attempt counter so both the database record and the
                        // re-queued message carry the same next-attempt number.
                        val nextAttempt = job.attempt + 1
                        val delayMs     = retryDelayMs(job.attempt)

                        // Persist RETRYING status with the incremented counter before
                        // publishing, so the state is durable even if the publish fails.
                        repository.updateStatus(
                            deliveryId    = UUID.fromString(job.deliveryId),
                            status        = DeliveryStatus.RETRYING,
                            attempts      = nextAttempt,
                            lastError     = result.message,
                            lastAttemptAt = lastAttemptAt,
                        )

                        // Publish the job with the incremented attempt number.  The message
                        // expiration (milliseconds as a string) tells the broker how long to
                        // hold the message in QUEUE_RETRY before dead-lettering it back to
                        // the main exchange for the next pickup.
                        republishWithDelay(job.copy(attempt = nextAttempt), delayMs)
                        logger.warn(
                            "Delivery={} failed (attempt {}/{}); retry in {}ms — {}",
                            job.deliveryId, job.attempt, maxAttempts, delayMs, result.message,
                        )
                    } else {
                        // Non-retryable error or attempts exhausted.
                        // 1. Persist DEAD status with final error and attempt count.
                        // 2. Publish the job to the DLQ for retention and inspection.
                        // 3. Ack the original (done below) — DLQ routing is application-driven,
                        //    not via broker dead-lettering, so the original is not nacked.
                        repository.updateStatus(
                            deliveryId    = UUID.fromString(job.deliveryId),
                            status        = DeliveryStatus.DEAD,
                            attempts      = job.attempt,
                            lastError     = result.message,
                            lastAttemptAt = lastAttemptAt,
                        )
                        publishToDlq(job)
                        val reason = if (!result.retryable) {
                            "non-retryable error (HTTP ${result.statusCode})"
                        } else {
                            "exhausted $maxAttempts attempts"
                        }
                        logger.error(
                            "Delivery={} marked DEAD after {} — {}",
                            job.deliveryId, reason, result.message,
                        )
                    }
                }
            }

            // DB write (and DLQ publish for DEAD deliveries) have completed — ack
            // the original message to remove it from QUEUE_DELIVERIES.
            channel.basicAck(tag, false)

        } catch (e: Exception) {
            logger.error("Unhandled failure for delivery tag={}; dead-lettering message", tag, e)
            // nack with requeue=false → broker forwards to the dead-letter exchange
            runCatching { channel.basicNack(tag, false, false) }
        } finally {
            MDC.remove("correlationId")
        }
    }

    /**
     * Publishes [job] to [RabbitMQTopology.EXCHANGE_DLX] for permanent retention in
     * [RabbitMQTopology.QUEUE_DLQ].
     *
     * Routing through the exchange (rather than directly to the queue) keeps DLQ routing
     * consistent with the declared topology and allows additional fanout subscribers to
     * be bound to [RabbitMQTopology.EXCHANGE_DLX] without changing this code.
     *
     * The message is marked persistent (deliveryMode=2) so it survives a broker restart.
     */
    private fun publishToDlq(job: DeliveryJob) {
        channel.basicPublish(
            RabbitMQTopology.EXCHANGE_DLX,             // fanout — routing key is ignored
            "",
            MessageProperties.PERSISTENT_BASIC,
            AppJson.encodeToString(job).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Publishes [job] to [RabbitMQTopology.QUEUE_RETRY] with the AMQP `expiration`
     * property set to [delayMs] (milliseconds as a string, as required by the spec).
     *
     * The caller is responsible for passing the already-incremented [job] so that the
     * database record and the queued message always agree on the next attempt number.
     *
     * When the per-message TTL expires the broker dead-letters the message back to the
     * main exchange ([RabbitMQTopology.EXCHANGE]) with routing key
     * [RabbitMQTopology.ROUTING_KEY_DELIVERY], which routes it into
     * [RabbitMQTopology.QUEUE_DELIVERIES] for the next pickup.
     *
     * The message is marked persistent (deliveryMode=2) so it survives a broker restart
     * during the backoff window.
     */
    private fun republishWithDelay(job: DeliveryJob, delayMs: Long) {
        val props = MessageProperties.PERSISTENT_BASIC.builder()
            .expiration(delayMs.toString())
            .build()
        channel.basicPublish(
            "",                            // default exchange — routes by queue name
            RabbitMQTopology.QUEUE_RETRY,  // routing key = queue name for default exchange
            props,
            AppJson.encodeToString(job).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Returns the backoff delay in milliseconds before the next delivery attempt.
     *
     * Formula: `baseDelayMs × 2^(attempt − 1)`, capped at [MAX_DELAY_MS].
     *
     * With the production default of [DEFAULT_BASE_DELAY_MS] = 5 000 ms:
     *
     * | Attempt | Raw delay  | Effective delay |
     * |---------|------------|-----------------|
     * | 1       |   5 000 ms |    5 000 ms     |
     * | 2       |  10 000 ms |   10 000 ms     |
     * | 3       |  20 000 ms |   20 000 ms     |
     * | 4       |  40 000 ms |   40 000 ms     |
     * | …       |  doubles   | ≤ MAX_DELAY_MS  |
     *
     * The shift exponent is clamped to 30 so the intermediate Long value never
     * overflows ([baseDelayMs] × 2^30 is well within [Long.MAX_VALUE] for any
     * realistic base delay).
     */
    private fun retryDelayMs(attempt: Int): Long =
        minOf(baseDelayMs shl (attempt - 1).coerceAtMost(30), MAX_DELAY_MS)
}
