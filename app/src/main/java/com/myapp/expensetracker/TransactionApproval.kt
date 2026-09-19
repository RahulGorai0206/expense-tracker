package com.myapp.expensetracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The single approval pipeline shared by every detection layer (SmsReceiver,
 * SmsMonitorService's ContentObserver and TransactionNotificationListener).
 *
 * A detected transaction is written to [PendingTransaction] *before* the
 * notification is posted, so the pending state is durable. Accept, Deny and the
 * 30s timeout all resolve against that row, and [reconcileAbandoned] recovers
 * anything whose alarm never fired because the device rebooted or the process
 * was killed.
 */
object TransactionApproval {

    private const val TAG = "TransactionApproval"

    const val TIMEOUT_MS = 30_000L
    const val CHANNEL_ID = "transaction_alerts"

    const val ACTION_ACCEPT = "ACCEPT_TRANSACTION"
    const val ACTION_DENY = "DENY_TRANSACTION"
    const val ACTION_TIMEOUT = "TIMEOUT_TRANSACTION"

    const val EXTRA_PENDING_ID = "pendingId"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    /** Same window the in-memory cross-layer guard uses. */
    private const val QUEUE_DEDUP_WINDOW_MS = TransactionDedup.DB_DEDUP_WINDOW_MS

    /**
     * Persists [transaction] as pending and posts the Accept/Deny notification.
     * Safe to call from any detection layer — an equivalent already-queued
     * message is dropped.
     */
    suspend fun requestApproval(context: Context, transaction: Transaction) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).pendingTransactionDao()

        val alreadyQueued = dao.countMatching(
            date = transaction.date,
            bodyHash = transaction.bodyHash,
            windowMs = QUEUE_DEDUP_WINDOW_MS
        )
        if (alreadyQueued > 0) {
            Log.d(TAG, "Skipping — an equivalent transaction is already awaiting approval")
            return
        }

        val pendingId = dao.insert(
            PendingTransaction(
                notificationId = 0, // replaced below, once the row id is known
                sender = transaction.sender,
                amount = transaction.amount,
                date = transaction.date,
                body = transaction.body,
                bodyHash = transaction.bodyHash,
                category = transaction.category,
                latitude = transaction.latitude,
                longitude = transaction.longitude
            )
        )

        val notificationId = notificationIdFor(pendingId)
        dao.setNotificationId(pendingId, notificationId)

        showNotification(appContext, transaction, notificationId, pendingId)
    }

    /**
     * Commits a pending row to the ledger. Returns false when the row is gone
     * (already handled) or a matching transaction already exists.
     */
    suspend fun commit(context: Context, pendingId: Long, autoCleared: Boolean): Boolean {
        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val pending = db.pendingTransactionDao().getById(pendingId)

        if (pending == null) {
            Log.d(TAG, "Pending row $pendingId already resolved — nothing to commit")
            return false
        }

        val status = if (autoCleared) "Auto-Cleared" else "Cleared"
        val committed = insertIfAbsent(appContext, pending.toTransaction(status))

        db.pendingTransactionDao().deleteById(pendingId)
        cancelTimeout(appContext, pending.notificationId)
        dismissNotification(appContext, pending.notificationId)

        return committed
    }

    /** User tapped Deny — drop the pending row without recording anything. */
    suspend fun discard(context: Context, pendingId: Long, notificationId: Int) {
        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val pending = db.pendingTransactionDao().getById(pendingId)

        db.pendingTransactionDao().deleteById(pendingId)
        cancelTimeout(appContext, pending?.notificationId ?: notificationId)
        dismissNotification(appContext, pending?.notificationId ?: notificationId)
    }

    /**
     * Recovers transactions whose 30s timeout never fired — the device rebooted,
     * the process was killed, or an exact alarm was dropped. Anything older than
     * the timeout is auto-accepted, matching what would have happened had the
     * app stayed alive.
     */
    suspend fun reconcileAbandoned(context: Context) {
        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val now = System.currentTimeMillis()

        val abandoned = db.pendingTransactionDao().getAll()
            .filter { now - it.createdAt >= TIMEOUT_MS }

        if (abandoned.isEmpty()) return
        Log.d(TAG, "Recovering ${abandoned.size} abandoned pending transaction(s)")

        abandoned.forEach { pending ->
            try {
                commit(appContext, pending.id, autoCleared = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover pending transaction ${pending.id}", e)
            }
        }
    }

    /**
     * Legacy path: notifications posted by a previous app version carry the
     * payload in intent extras and have no pending row behind them.
     */
    suspend fun commitLegacy(context: Context, transaction: Transaction): Boolean =
        insertIfAbsent(context.applicationContext, transaction)

    private suspend fun insertIfAbsent(context: Context, transaction: Transaction): Boolean {
        val db = AppDatabase.getDatabase(context)
        val existing = db.transactionDao().findExistingTransaction(
            date = transaction.date,
            amount = transaction.amount,
            bodyHash = transaction.bodyHash,
            windowMs = TransactionDedup.DB_DEDUP_WINDOW_MS
        )

        if (existing != null) {
            Log.d(TAG, "Duplicate detected in DB (id=${existing.id}) — skipping insert")
            return false
        }

        val localId = db.transactionDao().insertAndReturnId(transaction)
        Log.d(TAG, "Saved transaction locally: ${transaction.amount} from ${transaction.sender}")

        enqueueWidgetUpdate(context)
        GoogleSheetsLogger.init(context)
        GoogleSheetsLogger.logAsync(context, transaction, localId)
        return true
    }

    // ── Notification plumbing ────────────────────────────────────────────────

    /**
     * Derived from the pending row id, not the message timestamp. The old
     * `date % Int.MAX_VALUE` scheme collided for messages arriving in the same
     * millisecond, and because Deny/Timeout used `id + 1` / `id + 2` as
     * PendingIntent request codes, two messages 1–2 ms apart could overwrite
     * each other's actions — accepting one would resolve the other.
     *
     * Spacing ids by [REQUEST_CODE_STRIDE] keeps every action of every pending
     * transaction in its own slot.
     */
    internal fun notificationIdFor(pendingId: Long): Int =
        ((pendingId % ID_SPACE) * REQUEST_CODE_STRIDE).toInt()

    private const val REQUEST_CODE_STRIDE = 4L

    /** Keeps id * stride comfortably inside Int range. */
    private const val ID_SPACE = 500_000_000L

    private fun showNotification(
        context: Context,
        transaction: Transaction,
        notificationId: Int,
        pendingId: Long
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transaction Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        fun intentFor(action: String) = Intent(context, NotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_PENDING_ID, pendingId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val acceptIntent = PendingIntent.getBroadcast(
            context, notificationId, intentFor(ACTION_ACCEPT), flags
        )
        val denyIntent = PendingIntent.getBroadcast(
            context, notificationId + 1, intentFor(ACTION_DENY), flags
        )
        val timeoutIntent = PendingIntent.getBroadcast(
            context, notificationId + 2, intentFor(ACTION_TIMEOUT), flags
        )

        val triggerAt = System.currentTimeMillis() + TIMEOUT_MS

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Transaction: ₹${"%,.2f".format(transaction.amount)}")
            .setContentText("From ${transaction.sender}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(triggerAt)
            .addAction(android.R.drawable.ic_input_add, "Accept", acceptIntent)
            .addAction(android.R.drawable.ic_delete, "Deny", denyIntent)
            .build()

        notificationManager.notify(notificationId, notification)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, timeoutIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, timeoutIntent)
        }
    }

    private fun cancelTimeout(context: Context, notificationId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val timeoutIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            Intent(context, NotificationReceiver::class.java).apply { action = ACTION_TIMEOUT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(timeoutIntent)
    }

    private fun dismissNotification(context: Context, notificationId: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
}
