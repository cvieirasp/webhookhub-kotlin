package io.github.cvieirasp.shared.logging

import org.slf4j.LoggerFactory

/**
 * Emits structured log events at every significant state transition in the
 * webhook pipeline.
 *
 * Each method adds the fields relevant to its transition as SLF4J key-value
 * pairs. The logstash-logback-encoder installed in :api and :worker serialises
 * these pairs — together with [timestamp], [level], and any MDC (Mapped Diagnostic
 * Context) fields such as [correlationId] — as a flat JSON object per log line.
 *
 * Fields that are not meaningful for a given transition (e.g. [deliveryId] for
 * ingest-side events) are simply absent from the emitted JSON.
 */
object WebhookEventLogger {

    /** We intentionally use a single logger for all events in the pipeline, so
     * that they can be easily correlated by [correlationId] in log analysis tools.
     */
    private val logger = LoggerFactory.getLogger(WebhookEventLogger::class.java)

    /**
     * Webhook event received from source; persisted and enqueued for delivery.
     *
     * Note: this is the first log emitted for a given event, and serves as the "anchor"
     * for all subsequent logs related to the same event. The [correlationId] MDC
     * field should be set at this point (e.g. to the event ID) so that all logs for
     * this event can be easily correlated in log analysis tools.
     */
    fun eventReceived(sourceName: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("status", "RECEIVED")
            .log("event received")
    }

    /**
     * HMAC-SHA256 signature on incoming webhook event verified successfully; event is authentic.
     *
     * Note: signature verification failure is not logged here, since the event is rejected outright
     * and never persisted or enqueued for delivery. However, the API server's access logs will
     * still show the incoming request and its response status (e.g. 401 Unauthorized),
     * which can be used to monitor for potential abuse or misconfiguration.
     */
    fun signatureValidated(sourceName: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("status", "VALIDATED")
            .log("signature validated")
    }

    /**
     * Idempotency check found an already-persisted event; no delivery scheduled.
     *
     * Note: this can occur when the same webhook event is sent multiple times by the source,
     * which is a common occurrence in real-world webhook systems. The idempotency check
     * prevents duplicate deliveries and ensures that the system behaves predictably even
     * in the face of such duplicates.
     */
    fun duplicateDetected(sourceName: String, eventId: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("eventId", eventId)
            .addKeyValue("status", "DUPLICATE")
            .log("duplicate event detected")
    }

    /**
     * Worker picked up the job and is about to make the HTTP call.
     *
     * Note: this is the point at which we have a [deliveryId] and can start tracking
     * delivery attempts for this event. The [attemptNumber] starts at 1 for the first attempt,
     * and increments with each retry. The [destination] is the URL to which the webhook event
     * is being delivered, and can be used to monitor delivery success/failure patterns for
     * specific endpoints (e.g. if a particular customer is having trouble receiving webhooks).
     */
    fun deliveryAttempted(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
    ) {
        logger.atInfo()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "ATTEMPTING")
            .addKeyValue("attemptNumber", attemptNumber)
            .log("delivery attempted")
    }

    /**
     * HTTP destination responded with 2xx; delivery is complete.
     *
     * Note: even if the HTTP call succeeds, the webhook event might still be
     * considered "undelivered" if the response body doesn't match the expected
     * format (e.g. missing required fields). In that case, the worker would log
     * a retry or dead event instead of a success. However, for simplicity,
     * this project assumes that any 2xx response indicates a successful delivery.
     */
    fun deliverySucceeded(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
    ) {
        logger.atInfo()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "DELIVERED")
            .addKeyValue("attemptNumber", attemptNumber)
            .log("delivery succeeded")
    }

    /**
     * Retryable failure; job re-queued with exponential-backoff TTL.
     *
     * Note: the [errorMessage] should be a concise description of the failure
     * reason (e.g. "HTTP 500 Internal Server Error", "Network timeout", etc.)
     * that can help with debugging and monitoring. The presence of this field
     * in the logs allows us to analyze common failure patterns and potentially
     * identify systemic issues (e.g. if a particular destination is consistently
     * returning 500 errors).
     */
    fun retryScheduled(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
        errorMessage: String,
    ) {
        logger.atWarn()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "RETRYING")
            .addKeyValue("attemptNumber", attemptNumber)
            .addKeyValue("errorMessage", errorMessage)
            .log("retry scheduled")
    }

    /**
     * All attempts exhausted or non-retryable error; job moved to DLQ.
     *
     * Note: the [errorMessage] here is especially important for diagnosing
     * why deliveries are failing and ending up in the DLQ. By analyzing these
     * messages, we can identify common failure modes and potentially take
     * corrective actions (e.g. if a particular destination is consistently
     * timing out, we might want to reach out to the customer or implement
     * additional retry logic). Additionally, monitoring the volume of "DEAD"
     * events can help us detect systemic issues in the delivery pipeline that
     * might require attention.
     */
    fun deliveryDead(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
        errorMessage: String,
    ) {
        logger.atError()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "DEAD")
            .addKeyValue("attemptNumber", attemptNumber)
            .addKeyValue("errorMessage", errorMessage)
            .log("delivery dead")
    }
}
