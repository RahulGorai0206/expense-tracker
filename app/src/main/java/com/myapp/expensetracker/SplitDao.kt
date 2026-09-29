package com.myapp.expensetracker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SplitDao {
    @Query("SELECT * FROM split_events ORDER BY updatedAt DESC")
    fun observeEvents(): Flow<List<SplitEvent>>

    @Query("SELECT * FROM split_events WHERE id = :eventId")
    fun observeEvent(eventId: Long): Flow<SplitEvent?>

    @Query("SELECT * FROM split_members WHERE eventId = :eventId ORDER BY createdAt ASC")
    fun observeMembers(eventId: Long): Flow<List<SplitMember>>

    @Query("SELECT * FROM split_expenses WHERE eventId = :eventId ORDER BY createdAt DESC")
    fun observeExpenses(eventId: Long): Flow<List<SplitExpense>>

    @Query(
        """
        SELECT split_shares.* FROM split_shares
        INNER JOIN split_expenses ON split_expenses.id = split_shares.splitExpenseId
        WHERE split_expenses.eventId = :eventId
        """
    )
    fun observeSharesForEvent(eventId: Long): Flow<List<SplitShare>>

    @Query("SELECT * FROM split_payments WHERE eventId = :eventId ORDER BY createdAt DESC")
    fun observePayments(eventId: Long): Flow<List<SplitPayment>>

    // ── One-shot reads (backup export) ──────────────────────────────────────

    @Query("SELECT * FROM split_events ORDER BY createdAt ASC")
    suspend fun getAllEvents(): List<SplitEvent>

    @Query("SELECT * FROM split_members WHERE eventId = :eventId ORDER BY createdAt ASC")
    suspend fun getMembersForEvent(eventId: Long): List<SplitMember>

    @Query("SELECT * FROM split_expenses WHERE eventId = :eventId ORDER BY createdAt ASC")
    suspend fun getExpensesForEvent(eventId: Long): List<SplitExpense>

    @Query("SELECT * FROM split_shares WHERE splitExpenseId = :expenseId")
    suspend fun getSharesForExpense(expenseId: Long): List<SplitShare>

    @Query("SELECT * FROM split_payments WHERE eventId = :eventId ORDER BY createdAt ASC")
    suspend fun getPaymentsForEvent(eventId: Long): List<SplitPayment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SplitEvent): Long

    @Update
    suspend fun updateEvent(event: SplitEvent)

    @Delete
    suspend fun deleteEvent(event: SplitEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: SplitMember): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: SplitExpense): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<SplitShare>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: SplitPayment): Long

    @Query("DELETE FROM split_expenses WHERE id = :expenseId")
    suspend fun deleteExpenseById(expenseId: Long)

    @Query("SELECT * FROM split_events WHERE id = :eventId")
    suspend fun getEvent(eventId: Long): SplitEvent?

    // ── App user ────────────────────────────────────────────────────────────

    /** Keeps the app user's name identical in every event when it changes. */
    @Query("UPDATE split_members SET displayName = :name WHERE isAppUser = 1")
    suspend fun renameAppUserMembers(name: String)

    /**
     * Makes [memberId] the app user for its event and clears the flag from
     * everyone else there, in one statement, so an event can never end up with
     * two "you"s.
     */
    @Query(
        "UPDATE split_members SET isAppUser = CASE WHEN id = :memberId THEN 1 ELSE 0 END " +
            "WHERE eventId = :eventId"
    )
    suspend fun markAppUser(eventId: Long, memberId: Long)

    @Query("UPDATE split_shares SET transactionId = :transactionId WHERE id = :shareId")
    suspend fun linkShareToTransaction(shareId: Long, transactionId: Int?)

    @Transaction
    suspend fun insertExpenseWithShares(expense: SplitExpense, shares: List<SplitShare>) {
        val expenseId = insertExpense(expense)
        insertShares(shares.map { it.copy(splitExpenseId = expenseId) })
        getEvent(expense.eventId)?.let { updateEvent(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    @Transaction
    suspend fun insertPaymentAndTouchEvent(payment: SplitPayment) {
        insertPayment(payment)
        getEvent(payment.eventId)?.let { updateEvent(it.copy(updatedAt = System.currentTimeMillis())) }
    }
}
