package be.discrip.core.disc

import be.discrip.core.scsi.DiscProfile
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.scsi.SenseData
import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.testing.SimulatedOpticalDrive.ReadFault
import be.discrip.core.testing.SimulatedOpticalDrive.Sense
import be.discrip.core.testing.TestImages
import be.discrip.core.usb.BulkOnlyTransport
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DriveMonitorTest {

    private val simulator = SimulatedOpticalDrive()
    private val monitor = DriveMonitor(OpticalDrive(BulkOnlyTransport(simulator)))

    private val movie = MemoryBlockDevice(TestImages.synthetic(udfLabel = "Movie"))
    private val other = MemoryBlockDevice(TestImages.synthetic(udfLabel = "Other Movie"))

    private fun pollUntilSettled(): DiscState {
        repeat(10) {
            val state = monitor.poll()
            if (state != DiscState.Loading) return state
        }
        error("Still loading")
    }

    private fun label(state: DiscState) = assertIs<DiscState.Ready>(state).disc.label

    private fun reads() = simulator.count(ScsiCommands.READ_10)

    @Test
    fun `an empty drive`() {
        assertEquals(DiscState.Unknown, monitor.state)

        assertEquals(DiscState.NoDisc(trayOpen = false), monitor.poll())

        simulator.openTray()
        assertEquals(DiscState.NoDisc(trayOpen = true), monitor.poll())
    }

    @Test
    fun `a disc spins up and is probed once`() {
        monitor.poll()
        simulator.insert(movie)

        assertEquals(DiscState.Loading, monitor.poll())
        assertEquals(DiscState.Loading, monitor.poll())
        assertEquals("Movie", label(monitor.poll()))

        val readsAfterProbe = reads()
        repeat(5) { assertEquals("Movie", label(monitor.poll())) }
        assertEquals(readsAfterProbe, reads())
        assertEquals(monitor.state, monitor.poll())
    }

    @Test
    fun `swapping discs`() {
        simulator.insert(movie)
        assertEquals("Movie", label(pollUntilSettled()))

        simulator.removeDisc()
        assertEquals(DiscState.NoDisc(trayOpen = true), monitor.poll())

        simulator.insert(other)
        assertEquals("Other Movie", label(pollUntilSettled()))
    }

    @Test
    fun `a disc changed between two polls is probed again`() {
        simulator.insert(movie)
        assertEquals("Movie", label(pollUntilSettled()))

        simulator.removeDisc()
        simulator.insert(other)

        assertEquals("Other Movie", label(pollUntilSettled()))
    }

    @Test
    fun `eject opens the tray`() {
        simulator.insert(movie)
        pollUntilSettled()

        monitor.eject()

        assertEquals(DiscState.NoDisc(trayOpen = true), monitor.state)
        assertTrue(simulator.trayOpen)
        assertEquals(DiscState.NoDisc(trayOpen = true), monitor.poll())
    }

    @Test
    fun `drives without media events are followed with test unit ready`() {
        simulator.supportsMediaEvents = false

        assertEquals(DiscState.NoDisc(trayOpen = false), monitor.poll())
        simulator.openTray()
        assertEquals(DiscState.NoDisc(trayOpen = true), monitor.poll())

        simulator.insert(movie)
        assertEquals("Movie", label(pollUntilSettled()))

        simulator.removeDisc()
        simulator.insert(other)
        assertEquals("Other Movie", label(pollUntilSettled()))
    }

    @Test
    fun `an unreadable disc is not probed over and over`() {
        simulator.readFaults[16] = ReadFault(Sense(0x03, 0x11, 0x00), times = 4)
        simulator.insert(movie)

        val state = assertIs<DiscState.Unusable>(pollUntilSettled())
        assertEquals(SenseData(3, 0x11, 0), assertIs<DiscProblem.ReadError>(state.problem).sense)

        val readsAfterProbe = reads()
        repeat(3) { assertEquals(state, monitor.poll()) }
        assertEquals(readsAfterProbe, reads())

        monitor.invalidate()
        assertEquals("Movie", label(monitor.poll()))
    }

    @Test
    fun `a cd is unusable`() {
        simulator.profile = 0x0009
        simulator.insert(movie)

        assertEquals(DiscState.Unusable(DiscProblem.WrongMedium(DiscProfile(0x0009))), pollUntilSettled())
    }

    @Test
    fun `a pulled cable ends the monitoring`() {
        simulator.insert(movie)
        pollUntilSettled()
        simulator.disconnected = true

        assertEquals(TransferFailure.DISCONNECTED, assertThrows<UsbTransferException> { monitor.poll() }.failure)
    }

    @Test
    fun `a single failed poll keeps the last state`() {
        simulator.insert(movie)
        val ready = pollUntilSettled()
        simulator.timeouts = 1

        assertEquals(ready, monitor.poll())
        assertEquals(ready, monitor.poll())
    }

    @Test
    fun `a drive that stops answering is given up`() {
        simulator.insert(movie)
        val ready = pollUntilSettled()
        simulator.timeouts = Int.MAX_VALUE

        assertEquals(ready, monitor.poll())
        assertEquals(ready, monitor.poll())
        assertThrows<DriveUnresponsiveException> { monitor.poll() }
    }
}
