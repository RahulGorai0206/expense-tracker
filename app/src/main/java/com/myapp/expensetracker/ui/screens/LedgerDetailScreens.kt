package com.myapp.expensetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myapp.expensetracker.LoanWithRepayments
import com.myapp.expensetracker.PotContribution
import com.myapp.expensetracker.PotSummary
import com.myapp.expensetracker.viewmodel.LedgerViewModel
import com.myapp.expensetracker.viewmodel.PersonDetailState
import com.myapp.expensetracker.viewmodel.PotDetailState
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ledgerDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun formatLedgerDate(millis: Long): String = ledgerDateFormat.format(Date(millis))

/** One person: every loan, what has come back, and what is still out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(personId: Long, onBack: () -> Unit) {
    val viewModel: LedgerViewModel = koinViewModel()
    val state by remember(personId) { viewModel.personDetail(personId) }
        .collectAsState(initial = PersonDetailState())
    val pots by viewModel.pots.collectAsState()

    var showAddLoan by remember { mutableStateOf(false) }
    var repayingLoan by remember { mutableStateOf<LoanWithRepayments?>(null) }

    val person = state.person

    if (showAddLoan && person != null) {
        AddLoanSheet(
            personName = person.name,
            pots = pots,
            onDismiss = { showAddLoan = false },
            onAdd = { amount, reason, fromPotId ->
                viewModel.addLoan(
                    personId = personId,
                    amount = amount,
                    reason = reason,
                    fromPotId = fromPotId
                )
                showAddLoan = false
            }
        )
    }

    repayingLoan?.let { loan ->
        AddRepaymentSheet(
            outstanding = loan.outstanding,
            onDismiss = { repayingLoan = null },
            onAdd = { amount, note ->
                viewModel.addRepayment(loanId = loan.loan.id, amount = amount, note = note)
                repayingLoan = null
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(person?.name ?: "Person") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddLoan = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Lend") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PersonSummaryCard(state)
            }

            if (state.loans.isEmpty()) {
                item {
                    Text(
                        "No loans recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            items(state.loans, key = { it.loan.id }) { loan ->
                LoanCard(
                    loan = loan,
                    onRepay = { repayingLoan = loan },
                    onDelete = { viewModel.deleteLoan(loan.loan.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PersonSummaryCard(state: PersonDetailState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Still owed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                formatLedgerAmount(state.outstanding),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LedgerStat("Lent", formatLedgerAmount(state.totalLent))
                LedgerStat("Paid back", formatLedgerAmount(state.totalRepaid))
            }
        }
    }
}

@Composable
private fun LedgerStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun LoanCard(
    loan: LoanWithRepayments,
    onRepay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        loan.loan.reason.ifBlank { "No reason recorded" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        formatLedgerDate(loan.loan.lentAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatLedgerAmount(loan.loan.amount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        loanStatusLabel(loan),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loan.isSettled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            if (loan.repayments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                loan.repayments.forEach { repayment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                formatLedgerDate(repayment.receivedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (repayment.note.isNotBlank()) {
                                Text(
                                    repayment.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "+ ${formatLedgerAmount(repayment.amount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loan.isSettled) {
                    TextButton(onClick = onRepay) { Text("Record repayment") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun loanStatusLabel(loan: LoanWithRepayments): String = when {
    loan.outstanding < 0.0 -> "Overpaid by ${formatLedgerAmount(-loan.outstanding)}"
    loan.isSettled -> "Settled"
    loan.repaid > 0.0 -> "${formatLedgerAmount(loan.outstanding)} left"
    else -> "Unpaid"
}

/** One savings pot and every contribution made to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PotDetailScreen(potId: Long, onBack: () -> Unit) {
    val viewModel: LedgerViewModel = koinViewModel()
    val state by remember(potId) { viewModel.potDetail(potId) }
        .collectAsState(initial = PotDetailState())
    var showAddContribution by remember { mutableStateOf(false) }

    val summary = state.summary

    if (showAddContribution && summary != null) {
        AddContributionSheet(
            potName = summary.pot.name,
            onDismiss = { showAddContribution = false },
            onAdd = { amount, note ->
                viewModel.addContribution(potId = potId, amount = amount, note = note)
                showAddContribution = false
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(summary?.pot?.name ?: "Savings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddContribution = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            summary?.let { item { PotSummaryCard(it) } }

            if (state.contributions.isEmpty()) {
                item {
                    Text(
                        "Nothing added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            items(state.contributions, key = { it.id }) { contribution ->
                ContributionRow(
                    contribution = contribution,
                    onDelete = { viewModel.deleteContribution(contribution.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PotSummaryCard(summary: PotSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Total saved",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                formatLedgerAmount(summary.totalSaved),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (summary.lentOut > 0.0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    LedgerStat("Lent out", formatLedgerAmount(summary.lentOut))
                    LedgerStat("Available", formatLedgerAmount(summary.available))
                }
            }
        }
    }
}

@Composable
private fun ContributionRow(contribution: PotContribution, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatLedgerDate(contribution.addedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (contribution.note.isNotBlank()) {
                    Text(
                        contribution.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                formatLedgerAmount(contribution.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
