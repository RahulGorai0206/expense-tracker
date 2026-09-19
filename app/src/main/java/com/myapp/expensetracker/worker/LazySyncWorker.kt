package com.myapp.expensetracker.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myapp.expensetracker.LazySyncManager
import com.myapp.expensetracker.MainActivity
import com.myapp.expensetracker.R

class LazySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val startDate = inputData.getLong(KEY_START_DATE, -1L)
        val endDate = inputData.getLong(KEY_END_DATE, -1L)
        if (startDate <= 0L || endDate <= 0L) return Result.failure()

        createNotificationChannel()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setForeground(createForegroundInfo("SMS permission is required", 0, 0, 0, done = true))
            return Result.failure()
        }

        setForeground(createForegroundInfo("Starting Lazy Sync...", 0, 0, 0))

        val manager = LazySyncManager(applicationContext)
        var lastProgress = LazySyncManager.SyncProgress("Starting Lazy Sync...")
        val success = manager.syncMessagesInBackground(startDate, endDate) { progress ->
            lastProgress = progress
            setProgress(
                workDataOf(
                    "message" to progress.message,
                    "current" to progress.current,
                    "total" to progress.total,
                    "added" to progress.added
                )
            )
            setForeground(
                createForegroundInfo(
                    message = progress.message,
                    current = progress.current,
                    total = progress.total,
                    added = progress.added
                )
            )
        }

        setForeground(
            createForegroundInfo(
                message = if (success) lastProgress.message else "Lazy Sync failed",
                current = lastProgress.current,
                total = lastProgress.total,
                added = lastProgress.added,
                done = true
            )
        )

        return if (success) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(
        message: String,
        current: Int,
        total: Int,
        added: Int,
        done: Boolean = false
    ): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (done) "Lazy Sync finished" else "Lazy Sync running")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSubText("Added $added")

        if (total > 0 && !done) {
            builder.setProgress(total, current.coerceAtMost(total), false)
        } else if (!done) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(0, 0, false)
            builder.setAutoCancel(true)
        }

        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lazy Sync Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while AI scans historical SMS messages"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val KEY_START_DATE = "start_date"
        private const val KEY_END_DATE = "end_date"
        private const val CHANNEL_ID = "lazy_sync_progress"
        private const val NOTIFICATION_ID = 4401
        private const val UNIQUE_WORK_NAME = "lazy_sync_background"

        fun start(context: Context, startDate: Long, endDate: Long) {
            val request = OneTimeWorkRequestBuilder<LazySyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_START_DATE to startDate,
                        KEY_END_DATE to endDate
                    )
                )
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
