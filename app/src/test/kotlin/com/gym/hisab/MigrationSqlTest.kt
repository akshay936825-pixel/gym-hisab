package com.gym.hisab

import com.gym.hisab.data.local.MigrationSql
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Builds a v1-shaped database with data, runs the real migration SQL, and checks that nothing was lost. */
class MigrationSqlTest {
    private fun conn(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun v1(c: Connection) = c.createStatement().use { s ->
        s.execute("CREATE TABLE members (id TEXT NOT NULL, gymId TEXT NOT NULL, name TEXT NOT NULL, phone TEXT, joinedOn TEXT NOT NULL, notes TEXT, archived INTEGER NOT NULL, updatedAt TEXT, PRIMARY KEY(id))")
        s.execute("CREATE TABLE payments (id TEXT NOT NULL, gymId TEXT NOT NULL, memberId TEXT NOT NULL, membershipId TEXT, kind TEXT NOT NULL, amountPaise INTEGER NOT NULL, mode TEXT NOT NULL, paidOn TEXT NOT NULL, note TEXT, receiptNo TEXT NOT NULL, updatedAt TEXT, PRIMARY KEY(id))")
        s.execute("INSERT INTO members VALUES ('m1','g','Pranav gudha','999','2026-10-14',NULL,0,NULL)")
        s.execute("INSERT INTO members VALUES ('m2','g','Lalit matora',NULL,'2026-10-15',NULL,1,NULL)")
        s.execute("INSERT INTO payments VALUES ('p1','g','m1',NULL,'payment',40000,'cash','2026-10-22',NULL,'R1',NULL)")
    }

    private fun count(c: Connection, sql: String): Int =
        c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

    @Test fun migrationKeepsEveryExistingRow() {
        conn().use { c ->
            v1(c)
            MigrationSql.V1_TO_2.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            assertEquals(2, count(c, "SELECT COUNT(*) FROM members"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM payments"))
            assertEquals(40000, count(c, "SELECT amountPaise FROM payments WHERE id='p1'"))
            assertEquals(1, count(c, "SELECT archived FROM members WHERE id='m2'"))
            assertEquals(0, count(c, "SELECT monthlyFeePaise FROM members WHERE id='m1'")) // new column defaults to 0
            assertEquals(0, count(c, "SELECT COUNT(*) FROM invoices"))
        }
    }

    @Test fun migrationContainsNoDestructiveStatement() {
        val bad = Regex("\\b(DROP|DELETE|UPDATE|TRUNCATE)\\b", RegexOption.IGNORE_CASE)
        MigrationSql.V1_TO_2.forEach { if (bad.containsMatchIn(it)) fail("Destructive SQL in migration: $it") }
    }

    @Test fun oneInvoicePerMemberAndMonthIsEnforced() {
        conn().use { c ->
            v1(c)
            MigrationSql.V1_TO_2.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            val ins = "INSERT OR IGNORE INTO invoices VALUES ('%s','m1','2026-10',50000,0,NULL,'2026-10-01')"
            c.createStatement().use { it.execute(ins.format("a")); it.execute(ins.format("b")) }
            assertEquals(1, count(c, "SELECT COUNT(*) FROM invoices"))
        }
    }
}
