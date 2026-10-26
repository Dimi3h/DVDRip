package be.discrip.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small icon set drawn in the spirit of SF Symbols (round caps and joins, regular weight). SF
 * Symbols themselves may not be used outside Apple platforms, so these are original drawings.
 */
object HigIcons {

    private val ink = SolidColor(Color.Black)

    private fun icon(name: String, width: Float = 24f, height: Float = 24f, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(
            name = name,
            defaultWidth = width.dp,
            defaultHeight = height.dp,
            viewportWidth = width,
            viewportHeight = height,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float, block: PathBuilder.() -> Unit) = path(
        stroke = ink,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    private fun ImageVector.Builder.fill(evenOdd: Boolean = false, block: PathBuilder.() -> Unit) = path(
        fill = ink,
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        pathBuilder = block,
    )

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcToRelative(r, r, 0f, false, true, r, r)
        lineTo(x + w, y + h - r)
        arcToRelative(r, r, 0f, false, true, -r, r)
        lineTo(x + r, y + h)
        arcToRelative(r, r, 0f, false, true, -r, -r)
        lineTo(x, y + r)
        arcToRelative(r, r, 0f, false, true, r, -r)
        close()
    }

    /** Table-row disclosure indicator. */
    val Chevron: ImageVector by lazy {
        icon("Chevron", width = 8f, height = 14f) {
            stroke(2f) {
                moveTo(1.5f, 1.5f)
                lineTo(6.5f, 7f)
                lineTo(1.5f, 12.5f)
            }
        }
    }

    /** Navigation bar back chevron. */
    val BackChevron: ImageVector by lazy {
        icon("BackChevron", width = 12f, height = 21f) {
            stroke(2.6f) {
                moveTo(10f, 1.8f)
                lineTo(1.8f, 10.5f)
                lineTo(10f, 19.2f)
            }
        }
    }

    val Checkmark: ImageVector by lazy {
        icon("Checkmark") {
            stroke(2.4f) {
                moveTo(4.5f, 12.8f)
                lineTo(9.6f, 17.8f)
                lineTo(19.5f, 6.5f)
            }
        }
    }

    val MagnifyingGlass: ImageVector by lazy {
        icon("MagnifyingGlass") {
            stroke(2.2f) { circle(10.5f, 10.5f, 6.5f) }
            stroke(2.4f) {
                moveTo(15.4f, 15.4f)
                lineTo(20.5f, 20.5f)
            }
        }
    }

    /** opticaldisc: a filled disc with its centre hole and a lighter ring. */
    val Disc: ImageVector by lazy {
        icon("Disc") {
            fill(evenOdd = true) {
                circle(12f, 12f, 10f)
                circle(12f, 12f, 2.6f)
            }
        }
    }

    val Gear: ImageVector by lazy {
        icon("Gear") {
            fill(evenOdd = true) {
                val teeth = 8
                val outer = 10.6f
                val inner = 8.1f
                val step = 2 * PI / teeth
                for (i in 0 until teeth) {
                    val a0 = i * step - step * 0.22
                    val a1 = i * step - step * 0.14
                    val a2 = i * step + step * 0.14
                    val a3 = i * step + step * 0.22
                    val points = listOf(inner to a0, outer to a1, outer to a2, inner to a3)
                    points.forEachIndexed { index, (r, a) ->
                        val x = 12f + (r * cos(a)).toFloat()
                        val y = 12f + (r * sin(a)).toFloat()
                        if (i == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    val next = (i + 1) * step - step * 0.22
                    val nx = 12f + (inner * cos(next)).toFloat()
                    val ny = 12f + (inner * sin(next)).toFloat()
                    arcTo(inner, inner, 0f, false, true, nx, ny)
                }
                close()
                circle(12f, 12f, 3.4f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            fill {
                moveTo(2.5f, 7f)
                curveTo(2.5f, 5.6f, 3.6f, 4.5f, 5f, 4.5f)
                lineTo(9.4f, 4.5f)
                curveTo(10f, 4.5f, 10.5f, 4.7f, 10.9f, 5.1f)
                lineTo(12.4f, 6.6f)
                lineTo(19f, 6.6f)
                curveTo(20.4f, 6.6f, 21.5f, 7.7f, 21.5f, 9.1f)
                lineTo(21.5f, 17f)
                curveTo(21.5f, 18.4f, 20.4f, 19.5f, 19f, 19.5f)
                lineTo(5f, 19.5f)
                curveTo(3.6f, 19.5f, 2.5f, 18.4f, 2.5f, 17f)
                close()
            }
        }
    }

    /** server.rack style: two stacked units with status lights. */
    val Server: ImageVector by lazy {
        icon("Server") {
            fill(evenOdd = true) {
                roundRect(3f, 4f, 18f, 7f, 2f)
                circle(7f, 7.5f, 1.2f)
                roundRect(3f, 13f, 18f, 7f, 2f)
                circle(7f, 16.5f, 1.2f)
            }
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            path(
                fill = ink,
                stroke = ink,
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8f, 5.5f)
                lineTo(18.5f, 12f)
                lineTo(8f, 18.5f)
                close()
            }
        }
    }

    /** slider.horizontal.3 */
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            stroke(1.9f) {
                moveTo(3.5f, 6f); lineTo(20.5f, 6f)
                moveTo(3.5f, 12f); lineTo(20.5f, 12f)
                moveTo(3.5f, 18f); lineTo(20.5f, 18f)
            }
            fill {
                circle(15.5f, 6f, 2.6f)
                circle(8.5f, 12f, 2.6f)
                circle(13f, 18f, 2.6f)
            }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            stroke(1.8f) { circle(12f, 12f, 9.2f) }
            stroke(2f) {
                moveTo(12f, 11f)
                lineTo(12f, 16.6f)
            }
            fill { circle(12f, 7.6f, 1.25f) }
        }
    }

    val ExclamationTriangle: ImageVector by lazy {
        icon("ExclamationTriangle") {
            fill(evenOdd = true) {
                moveTo(10.3f, 3.9f)
                curveTo(11.1f, 2.6f, 12.9f, 2.6f, 13.7f, 3.9f)
                lineTo(21.6f, 17.6f)
                curveTo(22.4f, 18.9f, 21.4f, 20.6f, 19.9f, 20.6f)
                lineTo(4.1f, 20.6f)
                curveTo(2.6f, 20.6f, 1.6f, 18.9f, 2.4f, 17.6f)
                close()
                roundRect(11f, 8f, 2f, 6.6f, 1f)
                circle(12f, 17.3f, 1.2f)
            }
        }
    }

    val Eye: ImageVector by lazy {
        icon("Eye") {
            stroke(1.8f) {
                moveTo(2.5f, 12f)
                curveTo(4.6f, 7.9f, 8f, 5.8f, 12f, 5.8f)
                curveTo(16f, 5.8f, 19.4f, 7.9f, 21.5f, 12f)
                curveTo(19.4f, 16.1f, 16f, 18.2f, 12f, 18.2f)
                curveTo(8f, 18.2f, 4.6f, 16.1f, 2.5f, 12f)
                close()
            }
            fill { circle(12f, 12f, 3.2f) }
        }
    }

    val EyeSlash: ImageVector by lazy {
        icon("EyeSlash") {
            stroke(1.8f) {
                moveTo(2.5f, 12f)
                curveTo(4.6f, 7.9f, 8f, 5.8f, 12f, 5.8f)
                curveTo(16f, 5.8f, 19.4f, 7.9f, 21.5f, 12f)
                curveTo(19.4f, 16.1f, 16f, 18.2f, 12f, 18.2f)
                curveTo(8f, 18.2f, 4.6f, 16.1f, 2.5f, 12f)
                close()
            }
            stroke(2f) {
                moveTo(4f, 3.5f)
                lineTo(20f, 20.5f)
            }
        }
    }

    val Film: ImageVector by lazy {
        icon("Film") {
            fill(evenOdd = true) {
                roundRect(3f, 3.5f, 18f, 17f, 2.5f)
                roundRect(7f, 5.5f, 10f, 13f, 1f)
                for (y in listOf(5.5f, 9.5f, 13.5f, 17f)) {
                    roundRect(4.4f, y, 1.6f, 1.6f, 0.4f)
                    roundRect(18f, y, 1.6f, 1.6f, 0.4f)
                }
            }
        }
    }
}
