package com.ivy.data.backup

import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.CategoryEntity
import java.util.UUID

/**
 * Which accounts and categories of a backup are the same records as ones already in the wallet.
 * Only records that need a different id are listed: [accounts] and [categories] map the id used
 * in the backup to the id of the wallet record it is merged into.
 */
internal class BackupIdMaps(
    val accounts: Map<UUID, UUID>,
    val categories: Map<UUID, UUID>,
) {
    val isEmpty: Boolean get() = accounts.isEmpty() && categories.isEmpty()
}

/**
 * Matching rule for accounts and categories alike:
 * 1. The same UUID is the same record.
 * 2. Of the rest, an incoming record is merged by name only if it is the only incoming record
 *    with that name, there is exactly one wallet record with that name that no incoming record
 *    matched by UUID, and [compatible] accepts the pair. Anything else keeps its own id.
 */
private fun <T> matchRecords(
    incoming: List<T>,
    existing: List<T>,
    id: (T) -> UUID,
    name: (T) -> String,
    compatible: (incoming: T, existing: T) -> Boolean,
): Map<UUID, UUID> {
    val existingIds = existing.mapTo(mutableSetOf(), id)
    val incomingIds = incoming.mapTo(mutableSetOf(), id)
    val freeExisting = existing.filter { id(it) !in incomingIds }
    return incoming.filter { id(it) !in existingIds }
        .groupBy(name)
        .mapNotNull { (recordName, sameName) ->
            val candidates = freeExisting.filter { name(it) == recordName }
            val match = candidates.singleOrNull()
            if (sameName.size == 1 && match != null && compatible(sameName.single(), match)) {
                id(sameName.single()) to id(match)
            } else {
                null
            }
        }
        .toMap()
}

/** [baseCurrency] fills in accounts without an explicit currency, on each side. */
internal fun matchAccounts(
    incoming: List<AccountEntity>,
    incomingBaseCurrency: String?,
    existing: List<AccountEntity>,
    existingBaseCurrency: String?,
): Map<UUID, UUID> = matchRecords(
    incoming = incoming,
    existing = existing,
    id = { it.id },
    name = { it.name },
    compatible = { new, old ->
        new.type.equals(old.type, ignoreCase = true) &&
            (new.currency ?: incomingBaseCurrency).equals(old.currency ?: existingBaseCurrency, ignoreCase = true)
    },
)

internal fun matchCategories(
    incoming: List<CategoryEntity>,
    existing: List<CategoryEntity>,
): Map<UUID, UUID> = matchRecords(
    incoming = incoming,
    existing = existing,
    id = { it.id },
    name = { it.name },
    compatible = { _, _ -> true },
)

/**
 * Replaces account and category ids in the fields that reference those records and nowhere
 * else, so titles, notes and descriptions stay exactly as they were.
 */
internal fun IvyWalletCompleteData.remapIds(maps: BackupIdMaps): IvyWalletCompleteData {
    if (maps.isEmpty) return this
    val account = { id: UUID -> maps.accounts[id] ?: id }
    val category = { id: UUID -> maps.categories[id] ?: id }
    return copy(
        // The records that were merged are the wallet's own, so they take the wallet's ids.
        accounts = accounts.map { it.copy(id = account(it.id)) },
        categories = categories.map { it.copy(id = category(it.id)) },
        transactions = transactions.map {
            it.copy(
                accountId = account(it.accountId),
                toAccountId = it.toAccountId?.let(account),
                categoryId = it.categoryId?.let(category),
            )
        },
        budgets = budgets.map {
            it.copy(
                accountIdsSerialized = it.accountIdsSerialized.remapIdList(maps.accounts),
                categoryIdsSerialized = it.categoryIdsSerialized.remapIdList(maps.categories),
            )
        },
        plannedPaymentRules = plannedPaymentRules.map {
            it.copy(accountId = account(it.accountId), categoryId = it.categoryId?.let(category))
        },
        loans = loans.map { it.copy(accountId = it.accountId?.let(account)) },
        loanRecords = loanRecords.map { it.copy(accountId = it.accountId?.let(account)) },
        creditCards = creditCards.map { it.copy(accountId = account(it.accountId)) },
        creditCardStatements = creditCardStatements.map { it.copy(accountId = account(it.accountId)) },
    )
}

/** "id1,id2" as the budgets store them. Entries that aren't mapped (or aren't UUIDs) stay as they are. */
private fun String?.remapIdList(map: Map<UUID, UUID>): String? {
    if (this == null || map.isEmpty()) return this
    var changed = false
    val remapped = split(",").map { token ->
        val replacement = runCatching { UUID.fromString(token.trim()) }.getOrNull()?.let(map::get)
        if (replacement != null) changed = true
        replacement?.toString() ?: token
    }
    // A budget that listed both a merged record and the record it merged into lists it once.
    return if (changed) remapped.distinct().joinToString(",") else this
}

/**
 * The first transaction that points at an account the wallet won't have after the restore, or
 * null if every reference resolves. Deleted transactions are never shown, so they don't count.
 * [accountIds] are all accounts that exist after the restore.
 */
internal fun IvyWalletCompleteData.firstUnresolvedTransaction(accountIds: Set<UUID>): UUID? =
    transactions.firstOrNull {
        !it.isDeleted && (
            it.accountId !in accountIds ||
                (it.type == TransactionType.TRANSFER && it.toAccountId !in accountIds)
            )
    }?.id
