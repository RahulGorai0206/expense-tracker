package com.myapp.expensetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.Person
import com.myapp.expensetracker.SavingsPot
import com.myapp.expensetracker.SplitEvent
import com.myapp.expensetracker.Transaction
import com.myapp.expensetracker.viewmodel.LedgerViewModel
import com.myapp.expensetracker.viewmodel.SplitEventState
import com.myapp.expensetracker.viewmodel.SplitViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs

/** Whether the transaction stays in History after it has been recorded elsewhere. */
enum class LedgerSendMode(val verb: String) {
    COPY("Copy"),
    MOVE("Move")
}

/** Where a transaction can go. Debits become loans or split costs; credits, savings. */
private enum class LedgerDestination(val label: String) {
    LENDING("Lending"),
    SPLIT("Split"),
    SAVINGS("Savings pot")
}

/** The specific person, event or pot picked in the first step. */
private sealed interface SendTarget {
    val name: String

    /** Stable list key — ids are only unique within their own table. */
    val key: String

    data class ToPerson(val person: Person) : SendTarget {
        override val name get() = person.name
        override val key get() = "person-${person.id}"
    }

    data class ToEvent(val event: SplitEvent) : SendTarget {
        override val name get() = event.name
        override val key get() = "event-${event.id}"
    }

    data class ToPot(val pot: SavingsPot) : SendTarget {
        override val name get() = pot.name
        override val key get() = "pot-${pot.id}"
    }
}

/**
 * Records a detected transaction in one of the hand-kept ledgers.
 *
 * Two steps: pick copy or move, the destination and which person/event/pot,
 * then fill that ledger's usual form — prefilled with the transaction's amount
 * and dated to the transaction rather than to today. Backing out of the form
 * returns to the picker instead of abandoning the whole flow.
 *
 * Only existing people, events and pots are offered; new ones are created from
 * the Ledgers tab as usual.
 *
 * @param onSent called only once the ledger write has actually succeeded, with
 * the chosen mode and the destination's name. The caller owns the transaction,
 * so removing it for [LedgerSendMode.MOVE] is left to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToLedgerFlow(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSent: (mode: LedgerSendMode, destinationName: String) -> Unit
) {
    val ledgerViewModel: LedgerViewModel = koinViewModel()
    val splitViewModel: SplitViewModel = koinViewModel()
    val ledgerState by ledgerViewModel.state.collectAsState()
    val pots by ledgerViewModel.pots.collectAsState()
    val events by splitViewModel.events.collectAsState()
    val scope = rememberCoroutineScope()

    val isDebit = transaction.amount < 0
    val amount = abs(transaction.amount)
    val destinations = if (isDebit) {
        listOf(LedgerDestination.LENDING, LedgerDestination.SPLIT)
    } else {
        listOf(LedgerDestination.SAVINGS)
    }

    // Copy by default: it's the choice that can't lose anything if picked by
    // mistake. Move has to be chosen deliberately.
    var modeIndex by remember { mutableIntStateOf(0) }
    val mode = LedgerSendMode.entries[modeIndex]
    var destinationIndex by remember { mutableIntStateOf(0) }
    val destination = destinations[destinationIndex.coerceIn(destinations.indices)]
    var target by remember { mutableStateOf<SendTarget?>(null) }

    /**
     * Awaits the write before reporting success. For a move this is what keeps
     * the transaction from being deleted when the ledger row never landed.
     */
    fun finishAfter(job: Job, name: String) {
        scope.launch {
            job.join()
            if (!job.isCancelled) onSent(mode, name)
        }
    }

    val confirmLabel = target?.let { "${mode.verb} to ${it.name}" }.orEmpty()

    when (val picked = target) {
        null -> TargetPickerSheet(
            transaction = transaction,
            isDebit = isDebit,
            modeIndex = modeIndex,
            onModeChange = { modeIndex = it },
            destinations = destinations,
            destinationIndex = destinationIndex,
            onDestinationChange = { destinationIndex = it },
            destination = destination,
            people = ledgerState.people.map { it.person },
            events = events,
            pots = pots,
            onPick = { target = it },
            onDismiss = onDismiss
        )

        is SendTarget.ToPerson -> AddLoanSheet(
            personName = picked.person.name,
            pots = pots,
            onDismiss = { target = null },
            onAdd = { loanAmount, reason, fromPotId ->
                finishAfter(
                    ledgerViewModel.addLoan(
                        personId = picked.person.id,
                        amount = loanAmount,
                        reason = reason,
                        lentAt = transaction.date,
                        fromPotId = fromPotId
                    ),
                    picked.name
                )
            },
            initialAmount = amount,
            confirmLabel = confirmLabel
        )

        is SendTarget.ToEvent -> {
            // Members are per-event, so they're only loaded once one is picked.
            val eventState by remember(picked.event.id) {
                splitViewModel.eventState(picked.event.id)
            }.collectAsState(initial = SplitEventState())

            SplitCreateDialog(
                eventId = picked.event.id,
                members = eventState.members,
                onDismiss = { target = null },
                onAddMember = { name, lookupKey ->
                    splitViewModel.addMember(picked.event.id, name, lookupKey)
                },
                onSave = { splitAmount, description, payerId, splitMode, shares ->
                    // saveSplit only calls back once the insert has succeeded.
                    splitViewModel.saveSplit(
                        eventId = picked.event.id,
                        amount = splitAmount,
                        description = description,
                        paidByMemberId = payerId,
                        mode = splitMode,
                        shares = shares,
                        createdAt = transaction.date
                    ) { onSent(mode, picked.name) }
                },
                initialAmount = amount,
                saveLabel = confirmLabel
            )
        }

        is SendTarget.ToPot -> AddContributionSheet(
            potName = picked.pot.name,
            onDismiss = { target = null },
            onAdd = { contribution, note ->
                finishAfter(
                    ledgerViewModel.addContribution(
                        potId = picked.pot.id,
                        amount = contribution,
                        addedAt = transaction.date,
                        note = note
                    ),
                    picked.name
                )
            },
            initialAmount = amount,
            confirmLabel = confirmLabel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPickerSheet(
    transaction: Transaction,
    isDebit: Boolean,
    modeIndex: Int,
    onModeChange: (Int) -> Unit,
    destinations: List<LedgerDestination>,
    destinationIndex: Int,
    onDestinationChange: (Int) -> Unit,
    destination: LedgerDestination,
    people: List<Person>,
    events: List<SplitEvent>,
    pots: List<SavingsPot>,
    onPick: (SendTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "Send to Ledgers",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${formatLedgerAmount(abs(transaction.amount))} " +
                    if (isDebit) "debit" else "credit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("WHAT TO DO")
            LedgerPillRow(
                labels = LedgerSendMode.entries.map { it.verb },
                selected = modeIndex,
                onSelect = onModeChange
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (LedgerSendMode.entries[modeIndex] == LedgerSendMode.MOVE) {
                    "Removes it from History once it's saved."
                } else {
                    "Keeps it in History as well."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // A credit has only one destination, so there's no choice to show.
            if (destinations.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("WHERE")
                LedgerPillRow(
                    labels = destinations.map { it.label },
                    selected = destinationIndex,
                    onSelect = onDestinationChange
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel(
                when (destination) {
                    LedgerDestination.LENDING -> "WHO DID YOU LEND TO?"
                    LedgerDestination.SPLIT -> "WHICH EVENT?"
                    LedgerDestination.SAVINGS -> "WHICH POT?"
                }
            )

            val options: List<SendTarget> = when (destination) {
                LedgerDestination.LENDING -> people.map { SendTarget.ToPerson(it) }
                LedgerDestination.SPLIT -> events.map { SendTarget.ToEvent(it) }
                LedgerDestination.SAVINGS -> pots.map { SendTarget.ToPot(it) }
            }

            if (options.isEmpty()) {
                Text(
                    when (destination) {
                        LedgerDestination.LENDING -> "No people yet. Add one from Ledgers → Lending first."
                        LedgerDestination.SPLIT -> "No split events yet. Create one from Ledgers → Split first."
                        LedgerDestination.SAVINGS -> "No savings pots yet. Create one from Ledgers → Savings first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                // Bounded so a long list of people scrolls inside the sheet
                // instead of pushing the sheet taller than the screen.
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options, key = { it.key }) { option ->
                        TargetRow(option = option, onClick = { onPick(option) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TargetRow(option: SendTarget, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedgerCardBadge {
                when (option) {
                    is SendTarget.ToPerson -> Text(
                        option.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is SendTarget.ToEvent -> Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )

                    is SendTarget.ToPot -> Icon(
                        Icons.Default.Savings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                option.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
