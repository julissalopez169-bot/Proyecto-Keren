package com.example.data.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AccountEntity
import com.example.data.model.CardEntity
import com.example.data.model.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupabaseManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lumina_supabase_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "SupabaseManager"
        private const val PREF_KEY_URL = "custom_supabase_url"
        private const val PREF_KEY_ANON_KEY = "custom_supabase_anon_key"
        private const val PREF_KEY_DISABLED = "custom_supabase_disabled"

        /**
         * Cleans up Supabase URL:
         * - Strips variable name prefixes like "NEXT_PUBLIC_SUPABASE_URL="
         * - Strips quotation marks
         * - Ensures valid scheme (defaults to https://)
         * - Removes trailing slashes
         */
        fun sanitizeUrl(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            var cleaned = raw.trim()
            if (cleaned.contains("=")) {
                cleaned = cleaned.substringAfter("=").trim()
            }
            cleaned = cleaned.removeSurrounding("\"").removeSurrounding("'").trim()
            if (cleaned.isEmpty()) return ""

            if (!cleaned.startsWith("http://", ignoreCase = true) && !cleaned.startsWith("https://", ignoreCase = true)) {
                cleaned = "https://$cleaned"
            }
            return cleaned.removeSuffix("/")
        }

        /**
         * Cleans up Supabase Anon / Publishable key:
         * - Strips variable name prefixes like "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY="
         * - Strips quotation marks
         */
        fun sanitizeKey(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            var cleaned = raw.trim()
            if (cleaned.contains("=")) {
                cleaned = cleaned.substringAfter("=").trim()
            }
            return cleaned.removeSurrounding("\"").removeSurrounding("'").trim()
        }

        const val SQL_MIGRATION_SCRIPT = """-- =========================================================================
-- BANCA NOVA HONDURAS - ESQUEMA COMPLETO Y MIGRACIÓN PARA SUPABASE
-- Ejecuta este script en el SQL Editor de tu proyecto en Supabase
-- =========================================================================

-- 1. Tabla 'profiles' para almacenar todos los datos de cuenta y usuarios
CREATE TABLE IF NOT EXISTS public.profiles (
  id text PRIMARY KEY,
  name text NOT NULL,
  username text,
  email text,
  password text,
  account_number text UNIQUE,
  balance numeric DEFAULT 15000.0,
  balance_usd numeric DEFAULT 0.0,
  account_type text DEFAULT 'Banca Digital Privada',
  sar_tax_id text,
  iban_hn text,
  cvv text DEFAULT '842',
  expiry_date text DEFAULT '10/30',
  card_daily_limit numeric DEFAULT 30000.0,
  card_contactless boolean DEFAULT true,
  card_online_shopping boolean DEFAULT true,
  card_atm_withdrawals boolean DEFAULT true,
  bch_pin text DEFAULT '1234',
  created_at timestamptz DEFAULT now()
);

-- Columnas de compatibilidad si la tabla ya existía
ALTER TABLE public.profiles 
  ADD COLUMN IF NOT EXISTS username text,
  ADD COLUMN IF NOT EXISTS password text,
  ADD COLUMN IF NOT EXISTS cvv text DEFAULT '842',
  ADD COLUMN IF NOT EXISTS expiry_date text DEFAULT '10/30',
  ADD COLUMN IF NOT EXISTS card_daily_limit numeric DEFAULT 30000.0,
  ADD COLUMN IF NOT EXISTS card_contactless boolean DEFAULT true,
  ADD COLUMN IF NOT EXISTS card_online_shopping boolean DEFAULT true,
  ADD COLUMN IF NOT EXISTS card_atm_withdrawals boolean DEFAULT true,
  ADD COLUMN IF NOT EXISTS bch_pin text DEFAULT '1234';

-- Índices de búsqueda rápida
CREATE INDEX IF NOT EXISTS idx_profiles_username ON public.profiles (lower(username));
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles (lower(email));
CREATE INDEX IF NOT EXISTS idx_profiles_account_number ON public.profiles (account_number);

-- 2. Tabla 'transactions' para movimientos y transferencias
CREATE TABLE IF NOT EXISTS public.transactions (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  created_at timestamptz DEFAULT now(),
  title text NOT NULL,
  subtitle text,
  amount numeric NOT NULL,
  type text NOT NULL,
  status text DEFAULT 'completed',
  category text DEFAULT 'Transferencia',
  recipient_or_sender text,
  destination_account text,
  destination_bank text,
  origin_account text,
  concept text,
  bch_tracking_key text,
  sar_fiscal_cai text
);

CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON public.transactions (user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON public.transactions (created_at DESC);

-- 3. Tabla 'admin_credit_logs' para auditoría SAR
CREATE TABLE IF NOT EXISTS public.admin_credit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id text NOT NULL,
  user_name text,
  amount numeric NOT NULL,
  type text NOT NULL,
  concept text,
  admin_note text,
  created_at timestamptz DEFAULT now()
);

-- 4. Habilitar permisos RLS para lectura y escritura desde la aplicación móvil
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_credit_logs ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Permitir todo en profiles' AND tablename = 'profiles') THEN
    CREATE POLICY "Permitir todo en profiles" ON public.profiles FOR ALL USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Permitir todo en transactions' AND tablename = 'transactions') THEN
    CREATE POLICY "Permitir todo en transactions" ON public.transactions FOR ALL USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Permitir todo en admin_credit_logs' AND tablename = 'admin_credit_logs') THEN
    CREATE POLICY "Permitir todo en admin_credit_logs" ON public.admin_credit_logs FOR ALL USING (true) WITH CHECK (true);
  END IF;
END $$;
"""
    }

    // Get current Supabase URL (Preferences override, then BuildConfig, then default project)
    val supabaseUrl: String
        get() {
            if (prefs.getBoolean(PREF_KEY_DISABLED, false)) return ""
            val custom = sanitizeUrl(prefs.getString(PREF_KEY_URL, null))
            if (custom.isNotEmpty()) return custom
            val buildUrl = try {
                val u = sanitizeUrl(BuildConfig.SUPABASE_URL)
                if (u.isNotEmpty() && !u.contains("your-project-id")) u
                else {
                    val nextU = sanitizeUrl(BuildConfig.NEXT_PUBLIC_SUPABASE_URL)
                    if (nextU.isNotEmpty() && !nextU.contains("your-project-id")) nextU else ""
                }
            } catch (e: Exception) {
                ""
            }
            if (buildUrl.isNotEmpty()) return buildUrl
            return "https://nrgyuposoruudmqjmysv.supabase.co"
        }

    // Get current Supabase Anon Key (Preferences override, then BuildConfig, then default key)
    val supabaseAnonKey: String
        get() {
            if (prefs.getBoolean(PREF_KEY_DISABLED, false)) return ""
            val custom = sanitizeKey(prefs.getString(PREF_KEY_ANON_KEY, null))
            if (custom.isNotEmpty()) return custom
            val buildKey = try {
                val k = sanitizeKey(BuildConfig.SUPABASE_ANON_KEY)
                if (k.isNotEmpty() && !k.contains("your-supabase-anon-key")) k
                else {
                    val nextK = sanitizeKey(BuildConfig.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY)
                    if (nextK.isNotEmpty() && !nextK.contains("your-supabase-anon-key")) nextK else ""
                }
            } catch (e: Exception) {
                ""
            }
            if (buildKey.isNotEmpty()) return buildKey
            return "sb_publishable_cyyWQu4ds6E_gTrL_sD_zw_AL5RlGst"
        }

    val isConfigured: Boolean
        get() {
            if (prefs.getBoolean(PREF_KEY_DISABLED, false)) return false
            val url = supabaseUrl
            val key = supabaseAnonKey
            if (url.isEmpty() || key.isEmpty()) return false
            if (url.contains("your-project-id", ignoreCase = true) ||
                key.contains("your-supabase-anon-key", ignoreCase = true)
            ) return false
            val httpUrl = url.toHttpUrlOrNull() ?: return false
            return httpUrl.scheme.equals("http", ignoreCase = true) ||
                    httpUrl.scheme.equals("https", ignoreCase = true)
        }

    fun saveCustomCredentials(url: String, key: String) {
        val cleanUrl = sanitizeUrl(url)
        val cleanKey = sanitizeKey(key)
        prefs.edit()
            .putBoolean(PREF_KEY_DISABLED, false)
            .putString(PREF_KEY_URL, cleanUrl)
            .putString(PREF_KEY_ANON_KEY, cleanKey)
            .apply()
    }

    fun clearCustomCredentials() {
        prefs.edit()
            .putBoolean(PREF_KEY_DISABLED, true)
            .remove(PREF_KEY_URL)
            .remove(PREF_KEY_ANON_KEY)
            .apply()
    }

    /**
     * Test connectivity to Supabase REST endpoint
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val url = supabaseUrl
        val key = supabaseAnonKey

        if (!isConfigured) {
            return@withContext Result.failure(
                Exception("Supabase no está configurado aún. Ingresa la URL y Anon Key de tu proyecto.")
            )
        }

        try {
            // First check tables endpoint
            val endpoint = "$url/rest/v1/profiles?select=count&limit=1"
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?: return@withContext Result.failure(Exception("URL inválida de Supabase: $endpoint"))

            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "count=exact")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Result.success("¡Conexión exitosa con Supabase PostgreSQL! (Tabla 'profiles' activa y lista)")
            } else if (code == 404 || body.contains("relation \"public.profiles\" does not exist") || body.contains("PGRST204") || body.contains("PGRST205")) {
                // Table doesn't exist yet, but verify if root REST endpoint responds with valid credentials
                val rootRequest = Request.Builder()
                    .url(url.toHttpUrlOrNull()!!.newBuilder().addPathSegments("rest/v1/").build())
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $key")
                    .get()
                    .build()
                val rootResp = client.newCall(rootRequest).execute()
                if (rootResp.isSuccessful || rootResp.code == 200) {
                    Result.success("¡Conexión con Supabase verificada exitosamente! Recuerda ejecutar el script SQL en Supabase para crear las tablas.")
                } else {
                    Result.success("Conexión establecida con Supabase (Proyecto en línea). Falta crear la tabla 'profiles'.")
                }
            } else if (code == 401 || code == 403) {
                Result.failure(Exception("Error de autenticación ($code): La publishable/anon key no tiene permisos."))
            } else {
                Result.failure(Exception("Supabase respondió con código $code: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Supabase: ${e.message}", e)
            Result.failure(Exception("Error de conexión con Supabase: ${e.localizedMessage ?: "Revisa tu conexión a internet o la URL."}"))
        }
    }

    /**
     * Sync local Account to Supabase profiles table
     */
    suspend fun syncProfileToSupabase(
        account: AccountEntity,
        username: String = "carlosmendoza",
        email: String = "carlos.mendoza@bancanova.hn"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.success(false)

        try {
            val url = supabaseUrl
            val key = supabaseAnonKey
            val endpoint = "$url/rest/v1/profiles?on_conflict=account_number"
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?: return@withContext Result.failure(Exception("URL inválida de Supabase: $endpoint"))

            val json = JSONObject().apply {
                put("id", account.accountNumber)
                put("name", account.accountHolder)
                put("username", username)
                put("email", email)
                put("account_number", account.accountNumber)
                put("balance", account.balanceHnl)
                put("balance_usd", account.balanceUsd)
                put("account_type", account.accountType)
                put("sar_tax_id", account.sarTaxId)
                put("iban_hn", account.ibanHn)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Profile successfully synced to Supabase")
                Result.success(true)
            } else {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "Failed to sync profile: HTTP ${response.code} $errBody")
                Result.failure(Exception("Error al sincronizar perfil: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync profile exception", e)
            Result.failure(e)
        }
    }

    /**
     * Push a new Transaction to Supabase
     */
    suspend fun pushTransactionToSupabase(
        transaction: TransactionEntity,
        userId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.success(false)

        try {
            val url = supabaseUrl
            val key = supabaseAnonKey
            val endpoint = "$url/rest/v1/transactions"
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?: return@withContext Result.failure(Exception("URL inválida de Supabase: $endpoint"))

            val json = JSONObject().apply {
                put("id", transaction.id)
                put("user_id", userId)
                put("title", transaction.title)
                put("subtitle", transaction.subtitle)
                put("amount", transaction.amount)
                put("type", if (transaction.isIncome) "income" else "expense")
                put("status", "completed")
                put("category", transaction.category)
                put("recipient_or_sender", transaction.recipientOrSender)
                put("destination_bank", transaction.bankName)
                put("bch_tracking_key", transaction.referenceNumber)
                put("sar_fiscal_cai", transaction.sarAuthCode)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Transaction pushed to Supabase")
                Result.success(true)
            } else {
                Log.w(TAG, "Failed to push transaction: HTTP ${response.code}")
                Result.failure(Exception("Error al enviar transacción a Supabase: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push transaction exception", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch all remote transactions from Supabase
     */
    suspend fun fetchTransactionsFromSupabase(
        userId: String
    ): Result<List<TransactionEntity>> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.success(emptyList())

        try {
            val url = supabaseUrl
            val key = supabaseAnonKey
            val endpoint = "$url/rest/v1/transactions?user_id=eq.$userId&order=created_at.desc&limit=50"
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?: return@withContext Result.failure(Exception("URL inválida de Supabase: $endpoint"))

            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                val jsonArray = JSONArray(body)
                val list = mutableListOf<TransactionEntity>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    list.add(
                        TransactionEntity(
                            id = item.optString("id", java.util.UUID.randomUUID().toString()),
                            title = item.optString("title", "Transacción"),
                            subtitle = item.optString("subtitle", "Banca Digital"),
                            amount = item.optDouble("amount", 0.0),
                            isIncome = item.optString("type") == "income",
                            timestamp = System.currentTimeMillis(),
                            category = item.optString("category", "Transferencia"),
                            recipientOrSender = item.optString("recipient_or_sender", "Usuario Nova"),
                            bankName = item.optString("destination_bank", "Lumina Bank"),
                            referenceNumber = item.optString("bch_tracking_key", "ACH-SUPABASE"),
                            sarAuthCode = item.optString("sar_fiscal_cai", "SAR-HN-OK")
                        )
                    )
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Error al obtener transacciones: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch transactions exception", e)
            Result.failure(e)
        }
    }
}
