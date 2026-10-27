package be.discrip.rip

import be.discrip.core.disc.DiscInfo
import be.discrip.core.disc.DriveProblem
import java.io.Closeable

/**
 * A disc in a USB drive, lent to the rip engine ([be.discrip.drive.DriveManager.lease]). While it
 * is lent the drive is not polled, so its reads see every medium change themselves; closing it
 * gives the drive back.
 */
interface DriveDisc : NativeBlocks, Closeable {
    val info: DiscInfo

    /** What ended reading for good, once something did. */
    val problem: DriveProblem?

    /**
     * CSS authentication, after which the drive hands out the scrambled sectors. Throws
     * [be.discrip.core.css.CssAuthenticationException], or an IOException when the drive is gone.
     */
    fun unlock()

    /** Locks the tray while a rip runs, or unlocks it; failures are ignored (not every drive can). */
    fun preventRemoval(prevent: Boolean)

    /** Unlocks the tray and ejects the disc; false when the drive would not. */
    fun eject(): Boolean
}
