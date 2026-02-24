# Why the Use Case layer?

Each feature exposes its logic through a **use case class** (`IngestUseCase`, `DeliveryUseCase`, etc.) rather than putting business logic directly inside route handlers or repository implementations.

This separation means:

- **Routes stay thin.** They parse the HTTP request, call the use case, and map the result to a response. They contain no business rules.
- **Use cases are independently testable.** They receive their dependencies (repositories, publishers) via constructor injection. Unit tests can pass fakes or mocks without spinning up a Ktor server or a database.
- **Business logic is named explicitly.** A use case method like `ingest(sourceName, eventType, signature, rawBody)` documents a business operation; a route handler that does the same work inline does not.
