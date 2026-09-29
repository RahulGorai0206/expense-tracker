package com.myapp.expensetracker.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.BackupResult
import com.myapp.expensetracker.GoogleSheetsLogger
import com.myapp.expensetracker.MonthlyBudget
import com.myapp.expensetracker.R
import com.myapp.expensetracker.SmsMonitorService
import com.myapp.expensetracker.ui.components.BackupProgressDialog
import com.myapp.expensetracker.ui.components.SetupCelebration
import com.myapp.expensetracker.ui.components.rememberBackupController
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import com.myapp.expensetracker.AppUserStore
import org.koin.compose.koinInject
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val appUser: AppUserStore = koinInject()

    // Seeded from any name already saved, e.g. by an abandoned earlier run.
    var userName by remember { mutableStateOf(appUser.name.value) }
    var showNameError by remember { mutableStateOf(false) }
    var budgetText by remember { mutableStateOf("") }
    var isCloudSyncEnabled by remember { mutableStateOf(false) }
    var sheetUrl by remember { mutableStateOf("") }
    var scriptUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }
    var isRestoring by remember { mutableStateOf(false) }

    // ── Restore from a local backup file (import step) ───────────────────────
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupFailed by remember { mutableStateOf(false) }
    var importedWithoutCloud by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(false) }

    /** True once a restored backup carries usable Google Sheets credentials. */
    fun hasRestoredCloudCredentials(): Boolean {
        val restoredScriptUrl = sharedPrefs.getString("script_url", "").orEmpty()
        val restoredApiKey = sharedPrefs.getString("api_key", "").orEmpty()
        return restoredScriptUrl.isNotBlank() && restoredApiKey.isNotBlank()
    }

    /**
     * Completes setup off the back of an import. The budget the user typed is
     * only used when the backup didn't bring one of its own — restored data
     * always wins over a value typed moments ago.
     */
    suspend fun finishFromImport() {
        val db = AppDatabase.getDatabase(context)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val restoredBudget = db.monthlyBudgetDao().getEffectiveBudget(monthKey)

        if (restoredBudget != null) {
            sharedPrefs.edit().putFloat("budget", restoredBudget.amount.toFloat()).apply()
        } else {
            val typedBudget = budgetText.toFloatOrNull() ?: 0f
            if (typedBudget > 0f) {
                db.monthlyBudgetDao().upsert(MonthlyBudget(monthKey, typedBudget.toDouble()))
                sharedPrefs.edit().putFloat("budget", typedBudget).apply()
            }
        }

        sharedPrefs.edit().putBoolean("is_setup_complete", true).apply()
        GoogleSheetsLogger.init(context)

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            SmsMonitorService.start(context)
        }
    }

    val backupController = rememberBackupController(scope) { result ->
        when (result) {
            is BackupResult.ImportSuccess -> {
                backupFailed = false
                // Gate on the file having actually carried settings, so stale
                // prefs from an abandoned setup run can't short-circuit this.
                if (result.summary.settingsApplied && hasRestoredCloudCredentials()) {
                    // Nothing left to configure — send them straight in.
                    scope.launch {
                        finishFromImport()
                        celebrate = true
                    }
                } else {
                    importedWithoutCloud = true
                    backupMessage = result.summary.toMessage() +
                            "\n\nThis backup didn't include cloud sync details, so let's set " +
                            "those up on the next screen."
                }
            }

            is BackupResult.Failure -> {
                backupFailed = true
                importedWithoutCloud = false
                backupMessage = result.message
            }

            is BackupResult.ExportSuccess -> Unit // not reachable during setup
        }
    }

    val totalSteps = SetupStep.COUNT

    // Permission States
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasNotificationAccess by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(false) }

    // Attempted States
    var attemptedSms by remember { mutableStateOf(false) }
    var attemptedNotifAccess by remember { mutableStateOf(false) }
    var attemptedLocation by remember { mutableStateOf(false) }
    var attemptedNotification by remember { mutableStateOf(false) }
    var attemptedBackground by remember { mutableStateOf(false) }

    fun checkPermissions() {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        hasNotificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    // Initial check
    LaunchedEffect(Unit) {
        checkPermissions()
    }

    // Re-check when returning to app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkPermissions() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkPermissions() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> checkPermissions() }

    BackHandler(enabled = currentStep > 0 && !celebrate) {
        currentStep--
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "SetupTransition",
                modifier = Modifier.weight(1f)
            ) { step ->
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep()
                    SetupStep.NAME -> NameStep(
                        value = userName,
                        isError = showNameError && userName.isBlank(),
                        onValueChange = {
                            userName = it
                            if (it.isNotBlank()) showNameError = false
                        }
                    )
                    SetupStep.PRIVACY -> PrivacyStep()
                    SetupStep.SMS -> PermissionStep(
                        icon = Icons.Default.Sms,
                        title = stringResource(R.string.setup_sms_title),
                        description = stringResource(R.string.setup_sms_description),
                        reasoning = stringResource(R.string.setup_sms_reasoning),
                        buttonText = if (hasSmsPermission) stringResource(R.string.setup_permission_granted) else if (attemptedSms) stringResource(
                            R.string.setup_permission_not_granted
                        ) else stringResource(R.string.setup_grant),
                        isGranted = hasSmsPermission,
                        attempted = attemptedSms,
                        onButtonClick = {
                            attemptedSms = true
                            smsPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.RECEIVE_SMS
                                )
                            )
                        }
                    )
                    SetupStep.NOTIFICATION_ACCESS -> PermissionStep(
                        icon = Icons.Default.MarkChatUnread,
                        title = stringResource(R.string.setup_notification_access_title),
                        description = stringResource(R.string.setup_notification_access_description),
                        reasoning = stringResource(R.string.setup_notification_access_reasoning),
                        buttonText = if (hasNotificationAccess) stringResource(R.string.setup_permission_granted) else if (attemptedNotifAccess) stringResource(
                            R.string.setup_permission_not_granted
                        ) else stringResource(R.string.setup_grant),
                        isGranted = hasNotificationAccess,
                        attempted = attemptedNotifAccess,
                        onButtonClick = {
                            attemptedNotifAccess = true
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    SetupStep.LOCATION -> PermissionStep(
                        icon = Icons.Default.LocationOn,
                        title = stringResource(R.string.setup_location_title),
                        description = stringResource(R.string.setup_location_description),
                        reasoning = stringResource(R.string.setup_location_reasoning),
                        buttonText = if (hasLocationPermission) stringResource(R.string.setup_permission_granted) else if (attemptedLocation) stringResource(
                            R.string.setup_permission_not_granted
                        ) else stringResource(R.string.setup_grant),
                        isGranted = hasLocationPermission,
                        attempted = attemptedLocation,
                        onButtonClick = {
                            attemptedLocation = true
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    )

                    SetupStep.NOTIFICATIONS -> PermissionStep(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.setup_notification_title),
                        description = stringResource(R.string.setup_notification_description),
                        reasoning = stringResource(R.string.setup_notification_reasoning),
                        buttonText = if (hasNotificationPermission) stringResource(R.string.setup_permission_granted) else if (attemptedNotification) stringResource(
                            R.string.setup_permission_not_granted
                        ) else stringResource(R.string.setup_grant),
                        isGranted = hasNotificationPermission,
                        attempted = attemptedNotification,
                        onButtonClick = {
                            attemptedNotification = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )

                    SetupStep.BACKGROUND -> PermissionStep(
                        icon = Icons.Default.RunningWithErrors,
                        title = stringResource(R.string.setup_background_title),
                        description = stringResource(R.string.setup_background_description),
                        reasoning = stringResource(R.string.setup_background_reasoning),
                        buttonText = if (isIgnoringBatteryOptimizations) stringResource(R.string.setup_permission_granted) else if (attemptedBackground) stringResource(
                            R.string.setup_permission_not_granted
                        ) else stringResource(R.string.setup_open_settings),
                        isGranted = isIgnoringBatteryOptimizations,
                        attempted = attemptedBackground,
                        onButtonClick = {
                            attemptedBackground = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (!isIgnoringBatteryOptimizations) {
                                    val intent =
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                    context.startActivity(intent)
                                } else {
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        }
                    )

                    SetupStep.BUDGET -> BudgetStep(
                        value = budgetText,
                        isError = showError && budgetText.isEmpty(),
                        onValueChange = { 
                            budgetText = it
                            if (it.isNotEmpty()) showError = false
                        }
                    )

                    SetupStep.IMPORT -> ImportStep(
                        onChooseFile = { backupController.startImport() }
                    )

                    SetupStep.CLOUD -> CloudSyncStep(
                        isEnabled = isCloudSyncEnabled,
                        onToggle = { isCloudSyncEnabled = it },
                        sheetUrl = sheetUrl,
                        onSheetUrlChange = { sheetUrl = it },
                        scriptUrl = scriptUrl,
                        onUrlChange = { scriptUrl = it },
                        apiKey = apiKey,
                        onKeyChange = { apiKey = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Progress Indicators
            Row(
                modifier = Modifier.padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(totalSteps) { index ->
                    val width by animateDpAsState(
                        targetValue = if (currentStep == index) 24.dp else 8.dp,
                        label = "DotWidth"
                    )
                    val color by animateColorAsState(
                        targetValue = if (currentStep == index) MaterialTheme.colorScheme.primary 
                                     else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        label = "DotColor"
                    )
                    Box(
                        modifier = Modifier
                            .size(width, 8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_back), fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        if (isTestingConnection) return@Button
                        when (currentStep) {
                            SetupStep.NAME -> {
                                if (userName.isBlank()) {
                                    showNameError = true
                                } else {
                                    showNameError = false
                                    // Saved now, not at the end: the import step
                                    // comes later, and a restored backup's name
                                    // should win over one typed here — the same
                                    // rule the budget follows.
                                    scope.launch { appUser.setName(userName) }
                                    currentStep++
                                }
                            }

                            SetupStep.BUDGET -> {
                                if (budgetText.isEmpty()) {
                                    showError = true
                                } else {
                                    showError = false
                                    currentStep++
                                }
                            }

                            SetupStep.CLOUD -> {
                                if (isCloudSyncEnabled) {
                                    if (scriptUrl.isBlank() || apiKey.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            "Please fill all cloud fields",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@Button
                                    }

                                    scope.launch {
                                        isTestingConnection = true
                                        val error =
                                            GoogleSheetsLogger.testConnection(scriptUrl, apiKey)
                                        if (error == null) {
                                            val budgetAmount = budgetText.toFloatOrNull() ?: 0f

                                            // Save to Room
                                            val db = AppDatabase.getDatabase(context)
                                            val monthKey = SimpleDateFormat(
                                                "yyyy-MM",
                                                Locale.getDefault()
                                            ).format(Date())
                                            db.monthlyBudgetDao().upsert(
                                                MonthlyBudget(
                                                    monthKey,
                                                    budgetAmount.toDouble()
                                                )
                                            )
                                            
                                            sharedPrefs.edit().apply {
                                                putFloat("budget", budgetAmount)
                                                putBoolean("cloud_sync", true)
                                                putString("sheet_url", sheetUrl)
                                                putString("script_url", scriptUrl)
                                                putString("api_key", apiKey)
                                                putBoolean("is_setup_complete", true)
                                                apply()
                                            }
                                            GoogleSheetsLogger.updateUrl(scriptUrl)
                                            GoogleSheetsLogger.updateApiKey(apiKey)

                                            isRestoring = true
                                            // Trigger initial restore of transactions and settings
                                            GoogleSheetsLogger.syncFromCloud(context)

                                            // Ensure current setup settings are also backed up
                                            GoogleSheetsLogger.backupSettings(context)
                                            isRestoring = false
                                            
                                            if (ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.READ_SMS
                                                ) == PackageManager.PERMISSION_GRANTED
                                            ) {
                                                SmsMonitorService.start(context)
                                            }
                                            onSetupComplete()
                                        } else {
                                            isTestingConnection = false
                                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    val budgetAmount = budgetText.toFloatOrNull() ?: 0f

                                    scope.launch {
                                        // Save to Room
                                        val db = AppDatabase.getDatabase(context)
                                        val monthKey =
                                            SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(
                                                Date()
                                            )
                                        db.monthlyBudgetDao().upsert(
                                            MonthlyBudget(
                                                monthKey,
                                                budgetAmount.toDouble()
                                            )
                                        )
                                    }
                                    
                                    sharedPrefs.edit().apply {
                                        putFloat("budget", budgetAmount)
                                        putBoolean("cloud_sync", false)
                                        remove("sheet_url")
                                        remove("script_url")
                                        remove("api_key")
                                        putBoolean("is_setup_complete", true)
                                        apply()
                                    }
                                    if (ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.READ_SMS
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        SmsMonitorService.start(context)
                                    }
                                    onSetupComplete()
                                }
                            }

                            // Welcome, privacy, permissions and the optional
                            // import step all just move on.
                            else -> currentStep++
                        }
                    },
                    modifier = Modifier
                        .weight(if (currentStep > 0) 1.5f else 1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    AnimatedContent(
                        targetState = isTestingConnection,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "ButtonContent"
                    ) { testing ->
                        if (testing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Checking connection...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = when (currentStep) {
                                        SetupStep.WELCOME -> stringResource(R.string.setup_get_started)
                                        totalSteps - 1 -> stringResource(R.string.setup_finish)
                                        else -> stringResource(R.string.setup_continue)
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }
            }

        }

        if (celebrate) {
            SetupCelebration(onFinished = onSetupComplete)
        }
    }

    BackupProgressDialog(backupController)

    backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            icon = {
                Icon(
                    if (backupFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (backupFailed) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
            },
            title = {
                Text(
                    if (backupFailed) "Restore failed" else "Data restored",
                    fontWeight = FontWeight.Black
                )
            },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = {
                    backupMessage = null
                    // Backup had no cloud credentials — carry on to the cloud step.
                    if (importedWithoutCloud) {
                        importedWithoutCloud = false
                        currentStep = SetupStep.CLOUD
                    }
                }) {
                    Text(if (importedWithoutCloud) "Set up cloud sync" else "OK")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (isRestoring) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restoring Data", fontWeight = FontWeight.Black) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Please wait while we restore your transactions and settings from the cloud...",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
fun WelcomeStep() {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            // Animated Background Glow
            val infiniteTransition = rememberInfiniteTransition(label = "Glow")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Scale"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.15f * scale),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension / 1.2f
                    )
                )
            }

            Surface(
                modifier = Modifier.size(140.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Decorative elements
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-20).dp, y = 40.dp)
                    .size(24.dp)
                    .alpha(0.6f),
                tint = secondaryColor
            )
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 20.dp, y = (-40).dp)
                    .size(28.dp)
                    .alpha(0.6f),
                tint = primaryColor
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.setup_welcome_title),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.setup_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun ImportStep(onChooseFile: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.SettingsBackupRestore,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.setup_import_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.setup_import_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onChooseFile,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            )
        ) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.setup_import_choose_file),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.setup_import_skip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PrivacyStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.setup_privacy_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.setup_privacy_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

@Composable
fun PermissionStep(
    icon: ImageVector,
    title: String,
    description: String,
    reasoning: String,
    buttonText: String,
    isGranted: Boolean,
    attempted: Boolean = false,
    onButtonClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = if (isGranted) Color(0xFFE8F5E9) else if (attempted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isGranted) Icons.Default.CheckCircle else if (attempted) Icons.Default.Error else icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = if (isGranted) Color(0xFF2E7D32) else if (attempted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = 0.5f
                )
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onButtonClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = if (isGranted) ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)) else if (attempted) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ) else ButtonDefaults.buttonColors()
        ) {
            if (isGranted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(buttonText)
        }
        if (!isGranted && attempted) {
            Text(
                text = stringResource(R.string.setup_try_again),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Setup step order. Named rather than numbered because the Continue logic, the
 * step content and the post-import redirect all refer to steps by position;
 * with bare integers, inserting one step meant renumbering every later one by
 * hand in three separate places.
 */
private object SetupStep {
    const val WELCOME = 0
    const val NAME = 1
    const val PRIVACY = 2
    const val SMS = 3
    const val NOTIFICATION_ACCESS = 4
    const val LOCATION = 5
    const val NOTIFICATIONS = 6
    const val BACKGROUND = 7
    const val BUDGET = 8
    const val IMPORT = 9
    const val CLOUD = 10
    const val COUNT = 11
}

@Composable
fun NameStep(value: String, isError: Boolean, onValueChange: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.setup_name_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_name_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.setup_name_label)) },
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(stringResource(R.string.setup_name_error), color = MaterialTheme.colorScheme.error)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun BudgetStep(value: String, isError: Boolean, onValueChange: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.setup_budget_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_budget_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.setup_budget_label)) },
            placeholder = { Text(stringResource(R.string.setup_budget_placeholder)) },
            prefix = { Text(stringResource(R.string.setup_currency_prefix), fontWeight = FontWeight.Bold) },
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(stringResource(R.string.setup_budget_error), color = MaterialTheme.colorScheme.error)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun FeaturesStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.setup_features_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        FeatureItem(
            icon = Icons.Default.Sms,
            title = stringResource(R.string.setup_feature_sms_title),
            description = stringResource(R.string.setup_feature_sms_desc)
        )
        Spacer(modifier = Modifier.height(24.dp))
        FeatureItem(
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            title = stringResource(R.string.setup_feature_insights_title),
            description = stringResource(R.string.setup_feature_insights_desc)
        )
    }
}

@Composable
fun CloudSyncStep(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    sheetUrl: String,
    onSheetUrlChange: (String) -> Unit,
    scriptUrl: String,
    onUrlChange: (String) -> Unit,
    apiKey: String,
    onKeyChange: (String) -> Unit
) {
    val extractedSheetId = remember(sheetUrl) {
        val pattern = "/spreadsheets/d/([a-zA-Z0-9-_]+)".toRegex()
        pattern.find(sheetUrl)?.groupValues?.get(1) ?: "YOUR_SHEET_ID_HERE"
    }

    val context = LocalContext.current

    val scriptCode = remember(extractedSheetId) {
        com.myapp.expensetracker.AppsScriptProvider.build(context, extractedSheetId)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            stringResource(R.string.setup_cloud_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.setup_cloud_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            onClick = { onToggle(!isEnabled) }
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.setup_cloud_enable_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }
        }
        
        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(24.dp))
                
                // Instructions Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.setup_instructions_title),
                            style = MaterialTheme.typography.titleMedium,
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
                                stringResource(stepRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                OutlinedTextField(
                    value = sheetUrl,
                    onValueChange = onSheetUrlChange,
                    label = { Text(stringResource(R.string.setup_sheet_url_label)) },
                    placeholder = { Text(stringResource(R.string.setup_sheet_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Apps Script Code", scriptCode)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_copy_code))
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.setup_cloud_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                            if (apiKey.isNotEmpty()) {
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("API Key", apiKey)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp))
                                }
                            }
                            TextButton(onClick = { 
                                val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
                                val randomKey = (1..43)
                                    .map { charPool.random() }
                                    .joinToString("")
                                onKeyChange(randomKey)
                            }) {
                                Text(stringResource(R.string.setup_cloud_generate_key))
                            }
                        }
                    },
                    readOnly = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = scriptUrl,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.setup_cloud_url_label)) },
                    placeholder = { Text(stringResource(R.string.setup_cloud_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.setup_cloud_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun FeatureItem(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
