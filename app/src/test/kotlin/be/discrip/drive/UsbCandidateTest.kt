package be.discrip.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbCandidateTest {

    private fun candidate(manufacturer: String?, product: String?) =
        UsbCandidate("/dev/bus/usb/001/002", 0x0E8D, 0x1887, product, manufacturer)

    @Test
    fun `manufacturer and product make the name`() {
        assertEquals("ASUSTeK SDRW-08U9M-U", candidate("ASUSTeK", "SDRW-08U9M-U").displayName)
    }

    @Test
    fun `a repeated or blank part is left out`() {
        assertEquals("Hitachi-LG", candidate("Hitachi-LG", "Hitachi-LG").displayName)
        assertEquals("GP65NB60", candidate(" ", "GP65NB60 ").displayName)
    }

    @Test
    fun `usb ids when the device has no strings`() {
        assertEquals("USB 0e8d:1887", candidate(null, null).displayName)
    }
}
