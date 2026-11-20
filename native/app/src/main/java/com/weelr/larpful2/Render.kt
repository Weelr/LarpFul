package com.weelr.larpful2

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.*

/** Draws page items. One instance per thread (it owns its Paint/Path). */
class InkPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    var sc = 1f          // device pixels per page point (controls curve subdivision)
    var mult = true      // highlighter blends with what is under it
    @Volatile var dimBefore = 0L   // audio replay: ink written after this moment (inside the recording) is dimmed
    @Volatile var dimEnd = 0L
    @Volatile var dimAlpha = 255

    fun stroke(c: Canvas, s: Stroke) {
        if (s.tool == K_TAPE) { tape(c, s.pts, s.pts.size, s.color, s.width, Tape.isOpen(s)); return }
        var color = s.color
        if (dimBefore != 0L && s.time > dimBefore && s.time <= dimEnd) color = (color and 0xFFFFFF) or ((Color.alpha(color) * dimAlpha / 255) shl 24)
        raw(c, s.pts, s.pts.size, s.tool, color, s.width)
    }

    /** study tape: a straight band from the first point to the last. Covered = solid (optionally striped); peeled = a faint outline. */
    fun tape(c: Canvas, pts: FloatArray, size: Int, color: Int, w: Float, peeled: Boolean) {
        if (size < 6) return
        val ax = pts[0]; val ay = pts[1]; val bx = pts[size - 3]; val by = pts[size - 2]
        val len = hypot(bx - ax, by - ay); val h = w / 2f; val rad = min(h, 3f)
        c.save(); c.translate(ax, ay); c.rotate(Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat())
        val l = -h * 0.35f; val r = len + h * 0.35f
        fillP.blendMode = null
        if (!peeled) {
            fillP.color = color or (0xFF shl 24); c.drawRoundRect(l, -h, r, h, rad, rad, fillP)
            if (P.s("tape.look") == "stripes") {
                c.clipRect(l, -h, r, h); p.color = 0x33FFFFFF; p.strokeWidth = w * 0.16f; p.strokeCap = Paint.Cap.BUTT
                var x = l - w; while (x < r + w) { c.drawLine(x, h, x + w, -h, p); x += w * 0.42f }
                p.strokeCap = Paint.Cap.ROUND
            }
        } else {
            when (P.s("tape.peeled")) {
                "faint" -> { fillP.color = (color and 0xFFFFFF) or (0x2E shl 24); c.drawRoundRect(l, -h, r, h, rad, rad, fillP) }
                "hidden" -> {}
                else -> { fillP.color = (color and 0xFFFFFF) or (0x14 shl 24); c.drawRoundRect(l, -h, r, h, rad, rad, fillP)
                    p.color = (color and 0xFFFFFF) or (0xB0 shl 24); p.strokeWidth = max(0.8f, w * 0.05f); p.pathEffect = DashPathEffect(floatArrayOf(w * 0.22f, w * 0.16f), 0f)
                    c.drawRoundRect(l, -h, r, h, rad, rad, p); p.pathEffect = null }
            }
        }
        c.restore()
    }

    /** size = number of floats used in pts (lets the live stroke be drawn straight from its growing buffer) */
    fun raw(c: Canvas, pts: FloatArray, size: Int, tool: Int, color: Int, w: Float) {
        val n = size / 3
        if (n <= 0) return
        if (tool == K_TAPE) { tape(c, pts, size, color, w, false); return }
        if (n == 1) {
            fillP.color = color; fillP.blendMode = if (tool == K_HL && mult) BlendMode.MULTIPLY else null
            c.drawCircle(pts[0], pts[1], w * pts[2] / 2f, fillP); return
        }
        val al = Color.alpha(color)
        if (al < 255 && tool != K_HL && tool != K_SHAPE) {
            // translucent ink: draw the stroke solid into a layer and fade the layer once, so the overlapping segments
            // of one stroke do not add up into darker beads
            var l = pts[0]; var t = pts[1]; var r = l; var b = t; var mf = 1f; var j = 0
            while (j + 2 < size) { val x = pts[j]; val y = pts[j + 1]; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; if (pts[j + 2] > mf) mf = pts[j + 2]; j += 3 }
            val pad = w * mf / 2f + 2f
            val sc0 = c.saveLayerAlpha(l - pad, t - pad, r + pad, b + pad, al)
            raw(c, pts, size, tool, color or (0xFF shl 24), w)
            c.restoreToCount(sc0); return
        }
        p.color = color
        if (tool == K_HL || tool == K_SHAPE) {
            path.rewind(); path.moveTo(pts[0], pts[1])
            if (tool == K_SHAPE || n < 12) { var i = 3; while (i < size) { path.lineTo(pts[i], pts[i + 1]); i += 3 } }
            else {
                var i = 3
                while (i + 3 < size) { path.quadTo(pts[i], pts[i + 1], (pts[i] + pts[i + 3]) / 2f, (pts[i + 1] + pts[i + 4]) / 2f); i += 3 }
                path.lineTo(pts[i], pts[i + 1])
            }
            p.strokeWidth = w
            p.strokeCap = if (tool == K_HL) Paint.Cap.SQUARE else Paint.Cap.ROUND
            p.blendMode = if (tool == K_HL && mult) BlendMode.MULTIPLY else null
            c.drawPath(path, p); p.blendMode = null; p.strokeCap = Paint.Cap.ROUND
            return
        }
        // variable-width ink: quadratic curve through the midpoints, widths interpolated
        var ax = pts[0]; var ay = pts[1]; var aw = pts[2] * w
        if (n == 2) { p.strokeWidth = (aw + pts[5] * w) / 2f; c.drawLine(ax, ay, pts[3], pts[4], p); return }
        var mx = (pts[0] + pts[3]) / 2f; var my = (pts[1] + pts[4]) / 2f; var mw = (pts[2] + pts[5]) / 2f * w
        p.strokeWidth = (aw + mw) / 2f; c.drawLine(ax, ay, mx, my, p)
        ax = mx; ay = my; aw = mw
        var i = 3
        val stepLen = 3.5f / sc
        while (i + 3 < size) {
            val cx = pts[i]; val cy = pts[i + 1]
            mx = (cx + pts[i + 3]) / 2f; my = (cy + pts[i + 4]) / 2f; mw = (pts[i + 2] + pts[i + 5]) / 2f * w
            val len = abs(cx - ax) + abs(cy - ay) + abs(mx - cx) + abs(my - cy)
            val steps = if (len <= stepLen) 1 else min(10, (len / stepLen).toInt() + 1)
            if (steps == 1) { p.strokeWidth = (aw + mw) / 2f; c.drawLine(ax, ay, mx, my, p) }
            else {
                var px = ax; var py = ay
                for (k in 1..steps) {
                    val t = k / steps.toFloat(); val u = 1 - t
                    val x = u * u * ax + 2 * u * t * cx + t * t * mx; val y = u * u * ay + 2 * u * t * cy + t * t * my
                    p.strokeWidth = aw + (mw - aw) * (t - 0.5f / steps); c.drawLine(px, py, x, y, p); px = x; py = y
                }
            }
            ax = mx; ay = my; aw = mw; i += 3
        }
        val lw = pts[i + 2] * w
        p.strokeWidth = (aw + lw) / 2f; c.drawLine(ax, ay, pts[i], pts[i + 1], p)
    }

    fun item(c: Canvas, it: Item, baseDir: File?) {
        when (it) {
            is Stroke -> stroke(c, it)
            is TextItem -> { c.save(); c.translate(it.x, it.y); it.layout.draw(c); c.restore() }
            is ImageItem -> { val b = if (baseDir != null) Images.get(File(baseDir, it.file)) else null
                if (b != null) c.drawBitmap(b, null, it.rect, bmpP) else { fillP.blendMode = null; fillP.color = col(0x22888888); c.drawRect(it.rect, fillP) } }
            is LinkItem -> link(c, it)
        }
    }

    private fun link(c: Canvas, l: LinkItem) {
        val style = P.s("lk.style"); if (style == "none") return
        val color = P.i("lk.color"); val r = l.rect
        when (style) {
            "highlight" -> { fillP.blendMode = null; fillP.color = (color and 0xFFFFFF) or (0x33 shl 24); c.drawRoundRect(r, 3f, 3f, fillP) }
            "box" -> { p.color = color; p.strokeWidth = P.f("lk.w"); c.drawRoundRect(r, 3f, 3f, p) }
            else -> { p.color = color; p.strokeWidth = P.f("lk.w"); c.drawLine(r.left, r.bottom, r.right, r.bottom, p) }
        }
        if (P.b("lk.marker")) {
            p.color = color; p.strokeWidth = 1.2f; val x = r.right + 2f; val y = r.top + 1f
            c.drawLine(x, y + 6f, x + 6f, y, p); c.drawLine(x + 2f, y, x + 6f, y, p); c.drawLine(x + 6f, y, x + 6f, y + 4f, p)
        }
    }
}

/** decoded image cache for ImageItems / image page backgrounds */
object Images {
    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    @Synchronized fun get(f: File): Bitmap? {
        val k = f.path; val c = mem.get(k); if (c != null) return c
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(k, o)
            var ss = 1; while (o.outWidth / ss > 2600 || o.outHeight / ss > 2600) ss *= 2
            val o2 = BitmapFactory.Options(); o2.inSampleSize = ss
            val b = BitmapFactory.decodeFile(k, o2); if (b != null) mem.put(k, b); b
        } catch (e: Throwable) { null }
    }
}

/** PdfRenderer wrapper: a few documents stay open; one page rendered at a time. */
object Pdf {
    private class Doc(val pfd: ParcelFileDescriptor, val r: PdfRenderer)
    private val open = LinkedHashMap<String, Doc>(8, 0.75f, true)
    private fun doc(f: File): Doc? {
        val k = f.path; open[k]?.let { return it }
        if (!f.exists()) return null
        return try {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); val d = Doc(pfd, PdfRenderer(pfd)); open[k] = d
            if (open.size > 4) { val first = open.entries.first(); open.remove(first.key); try { first.value.r.close(); first.value.pfd.close() } catch (e: Exception) { } }
            d
        } catch (e: Throwable) { null }
    }
    @Synchronized fun count(f: File): Int = doc(f)?.r?.pageCount ?: 0
    @Synchronized fun size(f: File, i: Int): Pair<Float, Float>? {
        val d = doc(f) ?: return null; if (i < 0 || i >= d.r.pageCount) return null
        return try { val pg = d.r.openPage(i); try { pg.width.toFloat() to pg.height.toFloat() } finally { pg.close() } } catch (e: Throwable) { null }
    }
    /** m maps PDF points -> bitmap pixels */
    @Synchronized fun render(f: File, i: Int, bmp: Bitmap, pageW: Float, pageH: Float, scale: Float, offX: Float, offY: Float, print: Boolean = false): Boolean {
        val d = doc(f) ?: return false; if (i < 0 || i >= d.r.pageCount) return false
        return try {
            val pg = d.r.openPage(i)
            try {
                val m = Matrix(); m.setScale(pageW / pg.width * scale, pageH / pg.height * scale); m.postTranslate(-offX, -offY)
                pg.render(bmp, null, m, if (print) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally { pg.close() }
            true
        } catch (e: Throwable) { false }
    }
    @Synchronized fun close(f: File) { val d = open.remove(f.path) ?: return; try { d.r.close(); d.pfd.close() } catch (e: Exception) { } }
    @Synchronized fun closeAll() { for (d in open.values) try { d.r.close(); d.pfd.close() } catch (e: Exception) { }; open.clear() }
}

object Render {
    val bgIp = InkPainter()   // bake thread only
    /** the original's paper gallery, in its order (older "wide" and "todo" pages still draw) */
    val PAPERS = listOf("lined" to "Lined", "grid" to "Grid", "dots" to "Dotted", "blank" to "Blank", "graph" to "Graph", "cornell" to "Cornell", "music" to "Music staff", "iso" to "Isometric dots", "dark" to "Dark", "black" to "Black")
    val PAPER_COLORS = intArrayOf(col(0xFFFFFFFF), col(0xFFFFFBEA), col(0xFFF3F7FF), col(0xFFEEFAF0), col(0xFFFFF1F6), col(0xFFF2F2F2))
    private const val K = 595f / 794f

    /** Lines of the paper, drawn exactly like the original (its sizes were px on a 794 px wide page). */
    fun paper(c: Canvas, w: Float, h: Float, kind: String, color: Int, label: String? = null) {
        val dark = lum(color) < 0.45f
        if (label != null && label.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.color = if (dark) Color.WHITE else col(0xFF1D3A7A); tp.textSize = 22f * K
            tp.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD); c.drawText(label, 50f * K, 64f * K, tp)
        }
        if (kind == "blank" || kind == "dark" || kind == "black") return
        val al = (P.f("paper.line") / 100f).coerceIn(0.2f, 2f)
        val sp = P.f("paper.sp").coerceIn(12f, 80f) * K * (if (kind == "wide") 1.35f else 1f)
        val lw = 1f * K
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE; p.strokeWidth = lw
        val alpha = ((if (dark) min(1f, 0.2f * al) else min(1f, if (al > 1f) 0.6f + (al - 1f) * 0.8f else al)) * 255).toInt().coerceIn(8, 255)
        val line = if (dark) Color.WHITE else if (al > 1f) col(0xFFA9BFE3) else col(0xFFC9D6EC)
        p.color = line; p.alpha = alpha
        val dotP = Paint(Paint.ANTI_ALIAS_FLAG); dotP.style = Paint.Style.FILL; dotP.color = if (dark) Color.WHITE else col(0xFF9FB0CC); dotP.alpha = alpha
        val top = Math.round(P.f("paper.sp").coerceIn(12f, 80f) * 3.2f) * K * (if (kind == "wide") 1.35f else 1f)
        val d = 2.4f * K
        when (kind) {
            "lined", "wide", "cornell", "todo" -> {
                var y = top; while (y < h) { c.drawLine(0f, y, w, y, p); y += sp }
                if (kind == "cornell") { p.color = if (dark) Color.WHITE else col(0xFF7C93C4); p.alpha = 178; p.strokeWidth = lw * 1.4f
                    c.drawLine(w * 0.28f, top - sp, w * 0.28f, h * 0.83f, p); c.drawLine(0f, top - sp, w, top - sp, p); c.drawLine(0f, h * 0.83f, w, h * 0.83f, p) }
                else if (kind == "todo") { var yy = top; while (yy < h - sp) { c.drawRoundRect(sp * 1.2f, yy + sp * 0.22f, sp * 1.2f + sp * 0.56f, yy + sp * 0.78f, 1.5f, 1.5f, p); yy += sp } }
                else if (P.b("paper.margin")) { p.color = if (dark) col(0xFFFF6B6B) else col(0xFFF4A3A3); p.alpha = 204; c.drawLine(78f * K, 0f, 78f * K, h, p) }
            }
            "grid" -> { var y = sp; while (y < h) { c.drawLine(0f, y, w, y, p); y += sp }; var x = sp; while (x < w) { c.drawLine(x, 0f, x, h, p); x += sp } }
            "graph" -> {
                val f = sp / 4f; p.strokeWidth = lw * 0.6f
                var y = f; while (y < h) { c.drawLine(0f, y, w, y, p); y += f }; var x = f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += f }
                p.strokeWidth = lw * 1.3f; y = sp; while (y < h) { c.drawLine(0f, y, w, y, p); y += sp }; x = sp; while (x < w) { c.drawLine(x, 0f, x, h, p); x += sp }
            }
            "dots" -> { var y = sp; while (y < h) { var x = sp; while (x < w) { c.drawRect(x - K, y - K, x - K + d, y - K + d, dotP); x += sp }; y += sp } }
            "iso" -> { val dy = sp * 0.866f; var row = 0; var y = dy
                while (y < h) { var x = (if (row % 2 == 1) sp / 2f else 0f) + sp / 2f; while (x < w) { c.drawRect(x - K, y - K, x - K + d, y - K + d, dotP); x += sp }; y += dy; row++ } }
            "music" -> { val g = sp * 0.26f; val pitch = sp * 3.1f; val mx = 40f * K; var y = top
                while (y + 4 * g < h) { for (k in 0..4) c.drawLine(mx, y + k * g, w - mx, y + k * g, p); c.drawLine(mx, y, mx, y + 4 * g, p); y += pitch } }
        }
    }

    /**
     * Renders (part of) a page into a bitmap. scale = bitmap px per page point; (offX, offY) = bitmap origin in page pixels.
     * Runs on any thread with its own InkPainter.
     */
    fun page(bmp: Bitmap, note: Note, pg: Page, scale: Float, offX: Float, offY: Float, items: List<Item>, skip: Set<Item>?, ip: InkPainter, print: Boolean = false) {
        val c = Canvas(bmp)
        c.drawColor(if (pg.src != null) Color.WHITE else pg.paperColor)
        val src = pg.src
        if (src != null) Pdf.render(File(note.dir, src), pg.srcPage, bmp, pg.w, pg.h, scale, offX, offY, print)
        c.translate(-offX, -offY); c.scale(scale, scale)
        val bg = pg.bg
        if (bg != null) { val b = Images.get(File(note.dir, bg)); if (b != null) c.drawBitmap(b, null, RectF(0f, 0f, pg.w, pg.h), Paint(Paint.FILTER_BITMAP_FLAG)) }
        if (src == null && bg == null) paper(c, pg.w, pg.h, pg.paper, pg.paperColor, pg.label)
        items(c, note, pg, scale, RectF(offX / scale, offY / scale, (offX + bmp.width) / scale, (offY + bmp.height) / scale), items, skip, ip)
    }

    fun items(c: Canvas, note: Note, pg: Page, scale: Float, clip: RectF?, items: List<Item>, skip: Set<Item>?, ip: InkPainter) {
        ip.sc = scale
        ip.mult = P.b("hl.under") && (pg.src != null || pg.bg != null || lum(pg.paperColor) > 0.5f)
        val dir = note.dir
        for (it in items) {
            if (skip != null && skip.contains(it)) continue
            if (clip != null && !RectF.intersects(clip, it.bounds)) continue
            ip.item(c, it, dir)
        }
    }

    fun paperPreview(kind: String, color: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.drawColor(color)
        val s = w / 595f; c.scale(s, s); paper(c, 595f, h / s, kind, color); return b
    }
}

/* ---------- geometry helpers: smoothing + shape tidy-up (no handwriting guessing) ---------- */
object Geo {
    /** "Straighten almost-straight strokes": a stroke that never strays more than amount/1500 of its length from the
     *  line between its two ends becomes that line (points re-spaced along it, pressure kept in order) */
    fun straighten(pts: FloatArray, amount: Float, width: Float): FloatArray {
        val n = pts.size / 3; if (n < 3 || amount <= 0f) return pts
        val ax = pts[0]; val ay = pts[1]; val bx = pts[(n - 1) * 3]; val by = pts[(n - 1) * 3 + 1]
        val dx = bx - ax; val dy = by - ay; val ch = hypot(dx, dy)
        if (ch <= max(width, 1f) * 8f) return pts
        var md = 0f; var len = 0f; var i = 0
        while (i < pts.size) { md = max(md, abs((pts[i] - ax) * dy - (pts[i + 1] - ay) * dx) / ch); if (i > 0) len += hypot(pts[i] - pts[i - 3], pts[i + 1] - pts[i - 2]); i += 3 }
        if (md > ch * amount / 1500f || len > ch * 1.15f) return pts   // bent, or going back and forth (a scribble): leave it
        val m = max(2, min(n, (ch / 2f).toInt() + 2)); val out = FloatArray(m * 3)
        for (k in 0 until m) { val t = k / (m - 1f); val src = (t * (n - 1)).toInt().coerceIn(0, n - 1)
            out[k * 3] = ax + dx * t; out[k * 3 + 1] = ay + dy * t; out[k * 3 + 2] = pts[src * 3 + 2] }
        return out
    }
    /** light Gaussian-like pass that keeps end points and sharp corners */
    fun refine(pts: FloatArray, amount: Float): FloatArray {
        val n = pts.size / 3; if (n < 5 || amount <= 0f) return pts
        val out = pts.copyOf(); val k = min(0.5f, amount * 0.5f)
        for (pass in 0 until 2) {
            val src = out.copyOf()
            for (i in 1 until n - 1) {
                val a = (i - 1) * 3; val b = i * 3; val c = (i + 1) * 3
                val v1x = src[b] - src[a]; val v1y = src[b + 1] - src[a + 1]; val v2x = src[c] - src[b]; val v2y = src[c + 1] - src[b + 1]
                val d = v1x * v2x + v1y * v2y; val l = sqrt((v1x * v1x + v1y * v1y) * (v2x * v2x + v2y * v2y))
                if (l > 0 && d / l < 0.3f) continue   // sharp corner: keep
                out[b] = src[b] * (1 - k) + (src[a] + src[c]) * 0.5f * k
                out[b + 1] = src[b + 1] * (1 - k) + (src[a + 1] + src[c + 1]) * 0.5f * k
            }
        }
        return out
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(bx - ax, by - ay)

    /** Ramer–Douglas–Peucker on x,y pairs; returns kept indices */
    private fun rdp(x: FloatArray, y: FloatArray, eps: Float): List<Int> {
        val keep = BooleanArray(x.size); keep[0] = true; keep[x.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to x.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast(); var md = 0f; var mi = -1
            for (i in a + 1 until b) { val d = sqrt(segDist2(x[i], y[i], x[a], y[a], x[b], y[b])); if (d > md) { md = d; mi = i } }
            if (mi >= 0 && md > eps) { keep[mi] = true; stack.addLast(a to mi); stack.addLast(mi to b) }
        }
        return keep.indices.filter { keep[it] }
    }

    /** Returns tidy polygon points (x,y pairs) or null when the stroke is not clearly a shape. */
    fun shape(pts: FloatArray, snapAngles: Boolean): FloatArray? {
        val n = pts.size / 3; if (n < 4) return null
        val x = FloatArray(n) { pts[it * 3] }; val y = FloatArray(n) { pts[it * 3 + 1] }
        var len = 0f; for (i in 1 until n) len += dist(x[i - 1], y[i - 1], x[i], y[i])
        if (len < 12f) return null
        val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
        val diag = hypot(maxX - minX, maxY - minY)
        val gap = dist(x[0], y[0], x[n - 1], y[n - 1])
        // straight line
        if (gap > len * 0.93f) return line(x[0], y[0], x[n - 1], y[n - 1], snapAngles)
        val closed = gap < max(diag * 0.22f, 10f)
        val idx = rdp(x, y, max(diag * 0.06f, 3f))
        if (!closed) {
            // arrow: a long shaft followed by a short hook back
            if (idx.size in 3..4) {
                val a = idx[0]; val b = idx[1]
                val shaft = dist(x[a], y[a], x[b], y[b]); val rest = len - shaft
                if (shaft > len * 0.6f && rest < shaft * 0.6f) return arrow(x[a], y[a], x[b], y[b], snapAngles)
            }
            if (idx.size == 2) return line(x[0], y[0], x[n - 1], y[n - 1], snapAngles)
            if (idx.size in 3..6) { val o = FloatArray(idx.size * 2); idx.forEachIndexed { k, i -> o[k * 2] = x[i]; o[k * 2 + 1] = y[i] }; return o }   // polyline with clean corners
            return null
        }
        val corners = idx.size - 1
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val rx = (maxX - minX) / 2; val ry = (maxY - minY) / 2
        // ellipse test: how far the points are from the fitted ellipse
        var err = 0f
        if (rx > 2 && ry > 2) { for (i in 0 until n) { val dx = (x[i] - cx) / rx; val dy = (y[i] - cy) / ry; err += abs(sqrt(dx * dx + dy * dy) - 1f) }; err /= n } else err = 9f
        if (err < 0.11f && corners != 3 || corners > 6) {
            if (err > 0.2f) return null
            val r2x = if (abs(rx - ry) < max(rx, ry) * 0.12f) (rx + ry) / 2 else rx; val r2y = if (r2x != rx) r2x else ry
            val m = 64; val o = FloatArray((m + 1) * 2)
            for (k in 0..m) { val t = (k * 2.0 * PI / m).toFloat(); o[k * 2] = cx + r2x * cos(t); o[k * 2 + 1] = cy + r2y * sin(t) }
            return o
        }
        if (corners == 3) { val o = FloatArray(8); for (k in 0 until 3) { o[k * 2] = x[idx[k]]; o[k * 2 + 1] = y[idx[k]] }; o[6] = o[0]; o[7] = o[1]; return o }
        if (corners == 4) {
            // rectangle when the sides are close to axis-aligned, otherwise a clean quadrilateral
            var axis = true
            for (k in 0 until 4) { val i = idx[k]; val j = idx[k + 1]; val ang = abs(Math.toDegrees(atan2((y[j] - y[i]).toDouble(), (x[j] - x[i]).toDouble()))) % 90; if (ang > 14 && ang < 76) axis = false }
            if (axis) return floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY)
            val o = FloatArray(10); for (k in 0 until 4) { o[k * 2] = x[idx[k]]; o[k * 2 + 1] = y[idx[k]] }; o[8] = o[0]; o[9] = o[1]; return o
        }
        if (corners in 5..6) { val o = FloatArray((corners + 1) * 2); for (k in 0 until corners) { o[k * 2] = x[idx[k]]; o[k * 2 + 1] = y[idx[k]] }; o[corners * 2] = o[0]; o[corners * 2 + 1] = o[1]; return o }
        return null
    }

    private fun snapEnd(ax: Float, ay: Float, bx: Float, by: Float, snap: Boolean): FloatArray {
        if (!snap) return floatArrayOf(bx, by)
        val l = dist(ax, ay, bx, by); val ang = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble()))
        val near = (ang / 45.0).roundToInt() * 45.0
        if (abs(ang - near) > 5) return floatArrayOf(bx, by)
        val r = Math.toRadians(near); return floatArrayOf(ax + (l * cos(r)).toFloat(), ay + (l * sin(r)).toFloat())
    }
    private fun line(ax: Float, ay: Float, bx: Float, by: Float, snap: Boolean): FloatArray { val e = snapEnd(ax, ay, bx, by, snap); return floatArrayOf(ax, ay, e[0], e[1]) }
    private fun arrow(ax: Float, ay: Float, bx0: Float, by0: Float, snap: Boolean): FloatArray {
        val e = snapEnd(ax, ay, bx0, by0, snap); val bx = e[0]; val by = e[1]
        val l = dist(ax, ay, bx, by); val h = min(l * 0.28f, 16f); val ang = atan2(by - ay, bx - ax)
        val a1 = ang + 2.6f; val a2 = ang - 2.6f
        return floatArrayOf(ax, ay, bx, by, bx + h * cos(a1), by + h * sin(a1), bx, by, bx + h * cos(a2), by + h * sin(a2))
    }
    fun toPts(xy: FloatArray): FloatArray { val o = FloatArray(xy.size / 2 * 3); for (i in 0 until xy.size / 2) { o[i * 3] = xy[i * 2]; o[i * 3 + 1] = xy[i * 2 + 1]; o[i * 3 + 2] = 1f }; return o }

    /** fraction of a stroke's points inside the polygon (x,y pairs) */
    fun inside(poly: FloatArray, px: Float, py: Float): Boolean {
        var c = false; val n = poly.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]; val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}
