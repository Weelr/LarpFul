package com.weelr.larpful2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** One window of the split view: its own note, zoom, scroll, undo history. */
class Pane(val ed: Editor) : FrameLayout(act) {
    val view = NoteView(act, this)
    var note: Note? = null
    private val chip = hbox(act)
    private val chipTitle = txt(act, "", 13f, Th.text, true).oneLine()
    private val ind = txt(act, "", 13f, Th.text, true)
    private val zb = vbox(act)
    init {
        addView(view, flp(MP, MP))
        chip.addView(chipTitle, lp(WC, WC).apply { rightMargin = dp(2) }); chipTitle.maxWidth = dp(190)
        chip.addView(ibtn(act, "close", 26, Th.sub, 7) { ed.closePane(this) })
        addView(chip, flp(WC, dp(28), Gravity.TOP or Gravity.START).apply { topMargin = dp(8); leftMargin = dp(12) })
        chip.visibility = View.GONE
        ind.setPadding(dp(12), dp(5), dp(12), dp(5))
        ind.setOnClickListener { if (P.b("pg.jump")) prompt("Go to page (1–${view.count()})", "", number = true) { s -> s.toIntOrNull()?.let { view.scrollToPage((it - 1).coerceIn(0, view.count() - 1)) } } else { ed.focus(this); PagesUi.open(ed) } }
        addView(ind, flp(WC, WC, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(14); bottomMargin = dp(14) })
        addView(zb, flp(dp(44), WC, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14); bottomMargin = dp(20) })
        applyChrome()
    }
    fun applyChrome() {
        chip.background = rr(Th.glass, dpf(100), Th.hair, 1); chip.setPadding(dp(12), 0, dp(2), 0); chip.elevation = dpf(3); chipTitle.setTextColor(Th.text)
        ind.background = rr(Th.glass, dpf(100), Th.hair, 1); ind.elevation = dpf(3); ind.setTextColor(Th.text); ind.fx(100f)
        ind.visibility = if (P.b("ui.pageInd")) View.VISIBLE else View.GONE
        zb.removeAllViews(); zb.background = rr(Th.glass, dpf(14), Th.hair, 1); zb.elevation = dpf(5)
        fun z(icon: String, on: () -> Unit) { val b = ibtn(act, icon, 44, Th.accent, 11) { on() }; zb.addView(b, lp(dp(44), dp(40))) }
        z("plus") { view.zoomTo((view.zoom * 1.3f).coerceAtMost(P.f("g.maxZoom")), view.width / 2f, view.height / 2f) }
        z("zfit") { view.zoomTo(1f, view.width / 2f, view.height / 2f) }
        z("minus") { view.zoomTo((view.zoom / 1.3f).coerceAtLeast(0.5f), view.width / 2f, view.height / 2f) }
        if (P.b("ui.zoomLockBtn")) { val on = P.b("g.zLock")   // freezes the zoom so a stray pinch while writing does nothing
            val b = ibtn(act, if (on) "lock" else "unlock", 44, if (on) android.graphics.Color.WHITE else Th.accent, 12) { val nv = !P.b("g.zLock"); P.set("g.zLock", nv); toast(if (nv) "Zoom locked" else "Zoom unlocked") }
            if (on) b.background = rr(Th.accent, dpf(11)); zb.addView(b, lp(dp(44), dp(40))) }
        zb.visibility = if (P.b("ui.zoomBtns")) View.VISIBLE else View.GONE
    }
    fun setPage(t: String) { if (ind.text != t) ind.text = t }
    fun open(n: Note, force: Boolean = false) { note = n; view.show(n, force); refreshChip(); hideSelBar() }
    fun refreshChip() { chipTitle.text = note?.meta?.title ?: ""; chip.visibility = if (ed.multi) View.VISIBLE else View.GONE }

    /* floating menu above the current selection */
    private val selBar = hbox(act)
    fun hideSelBar() { if (selBar.visibility != View.GONE) selBar.visibility = View.GONE }
    fun updateSelBar() {
        val r = view.selRect()
        if (r == null || !P.b("ls.menu")) { hideSelBar(); return }
        if (selBar.parent == null) {
            selBar.background = rr(Th.bg, dpf(16), Th.hair, 1); selBar.elevation = dpf(8); selBar.setPadding(dp(4), 0, dp(4), 0)
            fun b(icon: String, tint: Int = Th.text, on: (View) -> Unit) { selBar.addView(ibtn(act, icon, 38, tint, 9, on)) }
            b("copy", Th.accent) { view.selCopy(); toast("Copied") }; b("cut", Th.accent) { view.selCut() }; b("dup", Th.accent) { view.selDuplicate() }
            b("comment", Th.accent) { ed.comment(this) }; b("target", Th.accent) { v -> colorPicker(v, P.i("pen.color")) { nc -> view.selRecolor(nc) } }
            b("shelf", Th.accent) { ed.focus(this); ed.selToShelf() }; b("link", Th.accent) { ed.focus(this); ed.selLink() }; b("trash", Th.danger) { view.selDelete() }
            addView(selBar, flp(WC, dp(40)))
        }
        selBar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY))
        val w = selBar.measuredWidth; val h = dp(40); val p = selBar.layoutParams as FrameLayout.LayoutParams
        val x = (r.centerX() - w / 2f).toInt().coerceIn(dp(6), max(dp(6), width - w - dp(6)))
        val y = if (r.top - h - dp(12) > dp(4)) (r.top - h - dp(12)).toInt() else (r.bottom + dp(14)).toInt().coerceAtMost(max(dp(4), height - h - dp(6)))
        if (p.leftMargin != x || p.topMargin != y) { p.leftMargin = x; p.topMargin = y; selBar.layoutParams = p }
        if (selBar.visibility != View.VISIBLE) selBar.visibility = View.VISIBLE
    }
}

class Editor {
    val root = FrameLayout(act)
    private val column = vbox(act)
    val top = hbox(act)
    val audioSlot = FrameLayout(act)
    private val topLine = View(act)
    val tabBar = android.widget.HorizontalScrollView(act)
    private val tabRow = hbox(act)
    val tabs = ArrayList<String>()
    private val box = LinearLayout(act)
    val home = FrameLayout(act)
    val panes = ArrayList<Pane>()
    var focused: Pane? = null
    val multi: Boolean get() = panes.size > 1
    private var vertical = false         // true = panes stacked top/bottom
    private var ratio = 0.5f
    val bar: PenBar
    val audio = AudioCtl(this)
    private var pageChip: TextView? = null
    private var titleView: TextView? = null

    init {
        root.addView(column, flp(MP, MP))
        tabBar.isHorizontalScrollBarEnabled = false; tabBar.addView(tabRow, FrameLayout.LayoutParams(WC, MP)); tabBar.visibility = View.GONE
        try { val a = JSONArray(P.s("tabs")); for (i in 0 until a.length()) tabs.add(a.getString(i)) } catch (e: Exception) { }
        column.addView(top, lp(MP, dp(52))); column.addView(topLine, lp(MP, 1)); column.addView(tabBar, lp(MP, dp(44))); column.addView(box, lp(MP, 0, 1f))
        root.addView(audioSlot, flp(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        Lib.listeners.add { refreshTabs() }
        root.addView(home, flp(MP, MP))
        bar = PenBar(this)
        applyTheme(); showHome()
    }
    fun applyTheme() { top.setBackgroundColor(Th.bar); topLine.setBackgroundColor(Th.sep); root.setBackgroundColor(Th.canvas); home.setBackgroundColor(if (Th.dark) Th.bg else android.graphics.Color.WHITE); refreshTop(); for (p in panes) { p.refreshChip(); p.applyChrome(); p.updateSelBar(); p.view.invalidate() }; if (panes.isEmpty()) act.lib.fillHome(home) }
    fun topInset(): Int = if (panes.isEmpty()) 0 else top.height + 1 + (if (tabBar.visibility == View.VISIBLE) tabBar.height else 0)

    /* ---------- tabs: every note you opened stays one tap away ---------- */
    private fun addTab(id: String) {
        if (id !in tabs) tabs.add(id)
        val max = P.i("tb.tabsMax").coerceAtLeast(2)
        while (tabs.size > max) { val drop = tabs.firstOrNull { !isOpen(it) && it != id } ?: break; tabs.remove(drop) }
        saveTabs()
    }
    private fun saveTabs() { val a = JSONArray(); for (t in tabs) a.put(t); P.set("tabs", a.toString(), false) }
    fun closeTab(id: String) {
        val i = tabs.indexOf(id); tabs.remove(id); saveTabs()
        val pane = panes.firstOrNull { it.note?.id == id }
        if (pane != null) {
            val next = tabs.getOrNull(i.coerceIn(0, max(0, tabs.size - 1)))?.takeIf { !isOpen(it) } ?: tabs.lastOrNull { !isOpen(it) }
            if (next != null) { focus(pane); open(next, "replace") } else closePane(pane)
        }
        refreshTabs()
    }
    fun refreshTabs() {
        tabs.retainAll { Lib.note(it)?.trashed == 0L }
        val show = P.b("tb.tabs") && tabs.isNotEmpty() && panes.isNotEmpty()
        val vis = if (show) View.VISIBLE else View.GONE; if (tabBar.visibility != vis) tabBar.visibility = vis
        tabRow.removeAllViews(); if (!show) return
        tabBar.setBackgroundColor(Th.bar); tabRow.setPadding(dp(14), dp(6), dp(14), dp(6))
        val cur = focused?.note?.id
        for (id in tabs) {
            val m = Lib.note(id) ?: continue; val active = id == cur; val open = isOpen(id)
            val chip = hbox(act); chip.background = rr(if (active) Th.accent else Th.fill, dpf(100)); chip.setPadding(dp(6), 0, dp(2), 0); chip.fx(100f)
            val t = txt(act, m.title, 15f, if (active) android.graphics.Color.WHITE else if (open) Th.text else Th.sub, active).oneLine(); t.maxWidth = dp(180); t.setPadding(dp(8), 0, dp(2), 0); chip.addView(t)
            chip.addView(ibtn(act, "close", 26, if (active) android.graphics.Color.WHITE else Th.sub, 8) { closeTab(id) })
            chip.setOnClickListener { val p = panes.firstOrNull { it.note?.id == id }; if (p != null) focus(p) else open(id, "replace") }
            chip.setOnLongClickListener { v -> showMenu(v, listOf(
                MI("paneR", "Open beside") { open(id, "right") }, MI("close", "Close this tab") { closeTab(id) },
                MI("trash", "Close the other tabs") { for (o in ArrayList(tabs)) if (o != id && !isOpen(o)) tabs.remove(o); saveTabs(); refreshTabs() })); true }
            tabRow.addView(chip, lp(WC, dp(32)).apply { rightMargin = dp(6) })
        }
    }
    fun invalidatePanes() { for (p in panes) p.view.invalidate() }
    fun refreshPanes() { for (p in panes) { p.view.relayout(); p.view.refreshAll() } }

    private var wasEmpty = true
    private fun showHome() {
        val empty = panes.isEmpty()
        if (empty != wasEmpty) { wasEmpty = empty; if (act.hasEd) act.layoutMain() }
        home.visibility = if (empty) View.VISIBLE else View.GONE; column.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        bar.bar.visibility = if (empty) View.GONE else View.VISIBLE; if (empty) bar.ctx.visibility = View.GONE
        if (empty) act.lib.fillHome(home) else bar.place(false)
    }

    /* ---------- opening / closing ---------- */
    fun isOpen(id: String) = panes.any { it.note?.id == id }
    fun open(id: String, where: String = "replace", page: String? = null) {
        val m = Lib.note(id) ?: return
        if (m.locked && !act.unlocked.contains(id)) { act.askPin { act.unlocked.add(id); open(id, where, page) }; return }
        commitText()
        val existing = panes.firstOrNull { it.note?.id == id }
        if (existing != null) { focus(existing); if (page != null) jump(existing, page); return }
        val n = Store.openNote(id) ?: run { toast("This note could not be opened"); return }
        if (page != null) { val i = n.pageById(page); if (i >= 0) { n.vpPage = i; n.vpOff = 0f } }
        val target: Pane
        if (where == "replace" || panes.isEmpty()) {
            val t = focused ?: panes.firstOrNull()
            if (t == null) { target = Pane(this); panes.add(target) } else { saveNote(t); target = t }
        } else {
            if (panes.size >= 2) { val other = panes.first { it !== focused }; saveNote(other); panes.remove(other) }
            target = Pane(this); vertical = where == "top" || where == "bottom"; val r = P.f("split.ratio") / 100f
            if (where == "left" || where == "top") { panes.add(0, target); ratio = r } else { panes.add(target); ratio = 1f - r }
        }
        target.open(n, page != null); focused = target; addTab(id)
        layoutPanes(); showHome(); refreshTop(); bar.rebuild(); saveSession(); act.lib.refresh()
        Store.io { Store.snapshot(id) }
    }
    private fun jump(p: Pane, pageId: String) { val i = p.note?.pageById(pageId) ?: -1; if (i >= 0) p.view.post { p.view.scrollToPage(i) } }
    fun closePane(p: Pane, keepTab: Boolean = false) {
        commitText(); audio.onClose(p); saveNote(p); panes.remove(p); if (!keepTab) p.note?.let { tabs.remove(it.id); saveTabs() }
        if (focused === p) focused = panes.firstOrNull()
        layoutPanes(); showHome(); refreshTop(); bar.rebuild(); saveSession(); act.lib.refresh()
    }
    /** back to the notebooks: the open notes stay as tabs */
    fun goHome() { for (p in ArrayList(panes)) closePane(p, true) }
    fun closeNote(id: String) { panes.firstOrNull { it.note?.id == id }?.let { closePane(it) } }
    fun reload(id: String) { val p = panes.firstOrNull { it.note?.id == id } ?: return; val n = Store.openNote(id) ?: return; p.open(n); refreshTop() }
    fun focus(p: Pane) { if (focused === p) return; commitText(); focused = p; refreshTop(); bar.rebuild(); invalidatePanes(); act.lib.sideChanged() }

    @SuppressLint("ClickableViewAccessibility")
    private fun layoutPanes() {
        box.removeAllViews(); box.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for ((i, p) in panes.withIndex()) {
            (p.parent as? android.view.ViewGroup)?.removeView(p)
            val w = if (panes.size == 1) 1f else if (i == 0) ratio else 1f - ratio
            box.addView(p, if (vertical) lp(MP, 0, w) else lp(0, MP, w))
            if (i == 0 && panes.size > 1) {
                val div = FrameLayout(act); div.setBackgroundColor(Th.sep)
                val knob = View(act); knob.background = rr(Th.sub, dpf(3)); div.addView(knob, if (vertical) flp(dp(36), dp(4), Gravity.CENTER) else flp(dp(4), dp(36), Gravity.CENTER))
                div.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP) {
                        val loc = IntArray(2); box.getLocationOnScreen(loc)
                        ratio = (if (vertical) (e.rawY - loc[1]) / max(1, box.height) else (e.rawX - loc[0]) / max(1, box.width)).coerceIn(0.2f, 0.8f)
                        (panes[0].layoutParams as LinearLayout.LayoutParams).weight = ratio; (panes[1].layoutParams as LinearLayout.LayoutParams).weight = 1f - ratio; box.requestLayout()
                        if (e.actionMasked == MotionEvent.ACTION_UP) saveSession()
                    }
                    true
                }
                box.addView(div, if (vertical) lp(MP, dp(10)) else lp(dp(10), MP))
            }
        }
        for (p in panes) p.refreshChip()
    }

    /* ---------- session ---------- */
    fun saveSession() {
        val a = JSONArray(); for (p in panes) p.note?.let { a.put(it.id) }
        P.set("session", JSONObject().put("ids", a).put("v", vertical).put("r", ratio.toDouble()).put("f", panes.indexOf(focused)).toString(), false)
    }
    fun restoreSession() {
        if (!P.b("lib.resume")) return
        try {
            val o = JSONObject(P.s("session").ifEmpty { return }); val ids = o.getJSONArray("ids")
            val max = if (P.b("split.keep")) 2 else 1
            for (i in 0 until minOf(ids.length(), max)) {
                val id = ids.getString(i); val m = Lib.note(id) ?: continue; if (m.locked || m.trashed != 0L) continue
                val n = Store.openNote(id) ?: continue; val p = Pane(this); panes.add(p); p.open(n)
            }
            if (panes.isEmpty()) return
            vertical = o.optBoolean("v"); ratio = o.optDouble("r", 0.5).toFloat().coerceIn(0.2f, 0.8f)
            focused = panes.getOrNull(o.optInt("f", 0)) ?: panes[0]
            for (p in panes) p.note?.let { if (it.id !in tabs) tabs.add(it.id) }
            layoutPanes(); showHome(); refreshTop(); bar.rebuild()
        } catch (e: Exception) { }
    }

    /* ---------- saving ---------- */
    private val saveR = Runnable { doSave() }
    /** "Auto-save every 3 min": a full save on a clock, on top of the save that follows every pause in writing */
    private val auto3 = object : Runnable { override fun run() { if (P.b("doc.auto3") && panes.isNotEmpty()) doSave(); root.postDelayed(this, 180000) } }
    init { root.postDelayed(auto3, 180000) }
    private var savedPill: TextView? = null
    private val hideSaved = Runnable { savedPill?.animate()?.alpha(0f)?.setDuration(400)?.start() }
    private fun showSaved() {
        if (!P.b("ui.saved") || panes.isEmpty()) return
        var t = savedPill
        if (t == null) { t = txt(act, "Saved ✓", 12f, Th.text, true); t.setPadding(dp(12), dp(5), dp(12), dp(5)); t.elevation = dpf(3); root.addView(t, flp(WC, WC, Gravity.TOP or Gravity.END).apply { topMargin = dp(62); rightMargin = dp(16) }); savedPill = t }
        t.background = rr(Th.glass, dpf(100), Th.hair, 1); t.setTextColor(Th.text); t.animate().cancel(); t.alpha = 1f; t.bringToFront()
        root.removeCallbacks(hideSaved); root.postDelayed(hideSaved, 900)
    }
    private fun saveNote(p: Pane) { val n = p.note ?: return; p.view.saveViewport(); val id = n.id; val had = n.pages.any { it.dirty }; Store.save(n) { Store.snapshot(id); if (had) Store.main { showSaved() } } }
    fun doSave() {
        root.removeCallbacks(saveR)
        if (panes.any { it.view.busy }) { root.postDelayed(saveR, 700); return }
        for (p in panes) saveNote(p)
    }
    fun flush() { root.removeCallbacks(saveR); commitText(); for (p in panes) saveNote(p) }

    /* ---------- callbacks from the note views ---------- */
    fun onDocChanged(p: Pane) { root.removeCallbacks(saveR); root.postDelayed(saveR, (P.f("doc.autosave") * 1000).toLong().coerceAtLeast(400)) }
    fun onViewChanged(p: Pane) { if (p.view.sel != null) p.updateSelBar(); p.setPage(pageLabel(p)); act.lib.pageMoved(p) }
    fun onSelection(p: Pane) { p.updateSelBar(); if (p === focused) bar.rebuild() }

    /** a short typed note beside the selection */
    fun comment(p: Pane) {
        val s = p.view.sel ?: return; val n = p.note ?: return; val i = s.page; val b = RectF(s.bounds); val pg = n.pages.getOrNull(i) ?: return
        prompt("Comment", "", "Write a comment") { t ->
            if (t.isEmpty()) return@prompt
            val w = 150f; var x = b.right + 8f; if (x + w > pg.w - 4f) x = max(4f, b.left - w - 8f)
            p.view.commitAdd(i, listOf(TextItem(t, x, b.top.coerceIn(2f, max(2f, pg.h - 20f)), w, P.f("ls.commentSize"), P.i("ls.comment") or col(0xFF000000), false)))
        }
    }

    /** screenshot tool: the dragged rectangle of a page (PDF included) becomes an image */
    fun onShot(p: Pane, i: Int, r: RectF, sx: Float, sy: Float) {
        val n = p.note ?: return; val pg = n.pages.getOrNull(i) ?: return; val items = ArrayList(pg.items)
        val q = P.s("shot.q").toFloatOrNull() ?: 2f
        Store.work {
            val s = minOf(q * 1.5f, kotlin.math.sqrt(8e6f / max(1f, r.width() * r.height())))
            val bmp = android.graphics.Bitmap.createBitmap(max(1, (r.width() * s).toInt()), max(1, (r.height() * s).toInt()), android.graphics.Bitmap.Config.ARGB_8888)
            Render.page(bmp, n, pg, s, r.left * s, r.top * s, items, null, InkPainter())
            val name = "img_" + newId() + ".png"; val f = java.io.File(n.dir, name)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
            val item = ImageItem(name, RectF(r))
            Store.main {
                val toShelf = { Shelf.add(n, listOf(item)); toast("Saved to Shelf") }
                val copy = { Clip.items = listOf(item); Clip.fromNote = n.id; toast("Copied — hold a finger on a page to paste") }
                val share = { val out = java.io.File(ShareProvider.dir(act), "capture-" + System.currentTimeMillis() + ".png"); f.copyTo(out, true); act.share(out, "image/png") }
                when (P.s("shot.after")) {
                    "shelf" -> toShelf(); "copy" -> copy(); "share" -> share()
                    else -> { val loc = paneXY(p); showMenu(loc[0] + sx.toInt(), loc[1] + sy.toInt(), listOf(MI("shelf", "Save to Shelf") { toShelf() }, MI("copy", "Copy") { copy() }, MI("share", "Share…") { share() },
                        MI("image", "Place on this page") { Tools.set("lasso"); p.view.insertAt(listOf(ImageItem(name, RectF(0f, 0f, r.width(), r.height()))), sx, sy) })) }
                }
            }
        }
    }
    fun onUndoState() { bar.updateUndo() }
    fun onLink(p: Pane, l: LinkItem, sx: Float, sy: Float) { val loc = paneXY(p); LinksUi.tapped(this, p, l, loc[0] + sx.toInt(), loc[1] + sy.toInt()) }
    fun audioWantsTap(): Boolean = audio.wantsTap()
    fun audioSeek(p: Pane, s: Stroke): Boolean = audio.seekTo(p, s)
    private fun paneXY(p: Pane): IntArray { val a = IntArray(2); p.getLocationInWindow(a); val b = IntArray(2); act.root.getLocationInWindow(b); return intArrayOf(a[0] - b[0], a[1] - b[1]) }
    fun onLongPress(p: Pane, sx: Float, sy: Float) {
        val loc = paneXY(p); val v = p.view; val i = v.pageAt(sx, sy, true)
        if (i >= 0 && P.s("lk.trig") == "long") { val l = v.linkAt(i, v.px(i, sx), v.py(i, sy)); if (l != null) { onLink(p, l, sx, sy); return } }
        val items = ArrayList<MI?>()
        if (Clip.items.isNotEmpty()) items.add(MI("paste", "Paste") { v.pasteAt(sx, sy) })
        if (i >= 0) {
            items.add(MI("text", "Add text here") { editText(p, i, null, v.px(i, sx), v.py(i, sy)) })
            items.add(MI("lasso", "Select everything on this page") { val pg = p.note!!.pages[i]; if (pg.items.isNotEmpty()) { Tools.set("lasso"); v.select(i, ArrayList(pg.items)) } })
            items.add(MI("noteplus", "Add a page after this one") { addPage(p, i) })
        }
        items.add(MI("image", "Insert image") { act.pickImage() })
        showMenu(loc[0] + sx.toInt(), loc[1] + sy.toInt(), items)
    }

    /* ---------- text boxes ---------- */
    private var txEdit: EditText? = null; private var txPane: Pane? = null; private var txPage = 0; private var txOld: TextItem? = null; private var txX = 0f; private var txY = 0f
    private var txSize = 14f; private var txColor = 0; private var txBold = false; private var txBar: View? = null
    fun editText(p: Pane, i: Int, existing: TextItem?, x: Float, y: Float) {
        commitText()
        val v = p.view; val sc = v.scale; val n = p.note ?: return
        val e = EditText(act)
        val size = existing?.size ?: P.f("tx.size"); val color = existing?.color ?: P.i("tx.color"); val bold = existing?.bold ?: P.b("tx.bold")
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * sc); e.setTextColor(color or col(0xFF000000)); e.setText(existing?.text ?: ""); e.setSelection(e.text.length)
        e.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        val paper = n.pages[i].let { if (it.src != null) -1 else it.paperColor }
        e.background = rr(paper, dpf(4), Th.accent, dp(1.5f)); e.setPadding(dp(4), dp(4), dp(4), dp(4)); e.includeFontPadding = false
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; e.gravity = Gravity.TOP or Gravity.START
        val w = (existing?.w ?: P.f("tx.width")) * sc; val x0 = existing?.x ?: x; val y0 = existing?.y ?: y
        val p2 = flp(w.toInt() + dp(8), WC); p2.leftMargin = v.sx(i, x0).toInt() - dp(4); p2.topMargin = v.sy(i, y0).toInt() - dp(4)
        p.addView(e, p2)
        txEdit = e; txPane = p; txPage = i; txOld = existing; txX = x0; txY = y0
        txSize = size; txColor = color; txBold = bold
        // small format bar above the box: size, bold, colour
        val fb = hbox(act); fb.background = rr(Th.bg, dpf(16), Th.hair, 1); fb.elevation = dpf(8); fb.setPadding(dp(4), 0, dp(4), 0)
        fun restyle() { e.setTextSize(TypedValue.COMPLEX_UNIT_PX, txSize * sc); e.setTextColor(txColor or col(0xFF000000)); e.typeface = Typeface.create(Typeface.DEFAULT, if (txBold) Typeface.BOLD else Typeface.NORMAL) }
        fun tbtn(label: String, bold2: Boolean, on: (View) -> Unit): TextView { val t = txt(act, label, 15f, Th.text, bold2); t.gravity = Gravity.CENTER; t.fx(17f); t.setOnClickListener { on(it) }; fb.addView(t, lp(dp(38), dp(36))); return t }
        tbtn("A−", false) { txSize = (txSize - 1f).coerceAtLeast(6f); restyle() }
        tbtn("A+", false) { txSize = (txSize + 1f).coerceAtMost(72f); restyle() }
        val bb = tbtn("B", true) { }; bb.setTextColor(if (txBold) Th.accent else Th.text); bb.setOnClickListener { txBold = !txBold; bb.setTextColor(if (txBold) Th.accent else Th.text); restyle() }
        val sw = Swatch(act, txColor or col(0xFF000000), false); sw.fx(17f); sw.setOnClickListener { v2 -> colorPicker(v2, txColor or col(0xFF000000)) { nc -> txColor = nc; restyle() } }; fb.addView(sw, lp(dp(38), dp(36)))
        fb.addView(ibtn(act, "check", 38, Th.accent, 9) { commitText() })
        val p3 = flp(WC, dp(36)); p3.leftMargin = max(dp(4), p2.leftMargin); p3.topMargin = max(dp(4), p2.topMargin - dp(44)); p.addView(fb, p3); txBar = fb
        e.requestFocus(); e.post { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0) }
    }
    fun commitText() {
        val e = txEdit ?: return; val p = txPane ?: return
        txEdit = null
        val s = e.text.toString().trimEnd(); val old = txOld
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0)
        p.removeView(e)
        txBar?.let { p.removeView(it) }; txBar = null
        if (old != null) { if (s.isEmpty()) p.view.replaceItem(txPage, old, null) else if (s != old.text || txSize != old.size || txColor != old.color || txBold != old.bold) p.view.replaceItem(txPage, old, TextItem(s, old.x, old.y, old.w, txSize, txColor, txBold)) }
        else if (s.isNotEmpty()) p.view.commitAdd(txPage, listOf(TextItem(s, txX, txY, P.f("tx.width"), txSize, txColor, txBold)))
    }

    /* ---------- pages ---------- */
    fun addPage(p: Pane, after: Int, page: Page? = null) {
        val n = p.note ?: return; val like = n.pages.getOrNull(after)
        val pg = page ?: Store.newPage(like); n.pages.add((after + 1).coerceIn(0, n.pages.size), pg); pg.dirty = true
        p.view.structureChanged(); p.view.post { p.view.scrollToPage(n.pages.indexOf(pg)) }
    }
    fun deletePage(p: Pane, i: Int) {
        val n = p.note ?: return; if (n.pages.size <= 1) { toast("A note needs at least one page"); return }
        val pg = n.pages.removeAt(i); p.view.structureChanged()
        val nid = n.id; Store.io { Store.inkFile(nid, pg.id).delete(); Store.thumbFile(nid, pg.id).delete() }
    }
    fun insertImage(file: String, w: Float, h: Float) {
        val p = focused ?: return; val pg = p.note?.pages?.getOrNull(p.view.curPage()) ?: return
        val k = minOf(1f, pg.w * 0.6f / w, pg.h * 0.6f / h)
        Tools.set("lasso"); p.view.insertAt(listOf(ImageItem(file, RectF(0f, 0f, w * k, h * k))), p.view.width / 2f, p.view.height / 2f)
    }

    /* ---------- selection helpers used by the toolbar ---------- */
    fun selToShelf() { val p = focused ?: return; val s = p.view.sel ?: return; val n = p.note ?: return
        if (P.s("sh.what") == "image") {   // a picture of the area, with the PDF / paper under the ink
            val r = RectF(s.bounds); s.m.mapRect(r)
            p.view.areaImage(s.page, r) { name, rect -> Shelf.add(n, listOf(ImageItem(name, rect))); toast("Saved to Shelf"); if (P.b("sh.openAfter")) ShelfUi.open(this) }
            return }
        Shelf.add(n, s.items); toast("Saved to Shelf"); if (P.b("sh.openAfter")) ShelfUi.open(this) }
    fun selLink() { val p = focused ?: return; if (p.view.sel != null) LinksUi.create(this, p) }
    fun selExport() { val p = focused ?: return; val s = p.view.sel ?: return; Io.shareItems(p.note!!, s.items) }

    /* ---------- top bar ---------- */
    private fun tb(icon: String, tint: Int = Th.accent, on: (View) -> Unit): View = ibtn(act, icon, 40, tint, 9, on)
    fun refreshTop() {
        refreshTabs()
        top.removeAllViews(); top.setPadding(dp(12), 0, dp(12), 0)
        val p = focused; val n = p?.note; val m = n?.meta
        if (p != null) top.addView(tb("back") { goHome() })
        top.addView(tb("sidebar") { act.lib.toggle() })
        if (p == null || n == null || m == null) { pageChip = null; titleView = null; return }
        val title = txt(act, m.title, 17f, Th.text, true).oneLine(); title.gravity = Gravity.CENTER; title.setPadding(dp(8), dp(8), dp(8), dp(8)); title.fx()
        title.setOnClickListener { prompt("Rename", m.title) { s -> if (s.isNotEmpty()) { m.title = s; Lib.changed(); refreshTop(); p.refreshChip() } } }
        top.addView(title, lp(0, WC, 1f)); titleView = title
        // the original's order: record, recordings, shelf, links, search, split, export, paper, add page.
        // (priority, action): the lowest priorities fold into a “more” button only when the bar is too narrow (split view)
        val acts = listOf(
            5 to MI("mic", "Record") { audio.micTap() }, 8 to MI("phones", "Recordings") { audio.recordings() }, 4 to MI("snip", "Snippet shelf") { ShelfUi.toggle(this) },
            7 to MI("link", "Links & backlinks") { LinksUi.panel(this) }, 3 to MI("search", "Search") { SearchUi.open(this) }, 2 to MI("split", "Split view") { OpenBeside.open(this) },
            6 to MI("share", "Export & share") { Io.quickExport(n, p.view.curPage()) }, 9 to MI("paperl", "Paper") { PaperUi.open(this, p) }, 1 to MI("addpg", "Add page") { addPage(p, p.view.curPage()) })
            .filter { (_, a) -> when (a.icon) { "mic", "phones" -> P.b("ui.audioBtns"); "split" -> P.b("ui.splitBtn"); "share" -> P.b("ui.shareBtn"); else -> true } }
        val wdp = (if (top.width > 0) top.width else act.root.width) / DENS
        val fixed = 40 + 40 + 150 + 24
        val slots = ((wdp - fixed) / 40f).toInt()
        val fits = slots >= acts.size
        for ((pr, a) in acts) if (fits || pr <= slots - 1) { val b = tb(a.icon, if (a.icon == "mic" && audio.recording) Th.danger else Th.accent) { a.run() }
            if (a.icon == "share") b.setOnLongClickListener { Io.exportDialog(n, p.view.curPage()); true }   // hold: every export choice
            top.addView(b) }
        if (!fits) { val extra = acts.filter { it.first > slots - 1 }.map { it.second }; top.addView(tb("more") { v -> showMenu(v, extra) }) }
        lastTopW = top.width
        onViewChanged(p)
    }
    private var lastTopW = 0
    init { top.addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ -> if (r - l != orr - ol && r - l != lastTopW) top.post { refreshTop() } } }
    /** the page counter: "Page 3 / 12 · 160%". 100% is the original's page at its natural size. */
    fun pageLabel(p: Pane): String = "Page ${p.view.curPage() + 1} / ${p.view.count()} · ${Math.round(p.view.scale / DENS * 595f / 794f * 100f)}%"
}
