package com.myapp.expensetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myapp.expensetracker.LedgerRepository
import com.myapp.expensetracker.Loan
import com.myapp.expensetracker.LoanWithRepayments
import com.myapp.expensetracker.Person
import com.myapp.expensetracker.PersonBalance
import com.myapp.expensetracker.PotContribution
import com.myapp.expensetracker.PotSummary
import com.myapp.expensetracker.SavingsPot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the two ledger tabs render at the top level. */
data class LedgerState(
    val people: List<PersonBalance> = emptyList(),
    val pots: List<PotSummary> = emptyList(),
    val totalOutstanding: Double = 0.0,
    val totalSaved: Double = 0.0,
    val isLoading: Boolean = true
)

/** One person's page: who they are, plus every loan with its repayments. */
data class PersonDetailState(
    val person: Person? = null,
    val loans: List<LoanWithRepayments> = emptyList(),
    val outstanding: Double = 0.0,
    val totalLent: Double = 0.0,
    val totalRepaid: Double = 0.0,
    /**
     * Pot names by id, so a loan can show which pot it was drawn from. Looked up
     * rather than stored on the loan: renaming a pot then has to update one row,
     * not every loan that ever came out of it.
     */
    val potNamesById: Map<Long, String> = emptyMap()
)

/** One pot's page: the pot, its running total, and its contribution history. */
data class PotDetailState(
    val summary: PotSummary? = null,
    val contributions: List<PotContribution> = emptyList()
)

class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {

    val state: StateFlow<LedgerState> = combine(
        repository.observePersonBalances(),
        repository.observePotSummaries()
    ) { people, pots ->
        LedgerState(
            people = people,
            pots = pots,
            totalOutstanding = people.sumOf { it.outstanding },
            totalSaved = pots.sumOf { it.totalSaved },
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerState())

    /** Pots for the "came out of" picker when recording a loan. */
    val pots: StateFlow<List<SavingsPot>> = repository.observePots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun personDetail(personId: Long): Flow<PersonDetailState> = combine(
        repository.observePerson(personId),
        repository.observeLoansForPerson(personId),
        repository.observePots()
    ) { person, loans, pots ->
        PersonDetailState(
            person = person,
            loans = loans,
            outstanding = loans.sumOf { it.outstanding },
            totalLent = loans.sumOf { it.loan.amount },
            totalRepaid = loans.sumOf { it.repaid },
            potNamesById = pots.associate { it.id to it.name }
        )
    }

    fun potDetail(potId: Long): Flow<PotDetailState> = combine(
        repository.observePotSummaries(),
        repository.observeContributionsForPot(potId)
    ) { summaries, contributions ->
        PotDetailState(
            summary = summaries.firstOrNull { it.pot.id == potId },
            contributions = contributions
        )
    }

    // ── Lending writes ──────────────────────────────────────────────────────

    fun addPerson(name: String, note: String = "") = viewModelScope.launch {
        repository.addPerson(name, note)
    }

    fun renamePerson(person: Person, name: String, note: String) = viewModelScope.launch {
        repository.renamePerson(person, name, note)
    }

    fun deletePerson(person: Person) = viewModelScope.launch {
        repository.deletePerson(person)
    }

    fun addLoan(
        personId: Long,
        amount: Double,
        reason: String,
        lentAt: Long = System.currentTimeMillis(),
        fromPotId: Long? = null
    ) = viewModelScope.launch {
        repository.addLoan(personId, amount, reason, lentAt, fromPotId)
    }

    fun updateLoan(loan: Loan) = viewModelScope.launch { repository.updateLoan(loan) }

    fun deleteLoan(loanId: Long) = viewModelScope.launch { repository.deleteLoan(loanId) }

    fun addRepayment(
        loanId: Long,
        amount: Double,
        receivedAt: Long = System.currentTimeMillis(),
        note: String = ""
    ) = viewModelScope.launch {
        repository.addRepayment(loanId, amount, receivedAt, note)
    }

    fun deleteRepayment(repaymentId: Long) = viewModelScope.launch {
        repository.deleteRepayment(repaymentId)
    }

    // ── Savings writes ──────────────────────────────────────────────────────

    fun addPot(name: String, targetAmount: Double?, note: String = "") = viewModelScope.launch {
        repository.addPot(name, targetAmount, note)
    }

    fun updatePot(pot: SavingsPot) = viewModelScope.launch { repository.updatePot(pot) }

    fun deletePot(pot: SavingsPot) = viewModelScope.launch { repository.deletePot(pot) }

    fun addContribution(
        potId: Long,
        amount: Double,
        addedAt: Long = System.currentTimeMillis(),
        note: String = ""
    ) = viewModelScope.launch {
        repository.addContribution(potId, amount, addedAt, note)
    }

    fun deleteContribution(contributionId: Long) = viewModelScope.launch {
        repository.deleteContribution(contributionId)
    }
}
