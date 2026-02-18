package io.github.cvieirasp.api.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * DatabaseFactory is responsible for initializing the database connection and running migrations.
 * It uses HikariCP for connection pooling and Flyway for database migrations.
 */
@Serializable
data class DbPoolStats(
    val total: Int,
    val active: Int,
    val idle: Int,
    val pending: Int,
)

object DatabaseFactory {

    private lateinit var db: Database
    private lateinit var dataSource: HikariDataSource

    /**
     * Initializes the database connection and runs migrations.
     * It reads the database configuration from environment variables, with defaults for local development.
     */
    fun init() {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/webhookhub"
            username = System.getenv("DB_USER") ?: "webhookhub"
            password = System.getenv("DB_PASSWORD") ?: "webhookhub"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        })

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        db = Database.connect(dataSource)
    }

    /**
     * Retrieves the current statistics of the database connection pool.
     * It returns the total number of connections, active connections, idle connections, and pending threads.
     */
    fun poolStats(): DbPoolStats {
        val pool = dataSource.hikariPoolMXBean
        return DbPoolStats(
            total = pool?.totalConnections ?: 0,
            active = pool?.activeConnections ?: 0,
            idle = pool?.idleConnections ?: 0,
            pending = pool?.threadsAwaitingConnection ?: 0,
        )
    }

    /**
     * Pings the database to check if the connection is alive.
     * It executes a simple query and returns true if successful, false otherwise.
     */
    fun ping(): Boolean = try {
        transaction(db) {
            exec("SELECT 1") { it.next() } == true
        }
    } catch (e: Exception) {
        false
    }
}
