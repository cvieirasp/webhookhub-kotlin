plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
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

    // Tests
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass = "io.github.cvieirasp.worker.MainKt"
}
