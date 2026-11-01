package com.gym.hisab.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Plain SQL so it can be unit-tested on the JVM against a copy of a v1 database. */
object MigrationSql {
    /** Additive only. No DROP, DELETE, UPDATE or table rebuild: every existing row is untouched. */
    val V1_TO_2: List<String> = listOf(
        "ALTER TABLE members ADD COLUMN monthlyFeePaise INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS invoices (id TEXT NOT NULL, memberId TEXT NOT NULL, month TEXT NOT NULL, " +
            "feePaise INTEGER NOT NULL, voided INTEGER NOT NULL, note TEXT, createdAt TEXT NOT NULL, PRIMARY KEY(id))",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_invoices_memberId_month ON invoices (memberId, month)",
        "CREATE TABLE IF NOT EXISTS allocations (id TEXT NOT NULL, paymentId TEXT NOT NULL, invoiceId TEXT NOT NULL, " +
            "amountPaise INTEGER NOT NULL, createdAt TEXT NOT NULL, PRIMARY KEY(id))",
        "CREATE INDEX IF NOT EXISTS index_allocations_paymentId ON allocations (paymentId)",
        "CREATE INDEX IF NOT EXISTS index_allocations_invoiceId ON allocations (invoiceId)",
    )
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.V1_TO_2.forEach { db.execSQL(it) }
    }
}
