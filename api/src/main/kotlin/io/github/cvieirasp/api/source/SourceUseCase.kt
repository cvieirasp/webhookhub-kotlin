package io.github.cvieirasp.api.source

import kotlinx.datetime.Clock
import java.security.SecureRandom
import java.util.UUID

/**
 * Use case for managing sources.
 *
 * @param repository The repository for accessing source data.
 */
class SourceUseCase(private val repository: SourceRepository) {

    fun listSources(): List<Source> = repository.findAll()

    /**
     * Creates a new source with the given name.
     *
     * @param name The name of the source to create.
     * @return The created source.
     * @throws IllegalArgumentException if the name is blank or exceeds 100 characters.
     */
    fun createSource(name: String): Source {
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.trim().length <= 100) { "name must not exceed 100 characters" }

        val source = Source(
            id = UUID.randomUUID(),
            name = name.trim(),
            hmacSecret = generateHmacSecret(),
            active = true,
            createdAt = Clock.System.now(),
        )
        return repository.create(source)
    }

    /**
     * Generates a random HMAC secret as a hexadecimal string.
     *
     * @return A randomly generated HMAC secret.
     */
    private fun generateHmacSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
