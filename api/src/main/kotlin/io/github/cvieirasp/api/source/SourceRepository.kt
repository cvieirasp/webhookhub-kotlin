package io.github.cvieirasp.api.source

interface SourceRepository {
    fun findAll(): List<Source>
    fun create(source: Source): Source
}
