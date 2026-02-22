package io.github.cvieirasp.api.ingest

interface EventRepository {
    fun save(event: Event): Boolean
    fun findById(id: java.util.UUID): Event?
}
