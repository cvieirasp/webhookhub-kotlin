plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

val otelAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Exposed + HikariCP + PostgreSQL
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)

    // Flyway migrations
    implementation(libs.bundles.flyway)

    // RabbitMQ
    implementation(libs.rabbitmq.amqp.client)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // OpenTelemetry Java agent (agent-only — not on the application classpath)
    otelAgent(libs.otel.agent)

    // Tests
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass = "io.github.cvieirasp.api.MainKt"
}

tasks.named<JavaExec>("run") {
    doFirst {
        jvmArgs("-javaagent:${otelAgent.singleFile.absolutePath}")
    }
    environment("OTEL_SERVICE_NAME",             System.getenv("OTEL_SERVICE_NAME")             ?: "webhookhub-api")
    environment("OTEL_EXPORTER_OTLP_ENDPOINT",   System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")   ?: "http://localhost:4318")
    environment("OTEL_LOGS_EXPORTER",            System.getenv("OTEL_LOGS_EXPORTER")            ?: "none")
}
