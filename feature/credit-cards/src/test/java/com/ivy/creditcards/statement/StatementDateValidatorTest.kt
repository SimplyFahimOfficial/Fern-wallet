package com.ivy.creditcards.statement

import com.ivy.data.model.creditcard.DueDateRule
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate

class StatementDateValidatorTest {
    private val nominal = LocalDate.of(2026, 1, 15)
    private val rule = DueDateRule.DaysAfterStatement(20)

    private fun dueBeforeClosing(closing: String?, due: String?, rule: DueDateRule? = this.rule): Boolean =
        StatementDateValidator.dueBeforeClosing(
            nominalClosing = nominal,
            actualClosing = closing?.let(LocalDate::parse),
            actualDue = due?.let(LocalDate::parse),
            rule = rule,
        )

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
}
