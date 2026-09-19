package com.myapp.expensetracker

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the hand-kept lending and savings ledgers.
 *
 * Rows come back whole and the totals are computed in [LedgerCalculator] rather
 * than by SQL aggregates: the ledgers are small (tens of rows, not thousands),
 * and keeping the arithmetic in one testable place matters more here than
 * pushing it into the query.
 */
@Dao
interface LedgerDao {

    // ── Lending ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE ASC")
    fun observePeople(): Flow<List<Person>>

    @Query("SELECT * FROM people WHERE id = :personId")
    fun observePerson(personId: Long): Flow<Person?>

    @Query("SELECT * FROM loans ORDER BY lentAt DESC, id DESC")
    fun observeLoans(): Flow<List<Loan>>

    @Query("SELECT * FROM loans WHERE personId = :personId ORDER BY lentAt DESC, id DESC")
    fun observeLoansForPerson(personId: Long): Flow<List<Loan>>

    @Query("SELECT * FROM loan_repayments ORDER BY receivedAt DESC, id DESC")
    fun observeRepayments(): Flow<List<LoanRepayment>>

    @Query(
        """
        SELECT loan_repayments.* FROM loan_repayments
        INNER JOIN loans ON loans.id = loan_repayments.loanId
        WHERE loans.personId = :personId
        ORDER BY loan_repayments.receivedAt DESC, loan_repayments.id DESC
        """
    )
    fun observeRepaymentsForPerson(personId: Long): Flow<List<LoanRepayment>>

    // ── Savings ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM savings_pots ORDER BY createdAt ASC")
    fun observePots(): Flow<List<SavingsPot>>

    @Query("SELECT * FROM savings_pots WHERE id = :potId")
    fun observePot(potId: Long): Flow<SavingsPot?>

    @Query("SELECT * FROM pot_contributions ORDER BY addedAt DESC, id DESC")
    fun observeContributions(): Flow<List<PotContribution>>

    @Query("SELECT * FROM pot_contributions WHERE potId = :potId ORDER BY addedAt DESC, id DESC")
    fun observeContributionsForPot(potId: Long): Flow<List<PotContribution>>

    // ── One-shot reads (backup export) ──────────────────────────────────────

    @Query("SELECT * FROM people ORDER BY createdAt ASC")
    suspend fun getAllPeople(): List<Person>

    @Query("SELECT * FROM loans WHERE personId = :personId ORDER BY lentAt ASC")
    suspend fun getLoansForPerson(personId: Long): List<Loan>

    @Query("SELECT * FROM loan_repayments WHERE loanId = :loanId ORDER BY receivedAt ASC")
    suspend fun getRepaymentsForLoan(loanId: Long): List<LoanRepayment>

    @Query("SELECT * FROM savings_pots ORDER BY createdAt ASC")
    suspend fun getAllPots(): List<SavingsPot>

    @Query("SELECT * FROM pot_contributions WHERE potId = :potId ORDER BY addedAt ASC")
    suspend fun getContributionsForPot(potId: Long): List<PotContribution>

    // ── Writes ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Delete
    suspend fun deletePerson(person: Person)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoan(loan: Loan): Long

    @Update
    suspend fun updateLoan(loan: Loan)

    @Query("DELETE FROM loans WHERE id = :loanId")
    suspend fun deleteLoanById(loanId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepayment(repayment: LoanRepayment): Long

    @Query("DELETE FROM loan_repayments WHERE id = :repaymentId")
    suspend fun deleteRepaymentById(repaymentId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPot(pot: SavingsPot): Long

    @Update
    suspend fun updatePot(pot: SavingsPot)

    @Delete
    suspend fun deletePot(pot: SavingsPot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContribution(contribution: PotContribution): Long

    @Query("DELETE FROM pot_contributions WHERE id = :contributionId")
    suspend fun deleteContributionById(contributionId: Long)
}
