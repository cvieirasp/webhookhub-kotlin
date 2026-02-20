package io.github.cvieirasp.api.source

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class SourceRepositoryImpl : SourceRepository {

    /**
     * The `findByName` method retrieves a source from the `SourceTable` based on the provided name.
     * It uses the `selectAll` function to query the table and applies a `where` clause to filter by the name.
     * The result is then mapped to a `Source` object using the `toSource` extension function.
     * If no source is found with the given name, it returns null.
     */
    override fun findByName(name: String): Source? = transaction {
        SourceTable
            .selectAll()
            .where { SourceTable.name eq name }
            .map { it.toSource() }
            .singleOrNull()
    }

    /**
     * The `findAll` method retrieves all sources from the `SourceTable` in the database.
     * It uses the `selectAll` function to get all rows and orders them by the `createdAt` column in descending order.
     * Each row is then mapped to a `Source` object using the `toSource` extension function.
     */
    override fun findAll(): List<Source> = transaction {
        SourceTable
            .selectAll()
            .orderBy(SourceTable.createdAt to SortOrder.DESC)
            .map { it.toSource() }
    }

    /**
     * The `create` method takes a `Source` object as input and inserts it into the `SourceTable`.
     * It uses the `insert` function provided by Exposed to perform the insertion.
     * After inserting the new source, it returns the same source object.
     */
    override fun create(source: Source): Source = transaction {
        SourceTable.insert {
            it[id] = source.id
            it[name] = source.name
            it[hmacSecret] = source.hmacSecret
            it[active] = source.active
            it[createdAt] = source.createdAt
        }
        source
    }

    /**
     * The `toSource` extension function converts a `ResultRow` from the database query into a `Source` object.
     * It maps each column from the `SourceTable` to the corresponding property in the `Source` data class.
     */
    private fun ResultRow.toSource() = Source(
        id = this[SourceTable.id],
        name = this[SourceTable.name],
        hmacSecret = this[SourceTable.hmacSecret],
        active = this[SourceTable.active],
        createdAt = this[SourceTable.createdAt],
    )
}
