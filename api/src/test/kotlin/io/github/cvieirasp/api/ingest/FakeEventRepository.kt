package io.github.cvieirasp.api.ingest

class FakeEventRepository(private var rejectAsDuplicate: Boolean = false) : EventRepository {
    val saved = mutableListOf<Event>()
    override fun save(event: Event): Boolean {
        if (rejectAsDuplicate) return false
        saved.add(event)
        return true
    }
    override fun findById(id: java.util.UUID): Event? = saved.find { it.id == id }
    override fun findFiltered(filter: EventFilter, page: Int, pageSize: Int): Pair<Long, List<Event>> {
        val filtered = saved.filter { event ->
            (filter.sourceName == null || event.sourceName == filter.sourceName) &&
            (filter.eventType  == null || event.eventType  == filter.eventType) &&
            (filter.dateFrom   == null || event.receivedAt >= filter.dateFrom) &&
            (filter.dateTo     == null || event.receivedAt <= filter.dateTo)
        }.sortedByDescending { it.receivedAt }
        val total = filtered.size.toLong()
        val items = filtered.drop((page - 1) * pageSize).take(pageSize)
        return total to items
    }
}
