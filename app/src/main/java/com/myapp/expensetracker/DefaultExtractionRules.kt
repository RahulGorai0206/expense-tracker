package com.myapp.expensetracker

/**
 * Emergency floor used only if the bundled asset can't be read at all — an
 * unreachable state in a correctly built APK, but detection must never die
 * outright.
 *
 * Deliberately minimal rather than a copy of `rules/extraction-rules.json`:
 * duplicating the real rule set here would guarantee the two drift apart, which
 * is exactly what moving them into a file was meant to avoid.
 */
object DefaultExtractionRules {
    val rules = ExtractionRules(
        version = 0,
        releasedAt = "built-in",
        spendKeywords = listOf("debited", "spent", "paid", "withdrawn", "deducted"),
        receiveKeywords = listOf("credited", "received", "refunded"),
        weakDirectionKeywords = emptyList(),
        otpPhrases = listOf("otp", "verification code", "is your code"),
        nonTransactionalPhrases = emptyList(),
        txnDisqualifiers = listOf("limit", "alert", "password", "pin"),
        creditCardBillPhrases = listOf("total amount due", "minimum amount due"),
        amountPatterns = listOf(
            Regex("""(?:Rs\.?|INR|₹)\s*(\d+(?:,\d+)*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        ),
        balanceLookback = 15,
        categories = emptyMap()
    )
}
