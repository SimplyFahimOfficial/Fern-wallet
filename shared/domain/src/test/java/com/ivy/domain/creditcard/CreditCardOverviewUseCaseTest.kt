package com.ivy.domain.creditcard

import com.ivy.base.time.TimeConverter
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.AccountType
import com.ivy.data.model.creditcard.CardAppearance
import com.ivy.data.model.creditcard.CardStatus
import com.ivy.data.model.creditcard.CreditCard
import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.data.model.creditcard.StatementOverride
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CreditCardRepository
import com.ivy.data.repository.TransactionRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CreditCardOverviewUseCaseTest {
    private val cardId = AccountId(UUID(0, 1))
    private val nominal = LocalDate.of(2026, 1, 15)
    private val today = LocalDate.of(2026, 1, 25)

    private val card = CreditCard(
        accountId = cardId, bankId = null, customBankName = null, productId = null, productName = null,
        network = null, lastFour = null, creditLimit = null, statementDay = 15,
        dueDateRule = DueDateRule.DaysAfterStatement(20), openingOutstanding = BigDecimal.ZERO,
        openingDate = LocalDate.of(2026, 1, 1), status = CardStatus.ACTIVE, appearance = CardAppearance.FERN,
        remindersEnabled = true, reminderDaysBefore = 3,
    )
    private val account = Account(
        id = cardId,
        name = NotBlankTrimmedString.unsafe("Card"),
        asset = AssetCode.unsafe("INR"),
        color = ColorInt(0),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
        type = AccountType.CREDIT_CARD,
    )

    private fun override(closing: String?, due: String?, balance: String? = null) = StatementOverride(
        accountId = cardId,
        nominalClosingDate = nominal,
        actualClosingDate = closing?.let(LocalDate::parse),
        actualDueDate = due?.let(LocalDate::parse),
        issuerStatementBalance = balance?.let(::BigDecimal),
        minimumDue = null,
    )

    private fun useCase(saved: List<StatementOverride>): CreditCardOverviewUseCase {
        val accounts = mockk<AccountRepository>()
        val cards = mockk<CreditCardRepository>()
        val transactions = mockk<TransactionRepository>()
        coEvery { accounts.findById(cardId) } returns account
        coEvery { cards.findByAccountId(cardId) } returns card
        coEvery { cards.findStatementOverrides(cardId) } returns saved
        coEvery { transactions.findAllByAccountAndBetween(any(), any(), any()) } returns emptyList()
        coEvery { transactions.findAllToAccountAndBetween(any(), any(), any()) } returns emptyList()
        val time = mockk<TimeProvider> {
            io.mockk.every { utcNow() } returns Instant.parse("2026-01-25T12:00:00Z")
            io.mockk.every { localDateNow() } returns today
        }
        return CreditCardOverviewUseCase(accounts, cards, transactions, time, mockk<TimeConverter>())
    }

    @Test
    fun `the preview replaces the saved override of that statement and nothing else is saved`() = runTest {
        val useCase = useCase(saved = listOf(override(closing = "2026-01-17", due = null, balance = "100")))

        val preview = useCase.previewStatement(cardId, override(closing = "2026-01-20", due = "2026-01-30"))!!

        preview.closingDate shouldBe LocalDate.of(2026, 1, 20)
        preview.dueDate shouldBe LocalDate.of(2026, 1, 30)
        preview.issuerStatementBalance shouldBe null
        useCase.load(cardId)!!.overview.statements.single().closingDate shouldBe LocalDate.of(2026, 1, 17)
    }

    @Test
    fun `the preview shows the closing date the calculator really uses`() = runTest {
        val preview = useCase(saved = emptyList())
            .previewStatement(cardId, override(closing = "2025-12-10", due = "2025-12-12"))!!

        preview.closingDate shouldBe nominal
        preview.dueDate shouldBe LocalDate.of(2025, 12, 12)
    }
}
