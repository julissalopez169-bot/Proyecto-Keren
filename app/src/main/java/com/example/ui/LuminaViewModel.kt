package com.example.ui

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LuminaRepository
import com.example.data.model.AccountEntity
import com.example.data.model.BeneficiaryEntity
import com.example.data.model.CardEntity
import com.example.data.model.NotificationEntity
import com.example.data.model.TransactionEntity
import com.example.notification.LuminaNotificationManager
import com.example.security.BiometricHelper
import com.example.security.BiometricStatus
import com.example.update.AppUpdateInfo
import com.example.update.AppUpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen {
    DASHBOARD,
    TRANSFERS,
    CARDS,
    TRANSACTIONS,
    NOTIFICATIONS,
    QR_PAY,
    SECURITY,
    VAULTS,
    BUDGET
}

data class TransferState(
    val recipientName: String = "",
    val accountNumber: String = "",
    val bankName: String = "Lumina Bank",
    val amountText: String = "",
    val concept: String = "",
    val isTransferring: Boolean = false,
    val errorMessage: String? = null,
    val lastCompletedTransaction: TransactionEntity? = null
)

class LuminaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LuminaRepository.getInstance(application)
    val notificationManager = LuminaNotificationManager.getInstance(application)
    val biometricHelper = BiometricHelper(application)
    val appUpdateManager = AppUpdateManager.getInstance(application, repository.supabaseManager, notificationManager)

    // App Update State
    private val _availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<AppUpdateInfo?> = _availableUpdate.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    // Current Screen
    private val _currentScreen = MutableStateFlow(Screen.DASHBOARD)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Screen navigation stack for proper back handling
    private val screenStack = mutableListOf(Screen.DASHBOARD)

    // Security & Auth Lock State
    private val _isAppLocked = MutableStateFlow(true)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    // Inactivity Timeout (40 seconds without signal/touch interaction)
    companion object {
        const val INACTIVITY_TIMEOUT_SECONDS = 40L
    }

    private val _inactivitySecondsRemaining = MutableStateFlow(40)
    val inactivitySecondsRemaining: StateFlow<Int> = _inactivitySecondsRemaining.asStateFlow()

    private val _lockReason = MutableStateFlow<String?>(null)
    val lockReason: StateFlow<String?> = _lockReason.asStateFlow()

    private var lastUserActivityTimestamp = System.currentTimeMillis()
    private var inactivityWatchdogJob: Job? = null

    private val _biometricStatus = MutableStateFlow(biometricHelper.checkBiometricStatus())
    val biometricStatus: StateFlow<BiometricStatus> = _biometricStatus.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    // Dark / Light Theme Mode
    private val _isDarkMode = MutableStateFlow(biometricHelper.isDarkMode)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    // Privacy & Display
    private val _isBalanceHidden = MutableStateFlow(false)
    val isBalanceHidden: StateFlow<Boolean> = _isBalanceHidden.asStateFlow()

    private val _isUsdCurrency = MutableStateFlow(false)
    val isUsdCurrency: StateFlow<Boolean> = _isUsdCurrency.asStateFlow()

    // Transfer Form
    private val _transferState = MutableStateFlow(TransferState())
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    // Selected Transaction for SAR Receipt modal
    private val _selectedTransaction = MutableStateFlow<TransactionEntity?>(null)
    val selectedTransaction: StateFlow<TransactionEntity?> = _selectedTransaction.asStateFlow()

    // Notification permission banner state
    private val _showNotificationPermissionBanner = MutableStateFlow(!notificationManager.hasNotificationPermission())
    val showNotificationPermissionBanner: StateFlow<Boolean> = _showNotificationPermissionBanner.asStateFlow()

    // Repository Flows
    val account: StateFlow<AccountEntity?> = repository.accountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cards: StateFlow<List<CardEntity>> = repository.cardsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = repository.transactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<NotificationEntity>> = repository.notificationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadNotificationsCount: StateFlow<Int> = repository.unreadNotificationsCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val beneficiaries: StateFlow<List<BeneficiaryEntity>> = repository.beneficiariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaults: StateFlow<List<com.example.data.model.SavingsVaultEntity>> = repository.vaultsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val budgetLimits: StateFlow<List<com.example.data.model.BudgetLimitEntity>> = repository.budgetLimitsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val registeredAccounts: StateFlow<List<AccountEntity>> = repository.allAccountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshBiometricStatus()
        viewModelScope.launch {
            repository.checkAndInitializeData()
        }
        // If biometric and lock is disabled, unlock immediately
        if (!biometricHelper.isBiometricEnabled) {
            _isAppLocked.value = false
            startInactivityMonitor()
        }
        if (repository.supabaseManager.isConfigured) {
            viewModelScope.launch {
                val res = repository.supabaseManager.testConnection()
                res.onSuccess { msg ->
                    _supabaseStatusMessage.value = msg
                }
            }
        }
        // Check for updates in background
        checkForUpdates(forceCheck = false)
    }

    fun navigateTo(screen: Screen) {
        onUserActivityDetected()
        if (_currentScreen.value != screen) {
            screenStack.add(screen)
            _currentScreen.value = screen
        }
    }

    fun navigateBack(): Boolean {
        onUserActivityDetected()
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
            _currentScreen.value = screenStack.last()
            return true
        }
        return false
    }

    fun refreshBiometricStatus() {
        _biometricStatus.value = biometricHelper.checkBiometricStatus()
    }

    fun unlockWithBiometrics(accountToSelect: AccountEntity? = null, typedUsername: String? = null) {
        _isAppLocked.value = false
        _pinError.value = null
        _lockReason.value = null
        startInactivityMonitor()

        viewModelScope.launch {
            if (accountToSelect != null) {
                repository.switchUserAccount(
                    accountToSelect.accountHolder,
                    accountToSelect.accountNumber,
                    accountToSelect.balanceHnl
                )
            } else if (!typedUsername.isNullOrBlank()) {
                val matched = repository.findAccount(typedUsername.trim())
                if (matched != null) {
                    repository.switchUserAccount(matched.accountHolder, matched.accountNumber, matched.balanceHnl)
                } else {
                    val formattedName = typedUsername.trim()
                        .replace(".", " ")
                        .replace("_", " ")
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
                    val accNum = "HN" + Math.abs(typedUsername.hashCode()).toString().take(8).padEnd(8, '7')
                    repository.switchUserAccount(formattedName, accNum, 25000.0)
                }
            }
        }

        // Trigger security notification of successful secure login
        notificationManager.sendPushNotification(
            title = "Acceso Seguro con Huella",
            message = "Inicio de sesión exitoso mediante huella digital autorizada.",
            category = "security",
            channelId = LuminaNotificationManager.CHANNEL_SECURITY
        )
    }

    fun checkPin(pin: String): Boolean {
        if (biometricHelper.verifyPin(pin)) {
            _isAppLocked.value = false
            _pinError.value = null
            _lockReason.value = null
            startInactivityMonitor()
            return true
        } else {
            _pinError.value = "PIN incorrecto. Intenta de nuevo."
            return false
        }
    }

    fun loginWithCredentials(username: String, password: String): Boolean {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isNotBlank() && cleanPass.isNotEmpty()) {
            _isAppLocked.value = false
            _pinError.value = null
            _lockReason.value = null
            startInactivityMonitor()

            viewModelScope.launch {
                val matched = repository.findAccount(cleanUser)
                if (matched != null) {
                    repository.switchUserAccount(matched.accountHolder, matched.accountNumber, matched.balanceHnl)
                } else {
                    val formattedName = cleanUser
                        .replace(".", " ")
                        .replace("_", " ")
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
                    val accNum = "HN" + Math.abs(cleanUser.hashCode()).toString().take(8).padEnd(8, '7')
                    repository.switchUserAccount(formattedName, accNum, 25000.0)
                }
            }

            notificationManager.sendPushNotification(
                title = "Acceso a Lumina Bank",
                message = "Sesión iniciada correctamente con credenciales bancarias.",
                category = "security",
                channelId = LuminaNotificationManager.CHANNEL_SECURITY
            )
            return true
        } else {
            _pinError.value = "Por favor ingresa tu usuario y contraseña de Lumina Bank."
            return false
        }
    }

    fun deleteRegisteredAccount(accountNumber: String) {
        viewModelScope.launch {
            repository.deleteAccount(accountNumber)
        }
    }

    fun clearAuthError() {
        _pinError.value = null
    }

    fun registerAccount(
        fullName: String,
        username: String,
        email: String,
        phone: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            repository.registerNewAccount(fullName, username, email, 15000.0)
            _isAppLocked.value = false
            _pinError.value = null
            _lockReason.value = null
            startInactivityMonitor()
            onSuccess()
        }
    }

    fun switchUserAccount(name: String, accountNumber: String, balance: Double) {
        viewModelScope.launch {
            repository.switchUserAccount(name, accountNumber, balance)
            _isAppLocked.value = false
            _pinError.value = null
            _lockReason.value = null
            startInactivityMonitor()
        }
    }

    // =========================================================================
    // INACTIVITY WATCHDOG (40 SECONDS IDLE TIMEOUT)
    // =========================================================================
    fun onUserActivityDetected() {
        lastUserActivityTimestamp = System.currentTimeMillis()
        if (_inactivitySecondsRemaining.value != 40) {
            _inactivitySecondsRemaining.value = 40
        }
    }

    fun startInactivityMonitor() {
        inactivityWatchdogJob?.cancel()
        lastUserActivityTimestamp = System.currentTimeMillis()
        _inactivitySecondsRemaining.value = 40

        inactivityWatchdogJob = viewModelScope.launch {
            while (!_isAppLocked.value) {
                delay(1000L)
                if (_isAppLocked.value) break

                val elapsedSeconds = ((System.currentTimeMillis() - lastUserActivityTimestamp) / 1000L).toInt()
                val remaining = (INACTIVITY_TIMEOUT_SECONDS.toInt() - elapsedSeconds).coerceAtLeast(0)
                _inactivitySecondsRemaining.value = remaining

                if (elapsedSeconds >= INACTIVITY_TIMEOUT_SECONDS) {
                    lockAppDueToInactivity()
                    break
                }
            }
        }
    }

    fun lockAppDueToInactivity() {
        if (_isAppLocked.value) return
        _isAppLocked.value = true
        _lockReason.value = "Sesión cerrada por inactividad: 40 segundos sin recibir señal ni interacción. Ingresa nuevamente por tu seguridad."
        _pinError.value = null
        inactivityWatchdogJob?.cancel()

        notificationManager.sendPushNotification(
            title = "Sesión cerrada por inactividad",
            message = "Han transcurrido 40 segundos sin recibir señal en pantalla. Tu sesión ha sido protegida.",
            category = "security",
            channelId = LuminaNotificationManager.CHANNEL_SECURITY
        )
    }

    fun testInactivityLockNow() {
        lockAppDueToInactivity()
    }

    fun toggleDarkMode() {
        val newMode = !_isDarkMode.value
        _isDarkMode.value = newMode
        biometricHelper.isDarkMode = newMode
    }

    fun setDarkMode(dark: Boolean) {
        _isDarkMode.value = dark
        biometricHelper.isDarkMode = dark
    }

    fun lockApp() {
        _isAppLocked.value = true
        _lockReason.value = null
        inactivityWatchdogJob?.cancel()
    }

    fun toggleBalanceVisibility() {
        _isBalanceHidden.value = !_isBalanceHidden.value
    }

    fun toggleCurrency() {
        _isUsdCurrency.value = !_isUsdCurrency.value
    }

    fun dismissNotificationBanner() {
        _showNotificationPermissionBanner.value = false
    }

    fun onNotificationPermissionUpdated() {
        _showNotificationPermissionBanner.value = !notificationManager.hasNotificationPermission()
    }

    // Transfer State Management
    fun updateTransferRecipient(name: String) {
        _transferState.value = _transferState.value.copy(recipientName = name, errorMessage = null)
    }

    fun updateTransferAccount(account: String) {
        _transferState.value = _transferState.value.copy(accountNumber = account, errorMessage = null)
    }

    fun updateTransferBank(bank: String) {
        _transferState.value = _transferState.value.copy(bankName = bank, errorMessage = null)
    }

    fun updateTransferAmount(amount: String) {
        _transferState.value = _transferState.value.copy(amountText = amount, errorMessage = null)
    }

    fun updateTransferConcept(concept: String) {
        _transferState.value = _transferState.value.copy(concept = concept)
    }

    fun selectBeneficiary(beneficiary: BeneficiaryEntity) {
        _transferState.value = _transferState.value.copy(
            recipientName = beneficiary.name,
            accountNumber = beneficiary.accountNumber,
            bankName = beneficiary.bankName,
            errorMessage = null
        )
    }

    fun executeTransfer(onSuccessReceipt: (TransactionEntity) -> Unit) {
        val state = _transferState.value
        val amount = state.amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _transferState.value = state.copy(errorMessage = "Ingresa un monto válido mayor a L 0.00")
            return
        }

        if (state.recipientName.isBlank()) {
            _transferState.value = state.copy(errorMessage = "Ingresa el nombre del destinatario")
            return
        }

        if (state.accountNumber.isBlank()) {
            _transferState.value = state.copy(errorMessage = "Ingresa el número de cuenta de destino")
            return
        }

        _transferState.value = state.copy(isTransferring = true, errorMessage = null)

        viewModelScope.launch {
            val result = repository.performTransfer(
                amount = amount,
                recipientName = state.recipientName,
                recipientAccount = state.accountNumber,
                bankName = state.bankName,
                concept = state.concept
            )

            result.onSuccess { transaction ->
                _transferState.value = TransferState(lastCompletedTransaction = transaction)
                onSuccessReceipt(transaction)
            }.onFailure { error ->
                _transferState.value = _transferState.value.copy(
                    isTransferring = false,
                    errorMessage = error.localizedMessage ?: "Error al procesar la transferencia"
                )
            }
        }
    }

    fun dismissReceipt() {
        _transferState.value = _transferState.value.copy(lastCompletedTransaction = null)
    }

    fun setSelectedTransaction(transaction: TransactionEntity?) {
        _selectedTransaction.value = transaction
    }

    // Card Management
    fun toggleCardFreeze(card: CardEntity) {
        viewModelScope.launch {
            repository.toggleCardFreeze(card)
        }
    }

    fun updateCardLimit(cardId: String, newLimit: Double) {
        viewModelScope.launch {
            repository.updateCardLimit(cardId, newLimit)
        }
    }

    fun updateCardTheme(cardId: String, newTheme: String, tierName: String) {
        viewModelScope.launch {
            repository.updateCardTheme(cardId, newTheme, tierName)
        }
    }

    fun regenerateCardCvv(cardId: String, tierName: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val newCvv = repository.regenerateCvv(cardId, tierName)
            onDone(newCvv)
        }
    }

    fun updateCardFeatures(cardId: String, online: Boolean, contactless: Boolean, atm: Boolean) {
        viewModelScope.launch {
            repository.updateCardFeatures(cardId, online, contactless, atm)
        }
    }

    fun addNewCard(tier: String, type: String, colorTheme: String) {
        viewModelScope.launch {
            repository.addNewCard(tier, type, colorTheme)
        }
    }

    // Notifications Management & Simulation
    fun markNotificationAsRead(id: String) {
        viewModelScope.launch {
            repository.markNotificationAsRead(id)
        }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch {
            repository.markAllNotificationsAsRead()
        }
    }

    fun clearAllNotifications() {
        viewModelScope.launch {
            repository.clearAllNotifications()
        }
    }

    fun simulateIncomingDeposit(amount: Double, sender: String, bank: String) {
        viewModelScope.launch {
            repository.simulateIncomingTransfer(
                amount = amount,
                senderName = sender,
                bankName = bank,
                note = "Acreditación ACH en tiempo real"
            )
        }
    }

    fun schedulePush(delaySeconds: Int, title: String, message: String, category: String) {
        notificationManager.scheduleSimulatedPush(delaySeconds, title, message, category)
    }

    // =========================================================================
    // SUPABASE INTEGRATION & SYNC
    // =========================================================================
    val supabaseManager get() = repository.supabaseManager

    private val _isSupabaseConfigured = MutableStateFlow(repository.supabaseManager.isConfigured)
    val isSupabaseConfigured: StateFlow<Boolean> = _isSupabaseConfigured.asStateFlow()

    private val _supabaseStatusMessage = MutableStateFlow<String?>(null)
    val supabaseStatusMessage: StateFlow<String?> = _supabaseStatusMessage.asStateFlow()

    private val _isSupabaseLoading = MutableStateFlow(false)
    val isSupabaseLoading: StateFlow<Boolean> = _isSupabaseLoading.asStateFlow()

    fun refreshSupabaseConfigStatus() {
        _isSupabaseConfigured.value = repository.supabaseManager.isConfigured
    }

    fun saveSupabaseCredentials(url: String, key: String) {
        repository.supabaseManager.saveCustomCredentials(url, key)
        _isSupabaseConfigured.value = repository.supabaseManager.isConfigured
    }

    fun clearSupabaseCredentials() {
        repository.supabaseManager.clearCustomCredentials()
        _isSupabaseConfigured.value = repository.supabaseManager.isConfigured
        _supabaseStatusMessage.value = "Configuración de Supabase eliminada."
    }

    fun testSupabaseConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isSupabaseLoading.value = true
            _supabaseStatusMessage.value = "Conectando con Supabase PostgreSQL..."
            val result = repository.supabaseManager.testConnection()
            _isSupabaseLoading.value = false
            _isSupabaseConfigured.value = repository.supabaseManager.isConfigured
            result.fold(
                onSuccess = { msg ->
                    _supabaseStatusMessage.value = msg
                    onResult(true, msg)
                },
                onFailure = { err ->
                    val errMsg = err.localizedMessage ?: "Error de conexión"
                    _supabaseStatusMessage.value = errMsg
                    onResult(false, errMsg)
                }
            )
        }
    }

    fun syncDataWithSupabase(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isSupabaseLoading.value = true
            _supabaseStatusMessage.value = "Sincronizando perfiles y transacciones..."
            val result = repository.syncAllWithSupabase()
            _isSupabaseLoading.value = false
            result.fold(
                onSuccess = { msg ->
                    _supabaseStatusMessage.value = msg
                    onResult(true, msg)
                },
                onFailure = { err ->
                    val errMsg = err.localizedMessage ?: "Error al sincronizar con Supabase"
                    _supabaseStatusMessage.value = errMsg
                    onResult(false, errMsg)
                }
            )
        }
    }

    fun clearSupabaseStatusMessage() {
        _supabaseStatusMessage.value = null
    }

    // =========================================================================
    // ACTUALIZACIONES DE LA APLICACIÓN (OTA / IN-APP UPDATES)
    // =========================================================================
    fun checkForUpdates(forceCheck: Boolean = false, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            val result = appUpdateManager.checkForUpdates(forceCheck)
            _isCheckingUpdate.value = false
            result.onSuccess { update ->
                _availableUpdate.value = update
                if (update != null) {
                    onResult?.invoke(true, "Nueva versión v${update.versionName} disponible")
                } else {
                    onResult?.invoke(false, "Tu aplicación está actualizada a la última versión (v${appUpdateManager.currentVersionName})")
                }
            }.onFailure { err ->
                onResult?.invoke(false, "No se pudo verificar actualizaciones: ${err.message ?: "Sin conexión"}")
            }
        }
    }

    fun snoozeUpdate() {
        val update = _availableUpdate.value
        if (update != null) {
            appUpdateManager.snoozeUpdate(update.versionCode)
        }
        _availableUpdate.value = null
    }

    fun dismissUpdateDialog() {
        _availableUpdate.value = null
    }

    fun simulateUpdateForTesting(onTriggered: () -> Unit = {}) {
        val simulated = appUpdateManager.simulateUpdate()
        _availableUpdate.value = simulated
        onTriggered()
    }

    // =========================================================================
    // METAS DE AHORRO / BÓVEDAS (ALCANCÍAS DIGITALES)
    // =========================================================================
    fun createVault(name: String, targetAmount: Double, category: String, colorHex: String) {
        viewModelScope.launch {
            repository.createVault(name, targetAmount, category, colorHex)
        }
    }

    fun depositToVault(
        vault: com.example.data.model.SavingsVaultEntity,
        amount: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val currentBal = account.value?.balanceHnl ?: 0.0
            if (currentBal < amount) {
                onResult(false, "Saldo insuficiente en tu cuenta principal")
                return@launch
            }
            val acc = account.value
            if (acc != null) {
                // Deduce de cuenta
                val newBal = acc.balanceHnl - amount
                // Suma a la bóveda
                repository.updateVaultAmount(vault.id, vault.currentAmount + amount)
                repository.simulateIncomingTransfer(0.0, "", "", "") // Trigger flow or direct balance update:
                // Direct update
                repository.database.accountDao().updateBalance(acc.accountNumber, newBal, newBal / 24.80)
                notificationManager.sendPushNotification(
                    title = "Aporte a Bóveda Acreditado",
                    message = "Has guardado L ${String.format("%,.2f", amount)} en tu meta '${vault.name}'.",
                    category = "transfers",
                    channelId = LuminaNotificationManager.CHANNEL_TRANSFERS
                )
                onResult(true, "Aporte guardado con éxito en '${vault.name}'")
            }
        }
    }

    fun withdrawFromVault(
        vault: com.example.data.model.SavingsVaultEntity,
        amount: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            if (vault.currentAmount < amount) {
                onResult(false, "Monto mayor al saldo guardado en la bóveda")
                return@launch
            }
            val acc = account.value
            if (acc != null) {
                val newVaultAmount = vault.currentAmount - amount
                val newBal = acc.balanceHnl + amount
                repository.updateVaultAmount(vault.id, newVaultAmount)
                repository.database.accountDao().updateBalance(acc.accountNumber, newBal, newBal / 24.80)
                notificationManager.sendPushNotification(
                    title = "Retiro de Bóveda",
                    message = "Se transfirieron L ${String.format("%,.2f", amount)} desde '${vault.name}' a tu saldo disponible.",
                    category = "transfers",
                    channelId = LuminaNotificationManager.CHANNEL_TRANSFERS
                )
                onResult(true, "Fondos devueltos a tu cuenta disponible")
            }
        }
    }

    fun toggleVaultAutoRoundUp(vaultId: String, active: Boolean) {
        viewModelScope.launch {
            repository.toggleVaultAutoRoundUp(vaultId, active)
        }
    }

    fun deleteVault(vaultId: String) {
        viewModelScope.launch {
            repository.deleteVault(vaultId)
        }
    }

    // =========================================================================
    // PRESUPUESTO MENSUAL Y GASTOS POR CATEGORÍA
    // =========================================================================
    fun updateBudgetLimit(category: String, limit: Double) {
        viewModelScope.launch {
            repository.updateBudgetLimit(category, limit)
        }
    }

    // =========================================================================
    // PAGO RÁPIDO CON CÓDIGO QR NOVA
    // =========================================================================
    fun executeQrPayment(
        merchant: String,
        amount: Double,
        concept: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.executeQrPayment(merchant, amount, concept)
            result.fold(
                onSuccess = { tx ->
                    _selectedTransaction.value = tx
                    onResult(true, "Pago de L ${String.format("%,.2f", amount)} completado exitosamente a $merchant")
                },
                onFailure = { err ->
                    onResult(false, err.localizedMessage ?: "Error al procesar pago QR")
                }
            )
        }
    }

    // =========================================================================
    // RECEPCIÓN DE TRANSFERENCIAS EN TIEMPO REAL (WEBSOCKETS / SUPABASE REALTIME)
    // =========================================================================
    fun simulateRealtimeIncomingTransfer(
        amount: Double,
        sender: String,
        bank: String,
        concept: String = "Transferencia instantánea ACH / WebSockets"
    ) {
        viewModelScope.launch {
            val tx = repository.receiveRealtimeIncomingTransfer(amount, sender, bank, concept)
            _selectedTransaction.value = tx
        }
    }
}
