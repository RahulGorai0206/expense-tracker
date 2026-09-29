package com.myapp.expensetracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SplitRepository(
    private val dao: SplitDao,
    private val appUser: AppUserStore
) {
    fun observeEvents(): Flow<List<SplitEvent>> = dao.observeEvents()

    val appUserName: StateFlow<String> get() = appUser.name

    fun observeEvent(eventId: Long): Flow<SplitEvent?> = dao.observeEvent(eventId)

    fun observeMembers(eventId: Long): Flow<List<SplitMember>> = dao.observeMembers(eventId)

    fun observeExpenses(eventId: Long): Flow<List<SplitExpense>> = dao.observeExpenses(eventId)

    fun observeShares(eventId: Long): Flow<List<SplitShare>> = dao.observeSharesForEvent(eventId)

    fun observePayments(eventId: Long): Flow<List<SplitPayment>> = dao.observePayments(eventId)

    /**
     * Creates the event with the app user already in it, so every split starts
     * with "you" as a member and your share is known from the first expense.
     * Skipped when no name has been set — the event is created empty as before.
     */
    suspend fun createEvent(name: String): Long {
        val now = System.currentTimeMillis()
        val eventId =
            dao.insertEvent(SplitEvent(name = name.trim(), createdAt = now, updatedAt = now))
        addAppUser(eventId)
        return eventId
    }

    /** Adds the app user to an event that doesn't have them yet. */
    suspend fun addAppUser(eventId: Long): Long? {
        val userName = appUser.name.value
        if (userName.isBlank()) return null
        return dao.insertMember(
            SplitMember(eventId = eventId, displayName = userName, isAppUser = true)
        )
    }

    /** Marks an existing member as the app user, for events made before the name was set. */
    suspend fun markAppUser(eventId: Long, memberId: Long) {
        dao.markAppUser(eventId, memberId)
        // Take the app name now rather than on the next rename in Settings,
        // which would rename them anyway and look like it came from nowhere.
        appUser.name.value.takeIf { it.isNotBlank() }?.let { dao.renameAppUserMembers(it) }
    }

    suspend fun linkShareToTransaction(shareId: Long, transactionId: Int?) {
        dao.linkShareToTransaction(shareId, transactionId)
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
