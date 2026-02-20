package io.github.cvieirasp.api.delivery

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class DeliveryRepositoryImpl : DeliveryRepository {

    /**
     * Creates a new pending delivery in the database.
     *
     * @param delivery The delivery to be created.
     * @return The created delivery.
     */
    override fun createPending(delivery: Delivery): Delivery = transaction {
        DeliveryTable.insert {
            it[id] = delivery.id
            it[eventId] = delivery.eventId
            it[destinationId] = delivery.destinationId
            it[status] = delivery.status
            it[attempts] = delivery.attempts
            it[maxAttempts] = delivery.maxAttempts
            it[lastError] = delivery.lastError
            it[lastAttemptAt] = delivery.lastAttemptAt
            it[deliveredAt] = delivery.deliveredAt
            it[createdAt] = delivery.createdAt
        }
        delivery
    }
}
