package com.myapp.expensetracker

import com.google.mlkit.nl.entityextraction.*
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class TransactionExtractor {
    companion object {
        private val languageIdentifier by lazy { LanguageIdentification.getClient() }
        private val entityExtractor by lazy {
            EntityExtraction.getClient(
                EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH)
                    .build()
            )
        }
        @Volatile
        private var modelDownloaded = false

        private val keywordRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        /**
         * `\b` is only applied where the keyword actually starts/ends with a word
         * character, so markers ending in punctuation ("dr.") still anchor on
         * their left edge instead of never matching.
         */
        internal fun keywordRegex(keyword: String): Regex =
            keywordRegexCache.getOrPut(keyword) {
                val lead = if (keyword.firstOrNull()?.isLetterOrDigit() == true) "\\b" else ""
                val trail = if (keyword.lastOrNull()?.isLetterOrDigit() == true) "\\b" else ""
                Regex(lead + Regex.escape(keyword) + trail, RegexOption.IGNORE_CASE)
            }

    }

    /**
     * Resolved per instance so a rules update takes effect on the next detected
     * message without restarting the app.
     */
    private val rules: ExtractionRules get() = ExtractionRulesRepository.current()

    private val categoriesMap: Map<String, List<String>> get() = rules.categories
    private val spendKeywords: List<String> get() = rules.spendKeywords
    private val receiveKeywords: List<String> get() = rules.receiveKeywords
    private val weakDirectionKeywords: List<String> get() = rules.weakDirectionKeywords
    private val nonTransactionalPhrases: List<String> get() = rules.nonTransactionalPhrases
    private val txnDisqualifiers: List<String> get() = rules.txnDisqualifiers

    fun isCreditCardBill(body: String): Boolean {
        val lowerBody = body.lowercase()

        // 1. Direct Bill Indicators (Specific enough on their own)
        if (rules.creditCardBillPhrases.any { lowerBody.contains(it) }) return true

        // 2. Secondary check for "Card" + "Bill/Due/Statement" combinations
        val hasCardRef =
            lowerBody.contains("card ending") || lowerBody.contains("credit card") || lowerBody.contains(
                "your card"
            ) || lowerBody.contains(" card ")
        val isBillContext =
            lowerBody.contains("due date") || lowerBody.contains("statement") || lowerBody.contains(
                "outstanding"
            ) || lowerBody.contains("bill")

        // Explicit check for Card + Bill combination
        if (lowerBody.contains("card") && lowerBody.contains("bill")) return true

        return hasCardRef && isBillContext
    }

    suspend fun extractTransaction(body: String, sender: String, timestamp: Long): Transaction? {
        val lowerBody = body.lowercase()
        
        // 1. Skip OTPs, verification codes and promotional bank messages
        if (isNonTransactional(body)) return null

        return try {
            var extractedAmount: Double? = null
            
            try {
                if (!modelDownloaded) {
                    entityExtractor.downloadModelIfNeeded().await()
                    modelDownloaded = true
                }
    
                val params = EntityExtractionParams.Builder(body)
                    .setEntityTypesFilter(setOf(Entity.TYPE_MONEY)) // Only extract money
                    .build()
    
                val result = entityExtractor.annotate(params).await()
    
                // 2. Extract Money using ML Kit
                for (annotation in result) {
                    for (entity in annotation.entities) {
                        if (entity is MoneyEntity) {
                            if (extractedAmount == null) {
                                // Ignore amounts following "bal" or "balance" (likely account balance)
                                val startIndex = annotation.start
                                val prefix = lowerBody.substring((startIndex - 15).coerceAtLeast(0), startIndex)
                                if (!prefix.contains("bal")) {
                                    val rawAmount =
                                        entity.integerPart.toDouble() + (entity.fractionalPart.toDouble() / 100.0)
                                    extractedAmount = Math.round(rawAmount * 100.0) / 100.0
                                }
                            }
                        }
                    }
                }
            } catch (mlErr: Exception) {
                mlErr.printStackTrace()
                // Proceed to regex fallback if ML Kit fails
            }

            // ALWAYS use the SMS received timestamp as requested
            val transactionDate: Long = timestamp

            // 3. Regex Fallback for Amount (Rs. / INR / ₹)
            if (extractedAmount == null) {
                extractedAmount = extractAmountByRegex(body)
            }

            val direction = resolveDirection(lowerBody)

            if (extractedAmount != null && direction != null) {
                val finalAmount =
                    if (direction == Direction.SPEND) -extractedAmount else extractedAmount

                return Transaction(
                    sender = sender,
                    amount = finalAmount,
                    date = transactionDate,
                    body = body,
                    category = categorize(lowerBody),
                    status = "Cleared",
                    type = "automated"
                )
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Pure helpers ─────────────────────────────────────────────────────────
    // Split out of extractTransaction so the parsing rules can be unit tested
    // without ML Kit or an Android runtime. extractTransaction orchestrates
    // these; the ML Kit call remains the only untestable part.

    /** OTPs and promotional/informational bank messages that carry no transaction. */
    internal fun isNonTransactional(body: String): Boolean {
        val lowerBody = body.lowercase()
        if (rules.otpPhrases.any { lowerBody.contains(it) }) return true

        return nonTransactionalPhrases.any { lowerBody.contains(it) }
    }

    /**
     * Fallback when ML Kit finds no money entity. Skips amounts preceded by
     * "bal"/"balance" so an account balance isn't logged as a spend.
     */
    internal fun extractAmountByRegex(body: String): Double? {
        val lowerBody = body.lowercase()

        for (pattern in rules.amountPatterns) {
            // Every match, not just the first: when a message leads with the
            // available balance, the spend amount comes later in the text.
            for (match in pattern.findAll(body)) {
                val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                val matchStart = match.range.first
                val prefix = lowerBody.substring(
                    (matchStart - rules.balanceLookback).coerceAtLeast(0),
                    matchStart
                )
                if (!prefix.contains("bal")) return amount
            }
        }
        return null
    }

    internal fun isSpendMessage(lowerBody: String): Boolean =
        spendKeywords.any { keywordMatchesTransaction(lowerBody, it) }

    internal fun isReceiveMessage(lowerBody: String): Boolean =
        receiveKeywords.any { containsKeyword(lowerBody, it) }

    internal enum class Direction { SPEND, RECEIVE }

    /**
     * Which way the money moved, or null when the message isn't a transaction.
     *
     * Plenty of messages match both lists, because banks name the counterparty's
     * side too ("debited ... credited to beneficiary") and because markers like
     * "txn" or "payment" appear regardless of direction. Taking spend whenever it
     * matched booked every credit alert carrying a "Txn ID" as a debit, so a tie
     * is resolved rather than assumed:
     *
     *  1. An unambiguous marker beats a [weakDirectionKeywords] one.
     *  2. Level otherwise, the action named first wins — bank SMS leads with what
     *     happened to *this* account and mentions the other side afterwards.
     */
    internal fun resolveDirection(lowerBody: String): Direction? {
        val spendHits = spendKeywords.filter { keywordMatchesTransaction(lowerBody, it) }
        val receiveHits = receiveKeywords.filter { containsKeyword(lowerBody, it) }

        if (spendHits.isEmpty() && receiveHits.isEmpty()) return null
        if (receiveHits.isEmpty()) return Direction.SPEND
        if (spendHits.isEmpty()) return Direction.RECEIVE

        val weak = weakDirectionKeywords.mapTo(mutableSetOf()) { it.lowercase() }
        val strongSpend = spendHits.filterNot { it.lowercase() in weak }
        val strongReceive = receiveHits.filterNot { it.lowercase() in weak }

        if (strongSpend.isNotEmpty() && strongReceive.isEmpty()) return Direction.SPEND
        if (strongReceive.isNotEmpty() && strongSpend.isEmpty()) return Direction.RECEIVE

        // Both sides equally specific: compare within that tier only.
        val spendAt = (strongSpend.ifEmpty { spendHits }).minOf { firstMatchIndex(lowerBody, it) }
        val receiveAt =
            (strongReceive.ifEmpty { receiveHits }).minOf { firstMatchIndex(lowerBody, it) }
        return if (receiveAt < spendAt) Direction.RECEIVE else Direction.SPEND
    }

    /** Word-boundary aware, so it agrees with [containsKeyword] on what matched. */
    private fun firstMatchIndex(body: String, keyword: String): Int =
        keywordRegex(keyword).find(body)?.range?.first ?: Int.MAX_VALUE

    /**
     * Whole-word match rather than a raw substring.
     *
     * Plain `contains` matched "sent" inside "con**sent**", so a YES Bank
     * "require consent to continue disbursement" promo was logged as a ₹58,000
     * spend. Word boundaries also make short markers like "dr." safe to use as
     * keywords, which plain substring matching never would be ("address"
     * contains "dr").
     */
    internal fun containsKeyword(body: String, keyword: String): Boolean =
        keywordRegex(keyword).containsMatchIn(body)

    /** First matching category wins; "Other" when nothing matches. */
    internal fun categorize(lowerBody: String): String {
        for ((category, keywords) in categoriesMap) {
            if (keywords.any { lowerBody.contains(it.lowercase()) }) return category
        }
        return "Other"
    }

    /**
     * Checks if a spend keyword appears in a genuine transactional context.
     * For ambiguous keywords like "txn", verifies that the surrounding words
     * don't indicate a non-transactional context (e.g., "txn limit").
     */
    internal fun keywordMatchesTransaction(body: String, keyword: String): Boolean {
        if (!containsKeyword(body, keyword)) return false

        // For "txn", check that it's not followed by disqualifying words
        if (keyword == "txn") {
            // Find all occurrences and check context around each
            var searchFrom = 0
            while (true) {
                val idx = body.indexOf(keyword, searchFrom)
                if (idx == -1) return false
                // Grab up to 15 chars after the keyword to check context
                val afterEnd = (idx + keyword.length + 15).coerceAtMost(body.length)
                val after = body.substring(idx + keyword.length, afterEnd)
                    .trimStart('.', ' ', ',')
                    .lowercase()
                val isDisqualified = txnDisqualifiers.any { after.startsWith(it) }
                if (!isDisqualified) return true // found a valid transactional "txn"
                searchFrom = idx + keyword.length
            }
        }

        return true
    }
}
