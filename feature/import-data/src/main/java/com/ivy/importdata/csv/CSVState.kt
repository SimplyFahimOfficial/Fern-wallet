package com.ivy.importdata.csv

import androidx.compose.runtime.Immutable
import com.ivy.data.backup.ImportResult
import com.ivy.importdata.csv.domain.ImportPlan
import kotlinx.collections.immutable.ImmutableList

data class CSVState(
    val uiState: UIState,
    val columns: CSVRow?,
    val csv: ImmutableList<CSVRow>?,
    val important: ImportantFields?,
    val transfer: TransferFields?,
    val optional: OptionalFields?,
    val continueEnabled: Boolean,
)

sealed interface UIState {
    object Idle : UIState
    data class Processing(val percent: Int) : UIState

    /** The prepared import, waiting for the user. Nothing has been written yet. */
    @Immutable
    data class Review(
        val plan: ImportPlan,
        val excludedLines: Set<Int>,
        /** Why the last confirm didn't go through; the wallet is unchanged and Confirm can be retried. */
        val error: String?,
    ) : UIState

    data class Result(val importResult: ImportResult) : UIState
}

data class ImportantFields(
    val amount: ColumnMapping<Int>,
    val amountStatus: MappingStatus,
    val type: ColumnMapping<TrnTypeMetadata>,
    val typeStatus: MappingStatus,
    val date: ColumnMapping<DateMetadata>,
    val dateStatus: MappingStatus,
    val account: ColumnMapping<Unit>,
    val accountStatus: MappingStatus,
    val accountCurrency: ColumnMapping<Unit>,
    val accountCurrencyStatus: MappingStatus,
)

data class TrnTypeMetadata(
    val income: String,
    val expense: String,
    val transfer: String?,
)

enum class DateMetadata {
    MonthFirst, DateFirst
}

data class TransferFields(
    val toAccount: ColumnMapping<Unit>,
    val toAccountStatus: MappingStatus,
    val toAccountCurrency: ColumnMapping<Unit>,
    val toAccountCurrencyStatus: MappingStatus,
    val toAmount: ColumnMapping<Int>,
    val toAmountStatus: MappingStatus,
)

data class OptionalFields(
    val category: ColumnMapping<Unit>,
    val categoryStatus: MappingStatus,
    val title: ColumnMapping<Unit>,
    val titleStatus: MappingStatus,
    val description: ColumnMapping<Unit>,
    val descriptionStatus: MappingStatus,
)

data class ColumnMapping<M>(
    val ivyColumn: String,
    val helpInfo: String,
    val name: String,
    val index: Int,
    val metadata: M,
    val required: Boolean,
)

data class MappingStatus(
    val sampleValues: List<String>,
    val success: Boolean,
)

@Suppress("DataClassDefaultValues") // only rows read from a file have a line; sample rows don't
data class CSVRow(
    val values: List<String>,
    /** 1-based line of the file where this row starts, 0 if unknown. */
    val line: Int = 0,
)
