package be.discrip

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import be.discrip.core.model.MovieMetadata
import be.discrip.core.plex.PlexClient
import be.discrip.core.plex.PlexPublisher
import be.discrip.core.tmdb.TmdbClient
import be.discrip.data.HistoryStore
import be.discrip.data.LocalFolderAccess
import be.discrip.data.SecretStore
import be.discrip.data.SettingsRepository
import be.discrip.drive.DriveManager
import be.discrip.rip.RipLabels
import be.discrip.rip.RipManager
import be.discrip.rip.cleanStaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Manual dependency wiring; one instance lives in the Application. */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val appContentResolver get() = appContext.contentResolver

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
    val history = HistoryStore(File(appContext.filesDir, "history.json"))

    /** NAS rips wait here, in the app's own storage, until they are uploaded. */
    private val stagingDir = File(appContext.filesDir, "staging")
    private val startedAt = System.currentTimeMillis()

    /** Created on first use; the native engine loads with the first DVD. */
    val rips: RipManager by lazy {
        RipManager(
            resolver = appContext.contentResolver,
            labels = RipLabels(appContext),
            scope = applicationScope,
            stagingDir = stagingDir,
            credentials = secretStore::load,
            settings = settingsRepository::current,
            history = history,
            plex = PlexPublisher(plex),
            drives = drives::lease,
            // The film's metadata went into its file; the next disc is another film.
            onSucceeded = { job -> if (ripDraft.metadata.value == job.metadata) ripDraft.setMetadata(null) },
        )
    }

    init {
        applicationScope.launch(Dispatchers.IO) { cleanStaging(stagingDir, history.load(), startedAt) }
    }
}

/** The rip being prepared. Filled step by step by the Disc and Metadata screens. */
class RipDraft {
    private val metadataState = MutableStateFlow<MovieMetadata?>(null)
    val metadata: StateFlow<MovieMetadata?> = metadataState.asStateFlow()

    fun setMetadata(metadata: MovieMetadata?) {
        metadataState.value = metadata
    }
}
