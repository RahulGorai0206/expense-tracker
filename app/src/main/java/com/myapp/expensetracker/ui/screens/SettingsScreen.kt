package com.myapp.expensetracker.ui.screens

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.BackupResult
import com.myapp.expensetracker.BackupScope
import com.myapp.expensetracker.CloudSettingsBackupManager
import com.myapp.expensetracker.CrashReporter
import com.myapp.expensetracker.AppsScriptProvider
import com.myapp.expensetracker.ExtractionRulesRepository
import com.myapp.expensetracker.RemoteResource
import com.myapp.expensetracker.RemoteResourceLoader
import com.myapp.expensetracker.GoogleSheetsLogger
import com.myapp.expensetracker.LazySyncManager
import com.myapp.expensetracker.R
import com.myapp.expensetracker.SmsMonitorService
import androidx.core.app.NotificationManagerCompat
import android.content.ComponentName
import com.myapp.expensetracker.TransactionNotificationListener
import com.myapp.expensetracker.ExpenseWidgetReceiver
import com.myapp.expensetracker.PinnedWidgetReceiver
import com.myapp.expensetracker.worker.LazySyncWorker
import com.myapp.expensetracker.worker.UpdateCheckWorker
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.UUID
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.myapp.expensetracker.ui.components.BackupProgressDialog
import com.myapp.expensetracker.ui.components.rememberHaptics
import com.myapp.expensetracker.ui.components.canAuthenticate
import com.myapp.expensetracker.ui.components.isAppLockEnabled
import com.myapp.expensetracker.ui.components.setAppLockEnabled
import com.myapp.expensetracker.ui.components.BudgetEditSheet
import com.myapp.expensetracker.ui.components.PrivacyPolicyDialog
import com.myapp.expensetracker.ui.components.rememberBackupController
import com.myapp.expensetracker.viewmodel.HomeViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import com.myapp.expensetracker.AppUserStore

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 12.dp)) {
                trailing()
            }
        }
    }
}

@Composable
fun SettingsCategory(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun TagsEditorDialog(
    tags: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var draftTags by remember(tags) { mutableStateOf(tags) }
    var newTag by remember { mutableStateOf("") }

    fun normalized(values: Collection<String>): List<String> {
        return values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved Tags", fontWeight = FontWeight.Black) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("New tag") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                        shape = RoundedCornerShape(16.dp)
                    )
                    LargeFloatingActionButton(
                        onClick = {
                            draftTags = normalized(draftTags + newTag)
                            newTag = ""
                        },
                        modifier = Modifier.size(64.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add tag",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                if (draftTags.isEmpty()) {
                    Text(
                        "Create tags here, then apply them from manual entry or transaction details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    draftTags.forEach { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text(tag) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Label,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                            IconButton(
                                onClick = { draftTags = draftTags.filterNot { it == tag } }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $tag",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(normalized(draftTags + newTag))
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean, 
    onDarkThemeChange: (Boolean) -> Unit,
    followSystemTheme: Boolean,
    onFollowSystemThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HomeViewModel = koinViewModel()
    val budget by viewModel.currentBudget.collectAsState()
    val smartSuggestions by viewModel.smartSuggestions.collectAsState()

    val haptics = rememberHaptics()
    val appUser: AppUserStore = koinInject()
    val appUserName by appUser.name.collectAsState()
    var showNameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showBudgetEdit by remember { mutableStateOf(false) }
    var showTagsDialog by remember { mutableStateOf(false) }

    // ── Local file backup / restore ──────────────────────────────────────────
    var pendingExportScope by remember { mutableStateOf<BackupScope?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupFailed by remember { mutableStateOf(false) }

    var appLockEnabled by remember { mutableStateOf(isAppLockEnabled(context)) }

    // ── Remote-updatable rules & script ──────────────────────────────────────
    var rulesRefreshKey by remember { mutableIntStateOf(0) }
    var isUpdatingRules by remember { mutableStateOf(false) }
    var isUpdatingScript by remember { mutableStateOf(false) }
    var remoteResourceMessage by remember { mutableStateOf<String?>(null) }
    val activeRules = remember(rulesRefreshKey) { ExtractionRulesRepository.current() }
    val rulesAreCustom = remember(rulesRefreshKey) {
        RemoteResourceLoader.hasUpdate(context, RemoteResource.EXTRACTION_RULES)
    }
    val scriptIsUpdated = remember(rulesRefreshKey) { AppsScriptProvider.isUpdated(context) }

    remoteResourceMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { remoteResourceMessage = null },
            icon = { Icon(Icons.Default.FilterAlt, null) },
            title = { Text("Detection Rules", fontWeight = FontWeight.Black) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { remoteResourceMessage = null }) { Text("OK") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    // ── Crash diagnostics ────────────────────────────────────────────────────
    var crashRefreshKey by remember { mutableIntStateOf(0) }
    var showCrashDialog by remember { mutableStateOf(false) }
    val crashCount by remember(crashRefreshKey) {
        mutableIntStateOf(CrashReporter.crashCount(context))
    }

    if (showCrashDialog) {
        val report = remember(crashRefreshKey) { CrashReporter.readReport(context) }
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            icon = { Icon(Icons.Default.BugReport, null) },
            title = { Text("Crash Reports", fontWeight = FontWeight.Black) },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                    Text(
                        "Stored only on this device — nothing is uploaded. Share it if you want to report a bug.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Expense Tracker crash report")
                                putExtra(Intent.EXTRA_TEXT, report.take(100_000))
                            },
                            "Share crash report"
                        )
                    )
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashRefreshKey++
                        showCrashDialog = false
                    }) { Text("Clear") }
                    TextButton(onClick = { showCrashDialog = false }) { Text("Close") }
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    val backupController = rememberBackupController(scope) { result ->
        when (result) {
            is BackupResult.ExportSuccess -> {
                backupFailed = false
                backupMessage = buildString {
                    append("Saved ${result.transactions} transaction")
                    if (result.transactions != 1) append("s")
                    append(" and ${result.splitEvents} split event")
                    if (result.splitEvents != 1) append("s")
                    if (result.scope == BackupScope.FULL) append(", plus budgets and settings")
                    append(".")
                    if (result.scope == BackupScope.FULL) {
                        append("\n\nThis file contains your Google Sheets API key — keep it private.")
                    }
                }
            }

            // Importing is offered during setup only, so this screen never sees it.
            is BackupResult.ImportSuccess -> Unit

            is BackupResult.Failure -> {
                backupFailed = true
                backupMessage = result.message
            }
        }
    }

    BackupProgressDialog(backupController)

    pendingExportScope?.let { exportScope ->
        val isFull = exportScope == BackupScope.FULL
        AlertDialog(
            onDismissRequest = { pendingExportScope = null },
            icon = { Icon(if (isFull) Icons.Default.Backup else Icons.Default.FileDownload, null) },
            title = {
                Text(
                    if (isFull) "Export All App Data" else "Export Expense Data",
                    fontWeight = FontWeight.Black
                )
            },
            text = {
                Column {
                    Text(
                        if (isFull) {
                            "Creates a single file with your transaction history, splits, budget history and every app setting."
                        } else {
                            "Creates a single file with your transaction history and all split events."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isFull) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Includes your cloud sync API key in plain text. Store the file somewhere private.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingExportScope = null
                    backupController.startExport(exportScope)
                }) { Text("Choose Location") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExportScope = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            icon = {
                Icon(
                    if (backupFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    null,
                    tint = if (backupFailed) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
            },
            title = {
                Text(
                    if (backupFailed) "Something went wrong" else "Done",
                    fontWeight = FontWeight.Black
                )
            },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { backupMessage = null }) { Text("OK") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showNameDialog) {
        EditAppUserNameDialog(
            currentName = appUserName,
            onDismiss = { showNameDialog = false },
            onSave = { newName ->
                // Renames you in every split event too, so "you" reads the
                // same everywhere; backed up by the preference listener.
                scope.launch { appUser.setName(newName) }
                showNameDialog = false
            }
        )
    }

    if (showBudgetEdit) {
        BudgetEditSheet(
            currentBudget = budget,
            smartSuggestions = smartSuggestions,
            onSave = { amount ->
                viewModel.saveBudget(amount)
                com.myapp.expensetracker.enqueueWidgetUpdate(context)
                CloudSettingsBackupManager.backupAsync(context)
            },
            onDismiss = { showBudgetEdit = false }
        )
    }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        )
    }

    // Check if notification listener access is granted (needed for RCS detection)
    var isNotificationListenerEnabled by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        )
    }

    fun refreshStatuses() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isIgnoringBatteryOptimizations =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        isNotificationListenerEnabled =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
    }

    // Refresh state when coming back to this screen or app becomes resumed
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreProgress by remember { mutableIntStateOf(0) }
    var restoreTotal by remember { mutableIntStateOf(0) }
    var isRestoring by remember { mutableStateOf(false) }
    
    val sharedPrefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    
    var isMonthlyBudget by remember {
        mutableStateOf(
            sharedPrefs.getBoolean(
                "budget_monthly",
                true
            )
        )
    }
    
    var sheetUrl by remember { mutableStateOf(sharedPrefs.getString("sheet_url", "") ?: "") }
    var scriptUrl by remember { mutableStateOf(sharedPrefs.getString("script_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("api_key", "") ?: "") }
    var isCloudSaved by remember { mutableStateOf(scriptUrl.isNotBlank() && apiKey.isNotBlank()) }
    var isCloudEditing by remember { mutableStateOf(false) }
    var isCloudExpanded by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }

    val lazySyncManager = remember { LazySyncManager(context) }
    var isModelDownloaded by remember { mutableStateOf(lazySyncManager.isModelDownloaded()) }
    var isDownloadingModel by remember { mutableStateOf(false) }
    var modelDownloadProgress by remember { mutableStateOf("") }

    var showLazySyncDialog by remember { mutableStateOf(false) }
    var showLazySyncStartedDialog by remember { mutableStateOf(false) }
    var isLazySyncing by remember { mutableStateOf(false) }
    var lazySyncStatus by remember { mutableStateOf("") }
    val dateRangePickerState = rememberDateRangePickerState()
    
    var trackOnlyDebits by remember { mutableStateOf(sharedPrefs.getBoolean("track_only_debits", false)) }
    var ignoreCcBills by remember {
        mutableStateOf(
            sharedPrefs.getBoolean(
                "ignore_cc_bills",
                false
            )
        )
    }
    var backgroundMonitoring by remember { mutableStateOf(SmsMonitorService.isEnabled(context)) }
    var savedTags by remember { mutableStateOf(CloudSettingsBackupManager.getSavedTags(context)) }

    var updateAvailable by remember {
        mutableStateOf(
            sharedPrefs.getBoolean(
                "update_available",
                false
            )
        )
    }
    var latestVersion by remember {
        mutableStateOf(
            sharedPrefs.getString(
                "latest_version",
                "v${com.myapp.expensetracker.BuildConfig.VERSION_NAME}"
            ) ?: "v${com.myapp.expensetracker.BuildConfig.VERSION_NAME}"
        )
    }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    // Listen for update changes
    DisposableEffect(sharedPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "update_available") {
                    updateAvailable = prefs.getBoolean("update_available", false)
                }
                if (key == "latest_version") {
                    latestVersion = prefs.getString(
                        "latest_version",
                        "v${com.myapp.expensetracker.BuildConfig.VERSION_NAME}"
                    ) ?: "v${com.myapp.expensetracker.BuildConfig.VERSION_NAME}"
                }
            }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val extractedSheetId = remember(sheetUrl) {
        val pattern = "/spreadsheets/d/([a-zA-Z0-9-_]+)".toRegex()
        pattern.find(sheetUrl)?.groupValues?.get(1) ?: "YOUR_SHEET_ID_HERE"
    }

    val scriptCode = remember(extractedSheetId) {
        com.myapp.expensetracker.AppsScriptProvider.build(context, extractedSheetId)
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { if (!isRestoring) showRestoreDialog = false },
            title = { Text("Cloud Restore", fontWeight = FontWeight.Black) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (isRestoring) {
                        Text("Restoring transactions and settings from Google Sheets...")
                        Spacer(modifier = Modifier.height(16.dp))
                        if (restoreTotal > 0) {
                            val progressValue = restoreProgress.toFloat() / restoreTotal.toFloat()
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$restoreProgress / $restoreTotal records", style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Fetching records...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("This will replace local transactions and restore backed-up app settings from your Google Sheet. Continue?")
                    }
                }
            },
            confirmButton = {
                if (!isRestoring) {
                    Button(
                        onClick = {
                            isRestoring = true
                            restoreProgress = 0
                            restoreTotal = 0
                            scope.launch {
                                val error = GoogleSheetsLogger.syncFromCloud(context) { current, total ->
                                    restoreProgress = current
                                    restoreTotal = total
                                }
                                
                                if (error == null) {
                                    val restoredMonthlyBudget =
                                        sharedPrefs.getBoolean("budget_monthly", true)
                                    isMonthlyBudget = restoredMonthlyBudget

                                    val restoredTrackOnlyDebits =
                                        sharedPrefs.getBoolean("track_only_debits", false)
                                    trackOnlyDebits = restoredTrackOnlyDebits

                                    val restoredIgnoreCcBills =
                                        sharedPrefs.getBoolean("ignore_cc_bills", false)
                                    ignoreCcBills = restoredIgnoreCcBills
                                    
                                    backgroundMonitoring = SmsMonitorService.isEnabled(context)
                                    savedTags = CloudSettingsBackupManager.getSavedTags(context)

                                    onDarkThemeChange(sharedPrefs.getBoolean("dark_theme", true))
                                    onFollowSystemThemeChange(
                                        sharedPrefs.getBoolean(
                                            "follow_system_theme",
                                            true
                                        )
                                    )

                                    sheetUrl = sharedPrefs.getString("sheet_url", "") ?: ""
                                    scriptUrl = sharedPrefs.getString("script_url", "") ?: ""
                                    apiKey = sharedPrefs.getString("api_key", "") ?: ""
                                    isCloudSaved = scriptUrl.isNotBlank() && apiKey.isNotBlank()

                                    isRestoring = false
                                    showRestoreDialog = false
                                    Toast.makeText(
                                        context,
                                        "Cloud data and settings restored!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    isRestoring = false
                                    Toast.makeText(context, "Restore failed: $error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("Start Restore")
                    }
                }
            },
            dismissButton = {
                if (!isRestoring) {
                    TextButton(onClick = { showRestoreDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showLazySyncDialog) {
        Dialog(
            onDismissRequest = { if (!isLazySyncing) showLazySyncDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !isLazySyncing,
                dismissOnClickOutside = !isLazySyncing
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Lazy Sync (AI Powered)",
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isLazySyncing) {
                        Text(
                            lazySyncStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Text(
                            "Select a date range to scan for transaction SMS using Gemma AI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DateRangePicker(
                            state = dateRangePickerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp),
                            title = null,
                            headline = null,
                            showModeToggle = false
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!isLazySyncing) {
                            TextButton(onClick = { showLazySyncDialog = false }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                enabled = dateRangePickerState.selectedStartDateMillis != null &&
                                    dateRangePickerState.selectedEndDateMillis != null,
                                onClick = {
                                    // DateRangePicker returns UTC midnight; add 24h-1ms
                                    // so the entire end day is included in any timezone
                                    val endOfDay = dateRangePickerState.selectedEndDateMillis!! +
                                            (24 * 60 * 60 * 1000L - 1L)
                                    LazySyncWorker.start(
                                        context,
                                        dateRangePickerState.selectedStartDateMillis!!,
                                        endOfDay
                                    )
                                    showLazySyncDialog = false
                                    showLazySyncStartedDialog = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Start Lazy Sync")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLazySyncStartedDialog) {
        AlertDialog(
            onDismissRequest = { showLazySyncStartedDialog = false },
            title = { Text("Lazy Sync started", fontWeight = FontWeight.Black) },
            text = {
                Text("AI is scanning your messages in the background. You can close this window or minimize the app; progress will stay visible in the notification shade.")
            },
            confirmButton = {
                Button(
                    onClick = { showLazySyncStartedDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Okay")
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showDeleteDialog) {
        val db = AppDatabase.getDatabase(context)
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Wipe All Data", fontWeight = FontWeight.Black) },
            text = { Text("This will permanently remove all transaction history. This action cannot be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch {
                            db.transactionDao().deleteAllTransactions()
                            com.myapp.expensetracker.enqueueWidgetUpdate(context)
                            showDeleteDialog = false
                        }
                    }
                ) {
                    Text("Clear All")
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

    if (showPrivacyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyDialog = false })
    }

    if (showTagsDialog) {
        TagsEditorDialog(
            tags = savedTags,
            onDismiss = { showTagsDialog = false },
            onSave = { updatedTags ->
                savedTags = updatedTags
                CloudSettingsBackupManager.saveTags(context, updatedTags)
                CloudSettingsBackupManager.backupAsync(context)
                showTagsDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        // Replaces the two trailing spacer items; also clears the floating nav bar.
        contentPadding = PaddingValues(bottom = 132.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Text(
                "Manage your preferences and backup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // --- QUICK ACTIONS ---
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }

        // --- PROFILE ---
        item {
            SettingsCategory("PROFILE") {
                SettingsItem(
                    title = "Your name",
                    subtitle = appUserName.ifBlank { "Not set — you'll appear as this in splits" },
                    icon = Icons.Default.Person,
                    onClick = { showNameDialog = true },
                    trailing = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit name",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }

        // --- BUDGETING ---
        item {
            SettingsCategory("QUICK ACTIONS") {
                SettingsItem(
                    title = "Sync Now",
                    subtitle = "Update local and cloud ledger",
                    icon = Icons.Default.Sync,
                    onClick = {
                        if (isCloudSaved) {
                            scope.launch {
                                Toast.makeText(context, "Syncing with cloud...", Toast.LENGTH_SHORT).show()
                                GoogleSheetsLogger.syncFromCloud(context)
                                Toast.makeText(context, "Sync complete", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Cloud Sync not configured", Toast.LENGTH_SHORT)
                                .show()
                            isCloudExpanded = true
                        }
                    }
                )
            }
        }

        // --- TAGS ---
        item {
            SettingsCategory("BUDGETING") {
                SettingsItem(
                    title = "Monthly Target Budget",
                    subtitle = if (budget > 0) "\u20B9 ${"%,.0f".format(budget)}" else "Not set",
                    icon = Icons.Default.AccountBalanceWallet,
                    onClick = { showBudgetEdit = true },
                    trailing = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit Budget",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Current Month Only",
                    subtitle = "Track only this month's spending",
                    icon = Icons.Default.CalendarMonth,
                    trailing = {
                        Switch(checked = isMonthlyBudget, onCheckedChange = {
                            haptics.toggle(it)
                            isMonthlyBudget = it
                            sharedPrefs.edit().putBoolean("budget_monthly", it).apply()
                            com.myapp.expensetracker.enqueueWidgetUpdate(context)
                        })
                    }
                )
            }
        }

        // --- CLOUD & BACKUP ---
        item {
            SettingsCategory("TAGS") {
                SettingsItem(
                    title = "Saved Tags",
                    subtitle = if (savedTags.isEmpty()) "No tags saved" else savedTags.joinToString(", "),
                    icon = Icons.AutoMirrored.Filled.Label,
                    onClick = { showTagsDialog = true },
                    trailing = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit Tags",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }

        // --- BACKUP & RESTORE (local file) ---
        item {
            SettingsCategory("CLOUD & BACKUP") {
                SettingsItem(
                    title = "Google Sheets Sync",
                    subtitle = if (isCloudSaved) "Connected & Synchronized" else "Configure cloud backup",
                    icon = Icons.Default.CloudSync,
                    iconColor = if (isCloudSaved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    onClick = { isCloudExpanded = !isCloudExpanded },
                    trailing = {
                        val rotation by animateFloatAsState(if (isCloudExpanded) 180f else 0f)
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            null,
                            modifier = Modifier.graphicsLayer(rotationZ = rotation)
                        )
                    }
                )

                AnimatedVisibility(visible = isCloudExpanded) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        // Instructions Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                                    alpha = 0.3f
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Setup Guide",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                listOf(
                                    R.string.setup_step_1, R.string.setup_step_2, R.string.setup_step_3,
                                    R.string.setup_step_4, R.string.setup_step_5, R.string.setup_step_6,
                                    R.string.setup_step_7, R.string.setup_step_8, R.string.setup_step_9,
                                    R.string.setup_step_10, R.string.setup_step_11
                                ).forEach { stepRes ->
                                    Text(
                                        "• ${stringResource(stepRes)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "Apps Script",
                                                scriptCode
                                            )
                                        )
                                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT)
                                            .show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Copy Script Code")
                                }
                            }
                        }

                        OutlinedTextField(
                            value = sheetUrl,
                            onValueChange = { sheetUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCloudSaved || isCloudEditing,
                            label = { Text("Google Sheet URL") },
                            shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCloudSaved || isCloudEditing,
                            label = { Text("API Security Key") },
                            shape = RoundedCornerShape(16.dp),
                            readOnly = true,
                            trailingIcon = {
                                if (!isCloudSaved || isCloudEditing) {
                                    TextButton(onClick = {
                                        apiKey = UUID.randomUUID().toString().replace("-", "").take(32)
                                    }) { Text("Generate") }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = scriptUrl,
                            onValueChange = { scriptUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCloudSaved || isCloudEditing,
                            label = { Text("Web App URL") },
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (isCloudSaved && !isCloudEditing) {
                                Button(
                                    onClick = { showRestoreDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Restore")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { isCloudEditing = true }) { Text("Edit") }
                                TextButton(onClick = {
                                    isCloudSaved = false
                                    sheetUrl = ""; scriptUrl = ""; apiKey = ""
                                    sharedPrefs.edit().remove("sheet_url").remove("script_url")
                                        .remove("api_key").apply()
                                    GoogleSheetsLogger.updateUrl(""); GoogleSheetsLogger.updateApiKey("")
                                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                            } else {
                                if (isCloudEditing) {
                                    TextButton(onClick = {
                                        isCloudEditing = false
                                        sheetUrl = sharedPrefs.getString("sheet_url", "") ?: ""
                                        scriptUrl = sharedPrefs.getString("script_url", "") ?: ""
                                        apiKey = sharedPrefs.getString("api_key", "") ?: ""
                                    }) { Text("Cancel") }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(
                                    enabled = !isTestingConnection,
                                    onClick = {
                                        if (scriptUrl.isNotBlank() && apiKey.isNotBlank()) {
                                            scope.launch {
                                                isTestingConnection = true
                                                val error =
                                                    GoogleSheetsLogger.testConnection(scriptUrl, apiKey)
                                                if (error == null) {
                                                    sharedPrefs.edit().putString("sheet_url", sheetUrl)
                                                        .putString("script_url", scriptUrl)
                                                        .putString("api_key", apiKey).apply()
                                                    GoogleSheetsLogger.updateUrl(scriptUrl)
                                                    GoogleSheetsLogger.updateApiKey(apiKey)
                                                    val backupError =
                                                        GoogleSheetsLogger.backupSettings(context)
                                                    isCloudSaved = true; isCloudEditing =
                                                        false; isCloudExpanded = false
                                                    Toast.makeText(
                                                        context,
                                                        if (backupError == null) "Connected! Settings backup updated." else "Connected, but settings backup failed: $backupError",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    Toast.makeText(context, error, Toast.LENGTH_LONG)
                                                        .show()
                                                }
                                                isTestingConnection = false
                                            }
                                        }
                                    }
                                ) {
                                    if (isTestingConnection) CircularProgressIndicator(
                                        modifier = Modifier.size(
                                            18.dp
                                        ), strokeWidth = 2.dp
                                    )
                                    else Text(if (isCloudEditing) "Update" else "Connect")
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- AI & INTELLIGENCE ---
        item {
            SettingsCategory("BACKUP & RESTORE") {
                SettingsItem(
                    title = "Export Expense Data",
                    subtitle = "Transaction history and splits",
                    icon = Icons.Default.FileDownload,
                    onClick = { pendingExportScope = BackupScope.DATA }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Export All App Data",
                    subtitle = "Adds budgets and every setting",
                    icon = Icons.Default.Backup,
                    onClick = { pendingExportScope = BackupScope.FULL }
                )
            }
        }

        // --- AUTOMATED TRACKING ---
        item {
            SettingsCategory("AI & INTELLIGENCE") {
                SettingsItem(
                    title = "Lazy Sync (Historical)",
                    subtitle = "Scan SMS history using AI",
                    icon = Icons.Default.AutoFixHigh,
                    iconColor = if (isModelDownloaded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.5f
                    ),
                    onClick = {
                        if (isModelDownloaded) {
                            showLazySyncDialog = true
                        } else {
                            Toast.makeText(context, "Download AI model first", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsItem(
                    title = "Download AI Model",
                    subtitle = when {
                        isDownloadingModel -> modelDownloadProgress
                        isModelDownloaded -> "Gemma 2B Model • 1.2 GB (Downloaded)"
                        else -> "Download Gemma 2B (approx. 1.2 GB)"
                    },
                    icon = if (isModelDownloaded) Icons.Default.CheckCircle else Icons.Default.FileDownload,
                    iconColor = if (isModelDownloaded) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (!isModelDownloaded && !isDownloadingModel) {
                            isDownloadingModel = true
                            scope.launch {
                                val success = lazySyncManager.downloadModelOnly { progress ->
                                    modelDownloadProgress = progress
                                }
                                isModelDownloaded = lazySyncManager.isModelDownloaded()
                                isDownloadingModel = false
                                if (!success) {
                                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        }
                    }
                )

                if (isModelDownloaded && !isDownloadingModel) {
                    SettingsItem(
                        title = "Repair AI Model",
                        subtitle = "Delete and redownload model",
                        icon = Icons.Default.Build,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            isDownloadingModel = true
                            scope.launch {
                                val success = lazySyncManager.repairModel { progress ->
                                    modelDownloadProgress = progress
                                }
                                isModelDownloaded = lazySyncManager.isModelDownloaded()
                                isDownloadingModel = false
                                if (success) {
                                    Toast.makeText(context, "Repair complete", Toast.LENGTH_SHORT)
                                        .show()
                                } else {
                                    Toast.makeText(context, "Repair failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    SettingsItem(
                        title = "Delete AI Model",
                        subtitle = "Free up 1.2 GB of storage",
                        icon = Icons.Default.Delete,
                        iconColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            if (lazySyncManager.deleteModel()) {
                                isModelDownloaded = false
                                Toast.makeText(context, "AI Model deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        // --- HOME SCREEN WIDGET ---
        item {
            SettingsCategory("AUTOMATED TRACKING") {
                SettingsItem(
                    title = "Background Monitoring",
                    subtitle = if (backgroundMonitoring) "Active & Listening" else "Disabled",
                    icon = Icons.Default.RadioButtonChecked,
                    iconColor = if (backgroundMonitoring) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    trailing = {
                        Switch(checked = backgroundMonitoring, onCheckedChange = {
                            haptics.toggle(it)
                            backgroundMonitoring = it
                            SmsMonitorService.setEnabled(context, it)
                        })
                    }
                )

                if (backgroundMonitoring && !isIgnoringBatteryOptimizations) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    })
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(
                                alpha = 0.4f
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.WarningAmber,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Disable Battery Optimization for reliable tracking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (!isNotificationListenerEnabled) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Notifications, null, tint = Color(0xFFE65100))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Enable Notification Access for RCS/WhatsApp tracking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Track Only Debits",
                    subtitle = "Skip income/refund alerts",
                    icon = Icons.Default.Payment,
                    trailing = {
                        Switch(checked = trackOnlyDebits, onCheckedChange = {
                            haptics.toggle(it)
                            trackOnlyDebits = it
                            sharedPrefs.edit().putBoolean("track_only_debits", it).apply()
                        })
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Ignore CC Bills",
                    subtitle = "Skip credit card statements",
                    icon = Icons.Default.CreditCardOff,
                    trailing = {
                        Switch(checked = ignoreCcBills, onCheckedChange = {
                            haptics.toggle(it)
                            ignoreCcBills = it
                            sharedPrefs.edit().putBoolean("ignore_cc_bills", it).apply()
                        })
                    }
                )
            }
        }

        // --- INTERFACE ---
        item {
            SettingsCategory("HOME SCREEN WIDGET") {
                SettingsItem(
                    title = "Add Widget to Home Screen",
                    subtitle = "Pin the expense tracker to your home screen",
                    icon = Icons.Default.Widgets,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val appWidgetManager =
                                context.getSystemService(AppWidgetManager::class.java)
                            val myProvider = ComponentName(context, ExpenseWidgetReceiver::class.java)

                            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                                val successCallback = PendingIntent.getBroadcast(
                                    context,
                                    0,
                                    Intent(context, PinnedWidgetReceiver::class.java),
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )

                                val pinned = appWidgetManager.requestPinAppWidget(
                                    myProvider,
                                    null,
                                    successCallback
                                )
                                if (!pinned) {
                                    // Some launchers (OnePlus, Realme) silently reject.
                                    // Fallback: guide user to the manual widget picker.
                                    Toast.makeText(
                                        context,
                                        "Long-press your home screen → Widgets → Expense Tracker",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Long-press your home screen → Widgets → Expense Tracker",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Long-press your home screen → Widgets → Expense Tracker",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }
        }

        // --- UPDATES ---
        item {
            SettingsCategory("INTERFACE") {
                SettingsItem(
                    title = "Follow System Theme",
                    subtitle = "Match device dark/light mode",
                    icon = Icons.Default.SettingsSuggest,
                    trailing = {
                        Switch(
                            checked = followSystemTheme,
                            onCheckedChange = onFollowSystemThemeChange
                        )
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Dark Mode",
                    subtitle = "Premium dark theme",
                    icon = Icons.Default.NightsStay,
                    trailing = {
                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = onDarkThemeChange,
                            enabled = !followSystemTheme
                        )
                    }
                )
            }
        }

        // --- DETECTION RULES ---
        item {
            SettingsCategory("UPDATES") {
                SettingsItem(
                    title = "Check for Updates",
                    subtitle = if (updateAvailable) "New version $latestVersion available!" else "You are on the latest version",
                    icon = Icons.Default.Update,
                    iconColor = if (updateAvailable) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trailing = {
                        // Keep the IconButton mounted and swap only its content. A
                        // bare CircularProgressIndicator measures 24dp against the
                        // button's 48dp, which shrank the row and made the card jump.
                        IconButton(
                            enabled = !isCheckingUpdates,
                            onClick = {
                                isCheckingUpdates = true
                                UpdateCheckWorker.checkNow(context)
                                // We don't have a callback from WorkManager here easily,
                                // but the listener above will update the UI when prefs change.
                                // Let's add a small delay to show it's working
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    isCheckingUpdates = false
                                }
                            }
                        ) {
                            if (isCheckingUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val url = sharedPrefs.getString(
                            "latest_release_url",
                            "https://github.com/RahulGorai0206/expense-tracker/releases"
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = updateAvailable,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (updateAvailable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.38f
                        )
                    )
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Update")
                }
            }
        }

        // --- PRIVACY & SECURITY ---
        item {
            SettingsCategory("DETECTION RULES") {
                SettingsItem(
                    title = "SMS Filters & Extraction",
                    subtitle = "v${activeRules.version} · ${activeRules.releasedAt}" +
                            if (rulesAreCustom) " · updated" else " · bundled",
                    icon = Icons.Default.FilterAlt,
                    trailing = {
                        IconButton(
                            enabled = !isUpdatingRules,
                            onClick = {
                                isUpdatingRules = true
                                scope.launch {
                                    val outcome = ExtractionRulesRepository.update(context)
                                    isUpdatingRules = false
                                    rulesRefreshKey++
                                    remoteResourceMessage = when (outcome) {
                                        is ExtractionRulesRepository.UpdateOutcome.Updated ->
                                            "Filters updated from v${outcome.from} to v${outcome.to} (${outcome.releasedAt})."

                                        is ExtractionRulesRepository.UpdateOutcome.AlreadyCurrent ->
                                            "Already on the latest filters (v${outcome.version})."

                                        is ExtractionRulesRepository.UpdateOutcome.Failed ->
                                            "Filter update failed: ${outcome.reason}"
                                    }
                                }
                            }
                        ) {
                            if (isUpdatingRules) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Update filters")
                            }
                        }
                    }
                )

                if (rulesAreCustom) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "Revert to Bundled Filters",
                        subtitle = "Undo the downloaded update",
                        icon = Icons.Default.SettingsBackupRestore,
                        onClick = {
                            ExtractionRulesRepository.revert(context)
                            rulesRefreshKey++
                            remoteResourceMessage = "Reverted to the filters bundled with this build."
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "Apps Script",
                    subtitle = if (scriptIsUpdated) "Updated from repo" else "Bundled with this build",
                    icon = Icons.Default.Code,
                    trailing = {
                        // The IconButton stays mounted and only its content swaps —
                        // replacing it with a bare indicator changes the row height
                        // and makes the card twitch.
                        IconButton(
                            enabled = !isUpdatingScript,
                            onClick = {
                                isUpdatingScript = true
                                scope.launch {
                                    val outcome = AppsScriptProvider.update(context)
                                    isUpdatingScript = false
                                    rulesRefreshKey++
                                    remoteResourceMessage = when (outcome) {
                                        AppsScriptProvider.UpdateOutcome.Updated ->
                                            "Apps Script updated. Re-copy it into your spreadsheet and redeploy."

                                        AppsScriptProvider.UpdateOutcome.AlreadyCurrent ->
                                            "Apps Script is already up to date."

                                        is AppsScriptProvider.UpdateOutcome.Failed ->
                                            "Script update failed: ${outcome.reason}"
                                    }
                                }
                            }
                        ) {
                            if (isUpdatingScript) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Update Apps Script"
                                )
                            }
                        }
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = "View Source",
                    subtitle = "Read the rules and script on GitHub",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(RemoteResourceLoader.rawUrl(RemoteResource.EXTRACTION_RULES))
                            )
                        )
                    }
                )
            }
        }

        // --- DIAGNOSTICS ---
        item {
            SettingsCategory("PRIVACY & SECURITY") {
                val deviceCanAuthenticate = remember { canAuthenticate(context) }
                SettingsItem(
                    title = "App Lock",
                    subtitle = when {
                        !deviceCanAuthenticate -> "Set a screen lock on your device first"
                        appLockEnabled -> "Unlock required to open the app"
                        else -> "Protect your ledger with biometrics or PIN"
                    },
                    icon = if (appLockEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                    iconColor = if (appLockEnabled) {
                        Color(0xFF4CAF50)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    trailing = {
                        Switch(
                            checked = appLockEnabled,
                            enabled = deviceCanAuthenticate,
                            onCheckedChange = { enabled ->
                                haptics.toggle(enabled)
                                appLockEnabled = enabled
                                setAppLockEnabled(context, enabled)
                            }
                        )
                    }
                )
            }
        }

        // --- ABOUT ---
        item {
            SettingsCategory("DIAGNOSTICS") {
                SettingsItem(
                    title = "Crash Reports",
                    subtitle = if (crashCount > 0) {
                        "$crashCount crash${if (crashCount == 1) "" else "es"} recorded on this device"
                    } else {
                        "No crashes recorded"
                    },
                    icon = Icons.Default.BugReport,
                    iconColor = if (crashCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    containerColor = if (crashCount > 0) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    },
                    onClick = { if (crashCount > 0) showCrashDialog = true }
                )
            }
        }

        // --- DANGER ZONE ---
        item {
            SettingsCategory(stringResource(R.string.settings_category_about)) {
                SettingsItem(
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = stringResource(R.string.settings_privacy_policy_desc),
                    icon = Icons.Default.PrivacyTip,
                    onClick = { showPrivacyDialog = true }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    title = stringResource(R.string.settings_app_version),
                    subtitle = stringResource(
                        R.string.settings_app_version_desc,
                        com.myapp.expensetracker.BuildConfig.VERSION_NAME
                    ),
                    icon = Icons.Default.Info
                )
            }
        }

        item {
            SettingsCategory("DANGER ZONE") {
                SettingsItem(
                    title = "Clear All Transactions",
                    subtitle = "Reset local database to zero",
                    icon = Icons.Default.DeleteForever,
                    iconColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    onClick = { showDeleteDialog = true }
                )
            }
        }
    }
}

/** Edits the app user's name — the "you" in every split. */
@Composable
private fun EditAppUserNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name", fontWeight = FontWeight.Black) },
        text = {
            Column {
                Text(
                    "You appear under this name in every split. Changing it renames you in existing splits too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = {
            // A blank name would orphan "you" in existing splits, so it can't
            // be saved — only changed.
            Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp)
    )
}
