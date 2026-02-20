package io.github.cvieirasp.api.ingest

class FakeEventRepository(private var rejectAsDuplicate: Boolean = false) : EventRepository {
    val saved = mutableListOf<Event>()
    override fun save(event: Event): Boolean {
        if (rejectAsDuplicate) return false
        saved.add(event)
        return true
    }
}
