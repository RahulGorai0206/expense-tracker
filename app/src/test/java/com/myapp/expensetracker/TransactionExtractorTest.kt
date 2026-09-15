package com.myapp.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The parsing rules that decide whether a bank SMS becomes a transaction, and
 * for how much. Everything here is the pure half of the extractor — the ML Kit
 * money-entity call is the only part these can't reach.
 */
class TransactionExtractorTest {

    private val extractor = TransactionExtractor()

    /**
     * Runs against the real `rules/extraction-rules.json` that ships with the
     * app, so a bad edit to that file fails the build rather than silently
     * degrading detection on users' phones.
     */
    @Before
    fun loadShippedRules() {
        ExtractionRulesRepository.install(ShippedRules.load())
    }

    @After
    fun resetRules() {
        ExtractionRulesRepository.install(null)
    }

    // ── OTP / promotional filtering ──────────────────────────────────────────

    @Test
    fun `otp messages are rejected`() {
        assertTrue(extractor.isNonTransactional("123456 is your OTP for a txn of Rs.500"))
        assertTrue(extractor.isNonTransactional("Your verification code is 9987"))
        assertTrue(extractor.isNonTransactional("4321 is your code for login"))
    }

    @Test
    fun `promotional messages are rejected`() {
        assertTrue(extractor.isNonTransactional("You are pre-approved for a loan of Rs.500000"))
        assertTrue(extractor.isNonTransactional("Your txn limit has been enhanced to Rs.100000"))
        assertTrue(extractor.isNonTransactional("Welcome to HDFC Bank! Download the app now"))
        assertTrue(extractor.isNonTransactional("Complete your KYC to continue banking"))
    }

    @Test
    fun `a real debit alert is not filtered out`() {
        assertFalse(
            extractor.isNonTransactional("Rs.450.00 debited from a/c XX1234 to SWIGGY on 16-08-26")
        )
    }

    // ── Amount extraction ────────────────────────────────────────────────────

    @Test
    fun `extracts amounts in each supported currency notation`() {
        assertEquals(450.0, extractor.extractAmountByRegex("Rs.450 debited")!!, 0.001)
        assertEquals(450.0, extractor.extractAmountByRegex("Rs 450 debited")!!, 0.001)
        assertEquals(450.0, extractor.extractAmountByRegex("INR 450 debited")!!, 0.001)
        assertEquals(450.0, extractor.extractAmountByRegex("₹450 debited")!!, 0.001)
    }

    @Test
    fun `handles thousands separators and paise`() {
        assertEquals(12345.67, extractor.extractAmountByRegex("Rs.12,345.67 spent")!!, 0.001)
        assertEquals(1234567.0, extractor.extractAmountByRegex("INR 12,34,567 debited")!!, 0.001)
        assertEquals(99.5, extractor.extractAmountByRegex("₹99.50 paid")!!, 0.001)
    }

    @Test
    fun `supports the debited by form with no currency prefix`() {
        assertEquals(250.0, extractor.extractAmountByRegex("Acct XX99 debited by 250.00")!!, 0.001)
    }

    @Test
    fun `an account balance is never mistaken for the amount`() {
        // The only number here follows "bal" — there is no spend amount to take.
        assertNull(extractor.extractAmountByRegex("Your a/c balance is Rs.10,000"))
        assertNull(extractor.extractAmountByRegex("Avbl bal Rs.5,432.10"))
    }

    @Test
    fun `takes the transaction amount and not the trailing balance`() {
        val body = "Rs.450.00 debited from a/c XX1234. Avbl bal Rs.10,000.00"
        assertEquals(450.0, extractor.extractAmountByRegex(body)!!, 0.001)
    }

    @Test
    fun `finds the spend amount even when the balance is stated first`() {
        // Only the first regex match used to be considered, so the real amount
        // was lost whenever a message opened with the available balance.
        val body = "Avbl bal Rs.10,000.00. Rs.450.00 debited from a/c XX1234"
        assertEquals(450.0, extractor.extractAmountByRegex(body)!!, 0.001)
    }

    @Test
    fun `messages with no amount yield null`() {
        assertNull(extractor.extractAmountByRegex("Your account statement is ready"))
    }

    // ── Direction ────────────────────────────────────────────────────────────

    @Test
    fun `spend and receive keywords are recognised`() {
        assertTrue(extractor.isSpendMessage("rs.450 debited from a/c"))
        assertTrue(extractor.isSpendMessage("you spent rs.200 at cafe"))
        assertTrue(extractor.isSpendMessage("payment of rs.99 done"))

        assertTrue(extractor.isReceiveMessage("rs.5000 credited to a/c"))
        assertTrue(extractor.isReceiveMessage("refunded rs.120"))
        assertTrue(extractor.isReceiveMessage("cashback of rs.30 added"))
    }

    @Test
    fun `a credit alert carrying a txn reference stays a credit`() {
        // Airtel Payments Bank: "credited" and the weak "txn" both match, and
        // taking spend on any match booked this as -108.00.
        val body = "Airtel Payments Bank a/c is credited with Rs.108.00. " +
            "Txn ID: 078271330612. Call 180023400 for help"
        assertEquals(
            TransactionExtractor.Direction.RECEIVE,
            extractor.resolveDirection(body.lowercase())
        )
    }

    @Test
    fun `an unambiguous marker outranks a weak one in either direction`() {
        assertEquals(
            TransactionExtractor.Direction.RECEIVE,
            extractor.resolveDirection("payment of rs.500 received from john")
        )
        assertEquals(
            TransactionExtractor.Direction.RECEIVE,
            extractor.resolveDirection("rs.2000 credited to your a/c, transferred by acme ltd")
        )
        assertEquals(
            TransactionExtractor.Direction.SPEND,
            extractor.resolveDirection("rs.99 debited, rs.5 added as convenience fee")
        )
    }

    @Test
    fun `when both sides are equally specific the first action wins`() {
        // Both strong: the debit describes this account, the credit the payee.
        assertEquals(
            TransactionExtractor.Direction.SPEND,
            extractor.resolveDirection("rs.500 debited from a/c and credited to beneficiary")
        )
        // Both weak.
        assertEquals(
            TransactionExtractor.Direction.RECEIVE,
            extractor.resolveDirection("rs.100 added to your wallet using upi")
        )
    }

    @Test
    fun `a weak marker alone still gives a direction`() {
        // Demoting "txn" must not stop a bare card alert from registering.
        assertEquals(
            TransactionExtractor.Direction.SPEND,
            extractor.resolveDirection("txn of rs.450 at swiggy on card ending 1234")
        )
        assertNull(extractor.resolveDirection("your account statement is ready"))
    }

    @Test
    fun `txn is only a spend signal in a transactional context`() {
        // The disqualifier guard: "txn limit" must not read as spending.
        assertFalse(extractor.keywordMatchesTransaction("your txn limit is rs.50000", "txn"))
        assertFalse(extractor.keywordMatchesTransaction("txn password updated", "txn"))
        assertFalse(extractor.keywordMatchesTransaction("txn alert settings changed", "txn"))

        assertTrue(extractor.keywordMatchesTransaction("txn of rs.450 at swiggy", "txn"))
    }

    @Test
    fun `a message mixing a txn disqualifier and a real txn still counts`() {
        val body = "txn limit updated. txn of rs.450 completed"
        assertTrue(extractor.keywordMatchesTransaction(body, "txn"))
    }

    // ── Categorisation ───────────────────────────────────────────────────────

    @Test
    fun `merchants map to their category`() {
        assertEquals("Dining", extractor.categorize("paid to swiggy"))
        assertEquals("Transport", extractor.categorize("uber ride payment"))
        assertEquals("Groceries", extractor.categorize("blinkit order"))
        assertEquals("Shopping", extractor.categorize("amazon purchase"))
        assertEquals("Bills", extractor.categorize("electricity bill paid"))
        assertEquals("Entertainment", extractor.categorize("netflix subscription"))
        assertEquals("Health", extractor.categorize("apollo pharmacy"))
    }

    @Test
    fun `unknown merchants fall back to Other`() {
        assertEquals("Other", extractor.categorize("paid to xyz enterprises"))
    }

    @Test
    fun `categorisation is case insensitive`() {
        assertEquals("Dining", extractor.categorize("paid to SWIGGY".lowercase()))
    }

    // ── Real messages from the field ─────────────────────────────────────────

    private val yesBankConsentPromo =
        "Service Alert:\nFunds of INR 58,000.00 on YES BANK Credit Card ending 8535 are " +
                "available and require consent to continue disbursement. " +
                "ccybl.in/YESBNK/MTWjXYX2fV -YES BANK LTD"

    private val canaraUpiDebit =
        "Dear Customer, Acct XXX421 Dr. INR 16,498.87 on 07/07/26 to CRED Club; " +
                "UPI: 655406751992; Bal INR 2,769.96.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"

    @Test
    fun `yes bank consent promo is not a transaction`() {
        // "sent" used to match inside "con-sent-", logging this as a 58,000 spend.
        assertFalse(extractor.isSpendMessage(yesBankConsentPromo.lowercase()))
        assertFalse(extractor.isReceiveMessage(yesBankConsentPromo.lowercase()))
        assertTrue(extractor.isNonTransactional(yesBankConsentPromo))
    }

    @Test
    fun `sent does not match inside consent`() {
        assertFalse(extractor.containsKeyword("require consent to continue", "sent"))
        assertTrue(extractor.containsKeyword("rs.500 sent to john", "sent"))
    }

    @Test
    fun `canara upi debit is tracked`() {
        val lower = canaraUpiDebit.lowercase()

        assertFalse(extractor.isNonTransactional(canaraUpiDebit))
        assertTrue("Dr. marker should read as a spend", extractor.isSpendMessage(lower))
        assertFalse(extractor.isReceiveMessage(lower))
        assertEquals(16498.87, extractor.extractAmountByRegex(canaraUpiDebit)!!, 0.001)
    }

    @Test
    fun `canara debit takes the transaction amount not the trailing balance`() {
        // "Bal INR 2,769.96" appears later in the same message.
        assertEquals(16498.87, extractor.extractAmountByRegex(canaraUpiDebit)!!, 0.001)
    }

    @Test
    fun `dr marker is anchored and does not fire on ordinary words`() {
        assertTrue(extractor.containsKeyword("acct xxx421 dr. inr 16,498.87", "dr."))
        // Substring matching would have hit "address", "hundred", "drive".
        assertFalse(extractor.containsKeyword("sent to address on file", "dr."))
        assertFalse(extractor.containsKeyword("withdrawn at andheri", "dr."))
    }

    // ── Credit card bills ────────────────────────────────────────────────────

    @Test
    fun `statement and due-date alerts are detected as card bills`() {
        assertTrue(extractor.isCreditCardBill("Total amount due Rs.15,000 for your card"))
        assertTrue(extractor.isCreditCardBill("Minimum amount due is Rs.750"))
        assertTrue(extractor.isCreditCardBill("Your statement is generated for card ending 1234"))
        assertTrue(extractor.isCreditCardBill("Credit card bill due date is 20-08-26"))
    }

    @Test
    fun `an ordinary card spend is not a bill`() {
        assertFalse(
            extractor.isCreditCardBill("Rs.450.00 spent at SWIGGY using a/c XX1234 on 16-08-26")
        )
    }
}
