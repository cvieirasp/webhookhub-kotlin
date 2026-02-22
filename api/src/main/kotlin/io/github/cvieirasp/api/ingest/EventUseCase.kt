package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.NotFoundException
import java.util.UUID

class EventUseCase(private val repository: EventRepository) {

    fun getEvent(id: UUID): Event =
        repository.findById(id) ?: throw NotFoundException("event not found")
}
