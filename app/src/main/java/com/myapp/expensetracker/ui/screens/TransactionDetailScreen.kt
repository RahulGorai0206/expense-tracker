package com.myapp.expensetracker.ui.screens

import android.widget.Toast
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.CloudSettingsBackupManager
import com.myapp.expensetracker.Transaction
import com.myapp.expensetracker.ui.components.getCategoryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class ShareOptions(
    val shareScreenshot: Boolean = true,
    val shareMerchant: Boolean = true,
    val shareDateTime: Boolean = true,
    val shareLocation: Boolean = true,
    val shareMessage: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(initialTransaction: Transaction, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Observe the database for the specific transaction to ensure UI updates instantly
    val transaction by AppDatabase.getDatabase(context).transactionDao()
        .getTransactionById(initialTransaction.id)
        .collectAsState(initial = initialTransaction)

    // Handle case where transaction is null (e.g., just deleted)
    if (transaction == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val currentTransaction = transaction!!
    var showEditDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showSendToLedger by remember { mutableStateOf(false) }

    /**
     * Soft-deletes this transaction, closes the screen, then syncs the deletion.
     * Shared by the delete button and by "Move" to a ledger, so a moved
     * transaction is removed from Google Sheets exactly as a deleted one is.
     */
    fun removeTransaction() {
        scope.launch {
            val toDelete = currentTransaction
            // 1. Mark as deleted locally (Instant UI update)
            AppDatabase.getDatabase(context).transactionDao()
                .softDelete(toDelete.id)
            com.myapp.expensetracker.enqueueWidgetUpdate(context)

            // 2. Close window immediately
            onBack()

            // 3. Trigger cloud sync in background (doesn't block UI)
            val deletedTransaction = toDelete.copy(
                status = "deleted",
                syncStatus = "pending"
            )
            if (com.myapp.expensetracker.GoogleSheetsLogger.isConfigured()) {
                com.myapp.expensetracker.GoogleSheetsLogger.logAsync(
                    context,
                    deletedTransaction,
                    deletedTransaction.id.toLong()
                )
            } else {
                AppDatabase.getDatabase(context).transactionDao().updateSyncStatus(
                    deletedTransaction.id,
                    deletedTransaction.remoteId,
                    "synced"
                )
            }
        }
    }

    if (showSendToLedger) {
        SendToLedgerFlow(
            transaction = currentTransaction,
            onDismiss = { showSendToLedger = false },
            onSent = { mode, destinationName ->
                showSendToLedger = false
                Toast.makeText(
                    context,
                    "${if (mode == LedgerSendMode.MOVE) "Moved" else "Copied"} to $destinationName",
                    Toast.LENGTH_SHORT
                ).show()
                if (mode == LedgerSendMode.MOVE) removeTransaction()
            }
        )
    }

    if (showEditDialog) {
        EditTransactionDialog(
            transaction = currentTransaction,
            onDismiss = { showEditDialog = false },
            onSave = { updatedTransaction ->
                scope.launch {
                    val toUpdate = updatedTransaction.copy(syncStatus = "pending")
                    AppDatabase.getDatabase(context).transactionDao().insert(toUpdate)
                    com.myapp.expensetracker.enqueueWidgetUpdate(context)
                    com.myapp.expensetracker.GoogleSheetsLogger.logAsync(
                        context,
                        toUpdate,
                        toUpdate.id.toLong()
                    )
                }
                showEditDialog = false
            }
        )
    }

    fun captureAndShare(options: ShareOptions) {
        scope.launch {
            val uri = if (options.shareScreenshot) {
                val bitmap = withContext(Dispatchers.Main) {
                    val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(b)
                    view.draw(canvas)
                    b
                }

                withContext(Dispatchers.IO) {
                    val imagesDir = File(context.cacheDir, "shared_images")
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    val file = File(imagesDir, "transaction_${currentTransaction.id}.png")
                    FileOutputStream(file).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    FileProvider.getUriForFile(
                        context,
                        "com.myapp.expensetracker.fileprovider",
                        file
                    )
                }
            } else null

            val locationInfo =
                if (options.shareLocation && currentTransaction.latitude != null && currentTransaction.longitude != null) {
                "\n|📍 *Location:* https://www.google.com/maps/search/?api=1&query=${currentTransaction.latitude},${currentTransaction.longitude}"
            } else ""

            val merchantInfo = if (options.shareMerchant) {
                "\n|🏢 *Merchant:* ${currentTransaction.sender}"
            } else ""

            val dateTimeInfo = if (options.shareDateTime) {
                val dateStr =
                    SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault()).format(
                        Date(currentTransaction.date)
                    )
                "\n|📅 *Date & Time:* $dateStr"
            } else ""

            val messageInfo = if (options.shareMessage) {
                "\n|\n|💬 *Message:*\n|${currentTransaction.body}"
            } else ""

            val shareText = """
                |💸 *Transaction Receipt*
                |━━━━━━━━━━━━━━━━━━$merchantInfo
                |💰 *Total Amount:* \u20B9${"%,.2f".format(abs(currentTransaction.amount))}$dateTimeInfo
                |━━━━━━━━━━━━━━━━━━$locationInfo$messageInfo
                |
                |_Shared via Expense Tracker_
            """.trimMargin()

            val intent = Intent(Intent.ACTION_SEND).apply {
                if (uri != null) {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                } else {
                    type = "text/plain"
                }
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Transaction"))
        }
    }

    if (showShareDialog) {
        ShareOptionsDialog(
            onDismiss = { showShareDialog = false },
            onShare = { options ->
                showShareDialog = false
                captureAndShare(options)
            },
            hasLocation = currentTransaction.latitude != null && currentTransaction.longitude != null
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Transaction Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
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
                    IconButton(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        onClick = { showShareDialog = true }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share transaction",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val categoryInfo = getCategoryInfo(currentTransaction.category)
            val icon = categoryInfo.icon
            val color = categoryInfo.color

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, modifier = Modifier.size(48.dp), tint = color)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                currentTransaction.sender.uppercase(), 
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )
            Text(
                "\u20B9${"%,.2f".format(currentTransaction.amount)}",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 52.sp),
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(currentTransaction.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(currentTransaction.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            DetailCard("TRANSACTION DATE", SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(currentTransaction.date)), Icons.Default.CalendarMonth)

            if (currentTransaction.tag.isNotBlank()) {
                DetailCard("TAG", currentTransaction.tag, Icons.AutoMirrored.Filled.Label)
            }

            val sourceLabel = when (currentTransaction.type) {
                "manual" -> "LOGGED BY USER"
                "AI" -> "AI ANALYZED SOURCE"
                else -> "MERCHANT SOURCE"
            }
            val sourceValue = currentTransaction.sender
            val sourceSub = when (currentTransaction.type) {
                "manual" -> "Manual entry via Dashboard"
                "AI" -> "Historical SMS analyzed by On-Device AI"
                else -> "Identified from incoming SMS"
            }
            
            DetailCard(sourceLabel, sourceValue, null, sourceSub)
            
            if (currentTransaction.type == "manual") {
                DetailCard("NOTES / BODY", currentTransaction.body, Icons.Default.Description)
            } else {
                DetailCard("ORIGINAL MESSAGE", "\"${currentTransaction.body}\"", null, null, false)
            }
            
            if (currentTransaction.latitude != null && currentTransaction.longitude != null) {
                DetailCard(
                    label = if (currentTransaction.type == "manual") "LOCATION LOGGED" else "LOCATION CAPTURED",
                    value = "${"%.4f".format(currentTransaction.latitude)}, ${"%.4f".format(currentTransaction.longitude)}",
                    icon = Icons.Default.LocationOn,
                    subValue = if (currentTransaction.type == "manual") "User location at time of logging" else "Precise coordinates at time of SMS"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        try {
                            val uri = "geo:${currentTransaction.latitude},${currentTransaction.longitude}?q=${currentTransaction.latitude},${currentTransaction.longitude}(Transaction Location)"
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri.toUri())
                            context.startActivity(mapIntent)
                        } catch (e: Exception) {
                            // Fallback to browser if no map app
                            val webUri = "https://www.google.com/maps/search/?api=1&query=${currentTransaction.latitude},${currentTransaction.longitude}"
                            val browserIntent = Intent(Intent.ACTION_VIEW, webUri.toUri())
                            context.startActivity(browserIntent)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Map, null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("View on Google Maps", fontWeight = FontWeight.Bold)
                }
            } else if (currentTransaction.type == "automated") {
                DetailCard(
                    label = "LOCATION",
                    value = "Not captured",
                    icon = Icons.Default.LocationOff,
                    subValue = "Enable 'Allow all the time' location permission in Settings."
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            // Zero-amount rows have no direction, so there's nothing to record.
            if (currentTransaction.amount != 0.0) {
                Button(
                    onClick = { showSendToLedger = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if (currentTransaction.amount < 0) {
                            "Move or copy to Lending / Split"
                        } else {
                            "Move or copy to Savings"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showEditDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Transaction")
                }
                
                IconButton(
                    onClick = { removeTransaction() },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete transaction",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DetailCard(label: String, value: String, icon: ImageVector? = null, subValue: String? = null, isCategory: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (subValue != null) Text(subValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                if (isCategory) {
                    Row {
                        Box(modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
                    }
                }
            }
        }
    }
}

@Composable
fun EditTransactionDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit
) {
    val isManual = transaction.type == "manual"
    var amountText by remember { mutableStateOf(abs(transaction.amount).toString()) }
    var senderText by remember { mutableStateOf(transaction.sender) }
    var bodyText by remember { mutableStateOf(transaction.body) }
    var category by remember { mutableStateOf(transaction.category) }
    var tagText by remember { mutableStateOf(transaction.tag) }
    var isDebit by remember { mutableStateOf(transaction.amount < 0) }
    val context = LocalContext.current

    val categories = listOf("Dining", "Shopping", "Transport", "Groceries", "Bills", "Other")
    val savedTags = remember {
        CloudSettingsBackupManager.getSavedTags(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Edit Transaction",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        if (it.isEmpty() || it.toDoubleOrNull() != null) amountText = it
                    },
                    label = { Text("Amount (\u20B9)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, null) }
                )

                if (isManual) {
                    OutlinedTextField(
                        value = senderText,
                        onValueChange = { senderText = it },
                        label = { Text("Merchant / Description") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Store, null) }
                    )

                    OutlinedTextField(
                        value = bodyText,
                        onValueChange = { bodyText = it },
                        label = { Text("Source Message / Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) }
                    )

                    Text("Type", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = isDebit,
                            onClick = { isDebit = true },
                            label = { Text("Debit") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                        FilterChip(
                            selected = !isDebit,
                            onClick = { isDebit = false },
                            label = { Text("Credit") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE8F5E9),
                                selectedLabelColor = Color(0xFF2E7D32)
                            )
                        )
                    }
                } else {
                    // Show read-only merchant for automated/AI
                    Text(
                        "Source: ${transaction.sender}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text("Category", style = MaterialTheme.typography.labelLarge)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it }) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                Text("Tag", style = MaterialTheme.typography.labelLarge)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Label,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (tagText.isBlank()) "No tag selected" else tagText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (tagText.isBlank()) FontWeight.Normal else FontWeight.Bold,
                            color = if (tagText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (savedTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = tagText.isBlank(),
                                onClick = { tagText = "" },
                                label = { Text("No tag") }
                            )
                        }
                        items(savedTags, key = { it }) { savedTag ->
                            FilterChip(
                                selected = tagText == savedTag,
                                onClick = { tagText = savedTag },
                                label = { Text(savedTag) }
                            )
                        }
                    }
                } else {
                    Text(
                        "No saved tags. Add tags in Settings to categorize your transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val rawAmount = amountText.toDoubleOrNull() ?: 0.0
                            val newAmount = Math.round(rawAmount * 100.0) / 100.0
                            val updated = transaction.copy(
                                amount = if (isDebit) -abs(newAmount) else abs(newAmount),
                                sender = if (isManual) senderText else transaction.sender,
                                body = if (isManual) bodyText else transaction.body,
                                category = category,
                                tag = tagText.trim()
                            )
                            onSave(updated)
                        },
                        enabled = amountText.isNotEmpty() && (if (isManual) senderText.isNotEmpty() else true),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
fun ShareOptionsDialog(
    onDismiss: () -> Unit,
    onShare: (ShareOptions) -> Unit,
    hasLocation: Boolean
) {
    var options by remember { mutableStateOf(ShareOptions()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Share Options",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Select what you'd like to include:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                ShareOptionItem("Screenshot", options.shareScreenshot) {
                    options = options.copy(shareScreenshot = it)
                }
                ShareOptionItem("Merchant", options.shareMerchant) {
                    options = options.copy(shareMerchant = it)
                }
                ShareOptionItem("Date & Time", options.shareDateTime) {
                    options = options.copy(shareDateTime = it)
                }
                if (hasLocation) {
                    ShareOptionItem("Location", options.shareLocation) {
                        options = options.copy(shareLocation = it)
                    }
                }
                ShareOptionItem("Message", options.shareMessage) {
                    options = options.copy(shareMessage = it)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onShare(options) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
fun ShareOptionItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
