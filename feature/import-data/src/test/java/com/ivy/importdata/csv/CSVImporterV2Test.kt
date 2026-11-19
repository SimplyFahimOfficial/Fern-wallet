package com.ivy.importdata.csv

import com.ivy.base.model.TransactionType
import com.ivy.importdata.csv.domain.Duplicate
import com.ivy.legacy.domain.pure.transaction.AccountValueFunctions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

class CSVImporterV2Test {
    private val fixture = ImportFixture()

    private suspend fun balanceOf(accountId: UUID): BigDecimal =
        fixture.transactionDao.findAll()
            .map { with(fixture.transactionMapper) { it.toDomain() }.getOrNull()!! }
            .fold(BigDecimal.ZERO) { sum, trn -> sum + AccountValueFunctions.balance(trn, accountId) }

    // region Transfers
    @Test
    fun `USD 100 to INR 8,300 debits USD 100 and credits INR 8,300`() = runTest {
        val wallet = fixture.givenAccount("Wallet", "USD")
        val bank = fixture.givenAccount("Bank", "INR")

        val plan = fixture.prepare(
            row(type = "Transfer", account = "Wallet", amount = "100", toAccount = "Bank", toAmount = "8,300")
        )
        fixture.importer.commit(plan, emptySet()) {}

        val saved = fixture.transactionDao.findAll().single()
        saved.type shouldBe TransactionType.TRANSFER
        saved.accountId shouldBe wallet
        saved.amount shouldBe 100.0
        saved.toAccountId shouldBe bank
        saved.toAmount shouldBe 8300.0
        balanceOf(wallet).compareTo(BigDecimal(-100)) shouldBe 0
        balanceOf(bank).compareTo(BigDecimal(8300)) shouldBe 0
    }

    @Test
    fun `same-currency transfer without a destination amount uses the source amount`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        fixture.givenAccount("Savings", "USD")

        val plan = fixture.prepare(
            row(type = "Transfer", account = "Wallet", amount = "40", toAccount = "Savings")
        )

        plan.rejected.shouldBeEmpty()
        plan.rows.single().transaction.toAmount.compareTo(BigDecimal(40)) shouldBe 0
    }

    @Test
    fun `cross-currency transfer without a destination amount is rejected and creates no account`() = runTest {
        fixture.givenAccount("Wallet", "USD")

        val plan = fixture.prepare(
            row(type = "Transfer", account = "Wallet", amount = "40", toAccount = "Rupees", toCurrency = "INR")
        )

        plan.rows.shouldBeEmpty()
        plan.rejected.single().line shouldBe 2
        plan.rejected.single().reason shouldContain "USD to INR"
        plan.newAccounts.shouldBeEmpty()
    }

    @Test
    fun `a nonblank invalid destination amount is rejected with its line`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        fixture.givenAccount("Bank", "INR")

        val plan = fixture.prepare(
            row(type = "Expense", account = "Wallet", amount = "1"),
            row(type = "Transfer", account = "Wallet", amount = "40", toAccount = "Bank", toAmount = "n/a"),
        )

        plan.rows.map { it.line } shouldBe listOf(2)
        plan.rejected.single().line shouldBe 3
        plan.rejected.single().reason shouldContain "n/a"
    }

    @Test
    fun `a transfer to the same account is rejected`() = runTest {
        fixture.givenAccount("Wallet", "USD")

        val plan = fixture.prepare(
            row(type = "Transfer", account = "Wallet", amount = "5", toAccount = "wallet")
        )

        plan.rows.shouldBeEmpty()
        plan.rejected.single().reason shouldContain "same account"
    }
    // endregion

    @Test
    fun `expenses and income import as before`() = runTest {
        val wallet = fixture.givenAccount("Wallet", "USD")

        val plan = fixture.prepare(
            row(type = "Expense", account = "Wallet", amount = "-12.5", category = "Food", title = "Lunch"),
            row(type = "Income", account = "Wallet", amount = "100"),
        )
        val result = fixture.importer.commit(plan, emptySet()) {}

        result.transactionsImported shouldBe 2
        result.categoriesImported shouldBe 1
        balanceOf(wallet).compareTo(BigDecimal("87.5")) shouldBe 0
        fixture.transactionDao.findAll().first { it.title == "Lunch" }.amount shouldBe 12.5
    }

    @Test
    fun `nine valid rows and one invalid commit exactly nine and report the rejected line`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        val rows = (1..10).map {
            row(
                date = "%02d/01/2026 10:00".format(it),
                type = "Expense",
                account = "Wallet",
                amount = if (it == 7) "oops" else "$it",
            )
        }

        val plan = fixture.prepare(*rows.toTypedArray())
        plan.rows shouldHaveSize 9
        plan.rejected.map { it.line } shouldBe listOf(8)

        val result = fixture.importer.commit(plan, emptySet()) {}
        fixture.transactionDao.findAll() shouldHaveSize 9
        result.transactionsImported shouldBe 9
        result.failedRows.map { it.index } shouldBe listOf(8)
    }

    // region Accounts and categories
    @Test
    fun `preparing and cancelling writes nothing`() = runTest {
        fixture.prepare(
            row(type = "Expense", account = "Brand New", currency = "EUR", amount = "3", category = "Fresh")
        )

        fixture.accountNames().shouldBeEmpty()
        fixture.categoryDao.findAll(false).shouldBeEmpty()
        fixture.transactionDao.findAll().shouldBeEmpty()
        fixture.runner.runs shouldBe 0
    }

    @Test
    fun `excluding the only row that needs a new account creates no account`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        val plan = fixture.prepare(
            row(type = "Expense", account = "Wallet", amount = "3"),
            row(type = "Expense", account = "Brand New", amount = "4", category = "Fresh"),
        )
        plan.newAccounts shouldHaveSize 1

        val result = fixture.importer.commit(plan, excludedLines = setOf(3)) {}

        fixture.accountNames() shouldBe listOf("Wallet")
        fixture.categoryDao.findAll(false).shouldBeEmpty()
        result.accountsImported shouldBe 0
        result.categoriesImported shouldBe 0
        result.transactionsImported shouldBe 1
    }

    @Test
    fun `a new account used by two rows is created once and only while a row needs it`() = runTest {
        val plan = fixture.prepare(
            row(type = "Expense", account = "Brand New", amount = "4"),
            row(type = "Expense", account = "brand new", amount = "5"),
        )
        plan.newAccounts shouldHaveSize 1

        fixture.importer.commit(plan, excludedLines = setOf(2)) {}
        fixture.accountNames() shouldBe listOf("Brand New")
        fixture.transactionDao.findAll() shouldHaveSize 1
    }
    // endregion

    // region Duplicates
    @Test
    fun `identical purchases are flagged but both can be kept`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        val coffee = row(type = "Expense", account = "Wallet", amount = "3", title = "Coffee")

        val plan = fixture.prepare(coffee, coffee)

        plan.rows[0].duplicate shouldBe null
        plan.rows[1].duplicate?.inFileLine shouldBe 2
        fixture.importer.commit(plan, emptySet()) {}
        fixture.transactionDao.findAll() shouldHaveSize 2
    }

    @Test
    fun `a row matching a transaction already in the wallet is flagged`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        val coffee = row(type = "Expense", account = "Wallet", amount = "3", title = "Coffee")
        fixture.importer.commit(fixture.prepare(coffee), emptySet()) {}

        val plan = fixture.prepare(coffee)

        plan.rows.single().duplicate shouldBe Duplicate(inFileLine = null)
    }
    // endregion

    // region Failure and retry
    @Test
    fun `a failure halfway leaves the wallet unchanged and a retry imports once`() = runTest {
        fixture.givenAccount("Wallet", "USD")
        val plan = fixture.prepare(
            *(1..4).map {
                row(
                    date = "0$it/01/2026 10:00",
                    type = "Expense",
                    account = "Wallet",
                    amount = "$it",
                    category = "Food",
                )
            }.toTypedArray(),
            row(type = "Expense", account = "Brand New", amount = "9"),
        )

        fixture.failNextTransactionSave()
        shouldThrow<IOException> { fixture.importer.commit(plan, emptySet()) {} }

        fixture.accountNames() shouldBe listOf("Wallet")
        fixture.categoryDao.findAll(false).shouldBeEmpty()
        fixture.transactionDao.findAll().shouldBeEmpty()

        fixture.failNextTransactionSave(false)
        val result = fixture.importer.commit(plan, emptySet()) {}

        fixture.transactionDao.findAll() shouldHaveSize 5
        fixture.accountNames() shouldBe listOf("Wallet", "Brand New")
        fixture.categoryDao.findAll(false) shouldHaveSize 1
        result.transactionsImported shouldBe 5
        result.accountsImported shouldBe 1
        result.categoriesImported shouldBe 1
    }
    // endregion
}
