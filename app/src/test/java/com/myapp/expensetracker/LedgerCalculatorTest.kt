package com.myapp.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the ledgers exist to stop the user doing by hand: what is left
 * on a loan after part-payments, what one person still owes across several
 * loans, and what a savings pot really has available.
 */
class LedgerCalculatorTest {

    private val alice = Person(id = 1, name = "Alice")

    private fun loan(id: Long, amount: Double, personId: Long = 1, potId: Long? = null) =
        Loan(id = id, personId = personId, amount = amount, reason = "test", fromPotId = potId)

    private fun repayment(loanId: Long, amount: Double, at: Long = 0L) =
        LoanRepayment(loanId = loanId, amount = amount, receivedAt = at)

    // ── Single loan ─────────────────────────────────────────────────────────

    @Test
    fun `an untouched loan is fully outstanding`() {
        val result = LedgerCalculator.loanWithRepayments(loan(1, 10_000.0), emptyList())

        assertEquals(0.0, result.repaid, 0.001)
        assertEquals(10_000.0, result.outstanding, 0.001)
        assertFalse(result.isSettled)
    }

    @Test
    fun `part-payments reduce what is left`() {
        val result = LedgerCalculator.loanWithRepayments(
            loan(1, 10_000.0),
            listOf(repayment(1, 2_500.0), repayment(1, 1_500.0))
        )

        assertEquals(4_000.0, result.repaid, 0.001)
        assertEquals(6_000.0, result.outstanding, 0.001)
        assertFalse(result.isSettled)
    }

    @Test
    fun `paying the full amount settles the loan`() {
        val result = LedgerCalculator.loanWithRepayments(
            loan(1, 10_000.0),
            listOf(repayment(1, 10_000.0))
        )

        assertEquals(0.0, result.outstanding, 0.001)
        assertTrue(result.isSettled)
    }

    @Test
    fun `overpayment is reported rather than hidden`() {
        // Usually a repayment entered twice. Clamping to zero would leave the
        // user reconciling against their bank with no clue where the gap is.
        val result = LedgerCalculator.loanWithRepayments(
            loan(1, 5_000.0),
            listOf(repayment(1, 5_000.0), repayment(1, 500.0))
        )

        assertEquals(-500.0, result.outstanding, 0.001)
        assertTrue(result.isSettled)
    }

    @Test
    fun `repayments come back newest first`() {
        val result = LedgerCalculator.loanWithRepayments(
            loan(1, 900.0),
            listOf(repayment(1, 100.0, at = 10), repayment(1, 200.0, at = 30))
        )

        assertEquals(listOf(200.0, 100.0), result.repayments.map { it.amount })
    }

    @Test
    fun `many small repayments do not drift`() {
        // 0.1 + 0.2 != 0.3 in binary floating point; summing in paise keeps a
        // fully repaid loan reading as settled rather than 0.000000001 short.
        val repayments = (1..10).map { repayment(1, 0.1) }
        val result = LedgerCalculator.loanWithRepayments(loan(1, 1.0), repayments)

        assertEquals(1.0, result.repaid, 0.0)
        assertEquals(0.0, result.outstanding, 0.0)
        assertTrue(result.isSettled)
    }

    // ── Per person ──────────────────────────────────────────────────────────

    @Test
    fun `a person's balance spans every loan`() {
        val loans = listOf(loan(1, 10_000.0), loan(2, 5_000.0))
        val repayments = mapOf(
            1L to listOf(repayment(1, 10_000.0)),
            2L to listOf(repayment(2, 1_000.0))
        )

        val balance = LedgerCalculator.personBalance(alice, loans, repayments)

        assertEquals(15_000.0, balance.totalLent, 0.001)
        assertEquals(11_000.0, balance.totalRepaid, 0.001)
        assertEquals(4_000.0, balance.outstanding, 0.001)
        // Only the second loan is still open.
        assertEquals(1, balance.openLoanCount)
    }

    @Test
    fun `a person with everything paid back is settled`() {
        val balance = LedgerCalculator.personBalance(
            alice,
            listOf(loan(1, 2_000.0)),
            mapOf(1L to listOf(repayment(1, 2_000.0)))
        )

        assertEquals(0.0, balance.outstanding, 0.001)
        assertEquals(0, balance.openLoanCount)
    }

    @Test
    fun `a person with no loans has nothing outstanding`() {
        val balance = LedgerCalculator.personBalance(alice, emptyList(), emptyMap())

        assertEquals(0.0, balance.totalLent, 0.001)
        assertEquals(0.0, balance.outstanding, 0.001)
        assertEquals(0, balance.openLoanCount)
    }

    // ── Savings pots ────────────────────────────────────────────────────────

    @Test
    fun `contributions accumulate`() {
        val pot = SavingsPot(id = 1, name = "SIP")
        val contributions = (1..3).map {
            PotContribution(potId = 1, amount = 5_000.0)
        }

        val summary = LedgerCalculator.potSummary(pot, contributions)

        assertEquals(15_000.0, summary.totalSaved, 0.001)
        assertEquals(3, summary.contributionCount)
        assertEquals(0.0, summary.lentOut, 0.001)
        assertEquals(15_000.0, summary.available, 0.001)
    }

    @Test
    fun `money lent from a pot is not counted as available`() {
        val pot = SavingsPot(id = 1, name = "Emergency fund")
        val summary = LedgerCalculator.potSummary(
            pot = pot,
            contributions = listOf(PotContribution(potId = 1, amount = 100_000.0)),
            loansFromPot = listOf(loan(1, 20_000.0, potId = 1)),
            repaymentsByLoanId = emptyMap()
        )

        assertEquals(100_000.0, summary.totalSaved, 0.001)
        assertEquals(20_000.0, summary.lentOut, 0.001)
        assertEquals(80_000.0, summary.available, 0.001)
    }

    @Test
    fun `repaid money stops counting as lent out`() {
        val pot = SavingsPot(id = 1, name = "Emergency fund")
        val summary = LedgerCalculator.potSummary(
            pot = pot,
            contributions = listOf(PotContribution(potId = 1, amount = 100_000.0)),
            loansFromPot = listOf(loan(1, 20_000.0, potId = 1)),
            repaymentsByLoanId = mapOf(1L to listOf(repayment(1, 20_000.0)))
        )

        assertEquals(0.0, summary.lentOut, 0.001)
        assertEquals(100_000.0, summary.available, 0.001)
    }

    @Test
    fun `an overpaid loan never inflates a pot beyond what was saved`() {
        val pot = SavingsPot(id = 1, name = "Emergency fund")
        val summary = LedgerCalculator.potSummary(
            pot = pot,
            contributions = listOf(PotContribution(potId = 1, amount = 50_000.0)),
            loansFromPot = listOf(loan(1, 5_000.0, potId = 1)),
            repaymentsByLoanId = mapOf(1L to listOf(repayment(1, 6_000.0)))
        )

        // Per-loan overpayment is surfaced on the loan itself; a pot must not
        // read as holding more than was ever put into it.
        assertEquals(0.0, summary.lentOut, 0.001)
        assertEquals(50_000.0, summary.available, 0.001)
    }

    @Test
    fun `progress is null without a goal and capped with one`() {
        val noGoal = LedgerCalculator.potSummary(
            SavingsPot(id = 1, name = "Pot"),
            listOf(PotContribution(potId = 1, amount = 500.0))
        )
        assertEquals(null, noGoal.progress)

        val halfway = LedgerCalculator.potSummary(
            SavingsPot(id = 2, name = "Goal", targetAmount = 1_000.0),
            listOf(PotContribution(potId = 2, amount = 500.0))
        )
        assertEquals(0.5f, halfway.progress!!, 0.001f)

        val exceeded = LedgerCalculator.potSummary(
            SavingsPot(id = 3, name = "Goal", targetAmount = 1_000.0),
            listOf(PotContribution(potId = 3, amount = 4_000.0))
        )
        assertEquals(1.0f, exceeded.progress!!, 0.001f)
    }

    @Test
    fun `a zero target is treated as no goal rather than dividing by zero`() {
        val summary = LedgerCalculator.potSummary(
            SavingsPot(id = 1, name = "Pot", targetAmount = 0.0),
            listOf(PotContribution(potId = 1, amount = 100.0))
        )

        assertEquals(null, summary.progress)
    }

    // ── Headline totals ─────────────────────────────────────────────────────

    @Test
    fun `headline totals sum without drift`() {
        val balances = listOf(
            LedgerCalculator.personBalance(
                Person(id = 1, name = "A"),
                listOf(loan(1, 0.1)),
                emptyMap()
            ),
            LedgerCalculator.personBalance(
                Person(id = 2, name = "B"),
                listOf(loan(2, 0.2, personId = 2)),
                emptyMap()
            )
        )

        assertEquals(0.3, LedgerCalculator.totalOutstanding(balances), 0.0)
    }
}
