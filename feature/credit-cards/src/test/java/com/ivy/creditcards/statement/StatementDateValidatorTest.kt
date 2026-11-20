package com.ivy.creditcards.statement

import com.ivy.data.model.AccountId
import com.ivy.data.model.creditcard.CardAppearance
import com.ivy.data.model.creditcard.CardStatus
import com.ivy.data.model.creditcard.CreditCard
import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.data.model.creditcard.StatementOverride
import com.ivy.domain.creditcard.CardLedgerEntry
import com.ivy.domain.creditcard.CardStatement
import com.ivy.domain.creditcard.CreditCardCalculator
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class StatementDateValidatorTest {
    private val nominal = LocalDate.of(2026, 1, 15)
    private val rule = DueDateRule.DaysAfterStatement(20)
    private val cardId = AccountId(UUID(0, 1))

    private fun dueBeforeClosing(
        closing: String?,
        due: String?,
        rule: DueDateRule? = this.rule,
        preview: CardStatement? = null,
    ): Boolean = StatementDateValidator.dueBeforeClosing(
        preview = preview,
        nominalClosing = nominal,
        actualClosing = closing?.let(LocalDate::parse),
        actualDue = due?.let(LocalDate::parse),
        rule = rule,
    )

    /** The January statement of a card opened on 1 January, as calculated with this override saved. */
    private fun preview(closing: String?, due: String?): CardStatement {
        val card = CreditCard(
            accountId = cardId, bankId = null, customBankName = null, productId = null, productName = null,
            network = null, lastFour = null, creditLimit = null, statementDay = 15, dueDateRule = rule,
            openingOutstanding = BigDecimal.ZERO, openingDate = LocalDate.of(2026, 1, 1), status = CardStatus.ACTIVE,
            appearance = CardAppearance.FERN, remindersEnabled = true, reminderDaysBefore = 3,
        )
        val charge = CardLedgerEntry(null, LocalDate.of(2026, 1, 10), BigDecimal(10), CardLedgerEntry.Kind.CHARGE)
        val override = StatementOverride(
            accountId = cardId,
            nominalClosingDate = nominal,
            actualClosingDate = closing?.let(LocalDate::parse),
            actualDueDate = due?.let(LocalDate::parse),
            issuerStatementBalance = null,
            minimumDue = null,
        )
        return CreditCardCalculator.overview(card, listOf(charge), listOf(override), LocalDate.of(2026, 1, 25))
            .statements.single { it.nominalClosingDate == nominal }
    }

    @Test
    fun `a due date before the closing date is rejected`() {
        dueBeforeClosing(closing = "2026-01-20", due = "2026-01-19") shouldBe true
    }

    @Test
    fun `the same day is allowed`() {
        dueBeforeClosing(closing = "2026-01-20", due = "2026-01-20") shouldBe false
    }

    @Test
    fun `only a due date is compared with the calculated closing date`() {
        dueBeforeClosing(closing = null, due = "2026-01-14") shouldBe true
        dueBeforeClosing(closing = null, due = "2026-01-15") shouldBe false
    }

    @Test
    fun `only a closing date derives the due date from the card rule`() {
        dueBeforeClosing(closing = "2026-03-01", due = null) shouldBe false
    }

    @Test
    fun `a closing date alone is fine without a due rule`() {
        dueBeforeClosing(closing = "2026-03-01", due = null, rule = null) shouldBe false
    }

    @Test
    fun `no dates at all is fine`() {
        dueBeforeClosing(closing = null, due = null) shouldBe false
        dueBeforeClosing(closing = null, due = null, rule = null) shouldBe false
    }

    @Test
    fun `the dates the calculator ends up with decide`() {
        // 10 Dec would add a December cycle before January, so the calculator keeps 15 Jan as the
        // closing date and a 12 Dec due date would be earlier than it.
        val ignored = preview(closing = "2025-12-10", due = "2025-12-12")
        ignored.closingDate shouldBe nominal
        dueBeforeClosing("2025-12-10", "2025-12-12", preview = ignored) shouldBe true

        val fine = preview(closing = "2026-01-20", due = "2026-01-25")
        dueBeforeClosing("2026-01-20", "2026-01-25", preview = fine) shouldBe false
    }
}
