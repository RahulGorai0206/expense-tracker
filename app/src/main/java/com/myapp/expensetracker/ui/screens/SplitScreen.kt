package com.myapp.expensetracker.ui.screens

import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.myapp.expensetracker.MemberBalance
import com.myapp.expensetracker.Settlement
import com.myapp.expensetracker.SplitCalculator
import com.myapp.expensetracker.SplitEvent
import com.myapp.expensetracker.SplitExpense
import com.myapp.expensetracker.SplitMember
import com.myapp.expensetracker.SplitMode
import com.myapp.expensetracker.ui.components.rememberHaptics
import com.myapp.expensetracker.SplitShare
import com.myapp.expensetracker.SplitShareDraft
import com.myapp.expensetracker.SplitShareImageRenderer
import com.myapp.expensetracker.viewmodel.SplitEventState
import com.myapp.expensetracker.viewmodel.SplitViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The split event list, as one tab of [LedgersScreen].
 *
 * The screen heading, the floating action button and the create-event sheet all
 * live on the parent now, because the three ledger tabs share them — only the
 * list itself differs between tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitTabContent(
    onEventClick: (Long) -> Unit,
    onCreateEvent: () -> Unit
) {
    val viewModel: SplitViewModel = koinViewModel()
    val events by viewModel.events.collectAsState()

    if (events.isEmpty()) {
        SplitEmptyState(onCreate = onCreateEvent)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(events, key = { it.id }) { event ->
            Box(modifier = Modifier.animateItem()) {
                SplitEventCard(event = event, onClick = { onEventClick(event.id) })
            }
        }
        item { Spacer(modifier = Modifier.height(110.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitEventDetailScreen(eventId: Long, onBack: () -> Unit) {
    val viewModel: SplitViewModel = koinViewModel()
    val eventState by remember(eventId) { viewModel.eventState(eventId) }
        .collectAsState(initial = SplitEventState())
    val pagerState = rememberPagerState(pageCount = { 2 })
    val detailScope = rememberCoroutineScope()
    var showSplitDialog by remember { mutableStateOf(false) }
    var showDeleteEventDialog by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    var settlementToMarkPaid by remember { mutableStateOf<Settlement?>(null) }
    var hasLoadedEvent by remember(eventId) { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(eventState.event) {
        if (eventState.event != null) {
            hasLoadedEvent = true
        } else if (hasLoadedEvent) {
            onBack()
        }
    }

    if (showDeleteEventDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteEventDialog = false },
            title = { Text("Delete event?") },
            text = { Text("This removes the event, members, splits, and saved shares from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        eventState.event?.let { viewModel.deleteEvent(it, onBack) }
                        showDeleteEventDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteEventDialog = false
                }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showSplitDialog && eventState.event != null) {
        SplitCreateDialog(
            eventId = eventId,
            members = eventState.members,
            onDismiss = { showSplitDialog = false },
            onAddMember = { name, lookupKey -> viewModel.addMember(eventId, name, lookupKey) },
            onSave = { amount, description, payerId, mode, shares ->
                viewModel.saveSplit(eventId, amount, description, payerId, mode, shares) {
                    showSplitDialog = false
                }
            }
        )
    }

    settlementToMarkPaid?.let { settlement ->
        MarkPaidDialog(
            settlement = settlement,
            onDismiss = { settlementToMarkPaid = null },
            onSave = { amount ->
                viewModel.markPaid(
                    eventId = eventId,
                    fromMemberId = settlement.fromMemberId,
                    toMemberId = settlement.toMemberId,
                    amount = amount.coerceAtMost(settlement.amount),
                    note = "Settlement payment"
                ) {
                    settlementToMarkPaid = null
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        eventState.event?.name ?: "Split Event",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteEventDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete event",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { showSplitDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    Icons.Default.Payments,
                    contentDescription = "Add split",
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        detailScope.launch {
                            pagerState.animateScrollToPage(0, animationSpec = tween(320))
                        }
                    },
                    text = { Text("Splits") })
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        detailScope.launch {
                            pagerState.animateScrollToPage(1, animationSpec = tween(320))
                        }
                    },
                    text = { Text("Balances") })
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> SplitListTab(
                        state = eventState,
                        onDeleteSplit = viewModel::deleteSplit,
                        onCreate = { showSplitDialog = true }
                    )

                    1 -> BalancesTab(
                        state = eventState,
                        onMarkPaid = { haptics.confirm(); settlementToMarkPaid = it },
                        onShareMember = { memberId ->
                            viewModel.shareSummary(eventState, memberId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitEmptyState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "No split events yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Create an event for trips, dinners, rent, or anything shared.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onCreate, shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Event")
        }
    }
}

@Composable
private fun SplitEventCard(event: SplitEvent, onClick: () -> Unit) {
    val date = remember(event.updatedAt) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(event.updatedAt))
    }
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
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    "SPLIT EVENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                TinySplitChip(
                    label = "LOCAL",
                    color = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "OPEN",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateSplitEventDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(260)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Create Event",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Event name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null) }
            )

            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Create")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitListTab(
    state: SplitEventState,
    onDeleteSplit: (Long) -> Unit,
    onCreate: () -> Unit
) {
    var selectedExpense by remember { mutableStateOf<SplitExpense?>(null) }

    selectedExpense?.let { expense ->
        SplitExpenseDetailsSheet(
            expense = expense,
            members = state.members,
            shares = state.shares.filter { it.splitExpenseId == expense.id },
            onDismiss = { selectedExpense = null }
        )
    }

    if (state.expenses.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Payments,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "No splits yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCreate, shape = RoundedCornerShape(18.dp)) {
                Text("Split")
            }
        }
        return
    }

    val groupedExpenses = remember(state.expenses) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())

        state.expenses.groupBy { expense ->
            target.timeInMillis = expense.createdAt
            if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            ) {
                "TODAY"
            } else if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR) == 1
            ) {
                "YESTERDAY"
            } else {
                dateFormat.format(Date(expense.createdAt)).uppercase()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        groupedExpenses.forEach { (date, expenses) ->
            item(key = "date_$date") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        date,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
            items(expenses, key = { it.id }) { expense ->
                Box(modifier = Modifier.animateItem()) {
                    SplitExpenseListItem(
                        expense = expense,
                        members = state.members,
                        shares = state.shares.filter { it.splitExpenseId == expense.id },
                        onClick = { selectedExpense = expense },
                        onDelete = { onDeleteSplit(expense.id) }
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(96.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitExpenseDetailsSheet(
    expense: SplitExpense,
    members: List<SplitMember>,
    shares: List<SplitShare>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val payer = members.firstOrNull { it.id == expense.paidByMemberId }?.displayName ?: "Someone"
    val dateText = remember(expense.createdAt) {
        SimpleDateFormat(
            "MMMM dd, yyyy - hh:mm a",
            Locale.getDefault()
        ).format(Date(expense.createdAt))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                expense.description.ifBlank { "Shared expense" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            SplitDetailSummaryRow("TOTAL", rupee(expense.amount))
            SplitDetailSummaryRow("PAID BY", payer)
            SplitDetailSummaryRow("SPLIT MODE", SplitMode.fromDb(expense.splitMode).label)
            SplitDetailSummaryRow("DATE", dateText)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Text(
                "Member shares",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            shares.forEach { share ->
                val member =
                    members.firstOrNull { it.id == share.memberId }?.displayName ?: "Member"
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text(
                                "${"%.2f".format(share.percentage)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        }
                        Text(
                            rupee(share.owedAmount),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitDetailSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SplitExpenseListItem(
    expense: SplitExpense,
    members: List<SplitMember>,
    shares: List<SplitShare>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val payer = members.firstOrNull { it.id == expense.paidByMemberId }?.displayName ?: "Someone"
    SplitTransactionStyleRow(
        icon = Icons.Default.Payments,
        iconColor = MaterialTheme.colorScheme.secondary,
        title = expense.description.ifBlank { "Shared expense" },
        subtitle = "PAID BY ${payer.uppercase()}",
        chips = listOf(
            SplitRowChip(
                SplitMode.fromDb(expense.splitMode).label.uppercase(),
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
            ),
            SplitRowChip(
                "${shares.size} MEMBERS",
                MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            )
        ),
        amountText = rupee(expense.amount),
        amountColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
        action = {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete split",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

@Composable
private fun SettlementListItem(settlement: Settlement, onMarkPaid: () -> Unit) {
    SplitTransactionStyleRow(
        icon = Icons.Default.SwapHoriz,
        iconColor = MaterialTheme.colorScheme.tertiary,
        title = settlement.fromMemberName,
        subtitle = "OWES ${settlement.toMemberName.uppercase()}",
        chips = listOf(
            SplitRowChip(
                "SETTLEMENT",
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
            )
        ),
        amountText = rupee(settlement.amount),
        amountColor = MaterialTheme.colorScheme.onSurface,
        action = {
            TextButton(onClick = onMarkPaid) {
                Text("Mark paid")
            }
        }
    )
}

@Composable
private fun MemberBalanceListItem(balance: MemberBalance, onShare: () -> Unit) {
    val amountColor = when {
        balance.netAmount > 0.005 -> Color(0xFF4CAF50)
        balance.netAmount < -0.005 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = when {
        balance.netAmount > 0.005 -> "GETS BACK"
        balance.netAmount < -0.005 -> "OWES"
        else -> "SETTLED"
    }
    val amountText = when {
        balance.netAmount > 0.005 -> "+${rupee(balance.netAmount)}"
        balance.netAmount < -0.005 -> "-${rupee(abs(balance.netAmount))}"
        else -> rupee(0.0)
    }

    SplitTransactionStyleRow(
        icon = Icons.Default.Group,
        iconColor = amountColor,
        title = balance.memberName,
        subtitle = "MEMBER BALANCE",
        chips = listOf(SplitRowChip(label, amountColor, amountColor.copy(alpha = 0.12f))),
        amountText = amountText,
        amountColor = amountColor,
        action = {
            IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share ${balance.memberName}'s split",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

private data class SplitRowChip(
    val label: String,
    val color: Color,
    val containerColor: Color
)

@Composable
private fun SplitTransactionStyleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    chips: List<SplitRowChip>,
    amountText: String,
    amountColor: Color,
    onClick: (() -> Unit)? = null,
    action: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(26.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { chip ->
                        TinySplitChip(chip.label, chip.color, chip.containerColor)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    amountText,
                    fontWeight = FontWeight.Black,
                    color = amountColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp)
                )
                action()
            }
        }
    }
}

@Composable
private fun TinySplitChip(label: String, color: Color, containerColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun SplitExpenseCard(
    expense: SplitExpense,
    members: List<SplitMember>,
    shares: List<SplitShare>,
    onDelete: () -> Unit
) {
    val payer = members.firstOrNull { it.id == expense.paidByMemberId }?.displayName ?: "Someone"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        expense.description.ifBlank { "Shared expense" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Paid by $payer • ${SplitMode.fromDb(expense.splitMode).label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    rupee(expense.amount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete split",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.6f))
            shares.forEach { share ->
                val name = members.firstOrNull { it.id == share.memberId }?.displayName ?: "Member"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(rupee(share.owedAmount), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BalancesTab(
    state: SplitEventState,
    onMarkPaid: (Settlement) -> Unit,
    onShareMember: (Long) -> com.myapp.expensetracker.SplitShareSummary?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Settlements",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
        }
        if (state.settlements.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        "Everyone is settled up.",
                        modifier = Modifier.padding(18.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // Keyed so animateItem() can track a settlement across recomposition
            // as balances shift; a pair is unique within an event.
            items(
                state.settlements,
                key = { "${it.fromMemberId}-${it.toMemberId}" }
            ) { settlement ->
                Box(modifier = Modifier.animateItem()) {
                    SettlementListItem(settlement, onMarkPaid = { onMarkPaid(settlement) })
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Members",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
        }
        items(state.balances, key = { it.memberId }) { balance ->
            Box(modifier = Modifier.animateItem()) {
                MemberBalanceListItem(
                    balance = balance,
                    onShare = {
                        val summary =
                            onShareMember(balance.memberId) ?: return@MemberBalanceListItem
                        scope.launch {
                            val uri = SplitShareImageRenderer.renderToCache(context, summary)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "${summary.memberName}'s split for ${summary.eventName}"
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share split"))
                        }
                    }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(96.dp)) }
    }
}

@Composable
private fun SettlementRow(settlement: Settlement, onMarkPaid: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "${settlement.fromMemberName} owes ${settlement.toMemberName}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(rupee(settlement.amount), fontWeight = FontWeight.Black)
                TextButton(onClick = onMarkPaid) {
                    Text("Mark paid")
                }
            }
        }
    }
}

@Composable
private fun MarkPaidDialog(
    settlement: Settlement,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var amountText by remember(settlement) { mutableStateOf("%.2f".format(settlement.amount)) }
    val amount = amountText.toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as paid", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${settlement.fromMemberName} paid ${settlement.toMemberName}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = amount >= settlement.amount,
                        onClick = { amountText = "%.2f".format(settlement.amount) },
                        label = { Text("Fully") }
                    )
                    FilterChip(
                        selected = amount < settlement.amount,
                        onClick = {
                            if (amount >= settlement.amount) amountText = ""
                        },
                        label = { Text("Partially") }
                    )
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        if (it.isEmpty() || it.toDoubleOrNull() != null) amountText = it
                    },
                    label = { Text("Paid amount") },
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(amount) },
                enabled = amount > 0.0 && amount <= settlement.amount + 0.001
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun MemberBalanceCard(balance: MemberBalance, onShare: () -> Unit) {
    val label = when {
        balance.netAmount > 0.005 -> "Gets back ${rupee(balance.netAmount)}"
        balance.netAmount < -0.005 -> "Owes ${rupee(abs(balance.netAmount))}"
        else -> "Settled up"
    }
    val color = when {
        balance.netAmount > 0.005 -> Color(0xFF2E7D32)
        balance.netAmount < -0.005 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    balance.memberName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(label, color = color, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share ${balance.memberName}'s split",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitCreateDialog(
    eventId: Long,
    members: List<SplitMember>,
    onDismiss: () -> Unit,
    onAddMember: (String, String?) -> Unit,
    onSave: (Double, String, Long, SplitMode, List<SplitShareDraft>) -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var manualMember by remember { mutableStateOf("") }
    var selectedMemberIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var paidByMemberId by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf(SplitMode.EVEN) }
    var shares by remember { mutableStateOf<List<SplitShareDraft>>(emptyList()) }

    val selectedMembers = remember(members, selectedMemberIds) {
        members.filter { it.id in selectedMemberIds }
    }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    val contactLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            if (uri != null) {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.LOOKUP_KEY
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0).orEmpty()
                        val lookupKey = cursor.getString(1)
                        if (name.isNotBlank()) onAddMember(name, lookupKey)
                    }
                }
            }
        }

    LaunchedEffect(members) {
        val currentIds = members.map { it.id }.toSet()
        selectedMemberIds =
            if (selectedMemberIds.isEmpty()) currentIds else selectedMemberIds.intersect(currentIds)
        if (paidByMemberId !in currentIds) paidByMemberId = currentIds.firstOrNull()
    }

    LaunchedEffect(selectedMembers, amount, mode, paidByMemberId) {
        if (amount > 0 && selectedMembers.isNotEmpty()) {
            shares = SplitCalculator.equalShares(amount, selectedMembers, paidByMemberId)
        } else {
            shares = emptyList()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "New Split",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Step ${step + 1} of 4",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Delete, contentDescription = "Close")
                }
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(
                        tween(300, easing = FastOutSlowInEasing)
                    ) { direction * it / 3 })
                        .togetherWith(
                            fadeOut(tween(140)) + slideOutHorizontally(
                                tween(240, easing = FastOutSlowInEasing)
                            ) { -direction * it / 4 }
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "SplitCreateStep"
            ) { targetStep ->
                when (targetStep) {
                    0 -> SplitAmountStep(
                        amountText,
                        description,
                        { amountText = it },
                        { description = it })

                    1 -> SplitMembersStep(
                        members = members,
                        selectedIds = selectedMemberIds,
                        manualMember = manualMember,
                        onManualMemberChange = { manualMember = it },
                        onToggle = { memberId ->
                            selectedMemberIds =
                                if (memberId in selectedMemberIds) selectedMemberIds - memberId else selectedMemberIds + memberId
                        },
                        onAddManual = {
                            onAddMember(manualMember, null)
                            manualMember = ""
                        },
                        onPickContact = { contactLauncher.launch(null) }
                    )

                    2 -> PayerStep(
                        members = selectedMembers,
                        paidByMemberId = paidByMemberId,
                        onSelect = { paidByMemberId = it }
                    )

                    3 -> ShareStep(
                        amount = amount,
                        mode = mode,
                        shares = shares,
                        onModeChange = {
                            mode = it
                            shares = SplitCalculator.equalShares(
                                amount,
                                selectedMembers,
                                paidByMemberId
                            )
                        },
                        onAmountChange = { memberId, value ->
                            val updated = shares.map {
                                if (it.memberId == memberId) it.copy(
                                    owedAmount = value,
                                    edited = true
                                ) else it
                            }
                            shares = SplitCalculator.rebalanceAmounts(amount, updated)
                        },
                        onPercentageChange = { memberId, value ->
                            val updated = shares.map {
                                if (it.memberId == memberId) it.copy(
                                    percentage = value,
                                    edited = true
                                ) else it
                            }
                            shares = SplitCalculator.rebalancePercentages(amount, updated)
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (step == 0) onDismiss() else step-- },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (step == 0) "Cancel" else "Back")
                }
                Button(
                    onClick = {
                        if (step < 3) {
                            step++
                        } else {
                            onSave(
                                amount,
                                description,
                                paidByMemberId ?: selectedMembers.first().id,
                                mode,
                                shares
                            )
                        }
                    },
                    enabled = when (step) {
                        0 -> amount > 0
                        1 -> selectedMembers.size >= 2
                        2 -> paidByMemberId != null && selectedMembers.any { it.id == paidByMemberId }
                        else -> SplitCalculator.isBalanced(amount, shares, mode)
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(if (step == 3) "Save split" else "Next")
                }
            }
        }
    }
}

@Composable
private fun SplitAmountStep(
    amountText: String,
    description: String,
    onAmountChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) onAmountChange(it) },
            label = { Text("Amount") },
            leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SplitMembersStep(
    members: List<SplitMember>,
    selectedIds: Set<Long>,
    manualMember: String,
    onManualMemberChange: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onAddManual: () -> Unit,
    onPickContact: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualMember,
                onValueChange = onManualMemberChange,
                label = { Text("Member name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) }
            )
            IconButton(
                onClick = onAddManual,
                enabled = manualMember.isNotBlank(),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add member",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Button(
            onClick = onPickContact,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Contacts, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose from contacts")
        }

        if (members.isEmpty()) {
            Text("Add at least two members.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            members.forEach { member ->
                InputChip(
                    selected = member.id in selectedIds,
                    onClick = { onToggle(member.id) },
                    label = { Text(member.displayName) },
                    leadingIcon = if (member.id in selectedIds) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PayerStep(
    members: List<SplitMember>,
    paidByMemberId: Long?,
    onSelect: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Who paid?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        members.forEach { member ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(member.id) },
                shape = RoundedCornerShape(18.dp),
                color = if (paidByMemberId == member.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (paidByMemberId == member.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        member.displayName,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold
                    )
                    if (paidByMemberId == member.id) Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareStep(
    amount: Double,
    mode: SplitMode,
    shares: List<SplitShareDraft>,
    onModeChange: (SplitMode) -> Unit,
    onAmountChange: (Long, Double) -> Unit,
    onPercentageChange: (Long, Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SplitMode.entries.forEach { splitMode ->
                FilterChip(
                    selected = mode == splitMode,
                    onClick = { onModeChange(splitMode) },
                    label = { Text(splitMode.label) }
                )
            }
        }

        shares.forEach { share ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(share.memberName, fontWeight = FontWeight.Bold)
                    Text(
                        "${"%.2f".format(share.percentage)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (mode == SplitMode.EVEN) {
                    Text(rupee(share.owedAmount), fontWeight = FontWeight.Black)
                } else {
                    OutlinedTextField(
                        value = if (mode == SplitMode.AMOUNT) "%.2f".format(share.owedAmount) else "%.2f".format(
                            share.percentage
                        ),
                        onValueChange = {
                            val value = it.toDoubleOrNull() ?: return@OutlinedTextField
                            if (mode == SplitMode.AMOUNT) onAmountChange(
                                share.memberId,
                                value
                            ) else onPercentageChange(share.memberId, value)
                        },
                        modifier = Modifier.width(124.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        prefix = { Text(if (mode == SplitMode.AMOUNT) "\u20B9" else "") },
                        suffix = { if (mode == SplitMode.PERCENTAGE) Text("%") }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }

        val isBalanced = SplitCalculator.isBalanced(amount, shares, mode)
        Text(
            if (isBalanced) "Balanced at ${rupee(amount)}" else "Adjust shares until they match ${
                rupee(
                    amount
                )
            }.",
            color = if (isBalanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun rupee(amount: Double): String =
    String.format(Locale.getDefault(), "\u20B9%,.2f", amount)
