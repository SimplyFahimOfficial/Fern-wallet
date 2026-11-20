package com.ivy.creditcards.statement

import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.domain.creditcard.CardStatement
import com.ivy.domain.creditcard.CreditCardCalculator
import java.time.LocalDate

/** Checks the statement and due dates a saved issuer override would result in. */
object StatementDateValidator {

    /**
     * True if the due date would fall before the closing date.
     *
     * [preview] is the statement as the calculator would build it with the override saved; its
     * dates are the effective ones (the calculator ignores an issuer closing date that doesn't fit
     * between the neighbouring cycles). Without a preview, a date the user left empty is derived
     * the usual way: the closing date from the statement day ([nominalClosing]), the due date from
     * the card's due-date [rule]. No rule and no due date means there is nothing to compare.
     */
    fun dueBeforeClosing(
        preview: CardStatement?,
        nominalClosing: LocalDate,
        actualClosing: LocalDate?,
        actualDue: LocalDate?,
        rule: DueDateRule?,
    ): Boolean {
        val closing = preview?.closingDate ?: actualClosing ?: nominalClosing
        val due = preview?.dueDate ?: actualDue ?: CreditCardCalculator.dueDate(closing, rule)
        return due != null && due.isBefore(closing)
    }
}
