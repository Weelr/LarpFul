package com.weelr.larpful2

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/** slide-in side panel with a title bar */
fun sidePanel(title: String, widthDp: Int, right: Boolean, modal: Boolean, gear: (() -> Unit)?, body: View, onDismiss: (() -> Unit)? = null): Layer {
    val c = act; val box = vbox(c); box.setBackgroundColor(Th.bg); box.elevation = dpf(16)
    val bar = hbox(c); bar.setPadding(dp(16), dp(4), dp(6), 0)
    bar.addView(txt(c, title, 19f, Th.text, true), lp(0, WC, 1f))
    lateinit var l: Layer
    if (gear != null) bar.addView(ibtn(c, "gear", 40, Th.accent) { gear() })
    bar.addView(ibtn(c, "close", 40, Th.sub) { l.dismiss() })
    box.addView(bar, lp(MP, dp(50))); box.addView(body, lp(MP, 0, 1f))
    l = Layer(box, modal, false, onDismiss)
    l.show(flp(minOf(dp(widthDp), act.root.width - dp(40)), MP, if (right) Gravity.END else Gravity.START), if (right) 2 else 1)
    return l
}

/* ================= pages panel ================= */
object PagesUi {
    private var layer: Layer? = null
    fun open(ed: Editor) {
        layer?.dismiss()
        val p = ed.focused ?: return; val n = p.note ?: return
        ed.doSave()
        val grid = GridView(act); grid.numColumns = P.i("pg.cols").coerceIn(1, 3); grid.verticalSpacing = dp(10); grid.horizontalSpacing = dp(10); grid.setPadding(dp(12), dp(6), dp(12), dp(12)); grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        val ad = object : BaseAdapter() {
            override fun getCount() = n.pages.size
            override fun getItem(i: Int) = n.pages[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val pg = n.pages[i]; val box = (cv as? LinearLayout) ?: vbox(act).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }, lp(MP, WC)); addView(txt(act, "", 12.5f, Th.sub).apply { setPadding(0, dp(4), 0, 0); gravity = Gravity.CENTER }, lp(MP, WC)) }
                val iv = box.getChildAt(0) as ImageView; val tv = box.getChildAt(1) as TextView
                val cur = i == p.view.curPage()
                iv.background = rr(if (pg.src != null) -1 else pg.paperColor, dpf(6), if (cur) Th.accent else Th.sep, if (cur) dp(2.5f) else 1); iv.setPadding(dp(3), dp(3), dp(3), dp(3))
                iv.minimumHeight = dp(90); iv.setImageDrawable(null); val key = pg.id + "/" + Thumbs.gen; iv.tag = key
                Thumbs.load(n.id, pg.id) { b -> if (iv.tag == key && b != null) iv.setImageBitmap(b) }
                tv.text = if (P.b("pg.numbers")) (i + 1).toString() else ""; tv.setTextColor(if (cur) Th.accent else Th.sub)
                box.fx(8f)
                box.setOnClickListener { p.view.scrollToPage(i); notifyDataSetChanged() }
                box.setOnLongClickListener { v -> menu(ed, p, n, i, v) { notifyDataSetChanged() }; true }
                return box
            }
        }
        grid.adapter = ad
        val body = vbox(act); body.addView(grid, lp(MP, 0, 1f))
        val foot = hbox(act); foot.setPadding(dp(8), 0, dp(8), dp(6))
        fun fb(icon: String, label: String, on: () -> Unit) { val b = hbox(act); b.gravity = Gravity.CENTER; b.fx(); b.addView(ImageView(act).apply { setImageDrawable(Icon(icon, Th.accent)) }, lp(dp(20), dp(20))); b.addView(txt(act, label, 14.5f, Th.accent).apply { setPadding(dp(6), 0, 0, 0) }); b.setOnClickListener { on() }; foot.addView(b, lp(0, dp(44), 1f)) }
        fb("noteplus", "Add page") { ed.addPage(p, p.view.curPage()); ad.notifyDataSetChanged() }
        fb("share", "Add file") { act.pickFiles(n.id) }
        body.addView(foot)
        val listener: () -> Unit = { ad.notifyDataSetChanged() }
        Lib.listeners.add(listener)
        layer = sidePanel("Pages", 150 + 110 * P.i("pg.cols").coerceIn(1, 3), false, true, { SettingsUi.sub("Pages panel", Reg.pages) { grid.numColumns = P.i("pg.cols").coerceIn(1, 3); ad.notifyDataSetChanged() } }, body) { Lib.listeners.remove(listener); layer = null }
        grid.post { grid.setSelection(p.view.curPage()) }
    }
    fun menu(ed: Editor, p: Pane, n: Note, i: Int, v: View, changed: () -> Unit) {
        showMenu(v, listOf(
            MI("noteplus", "Add page after") { ed.addPage(p, i); changed() },
            MI("dup", "Duplicate") { val src = n.pages[i]; if (src.loaded) { val c = Page(newId()); c.w = src.w; c.h = src.h; c.paper = src.paper; c.paperColor = src.paperColor; c.src = src.src; c.srcPage = src.srcPage; c.bg = src.bg; c.items = ArrayList(src.items); c.loaded = true; ed.addPage(p, i, c); changed() } },
            MI("up", "Move up") { if (i > 0) { val x = n.pages.removeAt(i); n.pages.add(i - 1, x); p.view.structureChanged(); changed() } },
            MI("down", "Move down") { if (i < n.pages.size - 1) { val x = n.pages.removeAt(i); n.pages.add(i + 1, x); p.view.structureChanged(); changed() } },
            MI("edit", "Page label…") { prompt("Page label", n.pages[i].label ?: "", "Shown at the top of the page") { s -> n.pages[i].label = s.trim().ifEmpty { null }; if (n.pages[i].loaded) n.pages[i].dirty = true; Thumbs.drop(n.id, n.pages[i].id); p.view.pageChanged(i); ed.onDocChanged(p); changed() } },
            MI("paper", "Paper…") { p.view.scrollToPage(i); PaperUi.open(ed, p) },
            MI("share", "Export this page…") { Io.exportDialog(n, i) },
            null,
            MI("trash", "Delete page", true) { confirm("Delete page ${i + 1}?", "This cannot be undone.", "Delete") { ed.deletePage(p, i); changed() } }))
    }
}

/* ================= shelf ================= */
object ShelfUi {
    private val layer: Layer? get() = SideTabs.layer
    private var grid: GridLayout? = null
    private var ed: Editor? = null
    fun toggle(e: Editor) { if (SideTabs.layer != null && SideTabs.tab == 0) SideTabs.layer?.dismiss() else open(e) }
    fun exportFile() {
        act.createDoc("Larpful-shelf.zip", "application/zip") { uri -> Store.io {
            try { act.contentResolver.openOutputStream(uri)!!.use { o -> java.util.zip.ZipOutputStream(o.buffered()).use { z -> Store.zipDir(z, File(Store.root, "shelf"), "") { it.name.endsWith(".tmp") } } }; toast("Shelf exported") }
            catch (e: Throwable) { toast("Export failed") } } }
    }
    fun importFile() {
        act.pick(arrayOf("*/*"), false) { uris -> Store.io {
            try {
                val dir = File(Store.root, "shelf"); var n = 0
                act.contentResolver.openInputStream(uris[0])!!.use { i -> java.util.zip.ZipInputStream(i.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) { val name = e.name; if (!e.isDirectory && !name.contains('/') && !name.contains("..")) { val f = File(dir, name); if (!f.exists()) { f.outputStream().use { z.copyTo(it) }; if (name.endsWith(".json")) n++ } }; e = z.nextEntry } } }
                Store.main { Shelf.load(); refresh(); toast("Imported $n snippet" + (if (n == 1) "" else "s")) }
            } catch (e: Throwable) { toast("This file is not a shelf export") } } }
    }
    private var query = ""
    private var foot: TextView? = null
    fun open(e: Editor) { ed = e; SideTabs.open(e, 0) }
    /** the Shelf tab: search, "+ Add selection", the snippets, and how much there is */
    fun body(e: Editor): View {
        ed = e; val c = act; val box = vbox(c)
        val top = hbox(c); top.setPadding(dp(10), dp(2), dp(10), 0)
        val q = EditText(c); q.hint = "Search snippets"; q.setSingleLine(); q.setText(query); q.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f); q.setTextColor(Th.text); q.setHintTextColor(Th.sub); q.background = rr(Th.fill, dpf(10)); q.setPadding(dp(10), 0, dp(10), 0)
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val t = s?.toString() ?: ""; if (t != query) { query = t; refresh() } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
        })
        top.addView(q, lp(0, dp(34), 1f).apply { rightMargin = dp(8) })
        val add = hbox(c); add.gravity = Gravity.CENTER; add.background = rr(Th.fill, dpf(100)); add.setPadding(dp(10), dp(7), dp(12), dp(7)); add.fx(100f)
        add.addView(ImageView(c).apply { setImageDrawable(Icon("plus", Th.text)) }, lp(dp(14), dp(14)).apply { rightMargin = dp(5) }); add.addView(txt(c, "Add selection", 13f, Th.text, true))
        add.setOnClickListener { val p = e.focused; if (p?.view?.sel == null) toast("Select something with the Lasso first") else e.selToShelf() }
        top.addView(add); box.addView(top, lp(MP, WC))
        box.addView(txt(c, "Tap = place on the page · drag onto any page or note · hold for options", 11.5f, Th.sub).apply { setPadding(dp(12), dp(8), dp(12), dp(6)); setLineSpacing(dpf(2), 1f) })
        val sv = ScrollView(c); val g = GridLayout(c); g.columnCount = P.i("sh.cols").coerceIn(1, 3); g.setPadding(dp(8), dp(2), dp(8), dp(10)); sv.addView(g, FrameLayout.LayoutParams(MP, WC)); grid = g
        box.addView(sv, lp(MP, 0, 1f))
        val f = txt(c, "", 12f, Th.sub); f.setPadding(dp(12), dp(6), dp(12), dp(12)); foot = f; box.addView(f)
        refresh(); return box
    }
    fun closed() { grid = null; foot = null }
    fun refresh() {
        val g = grid ?: return; g.removeAllViews(); val cols = P.i("sh.cols").coerceIn(1, 3); g.columnCount = cols
        val cellW = (minOf(dp(P.i("sh.w")), act.root.width - dp(40)) - dp(16)) / cols
        val all = Shelf.sorted(); val qq = query.trim().lowercase(); val list = if (qq.isEmpty()) all else all.filter { it.name.lowercase().contains(qq) }
        val n = all.size; Store.work { val mb = Shelf.bytes() / 1048576f; Store.main { foot?.text = "$n snippet" + (if (n == 1) "" else "s") + " · " + String.format(Locale.US, "%.1f", mb) + " MB" } }
        if (list.isEmpty()) { g.columnCount = 1
            g.addView(txt(act, if (all.isEmpty()) "No snippets yet.\nUse the Lasso tool, select ink or a PDF area, then tap Snip." else "No snippet with that name.\nHold a snippet to give it a name.", 13.5f, Th.sub).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(30), dp(12), 0); setLineSpacing(dpf(4), 1f); width = cellW * cols - dp(8) }); return }
        for (s in list) g.addView(cell(s, cellW), GridLayout.LayoutParams().apply { width = cellW - dp(8); height = cellW - dp(8); setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun cell(s: Snip, cellW: Int): View {
        val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.background = rr(if (Th.dark) col(0xFFE5E5EA) else -1, dpf(10), Th.sep, 1); iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        Store.work { val b = BitmapFactory.decodeFile(Shelf.png(s.id).path); Store.main { if (b != null) iv.setImageBitmap(b) } }
        var dx = 0f; var dy = 0f; var drag: ImageView? = null; var scrolled = false
        fun startDrag(v: View) {
            if (drag != null) return
            v.parent.requestDisallowInterceptTouchEvent(true)
            val d = ImageView(act); d.setImageDrawable(iv.drawable); d.alpha = 0.85f; d.scaleType = ImageView.ScaleType.FIT_CENTER
            act.root.addView(d, flp(cellW, cellW)); drag = d
            val r = IntArray(2); act.root.getLocationOnScreen(r); d.translationX = dx - r[0] - cellW / 2f; d.translationY = dy - r[1] - cellW / 2f
        }
        val holdR = Runnable { if (!scrolled) { startDrag(iv); iv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } }
        iv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; scrolled = false; iv.postDelayed(holdR, 380) }
                MotionEvent.ACTION_MOVE -> {
                    val mx = Math.abs(e.rawX - dx); val my = Math.abs(e.rawY - dy)
                    if (drag == null && mx > dp(6) && mx > my) { iv.removeCallbacks(holdR); startDrag(v) }
                    else if (drag == null && my > dp(10)) { scrolled = true; iv.removeCallbacks(holdR) }
                    drag?.let { val r = IntArray(2); act.root.getLocationOnScreen(r); it.translationX = e.rawX - r[0] - cellW / 2f; it.translationY = e.rawY - r[1] - cellW / 2f }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    iv.removeCallbacks(holdR)
                    val d = drag; drag = null
                    if (d != null) {
                        act.root.removeView(d)
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            val g = grid; val a = IntArray(2); g?.getLocationOnScreen(a)
                            val overShelf = g != null && e.rawX >= a[0] && e.rawX <= a[0] + g.width
                            if (overShelf) showMenu(v, listOf(MI("plus", "Insert on the page") { drop(s, -1f, -1f) }, MI("edit", "Name…") { prompt("Snippet name", s.name, "Used by the shelf search") { nm -> Shelf.rename(s, nm.trim()); refresh() } }, MI("trash", "Delete", true) { val go = { Shelf.remove(s); refresh() }; if (P.b("sh.confirm")) confirm("Delete this snippet?", "", "Delete") { go() } else go() }))
                            else drop(s, e.rawX, e.rawY)
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && !scrolled) drop(s, -1f, -1f)
                }
            }
            true
        }
        return iv
    }
    private fun drop(s: Snip, rawX: Float, rawY: Float) {
        val e = ed ?: return
        var target: Pane? = null; var lx = 0f; var ly = 0f
        if (rawX >= 0) {
            for (p in e.panes) { val a = IntArray(2); p.view.getLocationOnScreen(a); if (rawX >= a[0] && rawX <= a[0] + p.view.width && rawY >= a[1] && rawY <= a[1] + p.view.height) { target = p; lx = rawX - a[0]; ly = rawY - a[1] } }
        } else { val f = e.focused; target = f; if (f != null) { lx = f.view.width / 2f; ly = f.view.height / 2f } }
        val p = target ?: return; val n = p.note ?: return
        Store.work { val items = Shelf.items(s); Shelf.prepare(items, n); Store.main { e.focus(p); Tools.set("lasso"); p.view.insertAt(items, lx, ly); if (P.b("sh.close")) layer?.dismiss() } }
    }
}

/* ================= open beside ================= */
object OpenBeside {
    fun open(ed: Editor) {
        var where = "right"
        val body = vbox(act)
        val seg = segmented(act, listOf("Left", "Right", "Top", "Bottom"), 1) { where = listOf("left", "right", "top", "bottom")[it] }
        body.addView(seg, lp(MP, WC).apply { leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(8) })
        val head = hbox(act); head.setPadding(dp(8), 0, dp(12), 0)
        lateinit var l: Layer
        val b = Browser(true) { m, _ -> l.dismiss(); ed.open(m.id, where) }
        val where2 = txt(act, "Notes", 16f, Th.text, true); val back = ibtn(act, "back", 36, Th.accent, 8) { b.up() }
        back.visibility = View.INVISIBLE; head.addView(back); head.addView(where2, lp(0, WC, 1f))
        b.onNavigate = { where2.text = b.title; back.visibility = if (b.canUp) View.VISIBLE else View.INVISIBLE }
        body.addView(head, lp(MP, dp(44))); body.addView(b.list, lp(MP, 0, 1f))
        l = sidePanel("Open Beside", 340, false, true, { SettingsUi.sub("Split view", Reg.split) }, body)
    }
}

/* ================= paper gallery ================= */
object PaperUi {
    fun open(ed: Editor, p: Pane) {
        val n = p.note ?: return; val i = p.view.curPage(); val pg = n.pages.getOrNull(i) ?: return
        val body = vbox(act)
        val fixed = pg.src != null || pg.bg != null     // a PDF / picture page keeps its own background
        var selK: String? = null; var selC: Int? = null   // what was tapped last: the tick always follows it
        fun setPaper(kind: String?, color: Int?) {
            val mode = P.s("paper.apply")
            if (kind != null) { selK = kind; selC = if (kind == "dark") col(0xFF1C1C1E) else if (kind == "black") col(0xFF000000) else if (selC != null && lum(selC!!) < 0.2f) -1 else selC }
            if (color != null) { selC = color; if (selK == "dark" || selK == "black" || ((selK ?: pg.paper) == "dark" || (selK ?: pg.paper) == "black")) selK = "blank" }
            // Dark and Black are papers of their own colour; leaving them goes back to white
            val col2 = if (kind == "dark") col(0xFF1C1C1E) else if (kind == "black") col(0xFF000000) else color
            if (kind != null) P.set("doc.paper", kind, false); if (col2 != null) P.set("doc.paperColor", col2, false) else if (kind != null && lum(P.i("doc.paperColor")) < 0.2f) P.set("doc.paperColor", -1, false)
            if (mode == "new") { toast("New pages will use this paper"); return }
            val targets = if (mode == "all") n.pages.indices.toList() else listOf(i)
            for (k in targets) { val x = n.pages[k]; if (x.src != null || x.bg != null) continue
                if (kind != null) { if ((x.paper == "dark" || x.paper == "black") && col2 == null) x.paperColor = -1; x.paper = kind }
                if (col2 != null) x.paperColor = col2
                if (x.loaded) x.dirty = true; Thumbs.drop(n.id, x.id); p.view.pageChanged(k) }
            ed.onDocChanged(p)
        }
        fun build() {
            body.removeAllViews()
            val modes = listOf("page", "all", "new")
            body.addView(segmented(act, listOf("This page", "Whole notebook", "New pages"), modes.indexOf(P.s("paper.apply")).coerceAtLeast(0)) { k -> P.set("paper.apply", modes[k], false) }, lp(MP, WC).apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(10); bottomMargin = dp(6) })
            if (fixed) body.addView(txt(act, "This page is a PDF or picture page, so its background stays as it is. Your choice applies to the paper pages.", 13.5f, Th.sub).apply { setPadding(dp(20), dp(6), dp(20), dp(2)); setLineSpacing(dpf(3), 1f) })
            val grid = GridLayout(act); grid.columnCount = 4; grid.setPadding(dp(12), dp(6), dp(12), 0)
            val color = selC ?: (if (!fixed) pg.paperColor else P.i("doc.paperColor")); val cur = selK ?: (if (!fixed) pg.paper else P.s("doc.paper"))
            for ((kind, name) in Render.PAPERS) {
                val cell = vbox(act); cell.gravity = Gravity.CENTER_HORIZONTAL; cell.fx(12f); val selK = cur == kind
                cell.background = rr(Th.card, dpf(12), if (selK) Th.accent else Th.hair, if (selK) dp(2) else 1); cell.setPadding(dp(5), dp(5), dp(5), dp(2))
                val pc = if (kind == "dark") col(0xFF1C1C1E) else if (kind == "black") col(0xFF000000) else if (lum(color) < 0.2f) -1 else color
                val iv = ImageView(act); iv.setImageBitmap(Render.paperPreview(kind, pc, 150, 212)); iv.scaleType = ImageView.ScaleType.FIT_XY; iv.background = rr(0, dpf(6), Th.sep, 1); iv.setPadding(1, 1, 1, 1); iv.clipToOutline = true
                val fr = FrameLayout(act); fr.addView(iv, flp(MP, MP))
                if (selK) fr.addView(ImageView(act).apply { setImageDrawable(Icon("check", android.graphics.Color.WHITE, 2.6f)); background = rr(Th.accent, dpf(10)); setPadding(dp(4), dp(4), dp(4), dp(4)) }, flp(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
                cell.addView(fr, lp(dp(104), dp(147))); cell.addView(txt(act, name, 12.5f, Th.text, true).apply { setPadding(0, dp(6), 0, dp(5)) })
                cell.setOnClickListener { setPaper(kind, null); build() }
                grid.addView(cell, GridLayout.LayoutParams().apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            body.addView(grid)
            body.addView(txt(act, "PAPER COLOR", 12.5f, Th.sub).apply { setPadding(dp(28), dp(14), 0, dp(6)) })
            val row = hbox(act); row.background = rr(Th.card, dpf(14)); row.setPadding(dp(12), dp(8), dp(12), dp(8))
            Render.PAPER_COLORS.forEachIndexed { k, pc ->
                val fr = FrameLayout(act); val on = pc == color || (k == 0 && color == -1)
                fr.addView(View(act).apply { background = rr(pc, dpf(10), if (on) Th.accent else Th.sep, if (on) dp(2.5f) else 1) }, flp(dp(46), dp(34), Gravity.CENTER))
                if (k == 0) fr.addView(txt(act, "Auto", 11f, col(0xFF8E8E93)), flp(WC, WC, Gravity.CENTER))
                fr.fx(10f); fr.setOnClickListener { setPaper(if (selK == "blank" && (pg.paper == "dark" || pg.paper == "black")) "blank" else null, pc); build() }; row.addView(fr, lp(dp(56), dp(40)))
            }
            row.addView(ibtn(act, "plus", 40, Th.accent) { v -> colorPicker(v, color) { nc -> setPaper(null, nc); build() } })
            body.addView(row, lp(MP, WC).apply { leftMargin = dp(16); rightMargin = dp(16) })
            body.addView(SettingsUi.panel(act, listOf(Group("Lines", Reg.paper[0].opts.filter { it.key == "paper.sp" || it.key == "paper.line" || it.key == "paper.margin" }), Group("New pages", Reg.paper[0].opts.filter { it.key == "doc.size" })), { p.view.refreshAll(); Thumbs.bump() }))
        }
        build(); sheet("Paper", body, 540)
    }
}

/* ================= version history ================= */
object HistoryUi {
    fun open(m: NoteMeta) {
        val body = vbox(act); body.setPadding(dp(14), dp(4), dp(14), dp(14))
        val fmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
        lateinit var layer: Layer
        fun build() {
            body.removeAllViews()
            val now = txt(act, "Save a version now", 15.5f, Th.accent, true); now.background = rr(Th.card, dpf(12)); now.setPadding(dp(16), dp(14), dp(16), dp(14)); now.fx(12f)
            now.setOnClickListener { act.ed.flush(); Store.io { Store.snapshot(m.id, true); Store.main { build() } } }
            body.addView(now, lp(MP, WC).apply { bottomMargin = dp(12) })
            val list = Store.snapshots(m.id)
            if (list.isEmpty()) body.addView(txt(act, "No versions yet. Larpful saves one automatically while you work on this note.", 14.5f, Th.sub).apply { setPadding(dp(6), dp(8), dp(6), dp(8)); setLineSpacing(dpf(3), 1f) })
            for (f in list) {
                val row = hbox(act); row.background = rr(Th.card, dpf(12)); row.setPadding(dp(10), dp(8), dp(8), dp(8))
                val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = rr(-1, dpf(5), Th.sep, 1); iv.clipToOutline = true
                Store.work { val b = Store.snapshotThumb(f); Store.main { if (b != null) iv.setImageBitmap(b) } }
                row.addView(iv, lp(dp(54), dp(72)))
                val t = f.name.removeSuffix(".zip").toLongOrNull() ?: f.lastModified()
                val colv = vbox(act); colv.addView(txt(act, fmt.format(Date(t)), 15f, Th.text, true)); colv.addView(txt(act, "${f.length() / 1024} KB", 12.5f, Th.sub).apply { setPadding(0, dp(4), 0, 0) })
                row.addView(colv, lp(0, WC, 1f).apply { leftMargin = dp(12) })
                row.addView(txt(act, "Restore", 15f, Th.accent, true).apply { setPadding(dp(12), dp(10), dp(10), dp(10)); fx(); setOnClickListener {
                    confirm("Restore this version?", "The current state is saved as a new version first, so you can come back.", "Restore") {
                        act.ed.flush()
                        Store.io { Store.snapshot(m.id, true); Store.restoreSnapshot(m.id, f); val n = Store.openNote(m.id)
                            Store.main { if (n != null) { m.pages = n.pages.size; m.first = n.pages.firstOrNull()?.id; m.modified = System.currentTimeMillis() }; Thumbs.bump(); act.ed.reload(m.id); Lib.changed(); layer.dismiss(); toast("Version restored") } }
                    } } })
                row.addView(ibtn(act, "trash", 36, Th.sub, 9) { f.delete(); build() })
                body.addView(row, lp(MP, WC).apply { bottomMargin = dp(8) })
            }
        }
        build()
        layer = sheet("History · " + m.title, body, 480, extra = ibtn(act, "gear", 40, Th.accent) { SettingsUi.sub("Version history", Reg.history) })
    }
}

/* ================= search ================= */
object Search {
    class Hit(val note: NoteMeta, val pageId: String?, val pageNo: Int, val kind: String, val snippet: String) {
        /** the page has handwriting / highlights on it */
        val annotated: Boolean get() = pageId != null && Store.inkFile(note.id, pageId).length() > 16
    }
    private val labelCache = HashMap<String, Pair<Long, List<Triple<String, Int, String>>>>()
    /** page labels of a notebook: (page id, page number, label) */
    private fun labels(m: NoteMeta): List<Triple<String, Int, String>> {
        synchronized(labelCache) { val c = labelCache[m.id]; if (c != null && c.first == m.modified) return c.second }
        val n = try { Store.openNote(m.id) } catch (e: Throwable) { null }
        val l = ArrayList<Triple<String, Int, String>>(); n?.pages?.forEachIndexed { i, p -> val lb = p.label; if (lb != null && lb.isNotEmpty()) l.add(Triple(p.id, i + 1, lb)) }
        synchronized(labelCache) { labelCache[m.id] = m.modified to l }; return l
    }
    private class Entry(val modified: Long, val ids: List<String>, val typed: List<String>, val pdf: List<String>)
    private val cache = HashMap<String, Entry>()

    /** IO thread, after every save: refreshes the typed-text and link index for the pages that changed */
    fun index(noteId: String, ids: List<String>, changed: Map<String, List<Item>>) {
        val dir = Store.noteDir(noteId); val f = File(dir, "typed.idx"); val old = HashMap<String, String>()
        if (f.exists()) for (line in f.readLines()) { val k = line.indexOf('\t'); if (k > 0) old[line.substring(0, k)] = line.substring(k + 1) }
        val sb = StringBuilder(); val links = JSONArray(); val oldLinks = LinkIndex.of(noteId)
        ids.forEachIndexed { no, pid ->
            val items = changed[pid]
            if (items != null) {
                val t = items.filterIsInstance<TextItem>().joinToString(" ⏎ ") { it.text.replace('\n', ' ').replace('\t', ' ') }
                if (t.isNotEmpty()) sb.append(pid).append('\t').append(t).append('\n')
                for (l in items) if (l is LinkItem) links.put(JSONObject().put("p", pid).put("n", no).put("t", l.noteId).put("tp", l.pageId).put("l", l.label))
            } else {
                old[pid]?.let { sb.append(pid).append('\t').append(it).append('\n') }
                for (k in 0 until oldLinks.length()) { val o = oldLinks.getJSONObject(k); if (o.optString("p") == pid) links.put(JSONObject(o.toString()).put("n", no)) }
            }
        }
        if (sb.isEmpty()) f.delete() else Store.atomic(f, sb.toString())
        LinkIndex.put(noteId, links)
        synchronized(cache) { cache.remove(noteId) }
    }

    /** is t[a until b] within k edits of w? */
    private fun near(t: String, a: Int, b: Int, w: String, k: Int): Boolean {
        val n = b - a; val m = w.length; var prev = IntArray(m + 1) { it }; var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i; var best = cur[0]; val ch = t[a + i - 1]
            for (j in 1..m) { val c = if (ch == w[j - 1]) 0 else 1; cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c); if (cur[j] < best) best = cur[j] }
            if (best > k) return false
            val x = prev; prev = cur; cur = x
        }
        return prev[m] <= k
    }
    fun norm(s: String, cs: Boolean, ar: Boolean): String {
        var x = if (cs) s else s.lowercase()
        if (ar) {
            val sb = StringBuilder(x.length)
            for (ch in x) { val c = ch.code
                if (c in 0x064B..0x0652 || c == 0x0670 || c == 0x0640) continue
                sb.append(when (ch) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; 'ؤ' -> 'و'; 'ئ' -> 'ي'; else -> ch }) }
            x = sb.toString()
        }
        return x
    }
    private fun entry(m: NoteMeta): Entry? {
        synchronized(cache) { cache[m.id]?.let { if (it.modified == m.modified) return it } }
        return try {
            val dir = Store.noteDir(m.id); val o = JSONObject(File(dir, "note.json").readText()); val a = o.getJSONArray("pages")
            val typedMap = HashMap<String, String>(); val tf = File(dir, "typed.idx")
            if (tf.exists()) for (line in tf.readLines()) { val k = line.indexOf('\t'); if (k > 0) typedMap[line.substring(0, k)] = line.substring(k + 1) }
            val pdfTexts = HashMap<String, List<String>>()
            val ids = ArrayList<String>(); val typed = ArrayList<String>(); val pdf = ArrayList<String>()
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i); val id = p.getString("id"); ids.add(id); typed.add(typedMap[id] ?: "")
                val src = if (p.isNull("src")) null else p.optString("src")
                if (src != null) { val pages = pdfTexts.getOrPut(src) { val t = File(dir, "$src.txt"); if (t.exists()) t.readText().split('\u000C') else emptyList() }; pdf.add(pages.getOrNull(p.optInt("sp")) ?: "") } else pdf.add("")
            }
            val e = Entry(m.modified, ids, typed, pdf); synchronized(cache) { cache[m.id] = e }; e
        } catch (e: Exception) { null }
    }
    fun dropCache(id: String) { synchronized(cache) { cache.remove(id) } }

    /** background thread */
    fun run(q0: String, onlyNote: String?): List<Hit> {
        val cs = P.b("sr.case"); val ar = P.b("sr.ar"); val q = norm(q0.trim(), cs, ar); if (q.isEmpty()) return emptyList()
        val mode = P.s("sr.mode"); val words = q.split(' ').filter { it.isNotEmpty() }
        val max = P.i("sr.max"); val ctx = P.i("sr.ctx"); val res = ArrayList<Hit>()
        val whole = P.b("sr.word")
        fun idx(t: String, w: String): Int {
            var i = t.indexOf(w); if (!whole) return i
            while (i >= 0) { val a = i == 0 || !t[i - 1].isLetterOrDigit(); val b = i + w.length >= t.length || !t[i + w.length].isLetterOrDigit(); if (a && b) return i; i = t.indexOf(w, i + 1) }
            return -1
        }
        val fuzzy = P.b("sr.fuzzy")
        /** exact position, or (with “tolerate small typos”) the first word that differs by one letter — two for long words */
        fun ix(t: String, w: String): Int {
            val e = idx(t, w); if (e >= 0 || !fuzzy || w.length < 4) return e
            val k = if (w.length >= 8) 2 else 1; var i = 0; val n = t.length
            while (i < n) {
                while (i < n && !t[i].isLetterOrDigit()) i++
                val st = i; while (i < n && t[i].isLetterOrDigit()) i++
                if (i > st && kotlin.math.abs((i - st) - w.length) <= k && near(t, st, i, w, k)) return st
            }
            return -1
        }
        fun find(text: String): Int { val t = norm(text, cs, ar)
            return when (mode) { "phrase" -> idx(t, q); "any" -> words.map { ix(t, it) }.filter { it >= 0 }.minOrNull() ?: -1
                else -> if (words.all { ix(t, it) >= 0 }) ix(t, words[0]) else -1 } }
        fun snip(text: String, at: Int): String { val a = maxOf(0, at - ctx); val b = minOf(text.length, at + q.length + ctx); return (if (a > 0) "…" else "") + text.substring(a.coerceAtMost(text.length), b).replace('\n', ' ').trim() + (if (b < text.length) "…" else "") }
        val metas = Lib.notes.filter { it.trashed == 0L && !it.locked && (onlyNote == null || it.id == onlyNote) }.sortedByDescending { it.modified }
        for (m in metas) {
            if (res.size >= max) break
            if (P.b("sr.names") && find(m.title) >= 0) res.add(Hit(m, m.first, 1, "Name", m.title))
            for ((pid, no, lb) in labels(m)) { if (res.size >= max) break; if (find(lb) >= 0) res.add(Hit(m, pid, no, "Label", lb)) }
            if (P.b("sr.links")) { val la = LinkIndex.of(m.id)
                for (k in 0 until la.length()) { if (res.size >= max) break; val o = la.optJSONObject(k) ?: continue; val lb = o.optString("l")
                    if (lb.isNotEmpty() && find(lb) >= 0) res.add(Hit(m, o.optString("p"), o.optInt("n") + 1, "Link", lb)) } }
            if (!P.b("sr.typed") && !P.b("sr.pdf")) continue
            val e = entry(m) ?: continue
            for (i in e.ids.indices) {
                if (res.size >= max) break
                if (P.b("sr.typed") && e.typed[i].isNotEmpty()) { val at = find(e.typed[i]); if (at >= 0) res.add(Hit(m, e.ids[i], i + 1, "Typed text", snip(e.typed[i], at))) }
                if (P.b("sr.pdf") && e.pdf[i].isNotEmpty()) { val at = find(e.pdf[i]); if (at >= 0) res.add(Hit(m, e.ids[i], i + 1, "PDF", snip(e.pdf[i], at))) }
            }
        }
        return res
    }
}

object SearchUi {
    private var gen = 0
    /** marks where the words are on the page that was opened from a result */
    fun mark(ed: Editor, noteId: String, pageId: String?, q: String) {
        if (!P.b("sr.mark") || pageId == null) return
        val pane = ed.panes.firstOrNull { it.note?.id == noteId } ?: return; val n = pane.note ?: return; val pg = n.pages.firstOrNull { it.id == pageId } ?: return
        val cs = P.b("sr.case"); val ar = P.b("sr.ar")
        val words = (if (P.s("sr.mode") == "phrase") listOf(q.trim()) else q.trim().split(' ')).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        Store.work {
            var g = 0; while (!pg.loaded && g++ < 60) Thread.sleep(50)
            val out = ArrayList<RectF>(); val nw = words.map { Search.norm(it, cs, ar) }
            try { for (it in ArrayList(pg.items)) if (it is TextItem) { val t = Search.norm(it.text, cs, ar); if (nw.any { w -> t.contains(w) }) out.add(RectF(it.bounds)) } } catch (e: Exception) { }
            val src = pg.src
            if (src != null && P.b("sr.pdf")) try {
                val f = File(n.dir, src); val size = Pdf.size(f, pg.srcPage)
                if (size != null) { val kx = pg.w / size.first; val ky = pg.h / size.second
                    for (r in PdfText.find(f, pg.srcPage, words, cs)) out.add(RectF(r.left * kx, r.top * ky, r.right * kx, r.bottom * ky)) }
            } catch (e: Throwable) { }
            Store.main { pane.view.marks.clear(); if (out.isNotEmpty()) pane.view.marks[pageId] = out; pane.view.invalidate() }
        }
    }
    fun open(ed: Editor) {
        val c = act; val body = vbox(c)
        lateinit var l: Layer
        var runNow: () -> Unit = {}
        val q = EditText(c); q.hint = "Search slides & text"; q.setSingleLine(); q.textSize = 16f; q.setTextColor(Th.text); q.setHintTextColor(Th.sub)
        q.background = rr(Th.fill, dpf(10)); q.setPadding(dp(12), dp(8), dp(12), dp(8))
        val head = hbox(c); head.setPadding(dp(2), dp(6), dp(2), 0)
        head.addView(ibtn(c, "back", 36, Th.accent, 9) { l.dismiss() }); head.addView(q, lp(0, WC, 1f))
        head.addView(ibtn(c, "gear", 38, Th.accent, 9) { SettingsUi.sub("Search", Reg.search) { runNow() } })
        body.addView(head, lp(MP, WC))
        // what to look in, and where (the original's two pickers)
        val picks = hbox(c); picks.setPadding(dp(10), dp(8), dp(10), dp(4))
        fun pick(label: () -> String, items: () -> List<MI>): View { val t = txt(c, label() + "  ▾", 13.5f, Th.text, true); t.background = rr(Th.fill, dpf(8)); t.setPadding(dp(12), dp(7), dp(12), dp(7)); t.fx(8f)
            t.setOnClickListener { v -> showMenu(v, items().map { m -> MI(m.icon, m.label) { m.run(); t.text = label() + "  ▾"; runNow() } }) }; return t }
        val kinds = listOf("all" to "All Pages", "pdf" to "PDF/slide text", "typed" to "Typed text", "labels" to "Page labels", "ann" to "Annotated pages")
        picks.addView(pick({ kinds.firstOrNull { it.first == P.s("sr.kind") }?.second ?: "All Pages" }) { kinds.map { (k, lab) -> MI(if (P.s("sr.kind") == k) "check" else "note", lab) { P.set("sr.kind", k, false) } } }, lp(WC, WC).apply { rightMargin = dp(8) })
        picks.addView(pick({ if (P.s("sr.scope") == "note") "This note" else "All notes" }) { listOf(MI(if (P.s("sr.scope") == "note") "check" else "note", "This note") { P.set("sr.scope", "note", false) }, MI(if (P.s("sr.scope") != "note") "check" else "nbook", "All notes") { P.set("sr.scope", "all", false) }) })
        body.addView(picks, lp(MP, WC))
        val status = txt(c, "", 12.5f, Th.sub); status.setPadding(dp(16), dp(2), dp(16), dp(6)); body.addView(status)
        val list = ListView(c); list.divider = null; list.selector = android.graphics.drawable.ColorDrawable(0); body.addView(list, lp(MP, 0, 1f))
        var hits: List<Search.Hit> = emptyList()
        fun openHit(h: Search.Hit) { val qq = q.text.toString(); l.dismiss(); ed.open(h.note.id, "replace", h.pageId); if (h.kind != "Name") mark(ed, h.note.id, h.pageId, qq) }
        // "Open the first result when I press Search on the keyboard"
        q.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        q.setOnEditorActionListener { _, _, _ -> if (P.b("sr.first") && hits.isNotEmpty()) { openHit(hits[0]); true } else false }
        val ad = object : BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(i: Int) = hits[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val h = hits[i]
                val row = (cv as? LinearLayout) ?: hbox(c).apply { setPadding(dp(12), dp(7), dp(12), dp(7)); gravity = Gravity.TOP
                    addView(ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }, lp(dp(60), dp(80)))
                    val col = vbox(c); col.addView(txt(c, "", 15f, Th.text, true).oneLine()); col.addView(txt(c, "", 12f, Th.accent).apply { setPadding(0, dp(3), 0, dp(3)) }); col.addView(txt(c, "", 13f, Th.sub).apply { maxLines = 3 })
                    addView(col, lp(0, WC, 1f).apply { leftMargin = dp(12) }); layoutParams = AbsListView.LayoutParams(MP, WC) }
                val iv = row.getChildAt(0) as ImageView; val col = row.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = h.note.title; (col.getChildAt(1) as TextView).text = "Page ${h.pageNo} · ${h.kind}"; (col.getChildAt(2) as TextView).text = h.snippet
                iv.background = rr(Th.card, dpf(6), Th.sep, 1); iv.setImageDrawable(null); val key = h.note.id + h.pageId; iv.tag = key
                if (h.pageId != null) Thumbs.load(h.note.id, h.pageId) { b -> if (iv.tag == key && b != null) iv.setImageBitmap(b) }
                row.fx(10f); row.setOnClickListener { openHit(h) }
                return row
            }
        }
        list.adapter = ad
        val runR = Runnable {
            val text = q.text.toString(); val g = ++gen
            if (text.isBlank() || text.trim().length < P.i("sr.min")) { hits = emptyList(); ad.notifyDataSetChanged(); status.text = ""; return@Runnable }
            status.text = "Searching…"
            val only = if (P.s("sr.scope") == "note") ed.focused?.note?.id else null
            val kind = P.s("sr.kind")
            Store.work { val r = Search.run(text, only).filter { h -> when (kind) { "pdf" -> h.kind == "PDF"; "typed" -> h.kind == "Typed text"; "labels" -> h.kind == "Label"; "ann" -> h.annotated; else -> true } }.let { r0 -> if (P.s("sr.sort") == "name") r0.sortedWith(compareBy<Search.Hit>({ it.note.title.lowercase() }, { it.pageNo })) else r0 }; Store.main { if (g == gen) { hits = r; ad.notifyDataSetChanged(); status.text = if (r.isEmpty()) "No results" else "${r.size} result" + (if (r.size == 1) "" else "s") } } }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { q.removeCallbacks(runR); q.postDelayed(runR, P.i("sr.delay").toLong()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
        })
        ed.doSave(); runNow = { q.removeCallbacks(runR); q.post(runR) }
        val box = vbox(c); box.setBackgroundColor(Th.bg); box.elevation = dpf(16); box.addView(body, lp(MP, MP))
        l = Layer(box, true, false) { (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(q.windowToken, 0) }
        l.show(flp(minOf(dp(P.i("sr.w")), act.root.width - dp(40)), MP, Gravity.START), 1)
        q.requestFocus(); q.postDelayed({ (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(q, 0) }, 200)
    }
}

/* ================= links + backlinks ================= */
object LinkIndex {
    private var data: JSONObject? = null
    private val file get() = File(Store.root, "links.json")
    @Synchronized private fun load(): JSONObject { var d = data; if (d == null) { d = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }; data = d }; return d!! }
    @Synchronized fun of(note: String): JSONArray = load().optJSONArray(note) ?: JSONArray()
    @Synchronized fun put(note: String, links: JSONArray) { val d = load(); val had = d.has(note); if (links.length() == 0) { if (!had) return; d.remove(note) } else d.put(note, links); Store.atomic(file, d.toString()) }
    @Synchronized fun reset() { data = null }
    /** links in other notes that point at this note: (source note id, link json) */
    @Synchronized fun backlinks(note: String): List<Pair<String, JSONObject>> {
        val d = load(); val out = ArrayList<Pair<String, JSONObject>>()
        for (k in d.keys()) { if (k == note) continue; val a = d.optJSONArray(k) ?: continue; for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("t") == note) out.add(k to o) } }
        return out
    }
}

object LinksUi {
    /** selection -> pick a note -> pick a page */
    fun create(ed: Editor, p: Pane) {
        val s = p.view.sel ?: return; val rect = RectF(s.bounds); val pageIdx = s.page
        val box = vbox(act); val head = hbox(act); head.setPadding(dp(8), 0, dp(12), 0)
        lateinit var layer: Layer
        val b = Browser(true) { m, _ -> layer.dismiss(); pickPage(m) { pid, no -> p.view.deselect(); p.view.commitAdd(pageIdx, listOf(LinkItem(rect, m.id, pid, m.title + " · p." + no))); toast("Link added") } }
        val where = txt(act, "Notes", 16f, Th.text, true); val back = ibtn(act, "back", 36, Th.accent, 8) { b.up() }; back.visibility = View.INVISIBLE
        head.addView(back); head.addView(where, lp(0, WC, 1f)); b.onNavigate = { where.text = b.title; back.visibility = if (b.canUp) View.VISIBLE else View.INVISIBLE }
        box.addView(head, lp(MP, dp(44))); box.addView(b.list, lp(MP, dp(420)))
        layer = sheet("Link to…", box, 440)
    }
    private fun pickPage(m: NoteMeta, done: (String, Int) -> Unit) {
        val n = Store.openNote(m.id) ?: return
        val grid = GridLayout(act); grid.columnCount = 4; grid.setPadding(dp(10), dp(4), dp(10), dp(10))
        lateinit var layer: Layer
        n.pages.forEachIndexed { i, pg ->
            val cell = vbox(act); cell.gravity = Gravity.CENTER_HORIZONTAL; cell.fx(8f)
            val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.background = rr(-1, dpf(6), Th.sep, 1); iv.setPadding(dp(2), dp(2), dp(2), dp(2))
            Thumbs.load(m.id, pg.id) { b -> if (b != null) iv.setImageBitmap(b) }
            cell.addView(iv, lp(dp(96), dp(128))); cell.addView(txt(act, (i + 1).toString(), 12.5f, Th.sub).apply { setPadding(0, dp(4), 0, dp(4)) })
            cell.setOnClickListener { layer.dismiss(); done(pg.id, i + 1) }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = dp(108); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        layer = sheet("Choose a page · " + m.title, grid, 500)
    }

    fun go(ed: Editor, noteId: String, pageId: String, beside: Boolean) {
        if (Lib.note(noteId) == null || Lib.note(noteId)?.trashed != 0L) { toast("The linked note no longer exists"); return }
        // where we came from, for the "Back" button
        val from = ed.focused; val fn = from?.note; val fp = if (fn != null) fn.pages.getOrNull(from!!.view.curPage())?.id else null; val ft = fn?.meta?.title ?: ""
        val fno = if (fn != null) from!!.view.curPage() + 1 else 0
        ed.open(noteId, if (beside && !ed.isOpen(noteId)) "right" else "replace", pageId)
        if (!beside && P.b("lk.back") && fn != null && fp != null) showBack(ed, fn.id, fp, if (fn.id == noteId) "page $fno" else ft)
    }
    private var backChip: View? = null
    /** a small "← Back" pill after following a link (as in the original); it goes by itself after a few seconds */
    private fun showBack(ed: Editor, noteId: String, pageId: String, title: String) {
        backChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val t = txt(act, "←  Back to $title", 14f, android.graphics.Color.WHITE, true).oneLine(); t.background = rr(Th.accent, dpf(20)); t.setPadding(dp(16), dp(10), dp(16), dp(10)); t.elevation = dpf(30); t.fx(20f)
        t.setOnClickListener { (t.parent as? ViewGroup)?.removeView(t); backChip = null; if (Lib.note(noteId) != null) ed.open(noteId, "replace", pageId) }
        act.root.addView(t, flp(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        backChip = t
        val secs = P.f("lk.backSec"); if (secs > 0f) t.postDelayed({ if (backChip === t) { (t.parent as? ViewGroup)?.removeView(t); backChip = null } }, (secs * 1000).toLong())
    }
    fun tapped(ed: Editor, p: Pane, l: LinkItem, x: Int, y: Int) {
        if (P.s("lk.act") == "jump") { go(ed, l.noteId, l.pageId, P.s("lk.open") == "beside"); return }
        val m = Lib.note(l.noteId)
        val box = vbox(act); box.setPadding(dp(12), dp(12), dp(12), dp(8))
        val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.adjustViewBounds = true; iv.background = rr(-1, dpf(8), Th.sep, 1); iv.setPadding(dp(2), dp(2), dp(2), dp(2)); iv.minimumHeight = dp(120)
        if (m != null && !m.locked) Thumbs.load(l.noteId, l.pageId) { b -> if (b != null) iv.setImageBitmap(b) }
        box.addView(iv, lp(MP, WC))
        box.addView(txt(act, m?.title ?: "Missing note", 15.5f, Th.text, true).apply { setPadding(dp(2), dp(10), 0, dp(2)) })
        box.addView(txt(act, l.label, 12.5f, Th.sub).apply { setPadding(dp(2), 0, 0, dp(6)) })
        lateinit var layer: Layer
        val row = hbox(act)
        fun btn(label: String, color: Int, on: () -> Unit) { row.addView(txt(act, label, 14.5f, color, true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), dp(10)); fx(); setOnClickListener { layer.dismiss(); on() } }, lp(0, WC, 1f)) }
        btn("Open", Th.accent) { go(ed, l.noteId, l.pageId, false) }
        btn("Open beside", Th.accent) { go(ed, l.noteId, l.pageId, true) }
        btn("Remove", Th.danger) { val i = p.note?.pages?.indexOfFirst { it.items.contains(l) } ?: -1; if (i >= 0) p.view.replaceItem(i, l, null) }
        box.addView(row)
        layer = popover(x, y + dp(10), box, P.i("lk.pvW"))
        val auto = P.f("lk.pvAuto"); if (auto > 0f) box.postDelayed({ layer.dismiss() }, (auto * 1000).toLong())
    }

    /** panel listing the links in this note and the backlinks that point to it */
    fun panel(ed: Editor) { if (SideTabs.layer != null && SideTabs.tab == 1) SideTabs.layer?.dismiss() else SideTabs.open(ed, 1) }
    /** the Links tab: "Link selection…", links out of this page, backlinks to the page and to the note, every link of the note */
    fun body(ed: Editor, dismiss: () -> Unit): View {
        val sv = ScrollView(act); val body = vbox(act); sv.addView(body, FrameLayout.LayoutParams(MP, WC))
        val p = ed.focused ?: return sv; val n = p.note ?: return sv
        ed.doSave()
        val cur = p.view.curPage(); val curId = n.pages.getOrNull(cur)?.id
        fun section(t: String, count: Int) { val h = hbox(act); h.setPadding(dp(14), dp(16), dp(14), dp(6)); h.addView(txt(act, t.uppercase(), 12f, Th.sub, true), lp(0, WC, 1f)); h.addView(txt(act, count.toString(), 12f, Th.sub)); body.addView(h, lp(MP, WC)) }
        fun none(t: String) { body.addView(txt(act, t, 13f, Th.sub).apply { setPadding(dp(16), dp(2), dp(16), dp(4)); setLineSpacing(dpf(2), 1f) }) }
        fun row(thumbNote: String, thumbPage: String, title: String, sub: String, on: () -> Unit) {
            val r = hbox(act); r.setPadding(dp(12), dp(6), dp(12), dp(6)); r.fx(10f)
            val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = rr(Th.card, dpf(6), Th.sep, 1); iv.clipToOutline = true
            Thumbs.load(thumbNote, thumbPage) { b -> if (b != null) iv.setImageBitmap(b) }
            r.addView(iv, lp(dp(48), dp(64)))
            val c = vbox(act); c.addView(txt(act, title, 14.5f, Th.text, true).oneLine()); c.addView(txt(act, sub, 12f, Th.sub).apply { setPadding(0, dp(3), 0, 0) })
            r.addView(c, lp(0, WC, 1f).apply { leftMargin = dp(10) }); r.setOnClickListener { dismiss(); on() }
            body.addView(r, lp(MP, WC))
        }
        val chip = hbox(act); chip.gravity = Gravity.CENTER; chip.background = rr(Th.fill, dpf(100)); chip.setPadding(dp(12), dp(8), dp(14), dp(8)); chip.fx(100f)
        chip.addView(ImageView(act).apply { setImageDrawable(Icon("link", Th.text)) }, lp(dp(15), dp(15)).apply { rightMargin = dp(6) }); chip.addView(txt(act, "Link selection…", 13.5f, Th.text, true))
        chip.setOnClickListener { if (p.view.sel == null) toast("Select a word or a drawing with the Lasso first") else { dismiss(); create(ed, p) } }
        val cr = hbox(act); cr.setPadding(dp(12), dp(6), 0, 0); cr.addView(chip); body.addView(cr, lp(MP, WC))
        val out = ArrayList<Triple<Int, LinkItem, Unit>>()
        n.pages.forEachIndexed { i, pg -> if (pg.loaded) for (it in pg.items) if (it is LinkItem) out.add(Triple(i, it, Unit)) }
        fun linkRow(i: Int, it: LinkItem) { row(it.noteId, it.pageId, Lib.note(it.noteId)?.title ?: "Missing note", "From page ${i + 1} · " + it.label) { go(ed, it.noteId, it.pageId, P.s("lk.open") == "beside") } }
        val here = out.filter { it.first == cur }
        section("On page ${cur + 1} → links out", here.size); if (here.isEmpty()) none("None. Lasso a word and choose Link.") else for (x in here) linkRow(x.first, x.second)
        val back = LinkIndex.backlinks(n.id).filter { Lib.note(it.first)?.trashed == 0L }
        fun backRow(src: String, o: JSONObject) { val tno = n.pageById(o.optString("tp")) + 1
            row(src, o.optString("p"), Lib.note(src)?.title ?: "Note", "Page ${o.optInt("n") + 1} → " + (if (tno > 0) "page $tno" else "this note")) { ed.open(src, "replace", o.optString("p")) } }
        val toPage = back.filter { curId != null && it.second.optString("tp") == curId }
        section("Backlinks to this page", toPage.size); if (toPage.isEmpty()) none("Nothing links here yet.") else for ((src, o) in toPage) backRow(src, o)
        val toNote = back.filter { !(curId != null && it.second.optString("tp") == curId) }
        section("Backlinks to this note", toNote.size); if (toNote.isEmpty()) none("No other links to this note.") else for ((src, o) in toNote) backRow(src, o)
        section("All links in this note", out.size); if (out.isEmpty()) none("No links yet.") else for (x in out) linkRow(x.first, x.second)
        body.addView(space(act, 0, dp(20)))
        return sv
    }
}

/** the panel on the right with the two tabs "Shelf | Links" (the original's layout) */
object SideTabs {
    var layer: Layer? = null
    var tab = 0
    fun open(ed: Editor, which: Int) {
        tab = which; layer?.dismiss()
        val c = act; val box = vbox(c); box.setBackgroundColor(Th.bg); box.elevation = dpf(16)
        val bar = hbox(c); bar.setPadding(dp(10), dp(6), dp(4), dp(4))
        lateinit var l: Layer
        val slot = FrameLayout(c)
        fun fill() { slot.removeAllViews(); ShelfUi.closed(); slot.addView(if (tab == 0) ShelfUi.body(ed) else LinksUi.body(ed) { l.dismiss() }, FrameLayout.LayoutParams(MP, MP)) }
        bar.addView(segmented(c, listOf("Shelf", "Links"), tab) { i -> tab = i; fill() }, lp(0, WC, 1f))
        bar.addView(ibtn(c, "close", 38, Th.accent) { l.dismiss() })
        bar.addView(ibtn(c, "gear", 38, Th.accent) { if (tab == 0) SettingsUi.sub("Shelf", Reg.shelf) { ShelfUi.refresh() } else SettingsUi.sub("Links", Reg.links) { ed.refreshPanes() } })
        box.addView(bar, lp(MP, WC)); box.addView(slot, lp(MP, 0, 1f))
        fill()
        // "Pin the shelf beside the page": no scrim, the page area makes room for it instead of being covered
        val dock = P.b("sh.dock"); val right = P.s("sh.side") != "left"; val w = minOf(dp(P.i("sh.w")), act.root.width - dp(40))
        l = Layer(box, P.b("ui.panelOut") && !dock, false) { layer = null; ShelfUi.closed(); if (dock) ed.root.setPadding(0, 0, 0, 0) }
        if (dock) { box.elevation = dpf(2); ed.root.setPadding(if (right) 0 else w, 0, if (right) w else 0, 0) }
        l.show(flp(w, MP, if (right) Gravity.END else Gravity.START), if (right) 2 else 1)
        layer = l
    }
}
