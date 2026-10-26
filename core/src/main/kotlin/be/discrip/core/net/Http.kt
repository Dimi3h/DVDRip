package be.discrip.core.net

import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Response
import okhttp3.coroutines.executeAsync

/** Lenient JSON for third-party APIs: unknown fields are ignored, nulls fall back to defaults. */
internal val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Executes the call on OkHttp's dispatcher; cancelling the coroutine cancels the request. */
internal suspend fun Call.await(): Response = executeAsync()
