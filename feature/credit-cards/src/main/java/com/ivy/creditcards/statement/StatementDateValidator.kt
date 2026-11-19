package com.ivy.creditcards.statement

import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.domain.creditcard.CreditCardCalculator
import java.time.LocalDate

/** Checks the statement and due dates a saved issuer override would result in. */
object StatementDateValidator {

    /**
     * True if the due date would fall before the closing date. A date the user left empty is
     * derived the way the statement itself derives it: the closing date from the card's statement
     * day ([nominalClosing]), the due date from the card's due-date [rule]. No rule and no due
     * date means there is nothing to compare.
     */
    fun dueBeforeClosing(
        nominalClosing: LocalDate,
        actualClosing: LocalDate?,
        actualDue: LocalDate?,
        rule: DueDateRule?,
    ): Boolean {
        val closing = actualClosing ?: nominalClosing
        val due = actualDue ?: CreditCardCalculator.dueDate(closing, rule)
        return due != null && due.isBefore(closing)
    }
}
