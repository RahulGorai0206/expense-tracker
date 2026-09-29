package com.myapp.expensetracker

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "split_events")
data class SplitEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "split_members",
    foreignKeys = [
        ForeignKey(
            entity = SplitEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId")]
)
data class SplitMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val eventId: Long,
    val displayName: String,
    val contactLookupKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * True for the member who is the person using this app. At most one per
     * event. Their share of each expense is their own spending, which is what
     * lets it be booked into the transaction history.
     */
    val isAppUser: Boolean = false
)

@Entity(
    tableName = "split_expenses",
    foreignKeys = [
        ForeignKey(
            entity = SplitEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId"), Index("paidByMemberId")]
)
data class SplitExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val eventId: Long,
    val amount: Double,
    val description: String,
    val paidByMemberId: Long,
    val splitMode: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "split_shares",
    foreignKeys = [
        ForeignKey(
            entity = SplitExpense::class,
            parentColumns = ["id"],
            childColumns = ["splitExpenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("splitExpenseId"), Index("memberId")]
)
data class SplitShare(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val splitExpenseId: Long,
    val memberId: Long,
    val owedAmount: Double,
    val percentage: Double,
    /**
     * The transaction this share was booked as, if the app user added their
     * share to History. Only ever set on the app user's share. Checked against
     * the transaction's current status rather than trusted outright, so a share
     * whose transaction was later deleted can be booked again.
     */
    val transactionId: Int? = null
)

@Entity(
    tableName = "split_payments",
    foreignKeys = [
        ForeignKey(
            entity = SplitEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId"), Index("fromMemberId"), Index("toMemberId")]
)
data class SplitPayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val eventId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amount: Double,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

enum class SplitMode(val dbValue: String, val label: String) {
    EVEN("even", "Evenly"),
    AMOUNT("amount", "By amount"),
    PERCENTAGE("percentage", "By percentage");

    companion object {
        fun fromDb(value: String): SplitMode =
            entries.firstOrNull { it.dbValue == value } ?: EVEN
    }
}

data class MemberBalance(
    val memberId: Long,
    val memberName: String,
    val netAmount: Double
)

data class Settlement(
    val fromMemberId: Long,
    val fromMemberName: String,
    val toMemberId: Long,
    val toMemberName: String,
    val amount: Double
)

data class SplitShareSummary(
    val eventName: String,
    val memberId: Long,
    val memberName: String,
    val netAmount: Double,
    val involvedSettlements: List<Settlement>,
    val allBalances: List<MemberBalance>
)
