package com.weelr.larpful2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Store {
    lateinit var root: File
    val ui = Handler(Looper.getMainLooper())
    private val ioEx = Executors.newSingleThreadExecutor { r -> Thread(r, "lf-io").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val bgEx = Executors.newSingleThreadExecutor { r -> Thread(r, "lf-bake").apply { priority = Thread.NORM_PRIORITY } }
    private val workEx = Executors.newFixedThreadPool(2) { r -> Thread(r, "lf-work").apply { priority = Thread.NORM_PRIORITY - 2 } }
    fun io(f: () -> Unit) { ioEx.execute { try { f() } catch (e: Throwable) { e.printStackTrace() } } }
    fun bake(f: () -> Unit) { bgEx.execute { try { f() } catch (e: Throwable) { e.printStackTrace() } } }
    fun work(f: () -> Unit) { workEx.execute { try { f() } catch (e: Throwable) { e.printStackTrace(); toast("Error: " + (e.message ?: e.javaClass.simpleName)) } } }
    fun main(f: () -> Unit) { ui.post(f) }

    fun init(filesDir: File) { root = File(filesDir, "data"); File(root, "notes").mkdirs(); File(root, "shelf").mkdirs(); File(root, "covers").mkdirs() }
    fun noteDir(id: String): File = File(root, "notes/$id")
    fun inkFile(note: String, page: String): File = File(noteDir(note), "p_$page.ink")
    fun thumbFile(note: String, page: String): File = File(noteDir(note), "t_$page.jpg")
    fun coverFile(note: String): File = File(noteDir(note), "cover.jpg")
    fun folderCover(id: String): File = File(root, "covers/$id.jpg")

    fun atomic(f: File, text: String) {
        f.parentFile?.mkdirs(); val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /* ---------- notes ---------- */
    fun createNote(title: String, folder: String?, firstPage: Page? = null): Note {
        val n = Note(newId()); n.dir.mkdirs()
        val p = firstPage ?: newPage()
        p.loaded = true; n.pages.add(p)
        val m = NoteMeta(n.id); m.title = title; m.folder = folder; m.first = p.id
        Lib.notes.add(m); atomic(File(n.dir, "note.json"), n.json().toString()); Lib.changed()
        return n
    }
    fun newPage(like: Page? = null): Page {
        val p = Page(newId()); p.loaded = true
        if (like != null && like.src == null && like.bg == null) { p.w = like.w; p.h = like.h; p.paper = like.paper; p.paperColor = like.paperColor }
        else if (like != null) { p.w = like.w; p.h = like.h; p.paper = P.s("doc.paper"); p.paperColor = P.i("doc.paperColor") }
        else {
            val (w, h) = when (P.s("doc.size")) { "letter" -> 612f to 792f; "a4l" -> 842f to 595f; "sq" -> 700f to 700f; "wide" -> 960f to 540f; else -> 595f to 842f }
            p.w = w; p.h = h; p.paper = P.s("doc.paper"); p.paperColor = P.i("doc.paperColor")
        }
        return p
    }
    /** reads the page list only: instant. Ink is loaded per page afterwards. */
    fun openNote(id: String): Note? {
        val f = File(noteDir(id), "note.json"); if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText()); val n = Note(id)
            val a = o.getJSONArray("pages"); for (i in 0 until a.length()) n.pages.add(Page.from(a.getJSONObject(i)))
            val r = o.optJSONArray("recs"); if (r != null) for (i in 0 until r.length()) { val x = r.getJSONObject(i); n.recs.add(Rec(x.getString("f"), x.getLong("s"), x.getLong("d"))) }
            n.vpPage = o.optInt("vp"); n.vpZoom = o.optDouble("vz", 1.0).toFloat(); n.vpOff = o.optDouble("vo", 0.0).toFloat()
            if (n.pages.isEmpty()) n.pages.add(newPage())
            n
        } catch (e: Exception) { null }
    }
    fun loadPage(note: Note, p: Page) {
        if (p.loaded) return
        val items = InkIO.readFile(inkFile(note.id, p.id))
        p.items = items; p.loaded = true
    }
    fun loadAll(note: Note) { for (p in ArrayList(note.pages)) loadPage(note, p) }
    /** copies of pages of another notebook, with the files they need (PDF, pictures), ready to be added to [into] */
    fun clonePages(src: Note, idx: List<Int>, into: Note): List<Page> {
        val out = ArrayList<Page>()
        for (i in idx) { val p = src.pages.getOrNull(i) ?: continue; loadPage(src, p)
            val c = Page(newId()); c.w = p.w; c.h = p.h; c.paper = p.paper; c.paperColor = p.paperColor; c.src = p.src; c.srcPage = p.srcPage; c.bg = p.bg; c.label = p.label; c.items = ArrayList(p.items); c.loaded = true; c.dirty = true
            for (name in listOfNotNull(p.src, p.bg) + p.items.filterIsInstance<ImageItem>().map { it.file }) { val a = File(src.dir, name); val b = File(into.dir, name); if (a.exists() && !b.exists()) try { a.copyTo(b) } catch (e: Exception) { } }
            out.add(c) }
        return out
    }

    /** called on the UI thread: snapshots what changed and writes it on the IO thread */
    fun save(note: Note, then: (() -> Unit)? = null) {
        val dirty = note.pages.filter { it.dirty && it.loaded }
        if (dirty.isEmpty() && !note.metaDirty) { then?.let { io { it() } }; return }
        val snaps = dirty.map { it.dirty = false; Triple(it, ArrayList(it.items), it.rev) }
        val js = note.json().toString(); note.metaDirty = false
        val m = note.meta
        if (m != null) { if (dirty.isNotEmpty()) m.modified = System.currentTimeMillis(); m.pages = note.pages.size; m.first = note.pages.firstOrNull()?.id; Lib.changed(false) }
        val id = note.id; val ids = note.pages.map { it.id }
        io {
            noteDir(id).mkdirs()
            for ((p, items, _) in snaps) InkIO.writeFile(inkFile(id, p.id), items)
            atomic(File(noteDir(id), "note.json"), js)
            for ((p, items, _) in snaps) { try { Thumbs.make(note, p, items) } catch (e: Throwable) { } }
            Search.index(id, ids, snaps.associate { it.first.id to it.second })
            if (snaps.isNotEmpty()) main { Thumbs.bump(); for (l in ArrayList(Lib.listeners)) l() }
            then?.invoke()
        }
    }

    fun deleteNoteFiles(id: String) { io { noteDir(id).deleteRecursively() } }

    fun duplicateNote(m: NoteMeta, done: (NoteMeta) -> Unit) {
        val nid = newId()
        io {
            noteDir(m.id).copyRecursively(noteDir(nid), true)
            File(noteDir(nid), "history").deleteRecursively()
            main {
                val c = NoteMeta(nid); c.title = m.title + " copy"; c.folder = m.folder; c.pages = m.pages; c.first = m.first; c.cover = m.cover; c.locked = m.locked
                Lib.notes.add(c); Lib.changed(); done(c)
            }
        }
    }

    /* ---------- version history ---------- */
    fun histDir(id: String): File = File(noteDir(id), "history")
    fun snapshots(id: String): List<File> = (histDir(id).listFiles { f -> f.name.endsWith(".zip") } ?: arrayOf()).sortedByDescending { it.name }
    /** IO thread */
    fun snapshot(id: String, force: Boolean = false) {
        if (!P.b("hist.on") && !force) return
        val d = noteDir(id); val h = histDir(id); h.mkdirs()
        val last = snapshots(id).firstOrNull()
        val newest = (d.listFiles { f -> f.name.endsWith(".ink") || f.name == "note.json" } ?: arrayOf()).maxOfOrNull { it.lastModified() } ?: return
        if (last != null && !force && (newest <= last.lastModified() || System.currentTimeMillis() - last.lastModified() < P.f("hist.every") * 60000L)) return
        if (last != null && newest <= last.lastModified()) return
        val out = File(h, System.currentTimeMillis().toString() + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (f in d.listFiles() ?: arrayOf()) if (f.isFile && (f.name.endsWith(".ink") || f.name == "note.json")) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            val first = Lib.note(id)?.first
            if (first != null) { val t = thumbFile(id, first); if (t.exists()) { z.putNextEntry(ZipEntry("thumb.jpg")); t.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        }
        val all = snapshots(id); val keep = P.i("hist.max").coerceAtLeast(1)
        for (f in all.drop(keep)) f.delete()
    }
    fun snapshotThumb(f: File): Bitmap? = try {
        ZipInputStream(f.inputStream().buffered()).use { z -> var e = z.nextEntry; var b: Bitmap? = null
            while (e != null) { if (e.name == "thumb.jpg") { b = BitmapFactory.decodeStream(z); break }; e = z.nextEntry }; b }
    } catch (e: Exception) { null }
    /** IO thread: replaces ink + page list with the snapshot */
    fun restoreSnapshot(id: String, f: File) {
        val d = noteDir(id)
        for (x in d.listFiles() ?: arrayOf()) if (x.name.endsWith(".ink") || x.name.startsWith("t_")) x.delete()
        ZipInputStream(f.inputStream().buffered()).use { z -> var e = z.nextEntry
            while (e != null) { if (!e.name.contains("/") && e.name != "thumb.jpg") File(d, e.name).outputStream().use { z.copyTo(it) }; e = z.nextEntry } }
    }

    /* ---------- zip helpers ---------- */
    fun zipDir(z: ZipOutputStream, dir: File, prefix: String, skip: (File) -> Boolean = { false }) {
        for (f in dir.listFiles() ?: arrayOf()) {
            if (skip(f)) continue
            if (f.isDirectory) zipDir(z, f, prefix + f.name + "/", skip)
            else { z.putNextEntry(ZipEntry(prefix + f.name)); f.inputStream().use { it.copyTo(z, 1 shl 16) }; z.closeEntry() }
        }
    }
    fun unzip(z: ZipInputStream, into: File) {
        var e = z.nextEntry
        val base = into.canonicalPath
        while (e != null) {
            val f = File(into, e.name)
            if (f.canonicalPath.startsWith(base)) { if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it, 1 shl 16) } } }
            e = z.nextEntry
        }
    }
}

/* ---------- thumbnails: small JPEGs on disk + memory LRU ---------- */
object Thumbs {
    const val W = 320
    var gen = 0
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    fun bump() { gen++ }
    fun cached(key: String): Bitmap? = mem.get(key)
    fun drop(note: String, page: String) { mem.remove("$note/$page") }

    /** background thread */
    fun make(note: Note, p: Page, items: List<Item>) {
        val s = W / p.w; val h = (p.h * s).toInt().coerceIn(40, 1400)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        Render.page(bmp, note, p, s, 0f, 0f, items, null, InkPainter())
        val f = Store.thumbFile(note.id, p.id); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        mem.put(note.id + "/" + p.id, bmp)
    }
    /** async load; cb on UI thread (may be called synchronously if cached) */
    fun load(note: String, page: String, cb: (Bitmap?) -> Unit) {
        val key = "$note/$page"; val c = mem.get(key); if (c != null) { cb(c); return }
        Store.work {
            val f = Store.thumbFile(note, page)
            var b: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            if (b == null) {
                val n = Store.openNote(note); val p = n?.pages?.firstOrNull { it.id == page }
                if (n != null && p != null) { try { Store.loadPage(n, p); make(n, p, p.items); b = mem.get(key) } catch (e: Throwable) { } }
            } else mem.put(key, b)
            Store.main { cb(b) }
        }
    }
    fun cover(m: NoteMeta, cb: (Bitmap?) -> Unit) {
        if (m.cover) {
            val key = "cover/" + m.id; val c = mem.get(key); if (c != null) { cb(c); return }
            Store.work { val f = Store.coverFile(m.id); val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null; if (b != null) mem.put(key, b); Store.main { cb(b) } }
        } else { val p = m.first; if (p == null) cb(null) else load(m.id, p, cb) }
    }
    fun folderCover(f: Folder, cb: (Bitmap?) -> Unit) {
        if (!f.cover) { cb(null); return }
        val key = "fcover/" + f.id; val c = mem.get(key); if (c != null) { cb(c); return }
        Store.work { val x = Store.folderCover(f.id); val b = if (x.exists()) BitmapFactory.decodeFile(x.path) else null; if (b != null) mem.put(key, b); Store.main { cb(b) } }
    }
    fun dropCover(id: String) { mem.remove("cover/$id"); mem.remove("fcover/$id") }
}

/* ---------- shelf (snippets) + clipboard ---------- */
class Snip(val id: String) { var w = 0f; var h = 0f; var time = 0L; var name = "" }

object Shelf {
    val list = ArrayList<Snip>()
    var rev = 0
    private val dir get() = File(Store.root, "shelf")
    fun ink(id: String) = File(dir, "$id.ink")
    fun png(id: String) = File(dir, "$id.png")
    fun load() {
        list.clear()
        for (f in dir.listFiles { x -> x.name.endsWith(".json") } ?: arrayOf()) try {
            val o = JSONObject(f.readText()); val s = Snip(f.name.removeSuffix(".json")); s.w = o.optDouble("w").toFloat(); s.h = o.optDouble("h").toFloat(); s.time = o.optLong("t"); s.name = o.optString("n"); list.add(s)
        } catch (e: Exception) { }
    }
    fun sorted(): List<Snip> = if (P.s("sh.sort") == "old") list.sortedBy { it.time } else list.sortedByDescending { it.time }

    /** items in page coordinates; normalised so the snippet starts at 0,0. Image files are copied next to the snippet. */
    fun add(note: Note, items: List<Item>) {
        if (items.isEmpty()) return
        val b = RectF(items[0].bounds); for (it in items) b.union(it.bounds)
        val m = android.graphics.Matrix(); m.setTranslate(-b.left, -b.top)
        val id = newId(); val s = Snip(id); s.w = b.width(); s.h = b.height(); s.time = System.currentTimeMillis()
        val norm = items.map { it.transformed(m) }
        val noteDir = note.dir
        list.add(s); rev++
        Store.io {
            for (it in norm) if (it is ImageItem) { val src = File(noteDir, it.file); if (src.exists()) src.copyTo(File(dir, it.file), true) }
            InkIO.writeFile(ink(id), norm)
            val sc = minOf(3f, 480f / maxOf(s.w, s.h, 1f)); val bw = (s.w * sc).toInt().coerceAtLeast(8); val bh = (s.h * sc).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(bmp); c.scale(sc, sc)
            val ip = InkPainter(); ip.sc = sc; ip.mult = false; for (it in norm) ip.item(c, it, dir)
            png(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Store.atomic(File(dir, "$id.json"), JSONObject().put("w", s.w.toDouble()).put("h", s.h.toDouble()).put("t", s.time).toString())
            Store.main { rev++; act.onShelfChanged() }
        }
        val mx = P.i("sh.max"); while (list.size > mx) remove(list.minByOrNull { it.time }!!)
    }
    /** worker thread: a picture file becomes a snippet of its own (the FreeNotes import uses it for pictures that are on no page).
     *  Returns null when that picture is already on the shelf; the caller adds the snippet to the list on the main thread. */
    fun pictureSnip(src: File, id: String, name: String): Snip? {
        if (File(dir, "$id.json").exists()) return null
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(src.path, o); if (o.outWidth <= 0 || o.outHeight <= 0) return null
        dir.mkdirs()
        val k = minOf(1f / 3f, 400f / o.outWidth, 400f / o.outHeight)
        val s = Snip(id); s.w = o.outWidth * k; s.h = o.outHeight * k; s.time = System.currentTimeMillis(); s.name = name
        val file = "img_$id." + src.extension.ifEmpty { "png" }
        src.copyTo(File(dir, file), true)
        val items = listOf<Item>(ImageItem(file, RectF(0f, 0f, s.w, s.h)))
        InkIO.writeFile(ink(id), items)
        val sc = minOf(3f, 480f / maxOf(s.w, s.h, 1f)); val bw = (s.w * sc).toInt().coerceAtLeast(8); val bh = (s.h * sc).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(bmp); c.scale(sc, sc)
        val ip = InkPainter(); ip.sc = sc; ip.mult = false; for (it in items) ip.item(c, it, dir)
        png(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Store.atomic(File(dir, "$id.json"), JSONObject().put("w", s.w.toDouble()).put("h", s.h.toDouble()).put("t", s.time).put("n", name).toString())
        return s
    }
    fun rename(s: Snip, name: String) { s.name = name; rev++; Store.io { Store.atomic(File(dir, s.id + ".json"), JSONObject().put("w", s.w.toDouble()).put("h", s.h.toDouble()).put("t", s.time).put("n", name).toString()) } }
    /** space the shelf takes on the tablet */
    fun bytes(): Long = (dir.listFiles() ?: arrayOf()).sumOf { it.length() }
    fun remove(s: Snip) { list.remove(s); rev++; Store.io { ink(s.id).delete(); png(s.id).delete(); File(dir, s.id + ".json").delete() } }
    fun items(s: Snip): ArrayList<Item> = InkIO.readFile(ink(s.id))
    /** copies image files a snippet needs into the note folder */
    fun prepare(items: List<Item>, note: Note) { for (it in items) if (it is ImageItem) { val src = File(dir, it.file); val dst = File(note.dir, it.file); if (src.exists() && !dst.exists()) src.copyTo(dst) } }
}

object Clip {
    var items: List<Item> = emptyList()
    var fromNote: String? = null
}
