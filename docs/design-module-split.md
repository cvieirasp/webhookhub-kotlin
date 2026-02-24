# Why split into API, Worker, and Shared?

The system separates ingestion from delivery because the two workloads have fundamentally different runtime characteristics.

The **API** is request-driven: it must respond in milliseconds, validate signatures, persist the event, and enqueue a job. Tying delivery into the request cycle would couple latency to the health of every downstream destination. One slow or failing webhook target would block the caller.

The **Worker** is queue-driven: it runs as a long-lived consumer, owns all retry logic, and interacts with destination URLs that may be slow or unavailable. Its concurrency is controlled independently via RabbitMQ prefetch count (`basicQos(5)`), not by the HTTP thread pool of the API. Both processes can be scaled, deployed, and restarted independently.

The **Shared** module exists to eliminate duplication without coupling. The API publishes `DeliveryJob` messages and the Worker consumes them. They must agree on the same wire format. Likewise, both processes declare the RabbitMQ topology on startup; declaring it in a single `RabbitMQTopology.declare()` call in Shared guarantees they always agree on exchange names, queue arguments, and bindings, with no risk of drift between the two codebases.
