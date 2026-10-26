package be.discrip.ui.metadata

import be.discrip.core.model.MediaKind
import be.discrip.core.model.MetadataMode
import be.discrip.core.model.MetadataProblem
import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.StorageTarget
import be.discrip.core.settings.AppSettings
import be.discrip.core.settings.LocalFolderSettings
import be.discrip.core.settings.NasSettings
import be.discrip.core.tmdb.TmdbMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataUiStateTest {

    private val settings = AppSettings(
        local = LocalFolderSettings(filmsFolder = "Films", homeVideosFolder = "Thuis"),
        nas = NasSettings(filmsFolder = "video/Films", homeVideosFolder = "video/Home Videos"),
    )

    private fun state(form: MetadataForm, mode: MetadataMode = MetadataMode.MANUAL, storage: StorageTarget = StorageTarget.LOCAL) =
        MetadataUiState(
            loading = false,
            mode = mode,
            form = form,
            settings = settings.copy(defaultStorage = storage),
            currentYear = 2026,
        )

    @Test
    fun `preview follows the local folder when the default storage uses it`() {
        val preview = state(MetadataForm(title = "The Matrix", year = "1999", tmdbId = "603")).preview

        assertEquals("Films/The Matrix (1999) {tmdb-603}", preview?.relativeFolder)
        assertEquals("The Matrix (1999) {tmdb-603}.mkv", preview?.fileName)
    }

    @Test
    fun `preview follows the NAS folder when only the NAS is used`() {
        val form = MetadataForm(title = "Vakantie", year = "", kind = MediaKind.HOME_VIDEO)

        val preview = state(form, storage = StorageTarget.NAS).preview

        assertEquals("video/Home Videos/Vakantie/Vakantie.mkv", preview?.relativeFile)
    }

    @Test
    fun `invalid form has no preview`() {
        val current = state(MetadataForm(title = "", year = "1999"))

        assertNull(current.preview)
        assertEquals(setOf(MetadataProblem.TITLE_MISSING), current.validation.problems)
    }

    @Test
    fun `automatic mode needs a chosen match before it can confirm`() {
        val empty = state(MetadataForm(title = "Matrix", year = "1999"), mode = MetadataMode.AUTOMATIC)
        assertFalse(empty.showForm)
        assertFalse(empty.canConfirm)

        val chosen = empty.copy(selectedTmdbId = 603)
        assertTrue(chosen.showForm)
        assertTrue(chosen.canConfirm)
    }

    @Test
    fun `form round trips metadata`() {
        val metadata = MovieMetadata("Zwartboek", 2006, MediaKind.FILM, tmdbId = 9075, imdbId = "tt0389557", originalTitle = "Zwartboek")

        val validated = state(MetadataForm.from(metadata)).validation.metadata

        assertEquals(metadata, validated)
    }

    @Test
    fun `form from a tmdb hit carries its id`() {
        val form = MetadataForm.from(TmdbMovie(603, "The Matrix", "The Matrix", 1999, "", "/m.jpg"))

        assertEquals("603", form.tmdbId)
        assertEquals("1999", form.year)
        assertEquals("/m.jpg", form.posterPath)
    }
}
