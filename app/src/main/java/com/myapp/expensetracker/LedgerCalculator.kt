package com.myapp.expensetracker

import kotlin.math.roundToLong

/**
 * Every running total in the lending and savings ledgers.
 *
 * Kept pure and free of Room so the arithmetic — the part the user would
 * otherwise be doing by hand, and the part that quietly goes wrong — can be
 * unit tested on its own.
 *
 * Sums are accumulated in paise rather than rupees: adding a few dozen `Double`
 * amounts drifts (0.1 + 0.2 is famously not 0.3), and an outstanding balance
 * that reads 19999.999999999996 makes a settled loan look unsettled. Integer
 * paise in, rounded rupees out. [SplitCalculator] does the same for the same
 * reason.
 */
object LedgerCalculator {

    private const val PAISE_PER_RUPEE = 100L

    private fun Double.toPaise(): Long = (this * PAISE_PER_RUPEE).roundToLong()

    private fun Long.toRupees(): Double = this / PAISE_PER_RUPEE.toDouble()

    private fun Iterable<Double>.sumAsPaise(): Long = sumOf { it.toPaise() }

    /**
     * One loan and what is left on it.
     *
     * Overpayment is reported as a negative [LoanWithRepayments.outstanding]
     * rather than clamped to zero: it nearly always means a repayment was
     * entered twice, and hiding it would leave the user hunting for the
     * discrepancy against their bank.
     */
    fun loanWithRepayments(
        loan: Loan,
        repayments: List<LoanRepayment>
    ): LoanWithRepayments {
        val repaidPaise = repayments.map { it.amount }.sumAsPaise()
        val outstandingPaise = loan.amount.toPaise() - repaidPaise
        return LoanWithRepayments(
            loan = loan,
            repayments = repayments.sortedByDescending { it.receivedAt },
            repaid = repaidPaise.toRupees(),
            outstanding = outstandingPaise.toRupees()
        )
    }

    /** Every loan for one person, with the person's net position across all of them. */
    fun personBalance(
        person: Person,
        loans: List<Loan>,
        repaymentsByLoanId: Map<Long, List<LoanRepayment>>
    ): PersonBalance {
        val lentPaise = loans.map { it.amount }.sumAsPaise()
        val repaidPaise = loans.sumOf { loan ->
            repaymentsByLoanId[loan.id].orEmpty().map { it.amount }.sumAsPaise()
        }
        val openLoans = loans.count { loan ->
            loan.amount.toPaise() > repaymentsByLoanId[loan.id].orEmpty()
                .map { it.amount }.sumAsPaise()
        }
        return PersonBalance(
            person = person,
            totalLent = lentPaise.toRupees(),
            totalRepaid = repaidPaise.toRupees(),
            outstanding = (lentPaise - repaidPaise).toRupees(),
            openLoanCount = openLoans
        )
    }

    /**
     * A pot's position. [loansFromPot] should already be filtered to loans whose
     * `fromPotId` is this pot; only the part still unpaid counts as lent out,
     * since repaid money is back in hand.
     */
    fun potSummary(
        pot: SavingsPot,
        contributions: List<PotContribution>,
        loansFromPot: List<Loan> = emptyList(),
        repaymentsByLoanId: Map<Long, List<LoanRepayment>> = emptyMap()
    ): PotSummary {
        val savedPaise = contributions.map { it.amount }.sumAsPaise()
        val lentOutPaise = loansFromPot.sumOf { loan ->
            val repaid = repaymentsByLoanId[loan.id].orEmpty().map { it.amount }.sumAsPaise()
            (loan.amount.toPaise() - repaid).coerceAtLeast(0L)
        }
        return PotSummary(
            pot = pot,
            totalSaved = savedPaise.toRupees(),
            lentOut = lentOutPaise.toRupees(),
            contributionCount = contributions.size
        )
    }

    /** Total still owed to the user across everyone, for an at-a-glance figure. */
    fun totalOutstanding(balances: List<PersonBalance>): Double =
        balances.map { it.outstanding }.sumAsPaise().toRupees()

    /** Total put aside across every pot. */
    fun totalSaved(summaries: List<PotSummary>): Double =
        summaries.map { it.totalSaved }.sumAsPaise().toRupees()
}
