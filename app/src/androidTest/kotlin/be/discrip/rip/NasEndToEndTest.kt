package be.discrip.rip

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.discrip.core.dvd.RipFailure
import be.discrip.core.model.MovieMetadata
import be.discrip.core.model.StorageTarget
import be.discrip.core.nas.Nas
import be.discrip.core.nas.NasProblem
import be.discrip.core.nas.NasShare
import be.discrip.core.nas.NasTarget
import be.discrip.core.naming.PlexNaming
import be.discrip.core.naming.RipPath
import be.discrip.core.plex.PlexPublication
import be.discrip.core.plex.PlexResult
import be.discrip.core.settings.NasSettings
import be.discrip.core.settings.PlexSectionRef
import be.discrip.core.settings.PlexSettings
import be.discrip.data.Secrets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rips the test DVD to a real SMB server: the Samba server of scripts/samba-test-server.sh on the
 * machine running the emulator, which the emulator reaches as 10.0.2.2. Its address comes in as
 * instrumentation arguments (smbHost, smbPort, smbShare, smbUser, smbPassword); CI passes them,
 * and without them these tests are skipped. A MockWebServer on the device plays the Plex server.
 */
@RunWith(AndroidJUnit4::class)
class NasEndToEndTest {

    private val arguments = InstrumentationRegistry.getArguments()
    private fun argument(name: String): String = arguments.getString(name).orEmpty()

    private lateinit var rig: RipTestRig
    private lateinit var plex: MockWebServer
    private lateinit var share: NasShare
    private val refreshed = CopyOnWriteArrayList<String?>()

    @Before
    fun setUp() {
        assumeTrue("No Samba server given (instrumentation argument smbHost); see the README", argument("smbHost").isNotEmpty())
        plex = MockWebServer()
        plex.dispatcher = PlexServer()
        plex.start()
        rig = RipTestRig("nas-test")
        rig.secrets = Secrets(nasPassword = argument("smbPassword"), plexToken = "plex-token")
        rig.settings = rig.settings.copy(
            nas = NasSettings(
                host = "${argument("smbHost")}:${argument("smbPort")}",
                share = argument("smbShare"),
                username = argument("smbUser"),
                filmsFolder = "Films",
            ),
            plex = PlexSettings(serverUrl = plex.url("/").toString(), filmsSection = PlexSectionRef("1", "Films")),
        )
        share = Nas.connect(NasTarget.of(rig.settings.nas.host, argument("smbShare"), argument("smbUser"), argument("smbPassword")))
    }

    @After
    fun tearDown() {
        if (!::rig.isInitialized) return
        share.close()
        rig.close()
        plex.close()
    }

    @Test
    fun ripsThroughTheAppStorageToTheNasAndTellsPlex() = runBlocking {
        val film = MovieMetadata(title = "Via de app", year = 2024, tmdbId = 1)
        val path = PlexNaming.plan(film, "Films")

        val done = rig.ripAndWait(rig.job(rig.openImage(), StorageTarget.NAS, film))

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        assertNull(done.localFile)
        assertEquals(NasOutcome.Stored("smb://${rig.settings.nas.host}/${argument("smbShare")}/${path.relativeFile}"), done.nas)
        assertEquals(done.result.outputBytes, share.size(path.relativeFile))
        assertFalse(share.exists(path.relativeFile + NasShare.PART_SUFFIX))
        // The copy in the app's storage goes once the NAS has the whole file.
        assertEquals(emptyList<Any>(), rig.filesIn(rig.staging))

        val folder = "/volume1/${argument("smbShare")}/${path.relativeFolder}"
        assertEquals(listOf<String?>(folder), refreshed)
        assertEquals(PlexOutcome.Published(PlexPublication.Added(path.folderName, folder)), done.plex)

        val record = rig.history.load().single()
        assertEquals((done.nas as NasOutcome.Stored).location, record.nasFile)
        assertEquals(PlexResult.ADDED, record.plex)
        assertNull(record.pendingUpload)

        // The engine reads the file back over SMB as well.
        share.openForReading(path.relativeFile).use { file ->
            val summary = DvdSession.probe(EngineFile.Callbacks(NasNativeFile(file)) {}).getOrThrow()
            assertEquals(listOf("dut", "eng"), summary.audio.map { it.language })
            assertEquals("Via de app", summary.tags["title"])
        }
    }

    @Test
    fun writesStraightToTheNasWhenAsked() = runBlocking {
        rig.settings = rig.settings.copy(nas = rig.settings.nas.copy(writeDirectly = true))
        val film = MovieMetadata(title = "Rechtstreeks", year = 2024, tmdbId = 2)
        val path = PlexNaming.plan(film, "Films")

        val done = rig.ripAndWait(rig.job(rig.openImage(), StorageTarget.NAS, film))

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        assertTrue(done.nas is NasOutcome.Stored)
        assertEquals(done.result.outputBytes, share.size(path.relativeFile))
        assertFalse(share.exists(path.relativeFile + NasShare.PART_SUFFIX))
        assertFalse(rig.staging.exists())
        // Read back over SMB by the engine before the file got its final name.
        assertEquals(listOf("dvd_subtitle", "dvd_subtitle"), done.file.subtitles.map { it.codec })
        assertEquals(12_000.0, done.file.durationMs.toDouble(), 100.0)
    }

    @Test
    fun keepsALocalCopyAndUploadsAnotherForBoth() = runBlocking {
        val film = MovieMetadata(title = "Beide", year = 2024, tmdbId = 3)
        val path = PlexNaming.plan(film, "Films")

        val done = rig.ripAndWait(rig.job(rig.openImage(), StorageTarget.BOTH, film))

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        val local = java.io.File(rig.out, path.relativeFile)
        assertTrue(local.isFile)
        assertEquals(local.length(), share.size(path.relativeFile))
        assertTrue(done.nas is NasOutcome.Stored)
        assertEquals("Movies/${path.relativeFile}", done.localFile)
    }

    @Test
    fun aNasThatDoesNotAnswerStopsTheRipBeforeItStarts() = runBlocking {
        rig.settings = rig.settings.copy(nas = rig.settings.nas.copy(host = "${argument("smbHost")}:1"))

        val done = rig.ripAndWait(rig.job(rig.openImage(), StorageTarget.NAS))

        done as RipState.Failed
        assertEquals(RipFailure.OUTPUT_UNAVAILABLE, done.reason)
        assertEquals(NasProblem.UNREACHABLE, done.nasProblem)
        assertFalse(rig.staging.exists())
    }

    @Test
    fun aRefusedLoginAndAMissingWritePermissionAreNamed() = runBlocking {
        val ready = rig.openImage()
        rig.secrets = rig.secrets.copy(nasPassword = "wrong")
        assertEquals(NasProblem.LOGIN_REFUSED, (rig.ripAndWait(rig.job(ready, StorageTarget.NAS)) as RipState.Failed).nasProblem)

        rig.secrets = rig.secrets.copy(nasPassword = argument("smbPassword"))
        rig.settings = rig.settings.copy(nas = rig.settings.nas.copy(share = argument("smbReadOnlyShare")))
        assertEquals(NasProblem.ACCESS_DENIED, (rig.ripAndWait(rig.job(ready, StorageTarget.NAS)) as RipState.Failed).nasProblem)

        rig.settings = rig.settings.copy(nas = rig.settings.nas.copy(share = "nothere"))
        assertEquals(NasProblem.SHARE_NOT_FOUND, (rig.ripAndWait(rig.job(ready, StorageTarget.NAS)) as RipState.Failed).nasProblem)
        assertFalse(rig.staging.exists())
    }

    @Test
    fun aFailedUploadWaitsOnThePhoneAndGoesUpFromTheHistory() = runBlocking {
        val film = MovieMetadata(title = "Later", year = 2024, tmdbId = 4)
        val path = PlexNaming.plan(film, "Films")
        // The NAS answers the check before the rip, then is gone when the upload starts.
        rig.nasGoneFromConnection = 2

        val done = rig.ripAndWait(rig.job(rig.openImage(), StorageTarget.NAS, film))

        assertTrue(done.toString(), done is RipState.Succeeded)
        done as RipState.Succeeded
        val failed = done.nas as NasOutcome.Failed
        assertEquals(NasProblem.UNREACHABLE, failed.problem)
        assertTrue(failed.pending)
        assertNull(done.plex)
        val waiting = rig.filesIn(rig.staging).single()
        assertEquals(done.result.outputBytes, waiting.length())
        val record = rig.history.load().single()
        assertEquals(waiting.path, record.pendingUpload!!.stagedFile)
        assertEquals(path.relativeFile, record.pendingUpload!!.nasPath)

        rig.nasGoneFromConnection = null
        assertTrue(rig.manager.retryUpload(record))
        withTimeout(RipTestRig.RIP_TIMEOUT) { rig.manager.retry.first { it == null } }

        val updated = rig.history.load().single()
        assertNull(updated.pendingUpload)
        assertNull(updated.nasProblem)
        assertEquals(PlexResult.ADDED, updated.plex)
        assertTrue(updated.nasFile!!.endsWith(path.relativeFile))
        assertEquals(done.result.outputBytes, share.size(path.relativeFile))
        assertEquals(emptyList<Any>(), rig.filesIn(rig.staging))
    }

    /** Plex as far as the app uses it: one film library on the share, which lists what it was asked to scan. */
    private inner class PlexServer : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.headers["X-Plex-Token"] != "plex-token") return MockResponse(code = 401)
            val path = request.url.encodedPath
            return when {
                path == "/library/sections" -> MockResponse(
                    body = """{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Films",
                        "Location":[{"path":"/volume1/${argument("smbShare")}/Films"}]}]}}""",
                )
                path == "/library/sections/1/refresh" -> {
                    refreshed += request.url.queryParameter("path")
                    MockResponse()
                }
                path == "/library/sections/1/all" -> {
                    val items = refreshed.filterNotNull().joinToString(",") { folder ->
                        val name = RipPath.of("$folder/x").folderName
                        """{"ratingKey":"${name.hashCode()}","title":"$name","Media":[{"Part":[{"file":"$folder/$name.mkv"}]}]}"""
                    }
                    MockResponse(body = """{"MediaContainer":{"Metadata":[$items]}}""")
                }
                else -> MockResponse(code = 404)
            }
        }
    }
}
