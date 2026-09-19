package com.myapp.expensetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myapp.expensetracker.PersonBalance
import com.myapp.expensetracker.PotSummary
import com.myapp.expensetracker.ui.components.EmptyState
import com.myapp.expensetracker.viewmodel.LedgerViewModel
import com.myapp.expensetracker.viewmodel.SplitViewModel
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/** Shared money formatter for the ledger screens. */
internal fun formatLedgerAmount(amount: Double): String =
    String.format(Locale.getDefault(), "₹%,.2f", amount)

/**
 * Every hand-kept record in the app: shared costs, money lent out, and money
 * set aside.
 *
 * These three are grouped because of where their data comes from, not because
 * they are about people — Home, History and Analytics are all views of the
 * automatic SMS stream, while everything here is typed in by the user. Splits
 * have never been linked to detected transactions, so they belong on this side
 * of that line too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgersScreen(
    onEventClick: (Long) -> Unit,
    onPersonClick: (Long) -> Unit,
    onPotClick: (Long) -> Unit
) {
    val viewModel: LedgerViewModel = koinViewModel()
    val splitViewModel: SplitViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateEvent by remember { mutableStateOf(false) }
    var showAddPerson by remember { mutableStateOf(false) }
    var showAddPot by remember { mutableStateOf(false) }

    if (showCreateEvent) {
        CreateSplitEventDialog(
            onDismiss = { showCreateEvent = false },
            onCreate = { name ->
                splitViewModel.createEvent(name) { eventId ->
                    showCreateEvent = false
                    onEventClick(eventId)
                }
            }
        )
    }

    if (showAddPerson) {
        AddPersonSheet(
            onDismiss = { showAddPerson = false },
            onAdd = { name, note ->
                viewModel.addPerson(name, note)
                showAddPerson = false
            }
        )
    }

    if (showAddPot) {
        AddPotSheet(
            onDismiss = { showAddPot = false },
            onAdd = { name, target, note ->
                viewModel.addPot(name, target, note)
                showAddPot = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Ledgers",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Shared costs, money lent out, and money set aside.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                LedgerTabs.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (LedgerTabs.entries[selectedTab]) {
                LedgerTabs.SPLIT -> SplitTabContent(
                    onEventClick = onEventClick,
                    onCreateEvent = { showCreateEvent = true }
                )

                LedgerTabs.LENDING -> LendingTab(
                    people = state.people,
                    totalOutstanding = state.totalOutstanding,
                    onPersonClick = onPersonClick
                )

                LedgerTabs.SAVINGS -> SavingsTab(
                    pots = state.pots,
                    totalSaved = state.totalSaved,
                    onPotClick = onPotClick
                )
            }
        }

        // One button, three meanings — the tab decides what "add" creates.
        LargeFloatingActionButton(
            onClick = {
                when (LedgerTabs.entries[selectedTab]) {
                    LedgerTabs.SPLIT -> showCreateEvent = true
                    LedgerTabs.LENDING -> showAddPerson = true
                    LedgerTabs.SAVINGS -> showAddPot = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 104.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = LedgerTabs.entries[selectedTab].addLabel,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/** The three hand-kept ledgers, in tab order. */
private enum class LedgerTabs(val label: String, val addLabel: String) {
    SPLIT("Split", "Create event"),
    LENDING("Lending", "Add person"),
    SAVINGS("Savings", "Add savings pot")
}

@Composable
private fun LendingTab(
    people: List<PersonBalance>,
    totalOutstanding: Double,
    onPersonClick: (Long) -> Unit
) {
    if (people.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Person,
            title = "No one owes you yet",
            description = "Add a person, then record what you lent them and why. " +
                "Repayments are subtracted for you."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LedgerHeadlineCard(
                label = "Still owed to you",
                amount = totalOutstanding,
                // Nothing outstanding reads as settled rather than as a warning.
                emphasise = totalOutstanding > 0.0
            )
        }
        items(people, key = { it.person.id }) { balance ->
            Box(modifier = Modifier.animateItem()) {
                PersonBalanceCard(
                    balance = balance,
                    onClick = { onPersonClick(balance.person.id) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(110.dp)) }
    }
}

@Composable
private fun SavingsTab(
    pots: List<PotSummary>,
    totalSaved: Double,
    onPotClick: (Long) -> Unit
) {
    if (pots.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Savings,
            title = "No savings pots yet",
            description = "Create a pot for your emergency fund or a SIP, then add " +
                "each contribution as it goes in."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LedgerHeadlineCard(label = "Total saved", amount = totalSaved, emphasise = true)
        }
        items(pots, key = { it.pot.id }) { summary ->
            Box(modifier = Modifier.animateItem()) {
                PotCard(summary = summary, onClick = { onPotClick(summary.pot.id) })
            }
        }
        item { Spacer(modifier = Modifier.height(110.dp)) }
    }
}

@Composable
private fun LedgerHeadlineCard(label: String, amount: Double, emphasise: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasise) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (emphasise) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                formatLedgerAmount(amount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (emphasise) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun PersonBalanceCard(balance: PersonBalance, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            balance.person.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column {
                    Text(
                        balance.person.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        personSubtitle(balance),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                formatLedgerAmount(balance.outstanding),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    balance.outstanding > 0.0 -> MaterialTheme.colorScheme.primary
                    // Overpaid: surfaced rather than hidden, it usually means a
                    // repayment was entered twice.
                    balance.outstanding < 0.0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun personSubtitle(balance: PersonBalance): String = when {
    balance.openLoanCount == 0 && balance.totalLent > 0.0 -> "All settled"
    balance.openLoanCount == 0 -> "No loans yet"
    balance.openLoanCount == 1 -> "1 open loan"
    else -> "${balance.openLoanCount} open loans"
}

@Composable
private fun PotCard(summary: PotSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        summary.pot.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (summary.contributionCount == 1) {
                            "1 contribution"
                        } else {
                            "${summary.contributionCount} contributions"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    formatLedgerAmount(summary.totalSaved),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            summary.progress?.let { progress ->
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Goal ${formatLedgerAmount(summary.pot.targetAmount ?: 0.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Only shown when money from this pot is actually out with someone,
            // so the headline figure is never quietly overstated.
            if (summary.lentOut > 0.0) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${formatLedgerAmount(summary.lentOut)} lent out",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${formatLedgerAmount(summary.available)} available",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
