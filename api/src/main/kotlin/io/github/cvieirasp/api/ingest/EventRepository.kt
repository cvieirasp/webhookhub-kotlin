package io.github.cvieirasp.api.ingest

interface EventRepository {
    fun save(event: Event): Boolean
    fun findById(id: java.util.UUID): Event?
    fun findFiltered(filter: EventFilter, page: Int, pageSize: Int): Pair<Long, List<Event>>
}
