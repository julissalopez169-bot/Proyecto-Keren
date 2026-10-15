package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AccountEntity
import com.example.data.model.BeneficiaryEntity
import com.example.data.model.CardEntity
import com.example.data.model.NotificationEntity
import com.example.data.model.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isActive DESC, lastActiveTimestamp DESC LIMIT 1")
    fun getPrimaryAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts ORDER BY isActive DESC, lastActiveTimestamp DESC LIMIT 1")
    suspend fun getPrimaryAccountDirect(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE accountNumber = :accNumber LIMIT 1")
    suspend fun getAccountByNumber(accNumber: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE LOWER(accountHolder) LIKE '%' || LOWER(:query) || '%' OR accountNumber = :query LIMIT 1")
    suspend fun findAccount(query: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY lastActiveTimestamp DESC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE accounts SET isActive = 1, lastActiveTimestamp = :timestamp WHERE accountNumber = :accNumber")
    suspend fun activateAccount(accNumber: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE accounts SET balanceHnl = :newBalanceHnl, balanceUsd = :newBalanceUsd WHERE accountNumber = :accNumber")
    suspend fun updateBalance(accNumber: String, newBalanceHnl: Double, newBalanceUsd: Double)

    @Query("UPDATE accounts SET accountHolder = :newName WHERE accountNumber = :accNumber")
    suspend fun updateAccountHolder(accNumber: String, newName: String)

    @Query("DELETE FROM accounts WHERE accountNumber IN ('01-002-849182-4', 'HN33 1120 7482 9102') OR LOWER(accountHolder) LIKE '%carlos%mendoza%' OR LOWER(accountHolder) LIKE '%roberto%valladares%'")
    suspend fun purgeFictitiousAccounts()

    @Query("DELETE FROM accounts WHERE accountNumber = :accNumber")
    suspend fun deleteAccountByNumber(accNumber: String)
}

@Dao
interface CardDao {
    @Query("SELECT * FROM cards")
    fun getAllCards(): Flow<List<CardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<CardEntity>)

    @Update
    suspend fun updateCard(card: CardEntity)

    @Query("UPDATE cards SET cardHolder = :holder")
    suspend fun updateAllCardsHolder(holder: String)

    @Query("UPDATE cards SET isFrozen = :frozen WHERE id = :cardId")
    suspend fun setFrozenState(cardId: String, frozen: Boolean)

    @Query("UPDATE cards SET dailyLimit = :limit WHERE id = :cardId")
    suspend fun updateLimit(cardId: String, limit: Double)

    @Query("UPDATE cards SET colorTheme = :theme WHERE id = :cardId")
    suspend fun updateTheme(cardId: String, theme: String)

    @Query("UPDATE cards SET cvv = :cvv WHERE id = :cardId")
    suspend fun updateCvv(cardId: String, cvv: String)

    @Query("UPDATE cards SET onlineEnabled = :online, contactlessEnabled = :contactless, atmEnabled = :atm WHERE id = :cardId")
    suspend fun updateFeatures(cardId: String, online: Boolean, contactless: Boolean, atm: Boolean)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<NotificationEntity>)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM notifications")
    suspend fun clearAllNotifications()
}

@Dao
interface BeneficiaryDao {
    @Query("SELECT * FROM beneficiaries")
    fun getAllBeneficiaries(): Flow<List<BeneficiaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeneficiary(beneficiary: BeneficiaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(beneficiaries: List<BeneficiaryEntity>)
}

@Dao
interface SavingsVaultDao {
    @Query("SELECT * FROM savings_vaults ORDER BY createdAt ASC")
    fun getAllVaults(): Flow<List<com.example.data.model.SavingsVaultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVault(vault: com.example.data.model.SavingsVaultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vaults: List<com.example.data.model.SavingsVaultEntity>)

    @Query("UPDATE savings_vaults SET currentAmount = :newAmount WHERE id = :vaultId")
    suspend fun updateVaultAmount(vaultId: String, newAmount: Double)

    @Query("UPDATE savings_vaults SET isAutoRoundUpActive = :active WHERE id = :vaultId")
    suspend fun setAutoRoundUp(vaultId: String, active: Boolean)

    @Query("SELECT * FROM savings_vaults WHERE isAutoRoundUpActive = 1 LIMIT 1")
    suspend fun getActiveRoundUpVault(): com.example.data.model.SavingsVaultEntity?

    @Query("DELETE FROM savings_vaults WHERE id = :vaultId")
    suspend fun deleteVault(vaultId: String)
}

@Dao
interface BudgetLimitDao {
    @Query("SELECT * FROM budget_limits")
    fun getAllBudgetLimits(): Flow<List<com.example.data.model.BudgetLimitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgetLimit(limit: com.example.data.model.BudgetLimitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(limits: List<com.example.data.model.BudgetLimitEntity>)

    @Query("UPDATE budget_limits SET monthlyLimit = :limit WHERE category = :category")
    suspend fun updateLimit(category: String, limit: Double)
}

