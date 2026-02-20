package io.github.cvieirasp.api.source

interface SourceRepository {
    fun findAll(): List<Source>
    fun findByName(name: String): Source?
    fun create(source: Source): Source
}
