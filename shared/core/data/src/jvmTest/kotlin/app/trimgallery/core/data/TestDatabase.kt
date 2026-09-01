package app.trimgallery.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trimgallery.core.data.db.TrimDatabase

/**
 * A database that behaves like the one on a phone.
 *
 * The one line that matters is `PRAGMA foreign_keys = ON`. `AndroidDatabase` sets it in its
 * `onOpen` callback, because SCHEMA.md's `ON DELETE CASCADE` is inert without it; SQLite
 * defaults it **off**, and `JdbcSqliteDriver` does not set it.
 *
 * That difference shipped a crash loop. `media_item.folder_grant_id` references
 * `folder_grant(id)`, nothing wrote a `folder_grant` row when a folder was granted, and the
 * first scan's insert therefore violated a constraint — on the device. In these tests the
 * same insert succeeded, seven times, green, because the constraint was not being enforced.
 *
 * So every test builds its driver here and nowhere else. A test environment that is more
 * permissive than production does not merely miss bugs: it certifies them.
 */
internal fun testDriver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).apply {
    TrimDatabase.Schema.create(this)
    execute(null, "PRAGMA foreign_keys = ON;", 0)
}
