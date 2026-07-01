package io.vladar107.data.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object Db {
    private var dataSource: HikariDataSource? = null

    fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database {
        // H2 needs CASE_INSENSITIVE_IDENTIFIERS so Exposed's quoted-lowercase identifiers match
        // H2's unquoted-uppercase storage. No-op / not appended for Postgres (folds to lowercase natively).
        val effectiveUrl = if (jdbcUrl.startsWith("jdbc:h2:") && !jdbcUrl.contains("CASE_INSENSITIVE_IDENTIFIERS"))
            "$jdbcUrl;CASE_INSENSITIVE_IDENTIFIERS=TRUE" else jdbcUrl
        val ds = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = effectiveUrl; username = user; this.password = password
            // Fail fast on unknown JDBC schemes rather than silently falling back to a wrong driver.
            driverClassName = when {
                effectiveUrl.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
                effectiveUrl.startsWith("jdbc:h2:")         -> "org.h2.Driver"
                else -> error("Unsupported JDBC URL scheme: $effectiveUrl")
            }
            maximumPoolSize = 10
        })
        dataSource = ds
        Flyway.configure().dataSource(ds).load().migrate()
        return Database.connect(ds)
    }

    /** Close the connection pool. Call this in tests before stopping the container to avoid
     *  "Connection is not available" warnings from pool-drain racing container shutdown. */
    fun close() { dataSource?.close(); dataSource = null }
}
