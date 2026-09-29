package com.myapp.expensetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myapp.expensetracker.MemberBalance
import com.myapp.expensetracker.Settlement
import com.myapp.expensetracker.SplitCalculator
import com.myapp.expensetracker.SplitEvent
import com.myapp.expensetracker.SplitExpense
import com.myapp.expensetracker.SplitMember
import com.myapp.expensetracker.SplitMode
import com.myapp.expensetracker.SplitPayment
import com.myapp.expensetracker.SplitRepository
import com.myapp.expensetracker.SplitShare
import com.myapp.expensetracker.SplitShareDraft
import com.myapp.expensetracker.SplitShareSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SplitEventState(
    val event: SplitEvent? = null,
    val members: List<SplitMember> = emptyList(),
    val expenses: List<SplitExpense> = emptyList(),
    val shares: List<SplitShare> = emptyList(),
    val payments: List<SplitPayment> = emptyList(),
    val balances: List<MemberBalance> = emptyList(),
    val settlements: List<Settlement> = emptyList()
)

class SplitViewModel(private val repository: SplitRepository) : ViewModel() {
    val events: StateFlow<List<SplitEvent>> = repository.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun eventState(eventId: Long): Flow<SplitEventState> {
        return combine(
            repository.observeEvent(eventId),
            repository.observeMembers(eventId),
            repository.observeExpenses(eventId),
            repository.observeShares(eventId),
            repository.observePayments(eventId)
        ) { event, members, expenses, shares, payments ->
            val balances = SplitCalculator.computeBalances(members, expenses, shares, payments)
            SplitEventState(
                event = event,
                members = members,
                expenses = expenses,
                shares = shares,
                payments = payments,
                balances = balances,
                settlements = SplitCalculator.simplifySettlements(balances)
            )
        }
    }

    fun createEvent(name: String, onCreated: (Long) -> Unit = {}) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            onCreated(repository.createEvent(cleanName))
        }
    }

    fun deleteEvent(event: SplitEvent, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteEvent(event)
            onDeleted()
        }
    }

    fun addMember(
        eventId: Long,
        displayName: String,
        lookupKey: String? = null,
        onAdded: (Long) -> Unit = {}
    ) {
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            onAdded(repository.addMember(eventId, cleanName, lookupKey))
        }
    }

    fun saveSplit(
        eventId: Long,
        amount: Double,
        description: String,
        paidByMemberId: Long,
        mode: SplitMode,
        shares: List<SplitShareDraft>,
        createdAt: Long = System.currentTimeMillis(),
        onSaved: () -> Unit = {}
    ) {
        if (amount <= 0.0 || shares.isEmpty() || !SplitCalculator.isBalanced(
                amount,
                shares,
                mode
            )
        ) return
        viewModelScope.launch {
            repository.saveSplit(
                eventId, amount, description, paidByMemberId, mode, shares, createdAt
            )
            onSaved()
        }
    }

    fun deleteSplit(expenseId: Long) {
        viewModelScope.launch {
            repository.deleteSplit(expenseId)
        }
    }

    fun markPaid(
        eventId: Long,
        fromMemberId: Long,
        toMemberId: Long,
        amount: Double,
        note: String = "",
        onSaved: () -> Unit = {}
    ) {
        if (amount <= 0.0) return
        viewModelScope.launch {
            repository.markPaid(eventId, fromMemberId, toMemberId, amount, note)
            onSaved()
        }
    }

    fun shareSummary(state: SplitEventState, memberId: Long): SplitShareSummary? {
        return SplitCalculator.buildShareSummary(
            eventName = state.event?.name.orEmpty(),
            memberId = memberId,
            balances = state.balances,
            settlements = state.settlements
        )
    }
}
