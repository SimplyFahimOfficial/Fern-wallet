package com.ivy.importdata.csv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.base.model.TransactionType
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.colorAs
import com.ivy.importdata.csv.domain.PlannedRow
import com.ivy.importdata.csv.domain.select
import java.math.BigDecimal
import java.text.DecimalFormat

/** The prepared import. Nothing is written until Confirm; Back and Cancel leave the wallet as it is. */
@Composable
internal fun CSVReviewUI(
    review: UIState.Review,
    onEvent: (CSVEvent) -> Unit,
) {
    val plan = review.plan
    val selection = plan.select(review.excludedLines)
    // Back steps to the mapping instead of leaving, so a stale review is never kept around.
    BackHandler { onEvent(CSVEvent.BackToMapping) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        ) {
            item(key = "summary") {
                Text(text = "Review the import", style = UI.typo.h2)
                Spacer8()
                Text(
                    text = "${plan.rows.size} accepted (${selection.rows.size} selected), " +
                        "${plan.rejected.size} rejected",
                    style = UI.typo.b1,
                )
                Text(
                    text = "Nothing is saved until you confirm.",
                    style = UI.typo.c.colorAs(UI.colors.gray),
                )
                if (review.error != null) {
                    Spacer8()
                    Text(text = review.error, style = UI.typo.b2.colorAs(UI.colors.red))
                }
            }

            if (selection.accounts.isNotEmpty() || selection.categories.isNotEmpty()) {
                item(key = "created") {
                    Spacer8()
                    Text(text = "Will be created", style = UI.typo.b1)
                    selection.accounts.forEach {
                        Text(text = "Account: ${it.name} (${it.currency})", style = UI.typo.nB2)
                    }
                    selection.categories.forEach {
                        Text(text = "Category: ${it.name}", style = UI.typo.nB2)
                    }
                }
            }

            if (plan.rejected.isNotEmpty()) {
                item(key = "rejected_title") {
                    Spacer8()
                    Text(text = "Rejected rows (not imported)", style = UI.typo.b1)
                }
                items(plan.rejected, key = { "rejected_${it.line}" }) {
                    Text(
                        text = "Line ${it.line}: ${it.reason}",
                        style = UI.typo.nB2.colorAs(UI.colors.red),
                    )
                }
            }

            item(key = "accepted_title") {
                Spacer8()
                Text(text = "Accepted rows", style = UI.typo.b1)
            }
            items(plan.rows, key = { "row_${it.line}" }) { row ->
                PlannedRowItem(
                    row = row,
                    selected = row.line !in review.excludedLines,
                    onToggle = { onEvent(CSVEvent.ToggleRow(row.line)) },
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selection.rows.isNotEmpty(),
                onClick = { onEvent(CSVEvent.ConfirmImport) },
            ) {
                Text(text = "Import ${selection.rows.size} transactions")
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onEvent(CSVEvent.BackToMapping) },
                ) {
                    Text(text = "Change mapping")
                }
                Spacer8(horizontal = true)
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onEvent(CSVEvent.CancelImport) },
                ) {
                    Text(text = "Cancel")
                }
            }
        }
    }
}

@Composable
private fun PlannedRowItem(
    row: PlannedRow,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column {
            Text(
                text = "Line ${row.line} · ${row.date} · ${row.transaction.type.label()}",
                style = UI.typo.nB2,
                fontWeight = FontWeight.Bold,
            )
            Text(text = row.describe(), style = UI.typo.nB2)
            val details = listOfNotNull(
                row.transaction.title,
                row.categoryName?.let { "Category: $it" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(text = details, style = UI.typo.c.colorAs(UI.colors.gray))
            }
            row.duplicate?.let {
                val other = it.inFileLine?.let { line -> "line $line of this file" }
                    ?: "a transaction already in your wallet"
                Text(
                    text = "Possible duplicate of $other (same date, type, amount, account and title)",
                    style = UI.typo.c.colorAs(UI.colors.orange),
                )
            }
        }
    }
}

private fun TransactionType.label(): String = when (this) {
    TransactionType.INCOME -> "Income"
    TransactionType.EXPENSE -> "Expense"
    TransactionType.TRANSFER -> "Transfer"
}

/** "Wallet: USD 100" or, for a transfer, "Wallet → Bank: USD 100 → INR 8,300". */
private fun PlannedRow.describe(): String {
    val amount = "$currency ${transaction.amount.format()}"
    return if (toAccountName != null) {
        "$accountName → $toAccountName: $amount → $toCurrency ${transaction.toAmount.format()}"
    } else {
        "$accountName: $amount"
    }
}

private fun BigDecimal.format(): String = DecimalFormat("#,##0.##").format(this)
