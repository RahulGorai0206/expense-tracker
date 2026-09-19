package com.myapp.expensetracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Joins the ledger tables into the derived views the UI renders, so a repayment
 * written anywhere reaches every balance that depends on it without a refresh.
 *
 * The arithmetic itself lives in [LedgerCalculator]; this class only assembles
 * the rows to hand it.
 */
class LedgerRepository(private val dao: LedgerDao) {

    // ── Lending ─────────────────────────────────────────────────────────────

    fun observePersonBalances(): Flow<List<PersonBalance>> =
        combine(
            dao.observePeople(),
            dao.observeLoans(),
            dao.observeRepayments()
        ) { people, loans, repayments ->
            val repaymentsByLoan = repayments.groupBy { it.loanId }
            val loansByPerson = loans.groupBy { it.personId }
            people.map { person ->
                LedgerCalculator.personBalance(
                    person = person,
                    loans = loansByPerson[person.id].orEmpty(),
                    repaymentsByLoanId = repaymentsByLoan
                )
            }
        }

    fun observePerson(personId: Long): Flow<Person?> = dao.observePerson(personId)

    /** Every loan for one person, newest first, each with its repayments folded in. */
    fun observeLoansForPerson(personId: Long): Flow<List<LoanWithRepayments>> =
        combine(
            dao.observeLoansForPerson(personId),
            dao.observeRepaymentsForPerson(personId)
        ) { loans, repayments ->
            val repaymentsByLoan = repayments.groupBy { it.loanId }
            loans.map { loan ->
                LedgerCalculator.loanWithRepayments(loan, repaymentsByLoan[loan.id].orEmpty())
            }
        }

    suspend fun addPerson(name: String, note: String = ""): Long =
        dao.insertPerson(Person(name = name.trim(), note = note.trim()))

    suspend fun renamePerson(person: Person, name: String, note: String) =
        dao.updatePerson(person.copy(name = name.trim(), note = note.trim()))

    suspend fun deletePerson(person: Person) = dao.deletePerson(person)

    suspend fun addLoan(
        personId: Long,
        amount: Double,
        reason: String,
        lentAt: Long,
        fromPotId: Long? = null
    ): Long = dao.insertLoan(
        Loan(
            personId = personId,
            amount = amount,
            reason = reason.trim(),
            lentAt = lentAt,
            fromPotId = fromPotId
        )
    )

    suspend fun updateLoan(loan: Loan) = dao.updateLoan(loan)

    suspend fun deleteLoan(loanId: Long) = dao.deleteLoanById(loanId)

    suspend fun addRepayment(
        loanId: Long,
        amount: Double,
        receivedAt: Long,
        note: String = ""
    ): Long = dao.insertRepayment(
        LoanRepayment(
            loanId = loanId,
            amount = amount,
            receivedAt = receivedAt,
            note = note.trim()
        )
    )

    suspend fun deleteRepayment(repaymentId: Long) = dao.deleteRepaymentById(repaymentId)

    // ── Savings ─────────────────────────────────────────────────────────────

    fun observePotSummaries(): Flow<List<PotSummary>> =
        combine(
            dao.observePots(),
            dao.observeContributions(),
            dao.observeLoans(),
            dao.observeRepayments()
        ) { pots, contributions, loans, repayments ->
            val repaymentsByLoan = repayments.groupBy { it.loanId }
            val contributionsByPot = contributions.groupBy { it.potId }
            val loansByPot = loans.mapNotNull { loan ->
                loan.fromPotId?.let { it to loan }
            }.groupBy({ it.first }, { it.second })

            pots.map { pot ->
                LedgerCalculator.potSummary(
                    pot = pot,
                    contributions = contributionsByPot[pot.id].orEmpty(),
                    loansFromPot = loansByPot[pot.id].orEmpty(),
                    repaymentsByLoanId = repaymentsByLoan
                )
            }
        }

    fun observePot(potId: Long): Flow<SavingsPot?> = dao.observePot(potId)

    fun observeContributionsForPot(potId: Long): Flow<List<PotContribution>> =
        dao.observeContributionsForPot(potId)

    /** Pots as plain rows, for the "which pot did this come from?" picker. */
    fun observePots(): Flow<List<SavingsPot>> = dao.observePots()

    suspend fun addPot(name: String, targetAmount: Double?, note: String = ""): Long =
        dao.insertPot(
            SavingsPot(
                name = name.trim(),
                targetAmount = targetAmount,
                note = note.trim()
            )
        )

    suspend fun updatePot(pot: SavingsPot) = dao.updatePot(pot)

    suspend fun deletePot(pot: SavingsPot) = dao.deletePot(pot)

    suspend fun addContribution(
        potId: Long,
        amount: Double,
        addedAt: Long,
        note: String = ""
    ): Long = dao.insertContribution(
        PotContribution(
            potId = potId,
            amount = amount,
            addedAt = addedAt,
            note = note.trim()
        )
    )

    suspend fun deleteContribution(contributionId: Long) =
        dao.deleteContributionById(contributionId)

    // ── Headline figures ────────────────────────────────────────────────────

    fun observeTotalOutstanding(): Flow<Double> =
        observePersonBalances().map { LedgerCalculator.totalOutstanding(it) }

    fun observeTotalSaved(): Flow<Double> =
        observePotSummaries().map { LedgerCalculator.totalSaved(it) }
}
