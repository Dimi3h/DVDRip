package be.discrip.core.css

import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ScsiCommandException
import be.discrip.core.scsi.SenseCategory
import java.io.IOException

/**
 * The drive will not hand out the disc's scrambled sectors. With [regionMismatch], most likely
 * because its region is not one the disc allows (or was never set).
 */
class CssAuthenticationException(val regionMismatch: Boolean, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * CSS authentication with the drive, after which it hands out the scrambled sectors of a
 * protected disc. Drives refuse those (sense 05/6F/03) until the host has gone through this
 * handshake; libdvdcss runs it over the operating system's DVD ioctls, which an app reaching the
 * drive over USB does not have, so it runs here over the app's own SCSI layer, as libdvdcss does
 * it (GetBusKey, then reading the disc key, which sets the authentication success flag). The
 * title keys still come from libdvdcss, which cracks them out of the sectors
 * (DVDCSS_METHOD=title).
 */
object CssAuthentication {

    /** Authenticates with [drive]; throws [CssAuthenticationException], or IOException when the drive is gone. */
    fun unlock(drive: OpticalDrive) {
        val agid = grantId(drive)
        try {
            exchangeKeys(drive, agid)
            drive.readDiscKey(agid)
        } catch (e: ScsiCommandException) {
            invalidate(drive, agid)
            throw failure(e)
        } catch (e: CssAuthenticationException) {
            invalidate(drive, agid)
            throw e
        }
        // Reading the disc key ends the session and should leave the flag set; a drive whose
        // region the disc does not allow (or whose region was never set) keeps it clear.
        val unlocked = try {
            drive.authenticationSucceeded()
        } catch (e: ScsiCommandException) {
            throw failure(e)
        }
        if (!unlocked) {
            invalidate(drive, agid)
            throw CssAuthenticationException(regionMismatch = true, "The drive did not grant access to the scrambled sectors")
        }
    }

    /** An authentication grant ID, freeing ones that hung sessions left behind when none is free. */
    private fun grantId(drive: OpticalDrive): Int {
        var last: ScsiCommandException
        try {
            return drive.reportAgid()
        } catch (e: ScsiCommandException) {
            if (e.sense.category == SenseCategory.INVALID_COMMAND) throw unsupported(e)
            last = e
        }
        for (stale in 0 until AGID_COUNT) {
            invalidate(drive, stale)
            try {
                return drive.reportAgid()
            } catch (e: ScsiCommandException) {
                last = e
            }
        }
        throw CssAuthenticationException(false, "The drive gives no authentication grant ID", last)
    }

    /**
     * Challenge and answer both ways. Challenges and keys travel in reverse byte order, as
     * libdvdcss sends them; any host challenge will do.
     */
    private fun exchangeKeys(drive: OpticalDrive, agid: Int) {
        val challenge = ByteArray(10) { it.toByte() }
        drive.sendChallenge(agid, challenge.reversedArray())
        val key1 = drive.reportKey1(agid).reversedArray()
        val variant = (0 until VARIANT_COUNT).firstOrNull { CssCipher.cryptKey(CssCipher.KEY1, it, challenge).contentEquals(key1) }
            ?: throw CssAuthenticationException(false, "The drive's answer fits none of the CSS variants")
        val driveChallenge = drive.reportChallenge(agid).reversedArray()
        drive.sendKey2(agid, CssCipher.cryptKey(CssCipher.KEY2, variant, driveChallenge).reversedArray())
    }

    private fun invalidate(drive: OpticalDrive, agid: Int) {
        try {
            drive.invalidateAgid(agid)
        } catch (e: ScsiCommandException) {
            // Nothing to free.
        }
    }

    private fun failure(e: ScsiCommandException): CssAuthenticationException = when (e.sense.category) {
        SenseCategory.REGION_MISMATCH -> CssAuthenticationException(true, "The drive's region does not match the disc", e)
        SenseCategory.INVALID_COMMAND -> unsupported(e)
        else -> CssAuthenticationException(false, "CSS authentication failed: ${e.message}", e)
    }

    private fun unsupported(e: ScsiCommandException) =
        CssAuthenticationException(false, "The drive does not support CSS authentication", e)

    private const val AGID_COUNT = 4
    private const val VARIANT_COUNT = 32
}
