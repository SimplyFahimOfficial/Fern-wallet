package com.ivy.data.backup

import android.content.Context
import android.net.Uri
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.DbTransactionRunner
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.read.BudgetDao
import com.ivy.data.db.dao.read.CategoryDao
import com.ivy.data.db.dao.read.CreditCardDao
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.read.PlannedPaymentRuleDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.read.TagAssociationDao
import com.ivy.data.db.dao.read.TagDao
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WriteAccountDao
import com.ivy.data.db.dao.write.WriteBudgetDao
import com.ivy.data.db.dao.write.WriteCategoryDao
import com.ivy.data.db.dao.write.WriteCreditCardDao
import com.ivy.data.db.dao.write.WriteLoanDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.db.dao.write.WriteTagAssociationDao
import com.ivy.data.db.dao.write.WriteTagDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.repository.mapper.AccountMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Backup export and import.
 *
 * Import is all-or-nothing: the file is read and validated first, a copy of the current wallet
 * is saved ([PreRestoreSnapshots]) so the import can be undone, and then every row is written in
 * one database transaction. If anything fails, the database is left as it was.
 */
class BackupDataUseCase @Inject constructor(
    private val accountDao: AccountDao,
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val loanRecordDao: LoanRecordDao,
    private val loanDao: LoanDao,
    private val plannedPaymentRuleDao: PlannedPaymentRuleDao,
    private val settingsDao: SettingsDao,
    private val transactionDao: TransactionDao,
    private val transactionWriter: WriteTransactionDao,
    private val sharedPrefs: SharedPrefs,
    private val accountWriter: WriteAccountDao,
    private val accountMapper: AccountMapper,
    private val categoryWriter: WriteCategoryDao,
    private val settingsWriter: WriteSettingsDao,
    private val budgetWriter: WriteBudgetDao,
    private val loanWriter: WriteLoanDao,
    private val loanRecordWriter: WriteLoanRecordDao,
    private val plannedPaymentRuleWriter: WritePlannedPaymentRuleDao,
    @ApplicationContext
    private val context: Context,
    private val json: Json,
    private val dispatchersProvider: DispatchersProvider,
    private val dataObserver: DataObserver,
    private val tagsReader: TagDao,
    private val tagAssociationReader: TagAssociationDao,
    private val tagsWriter: WriteTagDao,
    private val tagAssociationWriter: WriteTagAssociationDao,
    private val creditCardDao: CreditCardDao,
    private val creditCardWriter: WriteCreditCardDao,
    private val transactionRunner: DbTransactionRunner,
    private val preRestoreSnapshots: PreRestoreSnapshots,
) {
    suspend fun exportToFile(
        zipFileUri: Uri
    ) {
        val jsonString = generateJsonBackup()
        withContext(dispatchersProvider.io) {
            val descriptor = context.contentResolver.openFileDescriptor(zipFileUri, MODE_WRITE)
                ?: error("Couldn't open the backup file for writing")
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { out -> BackupArchive.writeZip(out, jsonString) }
            }
        }
    }

    suspend fun generateJsonBackup(): String {
        return withContext(dispatchersProvider.io) {
            json.encodeToString(readCompleteData())
        }
    }

    private suspend fun readCompleteData(): IvyWalletCompleteData {
        // One read transaction, so the backup is a consistent snapshot of the wallet.
        return transactionRunner.run {
            IvyWalletCompleteData(
                formatVersion = IvyWalletCompleteData.CURRENT_FORMAT_VERSION,
                accounts = accountDao.findAll(),
                budgets = budgetDao.findAll(),
                categories = categoryDao.findAll(),
                loanRecords = loanRecordDao.findAll(),
                loans = loanDao.findAll(),
                plannedPaymentRules = plannedPaymentRuleDao.findAll(),
                settings = settingsDao.findAll(),
                transactions = transactionDao.findAll(),
                sharedPrefs = getSharedPrefsData(),
                tags = tagsReader.findAll(),
                tagAssociations = tagAssociationReader.findAll(),
                creditCards = creditCardDao.findAll(),
                creditCardStatements = creditCardDao.findAllStatements(),
            )
        }
    }

    private fun getSharedPrefsData(): HashMap<String, String> {
        val hashmap = HashMap<String, String>()
        hashmap[SharedPrefs.SHOW_NOTIFICATIONS] =
            sharedPrefs.getBoolean(SharedPrefs.SHOW_NOTIFICATIONS, true).toString()

        hashmap[SharedPrefs.APP_LOCK_ENABLED] =
            sharedPrefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false).toString()

        hashmap[SharedPrefs.HIDE_CURRENT_BALANCE] =
            sharedPrefs.getBoolean(SharedPrefs.HIDE_CURRENT_BALANCE, false).toString()

        hashmap[SharedPrefs.TRANSFERS_AS_INCOME_EXPENSE] =
            sharedPrefs.getBoolean(SharedPrefs.TRANSFERS_AS_INCOME_EXPENSE, false).toString()

        return hashmap
    }

    suspend fun importBackupFile(
        backupFileUri: Uri,
        onProgress: suspend (progressPercent: Double) -> Unit
    ): ImportResult = withContext(dispatchersProvider.io) {
        try {
            val jsonString = try {
                val descriptor = context.contentResolver.openFileDescriptor(backupFileUri, MODE_READ)
                    ?: throw BackupImportException(ImportFailure.NOT_A_BACKUP)
                descriptor.use { BackupArchive.readJson(java.io.FileInputStream(it.fileDescriptor)) }
            } catch (e: BackupImportException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BackupImportException(ImportFailure.CORRUPTED, e)
            }
            onProgress(PROGRESS_READ)
            importJson(jsonString, onProgress)
        } catch (e: BackupImportException) {
            Timber.e(e, "Backup import failed: ${e.failure}")
            failedImport(e.failure)
        }
    }

    /**
     * Imports a backup JSON. Never throws for bad input: problems are reported in
     * [ImportResult.failure], and in that case nothing was written.
     */
    suspend fun importJson(
        jsonString: String,
        onProgress: suspend (Double) -> Unit = {},
    ): ImportResult = withContext(dispatchersProvider.io) {
        val parsed = try {
            parse(jsonString)
        } catch (e: BackupImportException) {
            Timber.e(e, "Backup import failed: ${e.failure}")
            return@withContext failedImport(e.failure)
        }
        onProgress(PROGRESS_PARSED)

        val completeData = withContext(dispatchersProvider.io) { mergeWithExistingRecords(parsed) }
        val accounts = importableAccounts(completeData)
        val missing = completeData.firstUnresolvedTransaction(
            accountIds = accountDao.findAll().mapTo(mutableSetOf()) { it.id } + accounts.map { it.id }
        )
        if (missing != null) {
            // Nothing has been written yet: no copy, no transaction.
            Timber.e("Backup import refused: transaction $missing refers to an account that can't be restored")
            return@withContext failedImport(ImportFailure.CORRUPTED)
        }

        val snapshot = try {
            savePreRestoreCopyUnlessWalletIsEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without a copy of the current data, don't risk changing it.
            Timber.e(e, "Couldn't save the pre-restore copy")
            return@withContext failedImport(ImportFailure.SAVE_FAILED)
        }
        onProgress(PROGRESS_SNAPSHOT)

        try {
            transactionRunner.run { insertDataToDb(completeData, accounts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Backup import failed while saving; nothing was changed")
            snapshot?.let(preRestoreSnapshots::delete)
            return@withContext failedImport(ImportFailure.SAVE_FAILED)
        } finally {
            dataObserver.post(DataWriteEvent.AllDataChange)
        }
        applySharedPrefs(completeData)
        onProgress(1.0)

        ImportResult(
            rowsFound = completeData.transactions.size,
            transactionsImported = completeData.transactions.size,
            accountsImported = completeData.accounts.size,
            categoriesImported = completeData.categories.size,
            failedRows = persistentListOf(),
            canUndo = snapshot != null,
        )
    }

    /** True if [undoLastImport] has a copy of the wallet to go back to. */
    suspend fun canUndoLastImport(): Boolean = withContext(dispatchersProvider.io) {
        preRestoreSnapshots.latest() != null
    }

    /**
     * Replaces the whole wallet with the copy saved before the most recent import, in one
     * database transaction. Returns false if there is no copy or it couldn't be restored (in which
     * case the current data is unchanged).
     */
    suspend fun undoLastImport(): Boolean = withContext(dispatchersProvider.io) {
        val snapshot = preRestoreSnapshots.latest() ?: return@withContext false
        try {
            val data = parse(snapshot.inputStream().use { BackupArchive.readJson(it) })
            transactionRunner.run {
                deleteAllWalletData()
                // Exactly the previous rows: they came from this database, so no re-validation.
                insertDataToDb(data, data.accounts)
            }
            applySharedPrefs(data)
            preRestoreSnapshots.delete(snapshot)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Couldn't undo the last import")
            false
        } finally {
            dataObserver.post(DataWriteEvent.AllDataChange)
        }
    }

    private fun parse(jsonString: String): IvyWalletCompleteData {
        val data = try {
            json.decodeFromString<IvyWalletCompleteData>(jsonString)
        } catch (e: IllegalArgumentException) {
            // Includes SerializationException: malformed JSON or unexpected values.
            throw BackupImportException(ImportFailure.CORRUPTED, e)
        }
        if (data.formatVersion > IvyWalletCompleteData.CURRENT_FORMAT_VERSION) {
            throw BackupImportException(ImportFailure.NEWER_VERSION)
        }
        return data
    }

    private suspend fun savePreRestoreCopyUnlessWalletIsEmpty(): java.io.File? {
        val current = readCompleteData()
        if (!current.hasRestorableRows()) return null
        preRestoreSnapshots.save(json.encodeToString(current), System.currentTimeMillis())
        return preRestoreSnapshots.latest()
    }

    // Any row the import can overwrite counts, not just accounts and categories: a wallet with
    // only tags, loans, budgets or settings must be restorable too. Shared preferences are not
    // considered: they always have values (defaults on a fresh install), so they'd make every
    // wallet look non-empty; they're still included in the copy whenever one is saved.
    private fun IvyWalletCompleteData.hasRestorableRows(): Boolean = listOf(
        accounts, budgets, categories, loanRecords, loans, plannedPaymentRules, settings,
        transactions, tags, tagAssociations, creditCards, creditCardStatements,
    ).any { it.isNotEmpty() }

    // Accounts and categories in the backup are the same records as the wallet's if they share
    // an id, or, failing that, if they are each other's only match by name (see [matchAccounts]).
    // Merged records take the wallet's id in every field that references them.
    private suspend fun mergeWithExistingRecords(parsed: IvyWalletCompleteData): IvyWalletCompleteData {
        val existingBaseCurrency = settingsDao.findFirstOrNull()?.currency
        val maps = BackupIdMaps(
            accounts = matchAccounts(
                incoming = parsed.accounts,
                incomingBaseCurrency = parsed.settings.firstOrNull()?.currency ?: existingBaseCurrency,
                existing = accountDao.findAll(),
                existingBaseCurrency = existingBaseCurrency,
            ),
            categories = matchCategories(parsed.categories, categoryDao.findAll()),
        )
        return parsed.remapIds(maps)
    }

    private suspend fun importableAccounts(completeData: IvyWalletCompleteData): List<AccountEntity> = with(
        accountMapper
    ) {
        completeData.accounts.mapNotNull { entity -> entity.toDomain().getOrNull()?.toEntity() }
    }

    private suspend fun deleteAllWalletData() {
        transactionWriter.deleteAll()
        accountWriter.deleteAll()
        categoryWriter.deleteAll()
        budgetWriter.deleteAll()
        loanWriter.deleteAll()
        loanRecordWriter.deleteAll()
        plannedPaymentRuleWriter.deleteAll()
        tagsWriter.deleteAll()
        tagAssociationWriter.deleteAll()
        creditCardWriter.deleteAll()
        creditCardWriter.deleteAllStatements()
        settingsWriter.deleteAll()
    }

    /** Must run inside [transactionRunner]: either every write is kept or none is. */
    private suspend fun insertDataToDb(
        completeData: IvyWalletCompleteData,
        accounts: List<AccountEntity>,
    ) {
        accountWriter.saveMany(accounts)
        categoryWriter.saveMany(completeData.categories)
        transactionWriter.saveMany(completeData.transactions)
        budgetWriter.saveMany(completeData.budgets)

        // Card details must only attach to accounts that exist after the import.
        val importedAccountIds = accounts.map { it.id }.toSet()
        creditCardWriter.saveMany(
            completeData.creditCards.filter { it.accountId in importedAccountIds }
        )
        creditCardWriter.saveStatements(
            completeData.creditCardStatements.filter { it.accountId in importedAccountIds }
        )

        loanWriter.saveMany(completeData.loans)
        loanRecordWriter.saveMany(completeData.loanRecords)
        tagsWriter.save(completeData.tags)
        tagAssociationWriter.save(completeData.tagAssociations)
        plannedPaymentRuleWriter.saveMany(completeData.plannedPaymentRules)

        // Keep the current settings (base currency etc.) if the backup has none.
        if (completeData.settings.isNotEmpty()) {
            settingsWriter.deleteAll()
            settingsWriter.saveMany(completeData.settings)
        }
    }

    private fun applySharedPrefs(completeData: IvyWalletCompleteData) {
        sharedPrefs.putBoolean(
            SharedPrefs.SHOW_NOTIFICATIONS,
            (completeData.sharedPrefs[SharedPrefs.SHOW_NOTIFICATIONS] ?: "true").toBoolean()
        )

        // Importing a backup may turn the app lock on, but must never silently turn it off
        // on a device where the user has enabled it.
        val appLockCurrentlyEnabled = sharedPrefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false)
        val appLockInBackup =
            (completeData.sharedPrefs[SharedPrefs.APP_LOCK_ENABLED] ?: "false").toBoolean()
        sharedPrefs.putBoolean(
            SharedPrefs.APP_LOCK_ENABLED,
            appLockCurrentlyEnabled || appLockInBackup
        )

        sharedPrefs.putBoolean(
            SharedPrefs.HIDE_CURRENT_BALANCE,
            (completeData.sharedPrefs[SharedPrefs.HIDE_CURRENT_BALANCE] ?: "false").toBoolean()
        )

        sharedPrefs.putBoolean(
            SharedPrefs.TRANSFERS_AS_INCOME_EXPENSE,
            (
                completeData.sharedPrefs[SharedPrefs.TRANSFERS_AS_INCOME_EXPENSE]
                    ?: "false"
                ).toBoolean()
        )
    }

    private fun failedImport(failure: ImportFailure): ImportResult = ImportResult(
        rowsFound = 0,
        transactionsImported = 0,
        accountsImported = 0,
        categoriesImported = 0,
        failedRows = persistentListOf(),
        failure = failure,
    )

    private companion object {
        const val MODE_READ = "r"
        const val MODE_WRITE = "w"
        const val PROGRESS_READ = 0.1
        const val PROGRESS_PARSED = 0.3
        const val PROGRESS_SNAPSHOT = 0.5
    }
}
