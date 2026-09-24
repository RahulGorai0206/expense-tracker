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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.LoanRepayment
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
    var deletingLoan by remember { mutableStateOf<LoanWithRepayments?>(null) }
    var deletingRepayment by remember { mutableStateOf<LoanRepayment?>(null) }
    var confirmDeletePerson by remember { mutableStateOf(false) }

    val person = state.person

    if (confirmDeletePerson && person != null) {
        val loanCount = state.loans.size
        ConfirmDeleteDialog(
            title = "Delete ${person.name}?",
            message = if (loanCount == 0) {
                "They have no loans recorded, so nothing else is removed."
            } else {
                "This also removes $loanCount loan${if (loanCount == 1) "" else "s"} " +
                    "and every repayment recorded against them."
            },
            onDismiss = { confirmDeletePerson = false },
            onConfirm = {
                viewModel.deletePerson(person)
                onBack()
            }
        )
    }

    deletingLoan?.let { loan ->
        ConfirmDeleteDialog(
            title = "Delete this loan?",
            message = buildString {
                append("${formatLedgerAmount(loan.loan.amount)} lent")
                if (loan.loan.reason.isNotBlank()) append(" for ${loan.loan.reason}")
                append(".")
                if (loan.repayments.isNotEmpty()) {
                    append(
                        " Its ${loan.repayments.size} repayment" +
                            "${if (loan.repayments.size == 1) "" else "s"} go with it."
                    )
                }
            },
            onDismiss = { deletingLoan = null },
            onConfirm = { viewModel.deleteLoan(loan.loan.id) }
        )
    }

    deletingRepayment?.let { repayment ->
        ConfirmDeleteDialog(
            title = "Delete this repayment?",
            message = "${formatLedgerAmount(repayment.amount)} received on " +
                "${formatLedgerDate(repayment.receivedAt)}. The loan's outstanding " +
                "goes back up by that much.",
            onDismiss = { deletingRepayment = null },
            onConfirm = { viewModel.deleteRepayment(repayment.id) }
        )
    }

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
                title = {
                    Text(
                        person?.name ?: "Person",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (person != null) {
                        IconButton(onClick = { confirmDeletePerson = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete person",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
                    potName = loan.loan.fromPotId?.let { state.potNamesById[it] },
                    onRepay = { repayingLoan = loan },
                    onDelete = { deletingLoan = loan },
                    onDeleteRepayment = { deletingRepayment = it }
                )
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PersonSummaryCard(state: PersonDetailState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "STILL OWED",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                formatLedgerAmount(state.outstanding),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LedgerStat("LENT", formatLedgerAmount(state.totalLent))
                LedgerStat("PAID BACK", formatLedgerAmount(state.totalRepaid))
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
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun LoanCard(
    loan: LoanWithRepayments,
    potName: String?,
    onRepay: () -> Unit,
    onDelete: () -> Unit,
    onDeleteRepayment: (LoanRepayment) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // The 52dp rounded-square badge SplitEventCard established, so a
                // loan row reads as a sibling of the cards on the tabs above.
                LedgerCardBadge {
                    Icon(
                        Icons.AutoMirrored.Filled.CallMade,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        loan.loan.reason.ifBlank { "No reason recorded" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    Text(
                        formatLedgerDate(loan.loan.lentAt).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatLedgerAmount(loan.loan.amount),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        loanStatusLabel(loan).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = if (loan.isSettled) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    // Only when the loan was actually attributed to a pot —
                    // most aren't, and an empty "FROM —" line would be noise.
                    potName?.let { name ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "FROM ${name.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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
                        // Compact rather than a text button: a repayment entered
                        // twice is the most likely correction, so removing one
                        // has to be reachable without crowding the row.
                        IconButton(
                            onClick = { onDeleteRepayment(repayment) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Delete repayment",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loan.isSettled) {
                    TextButton(onClick = onRepay) {
                        Text("Record repayment", fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
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
    var deletingContribution by remember { mutableStateOf<PotContribution?>(null) }
    var confirmDeletePot by remember { mutableStateOf(false) }

    val summary = state.summary

    if (confirmDeletePot && summary != null) {
        val count = state.contributions.size
        ConfirmDeleteDialog(
            title = "Delete ${summary.pot.name}?",
            message = buildString {
                if (count == 0) {
                    append("This pot has no contributions yet.")
                } else {
                    append(
                        "This removes $count contribution${if (count == 1) "" else "s"} " +
                            "totalling ${formatLedgerAmount(summary.totalSaved)}."
                    )
                }
                // The loans themselves survive (fromPotId is ON DELETE SET NULL);
                // only the attribution is lost, and that is worth saying plainly.
                if (summary.lentOut > 0.0) {
                    append(
                        " Loans drawn from it are kept, but stop being linked to " +
                            "any pot."
                    )
                }
            },
            onDismiss = { confirmDeletePot = false },
            onConfirm = {
                viewModel.deletePot(summary.pot)
                onBack()
            }
        )
    }

    deletingContribution?.let { contribution ->
        ConfirmDeleteDialog(
            title = "Delete this contribution?",
            message = "${formatLedgerAmount(contribution.amount)} added on " +
                "${formatLedgerDate(contribution.addedAt)}.",
            onDismiss = { deletingContribution = null },
            onConfirm = { viewModel.deleteContribution(contribution.id) }
        )
    }

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
                title = {
                    Text(
                        summary?.pot?.name ?: "Savings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (summary != null) {
                        IconButton(onClick = { confirmDeletePot = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete savings pot",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
                    onDelete = { deletingContribution = contribution }
                )
            }

            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PotSummaryCard(summary: PotSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "TOTAL SAVED",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                formatLedgerAmount(summary.totalSaved),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (summary.lentOut > 0.0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    LedgerStat("LENT OUT", formatLedgerAmount(summary.lentOut))
                    LedgerStat("AVAILABLE", formatLedgerAmount(summary.available))
                }
            }
        }
    }
}

@Composable
private fun ContributionRow(contribution: PotContribution, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedgerCardBadge {
                Icon(
                    Icons.Default.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contribution.note.ifBlank { "Contribution" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatLedgerDate(contribution.addedAt).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Text(
                formatLedgerAmount(contribution.amount),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Icon rather than a "Delete" text button: with the badge added,
            // a word-wide button left the amount no room on a narrow screen.
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete contribution",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
