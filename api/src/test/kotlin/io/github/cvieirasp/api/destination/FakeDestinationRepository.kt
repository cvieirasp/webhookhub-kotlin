package io.github.cvieirasp.api.destination

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * A fake implementation of [DestinationRepository] for testing purposes.
 */
class FakeDestinationRepository : DestinationRepository {

    private val destinations = mutableListOf<Destination>()

    override fun findAll(): List<Destination> = destinations.toList()

    override fun findById(id: UUID): Destination? = destinations.find { it.id == id }

    override fun create(destination: Destination): Destination {
        destinations.add(destination)
        return destination
    }

    override fun findBySourceNameAndEventType(sourceName: String, eventType: String): List<Destination> =
        destinations.filter { dest ->
            dest.rules.any { it.sourceName == sourceName && it.eventType == eventType }
        }

    override fun addRule(destinationId: UUID, rule: DestinationRule): DestinationRule {
        val index = destinations.indexOfFirst { it.id == destinationId }
        destinations[index] = destinations[index].copy(rules = destinations[index].rules + rule)
        return rule
    }

    fun seed(vararg destination: Destination) = destinations.addAll(destination)
}

/**
 * A helper function to create a [Destination] with default values for testing.
 */
fun aDestination(
    id: UUID = UUID.randomUUID(),
    name: String = "test-destination",
    targetUrl: String = "https://example.com/webhook",
    active: Boolean = true,
    createdAt: Instant = Clock.System.now(),
    rules: List<DestinationRule> = emptyList(),
) = Destination(
    id = id,
    name = name,
    targetUrl = targetUrl,
    active = active,
    createdAt = createdAt,
    rules = rules,
)

/**
 * A helper function to create a [DestinationRule] with default values for testing.
 */
fun aDestinationRule(
    id: UUID = UUID.randomUUID(),
    destinationId: UUID = UUID.randomUUID(),
    sourceName: String = "test-source",
    eventType: String = "order.created",
) = DestinationRule(
    id = id,
    destinationId = destinationId,
    sourceName = sourceName,
    eventType = eventType,
)
