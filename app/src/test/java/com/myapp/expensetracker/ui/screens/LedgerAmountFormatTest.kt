package com.myapp.expensetracker.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * formatPlainAmount seeds amount fields that must parse back with
 * toDoubleOrNull(), including when a transaction's amount is carried into a
 * ledger form. It has to ignore the device locale to do that.
 */
class LedgerAmountFormatTest {

    private lateinit var original: Locale

    @Before
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `whole rupees drop the decimals`() {
        assertEquals("3000", formatPlainAmount(3000.0))
    }

    @Test
    fun `paise keep two places`() {
        assertEquals("108.50", formatPlainAmount(108.5))
    }

    @Test
    fun `a comma-decimal locale still produces a parseable field value`() {
        // German formats 12.5 as "12,50"; the field would then refuse to save.
        Locale.setDefault(Locale.GERMANY)

        val text = formatPlainAmount(12.5)

        assertEquals("12.50", text)
        assertEquals(12.5, text.toDouble(), 0.0)
    }
}
