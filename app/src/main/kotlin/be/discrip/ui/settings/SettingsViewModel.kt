package be.discrip.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.discrip.AppContainer
import be.discrip.core.naming.FileNameSanitizer
import be.discrip.core.nas.Nas
import be.discrip.core.nas.NasException
import be.discrip.core.nas.NasProblem
import be.discrip.core.nas.NasTarget
import be.discrip.core.plex.PlexException
import be.discrip.core.plex.PlexSection
import be.discrip.core.settings.AppSettings
import be.discrip.core.tmdb.TmdbException
import be.discrip.data.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of a "test" button. */
sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data object Passed : CheckState
    data class Failed(val reason: CheckFailure) : CheckState
}

enum class CheckFailure { UNREACHABLE, REJECTED, MISSING_CREDENTIAL, INVALID_URL, OTHER }

sealed interface SectionsState {
    data object Idle : SectionsState
    data object Loading : SectionsState
    data class Loaded(val sections: List<PlexSection>) : SectionsState
    data class Failed(val reason: CheckFailure) : SectionsState
}

/** Outcome of the NAS test: connected and allowed to write, or why not. */
sealed interface NasCheck {
    data object Idle : NasCheck
    data object Running : NasCheck
    data class Connected(val freeBytes: Long) : NasCheck
    data class Failed(val problem: NasProblem) : NasCheck
}

/** Local folder state as the Storage screen shows it. */
enum class FolderAccess { NONE, GRANTED, REVOKED }

/**
 * Shared by all settings screens. Text fields keep their own state while typing and push every
 * change here, so DataStore round trips never move the cursor.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        container.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val secrets: StateFlow<Secrets?> = container.secretStore.secrets

    private val tmdbCheckState = MutableStateFlow<CheckState>(CheckState.Idle)
    val tmdbCheck: StateFlow<CheckState> = tmdbCheckState.asStateFlow()

    private val sectionsState = MutableStateFlow<SectionsState>(SectionsState.Idle)
    val plexSections: StateFlow<SectionsState> = sectionsState.asStateFlow()

    private val folderAccessState = MutableStateFlow(FolderAccess.NONE)
    val folderAccess: StateFlow<FolderAccess> = folderAccessState.asStateFlow()

    private val nasCheckState = MutableStateFlow<NasCheck>(NasCheck.Idle)
    val nasCheck: StateFlow<NasCheck> = nasCheckState.asStateFlow()

    init {
        viewModelScope.launch { container.secretStore.load() }
        refreshFolderAccess()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
        if (nasCheckState.value !is NasCheck.Running) nasCheckState.value = NasCheck.Idle
    }

    fun updateSecrets(transform: (Secrets) -> Secrets) {
        viewModelScope.launch { container.secretStore.update(transform) }
        if (tmdbCheckState.value !is CheckState.Running) tmdbCheckState.value = CheckState.Idle
        if (nasCheckState.value !is NasCheck.Running) nasCheckState.value = NasCheck.Idle
    }

    /** Connects to the NAS, signs in, and writes and removes a small file in the films folder. */
    fun testNas() {
        viewModelScope.launch {
            nasCheckState.value = NasCheck.Running
            val nas = container.settingsRepository.current().nas
            val password = container.secretStore.load().nasPassword
            nasCheckState.value = try {
                withContext(Dispatchers.IO) {
                    Nas.connect(NasTarget.of(nas.host, nas.share, nas.username, password, nas.domain)).use { share ->
                        share.checkWritable(FileNameSanitizer.sanitizeRelativePath(nas.filmsFolder))
                        NasCheck.Connected(share.freeBytes())
                    }
                }
            } catch (e: NasException) {
                NasCheck.Failed(e.problem)
            }
        }
    }

    fun testTmdbKey() {
        viewModelScope.launch {
            tmdbCheckState.value = CheckState.Running
            tmdbCheckState.value = try {
                container.tmdb.verifyCredential()
                CheckState.Passed
            } catch (e: TmdbException) {
                CheckState.Failed(
                    when (e) {
                        is TmdbException.MissingApiKey -> CheckFailure.MISSING_CREDENTIAL
                        is TmdbException.InvalidApiKey -> CheckFailure.REJECTED
                        is TmdbException.Unreachable -> CheckFailure.UNREACHABLE
                        else -> CheckFailure.OTHER
                    },
                )
            }
        }
    }

    fun loadPlexSections() {
        viewModelScope.launch {
            sectionsState.value = SectionsState.Loading
            val url = container.settingsRepository.current().plex.serverUrl
            val token = container.secretStore.load().plexToken
            sectionsState.value = try {
                SectionsState.Loaded(container.plex.librarySections(url, token).filter { it.isMovieLibrary })
            } catch (e: PlexException) {
                SectionsState.Failed(
                    when (e) {
                        is PlexException.InvalidUrl -> CheckFailure.INVALID_URL
                        is PlexException.MissingToken -> CheckFailure.MISSING_CREDENTIAL
                        is PlexException.InvalidToken -> CheckFailure.REJECTED
                        is PlexException.Unreachable -> CheckFailure.UNREACHABLE
                        else -> CheckFailure.OTHER
                    },
                )
            }
        }
    }

    /** Stores the folder picked in the system picker and releases the previous grant. */
    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val previous = container.settingsRepository.current().local.treeUri
            val name = withContext(Dispatchers.IO) {
                val displayName = container.localFolders.takePermission(uri)
                if (previous != null && previous != uri.toString()) container.localFolders.releasePermission(previous)
                displayName
            }
            container.settingsRepository.update {
                it.copy(local = it.local.copy(treeUri = uri.toString(), displayName = name))
            }
            refreshFolderAccess()
        }
    }

    fun refreshFolderAccess() {
        viewModelScope.launch {
            val uri = container.settingsRepository.current().local.treeUri
            folderAccessState.value = when {
                uri == null -> FolderAccess.NONE
                withContext(Dispatchers.IO) { container.localFolders.hasAccess(uri) } -> FolderAccess.GRANTED
                else -> FolderAccess.REVOKED
            }
        }
    }
}
