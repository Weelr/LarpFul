package com.weelr.larpful2

import android.graphics.Matrix
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val K_PEN = 0
const val K_BALL = 1
const val K_HL = 2
const val K_SHAPE = 3
/** study tape: a band that covers what is under it until it is tapped */
const val K_TAPE = 4

/** which tapes are peeled back right now. Not saved: a notebook always opens with every tape covering again. */
object Tape {
    private val open = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Stroke, Boolean>())
    @Synchronized fun isOpen(s: Stroke) = open.contains(s)
    @Synchronized fun toggle(s: Stroke): Boolean = if (open.remove(s)) false else { open.add(s); true }
    @Synchronized fun setAll(list: List<Stroke>, show: Boolean) { if (show) open.addAll(list) else open.removeAll(list.toSet()) }
}

private var idSeq = 0
fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 46655).toInt(), 36) + java.lang.Integer.toString(idSeq++ % 36, 36)

/* ---------- page content ---------- */
abstract class Item {
    abstract val bounds: RectF
    abstract fun transformed(m: Matrix): Item
}

/** pts = x, y, widthFactor triples in page points. Immutable once created. */
class Stroke(val tool: Int, val color: Int, val width: Float, val pts: FloatArray, val time: Long) : Item() {
    val n: Int get() = pts.size / 3
    override val bounds: RectF by lazy {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE; var mf = 1f
        var i = 0
        while (i < pts.size) { val x = pts[i]; val y = pts[i + 1]; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; if (pts[i + 2] > mf) mf = pts[i + 2]; i += 3 }
        val pad = width * mf / 2f + 1f
        RectF(l - pad, t - pad, r + pad, b + pad)
    }
    override fun transformed(m: Matrix): Stroke {
        val out = pts.copyOf(); val xy = FloatArray(2); var i = 0
        while (i < out.size) { xy[0] = out[i]; xy[1] = out[i + 1]; m.mapPoints(xy); out[i] = xy[0]; out[i + 1] = xy[1]; i += 3 }
        return Stroke(tool, color, width * m.mapRadius(1f), out, time)
    }
    fun recolored(c: Int): Stroke = Stroke(tool, if (tool == K_HL) (c and 0xFFFFFF) or (color and (0xFF shl 24)) else c, width, pts, time)
    /** square eraser: does the line come within the axis-aligned square of half-size r around (x,y)? */
    fun hitBox(x: Float, y: Float, r: Float): Boolean {
        val b = bounds; val h = r + width / 2f
        if (x < b.left - h || x > b.right + h || y < b.top - h || y > b.bottom + h) return false
        val l = x - h; val t = y - h; val rr = x + h; val bb = y + h
        if (pts.size == 3) return pts[0] in l..rr && pts[1] in t..bb
        var i = 0
        while (i + 5 < pts.size) { if (segRect(pts[i], pts[i + 1], pts[i + 3], pts[i + 4], l, t, rr, bb)) return true; i += 3 }
        return false
    }
    /** squared-distance style hit test against the polyline */
    fun hit(x: Float, y: Float, r: Float): Boolean {
        val b = bounds
        if (x < b.left - r || x > b.right + r || y < b.top - r || y > b.bottom + r) return false
        val rr = r + width / 2f; val r2 = rr * rr
        if (pts.size == 3) { val dx = pts[0] - x; val dy = pts[1] - y; return dx * dx + dy * dy <= r2 }
        var i = 0
        while (i + 5 < pts.size) { if (segDist2(x, y, pts[i], pts[i + 1], pts[i + 3], pts[i + 4]) <= r2) return true; i += 3 }
        return false
    }
}

/** Liang–Barsky: does segment a→b touch the rectangle? */
fun segRect(ax: Float, ay: Float, bx: Float, by: Float, l: Float, t: Float, r: Float, b: Float): Boolean {
    var t0 = 0f; var t1 = 1f; val dx = bx - ax; val dy = by - ay
    fun clip(p: Float, q: Float): Boolean { if (p == 0f) return q >= 0f; val u = q / p; if (p < 0f) { if (u > t1) return false; if (u > t0) t0 = u } else { if (u < t0) return false; if (u < t1) t1 = u }; return true }
    return clip(-dx, ax - l) && clip(dx, r - ax) && clip(-dy, ay - t) && clip(dy, b - ay)
}

fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
    var t = if (l2 == 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / l2
    t = max(0f, min(1f, t))
    val cx = ax + t * dx - px; val cy = ay + t * dy - py
    return cx * cx + cy * cy
}

class TextItem(val text: String, val x: Float, val y: Float, val w: Float, val size: Float, val color: Int, val bold: Boolean) : Item() {
    val layout: StaticLayout by lazy {
        val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.textSize = size; tp.color = color
        tp.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        StaticLayout.Builder.obtain(text, 0, text.length, tp, max(10, w.toInt())).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
    }
    override val bounds: RectF by lazy {
        var mw = 0f; for (i in 0 until layout.lineCount) mw = max(mw, layout.getLineWidth(i))
        RectF(x - 2, y - 2, x + max(mw, 12f) + 2, y + layout.height + 2)
    }
    override fun transformed(m: Matrix): TextItem {
        val xy = floatArrayOf(x, y); m.mapPoints(xy); val s = m.mapRadius(1f)
        return TextItem(text, xy[0], xy[1], w * s, size * s, color, bold)
    }
}

class ImageItem(val file: String, val rect: RectF) : Item() {
    override val bounds: RectF get() = rect
    override fun transformed(m: Matrix): ImageItem { val r = RectF(rect); m.mapRect(r); return ImageItem(file, r) }
}

class LinkItem(val rect: RectF, val noteId: String, val pageId: String, val label: String) : Item() {
    override val bounds: RectF get() = rect
    override fun transformed(m: Matrix): LinkItem { val r = RectF(rect); m.mapRect(r); return LinkItem(r, noteId, pageId, label) }
}

/* ---------- binary ink format ---------- */
object InkIO {
    private const val MAGIC = 0x4C463249 // "LF2I"
    private fun DataOutputStream.str(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataInputStream.str(): String { val n = readInt(); val b = ByteArray(n); readFully(b); return String(b, Charsets.UTF_8) }
    private fun DataOutputStream.rect(r: RectF) { writeFloat(r.left); writeFloat(r.top); writeFloat(r.right); writeFloat(r.bottom) }
    private fun DataInputStream.rect(): RectF = RectF(readFloat(), readFloat(), readFloat(), readFloat())

    fun write(out: DataOutputStream, items: List<Item>) {
        out.writeInt(MAGIC); out.writeInt(1); out.writeInt(items.size)
        for (it in items) when (it) {
            is Stroke -> {
                out.writeByte(1); out.writeByte(it.tool); out.writeInt(it.color); out.writeFloat(it.width); out.writeLong(it.time); out.writeInt(it.pts.size)
                val bb = ByteBuffer.allocate(it.pts.size * 4); bb.asFloatBuffer().put(it.pts); out.write(bb.array())
            }
            is TextItem -> { out.writeByte(2); out.str(it.text); out.writeFloat(it.x); out.writeFloat(it.y); out.writeFloat(it.w); out.writeFloat(it.size); out.writeInt(it.color); out.writeBoolean(it.bold) }
            is ImageItem -> { out.writeByte(3); out.str(it.file); out.rect(it.rect) }
            is LinkItem -> { out.writeByte(4); out.rect(it.rect); out.str(it.noteId); out.str(it.pageId); out.str(it.label) }
            else -> out.writeByte(0)
        }
    }

    fun read(inp: DataInputStream): ArrayList<Item> {
        val res = ArrayList<Item>()
        if (inp.readInt() != MAGIC) return res
        inp.readInt(); val n = inp.readInt()
        for (k in 0 until n) {
            when (inp.readByte().toInt()) {
                1 -> {
                    val tool = inp.readByte().toInt(); val color = inp.readInt(); val w = inp.readFloat(); val time = inp.readLong(); val len = inp.readInt()
                    val raw = ByteArray(len * 4); inp.readFully(raw); val pts = FloatArray(len); ByteBuffer.wrap(raw).asFloatBuffer().get(pts)
                    if (len >= 3) res.add(Stroke(tool, color, w, pts, time))
                }
                2 -> { val s = inp.str(); res.add(TextItem(s, inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readInt(), inp.readBoolean())) }
                3 -> { val f = inp.str(); res.add(ImageItem(f, inp.rect())) }
                4 -> { val r = inp.rect(); val a = inp.str(); val b = inp.str(); val c = inp.str(); res.add(LinkItem(r, a, b, c)) }
            }
        }
        return res
    }
    fun writeFile(f: File, items: List<Item>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { write(it, items) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
    fun readFile(f: File): ArrayList<Item> = try { if (f.exists()) DataInputStream(f.inputStream().buffered(1 shl 16)).use { read(it) } else ArrayList() } catch (e: Exception) { ArrayList() }
}

/* ---------- note / page ---------- */
class Page(val id: String) {
    var w = 595f; var h = 842f
    var paper = "blank"; var paperColor = -1
    var src: String? = null      // PDF file inside the note folder
    var srcPage = 0
    var bg: String? = null       // image file used as page background
    @Volatile var items = ArrayList<Item>()   // replaced/modified on the UI thread only
    @Volatile var loaded = false
    var dirty = false
    var rev = 0
    var text: String? = null     // cached PDF text for search (lazy)
    var label: String? = null    // page title written at the top of the paper (the original's "page label")
    fun json(): JSONObject = JSONObject().put("id", id).put("w", w.toDouble()).put("h", h.toDouble()).put("paper", paper).put("pc", paperColor)
        .put("src", src ?: JSONObject.NULL).put("sp", srcPage).put("bg", bg ?: JSONObject.NULL).put("lb", label ?: JSONObject.NULL)
    companion object {
        fun from(o: JSONObject): Page = Page(o.getString("id")).apply {
            w = o.optDouble("w", 595.0).toFloat(); h = o.optDouble("h", 842.0).toFloat(); paper = o.optString("paper", "blank"); paperColor = o.optInt("pc", -1)
            src = if (o.isNull("src")) null else o.optString("src"); srcPage = o.optInt("sp", 0); bg = if (o.isNull("bg")) null else o.optString("bg"); label = if (o.isNull("lb")) null else o.optString("lb").ifEmpty { null }
        }
    }
}

class Rec(val file: String, val start: Long, var dur: Long)

class Note(val id: String) {
    val pages = ArrayList<Page>()
    val recs = ArrayList<Rec>()
    var vpPage = 0; var vpZoom = 1f; var vpOff = 0f
    var metaDirty = false
    val dir: File get() = Store.noteDir(id)
    val meta: NoteMeta? get() = Lib.note(id)
    fun json(): JSONObject {
        val a = JSONArray(); for (p in pages) a.put(p.json())
        val r = JSONArray(); for (x in recs) r.put(JSONObject().put("f", x.file).put("s", x.start).put("d", x.dur))
        return JSONObject().put("v", 1).put("pages", a).put("recs", r).put("vp", vpPage).put("vz", vpZoom.toDouble()).put("vo", vpOff.toDouble())
    }
    fun pageById(pid: String): Int = pages.indexOfFirst { it.id == pid }
}

class NoteMeta(val id: String) {
    var title = "Untitled"; var folder: String? = null; var pinned = false; var locked = false; var cover = false
    var created = System.currentTimeMillis(); var modified = created; var pages = 1; var trashed = 0L; var fid: String? = null
    var first: String? = null   // id of the first page (for the preview thumbnail)
    var ord = 0.0               // place in "My own order"
    fun json(): JSONObject = JSONObject().put("o", ord).put("id", id).put("t", title).put("f", folder ?: JSONObject.NULL).put("pin", pinned).put("lock", locked).put("cover", cover)
        .put("c", created).put("m", modified).put("n", pages).put("tr", trashed).put("fid", fid ?: JSONObject.NULL).put("p0", first ?: JSONObject.NULL)
    companion object {
        fun from(o: JSONObject): NoteMeta = NoteMeta(o.getString("id")).apply {
            title = o.optString("t", "Untitled"); folder = if (o.isNull("f")) null else o.optString("f"); pinned = o.optBoolean("pin"); locked = o.optBoolean("lock"); cover = o.optBoolean("cover")
            created = o.optLong("c"); modified = o.optLong("m"); pages = o.optInt("n", 1); trashed = o.optLong("tr"); fid = if (o.isNull("fid")) null else o.optString("fid")
            first = if (o.isNull("p0")) null else o.optString("p0"); ord = o.optDouble("o", 0.0)
        }
    }
}

class Folder(val id: String) {
    var name = "Folder"; var parent: String? = null; var pinned = false; var locked = false; var cover = false; var created = System.currentTimeMillis(); var color = 0; var ord = 0.0
    fun json(): JSONObject = JSONObject().put("o", ord).put("id", id).put("n", name).put("p", parent ?: JSONObject.NULL).put("pin", pinned).put("lock", locked).put("cover", cover).put("c", created).put("col", color)
    companion object {
        fun from(o: JSONObject): Folder = Folder(o.getString("id")).apply {
            name = o.optString("n", "Folder"); parent = if (o.isNull("p")) null else o.optString("p"); pinned = o.optBoolean("pin"); locked = o.optBoolean("lock"); cover = o.optBoolean("cover")
            created = o.optLong("c"); color = o.optInt("col"); ord = o.optDouble("o", 0.0)
        }
    }
}

/* ---------- library index (one small file, read at launch) ---------- */
object Lib {
    val folders = ArrayList<Folder>()
    val notes = ArrayList<NoteMeta>()
    var pin = ""            // hashed lock code
    var lastBackup = 0L
    var rev = 0
    val listeners = ArrayList<() -> Unit>()
    private val file get() = File(Store.root, "library.json")

    fun note(id: String): NoteMeta? = notes.firstOrNull { it.id == id }
    fun folder(id: String?): Folder? = if (id == null) null else folders.firstOrNull { it.id == id }
    fun load() {
        folders.clear(); notes.clear()
        try {
            val f = file; if (!f.exists()) return
            val o = JSONObject(f.readText())
            val fa = o.optJSONArray("folders") ?: JSONArray(); for (i in 0 until fa.length()) folders.add(Folder.from(fa.getJSONObject(i)))
            val na = o.optJSONArray("notes") ?: JSONArray(); for (i in 0 until na.length()) notes.add(NoteMeta.from(na.getJSONObject(i)))
            pin = o.optString("pin", ""); lastBackup = o.optLong("bk", 0)
        } catch (e: Exception) { }
    }
    fun json(): JSONObject {
        val fa = JSONArray(); for (f in folders) fa.put(f.json())
        val na = JSONArray(); for (n in notes) na.put(n.json())
        return JSONObject().put("v", 1).put("folders", fa).put("notes", na).put("pin", pin).put("bk", lastBackup)
    }
    private var pending = false
    fun changed(notify: Boolean = true) {
        rev++
        if (notify) for (l in ArrayList(listeners)) l()
        if (pending) return
        pending = true
        act.root.postDelayed({ pending = false; val s = json().toString(); Store.io { Store.atomic(file, s) } }, 300)
    }
    fun saveNow() { Store.atomic(file, json().toString()) }

    fun childrenFolders(parent: String?): List<Folder> = folders.filter { it.parent == parent }
    fun childrenNotes(parent: String?): List<NoteMeta> = notes.filter { it.folder == parent && it.trashed == 0L }
    fun trash(): List<NoteMeta> = notes.filter { it.trashed != 0L }
    /** notebooks in a folder and in everything below it */
    fun notesUnder(folder: String): List<NoteMeta> = notes.filter { it.trashed == 0L && it.folder != null && (it.folder == folder || isDescendant(it.folder, folder)) }
    fun path(folder: String?): String { val parts = ArrayList<String>(); var f = folder(folder); var g = 0; while (f != null && g++ < 20) { parts.add(0, f.name); f = folder(f.parent) }; return parts.joinToString(" › ") }
    fun isDescendant(folder: String?, of: String): Boolean { var f = folder(folder); var g = 0; while (f != null && g++ < 30) { if (f.id == of) return true; f = folder(f.parent) }; return false }
    fun sorted(list: List<NoteMeta>): List<NoteMeta> {
        if (P.s("lib.sort") == "manual") return list.sortedByDescending { it.modified }.sortedBy { it.ord }   // new notebooks (0) come first, the rest as arranged
        val base = when (P.s("lib.sort")) { "name" -> list.sortedBy { it.title.lowercase() }; "created" -> list.sortedByDescending { it.created }; else -> list.sortedByDescending { it.modified } }
        return base.sortedByDescending { it.pinned }
    }
    fun hash(code: String): String { val md = java.security.MessageDigest.getInstance("SHA-256").digest(("larpful2:$code").toByteArray()); return md.joinToString("") { String.format("%02x", it) } }
}
