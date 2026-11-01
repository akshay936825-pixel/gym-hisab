package com.gym.hisab.core

import com.gym.hisab.data.local.AllocationE
import com.gym.hisab.data.local.InvoiceE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.ExpenseE
import java.time.LocalDate
import java.time.YearMonth

/**
 * Month-wise fee ledger. Pure logic (no Android, no I/O).
 * One invoice = one member + one calendar month. Payments are applied to invoices through
 * allocations; an old month's due is never replaced by a new month's due.
 */
object FeeLedger {
    enum class Status { PAID, PARTIAL, UNPAID }

    data class Row(val invoice: InvoiceE, val paidPaise: Long) {
        val pendingPaise: Long get() = if (invoice.voided) 0L else maxOf(0L, invoice.feePaise - paidPaise)
        val status: Status
            get() = when {
                paidPaise >= invoice.feePaise -> Status.PAID
                paidPaise > 0 -> Status.PARTIAL
                else -> Status.UNPAID
            }
    }

    data class Part(val invoiceId: String, val amountPaise: Long)
    data class Result(val parts: List<Part>, val unallocatedPaise: Long)

    /** Months (yyyy-MM) from the join month up to [today]'s month that have no invoice yet. */
    fun monthsToGenerate(joinedOn: String, today: LocalDate, existing: Set<String>): List<String> {
        val joined = Dates.parseOrNull(joinedOn) ?: return emptyList()
        var ym = YearMonth.from(joined)
        val last = YearMonth.from(today)
        val out = ArrayList<String>()
        while (!ym.isAfter(last)) {
            val key = ym.toString()
            if (key !in existing) out.add(key)
            ym = ym.plusMonths(1)
        }
        return out
    }

    fun rows(invoices: List<InvoiceE>, allocations: List<AllocationE>): List<Row> {
        val paid = allocations.groupBy { it.invoiceId }.mapValues { e -> e.value.sumOf { it.amountPaise } }
        return invoices.sortedBy { it.month }.map { Row(it, paid[it.id] ?: 0L) }
    }

    fun outstanding(rows: List<Row>): Long = rows.sumOf { it.pendingPaise }

    /**
     * Oldest outstanding month first. If [selectedInvoiceId] is given, that month is filled first
     * and any excess continues oldest-first. Whatever cannot be applied is returned unallocated.
     */
    fun allocate(rows: List<Row>, amountPaise: Long, selectedInvoiceId: String? = null): Result {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val open = rows.filter { it.pendingPaise > 0 }.sortedBy { it.invoice.month }
        val ordered = if (selectedInvoiceId == null) open
        else open.sortedWith(compareBy<Row>({ it.invoice.id != selectedInvoiceId }, { it.invoice.month }))
        var left = amountPaise
        val parts = ArrayList<Part>()
        for (r in ordered) {
            if (left <= 0) break
            val take = minOf(left, r.pendingPaise)
            parts.add(Part(r.invoice.id, take))
            left -= take
        }
        return Result(parts, left)
    }

    /** A refund reverses part of ONE month; it can never exceed what was paid for that month. */
    fun refundPart(row: Row, amountPaise: Long): Part {
        require(amountPaise > 0) { "Amount must be more than zero" }
        require(amountPaise <= row.paidPaise) { "Refund is more than what was paid for this month" }
        return Part(row.invoice.id, -amountPaise)
    }

    data class MonthSummary(
        val month: String,
        val demandPaise: Long,       // fees billed for this month
        val outstandingPaise: Long,  // billed minus applied, for this month's invoices
        val collectedPaise: Long,    // cash received on dates in this month (payments minus refunds)
        val expensesPaise: Long,
    ) { val netPaise: Long get() = collectedPaise - expensesPaise }

    /** Demand, collections, outstanding and expenses are kept separate, never mixed. */
    fun monthly(
        invoices: List<InvoiceE>, allocations: List<AllocationE>,
        payments: List<PaymentE>, expenses: List<ExpenseE>,
    ): List<MonthSummary> {
        val rows = rows(invoices.filter { !it.voided }, allocations)
        val demand = rows.groupBy { it.invoice.month }.mapValues { e -> e.value.sumOf { it.invoice.feePaise } }
        val pending = rows.groupBy { it.invoice.month }.mapValues { e -> e.value.sumOf { it.pendingPaise } }
        val collected = payments.groupBy { it.paidOn.take(7) }.mapValues { e -> e.value.sumOf { Ledger.signed(it) } }
        val spent = expenses.filter { !it.deleted }.groupBy { it.spentOn.take(7) }
            .mapValues { e -> e.value.sumOf { it.amountPaise } }
        val months = (demand.keys + collected.keys + spent.keys).toSortedSet()
        return months.map { MonthSummary(it, demand[it] ?: 0, pending[it] ?: 0, collected[it] ?: 0, spent[it] ?: 0) }
    }

    fun yearly(months: List<MonthSummary>, year: Int): MonthSummary {
        val ms = months.filter { it.month.startsWith("$year-") }
        return MonthSummary("$year", ms.sumOf { it.demandPaise }, ms.sumOf { it.outstandingPaise },
            ms.sumOf { it.collectedPaise }, ms.sumOf { it.expensesPaise })
    }
}
