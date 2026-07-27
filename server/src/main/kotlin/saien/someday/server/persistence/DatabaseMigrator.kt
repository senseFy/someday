package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import org.flywaydb.core.Flyway

object DatabaseMigrator {
    fun migrate(config: ServerConfig) {
        Flyway.configure()
            .dataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
