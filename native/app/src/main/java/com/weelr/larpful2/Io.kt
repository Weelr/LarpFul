package com.weelr.larpful2

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import android.view.Gravity
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Io {
    fun name(u: Uri): String {
        var n = u.lastPathSegment ?: "file"
        try { act.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) n = c.getString(i) ?: n } } } catch (e: Exception) { }
        return n
    }
    fun toCache(u: Uri): File {
        val f = File(act.cacheDir, "in_" + newId()); act.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }; return f
    }
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifEmpty { "note" }.take(80)

    /* ================= import ================= */
    fun importUris(uris: List<Uri>, intoNote: String?) {
        toast("Importing…")
        Store.work {
            var last: String? = null; var fails = 0; var msg = ""
            for (u in uris) {
                var f: File? = null
                try { val nm = name(u); f = toCache(u); last = importFile(f, nm, intoNote) ?: last }
                catch (e: Throwable) { fails++; msg = e.message ?: e.javaClass.simpleName }
                finally { f?.delete() }
            }
            val open = last
            Store.main {
                if (fails > 0) toast("Could not import $fails file(s): $msg")
                if (open != null && intoNote == null && P.b("imp.open")) act.ed.open(open)
            }
        }
    }

    private fun sniff(f: File, name: String): String {
        val head = ByteArray(16); val n = f.inputStream().use { it.read(head) }
        fun at(vararg b: Int) = b.indices.all { it < n && (head[it].toInt() and 0xFF) == b[it] }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (at(0x25, 0x50, 0x44, 0x46)) return "pdf"
        if (at(0x7B) && Larpful1.isBackup(f)) return "larpful1"
        if (at(0x89, 0x50, 0x4E, 0x47) || at(0xFF, 0xD8, 0xFF) || at(0x47, 0x49, 0x46) || at(0x42, 0x4D) || (at(0x52, 0x49, 0x46, 0x46) && n > 11 && head[8].toInt() == 0x57) || ext in listOf("heic", "heif", "webp", "jpg", "jpeg", "png")) return "image"
        if (at(0x1F, 0x8B)) return "fn"
        if (at(0x50, 0x4B)) {
            try { ZipFile(f).use { z ->
                if (z.getEntry("word/document.xml") != null) return "docx"
                if (z.getEntry("ppt/presentation.xml") != null) return "pptx"
                if (z.getEntry("larpful2.json") != null) return "backup" } } catch (e: Exception) { }
            return "fn"
        }
        if (ext in listOf("freebackup", "freenotes", "frnote")) return "fn"
        val probe = ByteArray(4096); val m = f.inputStream().use { it.read(probe) }
        for (i in 0 until m) if (probe[i].toInt() == 0) throw Exception("“$name” is not a supported file type")
        return "text"
    }

    /** worker thread. Every imported document is turned into PDF pages. Returns the note id to open. */
    fun importFile(f: File, name: String, intoNote: String?): String? {
        val base = name.substringBeforeLast('.', name)
        val tmp = File(act.cacheDir, "conv_" + newId() + ".pdf")
        try {
            when (sniff(f, name)) {
                "pdf" -> return addPdf(f, base, intoNote)
                "image" -> { Convert.image(f, tmp); return addPdf(tmp, base, intoNote) }
                // first with the document's own layout; the plain converter is only the fallback
                "docx" -> { if (!OfficeWeb.convert(f, "docx", tmp)) Convert.text(Convert.docx(f), tmp); return addPdf(tmp, base, intoNote) }
                "pptx" -> { if (!OfficeWeb.convert(f, "pptx", tmp)) Convert.pptx(f, tmp); return addPdf(tmp, base, intoNote) }
                "text" -> { Convert.text(f.readText(), tmp); return addPdf(tmp, base, intoNote) }
                "larpful1" -> { Larpful1.importFile(f); return null }
                "backup" -> { val keep = File(act.cacheDir, "restore_" + newId()); f.copyTo(keep, true); Store.main { BackupUi.restorePrompt(keep) }; return null }
                else -> { FreeNotes.importFile(f, base); return null }
            }
        } finally { tmp.delete() }
    }

    private fun addPdf(pdf: File, title: String, intoNote: String?): String {
        val src = "src_" + newId() + ".pdf"
        val noteId = intoNote ?: newId()
        val dir = Store.noteDir(noteId); dir.mkdirs(); val dst = File(dir, src); pdf.copyTo(dst, true)
        val cnt = Pdf.count(dst); if (cnt <= 0) { dst.delete(); throw Exception("“$title” could not be read as a PDF") }
        val pages = ArrayList<Page>()
        for (i in 0 until cnt) { val pg = Page(newId()); val s = Pdf.size(dst, i); pg.w = s?.first ?: 595f; pg.h = s?.second ?: 842f; pg.src = src; pg.srcPage = i; pg.loaded = true; pages.add(pg) }
        if (intoNote == null) {
            val n = Note(noteId); n.pages.addAll(pages)
            Store.atomic(File(dir, "note.json"), n.json().toString())
            try { Thumbs.make(n, pages[0], emptyList()) } catch (e: Throwable) { }
            val folder = act.lib.browser.let { if (it.trash || it.flat != null) null else it.folder }
            Store.main { val m = NoteMeta(noteId); m.title = title; m.folder = folder; m.pages = cnt; m.first = pages[0].id; Lib.notes.add(m); Thumbs.bump(); Lib.changed() }
        } else {
            Store.main {
                val pane = act.ed.panes.firstOrNull { it.note?.id == noteId }
                if (pane != null) { val n = pane.note!!; n.pages.addAll(pages); pane.view.structureChanged(); act.ed.doSave() }
                else Store.io { val n = Store.openNote(noteId); if (n != null) { n.pages.addAll(pages); Store.atomic(File(dir, "note.json"), n.json().toString()); Store.main { Lib.note(noteId)?.let { it.pages = n.pages.size; it.modified = System.currentTimeMillis() }; Lib.changed() } } }
                toast("Added $cnt page" + (if (cnt == 1) "" else "s"))
            }
        }
        try { PdfText.extract(dst, File(dir, "$src.txt")); Search.dropCache(noteId) } catch (e: Throwable) { }
        return noteId
    }

    /* ================= export ================= */
    /** the share button: a PDF of this page or of the whole notebook ("Quick export covers"), straight to the share sheet */
    fun quickExport(n: Note, cur: Int) { val pg = P.s("exp.scope") != "notebook"; export(n.id, (n.meta?.title ?: "note") + (if (pg) "-page" else ""), if (pg) cur else -1, false, true) }
    fun exportDialog(n: Note, cur: Int) {
        val title = n.meta?.title ?: "Note"; val id = n.id
        choose("Export “$title”", listOf("Share as PDF — whole note", "Share as PDF — page ${cur + 1}", "Share as image — page ${cur + 1}", "Save PDF to a folder…", "Save page ${cur + 1} as PDF…", "Save page ${cur + 1} as image…")) { k ->
            when (k) { 0 -> export(id, title, -1, false, true); 1 -> export(id, title, cur, false, true); 2 -> export(id, title, cur, true, true)
                3 -> export(id, title, -1, false, false); 4 -> export(id, title, cur, false, false); else -> export(id, title, cur, true, false) }
        }
    }
    private fun export(id: String, title: String, page: Int, png: Boolean, share: Boolean) {
        act.ed.flush()
        val name = safe(title) + (if (page >= 0) "-p${page + 1}" else "") + (if (png) ".png" else ".pdf"); val mime = if (png) "image/png" else "application/pdf"
        if (share) { toast("Preparing…"); Store.io { val f = File(ShareProvider.dir(act), name); f.outputStream().use { write(id, page, png, it) }; Store.main { act.share(f, mime) } } }
        else act.createDoc(name, mime) { uri -> toast("Saving…"); Store.io { try { act.contentResolver.openOutputStream(uri)!!.use { write(id, page, png, it) }; toast("Saved") } catch (e: Throwable) { toast("Export failed: " + e.message) } } }
    }
    /** "Share" in select mode: every chosen notebook as a PDF, in one share sheet */
    fun shareNotes(list: List<NoteMeta>) {
        act.ed.flush(); toast("Preparing…")
        Store.io {
            val files = ArrayList<File>()
            for (m in list) try { val f = File(ShareProvider.dir(act), safe(m.title) + (if (files.any { it.name == safe(m.title) + ".pdf" }) "-" + m.id.takeLast(4) else "") + ".pdf"); f.outputStream().use { write(m.id, -1, false, it) }; files.add(f) } catch (e: Throwable) { }
            Store.main {
                if (files.size == 1) act.share(files[0], "application/pdf")
                else if (files.isNotEmpty()) {
                    val uris = ArrayList<Uri>(files.map { ShareProvider.uriFor(it) })
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE); i.type = "application/pdf"; i.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris); i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { act.startActivity(android.content.Intent.createChooser(i, "Share")) } catch (e: Exception) { toast("Nothing can receive these files") }
                }
            }
        }
    }
    fun write(id: String, page: Int, png: Boolean, out: OutputStream) {
        val n = Store.openNote(id) ?: throw Exception("Note not found"); Store.loadAll(n)
        val ip = InkPainter()
        val idx = if (page >= 0 && page < n.pages.size) listOf(page) else n.pages.indices.toList()
        if (png) {
            val pg = n.pages[idx[0]]; val s = min(3f, sqrt(12e6f / (pg.w * pg.h)))
            val bmp = Bitmap.createBitmap((pg.w * s).toInt(), (pg.h * s).toInt(), Bitmap.Config.ARGB_8888)
            Render.page(bmp, n, pg, s, 0f, 0f, pg.items, null, ip, true); bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle(); return
        }
        val doc = PdfDocument(); val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((k, i) in idx.withIndex()) {
            val pg = n.pages[i]
            val page2 = doc.startPage(PdfDocument.PageInfo.Builder(max(1, pg.w.roundToInt()), max(1, pg.h.roundToInt()), k + 1).create()); val c = page2.canvas
            if (pg.src != null || pg.bg != null) {
                val s = min(2.2f, sqrt(6e6f / (pg.w * pg.h))); val bmp = Bitmap.createBitmap((pg.w * s).toInt(), (pg.h * s).toInt(), Bitmap.Config.ARGB_8888)
                Render.page(bmp, n, pg, s, 0f, 0f, emptyList(), null, ip, true); c.drawBitmap(bmp, null, RectF(0f, 0f, pg.w, pg.h), fp); bmp.recycle()
            } else { c.drawColor(pg.paperColor); Render.paper(c, pg.w, pg.h, pg.paper, pg.paperColor, pg.label) }
            Render.items(c, n, pg, 4f, null, pg.items, null, ip)
            doc.finishPage(page2)
        }
        doc.writeTo(out); doc.close()
    }
    fun shareItems(n: Note, items: List<Item>) {
        if (items.isEmpty()) return
        val dir = n.dir
        Store.work {
            val b = RectF(items[0].bounds); for (it in items) b.union(it.bounds); b.inset(-6f, -6f)
            val s = min(4f, sqrt(8e6f / max(1f, b.width() * b.height())))
            val bmp = Bitmap.createBitmap(max(1, (b.width() * s).toInt()), max(1, (b.height() * s).toInt()), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(s, s); c.translate(-b.left, -b.top); val ip = InkPainter(); ip.sc = s; ip.mult = false
            for (it in items) ip.item(c, it, dir)
            val f = File(ShareProvider.dir(act), "selection-" + System.currentTimeMillis() + ".png"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Store.main { act.share(f, "image/png") }
        }
    }
}

/* ================= anything -> PDF ================= */
object Convert {
    fun image(f: File, out: File) {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) throw Exception("The image could not be read")
        var ss = 1; while (o.outWidth / (ss * 2) >= 2400 || o.outHeight / (ss * 2) >= 2400) ss *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = ss; val bmp = BitmapFactory.decodeFile(f.path, o2) ?: throw Exception("The image could not be read")
        val pw = if (bmp.width > bmp.height) 842 else 595; val ph = (pw.toFloat() * bmp.height / bmp.width).roundToInt().coerceIn(100, 4000)
        val doc = PdfDocument(); val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        pg.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)); doc.finishPage(pg)
        out.outputStream().use { doc.writeTo(it) }; doc.close(); bmp.recycle()
    }

    /** flows text over A4 pages without cutting lines */
    fun text(cs: CharSequence, out: File) {
        val pw = 595; val ph = 842; val mg = 50
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.textSize = 11.5f; tp.color = Color.BLACK
        val text: CharSequence = if (cs.isEmpty()) " " else cs
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, pw - 2 * mg).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.1f).setIncludePad(false).build()
        val doc = PdfDocument(); var line = 0; var no = 1; val avail = ph - 2 * mg
        while (line < lay.lineCount && no <= 2000) {
            val top = lay.getLineTop(line); var end = line
            while (end + 1 < lay.lineCount && lay.getLineBottom(end + 1) - top <= avail) end++
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, no).create()); val c = pg.canvas
            c.drawColor(Color.WHITE); c.save(); c.translate(mg.toFloat(), mg.toFloat() - top); c.clipRect(0, top, pw - 2 * mg, lay.getLineBottom(end)); lay.draw(c); c.restore()
            doc.finishPage(pg); line = end + 1; no++
        }
        out.outputStream().use { doc.writeTo(it) }; doc.close()
    }

    /** reads the paragraphs of a Word file (headings kept bold and larger) */
    fun docx(f: File): CharSequence {
        val sb = SpannableStringBuilder()
        ZipFile(f).use { z ->
            val e = z.getEntry("word/document.xml") ?: throw Exception("Not a Word document")
            z.getInputStream(e).use { ins ->
                val xp = Xml.newPullParser(); xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); xp.setInput(ins, "UTF-8")
                var pStart = 0; var heading = 0; var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) { when (xp.name) {
                        "w:p" -> { pStart = sb.length; heading = 0 }
                        "w:pStyle" -> { val v = (xp.getAttributeValue(null, "w:val") ?: "").lowercase(); if (v.startsWith("heading") || v == "title") heading = if (v == "title" || v.endsWith("1")) 1 else 2 }
                        "w:t" -> sb.append(xp.nextText())
                        "w:tab" -> sb.append('\t')
                        "w:br", "w:cr" -> sb.append('\n')
                        else -> {}
                    } } else if (ev == XmlPullParser.END_TAG) { when (xp.name) {
                        "w:p" -> { if (heading > 0 && sb.length > pStart) { sb.setSpan(StyleSpan(Typeface.BOLD), pStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(RelativeSizeSpan(if (heading == 1) 1.6f else 1.3f), pStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }; sb.append('\n') }
                        "w:tc" -> sb.append('\t')
                        else -> {}
                    } }
                    ev = xp.next()
                }
            }
        }
        return sb
    }

    /** one landscape page per slide: title, text and the slide's pictures */
    fun pptx(f: File, out: File) {
        val doc = PdfDocument(); val pw = 960; val ph = 540
        ZipFile(f).use { z ->
            val rx = Regex("^ppt/slides/slide(\\d+)\\.xml$")
            val slides = z.entries().toList().mapNotNull { e -> rx.find(e.name)?.let { it.groupValues[1].toInt() to e } }.sortedBy { it.first }
            if (slides.isEmpty()) throw Exception("No slides found")
            var no = 1
            for ((num, e) in slides) {
                val paras = ArrayList<String>(); val cur = StringBuilder()
                z.getInputStream(e).use { ins ->
                    val xp = Xml.newPullParser(); xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); xp.setInput(ins, "UTF-8"); var ev = xp.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && xp.name == "a:t") cur.append(xp.nextText())
                        else if (ev == XmlPullParser.START_TAG && xp.name == "a:br") cur.append('\n')
                        else if (ev == XmlPullParser.END_TAG && xp.name == "a:p") { if (cur.isNotBlank()) paras.add(cur.toString()); cur.setLength(0) }
                        ev = xp.next()
                    }
                }
                val imgs = ArrayList<Bitmap>()
                val rel = z.getEntry("ppt/slides/_rels/slide$num.xml.rels")
                if (rel != null) {
                    val xml = z.getInputStream(rel).use { it.readBytes().toString(Charsets.UTF_8) }
                    for (m in Regex("Target=\"\\.\\./media/([^\"]+\\.(?:png|jpe?g|gif|bmp|webp))\"", RegexOption.IGNORE_CASE).findAll(xml)) {
                        if (imgs.size >= 4) break
                        val me = z.getEntry("ppt/media/" + m.groupValues[1]) ?: continue
                        try { val o = BitmapFactory.Options(); o.inSampleSize = 1; val bytes = z.getInputStream(me).use { it.readBytes() }
                            val ob = BitmapFactory.Options(); ob.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, ob)
                            while (ob.outWidth / (o.inSampleSize * 2) >= 1400) o.inSampleSize *= 2
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let { if (it.width > 40 && it.height > 40) imgs.add(it) } } catch (t: Throwable) { }
                    }
                }
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, no++).create()); val c = pg.canvas; c.drawColor(Color.WHITE)
                val mg = 36f; val textW = if (imgs.isEmpty()) pw - 2 * mg else pw * 0.52f - mg
                val sb = SpannableStringBuilder()
                paras.forEachIndexed { i, p -> val st = sb.length; sb.append(p).append("\n"); if (i == 0) { sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(RelativeSizeSpan(1.7f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.append("\n") } }
                fun mk(sz: Float): StaticLayout { val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.textSize = sz; tp.color = Color.BLACK
                    val t: CharSequence = if (sb.isEmpty()) " " else sb
                    return StaticLayout.Builder.obtain(t, 0, t.length, tp, textW.toInt()).setLineSpacing(2f, 1.08f).setIncludePad(false).build() }
                var size = 17f; var lay = mk(size)
                while (lay.height > ph - 2 * mg - 14 && size > 7f) { size -= 1.5f; lay = mk(size) }
                c.save(); c.translate(mg, mg); c.clipRect(0f, 0f, textW, ph - 2 * mg); lay.draw(c); c.restore()
                if (imgs.isNotEmpty()) {
                    val x0 = pw * 0.54f; val aw = pw - mg - x0; val ah = (ph - 2 * mg - 8f * (imgs.size - 1)) / imgs.size; var y = mg
                    val fp = Paint(Paint.FILTER_BITMAP_FLAG)
                    for (b in imgs) { val k = min(aw / b.width, ah / b.height); val w = b.width * k; val h = b.height * k; c.drawBitmap(b, null, RectF(x0 + (aw - w) / 2, y + (ah - h) / 2, x0 + (aw - w) / 2 + w, y + (ah - h) / 2 + h), fp); y += ah + 8f; b.recycle() }
                }
                val np = Paint(Paint.ANTI_ALIAS_FLAG); np.textSize = 9f; np.color = Color.GRAY; np.textAlign = Paint.Align.RIGHT; c.drawText(num.toString(), pw - 14f, ph - 12f, np)
                doc.finishPage(pg)
            }
        }
        out.outputStream().use { doc.writeTo(it) }; doc.close()
    }
}

/* ================= PDF text (for search) ================= */
object PdfText {
    private var ready = false
    /** rectangles (PDF points, origin top-left) of every occurrence of the words on one page */
    fun find(pdf: File, page: Int, words: List<String>, caseSensitive: Boolean): List<RectF> {
        if (!ready) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(App.app); ready = true }
        val out = ArrayList<RectF>(); val ws = words.map { if (caseSensitive) it else it.lowercase() }.filter { it.isNotEmpty() }
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
        try {
            val st = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                override fun writeString(text: String?, tp: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                    if (text != null && tp != null) {
                        val t = if (caseSensitive) text else text.lowercase()
                        for (w in ws) { var i = t.indexOf(w)
                            while (i >= 0 && out.size < 400) {
                                if (i + w.length <= tp.size) { var r: RectF? = null
                                    for (k in i until i + w.length) { val p = tp[k]; val x = p.xDirAdj; val y = p.yDirAdj; val hh = maxOf(p.heightDir, 4f); val c = RectF(x, y - hh * 1.15f, x + p.widthDirAdj, y + hh * 0.25f); if (r == null) r = c else r.union(c) }
                                    if (r != null) out.add(r) }
                                i = t.indexOf(w, i + 1) } }
                    }
                    super.writeString(text, tp)
                }
            }
            st.startPage = page + 1; st.endPage = page + 1; st.getText(doc)
        } finally { doc.close() }
        return out
    }
    fun extract(pdf: File, out: File) {
        if (!ready) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(App.app); ready = true }
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
        try {
            val st = com.tom_roush.pdfbox.text.PDFTextStripper(); val sb = StringBuilder()
            for (i in 1..doc.numberOfPages) { st.startPage = i; st.endPage = i; sb.append(try { st.getText(doc).trim() } catch (e: Throwable) { "" }).append('\u000C') }
            out.writeText(sb.toString())
        } finally { doc.close() }
    }
}

/* ================= FreeNotes backup reader ================= */
class PBF(val num: Int, val wire: Int, val v: Long, val b: ByteArray, val off: Int, val len: Int)
object PB {
    fun parse(u: ByteArray, off: Int = 0, len: Int = u.size): List<PBF> {
        val out = ArrayList<PBF>(); var i = off; val end = off + len
        fun vint(): Long {
            var r = 0L; var sh = 0; var c: Int
            do { if (i >= end) throw Exception("truncated"); c = u[i++].toInt() and 0xFF; if (sh < 64) r = r or ((c and 127).toLong() shl sh); sh += 7 } while (c >= 128 && sh < 77)
            return r
        }
        while (i < end) {
            val k = vint(); val f = (k ushr 3).toInt(); val t = (k and 7).toInt()
            when (t) {
                0 -> out.add(PBF(f, 0, vint(), u, 0, 0))
                1 -> { if (i + 8 > end) throw Exception("truncated"); out.add(PBF(f, 1, 0, u, i, 8)); i += 8 }
                5 -> { if (i + 4 > end) throw Exception("truncated"); out.add(PBF(f, 5, 0, u, i, 4)); i += 4 }
                2 -> { val l = vint().toInt(); if (l < 0 || i + l > end) throw Exception("truncated"); out.add(PBF(f, 2, 0, u, i, l)); i += l }
                else -> throw Exception("wire $t")
            }
        }
        return out
    }
    fun get(m: List<PBF>, f: Int): PBF? = m.firstOrNull { it.num == f }
    fun sub(f: PBF?): List<PBF> = if (f == null || f.wire != 2) emptyList() else try { parse(f.b, f.off, f.len) } catch (e: Exception) { emptyList() }
    fun str(f: PBF?): String = if (f == null || f.wire != 2) "" else String(f.b, f.off, f.len, Charsets.UTF_8)
    fun f64(f: PBF?): Double = if (f == null || f.wire != 1) Double.NaN else ByteBuffer.wrap(f.b, f.off, 8).order(ByteOrder.LITTLE_ENDIAN).double
}

object FreeNotes {
    fun importUri(u: Uri) {
        toast("Reading FreeNotes backup…")
        Store.work { var f: File? = null; try { val nm = Io.name(u); f = Io.toCache(u); importFile(f, nm.substringBeforeLast('.', nm)) } catch (e: Throwable) { toast("Import failed: " + (e.message ?: e.javaClass.simpleName)) } finally { f?.delete() } }
    }

    private fun clean(s: String) = s.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
    private fun magic(f: File): Int { val h = ByteArray(4); val n = f.inputStream().use { it.read(h) }; if (n < 4) return 0
        return if (h[0].toInt() == 0x50 && h[1].toInt() == 0x4B) 1 else if ((h[0].toInt() and 0xFF) == 0x1F && (h[1].toInt() and 0xFF) == 0x8B) 2 else 0 }

    private fun extract(f: File, into: File, depth: Int) {
        into.mkdirs()
        when (magic(f)) {
            2 -> { val t = File(into, "_gz"); GZIPInputStream(f.inputStream().buffered()).use { g -> t.outputStream().use { g.copyTo(it, 1 shl 16) } }; if (magic(t) != 0 && depth < 4) { extract(t, into, depth + 1); t.delete() } }
            1 -> {
                ZipInputStream(f.inputStream().buffered(1 shl 16)).use { Store.unzip(it, into) }
                if (depth < 3) for (x in into.walkTopDown().filter { it.isFile && it.name.matches(Regex(".*\\.(zip|gz|freebackup|freenotes?|frnote|fnb|bak|backup)$", RegexOption.IGNORE_CASE)) }.toList()) {
                    if (magic(x) != 0) { val d = File(x.parentFile, x.name + ".d"); extract(x, d, depth + 1); x.delete() } }
            }
            else -> {
                // zip with a prefix before the first local header
                val bytes = f.inputStream().use { val b = ByteArray(65540); val n = it.read(b); b.copyOf(max(0, n)) }
                var at = -1; for (i in 1 until bytes.size - 4) if (bytes[i].toInt() == 0x50 && bytes[i + 1].toInt() == 0x4B && bytes[i + 2].toInt() == 3 && bytes[i + 3].toInt() == 4) { at = i; break }
                if (at > 0 && depth < 4) { val t = File(into, "_cut"); f.inputStream().use { ins -> ins.skip(at.toLong()); t.outputStream().use { ins.copyTo(it, 1 shl 16) } }; extract(t, into, depth + 1); t.delete() }
            }
        }
    }

    /** FreeNotes saves the nib size with every point, and the line it paints is a third of that size.
     *  Measured across the lines of a capture FreeNotes made itself: nib 2.392 is painted 0.78 wide (0.327 of it);
     *  the pen keeps one width (its pressure is saved but not painted), the fountain pen carries its own width in every point. */
    private const val WK = 0.33

    private fun num(f: PBF?, d: Double): Double { val v = PB.f64(f); return if (v.isNaN() || v.isInfinite()) d else v }

    /** every stroke of one FreeNotes page, in the order it was written */
    internal fun ink(u: ByteArray): ArrayList<Item> {
        val out = ArrayList<Item>()
        val kPen = WK * P.f("imp.fnThick") / 100.0; val kHl = WK * P.f("imp.fnHl") / 100.0
        for (top in PB.parse(u)) {
            if (top.num != 3 || top.wire != 2) continue
            for (holder in PB.sub(top)) { if (holder.num != 2 || holder.wire != 2) continue
                for (rv in PB.sub(holder)) { if (rv.num != 1 || rv.wire != 2) continue
                    try { stroke(PB.sub(rv), kPen, kHl)?.let { out.add(it) } } catch (e: Exception) { } } }
        }
        return out
    }

    private fun stroke(rec: List<PBF>, kPen: Double, kHl: Double): Stroke? {
        val pm = PB.get(rec, 4) ?: return null
        var tool = 0L; var colr = 255L
        for (a in PB.sub(PB.get(rec, 2))) { if (a.wire != 0) continue; if (a.num == 1) tool = a.v else if (a.num == 2) colr = a.v }
        var ta = 1.0; var tb = 0.0; var tc = 0.0; var td = 1.0; var tx = 0.0; var ty = 0.0
        for (a in PB.sub(PB.get(rec, 3))) { if (a.wire != 1) continue; val v = PB.f64(a); if (v.isNaN() || v.isInfinite()) continue
            when (a.num) { 1 -> ta = v; 2 -> tb = v; 3 -> tc = v; 4 -> td = v; 5 -> tx = v; 6 -> ty = v } }
        val kf = sqrt(abs(ta * td - tb * tc)).let { if (it == 0.0) 1.0 else it }
        fun mx(x: Double, y: Double) = (ta * x + tc * y + tx).toFloat()
        fun my(x: Double, y: Double) = (tb * x + td * y + ty).toFloat()
        val xy = ArrayList<Float>(); val sz = ArrayList<Double>(); val fl = ArrayList<Double>(); var shape: List<PBF>? = null
        for (a in PB.sub(pm)) {
            if (a.wire != 2) continue
            if (a.num == 4) { shape = PB.sub(a); continue }
            if (a.num != 2) continue
            val q = PB.sub(a); val p = PB.sub(PB.get(q, 1)); val x = num(PB.get(p, 1), 0.0); val y = num(PB.get(p, 2), 0.0)
            xy.add(mx(x, y)); xy.add(my(x, y)); sz.add(num(PB.get(PB.sub(PB.get(q, 3)), 1), 0.0)); fl.add(num(PB.get(q, 10), 1.0))
        }
        val hl = tool == 2L
        val r = ((colr ushr 24) and 255).toInt(); val g = ((colr ushr 16) and 255).toInt(); val b = ((colr ushr 8) and 255).toInt(); val al = (colr and 255).toInt()
        val color = if (hl) Color.argb(if (al < 255) max(26, al) else 127, r, g, b) else Color.argb(max(40, al), r, g, b)
        val base = sz.firstOrNull { it > 0 } ?: if (hl) 8.5333 else 2.392
        // a stroke FreeNotes straightened into a line or a curve: what it shows is the shape, the points are only the hand-drawn start
        if (shape != null) {
            val d = PB.get(shape, 2); val n = if (d != null && d.wire == 2) d.len / 16 else 0
            if (d != null && (n == 2 || n == 3)) {
                val bb = ByteBuffer.wrap(d.b, d.off, d.len).order(ByteOrder.LITTLE_ENDIAN); val v = DoubleArray(n * 2) { bb.double }
                if (v.all { !it.isNaN() && !it.isInfinite() }) {
                    val w = (num(PB.get(shape, 3), base).let { if (it > 0) it else base } * kf * (if (hl) kHl else kPen)).toFloat().coerceIn(0.2f, 40f)
                    val pts: FloatArray
                    if (n == 2) pts = floatArrayOf(mx(v[0], v[1]), my(v[0], v[1]), 1f, mx(v[2], v[3]), my(v[2], v[3]), 1f)
                    else {   // start, end, bend
                        val len = abs(v[4] - v[0]) + abs(v[5] - v[1]) + abs(v[2] - v[4]) + abs(v[3] - v[5]); val k = (len / 1.5).toInt().coerceIn(16, 96)
                        pts = FloatArray((k + 1) * 3)
                        for (i in 0..k) { val t = i / k.toDouble(); val s = 1 - t
                            val x = s * s * v[0] + 2 * s * t * v[4] + t * t * v[2]; val y = s * s * v[1] + 2 * s * t * v[5] + t * t * v[3]
                            pts[i * 3] = mx(x, y); pts[i * 3 + 1] = my(x, y); pts[i * 3 + 2] = 1f }
                    }
                    return Stroke(if (hl) K_HL else K_SHAPE, color, w, pts, 0L)
                }
            }
        }
        val n = sz.size; if (n == 0) return null
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            pts[i * 3] = xy[i * 2]; pts[i * 3 + 1] = xy[i * 2 + 1]
            // the highlighter has one width; the pens follow the size saved in the point (tool 3 also thins with its own factor)
            var f = if (hl || sz[i] <= 0) 1.0 else sz[i] / base
            if (tool == 3L) f *= 0.4 + 0.6 * fl[i].coerceIn(0.0, 1.0)
            pts[i * 3 + 2] = f.toFloat().coerceIn(0.2f, 4f)
        }
        return if (hl) Stroke(K_HL, color, (base * kf * kHl).toFloat().coerceIn(0.5f, 40f), pts, 0L)
        else Stroke(K_PEN, color, (base * kf * kPen).toFloat().coerceIn(0.2f, 14f), pts, 0L)
    }

    /* ---- pictures ---- */
    /** the pictures a note keeps: name without extension → file */
    private fun pictures(root: File): HashMap<String, File> {
        val m = HashMap<String, File>()
        for (d in listOf(File(root, "Objects/Images"), File(root, "Images"))) for (f in d.listFiles() ?: arrayOf()) {
            if (!f.isFile || f.length() == 0L) continue
            val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth > 0 && o.outHeight > 0) m.getOrPut(f.name.substringBeforeLast('.')) { f }
        }
        return m
    }

    /** a message made only of numbers (x,y · w,h · a frame · a transform), as an array indexed by field */
    private fun numbers(a: PBF): DoubleArray? {
        if (a.wire != 2 || a.len < 9 || a.len > 54 || a.len % 9 != 0) return null
        val s = PB.sub(a); if (s.isEmpty() || s.any { it.wire != 1 || it.num < 1 || it.num > 6 }) return null
        val d = DoubleArray(s.maxOf { it.num }); for (x in s) { val v = PB.f64(x); if (v.isNaN() || v.isInfinite()) return null; d[x.num - 1] = v }
        return d
    }
    private fun numbersIn(m: List<PBF>, depth: Int, out: ArrayList<DoubleArray>) {
        for (a in m) { if (a.wire != 2) continue; val d = numbers(a); if (d != null) out.add(d) else if (depth > 0 && a.len > 9) numbersIn(PB.sub(a), depth - 1, out) }
    }
    /** where a picture sits, from the numbers saved next to its name: a frame (x, y, w, h), or a corner then a size, with an optional transform */
    private fun frame(ds: List<DoubleArray>): RectF? {
        ds.firstOrNull { it.size == 4 && it[2] > 2 && it[3] > 2 }?.let { return RectF(it[0].toFloat(), it[1].toFloat(), (it[0] + it[2]).toFloat(), (it[1] + it[3]).toFloat()) }
        val pairs = ds.filter { it.size <= 2 }.map { doubleArrayOf(it[0], if (it.size > 1) it[1] else 0.0) }
        val tr = ds.firstOrNull { it.size >= 4 && abs(it[0] * it[3] - it[1] * it[2]).let { k -> k > 0.01 && k < 100 } }
        val r: RectF
        if (pairs.size >= 2 && pairs[1][0] > 2 && pairs[1][1] > 2) r = RectF(pairs[0][0].toFloat(), pairs[0][1].toFloat(), (pairs[0][0] + pairs[1][0]).toFloat(), (pairs[0][1] + pairs[1][1]).toFloat())
        else if (pairs.size == 1 && tr != null && tr.size == 6 && pairs[0][0] > 2 && pairs[0][1] > 2) r = RectF(0f, 0f, pairs[0][0].toFloat(), pairs[0][1].toFloat())
        else return null
        if (tr != null) { val m = Matrix(); m.setValues(floatArrayOf(tr[0].toFloat(), tr[2].toFloat(), (if (tr.size > 4) tr[4] else 0.0).toFloat(), tr[1].toFloat(), tr[3].toFloat(), (if (tr.size > 5) tr[5] else 0.0).toFloat(), 0f, 0f, 1f)); m.mapRect(r) }
        return r
    }
    /** looks through one page file for the pictures placed on it; a name whose place is not next to it is tried one and two levels up */
    private fun placed(m: List<PBF>, pics: Map<String, File>, depth: Int, out: ArrayList<Pair<String, RectF>>): Pair<String, Int>? {
        var name: String? = null; var up = 0
        for (a in m) if (a.wire == 2 && a.len in 8..96) { val s = PB.str(a).substringAfterLast('/').substringBeforeLast('.'); if (pics.containsKey(s)) { name = s; break } }
        if (name == null && depth > 0) for (a in m) { if (a.wire != 2 || a.len < 10) continue
            val p = placed(PB.sub(a), pics, depth - 1, out); if (p != null && name == null) { name = p.first; up = p.second } }
        if (name == null) return null
        val ds = ArrayList<DoubleArray>(); numbersIn(m, 3, ds); val r = frame(ds)
        if (r != null) { out.add(name to r); return null }
        return if (up < 2) name to up + 1 else null
    }
    private fun placedIn(f: File, pics: Map<String, File>, depth: Int): List<Pair<String, RectF>> {
        val out = ArrayList<Pair<String, RectF>>()
        if (pics.isNotEmpty() && f.exists() && f.length() > 0) try { for (top in PB.parse(f.readBytes())) if (top.wire == 2 && top.len >= 10) placed(PB.sub(top), pics, depth, out) } catch (e: Exception) { }
        return out
    }

    private class Made(val meta: NoteMeta, val path: List<String>)

    /** worker thread */
    fun importFile(f: File, base: String) {
        val tmp = File(act.cacheDir, "fn_" + newId())
        try {
            extract(f, tmp, 0)
            val all = tmp.walkTopDown().filter { it.isFile }.toList()
            val roots = all.filter { it.name == "document.dat" }.map { it.parentFile!! }.filter { File(it, "layout.dat").exists() || File(it, "metadata.dat").exists() }
            if (roots.isEmpty()) throw Exception("No FreeNotes notes were found in this file")
            val fm = HashMap<String, JSONObject>(); val nm = HashMap<String, JSONObject>()
            all.firstOrNull { it.name == "foldertree.frdata" }?.let { ft -> try { val t = JSONObject(ft.readText())
                val fl = t.optJSONArray("folderList") ?: JSONArray(); for (i in 0 until fl.length()) { val o = fl.getJSONObject(i); fm[o.optString("id")] = o }
                val nl = t.optJSONArray("noteList") ?: JSONArray(); for (i in 0 until nl.length()) { val o = nl.getJSONObject(i); nm[o.optString("id")] = o } } catch (e: Exception) { } }
            fun fpath(id: String?, g: Int = 0): List<String> { val o = fm[id ?: ""] ?: return emptyList(); if (id == "unnamed" || g > 12) return emptyList()
                val pid = o.optString("parentId", ""); return (if (pid.isNotEmpty() && pid != "unnamed") fpath(pid, g + 1) else emptyList()) + clean(o.optString("name")).replace(Regex("[/\\\\]"), "-") }
            val existing = HashSet(Lib.notes.mapNotNull { it.fid }); val made = ArrayList<Made>(); var skipped = 0; var done = 0; val snips = ArrayList<Snip>()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fun date(s: String): Long = try { df.parse(s.replace('T', ' '))?.time ?: 0L } catch (e: Exception) { 0L }
            for (root in roots) {
                done++
                val fid = root.name.removeSuffix(".d").removeSuffix(".frnote"); val node = nm[fid]
                if (fid in existing) { skipped++; continue }
                var title = ""; var ct = 0L; var mt = 0L
                try { val m = PB.parse(File(root, "metadata.dat").readBytes()); title = PB.str(PB.get(m, 3) ?: PB.get(m, 4))
                    fun ts(x: Int): Long = (PB.get(PB.sub(PB.get(m, x)), 1)?.v ?: 0L) * 1000L
                    ct = ts(6); mt = ts(8) } catch (e: Exception) { }
                title = clean(node?.optString("name") ?: "").ifEmpty { clean(title) }.ifEmpty { base }
                var pm: List<List<PBF>> = try { PB.sub(PB.get(PB.parse(File(root, "document.dat").readBytes()), 4)).filter { it.num == 1 }.map { PB.sub(it) } } catch (e: Exception) { continue }
                // the page list carries each page's place in the note; follow it when every page has its own
                val ord = pm.map { (PB.get(it, 3)?.v ?: 0L) }; if (ord.toSet().size == pm.size) pm = pm.sortedBy { PB.get(it, 3)?.v ?: 0L }
                val pics = pictures(root); val used = HashSet<String>(); val copied = HashMap<String, String>()
                val lay = HashMap<String, Pair<Float, Float>>()
                try { val lf = File(root, "layout.dat"); if (lf.exists()) for (x in PB.parse(lf.readBytes())) { if (x.num != 3) continue; val d = PB.sub(x); val s = PB.sub(PB.get(d, 1)); val pid = PB.str(PB.get(d, 5))
                    val w = PB.f64(PB.get(s, 1)); val h = PB.f64(PB.get(s, 2)); if (pid.isNotEmpty() && w > 1 && h > 1) lay[pid] = w.toFloat() to h.toFloat() } } catch (e: Exception) { }
                val nid = newId(); val ndir = Store.noteDir(nid); ndir.mkdirs(); val n = Note(nid); val pdfs = HashMap<String, String?>()
                for ((k, q) in pm.withIndex()) {
                    val pid = PB.str(PB.get(q, 1)); val bg = PB.sub(PB.get(q, 4)); val pdfId = PB.str(PB.get(bg, 2)); val inkId = PB.str(PB.get(q, 6))
                    // which page of the PDF is behind this page (saved with the PDF; a missing number means the first page)
                    val idx = if (pdfId.isNotEmpty()) (PB.get(bg, 3)?.v ?: 0L).toInt() else (PB.get(q, 3)?.v ?: 0L).toInt()
                    val pg = Page(newId()); pg.paper = "blank"; pg.paperColor = -1
                    if (pdfId.isNotEmpty()) {
                        val src = pdfs.getOrPut(pdfId) { val sf = File(root, "PDFs/$pdfId.pdf"); if (!sf.exists()) null else { val name = "src_" + newId() + ".pdf"; val dst = File(ndir, name); sf.copyTo(dst, true); if (Pdf.count(dst) > 0) name else { dst.delete(); null } } }
                        if (src != null && idx < Pdf.count(File(ndir, src))) { pg.src = src; pg.srcPage = idx }
                    }
                    val l = lay[pid]; val ps = pg.src?.let { Pdf.size(File(ndir, it), pg.srcPage) }
                    pg.w = l?.first ?: ps?.first ?: 612f; pg.h = l?.second ?: ps?.second ?: 792f
                    val items = ArrayList<Item>()
                    // pictures placed on the page go under the handwriting, the way FreeNotes shows them
                    if (pics.isNotEmpty()) {
                        val where = ArrayList<Pair<String, RectF>>()
                        if (inkId.isNotEmpty()) where.addAll(placedIn(File(root, "FNPages/$inkId.dat"), pics, 3))
                        for (a in q) if (a.wire == 2 && a.len in 8..64) { val of = File(root, "FNPagesObject/" + PB.str(a) + ".dat"); if (of.exists()) where.addAll(placedIn(of, pics, 6)) }
                        for ((name, r) in where) {
                            if (r.width() < 4 || r.height() < 4 || r.width() > pg.w * 3 || r.height() > pg.h * 3 || !RectF.intersects(r, RectF(0f, 0f, pg.w, pg.h))) continue
                            val src = pics[name] ?: continue
                            val file = copied.getOrPut(name) { val fn = "img_" + newId() + "." + src.extension.ifEmpty { "png" }; src.copyTo(File(ndir, fn), true); fn }
                            items.add(ImageItem(file, r)); used.add(name)
                        }
                    }
                    if (inkId.isNotEmpty()) { val inf = File(root, "FNPages/$inkId.dat"); if (inf.exists()) try { items.addAll(ink(inf.readBytes())) } catch (e: Exception) { } }
                    if (items.isNotEmpty()) try { InkIO.writeFile(Store.inkFile(nid, pg.id), items) } catch (e: Exception) { }
                    n.pages.add(pg)
                    if (k % 8 == 0) toast("Importing $done/${roots.size} “${title.take(24)}” — page ${k + 1}/${pm.size}")
                }
                if (n.pages.isEmpty()) { ndir.deleteRecursively(); continue }
                Store.atomic(File(ndir, "note.json"), n.json().toString())
                try { val p0 = n.pages[0]; Store.loadPage(n, p0); Thumbs.make(n, p0, p0.items) } catch (e: Throwable) { }
                val m = NoteMeta(nid); m.title = title; m.pages = n.pages.size; m.first = n.pages[0].id; m.fid = fid
                val created = node?.optString("createDate", "")?.let { date(it) } ?: 0L
                m.modified = if (mt > 0) mt else if (ct > 0) ct else if (created > 0) created else System.currentTimeMillis(); m.created = if (ct > 0) ct else m.modified
                val dd = node?.optString("deleteDate", "")?.let { date(it) } ?: 0L
                if (P.b("imp.fnTrash") && dd > 86400000L * 800) m.trashed = dd
                made.add(Made(m, if (node != null) fpath(node.optString("parentId", "")) else emptyList()))
                // a picture the backup keeps without saying where it was: it is not lost, it waits in the Snippet Shelf
                if (P.s("imp.fnPics") == "shelf") for ((name, src) in pics) if (name !in used) try { Shelf.pictureSnip(src, "fn" + name.filter { it.isLetterOrDigit() }.take(40), title.take(40))?.let { snips.add(it) } } catch (e: Throwable) { }
                for (src in pdfs.values) if (src != null) try { PdfText.extract(File(ndir, src), File(ndir, "$src.txt")) } catch (e: Throwable) { }
            }
            val sk = skipped
            Store.main {
                for (x in made) {
                    var parent: String? = null
                    for (name in x.path) { if (name.isEmpty()) continue; var fo = Lib.folders.firstOrNull { it.parent == parent && it.name == name }; if (fo == null) { fo = Folder(newId()); fo.name = name; fo.parent = parent; Lib.folders.add(fo) }; parent = fo.id }
                    x.meta.folder = parent; Lib.notes.add(x.meta)
                }
                Thumbs.bump(); Lib.changed()
                if (snips.isNotEmpty()) { Shelf.list.addAll(snips); Shelf.rev++; act.onShelfChanged() }
                toast("Imported ${made.size} note" + (if (made.size == 1) "" else "s") + (if (sk > 0) " · $sk already imported" else "") +
                    (if (snips.isNotEmpty()) " · ${snips.size} picture" + (if (snips.size == 1) "" else "s") + " kept in the Snippet Shelf" else ""))
            }
        } finally { tmp.deleteRecursively() }
    }
}

/* ================= backup of the original (web) Larpful ================= */
/** Reads a ".larpful" backup written by the original Larpful and adds its notebooks to this library.
 *  The file is one JSON document (old style) or a header line followed by one ["key", value] line per record.
 *  It is read as a stream, so a multi-gigabyte backup never has to fit in memory. */
object Larpful1 {
    /** the web app measured pages in CSS pixels (A4 = 794 wide); this app uses points (A4 = 595 wide) */
    private const val K = 595f / 794f

    fun isBackup(f: File): Boolean = try {
        val h = ByteArray(96); val n = f.inputStream().use { it.read(h) }
        String(h, 0, max(0, n), Charsets.UTF_8).replace(" ", "").startsWith("{\"app\":\"larpful\"")
    } catch (e: Exception) { false }

    private class N(val oldId: String) {
        val id = newId(); val note = Note(id); val imgs = HashMap<Int, String>(); val pageIdx = ArrayList<Int>()
        var meta: NoteMeta? = null; var path: List<String> = emptyList(); var links = false
        val dir: File get() { val d = Store.noteDir(id); d.mkdirs(); return d }
    }

    private fun any(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> { val o = JSONObject(); r.beginObject(); while (r.hasNext()) { val k = r.nextName(); o.put(k, any(r) ?: JSONObject.NULL) }; r.endObject(); o }
        JsonToken.BEGIN_ARRAY -> { val a = JSONArray(); r.beginArray(); while (r.hasNext()) a.put(any(r) ?: JSONObject.NULL); r.endArray(); a }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextDouble()
        JsonToken.BOOLEAN -> r.nextBoolean()
        else -> { r.skipValue(); null }
    }
    private fun num(r: JsonReader): Double = when (r.peek()) {
        JsonToken.NUMBER -> r.nextDouble()
        JsonToken.BOOLEAN -> if (r.nextBoolean()) 1.0 else 0.0
        JsonToken.STRING -> r.nextString().toDoubleOrNull() ?: 0.0
        else -> { r.skipValue(); 0.0 }
    }
    private fun str(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); null }
    }
    private fun xy(r: JsonReader): FloatArray {
        val q = floatArrayOf(0f, 0f)
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return q }
        r.beginObject()
        while (r.hasNext()) { val k = r.nextName(); if (k == "x") q[0] = num(r).toFloat() else if (k == "y") q[1] = num(r).toFloat() else r.skipValue() }
        r.endObject(); return q
    }
    private fun color(s: String?, alpha: Double): Int {
        var rgb = 0x111111; var a = alpha
        try {
            val t = (s ?: "").trim()
            if (t.startsWith("#")) {
                val h = t.substring(1)
                if (h.length == 3) rgb = h.map { "$it$it" }.joinToString("").toInt(16)
                else if (h.length == 6) rgb = h.toInt(16)
                else if (h.length == 8) { rgb = h.substring(0, 6).toInt(16); a *= h.substring(6).toInt(16) / 255.0 }
            } else if (t.startsWith("rgb")) {
                val p = t.substringAfter("(").substringBefore(")").split(",").map { it.trim().toDouble() }
                rgb = (p[0].toInt() shl 16) or (p[1].toInt() shl 8) or p[2].toInt(); if (p.size > 3) a *= p[3]
            }
        } catch (e: Exception) { }
        return ((a.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
    }
    /** writes a data: URL to a file in [dir]; returns the file name */
    private fun saveDataUrl(u: String, dir: File, stem: String): String? {
        val c = u.indexOf(','); if (!u.startsWith("data:") || c < 0) return null
        val head = u.substring(5, c); val mime = head.substringBefore(';').lowercase()
        val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else if (mime.contains("jpeg") || mime.contains("jpg")) "jpg"
            else if (mime.contains("webm")) "webm" else if (mime.contains("ogg")) "ogg" else if (mime.contains("wav")) "wav"
            else if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) "m4a" else if (mime.startsWith("audio")) "webm" else "jpg"
        val bytes = if (head.contains("base64")) Base64.decode(u.substring(c + 1), Base64.DEFAULT) else java.net.URLDecoder.decode(u.substring(c + 1), "UTF-8").toByteArray(Charsets.ISO_8859_1)
        val name = "$stem.$ext"; File(dir, name).writeBytes(bytes); return name
    }

    private fun stroke(r: JsonReader, n: N, items: ArrayList<Item>, press: Boolean) {
        var k = "pen"; var c: String? = null; var w = 2.0; var o = Double.NaN; var bp = false; var raw = false
        var pts = FloatArray(0); var np = 0
        var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f; var hasB = false
        var src: String? = null; var t: String? = null; var s = 28.0; var fw = 600.0; var label = ""; var toNb: String? = null; var toPg = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "k" -> k = str(r) ?: "pen"
                "c" -> c = str(r)
                "w" -> w = num(r)
                "o" -> o = num(r)
                "bp" -> bp = num(r) != 0.0
                "raw" -> raw = num(r) != 0.0
                "a" -> { val q = xy(r); ax = q[0]; ay = q[1] }
                "b" -> { val q = xy(r); bx = q[0]; by = q[1]; hasB = true }
                "src" -> src = str(r)
                "t" -> t = str(r)
                "s" -> s = num(r)
                "fw" -> fw = num(r)
                "label" -> label = str(r) ?: ""
                "to" -> {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) { val tk = r.nextName(); if (tk == "nb") toNb = str(r) else if (tk == "pg") toPg = num(r).toInt() else r.skipValue() }
                        r.endObject()
                    } else r.skipValue()
                }
                "pts" -> {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        while (r.hasNext()) {
                            if (np + 3 > pts.size) pts = pts.copyOf(max(96, pts.size * 2))
                            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                var x = 0f; var y = 0f; var p = Float.NaN
                                r.beginObject()
                                while (r.hasNext()) {
                                    val pk = r.nextName()
                                    if (pk == "x") x = num(r).toFloat() else if (pk == "y") y = num(r).toFloat()
                                    else if (pk == "p") { if (r.peek() == JsonToken.NUMBER) p = r.nextDouble().toFloat() else r.skipValue() }
                                    else r.skipValue()
                                }
                                r.endObject()
                                np -= np % 3; pts[np] = x; pts[np + 1] = y; pts[np + 2] = p; np += 3
                            } else { pts[np] = num(r).toFloat(); np++ }
                        }
                        r.endArray()
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        np -= np % 3
        val op = if (o.isNaN() || o <= 0.0) 1.0 else o
        if (k == "pen") {
            if (np < 3) return
            val out = FloatArray(np); var i = 0
            while (i < np) {
                out[i] = pts[i] * K; out[i + 1] = pts[i + 1] * K
                val p = if (pts[i + 2].isNaN()) 0.5f else pts[i + 2]
                // same widths as the original: ink pen w*(0.3+1.4p), ballpoint w*(0.86+0.28p)
                out[i + 2] = if (np == 3 || !press || raw) 1f else if (bp) 0.86f + 0.28f * p else 0.3f + 1.4f * p
                i += 3
            }
            items.add(Stroke(if (bp) K_BALL else K_PEN, color(c, op), (w * K).toFloat(), out, 0L))
        } else if (k == "hl") {
            if (np < 3) return
            val out = FloatArray(np); var i = 0
            while (i < np) { out[i] = pts[i] * K; out[i + 1] = pts[i + 1] * K; out[i + 2] = 1f; i += 3 }
            items.add(Stroke(K_HL, color(c, if (o.isNaN() || o <= 0.0) 0.35 else o), (w * 3.0 * K).toFloat(), out, 0L))
        } else if (k == "line" || k == "rect" || k == "ell") {
            if (!hasB) return
            val x0 = ax * K; val y0 = ay * K; val x1 = bx * K; val y1 = by * K
            val out: FloatArray
            if (k == "line") out = floatArrayOf(x0, y0, 1f, x1, y1, 1f)
            else if (k == "rect") out = floatArrayOf(x0, y0, 1f, x1, y0, 1f, x1, y1, 1f, x0, y1, 1f, x0, y0, 1f)
            else {
                val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f; val rx = max(abs(x1 - x0) / 2f, 1f); val ry = max(abs(y1 - y0) / 2f, 1f); val m = 72
                out = FloatArray((m + 1) * 3)
                for (j in 0..m) { val th = j * 2.0 * Math.PI / m; out[j * 3] = cx + rx * cos(th).toFloat(); out[j * 3 + 1] = cy + ry * sin(th).toFloat(); out[j * 3 + 2] = 1f }
            }
            items.add(Stroke(K_SHAPE, color(c, op), (w * K).toFloat(), out, 0L))
        } else if (k == "img") {
            val u = src ?: return
            if (!hasB) return
            val name = saveDataUrl(u, n.dir, "img_" + newId()) ?: return
            items.add(ImageItem(name, RectF(min(ax, bx) * K, min(ay, by) * K, max(ax, bx) * K, max(ay, by) * K)))
        } else if (k == "txt") {
            val tx = t ?: return
            if (tx.isEmpty()) return
            // the box is exactly as wide as the longest line, so Arabic (right-to-left) lines stay where they were written
            val size = (s * K).toFloat(); val bold = fw >= 600.0
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.textSize = size; tp.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            var tw = 12f; for (ln in tx.split('\n')) tw = max(tw, tp.measureText(ln))
            items.add(TextItem(tx, ax * K, ay * K, tw + 6f, size, color(c, 1.0), bold))
        } else if (k == "lnk") {
            val nb = toNb ?: return
            if (!hasB) return
            items.add(LinkItem(RectF(min(ax, bx) * K, min(ay, by) * K, max(ax, bx) * K, max(ay, by) * K), "lf1:$nb", "#$toPg", label)); n.links = true
        }
    }

    private fun page(r: JsonReader, n: N, index: Int, press: Boolean) {
        val pg = Page(newId()); var w = 794.0; var h = 1123.0; var tpl = "blank"; var pcol: String? = null; val items = ArrayList<Item>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "label" -> pg.label = str(r)?.trim()?.ifEmpty { null }
                "w" -> w = num(r)
                "h" -> h = num(r)
                "tpl" -> tpl = str(r) ?: "blank"
                "col" -> pcol = str(r)
                "strokes" -> {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        while (r.hasNext()) { if (r.peek() == JsonToken.BEGIN_OBJECT) stroke(r, n, items, press) else r.skipValue() }
                        r.endArray()
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        pg.w = (if (w > 1.0) w else 794.0).toFloat() * K; pg.h = (if (h > 1.0) h else 1123.0).toFloat() * K
        pg.paper = when (tpl) { "ruled", "lined" -> "lined"; "isometric" -> "iso"; "grid", "graph", "dots", "cornell", "music", "dark", "black" -> tpl; else -> "blank" }
        val pc = pcol
        pg.paperColor = if (tpl == "dark") 0xFF1C1C1E.toInt() else if (tpl == "black") 0xFF000000.toInt() else if (pc != null && pc.startsWith("#")) color(pc, 1.0) else -1
        if (items.isNotEmpty()) InkIO.writeFile(Store.inkFile(n.id, pg.id), items)
        n.note.pages.add(pg); n.pageIdx.add(index)
    }

    private fun notebook(r: JsonReader, n: N, press: Boolean) {
        val m = NoteMeta(n.id); var tag = ""; var t = 0.0; var trash = 0.0
        n.dir
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "title" -> m.title = (str(r) ?: "").ifEmpty { "Untitled" }
                "tag" -> tag = str(r) ?: ""
                "pinned" -> m.pinned = num(r) != 0.0
                "trash" -> trash = num(r)
                "t" -> t = num(r)
                "cover" -> {
                    val cv = any(r); val u = if (cv is JSONObject) cv.optString("u", "") else if (cv is String) cv else ""
                    if (u.startsWith("data:") && u.indexOf(',') > 0) try { Store.coverFile(n.id).writeBytes(Base64.decode(u.substring(u.indexOf(',') + 1), Base64.DEFAULT)); m.cover = true } catch (e: Exception) { }
                }
                "pages" -> {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray(); var i = 0
                        while (r.hasNext()) { if (r.peek() == JsonToken.BEGIN_OBJECT) page(r, n, i, press) else r.skipValue(); i++ }
                        r.endArray()
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        m.modified = if (t > 0.0) t.toLong() else System.currentTimeMillis(); m.created = m.modified
        if (trash != 0.0) m.trashed = if (trash > 1e11) trash.toLong() else System.currentTimeMillis()
        m.fid = "lf1:" + n.oldId
        n.path = tag.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        n.meta = m
    }

    private fun audio(r: JsonReader, n: N) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var t = 0.0; var dur = 0.0; var u: String? = null
            r.beginObject()
            while (r.hasNext()) { val k = r.nextName(); if (k == "t") t = num(r) else if (k == "dur") dur = num(r) else if (k == "u") u = str(r) else r.skipValue() }
            r.endObject()
            val uu = u ?: continue
            var name: String? = null
            try { name = saveDataUrl(uu, n.dir, "rec_" + newId()) } catch (e: Exception) { }
            val nm = name ?: continue
            n.note.recs.add(Rec(nm, t.toLong(), dur.toLong()))
        }
        r.endArray()
    }

    /** worker thread */
    fun importFile(f: File) {
        val notes = LinkedHashMap<String, N>(); var press = true; var isLarpful = false; var skipped = 0; var broken = false
        val existing = HashSet(Lib.notes.mapNotNull { it.fid })
        fun slot(id: String): N = notes.getOrPut(id) { N(id) }
        fun entry(k: String, r: JsonReader) {
            val id = k.substringAfter(':', "").substringBefore(':')
            val have = ("lf1:$id") in existing
            if (k.startsWith("nb:")) {
                if (have) { skipped++; r.skipValue() } else { val x = slot(id); notebook(r, x, press); toast("Restoring “" + (x.meta?.title ?: "").take(28) + "”…") }
            } else if (k.startsWith("im:")) {
                val i = k.substringAfterLast(':').toIntOrNull()
                if (have || i == null) r.skipValue() else { val u = str(r); if (u != null) { val x = slot(id); val name = try { saveDataUrl(u, x.dir, "bg_$i") } catch (e: Exception) { null }; if (name != null) x.imgs[i] = name } }
            } else if (k.startsWith("au:")) {
                if (have) r.skipValue() else audio(r, slot(id))
            } else r.skipValue()
        }
        fun record(r: JsonReader) { r.beginArray(); val k = str(r) ?: ""; if (r.hasNext()) entry(k, r); while (r.hasNext()) r.skipValue(); r.endArray() }
        try {
            JsonReader(java.io.InputStreamReader(java.io.BufferedInputStream(f.inputStream(), 1 shl 16), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name == "app") isLarpful = str(r) == "larpful"
                    else if (name == "st") { val st = any(r); if (st is JSONObject && st.has("pressure")) { val pv = st.opt("pressure"); press = !(pv == false || pv == 0.0) } }
                    else if (name == "kv" && r.peek() == JsonToken.BEGIN_ARRAY) { r.beginArray(); while (r.hasNext()) { if (r.peek() == JsonToken.BEGIN_ARRAY) record(r) else r.skipValue() }; r.endArray() }
                    else r.skipValue()
                }
                r.endObject()
                if (!isLarpful) throw Exception("This is not a Larpful backup")
                while (r.peek() != JsonToken.END_DOCUMENT) { if (r.peek() == JsonToken.BEGIN_ARRAY) record(r) else r.skipValue() }
            }
        } catch (e: Throwable) {
            if (!isLarpful) { for (x in notes.values) Store.noteDir(x.id).deleteRecursively(); throw Exception("This is not a Larpful backup") }
            broken = true   // a cut-off file: keep everything that was read completely
        }
        val made = ArrayList<N>()
        for (x in notes.values) {
            val m = x.meta
            if (m == null || x.note.pages.isEmpty()) { Store.noteDir(x.id).deleteRecursively(); continue }
            for ((j, pg) in x.note.pages.withIndex()) { val name = x.imgs[x.pageIdx[j]]; if (name != null) pg.bg = name }
            Store.atomic(File(x.dir, "note.json"), x.note.json().toString())
            m.pages = x.note.pages.size; m.first = x.note.pages[0].id
            made.add(x)
        }
        // links between notebooks: point them at the new ids
        val byOld = HashMap<String, N>(); for (x in made) byOld["lf1:" + x.oldId] = x
        for (x in made) {
            if (!x.links) continue
            for (pg in x.note.pages) {
                val fl = Store.inkFile(x.id, pg.id); if (!fl.exists()) continue
                val items = InkIO.readFile(fl); var changed = false
                for (i in items.indices) {
                    val li = items[i]
                    if (li is LinkItem && li.noteId.startsWith("lf1:")) {
                        val tgt = byOld[li.noteId] ?: continue
                        val pi = (li.pageId.removePrefix("#").toIntOrNull() ?: 0).coerceIn(0, tgt.note.pages.size - 1)
                        items[i] = LinkItem(li.rect, tgt.id, tgt.note.pages[pi].id, li.label); changed = true
                    }
                }
                if (changed) InkIO.writeFile(fl, items)
            }
        }
        for (x in made) try { val p0 = x.note.pages[0]; Store.loadPage(x.note, p0); Thumbs.make(x.note, p0, p0.items) } catch (e: Throwable) { }
        val sk = skipped; val cut = broken
        Store.main {
            for (x in made) {
                val m = x.meta ?: continue
                var parent: String? = null
                for (name in x.path) { var fo = Lib.folders.firstOrNull { it.parent == parent && it.name == name }; if (fo == null) { fo = Folder(newId()); fo.name = name; fo.parent = parent; Lib.folders.add(fo) }; parent = fo.id }
                m.folder = parent; Lib.notes.add(m)
            }
            Thumbs.bump(); Lib.changed()
            toast("Restored ${made.size} notebook" + (if (made.size == 1) "" else "s") + " from Larpful" + (if (sk > 0) " · $sk already here" else "") + (if (cut) " · the file ends early, the rest could not be read" else ""))
        }
    }
}

/* ================= full backup in one file ================= */
object BackupUi {
    private fun used(): Long = Store.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun center() {
        val c = act; val body = vbox(c); body.setPadding(dp(14), dp(6), dp(14), dp(4))
        val info = txt(c, "", 14.5f, Th.sub); info.setLineSpacing(dpf(4), 1f); info.setPadding(dp(8), dp(4), dp(8), dp(12)); body.addView(info)
        fun refresh() { val d = if (Lib.lastBackup == 0L) "never" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(Lib.lastBackup)); info.text = "Last backup: $d\n${Lib.notes.size} notes · calculating size…"
            Store.work { val u = used(); Store.main { info.text = "Last backup: $d\n${Lib.notes.size} notes · ${u / 1048576} MB on this tablet" } } }
        refresh()
        fun btn(icon: String, label: String, sub: String, danger: Boolean = false, on: () -> Unit) {
            val r = hbox(c); r.background = rr(Th.card, dpf(12)); r.setPadding(dp(14), dp(12), dp(12), dp(12)); r.fx(12f)
            r.addView(android.widget.ImageView(c).apply { setImageDrawable(Icon(icon, if (danger) Th.danger else Th.accent)) }, lp(dp(24), dp(24)))
            val v = vbox(c); v.addView(txt(c, label, 15.5f, if (danger) Th.danger else Th.text, true)); v.addView(txt(c, sub, 12.5f, Th.sub).apply { setPadding(0, dp(3), 0, 0) })
            r.addView(v, lp(0, WC, 1f).apply { leftMargin = dp(12) }); r.setOnClickListener { on() }
            body.addView(r, lp(MP, WC).apply { bottomMargin = dp(8) })
        }
        val name = "Larpful2-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".larpful2"
        btn("save", "Create backup file", "Everything in one file: notes, PDFs, audio, history, shelf, settings") {
            act.createDoc(name, "application/octet-stream") { uri -> toast("Backing up…"); act.ed.flush()
                Store.io { try { Lib.saveNow(); act.contentResolver.openOutputStream(uri)!!.use { write(it) }; Store.main { Lib.lastBackup = System.currentTimeMillis(); Lib.changed(); refresh(); toast("Backup saved") } } catch (e: Throwable) { toast("Backup failed: " + e.message) } } } }
        btn("share", "Share backup", "Send it to Drive, a computer or another app") {
            toast("Backing up…"); act.ed.flush()
            Store.io { try { Lib.saveNow(); val f = File(ShareProvider.dir(act), name); f.outputStream().use { write(it) }; Store.main { Lib.lastBackup = System.currentTimeMillis(); Lib.changed(); refresh(); act.share(f, "application/octet-stream") } } catch (e: Throwable) { toast("Backup failed: " + e.message) } } }
        btn("noteplus", "Restore — add missing notes", "Merges a backup into this library. Nothing you have is changed") { pickRestore(false) }
        btn("history", "Restore — replace everything", "Wipes this library and loads the backup instead", true) { pickRestore(true) }
        body.addView(SettingsUi.panel(c, Reg.backup))
        sheet("Backup & restore", body, 500)
    }
    fun write(out: OutputStream) {
        ZipOutputStream(out.buffered(1 shl 16)).use { z ->
            z.setLevel(1)
            z.putNextEntry(java.util.zip.ZipEntry("larpful2.json")); z.write(JSONObject().put("app", "larpful2").put("v", 1).put("date", System.currentTimeMillis()).toString().toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("prefs.json")); z.write(P.exportJson().toString().toByteArray()); z.closeEntry()
            Store.zipDir(z, Store.root, "data/") { it.name.endsWith(".tmp") }
        }
    }
    private fun pickRestore(replace: Boolean) {
        act.pick(arrayOf("*/*"), false) { uris -> toast("Reading backup…"); Store.work { try { val f = Io.toCache(uris[0]); Store.main { confirmRestore(f, replace) } } catch (e: Throwable) { toast("Could not read the file") } } }
    }
    fun restorePrompt(f: File) { choose("Restore this Larpful backup?", listOf("Add missing notes (merge)", "Replace everything", "Cancel")) { k -> if (k == 0) restore(f, false) else if (k == 1) confirmRestore(f, true) else f.delete() } }
    private fun confirmRestore(f: File, replace: Boolean) {
        // a backup made by the original (web) Larpful: its notes are added to this library, nothing here is replaced
        if (Larpful1.isBackup(f)) { toast("Reading Larpful backup…"); Store.work { try { Larpful1.importFile(f) } finally { f.delete() } }; return }
        if (replace) confirm("Replace everything?", "All notes on this tablet are removed and replaced by the backup.", "Replace") { restore(f, true) } else restore(f, false)
    }
    fun restore(f: File, replace: Boolean) {
        toast("Restoring…"); act.ed.flush()
        if (replace) for (p in ArrayList(act.ed.panes)) act.ed.closePane(p)
        Store.io {
            val tmp = File(act.cacheDir, "rs_" + newId())
            try {
                ZipInputStream(f.inputStream().buffered(1 shl 16)).use { Store.unzip(it, tmp) }
                if (!File(tmp, "larpful2.json").exists() || !File(tmp, "data/library.json").exists()) throw Exception("This is not a Larpful backup")
                val data = File(tmp, "data"); val prefs = try { JSONObject(File(tmp, "prefs.json").readText()) } catch (e: Exception) { null }
                if (replace) {
                    Pdf.closeAll(); Store.root.deleteRecursively(); if (!data.renameTo(Store.root)) { data.copyRecursively(Store.root, true) }
                    File(Store.root, "notes").mkdirs(); File(Store.root, "shelf").mkdirs(); File(Store.root, "covers").mkdirs()
                    Store.main { Lib.load(); Shelf.load(); LinkIndex.reset(); if (prefs != null && P.b("bk.settings")) P.importJson(prefs); Thumbs.bump(); Lib.changed(); act.lib.refresh(); toast("Backup restored") }
                } else {
                    val o = JSONObject(File(data, "library.json").readText()); val na = o.optJSONArray("notes") ?: JSONArray(); val fa = o.optJSONArray("folders") ?: JSONArray()
                    val have = HashSet(Lib.notes.map { it.id }); val newNotes = ArrayList<NoteMeta>(); val folders = ArrayList<Folder>()
                    for (i in 0 until fa.length()) folders.add(Folder.from(fa.getJSONObject(i)))
                    for (i in 0 until na.length()) { val m = NoteMeta.from(na.getJSONObject(i)); if (m.id in have) continue
                        val src = File(data, "notes/" + m.id); if (!src.exists()) continue; val dst = Store.noteDir(m.id); if (!src.renameTo(dst)) src.copyRecursively(dst, true); newNotes.add(m) }
                    for (x in File(data, "covers").listFiles() ?: arrayOf()) { val d = File(Store.root, "covers/" + x.name); if (!d.exists()) x.copyTo(d) }
                    for (x in File(data, "shelf").listFiles() ?: arrayOf()) { val d = File(Store.root, "shelf/" + x.name); if (!d.exists()) x.copyTo(d) }
                    Store.main {
                        val haveF = HashSet(Lib.folders.map { it.id }); for (fo in folders) if (fo.id !in haveF) Lib.folders.add(fo)
                        Lib.notes.addAll(newNotes); Shelf.load(); Thumbs.bump(); Lib.changed(); toast("Added ${newNotes.size} note" + (if (newNotes.size == 1) "" else "s"))
                    }
                }
            } catch (e: Throwable) { toast("Restore failed: " + (e.message ?: e.javaClass.simpleName)) } finally { tmp.deleteRecursively(); f.delete() }
        }
    }
}

/* ================= automatic backup into a folder the user picked (Android's own folder picker, no storage permission) ================= */
object AutoBackup {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val PREFIX = "Larpful2-auto-"
    fun folderName(): String { val u = P.s("bk.tree"); if (u.isEmpty()) return "Not chosen"
        return try { android.net.Uri.decode(android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(u))).substringAfterLast(':').ifEmpty { "Internal storage" } } catch (e: Exception) { "Chosen" } }
    fun chooseFolder(then: (() -> Unit)? = null) { act.pickTree { uri -> P.set("bk.tree", uri.toString()); toast("Backups go to: " + folderName()); then?.invoke() } }
    private fun due(): Boolean {
        if (!P.b("bk.auto") || P.s("bk.tree").isEmpty() || Lib.notes.none { it.trashed == 0L }) return false
        val every = (P.s("bk.every").toFloatOrNull() ?: 1f) * 86400000L
        return System.currentTimeMillis() - P.l("bk.autoLast") >= every - 3600000L   // an hour of slack, so "daily" does not drift later each day
    }
    /** called when the app goes to the background: the zip is written on a low-priority thread, after the notes are saved */
    fun maybe() { if (due()) run(false) }
    fun run(manual: Boolean) {
        val tree = P.s("bk.tree"); if (tree.isEmpty()) { if (manual) chooseFolder { run(true) }; return }
        if (!busy.compareAndSet(false, true)) { if (manual) toast("A backup is already running"); return }
        if (manual) toast("Backing up…")
        Store.io {
            try { Lib.saveNow() } catch (e: Throwable) { }
            Store.work {
                try {
                    write(Uri.parse(tree))
                    Store.main { val now = System.currentTimeMillis(); P.set("bk.autoLast", now, false); Lib.lastBackup = now; Lib.changed(false); if (manual) toast("Backup saved to " + folderName()) }
                } catch (e: Throwable) {
                    Store.main { if (manual || P.b("bk.autoWarn")) toast("Automatic backup failed: " + (e.message ?: e.javaClass.simpleName) + ". Check the backup folder in Settings.") }
                } finally { busy.set(false) }
            }
        }
    }
    private fun write(tree: Uri) {
        val cr = act.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", "$name.part") ?: throw Exception("the folder cannot be written")
        try { cr.openOutputStream(doc)!!.use { BackupUi.write(it) } } catch (e: Throwable) { try { DocumentsContract.deleteDocument(cr, doc) } catch (x: Throwable) { }; throw e }
        try { DocumentsContract.renameDocument(cr, doc, "$name.larpful2") } catch (e: Throwable) { }
        prune(tree)
    }
    /** keeps the newest N automatic backups; files the user made by hand are never touched */
    private fun prune(tree: Uri) {
        val cr = act.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); val found = ArrayList<Pair<String, String>>()
        cr.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val id = c.getString(0); val nm = c.getString(1) ?: continue; if (nm.startsWith(PREFIX)) found.add(id to nm) } }
        val keep = P.i("bk.keep").coerceAtLeast(1)
        // leftovers of an interrupted backup go too
        for ((id, nm) in found) if (nm.endsWith(".part") && !nm.contains(SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()))) try { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (e: Throwable) { }
        val done = found.filter { it.second.endsWith(".larpful2") }.sortedByDescending { it.second }
        for ((id, _) in done.drop(keep)) try { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (e: Throwable) { }
    }
}
