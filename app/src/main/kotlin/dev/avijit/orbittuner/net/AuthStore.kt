package dev.avijit.orbittuner.net

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Minimal string storage so tests can swap out EncryptedSharedPreferences. */
interface SecureKv {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemoryKv : SecureKv {
    private val m = HashMap<String, String>()
    @Synchronized override fun get(key: String) = m[key]
    @Synchronized override fun put(key: String, value: String) { m[key] = value }
    @Synchronized override fun remove(key: String) { m.remove(key) }
}

class EncryptedKv(ctx: Context) : SecureKv {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "orbittuner_auth",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/** One consistent view of the account (read atomically). Never printed with its token. */
data class AuthSnapshot(val host: String, val userId: String, val token: String) {
    override fun toString() = "AuthSnapshot(host=$host, userId=$userId)"
}

/** The account: service host, device token and user. The token never leaves this class except via [token]. */
class AuthStore(private val kv: SecureKv) {
    constructor(ctx: Context) : this(EncryptedKv(ctx.applicationContext))

    private val _host = MutableStateFlow(kv.get(K_HOST))
    private val _user = MutableStateFlow(kv.get(K_USER)?.let(::decodeUser))
    private val _needsNewAccount = MutableStateFlow(kv.get(K_REVOKED) == "1")
    val host: StateFlow<String?> = _host.asStateFlow()
    val user: StateFlow<UserDto?> = _user.asStateFlow()
    val needsNewAccount: StateFlow<Boolean> = _needsNewAccount.asStateFlow()

    /** Device token, or null without an account or when revoked. */
    fun token(): String? = if (_needsNewAccount.value) null else kv.get(K_TOKEN)

    /** Host, user and token read together (no tearing against [save]/[markRevoked]); null unless signed in with a usable token. */
    @Synchronized
    fun snapshot(): AuthSnapshot? {
        val h = _host.value ?: return null
        val u = _user.value ?: return null
        val t = token() ?: return null
        return AuthSnapshot(h, u.id, t)
    }

    @Synchronized
    fun save(host: String, token: String, user: UserDto) {
        kv.put(K_HOST, host); kv.put(K_TOKEN, token); kv.put(K_USER, WireJson.encodeToString(UserDto.serializer(), user))
        kv.remove(K_REVOKED)
        _host.value = host; _user.value = user; _needsNewAccount.value = false
    }

    /**
     * Token was rejected as revoked: drop it, keep host/user (the library identity) until
     * AccountManager replaces the account; [needsNewAccount] turns true.
     * When [rejectedToken] is given, acts only if it is still the stored token (a late response for an
     * old token must not clobber a fresh account).
     */
    @Synchronized
    fun markRevoked(rejectedToken: String? = null) {
        if (rejectedToken != null && rejectedToken != kv.get(K_TOKEN)) return
        kv.remove(K_TOKEN); kv.put(K_REVOKED, "1")
        _needsNewAccount.value = true
    }

    @Synchronized
    fun clear() {
        listOf(K_HOST, K_TOKEN, K_USER, K_REVOKED).forEach(kv::remove)
        _host.value = null; _user.value = null; _needsNewAccount.value = false
    }

    private fun decodeUser(s: String): UserDto? =
        try { WireJson.decodeFromString(UserDto.serializer(), s) } catch (_: Exception) { null }

    private companion object {
        const val K_HOST = "host"; const val K_TOKEN = "token"; const val K_USER = "user"; const val K_REVOKED = "revoked"
    }
}
