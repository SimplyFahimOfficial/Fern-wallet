package com.ivy.importdata.csv

import android.net.Uri
import arrow.core.Either
import com.ivy.base.legacy.Transaction
import com.ivy.base.model.TransactionType
import com.ivy.data.backup.ImportResult
import com.ivy.data.file.FileSystem
import com.ivy.importdata.csv.domain.CSVImporterV2
import com.ivy.importdata.csv.domain.ImportPlan
import com.ivy.importdata.csv.domain.PlannedRow
import com.ivy.importdata.csv.domain.RejectedRow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CSVViewModelTest {
    private val importer = mockk<CSVImporterV2>()
    private val fileSystem = mockk<FileSystem>()
    private lateinit var viewModel: CSVViewModel

    private val result = ImportResult(
        rowsFound = 2,
        transactionsImported = 2,
        accountsImported = 0,
        categoriesImported = 0,
        failedRows = persistentListOf(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        io.mockk.every { fileSystem.read(any(), any()) } returns Either.Right("a,b,c\n1,2,3\n4,5,6\n")
        viewModel = CSVViewModel(fileSystem, importer, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun plannedRow(line: Int) = PlannedRow(
        line = line,
        transaction = Transaction(
            accountId = UUID.randomUUID(),
            type = TransactionType.EXPENSE,
            amount = BigDecimal.ONE,
        ),
        date = LocalDateTime.of(2026, 1, 5, 10, 0),
        accountName = "Wallet", currency = "USD", toAccountName = null, toCurrency = null,
        categoryName = null, newAccountIds = emptySet(), newCategory = null, duplicate = null,
    )

    private val plan = ImportPlan(
        rowsFound = 3,
        rows = listOf(plannedRow(2), plannedRow(3)),
        rejected = listOf(RejectedRow(4, "Invalid amount 'x'", listOf("x"))),
        newAccounts = emptyList(),
        newCategories = emptyList(),
    )

    private fun awaitState(check: (UIState) -> Boolean): UIState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            viewModel.uiState.let { if (check(it)) return it }
            Thread.sleep(10)
        }
        error("Timed out, state is ${viewModel.uiState}")
    }

    private fun review(): UIState.Review {
        viewModel.onEvent(CSVEvent.FilePicked(mockk<Uri>()))
        viewModel.onEvent(CSVEvent.Continue)
        return awaitState { it is UIState.Review } as UIState.Review
    }

    @Test
    fun `Continue shows the review and writes nothing`() {
        coEvery { importer.prepare(any(), any(), any(), any(), any()) } returns plan

        val review = review()

        review.plan shouldBe plan
        coVerify(exactly = 0) { importer.commit(any(), any(), any()) }
    }

    @Test
    fun `double tapping Confirm commits the selection once`() {
        coEvery { importer.prepare(any(), any(), any(), any(), any()) } returns plan
        coEvery { importer.commit(any(), any(), any()) } coAnswers {
            delay(300)
            result
        }
        review()

        viewModel.onEvent(CSVEvent.ConfirmImport)
        viewModel.onEvent(CSVEvent.ConfirmImport)

        awaitState { it is UIState.Result }
        Thread.sleep(500)
        coVerify(exactly = 1) { importer.commit(any(), any(), any()) }
        (viewModel.uiState as UIState.Result).importResult shouldBe result
    }

    @Test
    fun `excluded rows are passed to the commit`() {
        coEvery { importer.prepare(any(), any(), any(), any(), any()) } returns plan
        val excluded = slot<Set<Int>>()
        coEvery { importer.commit(any(), capture(excluded), any()) } returns result
        review()

        viewModel.onEvent(CSVEvent.ToggleRow(3))
        awaitState { (it as? UIState.Review)?.excludedLines == setOf(3) }
        viewModel.onEvent(CSVEvent.ConfirmImport)

        awaitState { it is UIState.Result }
        excluded.captured shouldBe setOf(3)
    }

    @Test
    fun `a failed commit goes back to the review and a retry commits once`() {
        coEvery { importer.prepare(any(), any(), any(), any(), any()) } returns plan
        coEvery { importer.commit(any(), any(), any()) } throws IOException("injected") andThen result
        review()

        viewModel.onEvent(CSVEvent.ConfirmImport)
        val failed = awaitState { it is UIState.Review && it.error != null } as UIState.Review
        failed.plan shouldBe plan

        viewModel.onEvent(CSVEvent.ConfirmImport)
        awaitState { it is UIState.Result }
        coVerify(exactly = 2) { importer.commit(any(), any(), any()) }
        (viewModel.uiState as UIState.Result).importResult shouldBe result
    }

    @Test
    fun `cancelling leaves nothing committed`() {
        coEvery { importer.prepare(any(), any(), any(), any(), any()) } returns plan
        review()

        viewModel.onEvent(CSVEvent.CancelImport)

        awaitState { it is UIState.Idle }
        coVerify(exactly = 0) { importer.commit(any(), any(), any()) }
    }

    @Test
    fun `changing a column mapping rebuilds the preview`() {
        val important = slot<ImportantFields>()
        coEvery { importer.prepare(any(), capture(important), any(), any(), any()) } returns plan
        review()
        important.captured.amount.index shouldBe -1

        viewModel.onEvent(CSVEvent.BackToMapping)
        awaitState { it is UIState.Idle }
        viewModel.onEvent(CSVEvent.MapAmount(2, "c"))
        viewModel.onEvent(CSVEvent.Continue)
        awaitState { it is UIState.Review }

        coVerify(exactly = 2) { importer.prepare(any(), any(), any(), any(), any()) }
        important.captured.amount.index shouldBe 2
    }
}
