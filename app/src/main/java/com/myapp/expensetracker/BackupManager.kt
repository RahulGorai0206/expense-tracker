package com.myapp.expensetracker

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// On-disk backup format.
//
// Every field is nullable and the DTOs are deliberately decoupled from the Room
// entities: a truncated, hand-edited or older file can then never crash the
// importer, and changing an entity does not silently invalidate existing
// backups. Row ids are never exported — the importer assigns fresh ones and
// remaps split relationships through the `localId` references below.
// ─────────────────────────────────────────────────────────────────────────────

enum class BackupScope(val id: String) {
    /** Transaction history + split data. */
    DATA("data"),

    /** Everything in DATA plus budget history and app settings. */
    FULL("full");

    companion object {
        fun fromId(value: String?): BackupScope =
            entries.firstOrNull { it.id == value } ?: DATA
    }
}

data class ExpenseBackupFile(
    val format: String? = null,
    val schemaVersion: Int? = null,
    val appVersion: String? = null,
    val exportedAt: Long? = null,
    val scope: String? = null,
    val transactions: List<TransactionBackup>? = null,
    val splitEvents: List<SplitEventBackup>? = null,
    // Added after the first release of this format. Null in every older backup,
    // which restores exactly as it always did.
    val savingsPots: List<SavingsPotBackup>? = null,
    val people: List<PersonBackup>? = null,
    val budgets: List<MonthlyBudgetBackup>? = null,
    val settings: CloudSettingsBackup? = null
)

data class TransactionBackup(
    val remoteId: String? = null,
    val syncStatus: String? = null,
    val sender: String? = null,
    val amount: Double? = null,
    val date: Long? = null,
    val body: String? = null,
    val bodyHash: Int? = null,
    val category: String? = null,
    val tag: String? = null,
    val status: String? = null,
    val type: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class MonthlyBudgetBackup(
    val monthKey: String? = null,
    val amount: Double? = null,
    val createdAt: Long? = null
)

data class SplitEventBackup(
    val name: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val members: List<SplitMemberBackup>? = null,
    val expenses: List<SplitExpenseBackup>? = null,
    val payments: List<SplitPaymentBackup>? = null
)

data class SplitMemberBackup(
    /** Row id as it was on the exporting device — only used to rejoin references. */
    val localId: Long? = null,
    val displayName: String? = null,
    val contactLookupKey: String? = null,
    val createdAt: Long? = null
)

data class SplitExpenseBackup(
    val amount: Double? = null,
    val description: String? = null,
    val paidByMemberLocalId: Long? = null,
    val splitMode: String? = null,
    val createdAt: Long? = null,
    val shares: List<SplitShareBackup>? = null
)

data class SplitShareBackup(
    val memberLocalId: Long? = null,
    val owedAmount: Double? = null,
    val percentage: Double? = null
)

data class SplitPaymentBackup(
    val fromMemberLocalId: Long? = null,
    val toMemberLocalId: Long? = null,
    val amount: Double? = null,
    val note: String? = null,
    val createdAt: Long? = null
)

/**
 * A savings pot and everything put into it. [localId] lets a loan say which pot
 * it was drawn from after both have been reinserted with fresh ids.
 */
data class SavingsPotBackup(
    val localId: Long? = null,
    val name: String? = null,
    val targetAmount: Double? = null,
    val note: String? = null,
    val createdAt: Long? = null,
    val contributions: List<PotContributionBackup>? = null
)

data class PotContributionBackup(
    val amount: Double? = null,
    val addedAt: Long? = null,
    val note: String? = null
)

/** One person, with every loan and repayment nested underneath. */
data class PersonBackup(
    val name: String? = null,
    val note: String? = null,
    val createdAt: Long? = null,
    val loans: List<LoanBackup>? = null
)

data class LoanBackup(
    val amount: Double? = null,
    val reason: String? = null,
    val lentAt: Long? = null,
    /** References [SavingsPotBackup.localId]; null when not drawn from a pot. */
    val fromPotLocalId: Long? = null,
    val repayments: List<LoanRepaymentBackup>? = null
)

data class LoanRepaymentBackup(
    val amount: Double? = null,
    val receivedAt: Long? = null,
    val note: String? = null
)

/** What an import actually changed. Surfaced to the user verbatim. */
data class ImportSummary(
    val scope: BackupScope,
    val transactionsAdded: Int = 0,
    val transactionsSkipped: Int = 0,
    val splitEventsAdded: Int = 0,
    val splitEventsSkipped: Int = 0,
    val peopleAdded: Int = 0,
    val potsAdded: Int = 0,
    val budgetsAdded: Int = 0,
    val settingsApplied: Boolean = false
) {
    fun toMessage(): String = buildString {
        append("$transactionsAdded transaction${if (transactionsAdded == 1) "" else "s"}")
        if (transactionsSkipped > 0) append(" ($transactionsSkipped already present)")
        append(", $splitEventsAdded split event${if (splitEventsAdded == 1) "" else "s"}")
        if (splitEventsSkipped > 0) append(" ($splitEventsSkipped already present)")
        // Only mentioned when there is something to mention, so a user who has
        // never opened the ledger doesn't see two permanent zeroes.
        if (peopleAdded > 0) append(", $peopleAdded ${if (peopleAdded == 1) "person" else "people"}")
        if (potsAdded > 0) append(", $potsAdded savings pot${if (potsAdded == 1) "" else "s"}")
        if (budgetsAdded > 0) append(", $budgetsAdded budget${if (budgetsAdded == 1) "" else "s"}")
        if (settingsApplied) append(", settings restored")
        append(" imported.")
    }
}

sealed interface BackupResult {
    data class ExportSuccess(val scope: BackupScope, val transactions: Int, val splitEvents: Int) :
        BackupResult

    data class ImportSuccess(val summary: ImportSummary) : BackupResult
    data class Failure(val message: String) : BackupResult
}

object BackupManager {
    const val FORMAT = "expense-tracker-backup"
    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"

    /** Refuse absurdly large files rather than OOM trying to parse them. */
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun suggestedFileName(scope: BackupScope): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val label = if (scope == BackupScope.FULL) "full" else "data"
        return "expense-tracker-$label-$stamp.json"
    }

    // ── Export ───────────────────────────────────────────────────────────────

    suspend fun buildBackup(context: Context, scope: BackupScope): ExpenseBackupFile =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val transactions = db.transactionDao().getAllTransactionsList()
            val splitEvents = db.splitDao().getAllEvents().map { event ->
                val members = db.splitDao().getMembersForEvent(event.id)
                val expenses = db.splitDao().getExpensesForEvent(event.id).map { expense ->
                    SplitExpenseBackup(
                        amount = expense.amount,
                        description = expense.description,
                        paidByMemberLocalId = expense.paidByMemberId,
                        splitMode = expense.splitMode,
                        createdAt = expense.createdAt,
                        shares = db.splitDao().getSharesForExpense(expense.id).map { share ->
                            SplitShareBackup(
                                memberLocalId = share.memberId,
                                owedAmount = share.owedAmount,
                                percentage = share.percentage
                            )
                        }
                    )
                }
                SplitEventBackup(
                    name = event.name,
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt,
                    members = members.map { member ->
                        SplitMemberBackup(
                            localId = member.id,
                            displayName = member.displayName,
                            contactLookupKey = member.contactLookupKey,
                            createdAt = member.createdAt
                        )
                    },
                    expenses = expenses,
                    payments = db.splitDao().getPaymentsForEvent(event.id).map { payment ->
                        SplitPaymentBackup(
                            fromMemberLocalId = payment.fromMemberId,
                            toMemberLocalId = payment.toMemberId,
                            amount = payment.amount,
                            note = payment.note,
                            createdAt = payment.createdAt
                        )
                    }
                )
            }

            val savingsPots = db.ledgerDao().getAllPots().map { pot ->
                SavingsPotBackup(
                    localId = pot.id,
                    name = pot.name,
                    targetAmount = pot.targetAmount,
                    note = pot.note,
                    createdAt = pot.createdAt,
                    contributions = db.ledgerDao().getContributionsForPot(pot.id).map { c ->
                        PotContributionBackup(
                            amount = c.amount,
                            addedAt = c.addedAt,
                            note = c.note
                        )
                    }
                )
            }

            val people = db.ledgerDao().getAllPeople().map { person ->
                PersonBackup(
                    name = person.name,
                    note = person.note,
                    createdAt = person.createdAt,
                    loans = db.ledgerDao().getLoansForPerson(person.id).map { loan ->
                        LoanBackup(
                            amount = loan.amount,
                            reason = loan.reason,
                            lentAt = loan.lentAt,
                            fromPotLocalId = loan.fromPotId,
                            repayments = db.ledgerDao().getRepaymentsForLoan(loan.id).map { r ->
                                LoanRepaymentBackup(
                                    amount = r.amount,
                                    receivedAt = r.receivedAt,
                                    note = r.note
                                )
                            }
                        )
                    }
                )
            }

            ExpenseBackupFile(
                format = FORMAT,
                schemaVersion = SCHEMA_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = System.currentTimeMillis(),
                scope = scope.id,
                transactions = transactions.map { it.toBackup() },
                splitEvents = splitEvents,
                savingsPots = savingsPots,
                people = people,
                budgets = if (scope == BackupScope.FULL) {
                    db.monthlyBudgetDao().getAllBudgets().map { it.toBackup() }
                } else null,
                settings = if (scope == BackupScope.FULL) {
                    CloudSettingsBackupManager.capture(context)
                } else null
            )
        }

    suspend fun exportTo(context: Context, uri: Uri, scope: BackupScope): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val backup = buildBackup(context, scope)
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.bufferedWriter().use { writer -> gson.toJson(backup, writer) }
                } ?: return@withContext BackupResult.Failure("Could not open the selected file.")

                BackupResult.ExportSuccess(
                    scope = scope,
                    transactions = backup.transactions?.size ?: 0,
                    splitEvents = backup.splitEvents?.size ?: 0
                )
            } catch (e: Exception) {
                BackupResult.Failure("Export failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

    // ── Import ───────────────────────────────────────────────────────────────

    suspend fun importFrom(context: Context, uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val size = fileSize(context, uri)
        if (size != null && size > MAX_FILE_BYTES) {
            return@withContext BackupResult.Failure("That file is too large to be a backup.")
        }

        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return@withContext BackupResult.Failure("Could not open the selected file.")

        // Gson returns null for an empty file or a bare `null` literal, so this
        // stays nullable despite the platform-type signature.
        val parsed: ExpenseBackupFile? = try {
            stream.use { input ->
                input.bufferedReader().use { reader ->
                    gson.fromJson(reader, ExpenseBackupFile::class.java)
                }
            }
        } catch (e: Exception) {
            return@withContext BackupResult.Failure("This file isn't valid JSON.")
        }

        if (parsed == null || parsed.format != FORMAT) {
            return@withContext BackupResult.Failure("Not an Expense Tracker backup file.")
        }
        val schema = parsed.schemaVersion ?: 0
        if (schema > SCHEMA_VERSION) {
            return@withContext BackupResult.Failure(
                "This backup was made by a newer version of the app. Update first, then import."
            )
        }

        try {
            applyBackup(context, parsed)
        } catch (e: Exception) {
            BackupResult.Failure("Import failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Merges [backup] into the local database inside a single Room transaction —
     * either everything lands or nothing does. Nothing is ever deleted, and
     * re-importing the same file is a no-op.
     */
    private suspend fun applyBackup(context: Context, backup: ExpenseBackupFile): BackupResult {
        val db = AppDatabase.getDatabase(context)
        val scope = BackupScope.fromId(backup.scope)

        var transactionsAdded = 0
        var transactionsSkipped = 0
        var splitEventsAdded = 0
        var splitEventsSkipped = 0
        var peopleAdded = 0
        var potsAdded = 0
        var budgetsAdded = 0

        db.withTransaction {
            // ── Transactions ────────────────────────────────────────────────
            val existingKeys = db.transactionDao().getAllTransactionsList()
                .map { it.dedupKey() }
                .toMutableSet()

            backup.transactions.orEmpty().forEach { dto ->
                val entity = dto.toEntity()
                if (entity == null) {
                    transactionsSkipped++
                    return@forEach
                }
                if (!existingKeys.add(entity.dedupKey())) {
                    transactionsSkipped++
                    return@forEach
                }
                db.transactionDao().insert(entity)
                transactionsAdded++
            }

            // ── Splits ──────────────────────────────────────────────────────
            val existingEventKeys = db.splitDao().getAllEvents()
                .map { it.dedupKey() }
                .toMutableSet()

            backup.splitEvents.orEmpty().forEach { eventDto ->
                val name = eventDto.name?.trim().orEmpty()
                if (name.isBlank()) {
                    splitEventsSkipped++
                    return@forEach
                }
                val createdAt = eventDto.createdAt ?: System.currentTimeMillis()
                if (!existingEventKeys.add(splitEventDedupKey(name, createdAt))) {
                    splitEventsSkipped++
                    return@forEach
                }

                val newEventId = db.splitDao().insertEvent(
                    SplitEvent(
                        name = name,
                        createdAt = createdAt,
                        updatedAt = eventDto.updatedAt ?: createdAt
                    )
                )

                // Old member id → freshly assigned member id.
                val memberIdMap = mutableMapOf<Long, Long>()
                eventDto.members.orEmpty().forEach { memberDto ->
                    val displayName = memberDto.displayName?.trim().orEmpty()
                    if (displayName.isBlank()) return@forEach
                    val newMemberId = db.splitDao().insertMember(
                        SplitMember(
                            eventId = newEventId,
                            displayName = displayName,
                            contactLookupKey = memberDto.contactLookupKey,
                            createdAt = memberDto.createdAt ?: createdAt
                        )
                    )
                    memberDto.localId?.let { memberIdMap[it] = newMemberId }
                }

                eventDto.expenses.orEmpty().forEach { expenseDto ->
                    val amount = expenseDto.amount ?: return@forEach
                    val payerId = memberIdMap[expenseDto.paidByMemberLocalId] ?: return@forEach
                    val newExpenseId = db.splitDao().insertExpense(
                        SplitExpense(
                            eventId = newEventId,
                            amount = amount,
                            description = expenseDto.description.orEmpty(),
                            paidByMemberId = payerId,
                            splitMode = SplitMode.fromDb(expenseDto.splitMode.orEmpty()).dbValue,
                            createdAt = expenseDto.createdAt ?: createdAt
                        )
                    )
                    val shares = expenseDto.shares.orEmpty().mapNotNull { shareDto ->
                        val memberId = memberIdMap[shareDto.memberLocalId] ?: return@mapNotNull null
                        SplitShare(
                            splitExpenseId = newExpenseId,
                            memberId = memberId,
                            owedAmount = shareDto.owedAmount ?: 0.0,
                            percentage = shareDto.percentage ?: 0.0
                        )
                    }
                    if (shares.isNotEmpty()) db.splitDao().insertShares(shares)
                }

                eventDto.payments.orEmpty().forEach { paymentDto ->
                    val amount = paymentDto.amount ?: return@forEach
                    val fromId = memberIdMap[paymentDto.fromMemberLocalId] ?: return@forEach
                    val toId = memberIdMap[paymentDto.toMemberLocalId] ?: return@forEach
                    db.splitDao().insertPayment(
                        SplitPayment(
                            eventId = newEventId,
                            fromMemberId = fromId,
                            toMemberId = toId,
                            amount = amount,
                            note = paymentDto.note.orEmpty(),
                            createdAt = paymentDto.createdAt ?: createdAt
                        )
                    )
                }

                splitEventsAdded++
            }

            // ── Savings pots ────────────────────────────────────────────────
            // Imported before people, so a loan can be reattached to the pot it
            // was drawn from once both have fresh row ids.
            val existingPotKeys = db.ledgerDao().getAllPots()
                .map { ledgerDedupKey(it.name, it.createdAt) }
                .toMutableSet()
            val potIdByLocalId = mutableMapOf<Long, Long>()

            backup.savingsPots.orEmpty().forEach { dto ->
                val name = dto.name?.trim().orEmpty()
                if (name.isBlank()) return@forEach
                val createdAt = dto.createdAt ?: System.currentTimeMillis()
                if (!existingPotKeys.add(ledgerDedupKey(name, createdAt))) return@forEach

                val newPotId = db.ledgerDao().insertPot(
                    SavingsPot(
                        name = name,
                        targetAmount = dto.targetAmount,
                        note = dto.note.orEmpty(),
                        createdAt = createdAt
                    )
                )
                dto.localId?.let { potIdByLocalId[it] = newPotId }

                dto.contributions.orEmpty().forEach { c ->
                    val amount = c.amount ?: return@forEach
                    db.ledgerDao().insertContribution(
                        PotContribution(
                            potId = newPotId,
                            amount = amount,
                            addedAt = c.addedAt ?: createdAt,
                            note = c.note.orEmpty()
                        )
                    )
                }
                potsAdded++
            }

            // ── People, loans and repayments ────────────────────────────────
            val existingPersonKeys = db.ledgerDao().getAllPeople()
                .map { ledgerDedupKey(it.name, it.createdAt) }
                .toMutableSet()

            backup.people.orEmpty().forEach { dto ->
                val name = dto.name?.trim().orEmpty()
                if (name.isBlank()) return@forEach
                val createdAt = dto.createdAt ?: System.currentTimeMillis()
                if (!existingPersonKeys.add(ledgerDedupKey(name, createdAt))) return@forEach

                val newPersonId = db.ledgerDao().insertPerson(
                    Person(
                        name = name,
                        note = dto.note.orEmpty(),
                        createdAt = createdAt
                    )
                )

                dto.loans.orEmpty().forEach { loanDto ->
                    val amount = loanDto.amount ?: return@forEach
                    val lentAt = loanDto.lentAt ?: createdAt
                    val newLoanId = db.ledgerDao().insertLoan(
                        Loan(
                            personId = newPersonId,
                            amount = amount,
                            reason = loanDto.reason.orEmpty(),
                            lentAt = lentAt,
                            // Drops to null when the pot wasn't in this backup;
                            // the loan is still worth keeping without it.
                            fromPotId = loanDto.fromPotLocalId?.let { potIdByLocalId[it] }
                        )
                    )
                    loanDto.repayments.orEmpty().forEach { r ->
                        val repaid = r.amount ?: return@forEach
                        db.ledgerDao().insertRepayment(
                            LoanRepayment(
                                loanId = newLoanId,
                                amount = repaid,
                                receivedAt = r.receivedAt ?: lentAt,
                                note = r.note.orEmpty()
                            )
                        )
                    }
                }
                peopleAdded++
            }

            // ── Budget history (full backups only) ──────────────────────────
            backup.budgets.orEmpty().forEach { dto ->
                val entity = dto.toEntity() ?: return@forEach
                db.monthlyBudgetDao().upsert(entity)
                budgetsAdded++
            }
        }

        // Settings touch SharedPreferences and services, so they are applied
        // outside the database transaction.
        val settings = backup.settings
        if (settings != null) {
            CloudSettingsBackupManager.apply(context, settings)
        }

        enqueueWidgetUpdate(context)

        return BackupResult.ImportSuccess(
            ImportSummary(
                scope = scope,
                transactionsAdded = transactionsAdded,
                transactionsSkipped = transactionsSkipped,
                splitEventsAdded = splitEventsAdded,
                splitEventsSkipped = splitEventsSkipped,
                peopleAdded = peopleAdded,
                potsAdded = potsAdded,
                budgetsAdded = budgetsAdded,
                settingsApplied = settings != null
            )
        )
    }

    private fun fileSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?.takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }
}

// ── Entity ⇄ DTO mapping ─────────────────────────────────────────────────────

private fun Transaction.toBackup() = TransactionBackup(
    remoteId = remoteId,
    syncStatus = syncStatus,
    sender = sender,
    amount = amount,
    date = date,
    body = body,
    bodyHash = bodyHash,
    category = category,
    tag = tag,
    status = status,
    type = type,
    latitude = latitude,
    longitude = longitude
)

/** Returns null when the row carries no usable amount or date. */
internal fun TransactionBackup.toEntity(): Transaction? {
    val safeAmount = amount ?: return null
    val safeDate = date ?: return null
    if (safeAmount.isNaN() || safeAmount.isInfinite()) return null
    val safeBody = body.orEmpty()
    return Transaction(
        remoteId = remoteId,
        // A restored row has no verified cloud counterpart, so anything that was
        // mid-flight when the backup was taken is re-queued rather than trusted.
        syncStatus = if (remoteId.isNullOrBlank()) "failed" else syncStatus ?: "synced",
        sender = sender.orEmpty(),
        amount = safeAmount,
        date = safeDate,
        body = safeBody,
        bodyHash = bodyHash ?: safeBody.hashCode(),
        category = category ?: "Other",
        tag = tag.orEmpty(),
        status = status ?: "Cleared",
        type = type ?: "automated",
        latitude = latitude,
        longitude = longitude
    )
}

private fun MonthlyBudget.toBackup() = MonthlyBudgetBackup(
    monthKey = monthKey,
    amount = amount,
    createdAt = createdAt
)

internal fun MonthlyBudgetBackup.toEntity(): MonthlyBudget? {
    val key = monthKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val safeAmount = amount ?: return null
    if (safeAmount.isNaN() || safeAmount.isInfinite()) return null
    return MonthlyBudget(
        monthKey = key,
        amount = safeAmount,
        createdAt = createdAt ?: System.currentTimeMillis()
    )
}

/**
 * Identity of a transaction for merge purposes. Exact on all three fields, so
 * two genuinely distinct payments of the same amount are never conflated.
 */
internal fun Transaction.dedupKey(): String = "$date|$amount|$bodyHash"

internal fun splitEventDedupKey(name: String, createdAt: Long): String = "$name|$createdAt"

internal fun SplitEvent.dedupKey(): String = splitEventDedupKey(name, createdAt)

/**
 * Identity for a person or a savings pot on re-import. Name alone would collide
 * for two relatives who share one, so creation time is folded in — the same
 * shape [splitEventDedupKey] uses.
 */
internal fun ledgerDedupKey(name: String, createdAt: Long): String = "$name|$createdAt"
