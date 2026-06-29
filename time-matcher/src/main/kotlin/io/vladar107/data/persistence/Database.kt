package io.vladar107.data.persistence

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

object Db {
    fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database {
        Flyway.configure().dataSource(jdbcUrl, user, password).load().migrate()
        return Database.connect(jdbcUrl, driver = "org.h2.Driver", user = user, password = password)
    }
}
