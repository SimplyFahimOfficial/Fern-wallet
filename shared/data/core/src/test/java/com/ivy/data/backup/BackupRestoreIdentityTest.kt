package com.ivy.data.backup

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.di.KotlinxSerializationModule
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.legacy.Theme
import com.ivy.base.model.TransactionType
import com.ivy.data.DataObserver
import com.ivy.data.db.dao.fake.FakeAccountDao
import com.ivy.data.db.dao.fake.FakeBudgetDao
import com.ivy.data.db.dao.fake.FakeCategoryDao
import com.ivy.data.db.dao.fake.FakeCreditCardDao
import com.ivy.data.db.dao.fake.FakeDbTransactionRunner
import com.ivy.data.db.dao.fake.FakeLoanDao
import com.ivy.data.db.dao.fake.FakeLoanRecordDao
import com.ivy.data.db.dao.fake.FakePlannedPaymentDao
import com.ivy.data.db.dao.fake.FakeSettingsDao
import com.ivy.data.db.dao.fake.FakeTagAssociationDao
import com.ivy.data.db.dao.fake.FakeTagDao
import com.ivy.data.db.dao.fake.FakeTransactionDao
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.AccountTypeCreditCard
import com.ivy.data.db.entity.BudgetEntity
import com.ivy.data.db.entity.CategoryEntity
import com.ivy.data.db.entity.CreditCardEntity
import com.ivy.data.db.entity.CreditCardStatementEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.IntervalType
import com.ivy.data.model.LoanType
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.mapper.AccountMapper
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.UUID

class BackupRestoreIdentityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = KotlinxSerializationModule.provideJson()
    private val accountDao = FakeAccountDao()
    private val categoryDao = FakeCategoryDao()
    private val transactionDao = FakeTransactionDao()
    private val budgetDao = FakeBudgetDao()
    private val plannedDao = FakePlannedPaymentDao()
    private val loanDao = FakeLoanDao()
    private val loanRecordDao = FakeLoanRecordDao()
    private val cardDao = FakeCreditCardDao()
    private val settingsDao = FakeSettingsDao()
    private val runner = FakeDbTransactionRunner()
    private val snapshots by lazy { PreRestoreSnapshots { tempFolder.root.resolve("pre-restore") } }
    private val tagDao = FakeTagDao()
    private val tagAssociationDao = FakeTagAssociationDao()

    private val useCase by lazy {
        BackupDataUseCase(
            accountDao = accountDao,
            accountMapper = AccountMapper(
                CurrencyRepository(settingsDao, settingsDao, TestDispatchersProvider)
            ),
            accountWriter = accountDao,
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            loanRecordDao = loanRecordDao,
            loanDao = loanDao,
            plannedPaymentRuleDao = plannedDao,
            transactionDao = transactionDao,
            transactionWriter = transactionDao,
            settingsDao = settingsDao,
            categoryWriter = categoryDao,
            settingsWriter = settingsDao,
            budgetWriter = budgetDao,
            loanWriter = loanDao,
            loanRecordWriter = loanRecordDao,
            plannedPaymentRuleWriter = plannedDao,
            context = mockk(relaxed = true),
            sharedPrefs = mockk<SharedPrefs>(relaxed = true),
            json = json,
            dispatchersProvider = TestDispatchersProvider,
            dataObserver = DataObserver(),
            tagsReader = tagDao,
            tagsWriter = tagDao,
            tagAssociationReader = tagAssociationDao,
            tagAssociationWriter = tagAssociationDao,
            creditCardDao = cardDao,
            creditCardWriter = cardDao,
            transactionRunner = runner,
            preRestoreSnapshots = snapshots,
        )
    }

    private fun account(name: String, currency: String = "USD", type: String = "ASSET"): AccountEntity =
        AccountEntity(name = name, currency = currency, color = 0, type = type, id = UUID.randomUUID())

    private fun category(name: String) = CategoryEntity(name = name, color = 0)

    private fun transaction(
        account: AccountEntity,
        amount: Double,
        type: TransactionType = TransactionType.EXPENSE,
        to: AccountEntity? = null,
        toAmount: Double? = null,
        category: CategoryEntity? = null,
        description: String? = null,
    ) = TransactionEntity(
        accountId = account.id,
        type = type,
        amount = amount,
        toAccountId = to?.id,
        toAmount = toAmount,
        categoryId = category?.id,
        description = description,
        dateTime = Instant.parse("2026-01-05T10:00:00Z"),
    )

    private fun backup(data: IvyWalletCompleteData): String = json.encodeToString(data)

    private suspend fun balance(accountId: UUID): Double = transactionDao.findAll().sumOf {
        when {
            it.accountId == accountId && it.type == TransactionType.INCOME -> it.amount
            it.accountId == accountId -> -it.amount
            it.toAccountId == accountId && it.type == TransactionType.TRANSFER -> it.toAmount ?: it.amount
            else -> 0.0
        }
    }

    @Test
    fun `two incoming accounts with the same name keep their own ids, transactions and balances`() = runTest {
        val unrelated = account("Cash").also { accountDao.save(it) }
        val savings1 = account("Savings")
        val savings2 = account("Savings")
        val t1 = transaction(savings1, 10.0, TransactionType.INCOME)
        val t2 = transaction(savings2, 25.0, TransactionType.INCOME)

        val result = useCase.importJson(
            backup(IvyWalletCompleteData(accounts = listOf(savings1, savings2), transactions = listOf(t1, t2)))
        )

        result.failure shouldBe null
        accountDao.findAll().map { it.id } shouldContainExactlyInAnyOrder
            listOf(unrelated.id, savings1.id, savings2.id)
        transactionDao.findById(t1.id)!!.accountId shouldBe savings1.id
        transactionDao.findById(t2.id)!!.accountId shouldBe savings2.id
        balance(savings1.id) shouldBe 10.0
        balance(savings2.id) shouldBe 25.0
    }

    @Test
    fun `a unique compatible name match merges into the wallet record everywhere it is referenced`() = runTest {
        val existing = account("Savings").also { accountDao.save(it) }
        val incoming = account("Savings")
        val other = account("Bank")
        val food = category("Food")
        val existingFood = category("Food").also { categoryDao.save(it) }
        val loan = LoanEntity(name = "Loan", amount = 5.0, type = LoanType.LEND, accountId = incoming.id)
        val record = LoanRecordEntity(
            loanId = loan.id,
            amount = 1.0,
            dateTime = Instant.EPOCH,
            accountId = incoming.id,
        )
        val planned = PlannedPaymentRuleEntity(
            startDate = Instant.EPOCH,
            intervalN = 1,
            intervalType = IntervalType.MONTH,
            oneTime = false,
            type = TransactionType.EXPENSE,
            accountId = incoming.id,
            categoryId = food.id,
        )
        val transfer = transaction(
            incoming,
            5.0,
            TransactionType.TRANSFER,
            to = other,
            toAmount = 5.0,
            category = food,
        )
        val reverse = transaction(other, 2.0, TransactionType.TRANSFER, to = incoming, toAmount = 2.0)
        val budget = BudgetEntity(
            name = "B",
            amount = 1.0,
            categoryIdsSerialized = "${food.id}",
            accountIdsSerialized = "${incoming.id},${other.id}",
            orderId = 0.0,
        )

        useCase.importJson(
            backup(
                IvyWalletCompleteData(
                    accounts = listOf(incoming, other),
                    categories = listOf(food),
                    transactions = listOf(transfer, reverse),
                    budgets = listOf(budget),
                    loans = listOf(loan),
                    loanRecords = listOf(record),
                    plannedPaymentRules = listOf(planned),
                )
            )
        ).failure shouldBe null

        accountDao.findAll().map { it.id } shouldContainExactlyInAnyOrder listOf(existing.id, other.id)
        categoryDao.findAll().map { it.id } shouldBe listOf(existingFood.id)
        transactionDao.findById(transfer.id)!!.let {
            it.accountId shouldBe existing.id
            it.toAccountId shouldBe other.id
            it.categoryId shouldBe existingFood.id
        }
        transactionDao.findById(reverse.id)!!.toAccountId shouldBe existing.id
        budgetDao.findById(budget.id)!!.let {
            it.accountIdsSerialized shouldBe "${existing.id},${other.id}"
            it.categoryIdsSerialized shouldBe "${existingFood.id}"
        }
        loanDao.findById(loan.id)!!.accountId shouldBe existing.id
        loanRecordDao.findAll().single().accountId shouldBe existing.id
        plannedDao.findAll().single().let {
            it.accountId shouldBe existing.id
            it.categoryId shouldBe existingFood.id
        }
    }

    @Test
    fun `card details and statements follow a merged credit card account`() = runTest {
        val existing = account("Card", type = AccountTypeCreditCard).also { accountDao.save(it) }
        val incoming = account("Card", type = AccountTypeCreditCard)
        val card = CreditCardEntity(accountId = incoming.id, openingDateEpochDay = 20_000)
        val statement = CreditCardStatementEntity(
            accountId = incoming.id,
            nominalClosingEpochDay = 20_030,
            issuerStatementBalance = 5_000.0,
        )

        useCase.importJson(
            backup(
                IvyWalletCompleteData(
                    accounts = listOf(incoming), creditCards = listOf(card), creditCardStatements = listOf(statement),
                )
            )
        ).failure shouldBe null

        accountDao.findAll().map { it.id } shouldBe listOf(existing.id)
        cardDao.findAll().map { it.accountId } shouldBe listOf(existing.id)
        cardDao.findAllStatements().map { it.accountId } shouldBe listOf(existing.id)
    }

    @Test
    fun `same-name accounts with a different currency or type stay separate`() = runTest {
        val usd = account("Savings", "USD").also { accountDao.save(it) }
        val card = account("Card", "USD", AccountTypeCreditCard).also { accountDao.save(it) }
        val eur = account("Savings", "EUR")
        val asset = account("Card", "USD")

        useCase.importJson(backup(IvyWalletCompleteData(accounts = listOf(eur, asset)))).failure shouldBe null

        accountDao.findAll().map { it.id } shouldContainExactlyInAnyOrder listOf(usd.id, card.id, eur.id, asset.id)
    }

    @Test
    fun `ambiguous categories are kept under their own ids and unique ones merge`() = runTest {
        val twinA = category("Food").also { categoryDao.save(it) }
        val twinB = category("Food").also { categoryDao.save(it) }
        val oneExisting = category("Rent").also { categoryDao.save(it) }
        val incomingFood = category("Food")
        val incomingRent1 = category("Rent")
        val incomingRent2 = category("Rent")
        val incomingGym = category("Gym")
        val existingGym = category("Gym").also { categoryDao.save(it) }

        useCase.importJson(
            backup(
                IvyWalletCompleteData(
                    categories = listOf(incomingFood, incomingRent1, incomingRent2, incomingGym)
                )
            )
        ).failure shouldBe null

        categoryDao.findAll().map { it.id } shouldContainExactlyInAnyOrder listOf(
            twinA.id, twinB.id, incomingFood.id, // two wallet "Food" records: ambiguous
            oneExisting.id, incomingRent1.id, incomingRent2.id, // two incoming "Rent": ambiguous
            existingGym.id, // unique pair: merged
        )
    }

    @Test
    fun `a UUID inside a note stays literal while real references follow the remapping`() = runTest {
        val existing = account("Savings").also { accountDao.save(it) }
        val incoming = account("Savings")
        val other = account("Bank")
        val note = "moved from ${incoming.id}, see budget ${incoming.id}"
        val transfer = transaction(
            incoming,
            5.0,
            TransactionType.TRANSFER,
            to = other,
            toAmount = 5.0,
            description = note,
        )
        val budget = BudgetEntity(
            name = "Budget for ${incoming.id}",
            amount = 1.0,
            categoryIdsSerialized = null,
            accountIdsSerialized = "${incoming.id}",
            orderId = 0.0,
        )

        useCase.importJson(
            backup(
                IvyWalletCompleteData(
                    accounts = listOf(incoming, other), transactions = listOf(transfer), budgets = listOf(budget),
                )
            )
        ).failure shouldBe null

        transactionDao.findById(transfer.id)!!.let {
            it.description shouldBe note
            it.accountId shouldBe existing.id
        }
        budgetDao.findById(budget.id)!!.let {
            it.name shouldBe "Budget for ${incoming.id}"
            it.accountIdsSerialized shouldBe "${existing.id}"
        }
    }

    @Test
    fun `restoring the same backup twice is stable and the last restore can be undone exactly`() = runTest {
        accountDao.save(account("Cash"))
        val savings1 = account("Savings")
        val savings2 = account("Savings")
        val food = category("Food")
        val data = IvyWalletCompleteData(
            accounts = listOf(savings1, savings2),
            categories = listOf(food),
            transactions = listOf(
                transaction(savings1, 10.0, TransactionType.INCOME, category = food),
                transaction(savings2, 3.0, TransactionType.EXPENSE, category = food),
            ),
        )
        useCase.importJson(backup(data)).failure shouldBe null
        val afterFirst = useCase.generateJsonBackup()
        val balances = listOf(savings1.id, savings2.id).map { balance(it) }

        val second = useCase.importJson(backup(data))

        second.failure shouldBe null
        useCase.generateJsonBackup() shouldBe afterFirst
        accountDao.findAll().size shouldBe 3
        categoryDao.findAll().size shouldBe 1
        transactionDao.findAll().size shouldBe 2
        listOf(savings1.id, savings2.id).map { balance(it) } shouldBe balances

        // Undo goes back to the wallet as it was right before the second restore.
        useCase.undoLastImport() shouldBe true
        useCase.generateJsonBackup() shouldBe afterFirst
    }

    @Test
    fun `a transaction whose account is missing fails the restore without changing the wallet`() = runTest {
        val existing = account("Cash").also { accountDao.save(it) }
        val before = useCase.generateJsonBackup()
        val good = account("Good")
        val missing = account("Never in the backup")
        val runsBefore = runner.runs

        val result = useCase.importJson(
            backup(
                IvyWalletCompleteData(
                    accounts = listOf(good),
                    transactions = listOf(
                        transaction(good, 1.0),
                        transaction(good, 2.0, TransactionType.TRANSFER, to = missing, toAmount = 2.0),
                    ),
                )
            )
        )

        result.failure shouldBe ImportFailure.CORRUPTED
        result.canUndo shouldBe false
        useCase.generateJsonBackup() shouldBe before
        accountDao.findAll().map { it.id } shouldBe listOf(existing.id)
        runner.runs shouldBe runsBefore + 1 // only the read for the backup taken after the failure
        snapshots.latest() shouldBe null
    }

    @Test
    fun `an account that cannot be imported fails the restore when a transaction depends on it`() = runTest {
        accountDao.save(account("Cash"))
        val before = useCase.generateJsonBackup()
        val broken = account("")

        val result = useCase.importJson(
            backup(IvyWalletCompleteData(accounts = listOf(broken), transactions = listOf(transaction(broken, 1.0))))
        )

        result.failure shouldBe ImportFailure.CORRUPTED
        useCase.generateJsonBackup() shouldBe before
    }

    @Test
    fun `a missing currency means the wallet base currency when matching`() = runTest {
        settingsDao.save(SettingsEntity(Theme.DARK, "INR", 0.0, ""))
        val existing = AccountEntity(name = "Savings", currency = null, color = 0).also { accountDao.save(it) }
        val incoming = AccountEntity(name = "Savings", currency = "INR", color = 0)

        useCase.importJson(backup(IvyWalletCompleteData(accounts = listOf(incoming)))).failure shouldBe null

        // A missing currency means the wallet's base currency, so these are the same account.
        accountDao.findAll().map { it.id } shouldBe listOf(existing.id)
    }
}
