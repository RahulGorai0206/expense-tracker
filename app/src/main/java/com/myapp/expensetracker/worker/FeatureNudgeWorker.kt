package com.myapp.expensetracker.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.ExpenseWidgetReceiver
import com.myapp.expensetracker.LazySyncManager
import com.myapp.expensetracker.MainActivity
import com.myapp.expensetracker.R
import com.myapp.expensetracker.SmsMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class FeatureNudgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            createNotificationChannels(applicationContext)

            val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val nowMillis = System.currentTimeMillis()
            val state = buildFeatureUsageState()
            val candidate = FeatureNudgePlanner.chooseCandidate(
                state = state,
                nowMillis = nowMillis,
                lastSentLookup = { key -> prefs.getLong(key, 0L) }
            )

            if (candidate != null) {
                postNotification(candidate)
                prefs.edit {
                    putLong(FeatureNudgePlanner.PREF_LAST_SENT_MS, nowMillis)
                    putString(FeatureNudgePlanner.PREF_LAST_KIND, candidate.kind.name.lowercase())
                    putLong(FeatureNudgePlanner.candidateLastSentKey(candidate), nowMillis)
                }
                Log.d(TAG, "Posted ${candidate.kind} nudge: ${candidate.id}")
            } else {
                Log.d(TAG, "No eligible feature nudge or tip")
            }

            scheduleNext(applicationContext, ExistingWorkPolicy.REPLACE)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Feature nudge worker failed", e)
            scheduleNext(applicationContext, ExistingWorkPolicy.REPLACE)
            Result.success()
        }
    }

    private suspend fun buildFeatureUsageState(): FeatureNudgePlanner.FeatureUsageState {
        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val db = AppDatabase.getDatabase(applicationContext)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val budget = db.monthlyBudgetDao().getEffectiveBudget(monthKey)?.amount ?: 0.0
        val transactionCount = db.transactionDao().getActiveTransactionCount()
        val scriptUrl = prefs.getString("script_url", "").orEmpty()
        val apiKey = prefs.getString("api_key", "").orEmpty()

        return FeatureNudgePlanner.FeatureUsageState(
            setupComplete = prefs.getBoolean("is_setup_complete", false),
            notificationsAllowed = areNotificationsAllowed(applicationContext),
            hasBudget = budget > 0.0,
            cloudSyncConfigured = scriptUrl.isNotBlank() && apiKey.isNotBlank(),
            backgroundSmsMonitoringEnabled = SmsMonitorService.isEnabled(applicationContext),
            notificationListenerEnabled = isNotificationListenerEnabled(applicationContext),
            aiModelDownloaded = LazySyncManager(applicationContext).isModelDownloaded(),
            homeWidgetPinned = hasHomeWidget(applicationContext),
            transactionCount = transactionCount
        )
    }

    private fun postNotification(candidate: FeatureNudgePlanner.Candidate) {
        if (!areNotificationsAllowed(applicationContext)) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NUDGE_KIND, candidate.kind.name.lowercase())
            putExtra(EXTRA_NUDGE_ID, candidate.id)
            putExtra(EXTRA_NUDGE_TARGET, candidate.targetArea)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            candidate.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, candidate.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(candidate.title)
            .setContentText(candidate.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(candidate.text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(candidate.notificationId, notification)
    }

    companion object {
        private const val TAG = "FeatureNudgeWorker"
        const val EXTRA_NUDGE_KIND = "nudge_kind"
        const val EXTRA_NUDGE_ID = "nudge_id"
        const val EXTRA_NUDGE_TARGET = "nudge_target"

        fun ensureScheduled(context: Context) {
            scheduleNext(context.applicationContext, ExistingWorkPolicy.KEEP)
        }

        fun scheduleNext(
            context: Context,
            policy: ExistingWorkPolicy,
            delayMillis: Long = FeatureNudgePlanner.randomDelayMillis()
        ) {
            val appContext = context.applicationContext
            val request = OneTimeWorkRequestBuilder<FeatureNudgeWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                FeatureNudgePlanner.UNIQUE_WORK_NAME,
                policy,
                request
            )

            val now = System.currentTimeMillis()
            val scheduledFor = now + delayMillis
            val prefs = appContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val existingNext = prefs.getLong(FeatureNudgePlanner.PREF_NEXT_AFTER_MS, 0L)
            if (policy != ExistingWorkPolicy.KEEP || existingNext <= now) {
                prefs.edit { putLong(FeatureNudgePlanner.PREF_NEXT_AFTER_MS, scheduledFor) }
            }

            Log.d(TAG, "Scheduled next nudge in ${TimeUnit.MILLISECONDS.toDays(delayMillis)} days")
        }

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val featureChannel = NotificationChannel(
                FeatureNudgePlanner.FEATURE_CHANNEL_ID,
                "Feature Suggestions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Occasional reminders about unused Expense Tracker features"
            }

            val tipsChannel = NotificationChannel(
                FeatureNudgePlanner.TIPS_CHANNEL_ID,
                "Tips",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Occasional tips for getting more from Expense Tracker"
            }

            notificationManager.createNotificationChannels(listOf(featureChannel, tipsChannel))
        }

        fun areNotificationsAllowed(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
        }

        fun isNotificationListenerEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }

        fun hasHomeWidget(context: Context): Boolean {
            val widgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, ExpenseWidgetReceiver::class.java)
            return widgetManager.getAppWidgetIds(componentName).isNotEmpty()
        }
    }
}
