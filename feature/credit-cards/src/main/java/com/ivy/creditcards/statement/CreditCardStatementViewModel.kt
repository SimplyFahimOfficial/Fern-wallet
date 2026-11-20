package com.ivy.creditcards.statement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.creditcards.details.CardRowsBuilder
import com.ivy.creditcards.details.CardTransactionRow
import com.ivy.creditcards.edit.CardFormValidator
import com.ivy.data.model.AccountId
import com.ivy.data.model.creditcard.DueDateRule
import com.ivy.data.model.creditcard.StatementOverride
import com.ivy.data.repository.CreditCardRepository
import com.ivy.domain.creditcard.CardStatement
import com.ivy.domain.creditcard.CreditCardOverviewUseCase
import com.ivy.navigation.CreditCardStatementScreen
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Issuer values typed by the user, as strings until saved. */
@Immutable
data class IssuerForm(
    val actualClosingDate: LocalDate?,
    val actualDueDate: LocalDate?,
    val issuerBalance: String,
    val minimumDue: String,
)

@Immutable
data class StatementState(
    val loading: Boolean,
    val cardName: String,
    val currency: String,
    val statement: CardStatement?,
    val rows: ImmutableList<CardTransactionRow>,
    val issuerForm: IssuerForm?,
    val invalidAmount: Boolean,
    /** The due date the form would save is earlier than the statement date. */
    val dueBeforeClosing: Boolean,
    val today: LocalDate,
)

sealed interface StatementEvent {
    data object EditIssuerValues : StatementEvent
    data object DismissIssuerValues : StatementEvent
    data class UpdateIssuerForm(val form: IssuerForm) : StatementEvent
    data object SaveIssuerValues : StatementEvent
    data object ClearIssuerValues : StatementEvent

    @Suppress("DataClassTypedIDs") // navigation takes raw UUIDs
    data class OpenTransaction(val id: UUID) : StatementEvent
}

@HiltViewModel
class CreditCardStatementViewModel @Inject constructor(
    private val overviewUseCase: CreditCardOverviewUseCase,
    private val creditCardRepository: CreditCardRepository,
    private val rowsBuilder: CardRowsBuilder,
    private val timeProvider: TimeProvider,
    private val navigation: Navigation,
) : ComposeViewModel<StatementState, StatementEvent>() {

    private var screen: CreditCardStatementScreen? = null
    private var loading by mutableStateOf(true)
    private var cardName by mutableStateOf("")
    private var currency by mutableStateOf("")
    private var statement by mutableStateOf<CardStatement?>(null)
    private var rows by mutableStateOf<List<CardTransactionRow>>(emptyList())
    private var issuerForm by mutableStateOf<IssuerForm?>(null)
    private var invalidAmount by mutableStateOf(false)
    private var dueBeforeClosing by mutableStateOf(false)
    private var dueRule: DueDateRule? = null

    // Checking the dates suspends. Any change to the form (edit, dismiss, a newer save) bumps this
    // so the result of a check that started earlier is dropped instead of applied to another form.
    private var formVersion = 0

    fun start(screen: CreditCardStatementScreen) {
        this.screen = screen
        reload()
    }

    private fun reload() {
        val s = screen ?: return
        viewModelScope.launch {
            val item = overviewUseCase.load(AccountId(s.accountId))
            val nominal = LocalDate.ofEpochDay(s.nominalClosingEpochDay)
            val statements = item?.overview?.statements.orEmpty() // newest first
            val found = statements.firstOrNull { it.nominalClosingDate == nominal }
            cardName = item?.account?.name?.value.orEmpty()
            currency = item?.account?.asset?.code.orEmpty()
            dueRule = item?.card?.dueDateRule
            statement = found
            val ids = found?.entries?.mapNotNull { it.transactionId?.value }?.toSet().orEmpty()
            rows = item?.let { rowsBuilder.rows(it) }.orEmpty().filter { it.id in ids }
            loading = false
        }
    }

    @Composable
    override fun uiState(): StatementState = state()

    internal fun state(): StatementState = StatementState(
        loading = loading,
        cardName = cardName,
        currency = currency,
        statement = statement,
        rows = rows.toImmutableList().ifEmpty { persistentListOf() },
        issuerForm = issuerForm,
        invalidAmount = invalidAmount,
        dueBeforeClosing = dueBeforeClosing,
        today = timeProvider.localDateNow(),
    )

    override fun onEvent(event: StatementEvent) {
        when (event) {
            StatementEvent.EditIssuerValues -> statement?.let {
                formVersion++
                dueBeforeClosing = false
                invalidAmount = false
                issuerForm = IssuerForm(
                    actualClosingDate = it.closingDate.takeIf { _ -> it.closingDateFromIssuer },
                    actualDueDate = it.dueDate.takeIf { _ -> it.dueDateFromIssuer },
                    issuerBalance = it.issuerStatementBalance?.toPlainString().orEmpty(),
                    minimumDue = it.minimumDue?.toPlainString().orEmpty(),
                )
            }
            StatementEvent.DismissIssuerValues -> {
                formVersion++
                issuerForm = null
                invalidAmount = false
                dueBeforeClosing = false
            }
            is StatementEvent.UpdateIssuerForm -> {
                formVersion++
                issuerForm = event.form
                dueBeforeClosing = false
            }
            StatementEvent.SaveIssuerValues -> save(clear = false)
            StatementEvent.ClearIssuerValues -> save(clear = true)
            is StatementEvent.OpenTransaction -> navigation.navigateTo(
                EditTransactionScreen(initialTransactionId = event.id, type = TransactionType.EXPENSE)
            )
        }
    }

    private fun save(clear: Boolean) {
        val s = screen
        val form = issuerForm
        if (s == null || form == null) return
        val balance = form.issuerBalance.takeIf { it.isNotBlank() }?.let(CardFormValidator::parseAmount)
        val minimum = form.minimumDue.takeIf { it.isNotBlank() }?.let(CardFormValidator::parseAmount)
        val badAmount = (form.issuerBalance.isNotBlank() && balance == null) ||
            (form.minimumDue.isNotBlank() && (minimum == null || minimum.signum() < 0))
        val nominal = LocalDate.ofEpochDay(s.nominalClosingEpochDay)
        invalidAmount = !clear && badAmount
        if (invalidAmount) return
        val version = ++formVersion
        viewModelScope.launch {
            val accountId = AccountId(s.accountId)
            if (clear) {
                // Clearing never validates: it is how an invalid override saved earlier gets removed.
                creditCardRepository.deleteStatementOverride(accountId, nominal)
                dueBeforeClosing = false
            } else {
                val override = StatementOverride(
                    accountId = accountId,
                    nominalClosingDate = nominal,
                    actualClosingDate = form.actualClosingDate,
                    actualDueDate = form.actualDueDate,
                    issuerStatementBalance = balance,
                    minimumDue = minimum,
                )
                val invalidDates = StatementDateValidator.dueBeforeClosing(
                    preview = overviewUseCase.previewStatement(accountId, override),
                    nominalClosing = nominal,
                    actualClosing = form.actualClosingDate,
                    actualDue = form.actualDueDate,
                    rule = dueRule,
                )
                if (version != formVersion) return@launch // the form changed while checking
                dueBeforeClosing = invalidDates
                if (invalidDates) return@launch
                creditCardRepository.saveStatementOverride(override)
            }
            issuerForm = null
            // Overrides are keyed by the nominal closing date, so the same screen reloads the
            // corrected period even when the actual closing date moved.
            reload()
        }
    }
}
