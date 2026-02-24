# Why Ktor instead of Spring?

Ktor is lightweight and coroutine-native. It starts fast, requires minimal configuration, and has no hidden "magic". Every plugin, route, and serialiser is registered explicitly in code. This makes the startup sequence easy to follow and test.

Spring Boot's abstractions are valuable in large teams maintaining large codebases, but for a focused service like this they introduce overhead (annotation scanning, proxy generation, autoconfiguration) without adding proportional value.
