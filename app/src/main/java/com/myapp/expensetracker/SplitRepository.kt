package com.myapp.expensetracker

import kotlinx.coroutines.flow.Flow

class SplitRepository(private val dao: SplitDao) {
    fun observeEvents(): Flow<List<SplitEvent>> = dao.observeEvents()

    fun observeEvent(eventId: Long): Flow<SplitEvent?> = dao.observeEvent(eventId)

    fun observeMembers(eventId: Long): Flow<List<SplitMember>> = dao.observeMembers(eventId)

    fun observeExpenses(eventId: Long): Flow<List<SplitExpense>> = dao.observeExpenses(eventId)

    fun observeShares(eventId: Long): Flow<List<SplitShare>> = dao.observeSharesForEvent(eventId)

    fun observePayments(eventId: Long): Flow<List<SplitPayment>> = dao.observePayments(eventId)

    suspend fun createEvent(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertEvent(SplitEvent(name = name.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun deleteEvent(event: SplitEvent) {
        dao.deleteEvent(event)
    }

    suspend fun addMember(
        eventId: Long,
        displayName: String,
        contactLookupKey: String? = null
    ): Long {
        return dao.insertMember(
            SplitMember(
                eventId = eventId,
                displayName = displayName.trim(),
                contactLookupKey = contactLookupKey
            )
        )
    }

    suspend fun saveSplit(
        eventId: Long,
        amount: Double,
        description: String,
        paidByMemberId: Long,
        mode: SplitMode,
        shares: List<SplitShareDraft>,
        // Defaults to now for splits typed in by hand; a split made from a
        // detected transaction passes the transaction's own date instead.
        createdAt: Long = System.currentTimeMillis()
    ) {
        dao.insertExpenseWithShares(
            expense = SplitExpense(
                eventId = eventId,
                amount = amount,
                description = description.trim(),
                paidByMemberId = paidByMemberId,
                splitMode = mode.dbValue,
                createdAt = createdAt
            ),
            shares = shares.map {
                SplitShare(
                    splitExpenseId = 0,
                    memberId = it.memberId,
                    owedAmount = it.owedAmount,
                    percentage = it.percentage
                )
            }
        )
    }

    suspend fun deleteSplit(expenseId: Long) {
        dao.deleteExpenseById(expenseId)
    }

    suspend fun markPaid(
        eventId: Long,
        fromMemberId: Long,
        toMemberId: Long,
        amount: Double,
        note: String = ""
    ) {
        dao.insertPaymentAndTouchEvent(
            SplitPayment(
                eventId = eventId,
                fromMemberId = fromMemberId,
                toMemberId = toMemberId,
                amount = amount,
                note = note
            )
        )
    }
}
