plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

val otelAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":shared"))

    // Ktor HTTP client (CIO engine)
    implementation(libs.bundles.ktor.client)

    // Exposed + HikariCP + PostgreSQL
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)

    // RabbitMQ
    implementation(libs.rabbitmq.amqp.client)

    // Coroutines (Semaphore, concurrency control)
    implementation(libs.kotlinxCoroutines)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // OpenTelemetry Java agent (agent-only — not on the application classpath)
    otelAgent(libs.otel.agent)

    // Tests
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.bundles.flyway)
}

application {
    mainClass = "io.github.cvieirasp.worker.MainKt"
}

tasks.named<JavaExec>("run") {
    doFirst {
        jvmArgs("-javaagent:${otelAgent.singleFile.absolutePath}")
    }
    environment("OTEL_SERVICE_NAME",             System.getenv("OTEL_SERVICE_NAME")             ?: "webhookhub-worker")
    environment("OTEL_EXPORTER_OTLP_ENDPOINT",   System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")   ?: "http://localhost:4318")
    environment("OTEL_LOGS_EXPORTER",            System.getenv("OTEL_LOGS_EXPORTER")            ?: "none")
}
