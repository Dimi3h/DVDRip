package be.discrip

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import be.discrip.core.model.MovieMetadata
import be.discrip.core.plex.PlexClient
import be.discrip.core.tmdb.TmdbClient
import be.discrip.data.LocalFolderAccess
import be.discrip.data.SecretStore
import be.discrip.data.SettingsRepository
import be.discrip.drive.DriveManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Manual dependency wiring; one instance lives in the Application. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Work that outlives screens, such as the USB drive. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    val settingsRepository = SettingsRepository(appContext.settingsDataStore)
    val secretStore = SecretStore(appContext)
    val localFolders = LocalFolderAccess(appContext)
    val tmdb = TmdbClient(httpClient, credential = { secretStore.load().tmdbApiKey })
    val plex = PlexClient(httpClient)
    val ripDraft = RipDraft()
    val drives = DriveManager(appContext, applicationScope)
}

/** The rip being prepared. Filled step by step by the Disc and Metadata screens. */
class RipDraft {
    private val metadataState = MutableStateFlow<MovieMetadata?>(null)
    val metadata: StateFlow<MovieMetadata?> = metadataState.asStateFlow()

    fun setMetadata(metadata: MovieMetadata?) {
        metadataState.value = metadata
    }
}
