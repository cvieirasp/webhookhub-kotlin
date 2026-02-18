package io.github.cvieirasp.shared.config

object EnvConfig {

    fun string(key: String, default: String = ""): String =
        System.getenv(key) ?: default

    fun int(key: String, default: Int): Int =
        System.getenv(key)?.toIntOrNull() ?: default

    fun long(key: String, default: Long): Long =
        System.getenv(key)?.toLongOrNull() ?: default

    fun boolean(key: String, default: Boolean = false): Boolean =
        System.getenv(key)?.toBooleanStrictOrNull() ?: default

    object Database {
        val url: String get() = string("DB_URL", "jdbc:postgresql://localhost:5432/webhookhub")
        val user: String get() = string("DB_USER", "webhookhub")
        val password: String get() = string("DB_PASSWORD", "webhookhub")
    }

    object RabbitMQ {
        val host: String get() = string("RABBITMQ_HOST", "localhost")
        val port: Int get() = int("RABBITMQ_PORT", 5672)
        val username: String get() = string("RABBITMQ_USER", "webhookhub")
        val password: String get() = string("RABBITMQ_PASSWORD", "webhookhub")
        val vhost: String get() = string("RABBITMQ_VHOST", "webhookhub")
    }
}
