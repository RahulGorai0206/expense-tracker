package com.myapp.expensetracker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.BuildConfig
import com.myapp.expensetracker.GitHubApi
import com.myapp.expensetracker.GoogleSheetsLogger
import com.myapp.expensetracker.R
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("UpdateCheckWorker", "Starting update check...")

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient.Builder().build())
            .build()

        val githubApi = retrofit.create(GitHubApi::class.java)
        val sharedPrefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        try {
            val latestRelease = githubApi.getLatestRelease()
            val latestTagName = latestRelease.tag_name

            // Normalize both sides by stripping a leading "v" so that the GitHub tag
            // "v2.2.0" compares equal to the BuildConfig value "2.2.0" (which the CI
            // workflow strips before baking into the APK).
            val remoteVersion = latestTagName.trimStart('v')
            val currentVersion = BuildConfig.VERSION_NAME.trimStart('v')
            val currentCommitHash = BuildConfig.GIT_COMMIT_HASH

            Log.d("UpdateCheckWorker", "Local:  version=$currentVersion  commit=$currentCommitHash")
            Log.d("UpdateCheckWorker", "Remote: version=$remoteVersion   tag=$latestTagName")

            var updateAvailable = false
            var latestCommitSha = ""

            if (remoteVersion != currentVersion) {
                // A new tag exists — always an update
                updateAvailable = true
                Log.d(
                    "UpdateCheckWorker",
                    "Update available: new tag $remoteVersion vs local $currentVersion"
                )
            } else {
                // Same tag – resolve the *commit* SHA via the Git refs API.
                // We must handle both:
                //   - Lightweight tags: /git/ref/tags/{tag}.object.type == "commit"
                //     → .object.sha IS the commit SHA
                //   - Annotated tags:   /git/ref/tags/{tag}.object.type == "tag"
                //     → .object.sha is the tag *object* SHA, need one more call to peel it
                try {
                    val tagRef = githubApi.getTagRef(latestTagName)
                    val tagObject = tagRef.`object`

                    latestCommitSha = if (tagObject.type == "tag") {
                        // Annotated tag → peel to the underlying commit
                        val annotatedTag = githubApi.getAnnotatedTagObject(tagObject.sha)
                        Log.d(
                            "UpdateCheckWorker",
                            "Annotated tag peeled: tag_sha=${tagObject.sha} → commit_sha=${annotatedTag.`object`.sha}"
                        )
                        annotatedTag.`object`.sha
                    } else {
                        // Lightweight tag → SHA is already the commit SHA
                        Log.d("UpdateCheckWorker", "Lightweight tag: commit_sha=${tagObject.sha}")
                        tagObject.sha
                    }

                    when {
                        currentCommitHash == "unknown" -> {
                            // Build was done outside git – can't compare, skip
                            Log.d(
                                "UpdateCheckWorker",
                                "Local commit hash is 'unknown' – skipping SHA comparison"
                            )
                        }

                        latestCommitSha.equals(currentCommitHash, ignoreCase = true) -> {
                            // Exact match
                            Log.d(
                                "UpdateCheckWorker",
                                "SHAs match – no update: remote=$latestCommitSha, local=$currentCommitHash"
                            )
                        }
                        // Handles the case where one is a short-SHA prefix of the other (shouldn't happen,
                        // but safe to guard against if build system ever uses abbreviated hashes)
                        latestCommitSha.startsWith(currentCommitHash, ignoreCase = true) ||
                                currentCommitHash.startsWith(
                                    latestCommitSha,
                                    ignoreCase = true
                                ) -> {
                            Log.d(
                                "UpdateCheckWorker",
                                "SHAs are prefix-match – treating as same commit"
                            )
                        }

                        else -> {
                            updateAvailable = true
                            Log.d(
                                "UpdateCheckWorker",
                                "SHA mismatch – update available: remote=$latestCommitSha, local=$currentCommitHash"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UpdateCheckWorker", "Error resolving tag ref SHA: ${e.message}")
                    // Don't flip updateAvailable on network/parse error – fail silently
                }
            }

            if (updateAvailable) {
                Log.d(
                    "UpdateCheckWorker",
                    "Flagging update: $latestTagName (commit: $latestCommitSha)"
                )
                sharedPrefs.edit().apply {
                    putBoolean("update_available", true)
                    putString("latest_version", latestTagName)
                    putString("latest_version_sha", latestCommitSha)
                    putString("latest_release_url", latestRelease.html_url)
                    apply()
                }
                showUpdateNotification(latestTagName, latestRelease.html_url)
            } else {
                Log.d("UpdateCheckWorker", "No update available.")
                sharedPrefs.edit().apply {
                    putBoolean("update_available", false)
                    putString("latest_version_sha", latestCommitSha)
                    apply()
                }
            }

            // --- SYNC PENDING/FAILED TRANSACTIONS ---
            if (GoogleSheetsLogger.isConfigured()) {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val needingSync = dao.getAllTransactionsNeedingSync()
                if (needingSync.isNotEmpty()) {
                    Log.d(
                        "UpdateCheckWorker",
                        "Found ${needingSync.size} transactions needing sync. Triggering..."
                    )
                    needingSync.forEach { tx ->
                        GoogleSheetsLogger.logAsync(applicationContext, tx, tx.id.toLong())
                    }
                }
            }

            // Schedule for next day at 6 PM IST
            scheduleNextCheck(applicationContext)

            return Result.success()
        } catch (e: Exception) {
            Log.e("UpdateCheckWorker", "Error checking for updates", e)
            return Result.retry()
        }
    }

    private fun showUpdateNotification(version: String, url: String) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "app_updates"

        val channel = NotificationChannel(
            channelId,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Update Available")
            .setContentText("A new version ($version) of Expense Tracker is available.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(1001, notification)
    }

    companion object {
        fun scheduleNextCheck(context: Context) {
            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            val target = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
                set(Calendar.HOUR_OF_DAY, 18)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (now.after(target)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val workRequest = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "daily_update_check",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d("UpdateCheckWorker", "Scheduled next check in ${delay / 1000 / 60} minutes")
        }

        fun checkNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
