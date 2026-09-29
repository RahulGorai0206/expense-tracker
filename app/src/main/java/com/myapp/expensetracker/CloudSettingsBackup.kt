package com.myapp.expensetracker

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CloudSettingsBackup(
    val version: Int = 1,
    val updated_at: Long = System.currentTimeMillis(),
    val budget_month_key: String? = null,
    val budget_amount: Double? = null,
    val budget_monthly: Boolean? = null,
    val track_only_debits: Boolean? = null,
    val ignore_cc_bills: Boolean? = null,
    val background_monitoring: Boolean? = null,
    val follow_system_theme: Boolean? = null,
    val dark_theme: Boolean? = null,
    val cloud_sync: Boolean? = null,
    val is_setup_complete: Boolean? = null,
    val sheet_url: String? = null,
    val script_url: String? = null,
    val api_key: String? = null,
    val saved_tags: List<String>? = null,
    val app_user_name: String? = null
)

object CloudSettingsBackupManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val backupPreferenceKeys = setOf(
        "budget_monthly",
        "track_only_debits",
        "ignore_cc_bills",
        "background_monitoring",
        "follow_system_theme",
        "dark_theme",
        "cloud_sync",
        "saved_tags",
        AppUserStore.PREF_KEY
    )

    fun backupAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            GoogleSheetsLogger.init(appContext)
            GoogleSheetsLogger.backupSettings(appContext)
        }
    }

    suspend fun capture(context: Context): CloudSettingsBackup {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val db = AppDatabase.getDatabase(context)
        val monthKey = currentMonthKey()
        val budget = db.monthlyBudgetDao().getEffectiveBudget(monthKey)

        return CloudSettingsBackup(
            budget_month_key = budget?.monthKey,
            budget_amount = budget?.amount,
            budget_monthly = prefs.getBoolean("budget_monthly", true),
            track_only_debits = prefs.getBoolean("track_only_debits", false),
            ignore_cc_bills = prefs.getBoolean("ignore_cc_bills", false),
            background_monitoring = prefs.getBoolean("background_monitoring", true),
            follow_system_theme = prefs.getBoolean("follow_system_theme", true),
            dark_theme = prefs.getBoolean("dark_theme", true),
            cloud_sync = GoogleSheetsLogger.isConfigured(),
            is_setup_complete = prefs.getBoolean("is_setup_complete", false),
            sheet_url = prefs.getString("sheet_url", ""),
            script_url = prefs.getString("script_url", ""),
            api_key = prefs.getString("api_key", ""),
            saved_tags = getSavedTags(context),
            app_user_name = prefs.getString(AppUserStore.PREF_KEY, "")
                ?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun apply(context: Context, backup: CloudSettingsBackup) {
        // Deliberately not logging `backup` itself: its generated toString()
        // contains api_key, script_url and sheet_url.
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        prefs.edit(commit = true) {
            backup.budget_monthly?.let {
                android.util.Log.d("CloudBackup", "Setting budget_monthly: $it")
                putBoolean("budget_monthly", it)
            }
            backup.track_only_debits?.let {
                android.util.Log.d("CloudBackup", "Setting track_only_debits: $it")
                putBoolean("track_only_debits", it)
            }
            backup.ignore_cc_bills?.let {
                android.util.Log.d("CloudBackup", "Setting ignore_cc_bills: $it")
                putBoolean("ignore_cc_bills", it)
            }
            backup.follow_system_theme?.let {
                android.util.Log.d("CloudBackup", "Setting follow_system_theme: $it")
                putBoolean("follow_system_theme", it)
            }
            backup.dark_theme?.let {
                android.util.Log.d("CloudBackup", "Setting dark_theme: $it")
                putBoolean("dark_theme", it)
            }
            backup.cloud_sync?.let {
                android.util.Log.d("CloudBackup", "Setting cloud_sync: $it")
                putBoolean("cloud_sync", it)
            }
            backup.is_setup_complete?.let {
                android.util.Log.d("CloudBackup", "Setting is_setup_complete: $it")
                putBoolean("is_setup_complete", it)
            }
            // Values below are credential material — restored silently.
            backup.sheet_url?.let {
                putString("sheet_url", it)
            }
            backup.script_url?.let {
                putString("script_url", it)
                GoogleSheetsLogger.updateUrl(it)
            }
            backup.api_key?.let {
                putString("api_key", it)
                GoogleSheetsLogger.updateApiKey(it)
            }
            backup.saved_tags?.let {
                android.util.Log.d("CloudBackup", "Setting saved_tags: $it")
                putStringSet("saved_tags", it.toSortedSet())
            }
            // Blank is skipped: an older backup or a device that never set a
            // name must not wipe one that's been set here.
            backup.app_user_name?.takeIf { it.isNotBlank() }?.let {
                putString(AppUserStore.PREF_KEY, it.trim())
            }
        }

        backup.background_monitoring?.let { enabled ->
            SmsMonitorService.setEnabled(context, enabled)
        }

        val amount = backup.budget_amount
        if (amount != null && amount > 0.0) {
            val monthKey = backup.budget_month_key?.takeIf { it.isNotBlank() } ?: currentMonthKey()
            AppDatabase.getDatabase(context).monthlyBudgetDao().upsert(
                MonthlyBudget(
                    monthKey = monthKey,
                    amount = amount
                )
            )
            prefs.edit(commit = true) { putFloat("budget", amount.toFloat()) }
        }
    }

    private fun currentMonthKey(): String {
        return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
    }

    fun getSavedTags(context: Context): List<String> {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("saved_tags", emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun saveTags(context: Context, tags: Collection<String>) {
        val normalized = tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toSet()

        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit { putStringSet("saved_tags", normalized) }
    }
}
