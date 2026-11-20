package com.weelr.larpful2

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/* ---------- globals ---------- */
lateinit var act: MainActivity
var DENS = 2f
fun dp(v: Number): Int = (v.toFloat() * DENS + 0.5f).toInt()
fun dpf(v: Number): Float = v.toFloat() * DENS
fun col(hex: Long): Int = hex.toInt()

/* ---------- theme ---------- */
object Th {
    var dark = false
    var trueBlack = false
    var accent = col(0xFF007AFF)
    var family = "sans-serif"
    var trans = "slide"
    var animK = 1f          // duration multiplier for every transition
    fun dur(ms: Long): Long = (ms * animK).toLong().coerceAtLeast(1)
    // the same palette as the first Larpful: grouped-grey panels, white cards, blue accent, yellow tint
    val bg get() = if (dark) (if (trueBlack) col(0xFF0A0A0A) else col(0xFF1C1C1E)) else col(0xFFF2F2F7)
    val card get() = if (dark) (if (trueBlack) col(0xFF151515) else col(0xFF2C2C2E)) else col(0xFFFFFFFF)
    val bar get() = bg
    val glass get() = if (dark) col(0xF52C2C2E) else col(0xF5FFFFFF)
    val text get() = if (dark) col(0xFFFFFFFF) else col(0xFF000000)
    val sub get() = if (dark) col(0x99EBEBF5) else col(0x993C3C43)
    val sep get() = if (dark) col(0x99545458) else col(0x2E3C3C43)
    val hair get() = if (dark) col(0x1AFFFFFF) else col(0x14000000)
    val fill get() = if (dark) col(0x3D787880) else col(0x1F787880)
    val fill2 get() = if (dark) col(0x5C787880) else col(0x33787880)
    val sel get() = (accent and 0xFFFFFF) or ((if (dark) 0x47 else 0x24) shl 24)
    val tint get() = if (dark) col(0xFFFFD60A) else col(0xFFF5B400)
    val canvas get() = if (dark && trueBlack) col(0xFF000000) else bg
    val danger get() = if (dark) col(0xFFFF453A) else col(0xFFFF3B30)
    val green get() = if (dark) col(0xFF30D158) else col(0xFF34C759)
    fun load() {
        val m = P.s("ui.theme")
        dark = when (m) { "dark" -> true; "light" -> false
            else -> (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES }
        trueBlack = P.b("ui.black")
        accent = when (P.s("ui.accentN")) { "teal" -> col(0xFF30B0C7); "green" -> col(0xFF34C759); "purple" -> col(0xFFAF52DE); "pink" -> col(0xFFFF2D55); "orange" -> col(0xFFFF9500); "red" -> col(0xFFFF3B30)
            "graphite" -> col(0xFF8E8E93); "custom" -> P.i("ui.accent") or col(0xFF000000); else -> col(0xFF007AFF) }
        if (dark && accent == col(0xFF007AFF)) accent = col(0xFF0A84FF)
        family = when (P.s("ui.font")) { "serif", "naskh" -> "serif"; "kufi" -> "sans-serif-medium"; "mono" -> "monospace"; "condensed" -> "sans-serif-condensed"; "light" -> "sans-serif-light"; "medium" -> "sans-serif-medium"; "casual" -> "casual"; else -> "sans-serif" }
        trans = if (P.b("ui.anim")) P.s("ui.trans") else "none"
        animK = 100f / P.f("ui.speed").coerceIn(40f, 300f)
    }
}

/* ---------- icons (own line-icon set, drawn from tiny path programs) ---------- */
object Ic {
    private val src = mapOf(
        "pen" to "M4 20 L8.5 19 L19.5 8 L16 4.5 L5 15.5 Z;M13.5 7 L17 10.5",
        "ball" to "M5 21 L8 20 L17 11 L15 9 L6 18 Z;M15 9 L18 6 L20 8 L17 11;M19.5 4.5 L21 3",
        "hl" to "M8 14 L15 7 L19 11 L12 18 Z;M8 14 L6.5 19.5 L10.5 19.5 L12 18;M15 7 L17 5 L21 9 L19 11;M4 22 L14 22",
        "eraser" to "M3.5 15 L12 6.5 L18.5 13 L12.5 19 L7.5 19 Z;M8.5 10 L15 16.5;M12.5 19 L20.5 19",
        "lasso" to "M12 4.5 C6.5 4.5 3 7.5 3 10.5 C3 13.5 7 16 12 16 C17 16 21 13.5 21 10.5 C21 7.5 17.5 4.5 12 4.5;O 7.5 16.5 1.8;M7.5 18.3 C7.5 20 8.5 21 11 21",
        "ruler" to "M3 16 L16 3 L21 8 L8 21 Z;M7 12 L9 14;M10 9 L12 11;M13 6 L15 8",
        "text" to "M5 7 L5 4.5 L19 4.5 L19 7;M12 4.5 L12 19.5;M9 19.5 L15 19.5",
        "image" to "R 3 5 18 14 2.5;O 8.5 10 1.5;M4 17 L10 12 L14 15 L17 13 L21 16",
        "undo" to "M8 5 L4 9 L8 13;M4 9 L14 9 C18 9 20 12 20 14.5 C20 17 18 19 15 19 L11 19",
        "redo" to "M16 5 L20 9 L16 13;M20 9 L10 9 C6 9 4 12 4 14.5 C4 17 6 19 9 19 L13 19",
        "plus" to "M12 5 L12 19;M5 12 L19 12",
        "close" to "M6 6 L18 18;M18 6 L6 18",
        "back" to "M15 5 L8 12 L15 19",
        "chev" to "M9 5 L16 12 L9 19",
        "down" to "M5 9 L12 16 L19 9",
        "more" to "f O 5 12 1.7;f O 12 12 1.7;f O 19 12 1.7",
        "search" to "O 10.5 10.5 6;M15 15 L20 20",
        "folder" to "M3 7 L3 18 C3 19 4 20 5 20 L19 20 C20 20 21 19 21 18 L21 9 C21 8 20 7 19 7 L12 7 L10 5 L5 5 C4 5 3 6 3 7",
        "folderplus" to "M3 7 L3 18 C3 19 4 20 5 20 L19 20 C20 20 21 19 21 18 L21 9 C21 8 20 7 19 7 L12 7 L10 5 L5 5 C4 5 3 6 3 7;M12 10.5 L12 16.5;M9 13.5 L15 13.5",
        "note" to "R 5 3 14 18 2.5;M8 8 L16 8;M8 12 L16 12;M8 16 L13 16",
        "noteplus" to "R 5 3 14 18 2.5;M12 8 L12 16;M8 12 L16 12",
        "pin" to "M9 3 L15 3 L14 9 L17 13 L7 13 L10 9 Z;M12 13 L12 21",
        "lock" to "R 5 11 14 10 2.5;M8 11 L8 8 C8 5 10 4 12 4 C14 4 16 5 16 8 L16 11",
        "trash" to "M4 7 L20 7;M9 7 L9 4 L15 4 L15 7;M6 7 L7 20 L17 20 L18 7;M10 11 L10 16;M14 11 L14 16",
        "copy" to "R 8 8 12 12 2.5;M16 8 L16 5.5 C16 4.5 15.5 4 14.5 4 L5.5 4 C4.5 4 4 4.5 4 5.5 L4 14.5 C4 15.5 4.5 16 5.5 16 L8 16",
        "cut" to "O 6 7 2.5;O 6 17 2.5;M8 8.5 L20 17;M8 15.5 L20 7",
        "dup" to "R 4 4 12 12 2.5;R 8 8 12 12 2.5",
        "paste" to "R 5 5 14 16 2.5;R 9 3 6 4 1;M9 12 L15 12;M9 16 L13 16",
        "link" to "M10 14 C8 12 8 9 10 7 L12 5 C14 3 17 3 19 5 C21 7 21 10 19 12 L18 13;M14 10 C16 12 16 15 14 17 L12 19 C10 21 7 21 5 19 C3 17 3 14 5 12 L6 11",
        "mic" to "R 9 3 6 11 3;M6 11 C6 15 8.5 17 12 17 C15.5 17 18 15 18 11;M12 17 L12 21;M9 21 L15 21",
        "play" to "f M8 5 L19 12 L8 19 Z",
        "pause" to "f R 7 5 3.5 14 1;f R 13.5 5 3.5 14 1",
        "stop" to "f R 6 6 12 12 2.5",
        "split" to "R 3 4 18 16 2.5;M12 4 L12 20",
        "shelf" to "R 3 4 18 16 2.5;M15 4 L15 20;M17.3 9 L18.7 9;M17.3 12 L18.7 12",
        "sidebar" to "R 3 4 18 16 2.5;M9 4 L9 20;M5.3 8 L6.7 8;M5.3 11 L6.7 11",
        "pages" to "R 4 3 7 8 1.5;R 13 3 7 8 1.5;R 4 13 7 8 1.5;R 13 13 7 8 1.5",
        "gear" to "O 12 12 3.2;M12 2.5 L12 5.5;M12 18.5 L12 21.5;M2.5 12 L5.5 12;M18.5 12 L21.5 12;M5.3 5.3 L7.4 7.4;M16.6 16.6 L18.7 18.7;M5.3 18.7 L7.4 16.6;M16.6 7.4 L18.7 5.3",
        "check" to "M5 12 L10 17 L19 7",
        "share" to "M12 15 L12 3;M8 7 L12 3 L16 7;M7 11 L5.5 11 C4.5 11 4 11.5 4 12.5 L4 19.5 C4 20.5 4.5 21 5.5 21 L18.5 21 C19.5 21 20 20.5 20 19.5 L20 12.5 C20 11.5 19.5 11 18.5 11 L17 11",
        "history" to "M4 12 C4 7.5 7.5 4 12 4 C16.5 4 20 7.5 20 12 C20 16.5 16.5 20 12 20 C9 20 6.5 18.5 5 16;M4 6 L4 11 L9 11;M12 8 L12 12 L15 14",
        "paper" to "R 5 3 14 18 2.5;M5 9 L19 9;M5 13 L19 13;M5 17 L19 17",
        "edit" to "M4 20 L8.5 19 L19.5 8 L16 4.5 L5 15.5 Z",
        "move" to "M3 8 L3 18 L21 18 L21 8 L12 8 L10 6 L3 6 Z;M9 13 L15 13;M13 11 L15 13 L13 15",
        "star" to "M12 3 L14.8 9 L21 9.8 L16.5 14.2 L17.6 20.5 L12 17.5 L6.4 20.5 L7.5 14.2 L3 9.8 L9.2 9 Z",
        "starf" to "f M12 3 L14.8 9 L21 9.8 L16.5 14.2 L17.6 20.5 L12 17.5 L6.4 20.5 L7.5 14.2 L3 9.8 L9.2 9 Z",
        "drag" to "f O 9 7 1.4;f O 15 7 1.4;f O 9 12 1.4;f O 15 12 1.4;f O 9 17 1.4;f O 15 17 1.4",
        "paneL" to "R 3 4 18 16 2.5;f R 3 4 8 16 2.5",
        "paneR" to "R 3 4 18 16 2.5;f R 13 4 8 16 2.5",
        "paneT" to "R 3 4 18 16 2.5;f R 3 4 18 7 2.5",
        "paneB" to "R 3 4 18 16 2.5;f R 3 13 18 7 2.5",
        "shape" to "O 8.5 15 5;R 11 4 9 9 1.5",
        "tape" to "M3.5 9 L20.5 9 L19.3 11.2 L20.5 13.5 L19.3 15.8 L20.5 18 L3.5 18 L4.7 15.8 L3.5 13.5 L4.7 11.2 Z;M7 9 L5 6;M12 9 L10 6",
        "info" to "O 12 12 9;M12 11 L12 17;f O 12 7.8 1.1",
        "spark" to "M12 3 L13.9 10.1 L21 12 L13.9 13.9 L12 21 L10.1 13.9 L3 12 L10.1 10.1 Z;M19 3 L19 6;M17.5 4.5 L20.5 4.5",
        "bolt" to "M13 3 L5 13 L11 13 L10 21 L19 10 L13 10 Z",
        "logo" to "M8 4 L8 18.5 L18 18.5",
        "up" to "M5 15 L12 8 L19 15",
        "target" to "O 12 12 8;O 12 12 3;M12 2 L12 5;M12 19 L12 22;M2 12 L5 12;M19 12 L22 12",
        "save" to "R 4 4 16 16 2.5;M8 4 L8 9 L15 9 L15 4;R 8 13 8 7 1",
        "clock" to "O 12 12 9;M12 7 L12 12 L15.5 14",
        "eye" to "M2 12 C5 7 8.5 5 12 5 C15.5 5 19 7 22 12 C19 17 15.5 19 12 19 C8.5 19 5 17 2 12;O 12 12 3",
        "skipb" to "M11 6 L4 12 L11 18 Z;M20 6 L13 12 L20 18 Z",
        "skipf" to "M13 6 L20 12 L13 18 Z;M4 6 L11 12 L4 18 Z",
        "shot" to "M4 9 L4 5.5 C4 4.5 4.5 4 5.5 4 L9 4;M15 4 L18.5 4 C19.5 4 20 4.5 20 5.5 L20 9;M20 15 L20 18.5 C20 19.5 19.5 20 18.5 20 L15 20;M9 20 L5.5 20 C4.5 20 4 19.5 4 18.5 L4 15;O 12 12 2.6",
        "comment" to "M5 5 L19 5 C20 5 20.5 5.5 20.5 6.5 L20.5 15 C20.5 16 20 16.5 19 16.5 L11 16.5 L7 20 L7 16.5 L5 16.5 C4 16.5 3.5 16 3.5 15 L3.5 6.5 C3.5 5.5 4 5 5 5;M8 9 L16 9;M8 12.5 L13 12.5",
        "sets" to "R 4 4 7 7 1.5;R 13 4 7 7 1.5;R 4 13 7 7 1.5;O 16.5 16.5 3.5",
        "folderf" to "f M3 7 L3 18 C3 19 4 20 5 20 L19 20 C20 20 21 19 21 18 L21 9 C21 8 20 7 19 7 L12 7 L10 5 L5 5 C4 5 3 6 3 7 Z",
        "pinf" to "f M9 3 L15 3 L14 9 L17 13 L7 13 L10 9 Z;M12 13 L12 21",
        "sort" to "M7 5 L7 19;M4 16 L7 19 L10 16;M17 19 L17 5;M14 8 L17 5 L20 8",
        "fit" to "M4 9 L4 4 L9 4;M20 9 L20 4 L15 4;M4 15 L4 20 L9 20;M20 15 L20 20 L15 20",
        "minus" to "M5 12 L19 12",
        "updown" to "M8 9 L12 5 L16 9;M8 15 L12 19 L16 15",
        "rec" to "f O 12 12 6",
        "unlock" to "R 5 11 14 10 2.5;M8 11 L8 7.6 C8 3.4 14.6 2.9 15.9 6.6",
        "mv" to "M12 3 L12 21;M3 12 L21 12;M9 6 L12 3 L15 6;M9 18 L12 21 L15 18;M6 9 L3 12 L6 15;M18 9 L21 12 L18 15",
        "max" to "M14 4 L20 4 L20 10;M10 20 L4 20 L4 14;M20 4 L13 11;M4 20 L11 13",
        "shLine" to "M5 19 L19 5", "shRect" to "R 4 6 16 12 2", "shEll" to "O 12 12 8",
        "phones" to "M4 15 L4 12 C4 7.5 7.5 4 12 4 C16.5 4 20 7.5 20 12 L20 15;R 3 14 4.5 6.5 1.8;R 16.5 14 4.5 6.5 1.8",
        "snip" to "M7 2.5 L7 17 L21.5 17;M2.5 7 L17 7 L17 21.5",
        "selc" to "O 12 12 9;M8 12.2 L11 15 L16 9.5",
        "hash" to "M9.5 4 L7.5 20;M16.5 4 L14.5 20;M4.5 9 L20 9;M4 15 L19.5 15",
        "nbook" to "R 6 3 13 18 2.2;M10 3 L10 21;M4 7.5 L6 7.5;M4 12 L6 12;M4 16.5 L6 16.5",
        "dashc" to "d O 12 12 8",
        "camera" to "M4 8.5 C4 7.5 4.5 7 5.5 7 L8 7 L9.5 4.8 L14.5 4.8 L16 7 L18.5 7 C19.5 7 20 7.5 20 8.5 L20 17.5 C20 18.5 19.5 19 18.5 19 L5.5 19 C4.5 19 4 18.5 4 17.5 Z;O 12 13 3.4",
        "rulerh" to "R 2.5 8.5 19 7 1.5;M6.5 8.5 L6.5 11.5;M10 8.5 L10 12.5;M13.5 8.5 L13.5 11.5;M17 8.5 L17 12.5",
        "paperl" to "R 4.5 3.5 15 17 2.5;M8 8 L16 8;M8 12 L16 12;M8 16 L13 16",
        "addpg" to "R 5 3 14 18 2.5;M12 8.5 L12 15.5;M8.5 12 L15.5 12",
        "tri" to "f M12 6 L19 18 L5 18 Z", "trid" to "f M12 18 L19 6 L5 6 Z",
        "zfit" to "M5 19 L19 5;M13 5 L19 5 L19 11;M11 19 L5 19 L5 13"
    )
    private val cache = HashMap<String, List<Pair<Path, Boolean>>>()
    fun get(n: String): List<Pair<Path, Boolean>> = cache.getOrPut(n) { parse(src[n] ?: src["note"]!!) }
    private fun parse(s: String): List<Pair<Path, Boolean>> = s.split(';').map { part ->
        var t = part.trim(); var fill = false; var dashed = false
        if (t.startsWith("f ")) { fill = true; t = t.substring(2) }
        if (t.startsWith("d ")) { dashed = true; t = t.substring(2) }
        val tk = t.replace(Regex("([MLCQZOR])"), " $1 ").split(' ').filter { it.isNotEmpty() }
        val path = Path(); var i = 0
        fun n(): Float = tk[i++].toFloat()
        while (i < tk.size) {
            when (tk[i++]) {
                "M" -> { val x = n(); val y = n(); path.moveTo(x, y) }
                "L" -> { val x = n(); val y = n(); path.lineTo(x, y) }
                "C" -> { val a = n(); val b = n(); val c = n(); val d = n(); val e = n(); val f = n(); path.cubicTo(a, b, c, d, e, f) }
                "Q" -> { val a = n(); val b = n(); val c = n(); val d = n(); path.quadTo(a, b, c, d) }
                "Z" -> path.close()
                "O" -> { val cx = n(); val cy = n(); val r = n(); path.addCircle(cx, cy, r, Path.Direction.CW) }
                "R" -> { val x = n(); val y = n(); val w = n(); val h = n(); val r = n(); path.addRoundRect(x, y, x + w, y + h, r, r, Path.Direction.CW) }
            }
        }
        if (dashed) {   // cut the outline into short dashes once, so it draws like any other icon part
            val pm = PathMeasure(path, false); val out = Path(); val len = pm.length; val cnt = 9; val seg = len / cnt; var d = 0f
            for (k in 0 until cnt) { pm.getSegment(d, d + seg * 0.58f, out, true); d += seg }
            Pair(out, fill)
        } else Pair(path, fill)
    }
}

class Icon(name: String, var color: Int, private val sw: Float = 1.8f) : Drawable() {
    private val parts = Ic.get(name)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var al = 255
    override fun draw(c: Canvas) {
        val b = bounds; val s = min(b.width(), b.height()) / 24f
        c.save(); c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f); c.scale(s, s)
        for ((path, fill) in parts) {
            p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            p.strokeWidth = sw; p.color = color; p.alpha = Color.alpha(color) * al / 255
            c.drawPath(path, p)
        }
        c.restore()
    }
    override fun setAlpha(a: Int) { al = a; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = dp(24)
    override fun getIntrinsicHeight() = dp(24)
}

/* ---------- small view helpers ---------- */
fun rr(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (strokeW > 0) setStroke(strokeW, stroke)
}
fun View.fx(radiusDp: Float = 10f) {
    foreground = RippleDrawable(ColorStateList.valueOf(col(0x30808080)), null, rr(-1, dpf(radiusDp)))
    isClickable = true
}
fun txt(c: Context, s: CharSequence, sp: Float = 15f, color: Int = Th.text, bold: Boolean = false): TextView = TextView(c).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); letterSpacing = -0.015f
    typeface = Typeface.create(Th.family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
}
fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }
fun ibtn(c: Context, icon: String, size: Int = 40, tint: Int = Th.accent, pad: Int = 9, on: (View) -> Unit): ImageView = ImageView(c).apply {
    setImageDrawable(Icon(icon, tint)); setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    fx(10f); setOnClickListener { on(it) }
}
fun hbox(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun vbox(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, g: Int = Gravity.TOP or Gravity.START): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, g)
const val MP = ViewGroup.LayoutParams.MATCH_PARENT
const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
fun space(c: Context, w: Int = 0, h: Int = 0, weight: Float = 0f): View = View(c).apply { layoutParams = lp(w, h, weight) }
private var toastView: TextView? = null
private val toastHide = Runnable { toastView?.let { v -> v.animate().alpha(0f).setDuration(220).withEndAction { v.visibility = View.GONE }.start() } }
fun toast(s: String) {
    act.runOnUiThread {
        var t = toastView
        if (t == null || t.parent !== act.root) {
            t = txt(act, "", 15f, Th.text); t.gravity = Gravity.CENTER; t.setPadding(dp(20), dp(12), dp(20), dp(12)); t.maxWidth = dp(520)
            act.root.addView(t, flp(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(90) }); toastView = t
        }
        t.background = rr(Th.glass, dpf(100), Th.sep, 1); t.elevation = dpf(200); t.setTextColor(Th.text); t.text = s
        t.bringToFront(); t.animate().cancel(); t.alpha = 1f; t.visibility = View.VISIBLE
        t.removeCallbacks(toastHide); t.postDelayed(toastHide, 1900)
    }
}
fun mix(a: Int, b: Int, t: Float): Int {
    val u = 1 - t
    return Color.argb((Color.alpha(a) * u + Color.alpha(b) * t).toInt(), (Color.red(a) * u + Color.red(b) * t).toInt(),
        (Color.green(a) * u + Color.green(b) * t).toInt(), (Color.blue(a) * u + Color.blue(b) * t).toInt())
}
fun lum(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

/** iOS-style switch */
class Sw(c: Context, var on: Boolean, val cb: (Boolean) -> Unit) : View(c) {
    private var t = if (on) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        setOnClickListener {
            on = !on
            ValueAnimator.ofFloat(t, if (on) 1f else 0f).apply { duration = 130; addUpdateListener { t = it.animatedValue as Float; invalidate() }; start() }
            cb(on)
        }
    }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(dp(51), dp(31)) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = mix(Th.fill2, Th.green, t); c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = Color.WHITE; p.setShadowLayer(dpf(2), 0f, dpf(1), col(0x40000000))
        val r = h / 2 - dpf(2); c.drawCircle(h / 2 + (w - h) * t, h / 2, r, p); p.clearShadowLayer()
    }
}

fun segmented(c: Context, labels: List<String>, sel: Int, on: (Int) -> Unit): LinearLayout {
    val box = hbox(c); box.background = rr(Th.fill, dpf(10)); box.setPadding(dp(2), dp(2), dp(2), dp(2))
    var cur = sel
    val tvs = ArrayList<TextView>()
    fun paint() { tvs.forEachIndexed { i, t -> t.background = if (i == cur) rr(if (Th.dark) col(0xFF636366) else Color.WHITE, dpf(8)) else null; t.elevation = if (i == cur) dpf(1.5f) else 0f; t.setTextColor(if (i == cur) Th.text else Th.sub) } }
    labels.forEachIndexed { i, s ->
        val t = txt(c, s, 14f, Th.text, true); t.gravity = Gravity.CENTER; t.setPadding(dp(12), dp(7), dp(12), dp(7)); t.oneLine()
        t.setOnClickListener { cur = i; paint(); on(i) }
        tvs.add(t); box.addView(t, lp(WC, WC, 1f))
    }
    paint(); return box
}

/** iOS-style slider: thin track, white round thumb */
class Slider(c: Context, var maxV: Int, var value: Int, val on: (Int) -> Unit) : View(c) {
    var onTrack: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(getDefaultSize(dp(120), w), dp(36)) }
    override fun onDraw(c: Canvas) {
        val pad = dpf(15); val cy = height / 2f; val w = width - 2 * pad; val x = pad + w * (if (maxV > 0) value.coerceIn(0, maxV) / maxV.toFloat() else 0f)
        p.clearShadowLayer(); p.style = Paint.Style.FILL
        p.color = Th.fill2; c.drawRoundRect(pad, cy - dpf(2), pad + w, cy + dpf(2), dpf(2), dpf(2), p)
        p.color = Th.accent; c.drawRoundRect(pad, cy - dpf(2), x, cy + dpf(2), dpf(2), dpf(2), p)
        p.color = Color.WHITE; p.setShadowLayer(dpf(3), 0f, dpf(1), col(0x50000000)); c.drawCircle(x, cy, dpf(13), p); p.clearShadowLayer()
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { parent?.requestDisallowInterceptTouchEvent(true); onTrack?.invoke(true) }
                val pad = dpf(15); val nv = Math.round(((e.x - pad) / max(1f, width - 2 * pad)).coerceIn(0f, 1f) * maxV)
                if (nv != value) { value = nv; invalidate(); on(nv) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTrack?.invoke(false)
        }
        return true
    }
    fun set(v: Int) { if (v != value) { value = v; invalidate() } }
}

/** wraps its children onto as many lines as they need (the chip rows of the original's cards) */
class Flow(c: Context, private val gap: Int = dp(8)) : ViewGroup(c) {
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws).let { if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
        var x = 0; var y = 0; var rowH = 0; var used = 0
        for (i in 0 until childCount) { val v = getChildAt(i); if (v.visibility == GONE) continue
            v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap; rowH = max(rowH, v.measuredHeight); used = max(used, x - gap) }
        setMeasuredDimension(if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else used, y + rowH)
    }
    override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l; var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) { val v = getChildAt(i); if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight); x += v.measuredWidth + gap; rowH = max(rowH, v.measuredHeight) }
    }
}

/* ---------- overlays ---------- */
class Layer(val content: View, val modal: Boolean = true, val dim: Boolean = true, val onDismiss: (() -> Unit)? = null) {
    val scrim = FrameLayout(act)
    var shown = false
    private var anim = 0
    /** anim: 0 pop, 1 from the left, 2 from the right, 3 rise. The "Screen transitions" setting can turn them all into fades, zooms or nothing. */
    fun show(p: FrameLayout.LayoutParams, anim: Int = 0): Layer {
        this.anim = when (Th.trans) { "none" -> -1; "fade" -> 9; "zoom" -> 0; else -> anim }
        content.isClickable = true
        if (modal) {
            scrim.setBackgroundColor(if (dim) col(0x40000000) else 0)
            scrim.setOnClickListener { dismiss() }
            scrim.addView(content, p)
            // a later layer is always above earlier ones and above every panel / sidebar, whatever their shadows are
            scrim.outlineProvider = null; scrim.elevation = dpf(40f + act.layers.size * 2)
            act.root.addView(scrim, flp(MP, MP))
            if (this.anim >= 0) { scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(Th.dur(120)).start() }
        } else act.root.addView(content, p)
        when (this.anim) {
            -1 -> {}
            1 -> { content.translationX = -dpf(360); content.animate().translationX(0f).setDuration(Th.dur(170)).start() }
            2 -> { content.translationX = dpf(360); content.animate().translationX(0f).setDuration(Th.dur(170)).start() }
            3 -> { content.translationY = dpf(40); content.alpha = 0f; content.animate().translationY(0f).alpha(1f).setDuration(Th.dur(150)).start() }
            9 -> { content.alpha = 0f; content.animate().alpha(1f).setDuration(Th.dur(150)).start() }
            else -> { content.scaleX = 0.94f; content.scaleY = 0.94f; content.alpha = 0f; content.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Th.dur(130)).start() }
        }
        shown = true; act.layers.add(this); return this
    }
    fun dismiss() {
        if (!shown) return
        shown = false; act.layers.remove(this)
        val v: View = if (modal) scrim else content
        val end = Runnable { (v.parent as? ViewGroup)?.removeView(v) }
        when (anim) {
            -1 -> end.run()
            1 -> { content.animate().translationX(-dpf(380)).setDuration(Th.dur(140)).start(); v.animate().alpha(if (modal) 0f else 1f).setDuration(Th.dur(140)).withEndAction(end).start() }
            2 -> { content.animate().translationX(dpf(380)).setDuration(Th.dur(140)).start(); v.animate().alpha(if (modal) 0f else 1f).setDuration(Th.dur(140)).withEndAction(end).start() }
            else -> v.animate().alpha(0f).setDuration(Th.dur(100)).withEndAction(end).start()
        }
        onDismiss?.invoke()
    }
}

fun card(c: Context, radius: Float = 22f): LinearLayout = vbox(c).apply {
    background = rr(Th.bg, dpf(radius), Th.hair, 1); elevation = dpf(14); clipToOutline = true
}

/** centered sheet: title in the middle, "Done" on the right, scrolling body (the settings look of the first Larpful) */
fun sheet(title: String, body: View, widthDp: Int = 600, onDismiss: (() -> Unit)? = null, extra: View? = null): Layer {
    val c = act
    val box = card(c, 22f)
    val bar = FrameLayout(c)
    bar.addView(txt(c, title, 17f, Th.text, true).oneLine().apply { gravity = Gravity.CENTER; setPadding(dp(80), 0, dp(80), 0) }, flp(MP, MP))
    lateinit var layer: Layer
    val right = hbox(c)
    if (extra != null) right.addView(extra)
    val done = txt(c, "Done", 17f, Th.accent, true); done.setPadding(dp(10), dp(10), dp(16), dp(10)); done.fx(); done.setOnClickListener { layer.dismiss() }
    right.addView(done); bar.addView(right, flp(WC, MP, Gravity.END or Gravity.CENTER_VERTICAL))
    box.addView(bar, lp(MP, dp(52)))
    box.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1))
    val sv = ScrollView(c); sv.isVerticalScrollBarEnabled = false; sv.addView(body, FrameLayout.LayoutParams(MP, WC))
    box.addView(sv, lp(MP, 0, 1f))
    layer = Layer(box, onDismiss = onDismiss)
    val w = min(dp(widthDp), act.root.width - dp(32)); val h = (act.root.height * 0.88f).toInt()
    layer.show(flp(w, h, Gravity.CENTER), 3)
    if (!P.b("ui.sheetOut")) layer.scrim.setOnClickListener { }
    return layer
}

/** popover placed near (x,y) in root coordinates */
fun popover(x: Int, y: Int, content: View, widthDp: Int = 0, onDismiss: (() -> Unit)? = null, radius: Float = 18f): Layer {
    val box = card(act, radius)
    val sv = ScrollView(act); sv.isVerticalScrollBarEnabled = false; sv.addView(content, FrameLayout.LayoutParams(MP, WC))
    box.addView(sv, lp(MP, WC))
    val rw = act.root.width; val rh = act.root.height
    val wSpec = if (widthDp > 0) View.MeasureSpec.makeMeasureSpec(min(dp(widthDp), rw - dp(16)), View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(rw - dp(16), View.MeasureSpec.AT_MOST)
    box.measure(wSpec, View.MeasureSpec.makeMeasureSpec(rh - dp(16), View.MeasureSpec.AT_MOST))
    val w = box.measuredWidth; val h = box.measuredHeight
    val p = flp(w, h)
    p.leftMargin = max(dp(8), min(x - w / 2, rw - w - dp(8)))
    p.topMargin = if (y + h + dp(8) <= rh) max(dp(8), y) else max(dp(8), min(y - h - dp(8), rh - h - dp(8)))
    val l = Layer(box, dim = false, onDismiss = onDismiss)
    l.show(p, 0); return l
}
fun popAt(anchor: View, content: View, widthDp: Int = 0, onDismiss: (() -> Unit)? = null): Layer {
    val a = IntArray(2); anchor.getLocationInWindow(a); val r = IntArray(2); act.root.getLocationInWindow(r)
    return popover(a[0] - r[0] + anchor.width / 2, a[1] - r[1] + anchor.height + dp(4), content, widthDp, onDismiss)
}

class MI(val icon: String, val label: String, val danger: Boolean = false, val run: () -> Unit)

/** context menu: blue icon on the left, 17pt label, red for destructive actions */
fun menuView(items: List<MI?>, close: () -> Unit, header: String? = null): LinearLayout {
    val c = act; val box = vbox(c); box.setPadding(dp(6), dp(6), dp(6), dp(6))
    if (header != null) box.addView(txt(c, header, 13f, Th.sub, true).oneLine().apply { setPadding(dp(14), dp(8), dp(14), dp(6)) })
    for (it in items) {
        if (it == null) { box.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { topMargin = dp(5); bottomMargin = dp(5); leftMargin = dp(8); rightMargin = dp(8) }); continue }
        val row = hbox(c); row.setPadding(dp(14), 0, dp(14), 0); row.fx(11f)
        row.addView(ImageView(c).apply { setImageDrawable(Icon(it.icon, if (it.danger) Th.danger else Th.accent)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(14) })
        row.addView(txt(c, it.label, 17f, if (it.danger) Th.danger else Th.text), lp(0, WC, 1f))
        row.setOnClickListener { _ -> close(); it.run() }
        box.addView(row, lp(MP, dp(44)))
    }
    return box
}
fun showMenu(x: Int, y: Int, items: List<MI?>, header: String? = null): Layer {
    lateinit var l: Layer
    l = popover(x, y, menuView(items, { l.dismiss() }, header), 300); return l
}
fun showMenu(anchor: View, items: List<MI?>, header: String? = null): Layer {
    val a = IntArray(2); anchor.getLocationInWindow(a); val r = IntArray(2); act.root.getLocationInWindow(r)
    return showMenu(a[0] - r[0] + anchor.width / 2, a[1] - r[1] + anchor.height, items, header)
}

fun dlgTheme(): Int = if (Th.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

/* ---------- iPhone-style alerts (replace the system AlertDialog: dark text on a dark box in dark mode) ---------- */
class AB(val label: String, val bold: Boolean = false, val danger: Boolean = false, val cancel: Boolean = false, val run: () -> Unit = {})
private val alertBg get() = if (Th.dark) col(0xFF2C2C2E) else col(0xFFF2F2F2)
private val alertSep get() = if (Th.dark) col(0xA6545458) else col(0x4A3C3C43)
private val alertBlue get() = if (Th.dark) col(0xFF0A84FF) else col(0xFF007AFF)
/** iOS alert: title, message, optional text field, buttons side by side (2) or stacked (1, 3+). Outside taps do nothing, Back = cancel. */
fun iosAlert(title: String, msg: String = "", field: View? = null, buttons: List<AB>): Layer {
    val c = act
    val box = vbox(c); box.background = rr(alertBg, dpf(14)); box.elevation = dpf(24); box.clipToOutline = true
    val head = vbox(c); head.gravity = Gravity.CENTER_HORIZONTAL; head.setPadding(dp(16), dp(19), dp(16), dp(if (field != null) 14 else 18))
    if (title.isNotEmpty()) head.addView(txt(c, title, 17f, Th.text, true).apply { gravity = Gravity.CENTER; letterSpacing = -0.02f; setLineSpacing(0f, 1.15f) }, lp(MP, WC))
    if (msg.isNotEmpty()) head.addView(txt(c, msg, 13f, Th.text).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp(MP, WC).apply { topMargin = dp(if (title.isNotEmpty()) 4 else 0) })
    if (field != null) head.addView(field, lp(MP, WC).apply { topMargin = dp(14) })
    box.addView(head, lp(MP, WC))
    lateinit var layer: Layer
    val side = buttons.size == 2
    fun btn(b: AB): TextView = txt(c, b.label, 17f, if (b.danger) Th.danger else alertBlue, b.bold).oneLine().apply {
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); fx(0f)
        setOnClickListener { layer.dismiss(); b.run() }
    }
    fun hair(w: Int, h: Int) = View(c).apply { setBackgroundColor(alertSep); layoutParams = lp(w, h) }
    if (side) {
        box.addView(hair(MP, 1)); val row = hbox(c)
        row.addView(btn(buttons[0]), lp(0, dp(44), 1f)); row.addView(hair(1, MP)); row.addView(btn(buttons[1]), lp(0, dp(44), 1f))
        box.addView(row, lp(MP, dp(44)))
    } else for (b in buttons) { box.addView(hair(MP, 1)); box.addView(btn(b), lp(MP, dp(44))) }
    // on close: keyboard away and focus back to the window root, so no hidden field keeps it (a focused field would steal later touches)
    layer = Layer(box, onDismiss = { act.currentFocus?.let { hideKeyboard(it) }; act.root.isFocusableInTouchMode = true; act.root.requestFocus() })
    val w = min(dp(300), act.root.width - dp(40))
    // with a text field the alert sits higher so the keyboard never covers it
    val p = if (field != null) flp(w, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (act.root.height * 0.16f).toInt() } else flp(w, WC, Gravity.CENTER)
    layer.show(p, 0)
    layer.scrim.setOnClickListener { }
    return layer
}
/** text field in the iOS alert look: rounded, follows light / dark mode, ✕ clear button */
fun iosField(init: String, hint: String, number: Boolean, password: Boolean, onDone: () -> Unit): Pair<FrameLayout, EditText> {
    val c = act; val f = FrameLayout(c); f.minimumHeight = dp(36)
    f.background = rr(if (Th.dark) col(0xFF1C1C1E) else col(0xFFFFFFFF), dpf(8), alertSep, 1)
    val e = EditText(c); e.background = null; e.setSingleLine(); e.setText(init); e.hint = hint
    e.setTextColor(Th.text); e.setHintTextColor(if (Th.dark) col(0x4DEBEBF5) else col(0x4D3C3C43)); e.highlightColor = (alertBlue and 0xFFFFFF) or (0x4D shl 24)
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); e.typeface = Typeface.create(Th.family, Typeface.NORMAL)
    e.setPadding(dp(9), 0, dp(32), 0); e.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    if (android.os.Build.VERSION.SDK_INT >= 29) e.textCursorDrawable = rr(alertBlue, 0f).apply { setSize(dp(2), dp(20)) }
    e.inputType = when { password && number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        number -> InputType.TYPE_CLASS_NUMBER; else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    e.imeOptions = EditorInfo.IME_ACTION_DONE
    e.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false }
    f.addView(e, flp(MP, dp(36)))
    val x = txt(c, "✕", 9f, if (Th.dark) col(0xFF1C1C1E) else col(0xFFFFFFFF), true).apply { gravity = Gravity.CENTER; background = rr(if (Th.dark) col(0x4DEBEBF5) else col(0x4D3C3C43), dpf(9)) }
    x.setOnClickListener { e.setText("") }
    f.addView(x, flp(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
    fun sync() { x.visibility = if (P.b("ui.dlgClear") && e.text.isNotEmpty()) View.VISIBLE else View.GONE }
    sync(); e.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = sync()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {} })
    if (P.b("ui.dlgSel")) e.selectAll() else e.setSelection(e.text.length)
    return f to e
}
fun showKeyboard(e: EditText) {
    e.postDelayed({ e.requestFocus(); (act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 120)
}
fun hideKeyboard(e: View) { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0) }

fun prompt(title: String, init: String = "", hint: String = "", number: Boolean = false, password: Boolean = false, msg: String = "", ok: String = "OK", cb: (String) -> Unit) {
    lateinit var layer: Layer; lateinit var e: EditText
    val done = { hideKeyboard(e); cb(e.text.toString().trim()) }
    val (f, ed) = iosField(init, hint, number, password) { if (layer.shown) { layer.dismiss(); done() } }
    e = ed
    layer = iosAlert(title, msg, f, listOf(AB("Cancel", cancel = true) { hideKeyboard(e) }, AB(ok, bold = true) { done() }))
    showKeyboard(e)
}
fun confirm(title: String, msg: String, ok: String = "OK", cb: () -> Unit) {
    val danger = Regex("^(delete|remove|replace|reset|erase|clear)", RegexOption.IGNORE_CASE).containsMatchIn(ok) || Regex("^(delete|remove|replace|reset|erase)", RegexOption.IGNORE_CASE).containsMatchIn(title)
    iosAlert(title, msg, null, listOf(AB("Cancel", cancel = true), AB(ok, bold = true, danger = danger) { cb() }))
}
/** list of choices as a stacked iOS alert; a "Cancel" entry (or an added one) closes it */
fun choose(title: String, labels: List<String>, cb: (Int) -> Unit) {
    val bs = labels.mapIndexed { i, l -> val cx = l.equals("Cancel", true); AB(l, bold = cx, cancel = cx, danger = Regex("^(replace|delete)", RegexOption.IGNORE_CASE).containsMatchIn(l)) { cb(i) } }
    iosAlert(title, "", null, if (bs.any { it.cancel }) bs else bs + AB("Cancel", bold = true, cancel = true))
}

/* ---------- colour picker ---------- */
/** the 24 colours of the original Larpful, in its order */
val PALETTE = intArrayOf(
    col(0xFF111111), col(0xFF6B7280), col(0xFF9CA3AF), col(0xFFFFFFFF), col(0xFFFF3B30), col(0xFFFF6B6B), col(0xFFFF9F0A), col(0xFFFFD60A),
    col(0xFFA3E635), col(0xFF34C759), col(0xFF00B894), col(0xFF06B6D4), col(0xFF0A84FF), col(0xFF1D4ED8), col(0xFF5856D6), col(0xFFAF52DE),
    col(0xFFFF2D92), col(0xFF8B4513), col(0xFF14532D), col(0xFF7F1D1D), col(0xFF1E3A8A), col(0xFFFDE68A), col(0xFFFBCFE8), col(0xFFBAE6FD))

fun colorPicker(anchor: View, initial: Int, on: (Int) -> Unit) {
    val c = act; val box = vbox(c); box.setPadding(dp(14), dp(14), dp(14), dp(14))
    val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
    val preview = View(c); preview.background = rr(initial, dpf(10), Th.sep, dp(1))
    val hex = txt(c, String.format("#%06X", initial and 0xFFFFFF), 14f, Th.sub)
    fun emit(col: Int) { preview.background = rr(col, dpf(10), Th.sep, dp(1)); hex.text = String.format("#%06X", col and 0xFFFFFF); on(col) }
    val grid = GridLayout(c); grid.columnCount = 8
    for (pc in PALETTE) {
        val v = View(c); v.background = rr(pc, dpf(15), col(0x2E000000), 1); v.fx(15f)
        v.setOnClickListener { Color.colorToHSV(pc, hsv); emit(pc) }
        grid.addView(v, GridLayout.LayoutParams().apply { width = dp(30); height = dp(30); setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    box.addView(grid)
    fun bar(label: String, idx: Int, maxV: Float) {
        val row = hbox(c); row.addView(txt(c, label, 13f, Th.sub), lp(dp(34), WC))
        row.addView(Slider(c, 1000, (hsv[idx] / maxV * 1000).toInt()) { p -> hsv[idx] = p / 1000f * maxV; emit(Color.HSVToColor(hsv)) }, lp(0, WC, 1f)); box.addView(row, lp(MP, dp(38)))
    }
    bar("Hue", 0, 360f); bar("Sat", 1, 1f); bar("Lum", 2, 1f)
    val row = hbox(c); row.addView(preview, lp(dp(44), dp(28))); row.addView(hex, lp(0, WC, 1f).apply { leftMargin = dp(10) })
    val hexBtn = txt(c, "Hex…", 14f, Th.accent); hexBtn.setPadding(dp(8), dp(6), dp(8), dp(6)); hexBtn.fx()
    hexBtn.setOnClickListener { prompt("Hex colour", hex.text.toString()) { s -> try { val v = Color.parseColor(if (s.startsWith("#")) s else "#$s"); Color.colorToHSV(v, hsv); emit(v or col(0xFF000000)) } catch (e: Exception) { toast("Invalid colour") } } }
    row.addView(hexBtn); box.addView(row, lp(MP, WC).apply { topMargin = dp(6) })
    popAt(anchor, box, 336)
}
