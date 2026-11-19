package com.ivy.importdata.csv

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.legacy.Theme
import com.ivy.base.time.TimeConverter
import com.ivy.data.DataObserver
import com.ivy.data.db.DbTransactionRunner
import com.ivy.data.db.dao.fake.FakeAccountDao
import com.ivy.data.db.dao.fake.FakeCategoryDao
import com.ivy.data.db.dao.fake.FakeSettingsDao
import com.ivy.data.db.dao.fake.FakeTransactionDao
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.RepositoryMemoFactory
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.AccountMapper
import com.ivy.data.repository.mapper.CategoryMapper
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.importdata.csv.domain.CSVImporterV2
import com.ivy.importdata.csv.domain.ImportPlan
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** Header of the rows built by [row]. */
val HEADER = listOf(
    "Date", "Type", "Account", "Currency", "Amount", "To Account", "To Currency", "To Amount",
    "Category", "Title",
)

fun row(
    date: String = "05/01/2026 10:00",
    type: String,
    account: String,
    currency: String = "",
    amount: String,
    toAccount: String = "",
    toCurrency: String = "",
    toAmount: String = "",
    category: String = "",
    title: String = "",
): List<String> = listOf(date, type, account, currency, amount, toAccount, toCurrency, toAmount, category, title)

/** Header is line 1, like in a real file. */
fun csv(vararg rows: List<String>): List<CSVRow> =
    (listOf(HEADER) + rows).mapIndexed { index, values -> CSVRow(values, line = index + 1) }

private fun <M> mapping(index: Int, metadata: M): ColumnMapping<M> = ColumnMapping(
    ivyColumn = "",
    helpInfo = "",
    name = "",
    index = index,
    metadata = metadata,
    required = false,
)

private val noStatus = MappingStatus(emptyList(), false)

val important = ImportantFields(
    amount = mapping(4, 1),
    amountStatus = noStatus,
    type = mapping(1, TrnTypeMetadata(income = "", expense = "", transfer = null)),
    typeStatus = noStatus,
    date = mapping(0, DateMetadata.DateFirst),
    dateStatus = noStatus,
    account = mapping(2, Unit),
    accountStatus = noStatus,
    accountCurrency = mapping(3, Unit),
    accountCurrencyStatus = noStatus,
)
val transfer = TransferFields(
    toAccount = mapping(5, Unit),
    toAccountStatus = noStatus,
    toAccountCurrency = mapping(6, Unit),
    toAccountCurrencyStatus = noStatus,
    toAmount = mapping(7, 1),
    toAmountStatus = noStatus,
)
val optional = OptionalFields(
    category = mapping(8, Unit),
    categoryStatus = noStatus,
    title = mapping(9, Unit),
    titleStatus = noStatus,
    description = mapping(-1, Unit),
    descriptionStatus = noStatus,
)

/** Fails the n-th batch of transactions after saving half of it, like a write dying mid-way. */
class FlakyTransactionDao(private val dao: FakeTransactionDao) :
    TransactionDao by dao, WriteTransactionDao by dao {
    var failNextSave = false

    override suspend fun saveMany(values: List<TransactionEntity>) {
        if (failNextSave) {
            dao.saveMany(values.take(values.size / 2))
            throw IOException("injected failure")
        }
        dao.saveMany(values)
    }
}

/** Undoes what a failed block saved, which is what a real database transaction does. */
class RollbackRunner(private val fixture: ImportFixture) : DbTransactionRunner {
    var runs = 0
    override suspend fun <T> run(block: suspend () -> T): T {
        runs++
        val accounts = fixture.accountDao.findAll().map { it.id }.toSet()
        val categories = fixture.categoryDao.findAll(false).map { it.id }.toSet()
        val transactions = fixture.transactionDao.findAll().map { it.id }.toSet()
        return try {
            block()
        } catch (e: Throwable) {
            fixture.accountDao.findAll().filter { it.id !in accounts }
                .forEach { fixture.accountDao.deleteById(it.id) }
            fixture.categoryDao.findAll(false).filter { it.id !in categories }
                .forEach { fixture.categoryDao.deleteById(it.id) }
            fixture.transactionDao.findAll().filter { it.id !in transactions }
                .forEach { fixture.transactionDao.deleteById(it.id) }
            throw e
        }
    }
}

class ImportFixture(baseCurrency: String = "USD") {
    val accountDao = FakeAccountDao()
    val categoryDao = FakeCategoryDao()
    val transactionDao = FakeTransactionDao()
    private val flaky = FlakyTransactionDao(transactionDao)
    private val settingsDao = FakeSettingsDao().apply {
        kotlinx.coroutines.runBlocking {
            save(SettingsEntity(Theme.DARK, baseCurrency, 0.0, ""))
        }
    }
    val dataObserver = DataObserver()
    val runner = RollbackRunner(this)

    private val memos = RepositoryMemoFactory(dataObserver, TestDispatchersProvider)
    private val accountRepository = AccountRepository(
        mapper = AccountMapper(CurrencyRepository(settingsDao, settingsDao, TestDispatchersProvider)),
        accountDao = accountDao,
        writeAccountDao = accountDao,
        dispatchersProvider = TestDispatchersProvider,
        memoFactory = memos,
        mediaFiles = mockk(relaxed = true),
    )
    val transactionMapper = TransactionMapper(accountRepository)

    val importer = CSVImporterV2(
        settingsDao = settingsDao,
        transactionRepository = TransactionRepository(
            mapper = transactionMapper,
            transactionDao = flaky,
            writeTransactionDao = flaky,
            dispatchersProvider = TestDispatchersProvider,
            tagRepository = mockk(relaxed = true),
        ),
        transactionMapper = transactionMapper,
        transactionDao = flaky,
        accountDao = accountDao,
        categoryRepository = CategoryRepository(
            mapper = CategoryMapper(),
            writeCategoryDao = categoryDao,
            categoryDao = categoryDao,
            dispatchersProvider = TestDispatchersProvider,
            memoFactory = memos,
        ),
        currencyRepository = CurrencyRepository(settingsDao, settingsDao, TestDispatchersProvider),
        accountRepository = accountRepository,
        timeConverter = object : TimeConverter {
            override fun Instant.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
            override fun Instant.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()
            override fun Instant.toLocalTime(): LocalTime = toLocalDateTime().toLocalTime()
            override fun LocalDateTime.toUTC(): Instant = toInstant(ZoneOffset.UTC)
        },
        transactionRunner = runner,
        dataObserver = dataObserver,
    )

    fun failNextTransactionSave(fail: Boolean = true) {
        flaky.failNextSave = fail
    }

    suspend fun givenAccount(name: String, currency: String): UUID {
        val id = UUID.randomUUID()
        accountDao.save(
            AccountEntity(name = name, currency = currency, color = 0, id = id)
        )
        return id
    }

    suspend fun prepare(vararg rows: List<String>): ImportPlan =
        importer.prepare(csv(*rows), important, transfer, optional) {}

    suspend fun accountNames(): List<String> = accountDao.findAll().map { it.name }
}
