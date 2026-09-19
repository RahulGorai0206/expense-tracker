package com.myapp.expensetracker

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Two hand-kept ledgers that sit alongside the automatic transaction stream:
 * money lent to people, and money set aside into savings pots.
 *
 * Nothing here is populated from SMS. These are records the user writes
 * themselves, because the bank message for a UPI transfer to a relative looks
 * identical to one for a restaurant bill — only the user knows which is which.
 *
 * Every total is derived from its rows rather than stored, so a repayment or a
 * correction never leaves a stale balance behind. See [LedgerCalculator].
 */

/**
 * Someone money is lent to. Deliberately *not* scoped to an event the way
 * [SplitMember] is: the point of this ledger is one running balance per person
 * that survives across years and unrelated loans.
 */
@Entity(tableName = "people")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One handover of money. [reason] is kept for the life of the row — the whole
 * point is still knowing why, long after it has been paid back.
 *
 * [fromPotId] is optional: set it when the money came out of a savings pot, so
 * that pot can show what is currently lent out rather than overstating what is
 * actually available.
 */
@Entity(
    tableName = "loans",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SavingsPot::class,
            parentColumns = ["id"],
            childColumns = ["fromPotId"],
            // The loan record outlives a deleted pot; it just stops being
            // attributed to one.
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("personId"), Index("fromPotId")]
)
data class Loan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val amount: Double,
    val reason: String,
    val lentAt: Long = System.currentTimeMillis(),
    val fromPotId: Long? = null
)

/** A part-payment or full repayment against one [Loan]. */
@Entity(
    tableName = "loan_repayments",
    foreignKeys = [
        ForeignKey(
            entity = Loan::class,
            parentColumns = ["id"],
            childColumns = ["loanId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("loanId")]
)
data class LoanRepayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val amount: Double,
    val receivedAt: Long = System.currentTimeMillis(),
    val note: String = ""
)

/**
 * A pot of money set aside: an emergency fund, or one SIP. Contributions are
 * appended over time, so "incremental" is the normal case rather than a mode.
 */
@Entity(tableName = "savings_pots")
data class SavingsPot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Optional goal, purely for showing progress. */
    val targetAmount: Double? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** One addition to a [SavingsPot] — a SIP instalment, or any top-up. */
@Entity(
    tableName = "pot_contributions",
    foreignKeys = [
        ForeignKey(
            entity = SavingsPot::class,
            parentColumns = ["id"],
            childColumns = ["potId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("potId")]
)
data class PotContribution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val potId: Long,
    val amount: Double,
    val addedAt: Long = System.currentTimeMillis(),
    val note: String = ""
)

// ── Derived views ────────────────────────────────────────────────────────────
// Computed by LedgerCalculator, never persisted.

/** One loan together with what has been paid back against it. */
data class LoanWithRepayments(
    val loan: Loan,
    val repayments: List<LoanRepayment>,
    val repaid: Double,
    val outstanding: Double
) {
    val isSettled: Boolean get() = outstanding <= 0.0
}

/** A person's running position across every loan, settled or not. */
data class PersonBalance(
    val person: Person,
    val totalLent: Double,
    val totalRepaid: Double,
    /** Positive: still owed to you. Negative: they have paid back more than they took. */
    val outstanding: Double,
    val openLoanCount: Int
)

/** A pot's position, separating what is saved from what is currently out on loan. */
data class PotSummary(
    val pot: SavingsPot,
    val totalSaved: Double,
    /** Outstanding across loans marked as drawn from this pot. */
    val lentOut: Double,
    val contributionCount: Int
) {
    /** What is actually on hand: saved, less what is still with someone else. */
    val available: Double get() = totalSaved - lentOut

    /** Progress toward [SavingsPot.targetAmount] as 0f..1f, or null when no goal is set. */
    val progress: Float?
        get() = pot.targetAmount
            ?.takeIf { it > 0.0 }
            ?.let { (totalSaved / it).coerceIn(0.0, 1.0).toFloat() }
}
