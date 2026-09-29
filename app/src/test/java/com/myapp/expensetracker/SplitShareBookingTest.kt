package com.myapp.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transaction booked for the app user's share of a split expense. Only the
 * share is ever booked — of a ₹300 dinner split three ways, the ₹120 that was
 * yours is your spending; the rest belongs to the others.
 */
class SplitShareBookingTest {

    private val expense = SplitExpense(
        id = 7,
        eventId = 1,
        amount = 300.0,
        description = "Dinner",
        paidByMemberId = 2,
        splitMode = SplitMode.AMOUNT.dbValue,
        createdAt = 1_700_000_000_000L
    )

    private val myShare = SplitShare(
        id = 11,
        splitExpenseId = 7,
        memberId = 1,
        owedAmount = 120.0,
        percentage = 40.0
    )

    @Test
    fun `books only the share, as a debit`() {
        val tx = SplitShareBooking.buildTransaction("Goa Trip", expense, myShare, "Dining")

        assertEquals(-120.0, tx.amount, 0.0)
    }

    @Test
    fun `is dated to the expense, not to today`() {
        val tx = SplitShareBooking.buildTransaction("Goa Trip", expense, myShare, "Dining")

        assertEquals(expense.createdAt, tx.date)
    }

    @Test
    fun `reads as a manual transaction from the event`() {
        val tx = SplitShareBooking.buildTransaction("Goa Trip", expense, myShare, "Dining")

        assertEquals("Goa Trip", tx.sender)
        assertEquals("manual", tx.type)
        assertEquals("Dining", tx.category)
        assertEquals("pending", tx.syncStatus)
        assertTrue(tx.body.contains("Dinner"))
        assertTrue(tx.body.contains("Goa Trip"))
    }

    @Test
    fun `percentage float noise is rounded to the paisa`() {
        // A three-way percentage split can leave 99.99999999 as the share.
        val noisy = myShare.copy(owedAmount = 99.999999999)

        val tx = SplitShareBooking.buildTransaction("Goa Trip", expense, noisy, "Other")

        assertEquals(-100.0, tx.amount, 0.0)
    }

    @Test
    fun `a blank description still produces a readable body`() {
        val tx = SplitShareBooking.buildTransaction(
            "Goa Trip",
            expense.copy(description = ""),
            myShare,
            "Other"
        )

        assertTrue(tx.body.contains("Shared expense"))
    }
}
