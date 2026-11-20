package com.ivy.importdata.csv.domain

import androidx.compose.ui.graphics.toArgb
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ivy.base.legacy.Transaction
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeConverter
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.backup.CSVRow
import com.ivy.data.backup.ImportResult
import com.ivy.data.db.DbTransactionRunner
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.design.IVY_COLOR_PICKER_COLORS_FREE
import com.ivy.importdata.csv.ImportantFields
import com.ivy.importdata.csv.OptionalFields
import com.ivy.importdata.csv.TransferFields
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import com.ivy.legacy.datamodel.toEntity
import com.ivy.legacy.utils.toLowerCaseLocal
import com.ivy.wallet.domain.data.IvyCurrency
import com.ivy.wallet.domain.pure.util.nextOrderNum
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.IvyDark
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import com.ivy.importdata.csv.CSVRow as CSVRowNew

/**
 * Two steps: [prepare] works out what a CSV import would do without touching the database, and
 * [commit] saves the part the user kept in a single database transaction.
 */
class CSVImporterV2 @Inject constructor(
    private val settingsDao: SettingsDao,
    private val transactionRepository: TransactionRepository,
    private val transactionMapper: TransactionMapper,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryRepository: CategoryRepository,
    private val currencyRepository: CurrencyRepository,
    private val accountRepository: AccountRepository,
    private val timeConverter: TimeConverter,
    private val transactionRunner: DbTransactionRunner,
    private val dataObserver: DataObserver,
) {

    /** Reads the wallet, never writes to it. */
    suspend fun prepare(
        csv: List<CSVRowNew>,
        importantFields: ImportantFields,
        transferFields: TransferFields,
        optionalFields: OptionalFields,
        onProgress: suspend (progressPercent: Double) -> Unit,
    ): ImportPlan {
        val rows = csv.drop(1) // drop the header
        val ctx = PlanContext(
            baseCurrency = settingsDao.findFirst().currency,
            accountOrderNum = accountDao.findMaxOrderNum(),
            categoryOrderNum = categoryRepository.findMaxOrderNum(),
        )
        accountDao.findAll().map { it.toLegacyDomain() }.forEach {
            ctx.accounts.putIfAbsent(it.name.toLowerCaseLocal(), it)
        }
        categoryRepository.findAll().forEach {
            ctx.categories.putIfAbsent(it.name.value.toLowerCaseLocal(), it)
        }

        val planned = mutableListOf<PlannedRow>()
        val rejected = mutableListOf<RejectedRow>()
        rows.forEachIndexed { index, row ->
            onProgress(index / rows.size.toDouble())
            // + 2 because the header is line 1 and lines start at 1; only a fallback for rows
            // that didn't come from the file parser.
            val line = row.line.takeIf { it > 0 } ?: (index + 2)
            val result = planRow(
                ctx = ctx,
                line = line,
                row = row,
                importantFields = importantFields,
                transferFields = transferFields,
                optionalFields = optionalFields,
            )
            when (result) {
                is Either.Left -> rejected.add(RejectedRow(line, result.value, row.values))
                is Either.Right -> {
                    // Only now, so a rejected row never leaves an account or category behind.
                    result.value.newAccounts.forEach { ctx.pendingAccounts[it.name.toLowerCaseLocal()] = it }
                    result.value.newCategory?.let { ctx.pendingCategories[it.name.toLowerCaseLocal()] = it }
                    planned.add(result.value.row)
                }
            }
        }

        return ImportPlan(
            rowsFound = rows.size,
            rows = flagDuplicates(planned),
            rejected = rejected,
            newAccounts = ctx.pendingAccounts.values.toList(),
            newCategories = ctx.pendingCategories.values.toList(),
        )
    }

    /**
     * Saves the rows the user kept (not in [excludedLines]) plus the accounts and categories only
     * those rows need, all in one database transaction. Throws if anything fails, and then
     * nothing has been saved.
     */
    suspend fun commit(
        plan: ImportPlan,
        excludedLines: Set<Int>,
        onProgress: suspend (progressPercent: Double) -> Unit,
    ): ImportResult {
        val selection = plan.select(excludedLines)
        try {
            transactionRunner.run {
                accountRepository.saveMany(selection.accounts.map { it.account })
                categoryRepository.saveMany(selection.categories.map { it.category })
                val transactions = selection.rows.map { row ->
                    with(transactionMapper) { row.transaction.toEntity().toDomain() }
                        .getOrElse { error("Line ${row.line} can't be saved: $it") }
                }
                transactions.chunked(SAVE_CHUNK).forEachIndexed { index, chunk ->
                    onProgress(index * SAVE_CHUNK / transactions.size.toDouble())
                    transactionRepository.saveMany(chunk)
                }
            }
        } finally {
            // Also after a rollback: the repositories cache what they saved inside the transaction.
            withContext(NonCancellable) { dataObserver.post(DataWriteEvent.AllDataChange) }
        }

        return ImportResult(
            // Rows the user excluded are neither imported nor failed, so they aren't counted.
            rowsFound = selection.rows.size + plan.rejected.size,
            transactionsImported = selection.rows.size,
            accountsImported = selection.accounts.size,
            categoriesImported = selection.categories.size,
            failedRows = plan.rejected.map { CSVRow(index = it.line, content = it.content) }
                .toImmutableList(),
        )
    }

    private class PlanContext(
        val baseCurrency: String,
        var accountOrderNum: Double?,
        var categoryOrderNum: Double,
    ) {
        val accounts = mutableMapOf<String, Account>()
        val categories = mutableMapOf<String, Category>()
        val pendingAccounts = linkedMapOf<String, PlannedAccount>()
        val pendingCategories = linkedMapOf<String, PlannedCategory>()
        var newCategoryColorIndex = 0
        var newAccountColorIndex = 0
    }

    private class AccountRef(val name: String, val currency: String, val id: UUID?, val pending: Boolean)

    private class RowPlan(
        val row: PlannedRow,
        val newAccounts: List<PlannedAccount>,
        val newCategory: PlannedCategory?,
    )

    private suspend fun planRow(
        ctx: PlanContext,
        line: Int,
        row: CSVRowNew,
        importantFields: ImportantFields,
        transferFields: TransferFields,
        optionalFields: OptionalFields,
    ): Either<String, RowPlan> = either {
        val rawType = row.extractValue(importantFields.type)
        val type = parseTransactionType(rawType, importantFields.type.metadata)
        ensureNotNull(type) { "Can't tell if '$rawType' is income, expense or a transfer" }

        val rawDate = row.extractValue(importantFields.date)
        val dateTime = parseDate(rawDate, importantFields.date.metadata)
        ensureNotNull(dateTime) { "Invalid date '$rawDate'" }

        val rawAmount = row.extractValue(importantFields.amount)
        val amount = parsePositiveAmount(rawAmount, importantFields.amount.metadata)
        ensureNotNull(amount) { "Invalid amount '$rawAmount'" }

        val from = resolveAccount(
            ctx = ctx,
            name = parseAccount(
                row.extractValue(importantFields.account),
                importantFields.account.metadata
            ),
            currencyRaw = parseAccountCurrency(
                row.extractValue(importantFields.accountCurrency),
                importantFields.accountCurrency.metadata
            ),
        )
        ensureNotNull(from) { "Missing account" }

        val to = if (type == TransactionType.TRANSFER) {
            resolveAccount(
                ctx = ctx,
                name = parseToAccount(
                    row.extractValue(transferFields.toAccount),
                    transferFields.toAccount.metadata
                ),
                currencyRaw = parseToAccountCurrency(
                    row.extractValue(transferFields.toAccountCurrency),
                    transferFields.toAccountCurrency.metadata
                ) ?: parseAccountCurrency(
                    row.extractValue(importantFields.accountCurrency),
                    importantFields.accountCurrency.metadata,
                ),
            )
        } else {
            null
        }
        val toAmount = if (type == TransactionType.TRANSFER) {
            ensureNotNull(to) { "Transfer is missing the destination account" }
            ensure(from.name.toLowerCaseLocal() != to.name.toLowerCaseLocal()) {
                "Transfer from and to the same account '${from.name}'"
            }
            val rawToAmount = row.extractValue(transferFields.toAmount)
            if (rawToAmount.isBlank()) {
                // Without a destination amount only a same-currency transfer is unambiguous.
                ensure(from.currency.equals(to.currency, ignoreCase = true)) {
                    "Missing destination amount for a ${from.currency} to ${to.currency} transfer"
                }
                amount
            } else {
                val parsed = parsePositiveAmount(rawToAmount, transferFields.toAmount.metadata)
                ensureNotNull(parsed) { "Invalid destination amount '$rawToAmount'" }
                parsed
            }
        } else {
            null
        }

        // Validated; from here on we only build what the row needs.
        val created = mutableListOf<PlannedAccount>()
        suspend fun idOf(ref: AccountRef): UUID = ref.id
            ?: newAccount(ctx, ref)?.also(created::add)?.account?.id?.value
            ?: raise("Couldn't create the account '${ref.name}'")
        val fromId = idOf(from)
        val toId = to?.let { idOf(it) }

        val categoryName = parseCategory(
            row.extractValue(optionalFields.category),
            optionalFields.category.metadata
        )
        val categoryKey = categoryName?.toLowerCaseLocal()
        val existingCategoryId = categoryKey?.let { ctx.categories[it]?.id?.value }
        val pendingCategory = categoryKey?.let { ctx.pendingCategories[it] }
        val newCategory = if (categoryName != null && existingCategoryId == null && pendingCategory == null) {
            newCategory(ctx, categoryName)
        } else {
            null
        }

        val transaction = Transaction(
            id = UUID.randomUUID(),
            type = type,
            amount = amount.toBigDecimal(),
            accountId = fromId,
            toAccountId = toId,
            toAmount = (toAmount ?: amount).toBigDecimal(),
            dateTime = with(timeConverter) { dateTime.toUTC() },
            dueDate = null,
            categoryId = existingCategoryId ?: (pendingCategory ?: newCategory)?.category?.id?.value,
            title = parseTitle(row.extractValue(optionalFields.title), optionalFields.title.metadata),
            description = parseDescription(
                row.extractValue(optionalFields.description),
                optionalFields.description.metadata
            ),
        )
        RowPlan(
            row = PlannedRow(
                line = line,
                transaction = transaction,
                date = dateTime,
                accountName = from.name,
                currency = from.currency,
                toAccountName = to?.name,
                toCurrency = to?.currency,
                categoryName = categoryName,
                newAccountIds = setOfNotNull(
                    from.id.takeIf { from.pending }?.let(::AccountId),
                    to?.id?.takeIf { to.pending }?.let(::AccountId),
                ) + created.map { it.account.id },
                newCategory = (pendingCategory ?: newCategory)?.category?.id,
                duplicate = null,
            ),
            newAccounts = created,
            newCategory = newCategory,
        )
    }

    /** Parses like the other amounts but also refuses zero, so nothing unsaveable gets through. */
    private fun parsePositiveAmount(raw: String, multiplier: Int): Double? =
        parseAmount(raw, multiplier)?.takeIf { it.isFinite() && it > 0 }

    private fun resolveAccount(ctx: PlanContext, name: String?, currencyRaw: String?): AccountRef? {
        if (name.isNullOrBlank()) return null
        val key = name.toLowerCaseLocal()
        val existing = ctx.accounts[key]
        val pending = ctx.pendingAccounts[key]
        return when {
            existing != null ->
                AccountRef(existing.name, existing.currency ?: ctx.baseCurrency, existing.id, pending = false)

            pending != null ->
                AccountRef(pending.name, pending.currency, pending.account.id.value, pending = true)

            else -> AccountRef(name, mapCurrency(ctx.baseCurrency, currencyRaw), id = null, pending = false)
        }
    }

    private suspend fun newAccount(ctx: PlanContext, ref: AccountRef): PlannedAccount? {
        val name = ref.name.toLowerCaseLocal()
        val color = when {
            name.contains("cash") -> Green
            name.contains("revolut") -> IvyDark
            else -> IVY_COLOR_PICKER_COLORS_FREE.getOrElse(ctx.newAccountColorIndex++) {
                ctx.newAccountColorIndex = 0
                IVY_COLOR_PICKER_COLORS_FREE.first()
            }
        }.toArgb()
        val orderNum = ctx.accountOrderNum.nextOrderNum().also { ctx.accountOrderNum = it }
        val account = Account(
            name = ref.name,
            currency = ref.currency,
            color = color,
            orderNum = orderNum,
        )
        val domain = account.toDomainAccount(currencyRepository).getOrNull() ?: return null
        return PlannedAccount(ref.name, ref.currency, domain)
    }

    private fun newCategory(ctx: PlanContext, name: String): PlannedCategory? {
        val color = IVY_COLOR_PICKER_COLORS_FREE.getOrElse(ctx.newCategoryColorIndex++) {
            ctx.newCategoryColorIndex = 0
            IVY_COLOR_PICKER_COLORS_FREE.first()
        }.toArgb()
        val category = either {
            Category(
                id = CategoryId(UUID.randomUUID()),
                name = NotBlankTrimmedString.from(name).bind(),
                color = ColorInt(color),
                icon = null,
                orderNum = ctx.categoryOrderNum.nextOrderNum().also { ctx.categoryOrderNum = it },
            )
        }.getOrNull() ?: return null
        return PlannedCategory(name, category)
    }

    private fun mapCurrency(
        baseCurrency: String,
        currencyCode: String?
    ): String {
        return try {
            if (currencyCode != null && currencyCode.isNotBlank()) {
                IvyCurrency.fromCode(currencyCode)?.code ?: baseCurrency
            } else {
                baseCurrency
            }
        } catch (e: Exception) {
            baseCurrency
        }
    }

    /**
     * Marks rows that match a transaction already in the wallet or an earlier row of the file.
     * Identical rows can be legitimate (two coffees), so this only informs the user.
     */
    private suspend fun flagDuplicates(rows: List<PlannedRow>): List<PlannedRow> {
        if (rows.isEmpty()) return rows
        val inWallet = mutableSetOf<DuplicateKey>()
        // Only an identical date and time can match, so look at the wallet around the CSV's dates
        // instead of everything between its first and last row.
        rows.mapNotNull { it.transaction.dateTime }.sorted().windows().forEach { (from, to) ->
            transactionDao.findAllBetween(from, to).mapTo(inWallet) {
                DuplicateKey(
                    type = it.type,
                    account = AccountId(it.accountId),
                    toAccount = it.toAccountId?.let(::AccountId),
                    amount = it.amount.normalized(),
                    // A transfer without a destination amount credits the source amount.
                    toAmount = if (it.toAccountId != null) (it.toAmount ?: it.amount).normalized() else null,
                    dateTime = it.dateTime,
                    title = it.title.normalized(),
                )
            }
        }
        val firstLineInFile = mutableMapOf<DuplicateKey, Int>()
        return rows.map { row ->
            val t = row.transaction
            val key = DuplicateKey(
                type = t.type,
                account = AccountId(t.accountId),
                toAccount = t.toAccountId?.let(::AccountId),
                amount = t.amount.normalized(),
                toAmount = if (t.toAccountId != null) t.toAmount.normalized() else null,
                dateTime = t.dateTime,
                title = t.title.normalized(),
            )
            val earlier = firstLineInFile.putIfAbsent(key, row.line)
            when {
                key in inWallet -> row.copy(duplicate = Duplicate(inFileLine = null))
                earlier != null -> row.copy(duplicate = Duplicate(inFileLine = earlier))
                else -> row
            }
        }
    }

    /** Sorted instants as ranges; a gap longer than [DUPLICATE_WINDOW_GAP] starts a new range. */
    private fun List<Instant>.windows(): List<Pair<Instant, Instant>> {
        val windows = mutableListOf<Pair<Instant, Instant>>()
        for (instant in this) {
            val last = windows.lastOrNull()
            if (last != null && Duration.between(last.second, instant) <= DUPLICATE_WINDOW_GAP) {
                windows[windows.lastIndex] = last.first to instant
            } else {
                windows += instant to instant
            }
        }
        return windows
    }

    private data class DuplicateKey(
        val type: TransactionType,
        val account: AccountId,
        val toAccount: AccountId?,
        val amount: BigDecimal,
        val toAmount: BigDecimal?,
        val dateTime: Instant?,
        val title: String,
    )

    // shortcut: amounts closer than 1e-8 count as equal (enough for BTC), not the full ETH precision.
    private fun Double.normalized(): BigDecimal = BigDecimal.valueOf(this).normalized()

    private fun BigDecimal.normalized(): BigDecimal = setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()

    private fun String?.normalized(): String = this?.trim()?.lowercase().orEmpty()

    private companion object {
        const val SAVE_CHUNK = 500
        const val AMOUNT_SCALE = 8
        val DUPLICATE_WINDOW_GAP: Duration = Duration.ofDays(31)
    }
}
