//
//  AuthAPI.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound.auth

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.InstantSerializer
import earth.outbound.networking.jsonBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * Request and response shapes for the backend's auth endpoints.
 *
 *     POST /auth/login     { email, password }                        -> TokenRead
 *     POST /auth/register  { email, password, display_name, ... }     -> TokenRead (201)
 *     POST /auth/logout                                               -> 204 (no body)
 *     GET  /users/me                                                  -> UserRead
 */
object AuthAPI {
    /** `Credentials`: body for `POST /auth/login`. */
    @Serializable
    data class LoginRequest(val email: String, val password: String)

    /** `UserCreate`: body for `POST /auth/register`. */
    @Serializable
    data class RegisterRequest(
        val email: String,
        val password: String,
        val displayName: String,
        val avatarUrl: String? = null,
    )

    /** `TokenRead`: returned by login and register. */
    @Serializable
    data class Session(val token: String, val expiresAt: Instant, val user: User)

    fun login(email: String, password: String) = Endpoint(
        path = "/auth/login",
        method = HTTPMethod.POST,
        body = jsonBody(LoginRequest(email = email, password = password)),
        requiresAuth = false,
    )

    fun register(email: String, password: String, displayName: String, avatarUrl: String? = null) = Endpoint(
        path = "/auth/register",
        method = HTTPMethod.POST,
        body = jsonBody(RegisterRequest(email = email, password = password, displayName = displayName, avatarUrl = avatarUrl)),
        requiresAuth = false,
    )

    val logout = Endpoint(path = "/auth/logout", method = HTTPMethod.POST)

    val me = Endpoint(path = "/users/me")
}
