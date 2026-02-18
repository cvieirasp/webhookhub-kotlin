plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
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

    // Tests
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass = "io.github.cvieirasp.api.MainKt"
}
