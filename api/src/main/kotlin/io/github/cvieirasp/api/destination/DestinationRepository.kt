package io.github.cvieirasp.api.destination

import java.util.UUID

interface DestinationRepository {
    fun findAll(): List<Destination>
    fun findById(id: UUID): Destination?
    fun findBySourceNameAndEventType(sourceName: String, eventType: String): List<Destination>
    fun create(destination: Destination): Destination
    fun addRule(destinationId: UUID, rule: DestinationRule): DestinationRule
}
