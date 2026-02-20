package io.github.cvieirasp.api.destination

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class DestinationRepositoryImpl : DestinationRepository {

    /**
     * The findAll method retrieves all destinations from the database.
     * It first queries the DestinationTable to get a list of all destinations, ordered by their creation date in descending order.
     * If there are no destinations, it returns an empty list.
     * If there are destinations, it then queries the DestinationRuleTable to get all rules associated with those destinations, grouping them by destination ID.
     * Finally, it returns a list of destinations with their associated rules attached.
     */
    override fun findAll(): List<Destination> = transaction {
        val destinations = DestinationTable
            .selectAll()
            .orderBy(DestinationTable.createdAt to SortOrder.DESC)
            .map { it.toDestination() }

        if (destinations.isEmpty()) return@transaction destinations

        val rulesByDestination = DestinationRuleTable
            .selectAll()
            .where { DestinationRuleTable.destinationId inList destinations.map { it.id } }
            .map { it.toDestinationRule() }
            .groupBy { it.destinationId }

        destinations.map { it.copy(rules = rulesByDestination[it.id].orEmpty()) }
    }

    /**
     * This method retrieves a Destination by its ID.
     * It first queries the DestinationTable to find the destination.
     * If the destination is found, it then queries the DestinationRuleTable to retrieve all associated rules for that destination.
     * Finally, it returns a copy of the destination with its rules attached.
     */
    override fun findById(id: UUID): Destination? = transaction {
        val destination = DestinationTable
            .selectAll()
            .where { DestinationTable.id eq id }
            .map { it.toDestination() }
            .singleOrNull() ?: return@transaction null

        val rules = DestinationRuleTable
            .selectAll()
            .where { DestinationRuleTable.destinationId eq id }
            .map { it.toDestinationRule() }

        destination.copy(rules = rules)
    }

    /**
     * Finds all active destinations that have a rule matching the given source name and event type.
     */
    override fun findBySourceNameAndEventType(sourceName: String, eventType: String): List<Destination> = transaction {
        val ids = DestinationRuleTable
            .selectAll()
            .where {
                (DestinationRuleTable.sourceName eq sourceName) and
                (DestinationRuleTable.eventType eq eventType)
            }
            .map { it[DestinationRuleTable.destinationId] }

        if (ids.isEmpty()) return@transaction emptyList()

        DestinationTable
            .selectAll()
            .where { (DestinationTable.id inList ids) and (DestinationTable.active eq true) }
            .map { it.toDestination() }
    }

    /**
     * The create method is responsible for inserting a new Destination into the database.
     * It first inserts the destination into the DestinationTable, and then iterates over the rules associated with that destination, inserting each rule into the DestinationRuleTable.
     * Finally, it returns the created destination.
     */
    override fun create(destination: Destination): Destination = transaction {
        DestinationTable.insert {
            it[id] = destination.id
            it[name] = destination.name
            it[targetUrl] = destination.targetUrl
            it[active] = destination.active
            it[createdAt] = destination.createdAt
        }
        destination.rules.forEach { rule ->
            DestinationRuleTable.insert {
                it[id] = rule.id
                it[destinationId] = rule.destinationId
                it[sourceName] = rule.sourceName
                it[eventType] = rule.eventType
            }
        }
        destination
    }

    /**
     * The addRule method is responsible for adding a new rule to an existing destination.
     * It takes the destination ID and the rule to be added as parameters.
     * It inserts the new rule into the DestinationRuleTable, associating it with the specified destination ID.
     * Finally, it returns the added rule.
     */
    override fun addRule(destinationId: UUID, rule: DestinationRule): DestinationRule = transaction {
        DestinationRuleTable.insert {
            it[id] = rule.id
            it[DestinationRuleTable.destinationId] = destinationId
            it[sourceName] = rule.sourceName
            it[eventType] = rule.eventType
        }
        rule
    }

    /**
     * The toDestination and toDestinationRule extension functions are used to convert a ResultRow from the database query into a Destination or DestinationRule object, respectively.
     * These functions map the columns from the database to the properties of the corresponding data classes.
     */
    private fun ResultRow.toDestination() = Destination(
        id = this[DestinationTable.id],
        name = this[DestinationTable.name],
        targetUrl = this[DestinationTable.targetUrl],
        active = this[DestinationTable.active],
        createdAt = this[DestinationTable.createdAt],
    )

    private fun ResultRow.toDestinationRule() = DestinationRule(
        id = this[DestinationRuleTable.id],
        destinationId = this[DestinationRuleTable.destinationId],
        sourceName = this[DestinationRuleTable.sourceName],
        eventType = this[DestinationRuleTable.eventType],
    )
}
