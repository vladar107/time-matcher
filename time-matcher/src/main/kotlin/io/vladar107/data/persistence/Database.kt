package io.vladar107.data.persistence

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

object Db {
    fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database {
        // H2 in-memory databases need CASE_INSENSITIVE_IDENTIFIERS=TRUE so that Exposed's
        // quoted identifiers (e.g. "name") match columns created unquoted (stored as NAME).
        val effectiveUrl = if (jdbcUrl.startsWith("jdbc:h2:") && !jdbcUrl.contains("CASE_INSENSITIVE_IDENTIFIERS"))
            "$jdbcUrl;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        else jdbcUrl
        Flyway.configure().dataSource(effectiveUrl, user, password).load().migrate()
        return Database.connect(effectiveUrl, driver = "org.h2.Driver", user = user, password = password)
    }
}
