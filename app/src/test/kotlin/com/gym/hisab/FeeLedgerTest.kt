package com.gym.hisab

import com.gym.hisab.core.FeeLedger
import com.gym.hisab.core.FeeLedger.Status
import com.gym.hisab.data.local.AllocationE
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.InvoiceE
import com.gym.hisab.data.local.PaymentE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class FeeLedgerTest {
    private val fee = 50000L // Rs 500
    private fun inv(month: String, f: Long = fee, voided: Boolean = false) =
        InvoiceE("inv-$month", "m1", month, f, voided, null, "2026-10-01")
    private fun alloc(id: String, month: String, amt: Long) = AllocationE(id, "p-$id", "inv-$month", amt, "2026-10-01")

    // 1 + 2: joins in October, October invoice generated once
    @Test fun joinMonthIsBilledOnceAndNothingBeforeJoin() {
        val today = LocalDate.of(2026, 10, 20)
        val first = FeeLedger.monthsToGenerate("2026-10-14", today, emptySet())
        assertEquals(listOf("2026-10"), first)
        // running again with the invoice already present creates nothing
        assertTrue(FeeLedger.monthsToGenerate("2026-10-14", today, setOf("2026-10")).isEmpty())
        // join date in the future: nothing yet
        assertTrue(FeeLedger.monthsToGenerate("2026-12-01", today, emptySet()).isEmpty())
    }

    // 3 + 4: November is a separate invoice; October stays unpaid
    @Test fun novemberIsSeparateAndOctoberStaysOpen() {
        val nov = LocalDate.of(2026, 11, 2)
        assertEquals(listOf("2026-11"), FeeLedger.monthsToGenerate("2026-10-14", nov, setOf("2026-10")))
        val rows = FeeLedger.rows(listOf(inv("2026-10"), inv("2026-11")), emptyList())
        assertEquals(2, rows.size)
        assertEquals(100000L, FeeLedger.outstanding(rows))
        assertTrue(rows.all { it.status == Status.UNPAID })
    }

    @Test fun missedMonthsAreAllGenerated() {
        val r = FeeLedger.monthsToGenerate("2026-08-31", LocalDate.of(2026, 11, 1), emptySet())
        assertEquals(listOf("2026-08", "2026-09", "2026-10", "2026-11"), r)
    }

    // 5 + 7: Rs 700 against Oct 500 + Nov 500 -> Oct paid, Nov 200 paid / 300 pending
    @Test fun oldestFirstPartialPayment() {
        val rows = FeeLedger.rows(listOf(inv("2026-10"), inv("2026-11")), emptyList())
        val res = FeeLedger.allocate(rows, 70000)
        assertEquals(listOf(FeeLedger.Part("inv-2026-10", 50000), FeeLedger.Part("inv-2026-11", 20000)), res.parts)
        assertEquals(0L, res.unallocatedPaise)
        val after = FeeLedger.rows(listOf(inv("2026-10"), inv("2026-11")),
            res.parts.mapIndexed { i, p -> AllocationE("a$i", "p", p.invoiceId, p.amountPaise, "2026-11-01") })
        assertEquals(Status.PAID, after[0].status)
        assertEquals(Status.PARTIAL, after[1].status)
        assertEquals(30000L, after[1].pendingPaise)
        assertEquals(30000L, FeeLedger.outstanding(after))
    }

    // 6: a full payment closes exactly that month
    @Test fun fullPaymentClosesMonth() {
        val rows = FeeLedger.rows(listOf(inv("2026-10")), emptyList())
        val res = FeeLedger.allocate(rows, 50000)
        assertEquals(1, res.parts.size)
        val after = FeeLedger.rows(listOf(inv("2026-10")), listOf(alloc("a", "2026-10", 50000)))
        assertEquals(Status.PAID, after[0].status)
        assertEquals(0L, after[0].pendingPaise)
    }

    // 8: owner selects November: November first, excess flows to oldest
    @Test fun selectedMonthIsFilledFirst() {
        val rows = FeeLedger.rows(listOf(inv("2026-10"), inv("2026-11")), emptyList())
        val res = FeeLedger.allocate(rows, 30000, "inv-2026-11")
        assertEquals(listOf(FeeLedger.Part("inv-2026-11", 30000)), res.parts)
        val big = FeeLedger.allocate(rows, 70000, "inv-2026-11")
        assertEquals(listOf(FeeLedger.Part("inv-2026-11", 50000), FeeLedger.Part("inv-2026-10", 20000)), big.parts)
    }

    @Test fun overpaymentIsReportedNotSilentlyKept() {
        val rows = FeeLedger.rows(listOf(inv("2026-10")), emptyList())
        assertEquals(10000L, FeeLedger.allocate(rows, 60000).unallocatedPaise)
    }

    @Test fun paymentIsNeverCountedTwice() {
        val rows = FeeLedger.rows(listOf(inv("2026-10"), inv("2026-11")),
            listOf(alloc("a", "2026-10", 50000)))
        val res = FeeLedger.allocate(rows, 50000)
        assertEquals(listOf(FeeLedger.Part("inv-2026-11", 50000)), res.parts) // October is already closed
    }

    @Test fun refundReversesOnlyWhatWasPaid() {
        val row = FeeLedger.rows(listOf(inv("2026-10")), listOf(alloc("a", "2026-10", 30000))).first()
        assertEquals(-10000L, FeeLedger.refundPart(row, 10000).amountPaise)
        try { FeeLedger.refundPart(row, 30001); fail("must reject") } catch (_: IllegalArgumentException) {}
        val after = FeeLedger.rows(listOf(inv("2026-10")),
            listOf(alloc("a", "2026-10", 30000), alloc("b", "2026-10", -10000)))
        assertEquals(30000L, after[0].pendingPaise) // 500 - (300-100) = 300
    }

    // 10: archiving stops billing in the repository, but invoices and payments already recorded remain readable
    @Test fun historyStaysAfterMemberIsArchived() {
        val rows = FeeLedger.rows(listOf(inv("2026-09"), inv("2026-10")), listOf(alloc("a", "2026-09", 50000)))
        assertEquals(2, rows.size)
        assertEquals(50000L, FeeLedger.outstanding(rows))
    }

    @Test fun voidedInvoiceOwesNothing() {
        val rows = FeeLedger.rows(listOf(inv("2026-10", voided = true)), emptyList())
        assertEquals(0L, FeeLedger.outstanding(rows))
    }

    // 15: demand, collections, outstanding and expenses are separate numbers
    @Test fun reportsKeepDemandCollectionsOutstandingAndExpensesSeparate() {
        val invoices = listOf(inv("2026-10"), inv("2026-11"))
        val allocations = listOf(alloc("a", "2026-10", 50000), alloc("b", "2026-11", 20000))
        val payments = listOf(
            PaymentE("p1", "g", "m1", null, "payment", 50000, "cash", "2026-10-20", null, "R1"),
            PaymentE("p2", "g", "m1", null, "payment", 20000, "upi", "2026-11-02", null, "R2"),
        )
        val expenses = listOf(ExpenseE("e1", "g", "Rent", 30000, "2026-10-05"))
        val m = FeeLedger.monthly(invoices, allocations, payments, expenses)
        val oct = m.first { it.month == "2026-10" }
        val nov = m.first { it.month == "2026-11" }
        assertEquals(50000L, oct.demandPaise); assertEquals(50000L, oct.collectedPaise)
        assertEquals(0L, oct.outstandingPaise); assertEquals(30000L, oct.expensesPaise)
        assertEquals(20000L, oct.netPaise) // collected 500 - expenses 300
        assertEquals(50000L, nov.demandPaise); assertEquals(20000L, nov.collectedPaise)
        assertEquals(30000L, nov.outstandingPaise)
        val year = FeeLedger.yearly(m, 2026)
        assertEquals(100000L, year.demandPaise); assertEquals(70000L, year.collectedPaise)
        assertEquals(30000L, year.outstandingPaise)
    }
}
