package com.myapp.expensetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.myapp.expensetracker.SavingsPot

/**
 * Entry sheets for the hand-kept ledgers.
 *
 * Amount fields accept only well-formed decimals as you type, and every Save
 * button stays disabled until the entry would actually be valid — a blank or
 * zero amount silently recorded would corrupt a balance the user is relying on.
 */

/** True when [text] parses to a positive amount. */
private fun parsedAmount(text: String): Double? =
    text.toDoubleOrNull()?.takeIf { it > 0.0 }

/** Accepts digits and at most one decimal point, so the field can't hold junk. */
private fun isAmountInput(text: String): Boolean =
    text.isEmpty() || text.toDoubleOrNull() != null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonSheet(
    onDismiss: () -> Unit,
    onAdd: (name: String, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            SheetTitle("Add person")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onAdd(name, note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPotSheet(
    onDismiss: () -> Unit,
    onAdd: (name: String, target: Double?, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            SheetTitle("New savings pot")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Emergency fund, Nifty 50 SIP…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = target,
                onValueChange = { if (isAmountInput(it)) target = it },
                label = { Text("Goal (optional)") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onAdd(name, parsedAmount(target), note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Records money handed over. [pots] drives the optional "came out of" picker;
 * when it's empty the picker is hidden entirely rather than shown empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLoanSheet(
    personName: String,
    pots: List<SavingsPot>,
    onDismiss: () -> Unit,
    onAdd: (amount: Double, reason: String, fromPotId: Long?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var fromPotId by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            SheetTitle("Lend to $personName")
            OutlinedTextField(
                value = amount,
                onValueChange = { if (isAmountInput(it)) amount = it },
                label = { Text("Amount") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("What for") },
                placeholder = { Text("Hospital bill, school fees…") },
                modifier = Modifier.fillMaxWidth()
            )

            if (pots.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Came out of (optional)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pots, key = { it.id }) { pot ->
                        FilterChip(
                            selected = fromPotId == pot.id,
                            // Tapping the selected pot clears it, so an
                            // attribution can be undone without reopening.
                            onClick = {
                                fromPotId = if (fromPotId == pot.id) null else pot.id
                            },
                            label = { Text(pot.name) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    parsedAmount(amount)?.let { onAdd(it, reason, fromPotId) }
                },
                enabled = parsedAmount(amount) != null && reason.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Record loan") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Records money coming back. [outstanding] is offered as a one-tap default,
 * since settling in full is the common case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepaymentSheet(
    outstanding: Double,
    onDismiss: () -> Unit,
    onAdd: (amount: Double, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            SheetTitle("Record repayment")
            Text(
                "${formatLedgerAmount(outstanding)} outstanding",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { if (isAmountInput(it)) amount = it },
                label = { Text("Amount received") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            if (outstanding > 0.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { amount = formatPlainAmount(outstanding) },
                        label = { Text("Full ${formatLedgerAmount(outstanding)}") }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { parsedAmount(amount)?.let { onAdd(it, note) } },
                enabled = parsedAmount(amount) != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContributionSheet(
    potName: String,
    onDismiss: () -> Unit,
    onAdd: (amount: Double, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            SheetTitle("Add to $potName")
            OutlinedTextField(
                value = amount,
                onValueChange = { if (isAmountInput(it)) amount = it },
                label = { Text("Amount") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                placeholder = { Text("September instalment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { parsedAmount(amount)?.let { onAdd(it, note) } },
                enabled = parsedAmount(amount) != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Unformatted, so tapping the "full amount" chip yields a parseable field value. */
private fun formatPlainAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount)

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(16.dp))
}
