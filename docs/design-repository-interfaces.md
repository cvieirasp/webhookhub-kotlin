# Why repository interfaces?

Repositories are defined as interfaces and injected into use cases. The API integration tests and worker integration tests run against real PostgreSQL containers via Testcontainers, but individual use case unit tests can use simple in-memory fakes.

This also keeps the persistence technology out of the business logic. The use case knows nothing about SQL, Exposed table objects, or HikariCP. It calls `eventRepository.save(event)` and moves on.
