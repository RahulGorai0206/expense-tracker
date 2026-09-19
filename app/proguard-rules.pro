# ============================================================================
#  R8 / ProGuard configuration
#
#  R8 full mode is ON by default in AGP 8+, which is aggressive: it renames
#  fields, strips generic signatures and removes anything it can't see being
#  used. Everything reached by REFLECTION therefore needs an explicit rule —
#  that is what silently broke SMS detection, GPS capture and the AI model the
#  first time minification was enabled.
# ============================================================================

# ── Attributes ──────────────────────────────────────────────────────────────
# Signature   : Gson/Retrofit need generic types (List<RemoteTransaction> etc.)
# *Annotation*: Retrofit reads @GET/@Field at runtime
# Exceptions  : preserves checked exception info on Retrofit interfaces
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes Exceptions

# Keep line numbers so CrashReporter's on-device stack traces stay readable.
# Deobfuscate anything else with app/build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin reflection/metadata used by several libraries below.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Gson ────────────────────────────────────────────────────────────────────
# Gson maps JSON keys to FIELD NAMES. R8 renaming a field to `a` silently turns
# every value null — which is how the settings restore, cloud sync and the
# downloaded extraction rules break without ever throwing.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**

# Every model this app serialises or deserialises, fields intact.
-keep class com.myapp.expensetracker.CloudSettingsBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GoogleSheetResponse { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.RemoteTransaction { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.ExpenseBackupFile { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.TransactionBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.MonthlyBudgetBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitEventBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitMemberBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitExpenseBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitShareBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitPaymentBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SavingsPotBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.PotContributionBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.PersonBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.LoanBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.LoanRepaymentBackup { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.ExtractionRulesFile { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GitHubRelease { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GitHubTagRef { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GitHubObject { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GitHubAnnotatedTag { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.GitHubCommit { <fields>; <init>(...); }

# Room entities: stored/read by generated code, but keep fields so column names
# survive and any future Gson use of them keeps working.
-keep class com.myapp.expensetracker.Transaction { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.MonthlyBudget { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.PendingTransaction { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitEvent { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitMember { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitExpense { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitShare { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SplitPayment { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.Person { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.Loan { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.LoanRepayment { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.SavingsPot { <fields>; <init>(...); }
-keep class com.myapp.expensetracker.PotContribution { <fields>; <init>(...); }

# ── Retrofit / OkHttp ───────────────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep interface com.myapp.expensetracker.GoogleSheetsApi { *; }
-keep interface com.myapp.expensetracker.GitHubApi { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

# ── Room ────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ── WorkManager ─────────────────────────────────────────────────────────────
# Workers are instantiated reflectively from their class NAME. If R8 renames
# them, every background job (cloud sync, AI lazy sync, update check, widget
# refresh, feature nudges) silently stops running.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class com.myapp.expensetracker.worker.** { *; }

# ── Android components referenced from the manifest ─────────────────────────
# AAPT generates keep rules for these, but they are the SMS/RCS capture path,
# so they are pinned explicitly rather than relied upon.
-keep class com.myapp.expensetracker.SmsReceiver { *; }
-keep class com.myapp.expensetracker.SmsMonitorService { *; }
-keep class com.myapp.expensetracker.TransactionNotificationListener { *; }
-keep class com.myapp.expensetracker.NotificationReceiver { *; }
-keep class com.myapp.expensetracker.BootReceiver { *; }
-keep class com.myapp.expensetracker.ExpenseWidgetReceiver { *; }
-keep class com.myapp.expensetracker.PinnedWidgetReceiver { *; }
-keep class com.myapp.expensetracker.ExpenseApplication { *; }

# ── ML Kit (entity extraction + language id) ────────────────────────────────
# Model loading and the native bridge are reflective.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ── MediaPipe GenAI (Gemma on-device inference) ─────────────────────────────
# Pure JNI: the native layer looks these up by name, so obfuscation breaks the
# AI Lazy Sync at runtime with no compile-time warning.
-keep class com.google.mediapipe.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# ── Play Services (Fused Location — GPS capture) ────────────────────────────
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**

# ── Koin ────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-dontwarn org.koin.**

# ── Glance app widget ───────────────────────────────────────────────────────
-keep class androidx.glance.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# ── Coroutines ──────────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Enums ───────────────────────────────────────────────────────────────────
# valueOf()/values() are reached reflectively by serialisation.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable / Serializable ───────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
