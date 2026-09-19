package com.myapp.expensetracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class SmsMonitorService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val extractor = TransactionExtractor()
    private var smsObserver: SmsContentObserver? = null
    private var mmsSmsObserver: SmsContentObserver? = null
    private val processLock = Mutex() // Prevents concurrent processNewMessages() calls

    companion object {
        private const val TAG = "SmsMonitorService"
        private const val CHANNEL_ID = "sms_monitor_channel"
        private const val NOTIFICATION_ID = 9999
        private const val PREF_LAST_SMS_ID = "last_processed_sms_id"

        fun start(context: Context) {
            val intent = Intent(context, SmsMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsMonitorService::class.java))
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("background_monitoring", true)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit { putBoolean("background_monitoring", enabled) }
            if (enabled) start(context) else stop(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForegroundWithType()
        registerSmsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        unregisterSmsObserver()
        scope.cancel()

        // Auto-restart if still enabled (handles edge case where system kills us)
        if (isEnabled(this)) {
            val restartIntent = Intent(this, SmsMonitorService::class.java)
            startForegroundService(restartIntent)
        }
    }

    // ── ContentObserver for RCS + SMS ──────────────────────────────────

    private fun registerSmsObserver() {
        // IMPORTANT: Seed the watermark BEFORE registering ContentObservers.
        // If observers fire before the watermark is set, processNewMessages()
        // would see lastId=0 and reprocess the entire SMS history.
        initLastProcessedId()

        val handler = Handler(Looper.getMainLooper())
        smsObserver = SmsContentObserver(handler)
        mmsSmsObserver = SmsContentObserver(handler)

        // Watch content://sms — triggers on standard SMS database writes
        contentResolver.registerContentObserver(
            "content://sms/".toUri(),
            true,
            smsObserver!!
        )
        // Watch content://mms-sms — some devices/Google Messages write RCS here
        contentResolver.registerContentObserver(
            "content://mms-sms/".toUri(),
            true,
            mmsSmsObserver!!
        )
        Log.d(TAG, "SMS + MMS-SMS ContentObservers registered")
    }

    private fun unregisterSmsObserver() {
        smsObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        mmsSmsObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        smsObserver = null
        mmsSmsObserver = null
        Log.d(TAG, "ContentObservers unregistered")
    }

    private fun initLastProcessedId() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val existingId = prefs.getLong(PREF_LAST_SMS_ID, -1L)

        if (existingId < 0L) {
            // First-ever run: seed with the current highest SMS ID so we
            // only process messages arriving AFTER this point.
            val highestId = getHighestSmsId()
            prefs.edit { putLong(PREF_LAST_SMS_ID, highestId) }
            Log.d(TAG, "Seeded last processed SMS ID: $highestId")
        } else if (existingId == 0L) {
            // Watermark is 0 — this means the previous seed ran before SMS
            // permission was granted (query returned 0). Re-seed now.
            val highestId = getHighestSmsId()
            if (highestId > 0L) {
                prefs.edit { putLong(PREF_LAST_SMS_ID, highestId) }
                Log.d(TAG, "Re-seeded last processed SMS ID (was 0): $highestId")
            }
        }
    }

    private fun getHighestSmsId(): Long {
        var maxId = 0L
        try {
            // Query all messages (not just Inbox) to get the true highest ID for watermark tracking
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null, null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    maxId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting highest SMS ID", e)
        }
        return maxId
    }

    /**
     * ContentObserver that fires whenever the SMS/MMS database changes.
     * RCS messages from Google Messages are written into this same database,
     * so this observer catches them even though no SMS_RECEIVED broadcast fires.
     */
    inner class SmsContentObserver(handler: Handler) : ContentObserver(handler) {

        // Debounce: Android may fire onChange multiple times for a single message
        @Volatile
        private var lastChangeTime = 0L
        private val debounceMs = 3000L // 3s debounce to avoid rapid re-fires

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)

            val now = System.currentTimeMillis()
            if (now - lastChangeTime < debounceMs) return
            lastChangeTime = now

            Log.d(TAG, "SMS database changed — URI: $uri")
            scope.launch { processNewMessages() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processNewMessages() = processLock.withLock {
        // Mutex ensures only one processNewMessages() runs at a time,
        // preventing race conditions when onChange fires rapidly.
        var lastId = 0L
        var newHighestId = 0L
        try {
            val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            lastId = prefs.getLong(PREF_LAST_SMS_ID, 0L)

            // Safety net: if watermark is 0 (permission wasn't available during seed),
            // re-seed now to avoid processing the entire SMS history.
            if (lastId == 0L) {
                val highestId = getHighestSmsId()
                if (highestId > 0L) {
                    prefs.edit { putLong(PREF_LAST_SMS_ID, highestId) }
                    Log.w(
                        TAG,
                        "Watermark was 0 — re-seeded to $highestId to prevent old message flood"
                    )
                    return@withLock
                }
            }

            newHighestId = lastId

            // Only process messages that arrived in the last 2 minutes.
            // This is a safety net to prevent flooding if the watermark
            // somehow falls behind (e.g. app data restore, permission timing).
            val ageLimit = System.currentTimeMillis() - 2 * 60 * 1000

            // Query inbox for messages newer than our last processed ID
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.BODY,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} > ?",
                arrayOf(lastId.toString(), ageLimit.toString()),
                "${Telephony.Sms._ID} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val addrIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val smsId = it.getLong(idIdx)
                    if (smsId > newHighestId) newHighestId = smsId

                    try {
                        val body = it.getString(bodyIdx) ?: continue
                        val sender = it.getString(addrIdx) ?: "Unknown"
                        val timestamp = it.getLong(dateIdx)

                        Log.d(TAG, "ContentObserver processing new message ID=$smsId from $sender")

                        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                        val ignoreCcBills = prefs.getBoolean("ignore_cc_bills", false)

                        if (ignoreCcBills && extractor.isCreditCardBill(body)) {
                            Log.d(TAG, "Skipping CC Bill as per settings")
                            continue
                        }

                        val transaction = extractor.extractTransaction(body, sender, timestamp)

                        if (transaction != null) {
                            // Cross-layer dedup: skip if SmsReceiver or NotificationListener already got it
                            if (TransactionDedup.isDuplicate(body)) {
                                Log.d(
                                    TAG,
                                    "Skipping ID=$smsId — already processed by another layer"
                                )
                                continue
                            }

                            // DB-level dedup: check by system timestamp + amount
                            val db = AppDatabase.getDatabase(this@SmsMonitorService)
                            val existsInDb =
                                db.transactionDao()
                                    .checkDuplicate(
                                        transaction.date,
                                        transaction.amount,
                                        transaction.bodyHash,
                                        TransactionDedup.DB_DEDUP_WINDOW_MS
                                    )
                            if (existsInDb > 0) {
                                Log.d(
                                    TAG,
                                    "Skipping ID=$smsId — transaction already exists in DB (date+amount match)"
                                )
                                continue
                            }

                            // Check track-only-debits preference
                            val trackOnlyDebits = prefs.getBoolean("track_only_debits", false)
                            if (trackOnlyDebits && transaction.amount >= 0) {
                                Log.d(TAG, "Ignoring non-debit transaction (ContentObserver)")
                            } else {
                                // Capture location
                                val fusedLocationClient =
                                    LocationServices.getFusedLocationProviderClient(this@SmsMonitorService)
                                val location = try {
                                    withTimeoutOrNull(5000) {
                                        val lastLoc = fusedLocationClient.lastLocation.await()
                                        if (lastLoc == null || (System.currentTimeMillis() - lastLoc.time) > 5 * 60 * 1000) {
                                            fusedLocationClient.getCurrentLocation(
                                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                                null
                                            ).await()
                                        } else {
                                            lastLoc
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG,
                                        "Location capture failed (ContentObserver): ${e.message}"
                                    )
                                    null
                                }

                                val withLocation = transaction.copy(
                                    latitude = location?.latitude,
                                    longitude = location?.longitude
                                )

                                // Use the same notification flow as SmsReceiver
                                TransactionApproval.requestApproval(
                                    this@SmsMonitorService,
                                    withLocation
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing individual message ID=$smsId", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing new messages via ContentObserver", e)
        } finally {
            // Persist the new watermark — always, even if no transactions were found.
            // This prevents re-processing non-transaction messages on subsequent onChange fires.
            if (newHighestId > lastId) {
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                prefs.edit { putLong(PREF_LAST_SMS_ID, newHighestId) }
                Log.d(TAG, "Updated last processed SMS ID to $newHighestId")
            }
        }
    }

    // ── Notification (replicates SmsReceiver logic) ───────────────────

    // ── Foreground notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transaction Monitoring",
            NotificationManager.IMPORTANCE_LOW // Low = no sound, shows in shade
        ).apply {
            description = "Keeps the app running to capture transaction SMS"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Expense Tracker Active")
            .setContentText("Monitoring SMS & RCS transactions")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
