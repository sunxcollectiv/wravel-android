//
//  APIClient.kt
//  outbound
//

package earth.outbound.networking

import earth.outbound.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.net.UnknownServiceException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

object APIConfig {
    /**
     * Debug builds (run from Android Studio) talk to the local dev server; release
     * builds use production. The emulator reaches a server running on the same
     * machine at `10.0.2.2`.
     */
    val baseURL: HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl()
}

enum class HTTPMethod {
    GET, POST, PUT, PATCH, DELETE
}

/** A request body already encoded as JSON. Make one with `jsonBody(value)`. */
class JsonBody(val json: String)

/**
 * Encodes `value` as an endpoint's JSON body, with snake_case keys and nulls left
 * out. For a key that must be sent as an explicit `null`, declare it as a
 * `JsonElement` and set it with `orJsonNull()`.
 */
inline fun <reified T> jsonBody(value: T): JsonBody = JsonBody(APIClient.json.encodeToString(value))

/** This string as a JSON value, or an explicit JSON `null`: for keys that are always sent. */
fun String?.orJsonNull(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

/** A body sent as is with its content type (e.g. a multipart upload). */
class RawBody(val data: ByteArray, val contentType: String)

/** A single API request: where it goes, how, and what it sends. */
data class Endpoint(
    val path: String,
    val method: HTTPMethod = HTTPMethod.GET,
    val query: List<Pair<String, String?>> = emptyList(),
    val body: JsonBody? = null,
    /** A body sent as is with its content type, instead of `body` encoded as JSON. */
    val rawBody: RawBody? = null,
    /** How long to wait for the response; null uses the client's default. */
    val timeout: Duration? = null,
    val requiresAuth: Boolean = true,
    /**
     * Keep the last successful response on disk, so it can still be shown offline
     * (read back with `APIClient.cached`). For GETs only.
     */
    val cachesResponse: Boolean = false,
) {
    /** What a cached response is saved under. */
    val cacheKey: String
        get() = if (query.isEmpty()) path else path + "?" + query.joinToString("&") { "${it.first}=${it.second ?: ""}" }
}

/** A `multipart/form-data` body carrying one file, as FastAPI's `UploadFile` expects. */
class MultipartFile(
    val field: String,
    val filename: String,
    val contentType: String,
    val data: ByteArray,
) {
    private val boundary = "Boundary-${UUID.randomUUID()}"

    val body: RawBody
        get() {
            val buffer = Buffer()
            buffer.writeUtf8("--$boundary\r\n")
            buffer.writeUtf8("Content-Disposition: form-data; name=\"${this.field}\"; filename=\"$filename\"\r\n")
            buffer.writeUtf8("Content-Type: $contentType\r\n\r\n")
            buffer.write(data)
            buffer.writeUtf8("\r\n--$boundary--\r\n")
            return RawBody(buffer.readByteArray(), "multipart/form-data; boundary=$boundary")
        }
}

sealed class APIError(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : APIError("Your session has expired. Please sign in again.")

    class Server(val status: Int, val serverMessage: String?) :
        APIError(serverMessage ?: "Something went wrong (error $status). Please try again.")

    class InvalidResponse : APIError("We got an unexpected response from the server.")

    class Decoding(cause: Throwable) : APIError("We got an unexpected response from the server.", cause)

    class Network(cause: Throwable) :
        APIError("Couldn't reach the server. Check your connection and try again.", cause)
}

/** What to tell the user about a failure, like Swift's `localizedDescription`. */
val Throwable.localizedDescription: String
    get() = message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."

/**
 * `Instant` as the backend's ISO-8601 timestamps, sometimes with fractional seconds
 * (e.g. "2026-10-09T12:00:00.123456Z") and sometimes with an offset instead of "Z".
 * Written back to the second, as the server expects.
 *
 * Use it on a file's models with `@file:UseSerializers(InstantSerializer::class)`.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    fun parse(string: String): Instant =
        try {
            OffsetDateTime.parse(string).toInstant()
        } catch (_: Exception) {
            try {
                // No zone at all: the server means UTC.
                Instant.parse(string + "Z")
            } catch (_: Exception) {
                throw SerializationException("Unrecognized date format: $string")
            }
        }
}

/**
 * Sends requests to the Outbound backend.
 *
 * Requests and responses are JSON with snake_case keys, which map to camelCase
 * Kotlin properties automatically. When a token is set, it's sent as
 * `Authorization: Bearer <token>`.
 */
class APIClient(
    private val baseURL: HttpUrl = APIConfig.baseURL,
    private val session: OkHttpClient = sharedSession,
    private val cache: ResponseCache = ResponseCache.shared,
    /**
     * Told whether requests reach the server; null for clients that shouldn't affect
     * the app's offline state, e.g. in tests.
     */
    private val connectivity: Connectivity? = null,
) {
    @Volatile
    var token: String? = null

    /**
     * Sends a request and decodes the JSON response. `onBodySent` is called once the
     * whole request body has gone out, e.g. to tell an upload from the server's work
     * on it.
     */
    suspend inline fun <reified Response> send(endpoint: Endpoint, noinline onBodySent: (() -> Unit)? = null): Response =
        send(endpoint, serializer<Response>(), onBodySent)

    suspend fun <Response> send(endpoint: Endpoint, serializer: KSerializer<Response>, onBodySent: (() -> Unit)? = null): Response {
        val data = perform(endpoint, onBodySent)
        // Off the caller's thread, which is usually the main one: an itinerary is a lot to decode.
        return withContext(Dispatchers.Default) {
            try {
                json.decodeFromString(serializer, data)
            } catch (error: Exception) {
                throw APIError.Decoding(error)
            }
        }
    }

    /** Sends a request whose response body we don't need. */
    suspend fun sendIgnoringResponse(endpoint: Endpoint) {
        perform(endpoint, null)
    }

    /**
     * The last successful response to a `cachesResponse` endpoint, decoded, or null if
     * none was saved (or it no longer decodes).
     */
    inline fun <reified Response> cached(endpoint: Endpoint): Response? = cached(endpoint, serializer<Response>())

    fun <Response> cached(endpoint: Endpoint, serializer: KSerializer<Response>): Response? {
        val data = cache.data(endpoint.cacheKey) ?: return null
        return try {
            json.decodeFromString(serializer, data.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    /** When the cached response to `endpoint` was saved. */
    fun cachedDate(endpoint: Endpoint): Instant? = cache.savedDate(endpoint.cacheKey)

    /**
     * Saves `value` as the cached response to `endpoint`, for a copy changed locally
     * (e.g. a trip edited in place) so it's what shows offline.
     */
    inline fun <reified Value> storeCached(value: Value, endpoint: Endpoint) = storeCached(value, serializer<Value>(), endpoint)

    fun <Value> storeCached(value: Value, serializer: KSerializer<Value>, endpoint: Endpoint) {
        val data = try {
            cacheJson.encodeToString(serializer, value)
        } catch (_: Exception) {
            return
        }
        cache.store(data.encodeToByteArray(), endpoint.cacheKey)
    }

    private suspend fun perform(endpoint: Endpoint, onBodySent: (() -> Unit)?): String {
        val request = makeRequest(endpoint, onBodySent)
        val sentToken = token
        // Like `URLRequest.timeoutInterval`, the longest the request may sit idle (e.g.
        // waiting on the server's work), not a limit on the whole exchange.
        val client = endpoint.timeout?.let { timeout ->
            session.newBuilder()
                .readTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
        } ?: session

        // OkHttp itself retries once on a kept-alive connection the server has closed
        // (e.g. while the app was in the background), on a fresh one.
        val (status, data) = try {
            client.newCall(request).awaitBody()
        } catch (error: IOException) {
            throw networkError(error)
        }

        // A gateway answering for a server that's down counts as not reaching it.
        if (status in Connectivity.gatewayStatuses) {
            connectivity?.serverUnreachable()
        } else {
            connectivity?.serverResponded()
        }

        return when {
            status in 200..299 -> {
                // Unless the user signed out while this was in flight, leaving the
                // cache cleared for whoever signs in next.
                if (endpoint.cachesResponse && endpoint.method == HTTPMethod.GET && token == sentToken) {
                    withContext(Dispatchers.IO) { cache.store(data.encodeToByteArray(), endpoint.cacheKey) }
                }
                data
            }
            // An authenticated request was rejected: the token is missing or expired.
            status == 401 && endpoint.requiresAuth -> throw APIError.Unauthorized()
            // Includes a 401 from login (wrong credentials): surface the server's message.
            else -> throw APIError.Server(status, errorMessage(data))
        }
    }

    /**
     * Wraps a failed request's error, and tells `connectivity` when it means the
     * server can't be reached: no route, no answer in time, or a connection dropped
     * part way. Not when the connection was refused for what it is (plain HTTP where
     * it isn't allowed, a certificate that doesn't check out), which waiting won't fix.
     */
    private fun networkError(error: IOException): APIError {
        val isRefused = error is UnknownServiceException || error is SSLHandshakeException ||
            error is SSLPeerUnverifiedException
        if (!isRefused) connectivity?.serverUnreachable()
        return APIError.Network(error)
    }

    private fun makeRequest(endpoint: Endpoint, onBodySent: (() -> Unit)?): Request {
        val url = baseURL.newBuilder()
            .addPathSegments(endpoint.path.trimStart('/'))
            .apply { endpoint.query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

        val body: RequestBody? = when {
            endpoint.rawBody != null -> BytesBody(endpoint.rawBody.data, endpoint.rawBody.contentType.toMediaType(), onBodySent)
            endpoint.body != null -> BytesBody(endpoint.body.json.encodeToByteArray(), jsonMediaType, onBodySent)
            // OkHttp wants a body, even an empty one, on these.
            endpoint.method == HTTPMethod.POST || endpoint.method == HTTPMethod.PUT || endpoint.method == HTTPMethod.PATCH ->
                BytesBody(ByteArray(0), null, onBodySent)
            else -> null
        }

        return Request.Builder()
            .url(url)
            .method(endpoint.method.name, body)
            .header("Accept", "application/json")
            .apply {
                val token = token
                if (endpoint.requiresAuth && token != null) header("Authorization", "Bearer $token")
            }
            .build()
    }

    /**
     * Pulls a human-readable message out of an error body.
     *
     * FastAPI returns `{"detail": "..."}` for simple errors and
     * `{"detail": [{"msg": "...", ...}]}` for validation errors; we also accept
     * `{"error": "..."}` / `{"message": "..."}` as a fallback.
     */
    private fun errorMessage(data: String): String? {
        val json = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        fun string(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        string("detail")?.let { return it }
        (json["detail"] as? JsonArray)?.let { items ->
            val messages = items.mapNotNull { item ->
                ((item as? JsonObject)?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
            if (messages.isNotEmpty()) return messages.joinToString("\n")
        }
        return string("message") ?: string("error")
    }

    companion object {
        val shared: APIClient by lazy { APIClient(connectivity = Connectivity.shared) }

        /** The app's one HTTP session, shared so its connections are reused. */
        val sharedSession: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /** How requests are written and responses read: snake_case keys, nulls left out. */
        val json = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            isLenient = true
        }

        /**
         * How a value changed locally is written back to the cache: like `json`, with
         * defaults kept, so it reads back the same.
         */
        val cacheJson = Json(json) { encodeDefaults = true }

        private val jsonMediaType = "application/json".toMediaType()
    }
}

/** A request body from memory that reports once it has all been sent. */
private class BytesBody(
    private val data: ByteArray,
    private val mediaType: MediaType?,
    private val onBodySent: (() -> Unit)?,
) : RequestBody() {
    /** OkHttp writes the body again when it retries on a fresh connection. */
    private val reported = AtomicBoolean(false)

    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(data)
        if (onBodySent == null) return
        // Out to the network, rather than waiting in the sink's buffer, before saying so.
        sink.flush()
        if (reported.compareAndSet(false, true)) onBodySent.invoke()
    }
}

/** Runs the call, cancelling it if the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/**
 * Runs the call and reads the whole response, on OkHttp's own threads, returning its
 * status and body. Cancelling the coroutine cancels the call, while it's still
 * downloading too.
 */
private suspend fun Call.awaitBody(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { Result.success(it.code to (it.body?.string() ?: "")) }
            } catch (error: IOException) {
                Result.failure(error)
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
