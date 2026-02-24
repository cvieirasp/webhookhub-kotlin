# Why is PostgreSQL the source of truth and RabbitMQ only a transport?

Every delivery is persisted to PostgreSQL as a `PENDING` record **before** the job is published to RabbitMQ. The broker is used purely for execution scheduling - getting the job to a worker - not for storing state.

This means the database is always authoritative. If RabbitMQ is restarted or a queue is purged, the delivery records remain in Postgres and can be replayed. The worker writes `DELIVERED`, `RETRYING`, or `DEAD` back to the database after each attempt, so the current state of every delivery is always queryable without inspecting the broker.
