package com.gym.hisab.data

import androidx.room.withTransaction
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ids
import com.gym.hisab.core.Ledger
import com.gym.hisab.core.FeeLedger
import com.gym.hisab.data.local.AllocationE
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.InvoiceE
import com.gym.hisab.data.local.HisabDb
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * Local-only data layer. Room is the single source of truth and the only live database.
 * Every write is one Room transaction; nothing here touches the network.
 *
 * Money rules:
 *  - amounts are Long paise;
 *  - a payment is never edited or deleted: mistakes are fixed with a refund entry plus a new
 *    payment, so the history stays complete;
 *  - every save carries a request id. Saving the same request twice (double tap, retry) returns
 *    the original row and writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Repository(
    private val db: HisabDb,
    private val prefs: Prefs,
    private val onLocalWrite: () -> Unit = {},
) {
    private val dao = db.dao()

    val gymId: StateFlow<String?> = prefs.gymId
    val gymName: StateFlow<String> = prefs.gymName

    private fun <T> byGym(empty: T, f: (String) -> Flow<T>): Flow<T> =
        prefs.gymId.flatMapLatest { g -> if (g == null) flowOf(empty) else f(g) }

    val members: Flow<List<MemberE>> = byGym(emptyList()) { dao.members(it) }
    val memberships: Flow<List<MembershipE>> = byGym(emptyList()) { dao.memberships(it) }
    val payments: Flow<List<PaymentE>> = byGym(emptyList()) { dao.payments(it) }
    val expenses: Flow<List<ExpenseE>> = byGym(emptyList()) { dao.expenses(it) }
    val plans: Flow<List<PlanE>> = byGym(emptyList()) { dao.plans(it) }
    val invoices: Flow<List<InvoiceE>> = dao.invoices()
    val allocations: Flow<List<AllocationE>> = dao.allocations()

    private fun gym(): String = prefs.gymId.value ?: Prefs.LOCAL_GYM_ID

    /** Runs once, off the main thread: opens the DB and seeds default plans on a brand-new install only. */
    suspend fun bootstrapLocal() {
        if (prefs.plansSeeded) return
        if (dao.planCount() == 0) {
            val g = gym()
            val defaults = listOf(
                Triple("Monthly", 1, 100000L), Triple("Quarterly", 3, 270000L),
                Triple("Half-yearly", 6, 500000L), Triple("Annual", 12, 900000L),
            ).map { PlanE(Ids.newId(), g, it.first, it.second, it.third) }
            db.withTransaction { if (dao.planCount() == 0) dao.upsertPlans(defaults) }
        }
        prefs.plansSeeded = true
    }

    // ---------------------------------------------------------------- members & money
    /** @return the receipt payment, or null when nothing was paid now (or this request was already saved). */
    suspend fun addMember(
        requestId: String, name: String, phone: String?, plan: PlanE?, startOn: LocalDate,
        discountPaise: Long, paidPaise: Long, mode: String,
    ): PaymentE? {
        val g = gym()
        return db.withTransaction {
            if (dao.member(requestId) != null) return@withTransaction null // already saved by an earlier tap
            val member = MemberE(requestId, g, name.trim(), phone?.trim()?.ifBlank { null }, startOn.toString())
            dao.upsertMembers(listOf(member))
            if (plan != null) sellMembership(g, requestId, member.id, plan, startOn, discountPaise, paidPaise, mode) else null
        }.also { onLocalWrite() }
    }

    suspend fun renew(
        requestId: String, memberId: String, plan: PlanE, startOn: LocalDate,
        discountPaise: Long, paidPaise: Long, mode: String,
    ): PaymentE? {
        val g = gym()
        return db.withTransaction {
            if (dao.membership(Ids.derived(requestId, "membership")) != null) return@withTransaction null
            sellMembership(g, requestId, memberId, plan, startOn, discountPaise, paidPaise, mode)
        }.also { onLocalWrite() }
    }

    private suspend fun sellMembership(
        g: String, requestId: String, memberId: String, plan: PlanE, startOn: LocalDate,
        discountPaise: Long, paidPaise: Long, mode: String,
    ): PaymentE? {
        val discount = discountPaise.coerceIn(0L, plan.pricePaise)
        val ms = MembershipE(
            id = Ids.derived(requestId, "membership"), gymId = g, memberId = memberId, planId = plan.id, planName = plan.name,
            startOn = startOn.toString(), endOn = Ledger.endDate(startOn, plan.durationMonths).toString(),
            pricePaise = plan.pricePaise, discountPaise = discount,
        )
        dao.upsertMemberships(listOf(ms))
        val pay = paidPaise.coerceIn(0L, plan.pricePaise - discount)
        if (pay <= 0) return null
        return writePayment(Ids.derived(requestId, "payment"), g, memberId, ms.id, Ledger.PAYMENT, pay, mode, null)
    }

    /** Records a (possibly partial) payment. Never more than the balance still due. */
    suspend fun collect(requestId: String, memberId: String, membershipId: String?, amountPaise: Long, mode: String, note: String?): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        return db.withTransaction {
            dao.payment(requestId)?.let { return@withTransaction it } // duplicate submission: return the original
            if (membershipId != null) {
                val ms = dao.membership(membershipId)
                if (ms != null) {
                    val balance = Ledger.balance(ms, dao.paymentsOfMembership(ms.id))
                    require(amountPaise <= balance) { "Amount is more than the balance due" }
                }
            }
            writePayment(requestId, g, memberId, membershipId, Ledger.PAYMENT, amountPaise, mode, note)
        }.also { onLocalWrite() }
    }

    /** A refund is a new, negative ledger entry. The original payment stays in the history. */
    suspend fun refund(requestId: String, memberId: String, membershipId: String?, amountPaise: Long, mode: String, note: String?): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        return db.withTransaction {
            dao.payment(requestId)?.let { return@withTransaction it }
            if (membershipId != null) {
                val net = Ledger.netPaid(dao.paymentsOfMembership(membershipId))
                require(amountPaise <= net) { "Refund is more than what was paid" }
            }
            writePayment(requestId, g, memberId, membershipId, Ledger.REFUND, amountPaise, mode, note)
        }.also { onLocalWrite() }
    }

    private suspend fun writePayment(
        id: String, g: String, memberId: String, membershipId: String?, kind: String, amount: Long, mode: String, note: String?,
        on: LocalDate = Dates.today(),
    ): PaymentE {
        val today = on
        val p = PaymentE(
            id = id, gymId = g, memberId = memberId, membershipId = membershipId, kind = kind,
            amountPaise = amount, mode = mode, paidOn = today.toString(), note = note?.trim()?.ifBlank { null },
            receiptNo = Ids.receiptNo(today),
        )
        dao.insertPaymentIfAbsent(p) // IGNORE: an existing payment row can never be overwritten
        return p
    }

    // ---------------------------------------------------------------- month-wise fee ledger
    /**
     * Creates the missing monthly invoices (join month .. current month) for every active member
     * with a monthly fee. The invoice id is derived from member+month and (member, month) is unique,
     * so running this any number of times can never create a duplicate. Existing invoices are never changed.
     */
    private suspend fun generateMissingInvoices(today: LocalDate): Int {
        val existing = dao.allInvoices().groupBy { it.memberId }.mapValues { e -> e.value.map { it.month }.toSet() }
        val fresh = dao.allMembers().filter { !it.archived && it.monthlyFeePaise > 0 }.flatMap { m ->
            FeeLedger.monthsToGenerate(m.joinedOn, today, existing[m.id].orEmpty()).map { month ->
                InvoiceE(Ids.derived(m.id, "invoice-$month"), m.id, month, m.monthlyFeePaise, createdAt = today.toString())
            }
        }
        if (fresh.isNotEmpty()) dao.insertInvoicesIfAbsent(fresh)
        return fresh.size
    }

    suspend fun ensureInvoices(today: LocalDate = Dates.today()): Int {
        val n = db.withTransaction { generateMissingInvoices(today) }
        if (n > 0) onLocalWrite()
        return n
    }

    /**
     * Records a payment and applies it to monthly invoices: oldest month first, or [selectedInvoiceId]
     * first when the owner picks a month. The payment's date and month allocation are never changed later.
     * Saving the same [requestId] twice returns the first payment and writes nothing.
     */
    suspend fun collectMonthly(
        requestId: String, memberId: String, amountPaise: Long, mode: String, note: String?,
        selectedInvoiceId: String? = null, paidOn: LocalDate = Dates.today(),
    ): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        return db.withTransaction {
            dao.payment(requestId)?.let { return@withTransaction it }
            generateMissingInvoices(Dates.today())
            val rows = FeeLedger.rows(dao.invoicesOfMember(memberId), dao.allocationsOfMember(memberId))
            val res = FeeLedger.allocate(rows, amountPaise, selectedInvoiceId)
            require(res.unallocatedPaise == 0L) { "Amount is more than the total pending" }
            val pay = writePayment(requestId, g, memberId, null, Ledger.PAYMENT, amountPaise, mode, note, paidOn)
            dao.insertAllocationsIfAbsent(res.parts.map {
                AllocationE(Ids.derived(requestId, "alloc-${it.invoiceId}"), pay.id, it.invoiceId, it.amountPaise, paidOn.toString())
            })
            pay
        }.also { onLocalWrite() }
    }

    /** Refund for ONE month: a new refund payment plus a negative allocation. Nothing is deleted. */
    suspend fun refundMonthly(requestId: String, invoiceId: String, amountPaise: Long, mode: String, note: String?): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        return db.withTransaction {
            dao.payment(requestId)?.let { return@withTransaction it }
            val inv = requireNotNull(dao.invoice(invoiceId)) { "Invoice not found" }
            val row = FeeLedger.rows(listOf(inv), dao.allocationsOfMember(inv.memberId).filter { it.invoiceId == inv.id }).first()
            val part = FeeLedger.refundPart(row, amountPaise)
            val today = Dates.today()
            val pay = writePayment(requestId, g, inv.memberId, null, Ledger.REFUND, amountPaise, mode, note, today)
            dao.insertAllocationsIfAbsent(listOf(AllocationE(Ids.derived(requestId, "alloc-$invoiceId"), pay.id, invoiceId, part.amountPaise, today.toString())))
            pay
        }.also { onLocalWrite() }
    }

    /** Correction with audit trail: the invoice stays in the database, marked voided with a reason. Only if nothing is paid on it. */
    suspend fun voidInvoice(invoiceId: String, reason: String) {
        require(reason.isNotBlank()) { "A reason is required" }
        db.withTransaction {
            val inv = requireNotNull(dao.invoice(invoiceId)) { "Invoice not found" }
            val paid = dao.allocationsOfMember(inv.memberId).filter { it.invoiceId == inv.id }.sumOf { it.amountPaise }
            require(paid == 0L) { "Refund the payments for this month before voiding it" }
            dao.upsertInvoices(listOf(inv.copy(voided = true, note = reason.trim())))
        }
        onLocalWrite()
    }

    suspend fun updateMember(m: MemberE) { db.withTransaction { dao.upsertMembers(listOf(m)) }; onLocalWrite() }

    suspend fun cancelMembership(ms: MembershipE) {
        val today = Dates.today().toString()
        val newEnd = maxOf(ms.startOn, minOf(ms.endOn, today))
        db.withTransaction { dao.upsertMemberships(listOf(ms.copy(status = Ledger.CANCELLED, endOn = newEnd))) }
        onLocalWrite()
    }

    // ---------------------------------------------------------------- expenses & plans
    suspend fun addExpense(category: String, amountPaise: Long, spentOn: LocalDate, note: String?) {
        val e = ExpenseE(Ids.newId(), gym(), category.trim(), amountPaise, spentOn.toString(), note?.trim()?.ifBlank { null })
        db.withTransaction { dao.upsertExpenses(listOf(e)) }
        onLocalWrite()
    }

    suspend fun deleteExpense(e: ExpenseE) { // soft delete: the row stays in the database and in backups
        db.withTransaction { dao.upsertExpenses(listOf(e.copy(deleted = true))) }
        onLocalWrite()
    }

    suspend fun savePlan(p: PlanE) {
        val fixed = if (p.gymId.isBlank()) p.copy(gymId = gym()) else p
        db.withTransaction { dao.upsertPlans(listOf(fixed)) }
        onLocalWrite()
    }

    fun setGymName(name: String) = prefs.setGymName(name)
}
