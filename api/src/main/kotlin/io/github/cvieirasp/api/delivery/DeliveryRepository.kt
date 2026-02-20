package io.github.cvieirasp.api.delivery

interface DeliveryRepository {
    fun createPending(delivery: Delivery): Delivery
}
