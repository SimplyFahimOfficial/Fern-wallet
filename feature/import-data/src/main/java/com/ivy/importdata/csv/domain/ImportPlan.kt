package com.ivy.importdata.csv.domain

import androidx.compose.runtime.Immutable
import com.ivy.base.legacy.Transaction
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import java.time.LocalDateTime

/**
 * What a CSV import would do, worked out without writing anything. Rows keep their original CSV
 * line number so the user can find them in the file.
 */
@Immutable
data class ImportPlan(
    /** Data rows in the file (the header excluded). */
    val rowsFound: Int,
    val rows: List<PlannedRow>,
    val rejected: List<RejectedRow>,
    /** Every account / category that some accepted row would create. */
    val newAccounts: List<PlannedAccount>,
    val newCategories: List<PlannedCategory>,
)

/** The rows the user kept plus only the accounts and categories those rows reference. */
fun ImportPlan.select(excludedLines: Set<Int>): ImportSelection {
    val selected = rows.filter { it.line !in excludedLines }
    val accountIds = selected.flatMapTo(mutableSetOf()) { it.newAccountIds }
    val categoryIds = selected.mapNotNullTo(mutableSetOf()) { it.newCategory }
    return ImportSelection(
        rows = selected,
        accounts = newAccounts.filter { it.account.id in accountIds },
        categories = newCategories.filter { it.category.id in categoryIds },
    )
}

@Immutable
data class ImportSelection(
    val rows: List<PlannedRow>,
    val accounts: List<PlannedAccount>,
    val categories: List<PlannedCategory>,
)

@Immutable
data class PlannedRow(
    /** 1-based line in the CSV file where the row starts (the header is line 1). */
    val line: Int,
    val transaction: Transaction,
    val date: LocalDateTime,
    val accountName: String,
    val currency: String,
    val toAccountName: String?,
    val toCurrency: String?,
    val categoryName: String?,
    /** Ids of the accounts this row needs that don't exist in the wallet yet. */
    val newAccountIds: Set<AccountId>,
    /** Id of the category this row needs if it doesn't exist in the wallet yet. */
    val newCategory: CategoryId?,
    /** Set when this row looks like one that is already there. Only a hint, never acted on. */
    val duplicate: Duplicate?,
)

/**
 * Same date, type, amount(s), account(s) and title as another transaction. [inFileLine] is the
 * earlier CSV line that matches; null means a transaction already in the wallet.
 */
@Immutable
data class Duplicate(val inFileLine: Int?)

@Immutable
data class RejectedRow(val line: Int, val reason: String, val content: List<String>)

@Immutable
data class PlannedAccount(
    val name: String,
    val currency: String,
    val account: Account,
)

@Immutable
data class PlannedCategory(val name: String, val category: Category)
