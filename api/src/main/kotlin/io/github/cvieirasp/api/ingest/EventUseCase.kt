package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.NotFoundException
import java.util.UUID

class EventUseCase(private val repository: EventRepository) {

    fun getEvent(id: UUID): Event =
        repository.findById(id) ?: throw NotFoundException("event not found")

    fun listEvents(filter: EventFilter, page: Int, pageSize: Int): Pair<Long, List<Event>> =
        repository.findFiltered(filter, page, pageSize)
}
