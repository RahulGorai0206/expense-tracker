package com.myapp.expensetracker.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.Transaction
import com.myapp.expensetracker.ui.components.rememberHaptics
import com.myapp.expensetracker.ui.components.EmptyState
import com.myapp.expensetracker.ui.components.TransactionListSkeleton
import com.myapp.expensetracker.ui.components.TransactionListItem
import com.myapp.expensetracker.viewmodel.TransactionViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SearchFilter(val label: String) {
    ALL("All"),
    TAG("Tag"),
    AMOUNT("Amount"),
    DATE("Date")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * @param isForeground true only while History is the visible page *and* no
 * detail screen is layered over it. The pager keeps neighbouring pages composed,
 * and this screen's back handler outranks MainActivity's, so without this gate
 * an open search here would swallow back presses meant for another tab or for
 * the transaction detail sitting on top.
 */
fun TransactionScreen(
    onTransactionClick: (Transaction) -> Unit,
    isForeground: Boolean = true,
    isCurrentPage: Boolean = true
) {
    val context = LocalContext.current
    val viewModel: TransactionViewModel = koinViewModel()
    val transactions by viewModel.transactions.collectAsState()
    val deletedSyncTransactions by viewModel.deletedSyncTransactions.collectAsState()
    val hasLoaded by viewModel.hasLoaded.collectAsState()

    val haptics = rememberHaptics()
    var selectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFilter by remember { mutableStateOf(SearchFilter.ALL) }

    val selectedTransactions = remember(transactions, selectedIds) {
        transactions.filter { it.id in selectedIds }
    }

    // One exit path for search, so the toolbar arrow and the system back gesture
    // can never leave it in different states.
    fun closeSearch() {
        isSearchActive = false
        searchQuery = ""
    }

    // Back unwinds the screen's own mode before leaving the tab: selection or
    // search first, and only then does MainActivity's handler take you Home.
    // Search and selection are mutually exclusive (see onLongClick below), so
    // at most one of these is ever open.
    BackHandler(enabled = isForeground && (selectionMode || isSearchActive)) {
        if (selectionMode) selectedIds = emptySet() else closeSearch()
    }

    // Leaving the tab drops the selection. The pager keeps this page composed,
    // so without this a selection survived a trip to another tab and was still
    // armed for "Delete" on return — the classic way to delete the wrong rows.
    // Search is deliberately kept: coming back to a half-finished search is useful.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) selectedIds = emptySet()
    }

    fun toggleSelection(transaction: Transaction) {
        // Entering multi-select is a mode change worth a firmer cue than the
        // individual picks that follow.
        if (selectedIds.isEmpty()) haptics.longPress() else haptics.tick()

        selectedIds = if (transaction.id in selectedIds) {
            selectedIds - transaction.id
        } else {
            selectedIds + transaction.id
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text("Delete ${selectedIds.size} transaction${if (selectedIds.size == 1) "" else "s"}?")
            },
            text = {
                Text("They will disappear from History now. If cloud sync is enabled, deletion will retry in the background until it succeeds.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.confirm()
                        val toDelete = selectedTransactions
                        viewModel.softDelete(context, toDelete) {
                            Toast.makeText(
                                context,
                                "${toDelete.size} transaction${if (toDelete.size == 1) "" else "s"} deleted",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        selectedIds = emptySet()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        AnimatedContent(
            targetState = isSearchActive,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 })
                    .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 2 })
            },
            label = "headerTransition"
        ) { searchActive ->
            if (searchActive) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { closeSearch() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search by ${searchFilter.label.lowercase()}...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        singleLine = true,
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                            MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = searchFilter == filter,
                                onClick = { searchFilter = filter },
                                label = { Text(filter.label) },
                                leadingIcon = if (searchFilter == filter) {
                                    {
                                        Icon(
                                            Icons.Default.Done,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                                        )
                                    }
                                } else null,
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .animateContentSize(
                                animationSpec = tween(
                                    durationMillis = 240,
                                    easing = FastOutSlowInEasing
                                )
                            )
                    ) {
                        AnimatedContent(
                            targetState = selectedIds.size,
                            transitionSpec = {
                                (fadeIn(tween(160)) + slideInVertically(
                                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                                ) { it / 3 }).togetherWith(
                                    fadeOut(tween(120)) + slideOutVertically(
                                        animationSpec = tween(180, easing = FastOutSlowInEasing)
                                    ) { -it / 3 }
                                ).using(SizeTransform(clip = false))
                            },
                            label = "historyTitle"
                        ) { selectedCount ->
                            Text(
                                if (selectedCount > 0) "$selectedCount selected" else "Ledger History",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Black
                            )
                        }
                        AnimatedContent(
                            targetState = selectionMode,
                            transitionSpec = {
                                fadeIn(tween(160)).togetherWith(fadeOut(tween(120)))
                            },
                            label = "historySubtitle"
                        ) { selecting ->
                            Text(
                                if (selecting) "Tap more transactions to add them." else "Tracking your financial journey.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = selectionMode,
                        enter = fadeIn(tween(160)) + expandHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.End
                        ),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.End
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Exit selection")
                            }
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                            }
                        }
                    }

                    if (!selectionMode) {
                        Surface(
                            onClick = { isSearchActive = true },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasLoaded) {
            TransactionListSkeleton(modifier = Modifier.weight(1f))
        } else if (transactions.isEmpty() && deletedSyncTransactions.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                title = "No Transactions Yet",
                description = "Your entire spending history will be listed here. Start by adding a transaction manually or letting the app track your SMS.",
                modifier = Modifier.weight(1f)
            )
        } else {
            val filteredTransactions = remember(transactions, searchQuery, searchFilter) {
                if (searchQuery.isBlank()) {
                    transactions
                } else {
                    val query = searchQuery.lowercase(Locale.getDefault())
                    val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
                    transactions.filter { tx ->
                        when (searchFilter) {
                            SearchFilter.ALL -> {
                                tx.category.lowercase().contains(query) ||
                                        tx.tag.lowercase().contains(query) ||
                                        tx.sender.lowercase().contains(query) ||
                                        tx.body.lowercase().contains(query) ||
                                        String.format(Locale.getDefault(), "%.2f", tx.amount)
                                            .contains(query)
                            }

                            SearchFilter.TAG -> tx.tag.lowercase().contains(query)
                            SearchFilter.AMOUNT -> String.format(
                                Locale.getDefault(),
                                "%.2f",
                                tx.amount
                            ).contains(query)

                            SearchFilter.DATE -> {
                                val dateStr = dateFormat.format(Date(tx.date)).lowercase()
                                dateStr.contains(query)
                            }
                        }
                    }
                }
            }

            val grouped = remember(filteredTransactions) {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance()
                val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())

                filteredTransactions.groupBy {
                    target.timeInMillis = it.date

                    if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
                    ) {
                        "TODAY"
                    } else if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                        now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR) == 1
                    ) {
                        "YESTERDAY"
                    } else {
                        dateFormat.format(Date(it.date)).uppercase()
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (deletedSyncTransactions.isNotEmpty()) {
                    item {
                        DeletedSyncStatusCard(
                            transactions = deletedSyncTransactions,
                            onRetry = {
                                viewModel.retryDeleteSync(context, deletedSyncTransactions)
                                Toast.makeText(
                                    context,
                                    "Retrying delete sync...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }

                grouped.forEach { (date, items) ->
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
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
                    items(items, key = { it.id }) { transaction ->
                        Box(modifier = Modifier.animateItem()) {
                            TransactionListItem(
                                transaction = transaction,
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelection(transaction)
                                    } else {
                                        onTransactionClick(transaction)
                                    }
                                },
                                selected = transaction.id in selectedIds,
                                selectionMode = selectionMode,
                                // Long-press starts selection only from plain
                                // browsing. null, not a no-op lambda: a non-null
                                // onLongClick makes combinedClickable fire its
                                // long-press haptic, which would feel like
                                // something happened when nothing did. The
                                // search button is already hidden during
                                // selection, so this closes the other direction.
                                onLongClick = if (!selectionMode && !isSearchActive) {
                                    { selectedIds = setOf(transaction.id) }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}

@Composable
private fun DeletedSyncStatusCard(
    transactions: List<Transaction>,
    onRetry: () -> Unit
) {
    val failedCount = transactions.count { it.syncStatus == "failed" }
    val pendingCount = transactions.count { it.syncStatus == "pending" }
    val isFailed = failedCount > 0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (isFailed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        },
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isFailed) Icons.Default.CloudOff else Icons.Default.Sync,
                contentDescription = null,
                tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isFailed) "Cloud delete needs retry" else "Cloud delete pending",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        failedCount > 0 && pendingCount > 0 -> "$failedCount failed, $pendingCount waiting"
                        failedCount > 0 -> "$failedCount transaction${if (failedCount == 1) "" else "s"} hidden locally but not deleted from cloud yet"
                        else -> "$pendingCount transaction${if (pendingCount == 1) "" else "s"} waiting for cloud confirmation"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isFailed) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
