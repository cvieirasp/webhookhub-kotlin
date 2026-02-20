package io.github.cvieirasp.api.source

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * A fake implementation of [SourceRepository] for testing purposes.
 */
class FakeSourceRepository : SourceRepository {

    private val sources = mutableListOf<Source>()

    override fun findAll(): List<Source> = sources.toList()

    override fun findByName(name: String): Source? = sources.find { it.name == name }

    override fun create(source: Source): Source {
        sources.add(source)
        return source
    }

    fun seed(vararg source: Source) = sources.addAll(source)
}

/**
 * A helper function to create a [Source] with default values for testing.
 */
fun aSource(
    id: UUID = UUID.randomUUID(),
    name: String = "test-source",
    hmacSecret: String = "a".repeat(64),
    active: Boolean = true,
    createdAt: Instant = Clock.System.now(),
) = Source(
    id = id,
    name = name,
    hmacSecret = hmacSecret,
    active = active,
    createdAt = createdAt,
)
