package io.vladar107.data.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object Db {
    fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database {
        // H2 needs CASE_INSENSITIVE_IDENTIFIERS so Exposed's quoted-lowercase identifiers match
        // H2's unquoted-uppercase storage. No-op / not appended for Postgres (folds to lowercase natively).
        val effectiveUrl = if (jdbcUrl.startsWith("jdbc:h2:") && !jdbcUrl.contains("CASE_INSENSITIVE_IDENTIFIERS"))
            "$jdbcUrl;CASE_INSENSITIVE_IDENTIFIERS=TRUE" else jdbcUrl
        val ds: DataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = effectiveUrl; username = user; this.password = password
            // driverClassName inferred from the URL by Hikari; set explicitly for clarity:
            driverClassName = if (effectiveUrl.startsWith("jdbc:postgresql:")) "org.postgresql.Driver" else "org.h2.Driver"
            maximumPoolSize = 10
        })
        Flyway.configure().dataSource(ds).load().migrate()
        return Database.connect(ds)
    }
}
