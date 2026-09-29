package com.myapp.expensetracker

import android.content.Context
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Turns the app user's share of a split expense into a row in History.
 *
 * Only the share is booked, never the whole expense: of a ₹300 dinner split
 * three ways, the ₹120 that was yours is your spending; the rest belongs to the
 * others, whoever happened to pay the bill.
 */
object SplitShareBooking {

    /**
     * Builds the transaction without touching the database, so its shape can be
     * unit tested. Dated to the expense rather than to today, so booking an old
     * split lands it in the month it actually happened.
     */
    fun buildTransaction(
        eventName: String,
        expense: SplitExpense,
        share: SplitShare,
        category: String
    ): Transaction {
        val description = expense.description.ifBlank { "Shared expense" }
        // Rounded to the paisa: shares from percentage splits can carry float
        // noise (119.99999…) that would otherwise show up in History.
        val amount = (share.owedAmount * 100).roundToLong() / 100.0
        return Transaction(
            sender = eventName,
            amount = -amount,
            date = expense.createdAt,
            body = "Your share of \"$description\" in $eventName — " +
                "${rupees(amount)} of ${rupees(expense.amount)}",
            category = category,
            type = "manual",
            syncStatus = "pending"
        )
    }

    /**
     * Inserts the transaction, links it to the share, and syncs it the same way
     * a manually added transaction is synced.
     *
     * @return the new transaction's id.
     */
    suspend fun book(
        context: Context,
        eventName: String,
        expense: SplitExpense,
        share: SplitShare
    ): Int {
        val db = AppDatabase.getDatabase(context)
        // Categorised from the description with the same rules SMS use, so a
        // "Swiggy dinner" split lands under Dining rather than Other.
        val category = TransactionExtractor().categorize(expense.description.lowercase())
        val transaction = buildTransaction(eventName, expense, share, category)

        val localId = db.transactionDao().insertAndReturnId(transaction)
        db.splitDao().linkShareToTransaction(share.id, localId.toInt())

        enqueueWidgetUpdate(context)
        GoogleSheetsLogger.logAsync(context, transaction, localId)
        return localId.toInt()
    }

    private fun rupees(amount: Double): String =
        String.format(Locale.getDefault(), "₹%,.2f", amount)
}
