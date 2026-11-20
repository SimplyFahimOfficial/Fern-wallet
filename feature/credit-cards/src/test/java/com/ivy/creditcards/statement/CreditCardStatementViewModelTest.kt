package com.ivy.creditcards.statement

import com.ivy.base.time.TimeProvider
import com.ivy.creditcards.details.CardRowsBuilder
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.creditcard.CardAppearance
import com.ivy.data.model.creditcard.CardStatus
import com.ivy.data.model.creditcard.CreditCard
import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.data.model.creditcard.StatementOverride
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.CreditCardRepository
import com.ivy.domain.creditcard.CardLedgerEntry
import com.ivy.domain.creditcard.CreditCardCalculator
import com.ivy.domain.creditcard.CreditCardOverviewUseCase
import com.ivy.domain.creditcard.CreditCardWithOverview
import com.ivy.navigation.CreditCardStatementScreen
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CreditCardStatementViewModelTest {
    private val cardId = AccountId(UUID(0, 1))
    private var today = LocalDate.of(2026, 1, 25)
    private val nominal = LocalDate.of(2026, 1, 15)
    private var screen = CreditCardStatementScreen(cardId.value, nominal.toEpochDay())

    private val card = CreditCard(
        accountId = cardId,
        bankId = null,
        customBankName = null,
        productId = null,
        productName = null,
        network = null,
        lastFour = null,
        creditLimit = null,
        statementDay = 15,
        dueDateRule = DueDateRule.DaysAfterStatement(20),
        openingOutstanding = BigDecimal.ZERO,
        openingDate = LocalDate.of(2026, 1, 1),
        status = CardStatus.ACTIVE,
        appearance = CardAppearance.FERN,
        remindersEnabled = true,
        reminderDaysBefore = 3,
    )
    private val account = Account(
        id = cardId,
        name = NotBlankTrimmedString.unsafe("Card"),
        asset = AssetCode.unsafe("INR"),
        color = ColorInt(0),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
    )

    // What the "database" holds: the card's ledger and the saved issuer override.
    private var ledger = listOf(entry("2026-01-10", "4550", CardLedgerEntry.Kind.CHARGE))
    private var overrides = emptyList<StatementOverride>()

    private val overviewUseCase = mockk<CreditCardOverviewUseCase>()
    private val repository = mockk<CreditCardRepository>()
    private lateinit var viewModel: CreditCardStatementViewModel

    private fun entry(date: String, amount: String, kind: CardLedgerEntry.Kind) = CardLedgerEntry(
        transactionId = null,
        date = LocalDate.parse(date),
        amount = BigDecimal(amount),
        kind = kind,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { overviewUseCase.load(any()) } coAnswers {
            CreditCardWithOverview(
                account = account,
                card = card,
                overview = CreditCardCalculator.overview(card, ledger, overrides, today),
                transactions = emptyList(),
            )
        }
        coEvery { overviewUseCase.previewStatement(any(), any()) } coAnswers {
            val proposed = secondArg<StatementOverride>()
            val saved = overrides.filterNot { it.nominalClosingDate == proposed.nominalClosingDate }
            CreditCardCalculator.overview(card, ledger, saved + proposed, today).statements
                .firstOrNull { it.nominalClosingDate == proposed.nominalClosingDate }
        }
        coEvery { repository.saveStatementOverride(any()) } coAnswers { overrides = listOf(firstArg()) }
        coEvery { repository.deleteStatementOverride(any(), any()) } coAnswers { overrides = emptyList() }
        viewModel = createViewModel()
        viewModel.start(screen)
    }

    private fun createViewModel() = CreditCardStatementViewModel(
        overviewUseCase = overviewUseCase,
        creditCardRepository = repository,
        rowsBuilder = mockk<CardRowsBuilder>(relaxed = true),
        timeProvider = mockk<TimeProvider> { io.mockk.every { localDateNow() } answers { today } },
        navigation = mockk(relaxed = true),
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun form(
        closing: String? = null,
        due: String? = null,
        balance: String = "",
        minimum: String = "",
    ) = IssuerForm(closing?.let(LocalDate::parse), due?.let(LocalDate::parse), balance, minimum)

    private fun submit(form: IssuerForm) {
        viewModel.onEvent(StatementEvent.EditIssuerValues)
        viewModel.onEvent(StatementEvent.UpdateIssuerForm(form))
        viewModel.onEvent(StatementEvent.SaveIssuerValues)
    }

    // region Dates
    @Test
    fun `a due date before the closing date keeps the form open with a field error and saves nothing`() {
        submit(form(closing = "2026-01-20", due = "2026-01-19"))

        viewModel.state().dueBeforeClosing shouldBe true
        viewModel.state().issuerForm?.actualDueDate shouldBe LocalDate.of(2026, 1, 19)
        coVerify(exactly = 0) { repository.saveStatementOverride(any()) }
    }

    @Test
    fun `fixing the due date saves and closes the form`() {
        submit(form(closing = "2026-01-20", due = "2026-01-19"))

        viewModel.onEvent(StatementEvent.UpdateIssuerForm(form(closing = "2026-01-20", due = "2026-02-01")))
        viewModel.onEvent(StatementEvent.SaveIssuerValues)

        viewModel.state().issuerForm shouldBe null
        viewModel.state().dueBeforeClosing shouldBe false
        coVerify(exactly = 1) { repository.saveStatementOverride(any()) }
    }

    @Test
    fun `a single due date is checked against the calculated closing date`() {
        submit(form(due = "2026-01-14"))
        viewModel.state().dueBeforeClosing shouldBe true
        coVerify(exactly = 0) { repository.saveStatementOverride(any()) }

        viewModel.onEvent(StatementEvent.UpdateIssuerForm(form(due = "2026-01-30")))
        viewModel.onEvent(StatementEvent.SaveIssuerValues)
        coVerify(exactly = 1) { repository.saveStatementOverride(any()) }
    }

    @Test
    fun `a single closing date derives the due date and saves`() {
        submit(form(closing = "2026-01-17"))

        viewModel.state().dueBeforeClosing shouldBe false
        coVerify(exactly = 1) { repository.saveStatementOverride(any()) }
        viewModel.state().statement!!.dueDate shouldBe LocalDate.of(2026, 2, 6)
    }

    @Test
    fun `a closing date the calculator would ignore is validated as the calculated one`() {
        // February's statement: January 10 doesn't come after January's closing (15th), so the
        // calculator keeps February 15 and a January 20 due date would precede the closing date.
        today = LocalDate.of(2026, 2, 25)
        ledger = listOf(
            entry("2026-01-10", "100", CardLedgerEntry.Kind.CHARGE),
            entry("2026-02-10", "50", CardLedgerEntry.Kind.CHARGE),
        )
        screen = CreditCardStatementScreen(cardId.value, LocalDate.of(2026, 2, 15).toEpochDay())
        viewModel = createViewModel()
        viewModel.start(screen)

        submit(form(closing = "2026-01-10", due = "2026-01-20"))

        viewModel.state().dueBeforeClosing shouldBe true
        coVerify(exactly = 0) { repository.saveStatementOverride(any()) }
    }

    @Test
    fun `an earlier closing date that adds a cycle before the first statement is validated as ignored`() {
        // Opened 1 Jan: entering 10 Dec as January's closing date makes the calculator add a
        // December cycle and drop that date, so a 12 Dec due date is earlier than 15 Jan.
        submit(form(closing = "2025-12-10", due = "2025-12-12"))

        viewModel.state().dueBeforeClosing shouldBe true
        coVerify(exactly = 0) { repository.saveStatementOverride(any()) }
    }

    @Test
    fun `an invalid saved override can be opened, corrected and cleared`() {
        // Saved before the validation existed: due date earlier than the closing date.
        overrides = listOf(
            StatementOverride(cardId, nominal, LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 10), null, null)
        )
        viewModel.start(screen)

        viewModel.onEvent(StatementEvent.EditIssuerValues)
        viewModel.state().issuerForm shouldBe form(closing = "2026-01-20", due = "2026-01-10")

        // Saving it unchanged is refused, so the user has to fix it ...
        viewModel.onEvent(StatementEvent.SaveIssuerValues)
        viewModel.state().dueBeforeClosing shouldBe true
        // ... or can remove it, which never validates.
        viewModel.onEvent(StatementEvent.ClearIssuerValues)
        coVerify(exactly = 1) { repository.deleteStatementOverride(cardId, nominal) }
        viewModel.state().issuerForm shouldBe null
        viewModel.state().statement!!.closingDateFromIssuer shouldBe false
    }
    // endregion

    // region Comparison
    private fun difference(): BigDecimal? = viewModel.state().statement!!.issuerDifference

    @Test
    fun `issuer 5000 against calculated 4550 is 450 higher and refreshes after save and clear`() {
        difference() shouldBe null

        submit(form(balance = "5000"))
        difference()!! shouldBeEqualComparingTo BigDecimal("450")

        viewModel.onEvent(StatementEvent.EditIssuerValues)
        viewModel.onEvent(StatementEvent.ClearIssuerValues)
        difference() shouldBe null
    }

    @Test
    fun `a payment after closing leaves the difference alone`() {
        submit(form(balance = "5000"))

        ledger = ledger + entry("2026-01-20", "4550", CardLedgerEntry.Kind.PAYMENT)
        viewModel.start(screen)

        difference()!! shouldBeEqualComparingTo BigDecimal("450")
        viewModel.state().statement!!.remainingDue shouldBeEqualComparingTo BigDecimal("450")
    }

    @Test
    fun `correcting a transaction inside the cycle refreshes the difference when the screen is shown again`() {
        submit(form(balance = "5000"))
        difference()!! shouldBeEqualComparingTo BigDecimal("450")

        // The user opens a transaction, fixes its amount, and comes back.
        ledger = listOf(entry("2026-01-10", "4950", CardLedgerEntry.Kind.CHARGE))
        viewModel.start(screen)

        difference()!! shouldBeEqualComparingTo BigDecimal("50")
    }

    @Test
    fun `viewing a discrepancy writes nothing`() {
        submit(form(balance = "5000"))

        coVerify(exactly = 1) { repository.saveStatementOverride(any()) }
        coVerify(exactly = 0) { repository.deleteStatementOverride(any(), any()) }
    }
    // endregion
}
