# Why Exposed instead of JPA/Hibernate?

Exposed's SQL DSL means the queries written in the repository implementations are very close to the SQL that will actually run. There is no session lifecycle, no lazy-loading surprise, no N+1 query to hunt down, and no entity state machine to reason about. The mapping from result set to domain model is explicit and straightforward.

JPA is powerful but trades control for convenience. In a system where delivery throughput and predictable query behaviour matter, explicit SQL is the better trade-off.
