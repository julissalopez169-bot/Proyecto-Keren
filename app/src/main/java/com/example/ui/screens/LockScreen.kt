package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AccountEntity
import com.example.security.BiometricStatus
import com.example.ui.theme.LuminaBlue
import com.example.ui.theme.LuminaEmerald
import com.example.ui.theme.LuminaGold
import com.example.ui.theme.LuminaRose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla de Iniciar Sesión (Login) estilo Banca en Línea BAC Credomatic.
 * Enfoque en autenticación biométrica nativa de alta seguridad y fallback inmediato a contraseña.
 */
@Composable
fun LockScreen(
    biometricStatus: BiometricStatus,
    pinError: String?,
    lockReason: String? = null,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onBiometricClick: (username: String?, onFallback: (String) -> Unit) -> Unit,
    onBiometricForAccount: ((account: AccountEntity, onFallback: (String) -> Unit) -> Unit)? = null,
    onPinEntered: (String) -> Unit = {},
    onLoginWithCredentials: (username: String, password: String) -> Unit,
    registeredAccounts: List<AccountEntity> = emptyList(),
    onSelectSavedAccount: (name: String, accountNumber: String, balance: Double) -> Unit = { _, _, _ -> },
    onRegisterAccount: (fullName: String, username: String, email: String, phone: String, password: String) -> Unit = { _, _, _, _, _ -> },
    onDeleteAccount: ((String) -> Unit)? = null,
    onClearError: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val passwordFocusRequester = remember { FocusRequester() }

    // 0 = Iniciar Sesión, 1 = Apertura de Cuenta Digital
    var selectedTab by remember { mutableIntStateOf(0) }

    // Campos de Entrada: Vacíos por defecto para que el usuario escriba su cuenta
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    // Gestión de estados de carga y concurrencia para evitar clics múltiples
    var isAuthenticatingWithPassword by remember { mutableStateOf(false) }
    var isBiometricPromptActive by remember { mutableStateOf(false) }

    // Mensaje contextual de fallback (cancelación, fallo o sensor ocupado)
    var fallbackMessage by remember { mutableStateOf<String?>(null) }

    // Formulario de Registro de Nueva Cuenta
    var regFullName by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regEmail by remember { mutableStateOf("") }
    var regPhone by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var isRegPasswordVisible by remember { mutableStateOf(false) }

    // Función de fallback centralizada que habilita contraseña y coloca el foco
    val handleFallbackToPassword: (String) -> Unit = { reason ->
        isBiometricPromptActive = false
        fallbackMessage = reason
        coroutineScope.launch {
            delay(150)
            try {
                passwordFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // Colores con estética empresarial bancaria BAC Credomatic
    val bgGradient = if (isDarkMode) {
        listOf(Color(0xFF090E17), Color(0xFF0F172A))
    } else {
        listOf(Color(0xFFF8FAFC), Color(0xFFEDF2F7))
    }
    val cardBg = if (isDarkMode) Color(0xFF131D33) else Color.White
    val textPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    val borderColor = if (isDarkMode) Color(0xFF24324D) else Color(0xFFE2E8F0)
    val inputBg = if (isDarkMode) Color(0xFF0B1220) else Color(0xFFF8FAFC)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(bgGradient))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // =========================================================================
            // 1. CABECERA LIMPIA CON BRANDING BANCARIO DISCRETO (ESTILO BAC)
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logotipo / Emblema Institucional Lumina Banca
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LuminaBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalance,
                            contentDescription = "Lumina Logo",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Lumina",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = LuminaBlue,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = " Banca",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }
                        Text(
                            text = "Banca en Línea",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = textSecondary
                        )
                    }
                }

                // Selector de Tema Institucional (Modo Oscuro / Claro)
                IconButton(
                    onClick = onToggleDarkMode,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        .testTag("btn_toggle_dark_mode")
                ) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Cambiar Tema",
                        tint = if (isDarkMode) LuminaGold else Color(0xFF334155),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // =========================================================================
            // AVISO DE SESIÓN CERRADA POR INACTIVIDAD (40 SEGUNDOS)
            // =========================================================================
            if (lockReason != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .testTag("inactivity_lock_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF451A03) else Color(0xFFFEF3C7)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(LuminaGold, LuminaGold))
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(LuminaGold.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = LuminaGold,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sesión cerrada por inactividad",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color(0xFFFDE68A) else Color(0xFF92400E)
                            )
                            Text(
                                text = "Por tu seguridad, la sesión se cerró tras 40 segundos sin actividad. Ingresa con tu huella o contraseña.",
                                fontSize = 11.sp,
                                color = if (isDarkMode) Color(0xFFFDE68A).copy(alpha = 0.9f) else Color(0xFF92400E).copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 2. TARJETA PRINCIPAL FLOTANTE CON LOS CAMPOS DE ENTRADA (ESTILO BAC)
            // =========================================================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .testTag("bac_login_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(listOf(borderColor, borderColor))
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Pestañas Segmentadas Institucionales
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = inputBg,
                        contentColor = LuminaBlue,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = LuminaBlue,
                                height = 3.dp
                            )
                        },
                        divider = {},
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                onClearError()
                                fallbackMessage = null
                            },
                            text = {
                                Text(
                                    text = "Iniciar Sesión",
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == 0) LuminaBlue else textSecondary
                                )
                            },
                            modifier = Modifier.testTag("tab_login")
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                onClearError()
                                fallbackMessage = null
                            },
                            text = {
                                Text(
                                    text = "Crear Cuenta",
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == 1) LuminaBlue else textSecondary
                                )
                            },
                            modifier = Modifier.testTag("tab_register")
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // =====================================================================
                    // VISTA 0: INICIAR SESIÓN (HUELLA NATIVA Y CONTRASEÑA FALLBACK)
                    // =====================================================================
                    if (selectedTab == 0) {
                        // Mensaje de Fallback o Error Contextual
                        val displayNotice = fallbackMessage ?: pinError
                        if (displayNotice != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (fallbackMessage != null) LuminaGold.copy(alpha = 0.15f)
                                        else LuminaRose.copy(alpha = 0.15f)
                                    )
                                    .border(
                                        1.dp,
                                        if (fallbackMessage != null) LuminaGold.copy(alpha = 0.4f)
                                        else LuminaRose.copy(alpha = 0.4f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(12.dp)
                                    .testTag("auth_feedback_notice"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (fallbackMessage != null) Icons.Default.Info else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (fallbackMessage != null) LuminaGold else LuminaRose,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = displayNotice,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (fallbackMessage != null) {
                                        if (isDarkMode) Color(0xFFFDE68A) else Color(0xFF92400E)
                                    } else LuminaRose
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // 1. Campo de Usuario / Identificación (Vacío por defecto para escritura manual)
                        Text(
                            text = "Usuario o Correo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = {
                                usernameInput = it
                                onClearError()
                                fallbackMessage = null
                            },
                            placeholder = { Text("Escribe tu usuario o correo", fontSize = 13.sp, color = textSecondary) },
                            leadingIcon = {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = textSecondary, modifier = Modifier.size(20.dp))
                            },
                            singleLine = true,
                            enabled = !isAuthenticatingWithPassword && !isBiometricPromptActive,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_bac_username"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LuminaBlue,
                                unfocusedBorderColor = borderColor,
                                focusedContainerColor = inputBg,
                                unfocusedContainerColor = inputBg,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. Campo de Contraseña (Visible/Ocultable con Eye Icon y Fallback Focus)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Contraseña",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = if (isPasswordVisible) "Ocultar" else "Mostrar",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = LuminaBlue,
                                modifier = Modifier
                                    .clickable { isPasswordVisible = !isPasswordVisible }
                                    .padding(vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                onClearError()
                                fallbackMessage = null
                            },
                            placeholder = { Text("Ingresa tu contraseña", fontSize = 13.sp, color = textSecondary) },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = textSecondary, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (isPasswordVisible) "Ocultar" else "Mostrar",
                                        tint = textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (usernameInput.isNotBlank() && passwordInput.isNotBlank()) {
                                        isAuthenticatingWithPassword = true
                                        onLoginWithCredentials(usernameInput, passwordInput)
                                        isAuthenticatingWithPassword = false
                                    } else {
                                        fallbackMessage = "Por favor completa tu usuario y contraseña."
                                    }
                                }
                            ),
                            singleLine = true,
                            enabled = !isAuthenticatingWithPassword && !isBiometricPromptActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(passwordFocusRequester)
                                .testTag("input_bac_password"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LuminaBlue,
                                unfocusedBorderColor = borderColor,
                                focusedContainerColor = inputBg,
                                unfocusedContainerColor = inputBg,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // 3. Botón de Acción Principal: INGRESAR CON CONTRASEÑA
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (usernameInput.isBlank()) {
                                    fallbackMessage = "Por favor escribe tu usuario o correo."
                                } else if (passwordInput.isBlank()) {
                                    fallbackMessage = "Por favor ingresa tu contraseña para continuar."
                                    passwordFocusRequester.requestFocus()
                                } else {
                                    isAuthenticatingWithPassword = true
                                    onLoginWithCredentials(usernameInput, passwordInput)
                                    isAuthenticatingWithPassword = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaBlue),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isAuthenticatingWithPassword && !isBiometricPromptActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_bac_login_submit")
                        ) {
                            if (isAuthenticatingWithPassword) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = "Ingresar",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 4. Divisor Estilo Institucional
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
                            Text(
                                text = "O CON AUTENTICACIÓN BIOMÉTRICA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 5. Botón Claro para Autenticar con Huella Dactilar NATIVA del teléfono
                        OutlinedButton(
                            onClick = {
                                focusManager.clearFocus()
                                onClearError()
                                fallbackMessage = null

                                // Validar estado biométrico previo a la solicitud nativa
                                if (biometricStatus != BiometricStatus.AVAILABLE) {
                                    val reason = when (biometricStatus) {
                                        BiometricStatus.NOT_ENROLLED -> "Tu celular no tiene huellas dactilares registradas en los ajustes del sistema. Por favor ingresa con tu contraseña."
                                        BiometricStatus.NO_HARDWARE -> "Este celular no posee sensor de huella dactilar. Por favor ingresa con tu contraseña."
                                        BiometricStatus.HW_UNAVAILABLE -> "Sensor biométrico ocupado o no disponible. Ingresa con tu contraseña."
                                        else -> "Biometría no disponible en este dispositivo. Ingresa con tu contraseña."
                                    }
                                    handleFallbackToPassword(reason)
                                } else {
                                    isBiometricPromptActive = true
                                    onBiometricClick(usernameInput.ifBlank { null }) { reason ->
                                        handleFallbackToPassword(reason)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isAuthenticatingWithPassword && !isBiometricPromptActive,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isDarkMode) Color(0xFF0F1A2E) else Color(0xFFF1F5F9)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.linearGradient(
                                    listOf(
                                        LuminaEmerald.copy(alpha = 0.7f),
                                        LuminaBlue.copy(alpha = 0.7f)
                                    )
                                )
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_authenticate_fingerprint")
                        ) {
                            if (isBiometricPromptActive) {
                                CircularProgressIndicator(color = LuminaEmerald, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Esperando huella...", fontSize = 12.sp, color = textPrimary)
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "Huella",
                                        tint = LuminaEmerald,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Ingresar con Huella Dactilar",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary
                                        )
                                        Text(
                                            text = "Sensor biométrico nativo del teléfono",
                                            fontSize = 10.sp,
                                            color = textSecondary
                                        )
                                    }
                                }
                            }
                        }

                        // =================================================================
                        // 6. ACCESO DIRECTO CON CUENTAS REGISTRADAS POR EL USUARIO
                        // =================================================================
                        if (registeredAccounts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
                                Text(
                                    text = "CUENTAS REGISTRADAS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textSecondary,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            registeredAccounts.forEach { acc ->
                                val initials = acc.accountHolder.split(" ")
                                    .filter { it.isNotBlank() }
                                    .take(2)
                                    .mapNotNull { it.firstOrNull()?.uppercase() }
                                    .joinToString("")
                                    .ifEmpty { "CB" }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !isAuthenticatingWithPassword && !isBiometricPromptActive) {
                                            usernameInput = acc.accountHolder
                                            fallbackMessage = null

                                            if (biometricStatus != BiometricStatus.AVAILABLE) {
                                                handleFallbackToPassword("Tu celular no tiene huellas dactilares registradas. Ingresa tu contraseña para ${acc.accountHolder}.")
                                            } else {
                                                isBiometricPromptActive = true
                                                if (onBiometricForAccount != null) {
                                                    onBiometricForAccount(acc) { reason ->
                                                        handleFallbackToPassword(reason)
                                                    }
                                                } else {
                                                    onBiometricClick(acc.accountHolder) { reason ->
                                                        handleFallbackToPassword(reason)
                                                    }
                                                }
                                            }
                                        }
                                        .testTag("account_card_${acc.accountNumber}"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = inputBg),
                                    border = CardDefaults.outlinedCardBorder().copy(
                                        brush = Brush.linearGradient(listOf(borderColor, borderColor))
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(LuminaBlue),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = initials,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = acc.accountHolder,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = textPrimary
                                                )
                                                Text(
                                                    text = "No. ${acc.accountNumber}",
                                                    fontSize = 10.sp,
                                                    color = textSecondary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Fingerprint,
                                                        contentDescription = null,
                                                        tint = LuminaEmerald,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "Tocar para ingresar con Huella",
                                                        fontSize = 10.sp,
                                                        color = LuminaEmerald,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = String.format("L %,.2f", acc.balanceHnl),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = LuminaEmerald
                                                )
                                                Text(
                                                    text = "Ingresar →",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = LuminaBlue
                                                )
                                            }
                                            if (onDeleteAccount != null) {
                                                IconButton(
                                                    onClick = {
                                                        onDeleteAccount(acc.accountNumber)
                                                        Toast.makeText(context, "Cuenta eliminada", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Eliminar cuenta",
                                                        tint = LuminaRose.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =====================================================================
                    // VISTA 1: CREAR CUENTA DIGITAL (APERTURA EN LÍNEA)
                    // =====================================================================
                    if (selectedTab == 1) {
                        Text(
                            text = "Apertura de Cuenta Digital",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "Crea tu cuenta bancaria en Lempiras con acreditación inmediata",
                            fontSize = 11.sp,
                            color = textSecondary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Nombre Completo
                        Text("Nombre Completo", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = regFullName,
                            onValueChange = { regFullName = it },
                            placeholder = { Text("Ej: Julissa López", fontSize = 12.sp, color = textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_full_name"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Usuario
                        Text("Nombre de Usuario", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = regUsername,
                            onValueChange = { regUsername = it },
                            placeholder = { Text("Ej: julissa_lopez", fontSize = 12.sp, color = textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_username"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Correo
                        Text("Correo Electrónico", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = regEmail,
                            onValueChange = { regEmail = it },
                            placeholder = { Text("Ej: julissa@gmail.com", fontSize = 12.sp, color = textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_email"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Teléfono
                        Text("Teléfono Celular", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = regPhone,
                            onValueChange = { regPhone = it },
                            placeholder = { Text("+504 9876-5432", fontSize = 12.sp, color = textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("reg_phone"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Contraseña
                        Text("Contraseña de Acceso", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = regPassword,
                            onValueChange = { regPassword = it },
                            placeholder = { Text("Crea una contraseña segura", fontSize = 12.sp, color = textSecondary) },
                            singleLine = true,
                            visualTransformation = if (isRegPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isRegPasswordVisible = !isRegPasswordVisible }) {
                                    Icon(
                                        if (isRegPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("reg_password"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (regFullName.isNotBlank() && regUsername.isNotBlank() && regPassword.isNotBlank()) {
                                    onRegisterAccount(
                                        regFullName.trim(),
                                        regUsername.trim(),
                                        regEmail.trim(),
                                        regPhone.trim(),
                                        regPassword.trim()
                                    )
                                } else {
                                    Toast.makeText(context, "Por favor completa nombre, usuario y contraseña", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LuminaBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("btn_submit_register")
                        ) {
                            Text("Abrir Cuenta Digital", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Footer institucional de seguridad
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Autenticación segura respaldada por SAR Honduras",
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }
    }
}
