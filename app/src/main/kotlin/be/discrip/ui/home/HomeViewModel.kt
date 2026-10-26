package be.discrip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.discrip.AppContainer
import be.discrip.core.model.MovieMetadata
import be.discrip.core.naming.PlexNaming
import be.discrip.core.settings.AppSettings
import be.discrip.data.Secrets
import be.discrip.ui.settings.FolderAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val settings: AppSettings,
    val secrets: Secrets,
    val folderAccess: FolderAccess,
    val draft: MovieMetadata?,
) {
    val draftPath: String? get() = draft?.let { PlexNaming.plan(it, settings.baseFolderFor(it)).relativeFile }
    val tmdbReady: Boolean get() = secrets.tmdbApiKey.isNotBlank()
    val nasReady: Boolean get() = settings.nas.isConfigured && settings.nas.username.isNotBlank()
    val plexReady: Boolean get() = settings.plex.isConfigured && secrets.plexToken.isNotBlank()
    val plexSectionsChosen: Boolean get() = settings.plex.filmsSection != null && settings.plex.homeVideosSection != null
}

/** The base folder a draft would land in, preferring the local folder when the default uses it. */
fun AppSettings.baseFolderFor(metadata: MovieMetadata): String =
    if (defaultStorage.usesLocalFolder) local.baseFolder(metadata.kind) else nas.baseFolder(metadata.kind)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val folderAccess = MutableStateFlow(FolderAccess.NONE)

    val state: StateFlow<HomeState?> = combine(
        container.settingsRepository.settings,
        container.secretStore.secrets.filterNotNull(),
        folderAccess,
        container.ripDraft.metadata,
    ) { settings, secrets, access, draft -> HomeState(settings, secrets, access, draft) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { container.secretStore.load() }
    }

    /** Called on every resume: folder grants can be revoked while the app is away. */
    fun refresh() {
        viewModelScope.launch {
            val uri = container.settingsRepository.current().local.treeUri
            folderAccess.value = when {
                uri == null -> FolderAccess.NONE
                withContext(Dispatchers.IO) { container.localFolders.hasAccess(uri) } -> FolderAccess.GRANTED
                else -> FolderAccess.REVOKED
            }
        }
    }

    fun clearDraft() = container.ripDraft.setMetadata(null)
}
