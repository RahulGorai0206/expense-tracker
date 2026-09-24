package com.myapp.expensetracker.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** The three hand-kept ledgers, in tab order. */
private enum class LedgerTabs(val label: String, val addLabel: String) {
    SPLIT("Split", "Create event"),
    LENDING("Lending", "Add person"),
    SAVINGS("Savings", "Add savings pot")
}

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

            Spacer(modifier = Modifier.height(16.dp))

            LedgerTabRow(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    // Slide toward the tab that was tapped, matching the
                    // direction of travel in the pill above.
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(tween(320)) { width ->
                        if (forward) width / 3 else -width / 3
                    } + fadeIn(tween(220))
                    val exit = slideOutHorizontally(tween(320)) { width ->
                        if (forward) -width / 3 else width / 3
                    } + fadeOut(tween(160))
                    // clip = false so a list doesn't get visibly cropped while
                    // the two tabs cross over each other.
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "ledgerTab"
            ) { tab ->
                when (LedgerTabs.entries[tab]) {
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

/**
 * Animated pill selector, matching the date-range chips on Analytics rather than
 * a stock TabRow — the underline indicator was the one piece of unstyled
 * Material in an app that uses rounded, colour-animated controls throughout.
 */
@Composable
private fun LedgerTabRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LedgerTabs.entries.forEachIndexed { index, tab ->
            val isSelected = selected == index
            val bgColor by animateColorAsState(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                label = "ledgerTabBg"
            )
            val textColor by animateColorAsState(
                if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                label = "ledgerTabText"
            )

            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(100.dp),
                color = bgColor,
                tonalElevation = if (isSelected) 0.dp else 2.dp
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Invisible, always ExtraBold: it fixes the pill's width at
                    // its widest state. Without it, selecting a tab switched its
                    // label from Medium to ExtraBold, which measures wider and
                    // shunted every pill to its right along the row.
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .alpha(0f)
                            // Measured only — never announced, or a screen
                            // reader would read every label twice.
                            .clearAndSetSemantics { }
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            }
        }
    }
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
                label = "STILL OWED TO YOU",
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
            LedgerHeadlineCard(label = "TOTAL SAVED", amount = totalSaved, emphasise = true)
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (emphasise) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = if (emphasise) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                formatLedgerAmount(amount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = if (emphasise) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/** Leading badge in the shape SplitEventCard established: 52dp rounded square. */
@Composable
internal fun LedgerCardBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun PersonBalanceCard(balance: PersonBalance, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedgerCardBadge {
                Text(
                    balance.person.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    balance.person.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    personSubtitle(balance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    letterSpacing = 1.sp
                )
            }
            Text(
                formatLedgerAmount(balance.outstanding),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Black,
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
    balance.openLoanCount == 0 && balance.totalLent > 0.0 -> "ALL SETTLED"
    balance.openLoanCount == 0 -> "NO LOANS YET"
    balance.openLoanCount == 1 -> "1 OPEN LOAN"
    else -> "${balance.openLoanCount} OPEN LOANS"
}

@Composable
private fun PotCard(summary: PotSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        summary.pot.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        if (summary.contributionCount == 1) {
                            "1 CONTRIBUTION"
                        } else {
                            "${summary.contributionCount} CONTRIBUTIONS"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    formatLedgerAmount(summary.totalSaved),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            summary.progress?.let { progress ->
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "GOAL ${formatLedgerAmount(summary.pot.targetAmount ?: 0.0)}",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Only shown when money from this pot is actually out with someone,
            // so the headline figure is never quietly overstated.
            if (summary.lentOut > 0.0) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LedgerMiniStat("LENT OUT", formatLedgerAmount(summary.lentOut))
                        LedgerMiniStat("AVAILABLE", formatLedgerAmount(summary.available))
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerMiniStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
