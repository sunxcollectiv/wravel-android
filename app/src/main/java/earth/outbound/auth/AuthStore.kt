//
//  AuthStore.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import earth.outbound.OutboundApp
import earth.outbound.PushNotifications
import earth.outbound.networking.APIClient
import earth.outbound.networking.APIError
import earth.outbound.networking.InstantSerializer
import earth.outbound.networking.Keychain
import earth.outbound.networking.ResponseCache
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import java.time.Instant

/**
 * `UserRead` from the backend. JSON snake_case keys map to these camelCase
 * properties automatically.
 */
@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val createdAt: Instant,
)

sealed class AuthError(message: String) : Exception(message) {
    class MissingFields : AuthError("Please fill in all fields.")
    class InvalidEmail : AuthError("Please enter a valid email address.")
    class PasswordTooShort : AuthError("Password must be at least 8 characters.")
}

/**
 * Holds the signed-in user and decides which screen the app shows.
 *
 * The auth token lives in the `Keychain`; the user profile is cached in the app's
 * preferences so the app can show the main screen immediately on launch, then
 * refreshes it from `/me` in the background.
 */
class AuthStore(private val api: APIClient = APIClient.shared) {
    var currentUser by mutableStateOf<User?>(null)
        private set

    val isSignedIn: Boolean get() = currentUser != null

    private val userKey = "auth.currentUser"

    init {
        val token = Keychain.string(tokenKey)
        val user = OutboundApp.defaults.getString(userKey, null)?.let {
            try {
                APIClient.json.decodeFromString<User>(it)
            } catch (_: Exception) {
                null
            }
        }
        if (token != null && user != null) {
            api.token = token
            currentUser = user
        }
    }

    suspend fun signIn(email: String, password: String) {
        val validEmail = validate(email, password)
        val session = api.send<AuthAPI.Session>(AuthAPI.login(email = validEmail, password = password))
        save(session)
    }

    suspend fun signUp(name: String, email: String, password: String) {
        val trimmedName = name.trimWhitespaces()
        if (trimmedName.isEmpty()) throw AuthError.MissingFields()
        val validEmail = validate(email, password)
        val session = api.send<AuthAPI.Session>(
            AuthAPI.register(email = validEmail, password = password, displayName = trimmedName)
        )
        save(session)
    }

    fun signOut() {
        // Tell the server to stop pushing here and end the session, but sign out
        // locally regardless of the result. `clear()` drops the shared client's token
        // right away, so these go through a client that keeps it.
        api.token?.let { token ->
            val client = APIClient()
            client.token = token
            OutboundApp.scope.launch {
                PushNotifications.shared.unregister(client)
                try {
                    client.sendIgnoringResponse(AuthAPI.logout)
                } catch (_: APIError) {
                    // Signed out here either way; the session just runs out on the server.
                }
            }
        }
        clear()
    }

    /** Re-fetches the user so profile changes show up, and signs out if the token is no longer valid. */
    suspend fun refreshUser() {
        if (!isSignedIn) return
        val token = api.token
        try {
            val user = api.send<User>(AuthAPI.me)
            // Unless the user signed out (or in again) while this was in flight.
            if (api.token == token) setUser(user)
        } catch (_: APIError.Unauthorized) {
            if (api.token == token) clear()
        } catch (_: APIError) {
            // Offline or server hiccup: keep the cached user.
        }
    }

    private fun validate(email: String, password: String): String {
        val trimmed = email.trimWhitespaces().lowercase()
        if (trimmed.isEmpty() || password.isEmpty()) throw AuthError.MissingFields()
        if (!trimmed.contains("@") || !trimmed.contains(".")) throw AuthError.InvalidEmail()
        if (password.length < 8) throw AuthError.PasswordTooShort()
        return trimmed
    }

    private fun save(session: AuthAPI.Session) {
        api.token = session.token
        Keychain.set(session.token, tokenKey)
        setUser(session.user)
    }

    private fun clear() {
        api.token = null
        Keychain.set(null, tokenKey)
        // Saved trips belong to this user; the next one to sign in starts fresh.
        ResponseCache.shared.removeAll()
        setUser(null)
    }

    private fun setUser(user: User?) {
        currentUser = user
        val editor = OutboundApp.defaults.edit()
        if (user != null) {
            editor.putString(userKey, APIClient.cacheJson.encodeToString(user))
        } else {
            editor.remove(userKey)
        }
        editor.apply()
    }

    companion object {
        /** Where the auth token is kept in the `Keychain`. */
        const val tokenKey = "auth.token"
    }
}

/** Without the spaces and tabs at either end, like Swift's `trimmingCharacters(in: .whitespaces)`. */
private fun String.trimWhitespaces(): String =
    trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** The app's `AuthStore`, provided at the root by `MainActivity`. */
val LocalAuthStore = staticCompositionLocalOf<AuthStore> { error("No AuthStore provided") }
