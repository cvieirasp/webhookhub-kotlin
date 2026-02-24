# Why feature-based packages rather than layer-based?

Each module is organised by **feature** (`source`, `destination`, `ingest`, `delivery`) rather than by layer (`controllers`, `services`, `repositories`). Every feature folder owns its domain model, table definition, repository interface and implementation, use case, and routes.

This keeps related code co-located. When working on delivery retries, for example, everything relevant is under `delivery/`; there is no need to jump between a `controllers/` package, a `services/` package, and a `repositories/` package to understand a single feature. Adding, changing, or deleting a feature is a self-contained operation.

Layer-based organisation tends to produce high coupling between distant files and low cohesion within each layer. Feature-based organisation inverts that: each feature is highly cohesive and minimally coupled to other features.
