package be.discrip.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

data class Secrets(
    val tmdbApiKey: String = "",
    val nasPassword: String = "",
    val plexToken: String = "",
)

/**
 * TMDB key, NAS password and Plex token, kept in EncryptedSharedPreferences with a master key in
 * the Android Keystore. Backups are disabled for the app, so the file never travels to a device
 * that lacks the key.
 */
class SecretStore(private val context: Context) {

    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow<Secrets?>(null)

    /** Null until the first [load]. */
    val secrets: StateFlow<Secrets?> = state.asStateFlow()

    suspend fun load(): Secrets = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.value ?: read(openPrefs()).also { state.value = it }
        }
    }

    suspend fun update(transform: (Secrets) -> Secrets) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = openPrefs()
            val updated = transform(state.value ?: read(store))
            store.edit {
                putString(KEY_TMDB, updated.tmdbApiKey)
                putString(KEY_NAS_PASSWORD, updated.nasPassword)
                putString(KEY_PLEX_TOKEN, updated.plexToken)
            }
            state.value = updated
        }
    }

    private fun read(store: SharedPreferences) = Secrets(
        tmdbApiKey = store.getString(KEY_TMDB, null).orEmpty(),
        nasPassword = store.getString(KEY_NAS_PASSWORD, null).orEmpty(),
        plexToken = store.getString(KEY_PLEX_TOKEN, null).orEmpty(),
    )

    private fun openPrefs(): SharedPreferences {
        prefs?.let { return it }
        val created = try {
            create()
        } catch (e: GeneralSecurityException) {
            recreate(e)
        } catch (e: IOException) {
            recreate(e)
        }
        prefs = created
        return created
    }

    /**
     * The keyset can become unreadable when the Keystore entry is lost (for example after the user
     * clears credentials). The secrets are unrecoverable then; start over so the app keeps working
     * and the user can re-enter them.
     */
    private fun recreate(cause: Exception): SharedPreferences {
        Log.w(TAG, "Encrypted settings unreadable, starting with empty secrets", cause)
        context.deleteSharedPreferences(FILE_NAME)
        return create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val TAG = "SecretStore"
        const val FILE_NAME = "secrets"
        const val KEY_TMDB = "tmdb_api_key"
        const val KEY_NAS_PASSWORD = "nas_password"
        const val KEY_PLEX_TOKEN = "plex_token"
    }
}
