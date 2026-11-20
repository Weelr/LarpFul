package com.weelr.larpful2

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.format.DateUtils
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.min

/** A hierarchical list of folders + notes with page previews. Used by the sidebar, "Open Beside", move and link pickers. */
class Browser(val showNotes: Boolean, val grid: Boolean = false, val onNote: (NoteMeta, View) -> Unit) {
    var folder: String? = null
    var trash = false
    var onLong: ((Any, View) -> Unit)? = null
    var onNavigate: (() -> Unit)? = null
    var exclude: String? = null       // folder that cannot be entered (when moving a folder into itself)
    var flat: String? = null          // "pin" = pinned notebooks, "unf" = notebooks outside every folder (flat lists of the sidebar)
    var query = ""                    // home search box: notebooks whose name contains this
    var selecting: HashSet<String>? = null   // select mode of the home screen
    var onSelChange: (() -> Unit)? = null
    var cards = false                 // home grid: pin / delete buttons under every notebook, as in the original
    /** home search: notebooks that contain the words (first place found in each), next to the ones matching by name */
    val inside = LinkedHashMap<String, Search.Hit>()
    val rows = ArrayList<Any>()
    private val adapter = Ad()
    val list: AbsListView = if (grid) GridView(act).apply { numColumns = GridView.AUTO_FIT; columnWidth = dp(190); stretchMode = GridView.STRETCH_COLUMN_WIDTH; horizontalSpacing = dp(26); verticalSpacing = dp(18); setPadding(dp(24), dp(14), dp(24), dp(120)); clipToPadding = false }
        else ListView(act).apply { divider = null; setPadding(0, dp(2), 0, dp(28)); clipToPadding = false }

    init {
        list.adapter = adapter; list.isVerticalScrollBarEnabled = false; list.selector = android.graphics.drawable.ColorDrawable(0)
        refresh()
    }
    fun open(f: String?) {
        val fo = Lib.folder(f)
        if (fo != null && fo.locked && !act.unlocked.contains(fo.id)) { act.askPin { act.unlocked.add(fo.id); open(f) }; return }
        folder = f; trash = false; flat = null; refresh(); onNavigate?.invoke()
    }
    fun refresh() {
        rows.clear()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) { rows.addAll(Lib.sorted((if (trash) Lib.trash() else Lib.notes.filter { it.trashed == 0L }).filter { it.title.lowercase().contains(q) }))
            if (!trash) { val have = rows.mapTo(HashSet()) { (it as NoteMeta).id }; for (h in inside.values) if (h.note.id !in have && h.note.trashed == 0L && Lib.note(h.note.id) != null) rows.add(h.note) } }
        else if (trash) rows.addAll(Lib.trash().sortedByDescending { it.trashed })
        else if (flat == "pin") rows.addAll(Lib.sorted(Lib.notes.filter { it.trashed == 0L && it.pinned }))
        else if (flat == "unf") rows.addAll(Lib.sorted(Lib.childrenNotes(null)))
        else {
            val fs = Lib.childrenFolders(folder).filter { it.id != exclude }.let { l -> if (P.s("lib.sort") == "manual") l.sortedBy { it.name.lowercase() }.sortedBy { it.ord } else l.sortedBy { it.name.lowercase() }.sortedByDescending { it.pinned } }
            val ns = if (showNotes) Lib.sorted(Lib.childrenNotes(folder)) else emptyList()
            if (P.b("lib.foldersFirst")) { rows.addAll(fs); rows.addAll(ns) } else { rows.addAll(ns); rows.addAll(fs) }
        }
        adapter.notifyDataSetChanged()
    }

    private class Holder(val root: View, val row: LinearLayout, val thumb: ThumbView, val ficon: ImageView?, val tile: FolderTile?, val title: TextView, val sub: TextView, val badge: ImageView, val badge2: ImageView) { var key = ""; var obj: Any? = null; var acts: LinearLayout? = null; var chip: TextView? = null; var check: ImageView? = null }

    /* hold a row, then drag it onto a folder (or onto the back arrow to move it up a level) */
    var upTarget: View? = null
    private var armed: Any? = null; private var dragV: ImageView? = null; private var dragOver: View? = null; private var dropTo: Any? = null
    private var rawX = 0f; private var rawY = 0f; private var ax = 0f; private var ay = 0f
    private fun canDrag(): Boolean = onLong != null && !trash && showNotes && P.b("lib.drag")
    private var dropAt: Pair<Any, Boolean>? = null   // reorder: the item it lands next to, and on which side
    private val dropBar: View by lazy { View(act).apply { background = rr(Th.accent, dpf(2.5f)); elevation = dpf(120) } }
    private fun endDrag() { dragV?.let { act.root.removeView(it) }; dragV = null; dragOver?.background = null; dragOver = null; dropTo = null; dropAt = null; (dropBar.parent as? ViewGroup)?.removeView(dropBar) }
    private fun hover(x: Float, y: Float, o: Any) {
        var over: View? = null; var target: Any? = null; val a = IntArray(2); var reo: Pair<Any, Boolean>? = null; var bx = 0; var by = 0; var bh = 0
        val canReo = cards && P.b("lib.reorder") && flat == null && query.isBlank()
        for (k in 0 until list.childCount) {
            val ch = list.getChildAt(k); val h = ch.tag as? Holder ?: continue; val it = h.obj ?: continue
            if (it === o) continue
            ch.getLocationOnScreen(a); if (!(x >= a[0] && x <= a[0] + ch.width && y >= a[1] && y <= a[1] + ch.height)) continue
            val rel = (x - a[0]) / ch.width.coerceAtLeast(1)
            // onto a folder = into it; beside a notebook (or at the edge of a folder, for folders) = change the order
            if (it is Folder && !(o is Folder && Lib.isDescendant(it.id, o.id)) && (!canReo || o !is Folder || (rel > 0.28f && rel < 0.72f))) { over = h.row; target = it }
            else if (canReo && ((it is NoteMeta && o is NoteMeta) || (it is Folder && o is Folder))) { val before = rel < 0.5f; reo = Pair(it, before); bx = if (before) a[0] else a[0] + ch.width; by = a[1]; bh = ch.height }
        }
        dropAt = if (target == null) reo else null
        if (dropAt != null) { val r = IntArray(2); act.root.getLocationOnScreen(r)
            if (dropBar.parent == null) act.root.addView(dropBar, flp(dp(5), bh))
            dropBar.layoutParams = flp(dp(5), bh); dropBar.translationX = (bx - r[0] - dp(2)).toFloat(); dropBar.translationY = (by - r[1]).toFloat()
        } else (dropBar.parent as? ViewGroup)?.removeView(dropBar)
        val up = upTarget
        if (target == null && up != null && up.isShown && folder != null) { up.getLocationOnScreen(a); if (x >= a[0] - dp(10) && x <= a[0] + up.width + dp(60) && y >= a[1] - dp(10) && y <= a[1] + up.height + dp(10)) { over = up; target = "up" } }
        if (over !== dragOver) { dragOver?.background = null; over?.background = rr(Th.sel, dpf(12), Th.accent, dp(2)); dragOver = over }
        dropTo = target
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun wire(root: View, h: Holder, o: Any) {
        root.setOnLongClickListener { v -> if (canDrag()) { armed = o; ax = rawX; ay = rawY; v.parent?.requestDisallowInterceptTouchEvent(true); true } else { onLong?.invoke(o, v); onLong != null } }
        root.setOnTouchListener { v, e ->
            rawX = e.rawX; rawY = e.rawY
            if (armed !== o) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragV == null && kotlin.math.hypot(e.rawX - ax, e.rawY - ay) > dp(10)) {
                        v.isPressed = false
                        val d = ImageView(act); val tb = h.thumb.bmp
                        if (tb != null && o is NoteMeta) { d.setImageBitmap(tb); d.scaleType = ImageView.ScaleType.CENTER_CROP } else { d.setImageDrawable(Icon(if (o is Folder) "folderf" else "note", if (o is Folder) Th.tint else Th.sub)); d.setPadding(dp(14), dp(20), dp(14), dp(20)) }
                        d.background = rr(Th.card, dpf(12)); d.clipToOutline = true; d.alpha = 0.92f; d.elevation = dpf(14); d.rotation = -3f
                        act.root.addView(d, flp(dp(84), dp(112))); dragV = d
                    }
                    dragV?.let { val r = IntArray(2); act.root.getLocationOnScreen(r); it.translationX = e.rawX - r[0] - dp(42); it.translationY = e.rawY - r[1] - dp(70); hover(e.rawX, e.rawY, o) }
                }
                MotionEvent.ACTION_UP -> {
                    val dragging = dragV != null; val t = dropTo; val ra = dropAt; armed = null; v.isPressed = false; endDrag()
                    if (!dragging) onLong?.invoke(o, v)
                    else if (t == null && ra != null) reorder(o, ra.first, ra.second)
                    else if (t != null) {
                        val dest: String? = if (t is Folder) t.id else Lib.folder(folder)?.parent
                        if (o is NoteMeta) o.folder = dest else if (o is Folder) o.parent = dest
                        Lib.changed(); toast("Moved to " + (Lib.folder(dest)?.name ?: "Notes"))
                    } else refresh()
                }
                MotionEvent.ACTION_CANCEL -> { armed = null; v.isPressed = false; endDrag() }
            }
            true
        }
    }

    /** "My own order": the dragged notebook / folder takes the place it was dropped at, the others keep their sequence */
    private fun reorder(o: Any, next: Any, before: Boolean) {
        if (o is NoteMeta) { val l = ArrayList(rows.filterIsInstance<NoteMeta>()); l.remove(o); var i = l.indexOf(next); if (i < 0) return; if (!before) i++; l.add(i, o); l.forEachIndexed { k, m -> m.ord = (k + 1).toDouble() } }
        else if (o is Folder) { val l = ArrayList(rows.filterIsInstance<Folder>()); l.remove(o); var i = l.indexOf(next); if (i < 0) return; if (!before) i++; l.add(i, o); l.forEachIndexed { k, m -> m.ord = (k + 1).toDouble() } }
        else return
        if (P.s("lib.sort") != "manual") { P.set("lib.sort", "manual", false); toast("Your own order is on (the sort button changes it back)") }
        Lib.changed()
    }
    private inner class Ad : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val h: Holder = (cv?.tag as? Holder) ?: make()
            bind(h, rows[i]); return h.root
        }
    }

    private fun make(): Holder {
        val c = act
        val thumb = ThumbView(c)
        val title = txt(c, "", if (grid) 15f else 16f, Th.text, true); title.maxLines = if (grid) 2 else 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        val sub = txt(c, "", 13f, Th.sub).oneLine()
        val badge = ImageView(c); val badge2 = ImageView(c)
        val root = FrameLayout(c); val row: LinearLayout; var ficon: ImageView? = null; var tile: FolderTile? = null
        if (grid) {
            row = vbox(c); row.gravity = Gravity.CENTER_HORIZONTAL; row.setPadding(dp(2), dp(4), dp(2), dp(8))
            val th = FrameLayout(c); tile = FolderTile(c); th.addView(thumb, flp(MP, MP)); th.addView(tile, flp(MP, MP))
            val bx = hbox(c); bx.addView(badge, lp(dp(18), dp(18))); bx.addView(badge2, lp(dp(18), dp(18)).apply { leftMargin = dp(3) })
            th.addView(bx, flp(WC, WC, Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
            row.addView(th, lp(MP, dp(240)))
            title.gravity = Gravity.CENTER; sub.gravity = Gravity.CENTER
            row.addView(title, lp(MP, WC).apply { topMargin = dp(10) }); row.addView(sub, lp(MP, WC).apply { topMargin = dp(3) })
            root.addView(row, flp(MP, WC))
            if (cards) {
                val chip = txt(c, "", 12f, Th.sub).oneLine(); chip.background = rr(Th.fill, dpf(100)); chip.setPadding(dp(9), dp(3), dp(9), dp(3)); row.addView(chip, lp(WC, WC).apply { topMargin = dp(4) })
                val acts = hbox(c); acts.gravity = Gravity.CENTER; row.addView(acts, lp(WC, dp(34)).apply { topMargin = dp(2) })
                val check = ImageView(c); th.addView(check, flp(dp(28), dp(28), Gravity.TOP or Gravity.START).apply { topMargin = dp(8); leftMargin = dp(8) })
                root.tag = Triple(acts, chip, check)
            }
        } else {
            val size = P.s("lib.row"); val tw = if (size == "compact") 44 else if (size == "large") 70 else 54; val thh = if (size == "compact") 56 else if (size == "large") 92 else 70
            row = hbox(c); row.setPadding(dp(8), dp(4), dp(10), dp(4))
            val slot = FrameLayout(c); ficon = ImageView(c); ficon.setPadding(dp(8), dp(8), dp(8), dp(8))
            slot.addView(thumb, flp(MP, MP)); slot.addView(ficon, flp(MP, MP))
            row.addView(slot, lp(dp(tw), dp(thh)))
            val col = vbox(c); col.addView(title); col.addView(sub, lp(WC, WC).apply { topMargin = dp(3) })
            row.addView(col, lp(0, WC, 1f).apply { leftMargin = dp(8) })
            row.addView(badge, lp(dp(17), dp(17))); row.addView(badge2, lp(dp(17), dp(17)).apply { leftMargin = dp(4) })
            root.setPadding(dp(8), dp(1), dp(8), dp(1)); root.addView(row, flp(MP, WC))
        }
        root.layoutParams = AbsListView.LayoutParams(MP, WC)
        val extra = root.tag as? Triple<*, *, *>
        val h = Holder(root, row, thumb, ficon, tile, title, sub, badge, badge2)
        if (extra != null) { h.acts = extra.first as LinearLayout; h.chip = extra.second as TextView; h.check = extra.third as ImageView }
        root.tag = h; return h
    }

    private fun bind(h: Holder, o: Any) {
        val row = h.row
        h.badge.setImageDrawable(null); h.badge2.setImageDrawable(null)
        row.background = null; row.fx(12f)
        h.title.setTextColor(Th.text); h.sub.setTextColor(Th.sub)
        h.acts?.let { it.removeAllViews(); it.visibility = View.GONE }; h.chip?.visibility = View.GONE; h.check?.visibility = View.GONE; h.sub.visibility = View.VISIBLE
        when (o) {
            is Folder -> {
                val key = "f" + o.id + "/" + Thumbs.gen + "/" + Lib.rev; h.key = key; h.obj = o; h.title.text = o.name
                val cnt = Lib.childrenNotes(o.id).size + Lib.childrenFolders(o.id).size
                h.sub.text = if (cnt == 1) "1 item" else "$cnt items"
                h.thumb.visibility = View.GONE
                if (grid) { h.tile?.visibility = View.VISIBLE; h.tile?.bind(o) }
                else {
                    h.ficon?.visibility = View.VISIBLE; h.ficon?.setImageDrawable(Icon(if (o.locked) "lock" else "folderf", Th.tint))
                    h.badge2.setImageDrawable(Icon("chev", Th.sub))
                }
                if (o.pinned) h.badge.setImageDrawable(Icon("pinf", Th.tint))
                row.setOnClickListener { open(o.id) }
                wire(row, h, o)
            }
            is NoteMeta -> {
                val key = "n" + o.id + "/" + o.modified + "/" + o.locked + P.b("lib.thumbs") + (if (o.cover) "c" + Thumbs.gen else "") + (if (o.first == null) Thumbs.gen else 0)
                val same = h.key == key; h.key = key; h.obj = o; h.title.text = o.title
                val date = DateUtils.getRelativeTimeSpanString(if (trash) o.trashed else o.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                h.sub.text = "$date · ${o.pages} " + (if (o.pages == 1) "page" else "pages")
                h.tile?.visibility = View.GONE; h.ficon?.visibility = View.GONE; h.thumb.visibility = View.VISIBLE
                h.thumb.book = o.cover && !o.locked; h.thumb.label = if (o.cover && grid && !o.locked) o.title else null
                if (o.locked) h.thumb.show(null, "lock")
                else if (!P.b("lib.thumbs")) h.thumb.show(null, "note")
                else if (!same || h.thumb.bmp == null) { if (!same) h.thumb.show(null, null); Thumbs.cover(o) { b -> if (h.key == key) h.thumb.show(b, if (b == null) "note" else null) } }
                else h.thumb.invalidate()
                if (o.pinned) h.badge.setImageDrawable(Icon("pinf", Th.tint))
                if (o.locked) h.badge2.setImageDrawable(Icon("lock", Th.sub))
                if (act.ed.isOpen(o.id) && !grid) row.background = rr(Th.sel, dpf(12))
                val sel = selecting
                val acts = h.acts
                if (acts != null) {
                    // the original's card: name, then pin and delete (restore + delete forever in Trashed)
                    h.sub.visibility = View.GONE; acts.visibility = View.VISIBLE
                    fun ab(icon: String, tint: Int, on: () -> Unit) { acts.addView(ImageView(act).apply { setImageDrawable(Icon(icon, tint)); setPadding(dp(8), dp(8), dp(8), dp(8)); fx(17f); setOnClickListener { on() } }, lp(dp(34), dp(34)).apply { leftMargin = dp(3); rightMargin = dp(3) }) }
                    ab(if (o.pinned) "pinf" else "pin", if (o.pinned) Th.tint else Th.accent) { o.pinned = !o.pinned; toast(if (o.pinned) "Pinned" else "Unpinned"); Lib.changed() }
                    if (o.trashed != 0L) {
                        ab("undo", Th.accent) { o.trashed = 0; if (Lib.folder(o.folder) == null) o.folder = null; Lib.changed() }
                        ab("trash", Th.accent) { confirm("Delete “${o.title}” forever?", "This cannot be undone.", "Delete") { act.lib.purge(o) } }
                    } else ab("trash", Th.accent) { act.ed.closeNote(o.id); o.trashed = System.currentTimeMillis(); o.pinned = false; Lib.changed(); toast("Moved to Trashed") }
                    val tag = Lib.folder(o.folder)?.name
                    val hit = if (query.isNotBlank()) inside[o.id] else null
                    if (hit != null && !o.title.lowercase().contains(query.trim().lowercase())) { h.chip?.text = "Page ${hit.pageNo} · " + hit.snippet.replace('\n', ' ').trim().take(26); h.chip?.visibility = View.VISIBLE }
                    else if (tag != null && (flat != null || trash || query.isNotBlank())) { h.chip?.text = tag; h.chip?.visibility = View.VISIBLE }
                    if (sel != null) { h.check?.visibility = View.VISIBLE; h.check?.setImageDrawable(Icon(if (o.id in sel) "selc" else "dashc", if (o.id in sel) Th.accent else Th.sub, 2f)); h.badge.setImageDrawable(null); h.badge2.setImageDrawable(null) }
                }
                if (sel != null && h.acts != null) { row.setOnClickListener { if (!sel.remove(o.id)) sel.add(o.id); adapter.notifyDataSetChanged(); onSelChange?.invoke() }; row.setOnLongClickListener(null); row.setOnTouchListener(null) }
                else { row.setOnClickListener { onNote(o, it) }; wire(row, h, o) }
            }
            else -> {
                h.key = "trash"; h.obj = null; row.setOnTouchListener(null); h.title.text = "Recently Deleted"; h.sub.text = "${Lib.trash().size} notes"
                h.thumb.visibility = View.GONE; h.tile?.visibility = View.GONE
                if (grid) { h.thumb.visibility = View.VISIBLE; h.thumb.book = false; h.thumb.label = null; h.thumb.show(null, "trash") }
                else { h.ficon?.visibility = View.VISIBLE; h.ficon?.setImageDrawable(Icon("trash", Th.sub)); h.badge2.setImageDrawable(Icon("chev", Th.sub)) }
                row.setOnClickListener { trash = true; refresh(); onNavigate?.invoke() }
                row.setOnLongClickListener { false }
            }
        }
    }
    val title: String get() = if (trash) "Trashed" else if (flat == "pin") "Pinned" else if (flat == "unf") "Unfiled" else Lib.folder(folder)?.name ?: "Notebooks"
    fun up(): Boolean { if (trash || flat != null) { trash = false; flat = null; refresh(); onNavigate?.invoke(); return true }; val f = Lib.folder(folder) ?: return false; folder = f.parent; refresh(); onNavigate?.invoke(); return true }
    val canUp: Boolean get() = trash || flat != null || folder != null
}

/** The original's sidebar (Library filters, folder tree; pages + notebooks while a note is open) and the home grid. */
class Library {
    private class Row(val type: Int, val a: Any? = null, val s: String = "", val n: Int = 0, val depth: Int = 0)
    companion object { const val T_TOP = 0; const val T_HEAD = 1; const val T_PAGES = 2; const val T_NOTE = 3; const val T_ITEM = 4; const val T_HR = 5; const val T_FROOT = 6; const val T_FOLDER = 7; const val T_PGBAR = 8 }
    private var pageDrag = false

    val view = FrameLayout(act)
    var docked = true
    /** all | unt | unf | trash | pin | t:<folder id> — what the home grid shows, and the highlighted sidebar row */
    var filter = "all"
    val browser: Browser = Browser(true, true) { m, _ ->
        if (m.trashed != 0L) noteMenu(m, null)
        else { val br = act.lib.browser; val q = br.query; val hit = if (q.isNotBlank() && !m.title.lowercase().contains(q.trim().lowercase())) br.inside[m.id] else null
            if (hit != null) { act.ed.open(m.id, "replace", hit.pageId); SearchUi.mark(act.ed, m.id, hit.pageId, q.trim()) } else act.ed.open(m.id) } }
    private var qGen = 0
    private val side = ListView(act)
    private val rows = ArrayList<Row>()
    private val sideAd = SideAd()
    private var homeBox: FrameLayout? = null
    private var pending = false
    private var crumbs: LinearLayout? = null
    private var batch: LinearLayout? = null
    private var empty: TextView? = null
    private var lastCur = -1

    init {
        docked = P.b("lib.open")
        side.divider = null; side.isVerticalScrollBarEnabled = false; side.selector = android.graphics.drawable.ColorDrawable(0); side.setPadding(0, 0, 0, dp(24)); side.clipToPadding = false
        side.adapter = sideAd; view.addView(side, flp(MP, MP))
        browser.cards = true
        browser.onLong = { o, v -> if (o is NoteMeta) noteMenu(o, v) else if (o is Folder) folderMenu(o, v) }
        browser.onNavigate = {
            filter = if (browser.trash) "trash" else if (browser.flat == "pin") "pin" else if (browser.flat == "unf") (if (filter == "unt") "unt" else "unf") else browser.folder?.let { "t:$it" } ?: "all"
            buildSide(); homeTitle()
        }
        browser.onSelChange = { updateBatch() }
        Lib.listeners.add { if (!pending) { pending = true; act.root.post { pending = false; refresh() } } }
        applyTheme()
    }
    fun applyTheme() { view.setBackgroundColor(Th.bg); buildSide(); browser.refresh(); homeBox?.let { fillHome(it) } }
    fun refresh() { browser.refresh(); buildSide(); homeTitle(); updateBatch() }
    /** sidebar shown over an open note */
    var over = false
    var homeLogo: View? = null
    /** "Edit" in the Pages section: delete with ✕, undo, add a file (as in the original) */
    var pgEdit = false
    private val pgUndo = ArrayList<Triple<Note, Int, Page>>()
    fun toggle() {
        if (act.ed.panes.isNotEmpty()) over = !over else { docked = !docked; P.set("lib.open", docked, false) }
        act.layoutMain(); buildSide(); side.post { sideAd.notifyDataSetChanged() }; act.ed.refreshTop()
    }
    fun sideChanged() { if (!pageDrag) buildSide() }
    /** the page in view changed: move the highlight in the Pages section */
    fun pageMoved(p: Pane) { if (!over || pageDrag || !act.hasEd || p !== act.ed.focused) return; val c = p.view.curPage(); if (c != lastCur) { lastCur = c; sideAd.notifyDataSetChanged() } }

    private fun openSet(): Set<String> = P.s("lib.op").split(',').filter { it.isNotEmpty() }.toSet()
    private fun toggleOpen(id: String) { val s = openSet().toMutableSet(); if (!s.remove(id)) s.add(id); P.set("lib.op", s.joinToString(","), false) }
    private fun count(f: String): Int = when {
        f == "all" -> Lib.notes.count { it.trashed == 0L }; f == "unt" || f == "unf" -> Lib.childrenNotes(null).size; f == "trash" -> Lib.trash().size
        f == "pin" -> Lib.notes.count { it.trashed == 0L && it.pinned }; else -> Lib.notesUnder(f.substring(2)).size }

    private fun buildSide() {
        if (pageDrag) return
        rows.clear(); rows.add(Row(T_TOP))
        val ed = if (act.hasEd) act.ed else null; val p = ed?.focused; val cur = p?.note
        if (ed != null && p != null && cur != null) {
            lastCur = p.view.curPage()
            for (pn in ed.panes) { val n = pn.note ?: continue
                rows.add(Row(T_HEAD, pn, (if (ed.multi) (n.meta?.title ?: "Untitled") + " · " else "") + "Pages", n.pages.size))
                if (pgEdit && P.b("ui.pgMgr")) rows.add(Row(T_PGBAR, pn))
                var i = 0; while (i < n.pages.size) { rows.add(Row(T_PAGES, pn, "", i)); i += 2 } }
            val fold = cur.meta?.folder; val l = Lib.sorted(Lib.childrenNotes(fold))
            rows.add(Row(T_HEAD, null, Lib.folder(fold)?.name ?: "Notebooks", l.size)); for (x in l) rows.add(Row(T_NOTE, x))
        }
        rows.add(Row(T_HEAD, null, "Library", -1))
        for (f in listOf("all", "unt", "unf", "trash", "pin")) rows.add(Row(T_ITEM, null, f))
        rows.add(Row(T_HR)); rows.add(Row(T_FROOT))
        if (P.b("lib.fo")) { val op = openSet()
            fun add(parent: String?, d: Int) { for (f in Lib.childrenFolders(parent).sortedBy { it.name.lowercase() }) { rows.add(Row(T_FOLDER, f, "", 0, d)); if (f.id in op) add(f.id, d + 1) } }
            add(null, 0) }
        sideAd.notifyDataSetChanged()
    }

    fun showFilter(f: String) {
        if (f.startsWith("t:")) { val fo = Lib.folder(f.substring(2)) ?: return
            if (fo.locked && !act.unlocked.contains(fo.id)) { act.askPin { act.unlocked.add(fo.id); showFilter(f) }; return } }
        filter = f; browser.query = ""; browser.inside.clear(); browser.selecting = null
        browser.trash = f == "trash"; browser.flat = when (f) { "pin" -> "pin"; "unt", "unf" -> "unf"; else -> null }; browser.folder = if (f.startsWith("t:")) f.substring(2) else null
        if (act.hasEd && act.ed.panes.isNotEmpty()) act.ed.goHome()
        homeBox?.let { fillHome(it) }; refresh()
    }
    fun selectMode(on: Boolean) { browser.selecting = if (on) HashSet() else null; browser.refresh(); updateBatch() }

    private inner class SideAd : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View { val v = make(rows[i]); if (v.layoutParams !is AbsListView.LayoutParams) v.layoutParams = AbsListView.LayoutParams(MP, WC); return v }
    }
    private fun wrap(inner: View, h: Int, l: Int = 10, r: Int = 10): View { val f = FrameLayout(act); f.setPadding(dp(l), dp(1), dp(r), dp(1)); f.addView(inner, flp(MP, if (h > 0) dp(h) else WC)); return f }
    private fun make(r: Row): View {
        val c = act
        when (r.type) {
            T_TOP -> { val bar = hbox(c); bar.setPadding(dp(12), dp(6), dp(8), dp(2)); bar.addView(act.logoButton()); bar.addView(txt(c, "Larpful", 20f, Th.text, true).apply { setPadding(dp(8), 0, 0, 0) }, lp(0, WC, 1f)); bar.minimumHeight = dp(52); return bar }
            T_HEAD -> { val h = hbox(c); h.setPadding(dp(20), dp(16), dp(20), dp(7))
                h.addView(txt(c, r.s.uppercase(), 12f, Th.sub, true).oneLine().apply { letterSpacing = 0.04f }); if (r.n >= 0) h.addView(txt(c, r.n.toString(), 12f, Th.sub).apply { setPadding(dp(8), 0, 0, 0) })
                h.addView(space(c, 0, 0, 1f))
                val pn = r.a as? Pane   // "Edit" opens the page manager, as in the original
                if (pn != null && P.b("ui.pgMgr")) h.addView(txt(c, if (pgEdit) "Done" else "Edit", 12f, if (pgEdit) android.graphics.Color.WHITE else Th.sub, true).apply { background = rr(if (pgEdit) Th.accent else Th.fill, dpf(100)); setPadding(dp(12), dp(4), dp(12), dp(4)); fx(100f); setOnClickListener { pgEdit = !pgEdit; buildSide() } })
                return h }
            T_PGBAR -> {
                val pn = r.a as Pane; val box = vbox(c); box.setPadding(dp(14), 0, dp(14), dp(6)); val fl = Flow(c, dp(6))
                fun pill(label: String, on: () -> Unit): View = txt(c, label, 12.5f, Th.text).apply { background = rr(Th.fill, dpf(100)); setPadding(dp(11), dp(6), dp(11), dp(6)); fx(100f); setOnClickListener { on() } }
                fl.addView(pill("＋ Add file") { addFile(pn) })
                fl.addView(pill("＋ Add page") { act.ed.addPage(pn, pn.view.curPage()); buildSide() })
                if (pgUndo.any { it.first === pn.note }) fl.addView(pill("↩ Undo delete") { undoDelete(pn) })
                box.addView(fl); box.addView(txt(c, "Hold a page, then drag it anywhere to move it", 11.5f, Th.sub).apply { setPadding(dp(2), dp(7), 0, 0) }); return box
            }
            T_HR -> { val f = FrameLayout(c); f.setPadding(dp(14), dp(10), dp(14), dp(8)); f.addView(View(c).apply { setBackgroundColor(Th.sep) }, flp(MP, 1)); return f }
            T_ITEM -> {
                val f = r.s; val on = filter == f; val sub = f == "unt" || f == "unf" || f == "trash"
                val row = hbox(c); row.setPadding(dp(if (sub) 14 else 10), 0, dp(10), 0); row.background = if (on) rr(Th.sel, dpf(10)) else null; row.fx(10f)
                val icon = when (f) { "all" -> "nbook"; "unt" -> "dashc"; "unf" -> "folder"; "trash" -> "trash"; else -> "pin" }
                row.addView(ImageView(c).apply { setImageDrawable(Icon(icon, if (f == "all") Th.accent else Th.tint)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(12) })
                row.addView(txt(c, when (f) { "all" -> "Notebooks"; "unt" -> "Untagged"; "unf" -> "Unfiled"; "trash" -> "Trashed"; else -> "Pinned" }, 16f).oneLine(), lp(0, WC, 1f))
                val n = count(f); if (n > 0) row.addView(txt(c, n.toString(), 14f, Th.sub))
                row.setOnClickListener { showFilter(f) }
                return wrap(row, 40)
            }
            T_FROOT -> {
                val open = P.b("lib.fo"); val row = hbox(c); row.setPadding(dp(8), 0, dp(4), 0); row.fx(10f)
                row.addView(ImageView(c).apply { setImageDrawable(Icon("chev", Th.tint, 2f)); rotation = if (open) 90f else 0f }, lp(dp(16), dp(16)).apply { rightMargin = dp(8) })
                row.addView(ImageView(c).apply { setImageDrawable(Icon("folder", Th.tint)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(10) })
                row.addView(txt(c, "Folders", 16f), lp(0, WC, 1f)); row.addView(txt(c, Lib.folders.size.toString(), 14f, Th.sub))
                row.addView(ibtn(c, "plus", 36, Th.accent, 9) { newFolder(null) })
                row.setOnClickListener { P.set("lib.fo", !open, false); buildSide() }
                return wrap(row, 40)
            }
            T_FOLDER -> {
                val f = r.a as Folder; val on = filter == "t:" + f.id; val kids = Lib.childrenFolders(f.id).isNotEmpty(); val open = f.id in openSet()
                val row = hbox(c); row.setPadding(dp(6 + r.depth * 20), 0, dp(10), 0); row.background = if (on) rr(Th.sel, dpf(10)) else null; row.fx(10f)
                if (kids) row.addView(ImageView(c).apply { setImageDrawable(Icon("chev", Th.tint, 2f)); rotation = if (open) 90f else 0f; setPadding(dp(4), dp(10), dp(4), dp(10)); setOnClickListener { toggleOpen(f.id); buildSide() } }, lp(dp(24), dp(36)))
                else row.addView(View(c), lp(dp(24), 1))
                row.addView(ImageView(c).apply { setImageDrawable(Icon("hash", Th.tint)) }, lp(dp(20), dp(20)).apply { leftMargin = dp(6); rightMargin = dp(8) })
                row.addView(txt(c, f.name, 16f).oneLine(), lp(0, WC, 1f))
                if (f.locked) row.addView(ImageView(c).apply { setImageDrawable(Icon("lock", Th.sub)) }, lp(dp(14), dp(14)).apply { rightMargin = dp(6) })
                val n = count("t:" + f.id); if (n > 0) row.addView(txt(c, n.toString(), 14f, Th.sub))
                row.setOnClickListener { if (on) { toggleOpen(f.id); buildSide() } else { if (kids && !open) toggleOpen(f.id); showFilter("t:" + f.id) } }
                row.setOnLongClickListener { v -> folderMenu(f, v); true }
                return wrap(row, 40)
            }
            T_NOTE -> {
                val m = r.a as NoteMeta; val on = act.hasEd && act.ed.focused?.note?.id == m.id
                val row = hbox(c); row.setPadding(dp(6), dp(4), dp(10), dp(4)); row.background = if (on) rr(Th.sel, dpf(12)) else null; row.fx(12f)
                val th = ThumbView(c); th.book = m.cover && !m.locked
                if (m.locked) th.show(null, "lock") else Thumbs.cover(m) { b -> th.show(b, if (b == null) "note" else null) }
                row.addView(th, lp(dp(46), dp(58)))
                val col = vbox(c); col.addView(txt(c, m.title, 15f, Th.text, true).oneLine())
                val date = DateUtils.getRelativeTimeSpanString(m.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                col.addView(txt(c, "$date · ${m.pages} " + (if (m.pages == 1) "page" else "pages"), 12.5f, Th.sub).oneLine(), lp(WC, WC).apply { topMargin = dp(3) })
                row.addView(col, lp(0, WC, 1f).apply { leftMargin = dp(8) })
                if (m.pinned) row.addView(ImageView(c).apply { setImageDrawable(Icon("pinf", Th.tint)) }, lp(dp(15), dp(15)))
                row.setOnClickListener { act.ed.open(m.id) }; row.setOnLongClickListener { v -> noteMenu(m, v); true }
                return wrap(row, 0, 8, 8)
            }
            else -> {   // two page previews side by side
                val pn = r.a as Pane; val n = pn.note; val row = hbox(c); row.setPadding(dp(14), dp(3), dp(14), dp(5)); row.gravity = Gravity.TOP
                if (n == null) return row
                val cur = pn.view.curPage()
                for (k in 0..1) {
                    val i = r.n + k
                    if (i >= n.pages.size) { row.addView(View(c), lp(0, 1, 1f).apply { leftMargin = dp(if (k == 1) 10 else 0) }); continue }
                    val pg = n.pages[i]; val on = i == cur && pn === act.ed.focused
                    val cell = FrameLayout(c); val iv = RatioImage(c, pg.h / pg.w); iv.scaleType = ImageView.ScaleType.FIT_XY
                    iv.background = rr(if (pg.src != null) -1 else pg.paperColor, dpf(6), if (on) Th.accent else Th.sep, if (on) dp(2.5f) else 1); iv.setPadding(dp(3), dp(3), dp(3), dp(3))
                    Thumbs.load(n.id, pg.id) { b -> if (b != null) iv.setImageBitmap(b) }
                    cell.addView(iv, flp(MP, WC))
                    val num = txt(c, (i + 1).toString(), 11f, if (on) android.graphics.Color.WHITE else Th.text, true); num.background = rr(if (on) Th.accent else Th.glass, dpf(100)); num.setPadding(dp(7), dp(2), dp(7), dp(2))
                    cell.addView(num, flp(WC, WC, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(7) })
                    if (pgEdit && P.b("ui.pgMgr")) cell.addView(txt(c, "✕", 13f, android.graphics.Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rr(Th.danger, dpf(13)); setOnClickListener { delPage(pn, i) } }, flp(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
                    cell.fx(6f); wirePage(cell, iv, pn, i)
                    row.addView(cell, lp(0, WC, 1f).apply { leftMargin = dp(if (k == 1) 10 else 0) })
                }
                return row
            }
        }
    }

    /* ---------- page manager (Pages section) ---------- */
    private fun delPage(pn: Pane, i: Int) {
        val n = pn.note ?: return; if (n.pages.size < 2) { toast("A note needs at least one page"); return }
        val pg = n.pages.removeAt(i); pgUndo.add(Triple(n, i, pg))
        if (pgUndo.size > 20) { val old = pgUndo.removeAt(0); Store.io { Store.inkFile(old.first.id, old.third.id).delete(); Store.thumbFile(old.first.id, old.third.id).delete() } }
        pn.view.structureChanged(); toast("Page deleted"); buildSide()
    }
    private fun undoDelete(pn: Pane) {
        val n = pn.note ?: return; val k = pgUndo.indexOfLast { it.first === n }; if (k < 0) return
        val u = pgUndo.removeAt(k); n.pages.add(min(u.second, n.pages.size), u.third); if (u.third.loaded) u.third.dirty = true
        pn.view.structureChanged(); toast("Page restored"); buildSide()
    }
    /** a page preview: tap = go to it; hold and drag = move it; hold and let go = its options */
    @SuppressLint("ClickableViewAccessibility")
    private fun wirePage(cell: FrameLayout, iv: ImageView, pn: Pane, i: Int) {
        cell.tag = Pair(pn, i)
        var sx = 0f; var sy = 0f; var down = false; var armed = false; var moved = false; var ghost: ImageView? = null; var target: View? = null; var dy = 0
        val root = IntArray(2); val a = IntArray(2)
        val scrollR = object : Runnable { override fun run() { if (armed && dy != 0) { side.scrollListBy(dy); side.postDelayed(this, 16) } } }
        fun end() { armed = false; pageDrag = false; dy = 0; side.removeCallbacks(scrollR); ghost?.let { act.root.removeView(it) }; ghost = null; cell.alpha = 1f; target?.let { it.scaleX = 1f; it.scaleY = 1f }; cell.parent?.requestDisallowInterceptTouchEvent(false) }
        val arm = Runnable {
            if (!down) return@Runnable
            armed = true; moved = false; pageDrag = true; cell.isPressed = false; cell.parent?.requestDisallowInterceptTouchEvent(true); cell.alpha = 0.35f
            cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val g = ImageView(act); g.setImageDrawable(iv.drawable); g.scaleType = ImageView.ScaleType.FIT_XY; g.background = rr(-1, dpf(6), Th.sep, 1); g.clipToOutline = true; g.elevation = dpf(150); g.rotation = 3f; g.alpha = 0.94f
            val gw = min(dp(120), cell.width.coerceAtLeast(dp(60))); val gh = gw * cell.height.coerceAtLeast(1) / cell.width.coerceAtLeast(1)
            act.root.addView(g, flp(gw, gh)); act.root.getLocationOnScreen(root); g.translationX = sx - root[0] - gw / 2f; g.translationY = sy - root[1] - gh / 2f; ghost = g
        }
        cell.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; armed = false; moved = false; sx = e.rawX; sy = e.rawY; cell.isPressed = true; cell.removeCallbacks(arm); cell.postDelayed(arm, 300) }
                MotionEvent.ACTION_MOVE -> {
                    val far = kotlin.math.hypot(e.rawX - sx, e.rawY - sy) > dp(9)
                    if (!armed) { if (far) { cell.removeCallbacks(arm); cell.isPressed = false } }
                    else {
                        if (far) moved = true
                        val g = ghost; if (g != null) { act.root.getLocationOnScreen(root); g.translationX = e.rawX - root[0] - g.width / 2f; g.translationY = e.rawY - root[1] - g.height / 2f }
                        var hit: View? = null
                        for (k in 0 until side.childCount) { val rowV = side.getChildAt(k) as? ViewGroup ?: continue
                            for (q in 0 until rowV.childCount) { val ch = rowV.getChildAt(q); val tg = ch.tag as? Pair<*, *> ?: continue; if (tg.first !== pn || ch === cell) continue
                                ch.getLocationOnScreen(a); if (e.rawX >= a[0] && e.rawX <= a[0] + ch.width && e.rawY >= a[1] && e.rawY <= a[1] + ch.height) hit = ch } }
                        if (hit !== target) { target?.let { it.scaleX = 1f; it.scaleY = 1f }; hit?.let { it.scaleX = 0.9f; it.scaleY = 0.9f }; target = hit }
                        side.getLocationOnScreen(a); val ndy = if (e.rawY < a[1] + dp(70)) -dp(12) else if (e.rawY > a[1] + side.height - dp(70)) dp(12) else 0
                        if (ndy != dy) { dy = ndy; side.removeCallbacks(scrollR); if (dy != 0) side.post(scrollR) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cell.removeCallbacks(arm); cell.isPressed = false; down = false
                    if (!armed) { if (kotlin.math.hypot(e.rawX - sx, e.rawY - sy) <= dp(9)) { act.ed.focus(pn); pn.view.scrollToPage(i) } }
                    else {
                        val j = (target?.tag as? Pair<*, *>)?.second as? Int; val wasMoved = moved; end()
                        val n = pn.note
                        if (n != null && !wasMoved) PagesUi.menu(act.ed, pn, n, i, cell) { buildSide() }
                        else if (n != null && j != null && j != i && i < n.pages.size) { val p = n.pages.removeAt(i); n.pages.add(j.coerceIn(0, n.pages.size), p); pn.view.structureChanged(); toast("Page moved to " + (j + 1)); buildSide() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { cell.removeCallbacks(arm); cell.isPressed = false; down = false; if (armed) end() }
            }
            true
        }
    }
    /** "＋ Add file": pages from the device, or chosen pages of another notebook (with previews) */
    private fun addFile(pn: Pane) {
        val n = pn.note ?: return; val c = act; val body = vbox(c); body.setPadding(dp(16), dp(10), dp(16), dp(20)); lateinit var l: Layer
        val dev = hbox(c); dev.background = rr(Th.card, dpf(12)); dev.setPadding(dp(14), 0, dp(14), 0); dev.fx(12f)
        dev.addView(ImageView(c).apply { setImageDrawable(Icon("folder", Th.tint)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(12) })
        dev.addView(txt(c, "From this device (PDF, image, Word, PowerPoint…)", 16f), lp(0, WC, 1f)); dev.setOnClickListener { l.dismiss(); act.pickFiles(n.id) }
        body.addView(dev, lp(MP, dp(52)))
        body.addView(txt(c, "MY NOTES IN LARPFUL", 13f, Th.sub).apply { setPadding(dp(14), dp(20), 0, dp(8)) })
        val others = Lib.sorted(Lib.notes.filter { it.trashed == 0L && it.id != n.id && !it.locked })
        if (others.isEmpty()) body.addView(txt(c, "No other notebooks yet", 14f, Th.sub).apply { setPadding(dp(14), dp(8), 0, 0) })
        val fl = Flow(c, dp(12))
        for (m in others) {
            val card = vbox(c); card.gravity = Gravity.CENTER_HORIZONTAL; card.fx(10f)
            val th = ThumbView(c); th.book = m.cover; Thumbs.cover(m) { b -> th.show(b, if (b == null) "note" else null) }
            card.addView(th, lp(dp(120), dp(160)))
            card.addView(txt(c, m.title, 13f, Th.text, true).oneLine().apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }, lp(dp(124), WC))
            card.addView(txt(c, "${m.pages} " + (if (m.pages == 1) "page" else "pages") + (Lib.folder(m.folder)?.let { " · " + it.name } ?: ""), 11.5f, Th.sub).oneLine().apply { gravity = Gravity.CENTER }, lp(dp(124), WC))
            card.setOnClickListener { l.dismiss(); pickPages(pn, m) }
            fl.addView(card)
        }
        body.addView(fl, lp(MP, WC).apply { leftMargin = dp(6) })
        l = sheet("Add pages from", body, 640)
    }
    private fun pickPages(pn: Pane, m: NoteMeta) {
        val into = pn.note ?: return; act.ed.flush(); toast("Loading…")
        Store.io {
            val src = Store.openNote(m.id); if (src == null) { toast("Could not open that notebook"); return@io }
            Store.main {
                val c = act; val sel = java.util.TreeSet<Int>(); val body = vbox(c); lateinit var l: Layer
                val grid = GridView(c); grid.numColumns = 4; grid.verticalSpacing = dp(12); grid.horizontalSpacing = dp(10); grid.setPadding(dp(16), dp(10), dp(16), dp(10)); grid.clipToPadding = false; grid.selector = android.graphics.drawable.ColorDrawable(0)
                val go = txt(c, "Add pages", 15f, android.graphics.Color.WHITE, true); val cnt = txt(c, "0 selected", 13f, Th.sub); val all = txt(c, "Select all", 14f, Th.text)
                val ad = object : BaseAdapter() {
                    override fun getCount() = src.pages.size
                    override fun getItem(k: Int) = src.pages[k]
                    override fun getItemId(k: Int) = k.toLong()
                    override fun getView(k: Int, cv: View?, parent: ViewGroup?): View {
                        val pg = src.pages[k]; val on = k in sel; val cell = FrameLayout(c); val iv = RatioImage(c, pg.h / pg.w); iv.scaleType = ImageView.ScaleType.FIT_XY
                        iv.background = rr(if (pg.src != null) -1 else pg.paperColor, dpf(6), if (on) Th.accent else Th.sep, if (on) dp(3) else 1); iv.setPadding(dp(3), dp(3), dp(3), dp(3))
                        Thumbs.load(src.id, pg.id) { b -> if (b != null) iv.setImageBitmap(b) }
                        cell.addView(iv, flp(MP, WC))
                        cell.addView(txt(c, if (on) "✓" else "", 13f, android.graphics.Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rr(if (on) Th.accent else col(0x73000000), dpf(12), android.graphics.Color.WHITE, dp(2)) }, flp(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { topMargin = dp(6); leftMargin = dp(6) })
                        cell.addView(txt(c, (k + 1).toString(), 11f, Th.text, true).apply { background = rr(Th.glass, dpf(100)); setPadding(dp(7), dp(2), dp(7), dp(2)) }, flp(WC, WC, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(6); bottomMargin = dp(6) })
                        cell.setOnClickListener { if (!sel.remove(k)) sel.add(k); notifyDataSetChanged()
                            cnt.text = "${sel.size} selected"; go.text = if (sel.isEmpty()) "Add pages" else "Add ${sel.size} page" + (if (sel.size == 1) "" else "s"); go.alpha = if (sel.isEmpty()) 0.4f else 1f; all.text = if (sel.size == src.pages.size) "Clear" else "Select all" }
                        return cell
                    }
                }
                grid.adapter = ad; body.addView(grid, lp(MP, 0, 1f))
                val bar = hbox(c); bar.setPadding(dp(16), dp(8), dp(16), dp(12))
                all.background = rr(Th.fill, dpf(100)); all.setPadding(dp(14), dp(8), dp(14), dp(8)); all.fx(100f)
                all.setOnClickListener { if (sel.size == src.pages.size) sel.clear() else sel.addAll(src.pages.indices); ad.notifyDataSetChanged(); cnt.text = "${sel.size} selected"; go.text = if (sel.isEmpty()) "Add pages" else "Add ${sel.size} page" + (if (sel.size == 1) "" else "s"); go.alpha = if (sel.isEmpty()) 0.4f else 1f; all.text = if (sel.size == src.pages.size) "Clear" else "Select all" }
                go.background = rr(Th.accent, dpf(100)); go.setPadding(dp(18), dp(8), dp(18), dp(8)); go.alpha = 0.4f; go.fx(100f)
                go.setOnClickListener { if (sel.isEmpty()) return@setOnClickListener
                    val idx = sel.toList(); l.dismiss(); toast("Adding…")
                    Store.io { val pages = Store.clonePages(src, idx, into)
                        Store.main { if (pn.note === into && pages.isNotEmpty()) { into.pages.addAll(pages); pn.view.structureChanged(); buildSide(); toast("Added ${pages.size} page" + (if (pages.size == 1) "" else "s") + " from “" + m.title.take(24) + "”") } } } }
                bar.addView(all); bar.addView(cnt, lp(0, WC, 1f).apply { leftMargin = dp(12) }); bar.addView(go)
                body.addView(bar, lp(MP, WC))
                val frame = FrameLayout(c); frame.addView(body, flp(MP, (act.root.height * 0.72f).toInt()))
                l = sheet(m.title.take(30), frame, 720)
            }
        }
    }

    private fun addMenu(v: View) {
        showMenu(v, listOf(MI("note", "New notebook") { newNote() }, MI("share", "Upload PDF (as is)") { act.pickFiles(null) },
            MI("noteplus", "Upload other file → convert to PDF") { act.pickFiles(null) }, MI("save", "Import FreeNotes backup (.freebackup)") { act.pickImport() }))
    }

    /* ---------- home (shown when no note is open): big title, card grid, round blue “+” ---------- */
    private fun homeTitle() {
        val row = crumbs ?: return; val c = act; row.removeAllViews()
        fun crumb(label: String, current: Boolean, go: (() -> Unit)?) {
            val t = txt(c, label, 34f, if (current) Th.text else Th.accent, true).oneLine(); t.letterSpacing = -0.03f
            if (go != null) { t.fx(8f); t.setOnClickListener { go() } }
            row.addView(t)
        }
        fun sepv() { row.addView(txt(c, "›", 30f, Th.sub).apply { setPadding(dp(8), 0, dp(8), 0) }) }
        empty?.visibility = if (browser.rows.isEmpty()) View.VISIBLE else View.GONE
        if (browser.query.isNotBlank()) { crumb("Search results", true, null); return }
        if (browser.trash || browser.flat != null) { crumb(when (filter) { "trash" -> "Trashed"; "pin" -> "Pinned"; "unt" -> "Untagged"; else -> "Unfiled" }, true, null); return }
        val chain = ArrayList<Folder>(); var f = Lib.folder(browser.folder); var g = 0
        while (f != null && g++ < 20) { chain.add(0, f); f = Lib.folder(f.parent) }
        crumb("Notebooks", chain.isEmpty(), if (chain.isEmpty()) null else ({ showFilter("all") }))
        chain.forEachIndexed { i, fo -> sepv(); crumb(fo.name, i == chain.size - 1, if (i == chain.size - 1) null else ({ showFilter("t:" + fo.id) })) }
    }
    private fun updateBatch() {
        val b = batch ?: return; val sel = browser.selecting
        b.visibility = if (sel == null) View.GONE else View.VISIBLE
        (b.getChildAt(0) as? TextView)?.text = "${sel?.size ?: 0} selected"
    }
    private fun picked(): List<NoteMeta> = browser.selecting?.mapNotNull { Lib.note(it) } ?: emptyList()
    fun fillHome(home: FrameLayout) {
        homeBox = home; home.removeAllViews()
        val c = act; val col = vbox(c)
        val bar = hbox(c); bar.setPadding(dp(12), 0, dp(12), 0); bar.setBackgroundColor(Th.bar)
        val logo = act.logoButton(); logo.visibility = if (docked) View.GONE else View.VISIBLE; homeLogo = logo; bar.addView(logo)
        bar.addView(ibtn(c, "sidebar", 40) { toggle() })
        bar.addView(space(c, 0, 0, 1f))
        bar.addView(ibtn(c, "selc", 40) { selectMode(browser.selecting == null) })
        val search = EditText(c); search.hint = "Search"; search.setSingleLine(); search.setText(browser.query); search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        search.setTextColor(Th.text); search.setHintTextColor(Th.sub); search.background = rr(Th.fill, dpf(10)); search.setPadding(dp(12), 0, dp(12), 0); search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString() ?: ""; if (q == browser.query) return
                browser.query = q; browser.inside.clear(); browser.refresh(); homeTitle()
                // "Home search also looks inside notes": typed text, PDF text and page labels, a moment after typing stops
                val g = ++qGen; val t = q.trim()
                if (P.b("lib.qs") && t.length >= 2) search.postDelayed({ if (g == qGen) Store.work {
                    val hits = try { Search.run(t, null) } catch (e: Throwable) { emptyList() }
                    Store.main { if (g == qGen && browser.query == q) { val max = P.i("lib.qsMax").coerceIn(1, 60)
                        for (h in hits) { if (h.kind == "Name" || browser.inside.containsKey(h.note.id)) continue; if (browser.inside.size >= max) break; browser.inside[h.note.id] = h }
                        if (browser.inside.isNotEmpty()) { browser.refresh(); homeTitle() } } } } }, 350)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
        })
        bar.addView(search, lp(dp(240), dp(36)).apply { leftMargin = dp(4) })
        bar.addView(ibtn(c, "sort", 40) { val next = when (P.s("lib.sort")) { "modified" -> "name"; "name" -> "created"; "created" -> "manual"; else -> "modified" }
            P.set("lib.sort", next); toast(when (next) { "name" -> "Sorted by name"; "created" -> "Sorted by date created"; "manual" -> "Your own order (hold a notebook and drag it)"; else -> "Sorted by last edited" }) }, lp(dp(40), dp(40)).apply { leftMargin = dp(4) })
        col.addView(bar, lp(MP, dp(52))); col.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1))
        val h1 = hbox(c); h1.setPadding(dp(24), dp(14), dp(24), dp(2)); crumbs = h1
        val hs = HorizontalScrollView(c); hs.isHorizontalScrollBarEnabled = false; hs.addView(h1); col.addView(hs, lp(MP, WC))
        (browser.list.parent as? ViewGroup)?.removeView(browser.list)
        browser.refresh()
        col.addView(browser.list, lp(MP, 0, 1f))
        home.addView(col, flp(MP, MP))
        val e = txt(c, "Nothing here yet.", 15f, Th.sub); e.setPadding(dp(46), dp(132), 0, 0); empty = e; home.addView(e, flp(WC, WC))
        homeTitle()
        val fab = FrameLayout(c); fab.background = rr(Th.accent, dpf(29)); fab.elevation = dpf(8); fab.fx(29f)
        fab.addView(ImageView(c).apply { setImageDrawable(Icon("plus", android.graphics.Color.WHITE, 2.4f)) }, flp(dp(28), dp(28), Gravity.CENTER))
        fab.setOnClickListener { addMenu(fab) }
        home.addView(fab, flp(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(26); bottomMargin = dp(26) })
        // select mode: "2 selected  Pin  Share  Move  Delete  Done"
        val b = hbox(c); b.background = rr(Th.glass, dpf(16), Th.hair, 1); b.elevation = dpf(10); b.setPadding(dp(16), 0, dp(8), 0)
        b.addView(txt(c, "", 14f, Th.sub).apply { setPadding(0, 0, dp(8), 0) })
        fun bb(label: String, color: Int, on: () -> Unit) { b.addView(txt(c, label, 16f, color).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); fx(10f); setOnClickListener { on() } }) }
        bb("Pin", Th.accent) { for (m in picked()) m.pinned = true; selectMode(false); Lib.changed() }
        bb("Share", Th.accent) { val l = picked(); if (l.isNotEmpty()) { selectMode(false); Io.shareNotes(l) } }
        bb("Move", Th.accent) { val l = picked(); if (l.isNotEmpty()) movePicker("Move ${l.size} " + (if (l.size == 1) "notebook" else "notebooks"), null) { f -> for (m in l) m.folder = f; selectMode(false); Lib.changed() } }
        bb("Delete", Th.danger) { val l = picked(); if (l.isNotEmpty()) {
            if (browser.trash) confirm("Delete ${l.size} forever?", "This cannot be undone.", "Delete") { for (m in l) purge(m); selectMode(false) }
            else { for (m in l) { act.ed.closeNote(m.id); m.trashed = System.currentTimeMillis(); m.pinned = false }; selectMode(false); Lib.changed() } } }
        bb("Done", Th.accent) { selectMode(false) }
        batch = b; home.addView(b, flp(WC, dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(26) }); updateBatch()
    }

    /* ---------- actions ---------- */
    fun newNote() { val n = Store.createNote("Untitled", if (browser.trash || browser.flat != null) null else browser.folder); act.ed.open(n.id) }
    fun newFolder(parent: String?) { prompt("New folder", "", "Folder name") { s -> if (s.isNotEmpty()) { val f = Folder(newId()); f.name = s; f.parent = parent; Lib.folders.add(f)
        if (parent != null && parent !in openSet()) toggleOpen(parent); P.set("lib.fo", true, false); Lib.changed() } } }

    fun noteMenu(m: NoteMeta, v: View?) {
        val items = ArrayList<MI?>()
        if (m.trashed != 0L) {
            items.add(MI("undo", "Restore") { m.trashed = 0; if (Lib.folder(m.folder) == null) m.folder = null; Lib.changed() })
            items.add(MI("trash", "Delete permanently", true) { confirm("Delete “${m.title}” permanently?", "This cannot be undone.", "Delete") { purge(m) } })
        } else {
            items.add(MI("edit", "Rename") { prompt("Rename", m.title) { s -> if (s.isNotEmpty()) { m.title = s; Lib.changed(); act.ed.refreshTop() } } })
            items.add(MI("dup", "Duplicate") { act.ed.flush(); Store.duplicateNote(m) { toast("Duplicated") } })
            items.add(MI("move", "Move…") { movePicker("Move “${m.title}”", null) { f -> m.folder = f; Lib.changed() } })
            items.add(MI("image", if (m.cover) "Change cover…" else "Add cover…") { CoverUi.open(m.id, false, m.cover) })
            items.add(MI("share", "Export…") { act.ed.flush(); Store.openNote(m.id)?.let { Io.exportDialog(it, 0) } })
            items.add(MI("spark", "Summarize with Claude…") { AiUi.open(m) })
            items.add(MI("pin", if (m.pinned) "Unpin" else "Pin") { m.pinned = !m.pinned; Lib.changed() })
            items.add(MI("lock", if (m.locked) "Unlock" else "Lock") { act.askPin { m.locked = !m.locked; if (m.locked) { act.unlocked.remove(m.id); act.ed.closeNote(m.id) }; Lib.changed() } })
            items.add(MI("history", "Restore history") { HistoryUi.open(m) })
            items.add(null)
            items.add(MI("paneT", "Open at the top") { act.ed.open(m.id, "top") })
            items.add(MI("paneB", "Open at the bottom") { act.ed.open(m.id, "bottom") })
            items.add(MI("paneL", "Open on the left") { act.ed.open(m.id, "left") })
            items.add(MI("paneR", "Open on the right") { act.ed.open(m.id, "right") })
            items.add(null)
            items.add(MI("trash", "Delete", true) { val go = { act.ed.closeNote(m.id); m.trashed = System.currentTimeMillis(); m.pinned = false; Lib.changed() }
                if (P.b("lib.confirmDel")) confirm("Delete “${m.title}”?", "It moves to Trashed.", "Delete") { go() } else go() })
        }
        if (v != null) showMenu(v, items, m.title) else showMenu(act.root.width / 2, act.root.height / 3, items, m.title)
    }
    fun purge(m: NoteMeta) { act.ed.closeNote(m.id); Lib.notes.remove(m); Store.deleteNoteFiles(m.id); Lib.changed() }

    fun folderMenu(f: Folder, v: View) {
        showMenu(v, listOf(
            MI("edit", "Rename") { prompt("Rename folder", f.name) { s -> if (s.isNotEmpty()) { f.name = s; Lib.changed() } } },
            MI("folderplus", "New folder inside") { newFolder(f.id) },
            MI("dup", "Duplicate") { duplicateFolder(f, f.parent, f.name + " copy") },
            MI("move", "Move…") { movePicker("Move “${f.name}”", f.id) { dest -> if (dest != f.id && !Lib.isDescendant(dest, f.id)) { f.parent = dest; Lib.changed() } } },
            MI("image", if (f.cover) "Change cover…" else "Add cover…") { CoverUi.open(f.id, true, f.cover) },
            MI("pin", if (f.pinned) "Unpin" else "Pin") { f.pinned = !f.pinned; Lib.changed() },
            MI("lock", if (f.locked) "Unlock" else "Lock") { act.askPin { f.locked = !f.locked; act.unlocked.remove(f.id); Lib.changed() } },
            null,
            MI("trash", "Delete", true) { confirm("Delete folder “${f.name}” and its sub-folders?", "Its notebooks move to Trashed.", "Delete") { if (browser.folder != null && (browser.folder == f.id || Lib.isDescendant(browser.folder, f.id))) showFilter("all"); deleteFolder(f); Lib.changed() } }), f.name)
    }
    private fun deleteFolder(f: Folder) {
        for (c in Lib.childrenFolders(f.id)) deleteFolder(c)
        for (n in Lib.notes) if (n.folder == f.id) { n.folder = null; if (n.trashed == 0L) { n.trashed = System.currentTimeMillis(); act.ed.closeNote(n.id) } }
        Lib.folders.remove(f)
    }
    private fun duplicateFolder(f: Folder, parent: String?, name: String) {
        val nf = Folder(newId()); nf.name = name; nf.parent = parent; Lib.folders.add(nf)
        for (n in Lib.childrenNotes(f.id)) Store.duplicateNote(n) { c -> c.folder = nf.id; c.title = n.title; Lib.changed() }
        for (c in Lib.childrenFolders(f.id)) duplicateFolder(c, nf.id, c.name)
        Lib.changed()
    }

    /** folder picker: navigate, then "Move here" */
    fun movePicker(title: String, excludeFolder: String?, done: (String?) -> Unit) {
        val c = act; val box = vbox(c)
        val b = Browser(false) { _, _ -> }
        b.exclude = excludeFolder
        val head = hbox(c); head.setPadding(dp(8), 0, dp(12), 0)
        val where = txt(c, "Notebooks", 17f, Th.text, true)
        val back = ibtn(c, "back", 36, Th.accent, 8) { b.up() }
        head.addView(back); head.addView(where, lp(0, WC, 1f))
        lateinit var layer: Layer
        head.addView(txt(c, "Move here", 15.5f, Th.accent, true).apply { setPadding(dp(12), dp(10), dp(4), dp(10)); fx(); setOnClickListener { layer.dismiss(); done(b.folder) } })
        b.onNavigate = { where.text = b.title; back.visibility = if (b.canUp) View.VISIBLE else View.INVISIBLE }
        back.visibility = View.INVISIBLE
        box.addView(head, lp(MP, dp(48))); box.addView(b.list, lp(MP, dp(380)))
        if (Lib.folders.isEmpty()) box.addView(txt(c, "No folders yet. Create one from the sidebar first.", 14f, Th.sub).apply { setPadding(dp(18), dp(8), dp(18), dp(18)) })
        layer = sheet(title, box, 440)
    }
}

/** Page preview with a soft shadow. With book = true it is drawn like a notebook cover: spine on the left, rounded right corners, title. */
class ThumbView(c: android.content.Context) : View(c) {
    var bmp: Bitmap? = null
        private set
    private var icon: Icon? = null
    var book = false; var label: String? = null
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val r = android.graphics.RectF(); private val clip = android.graphics.Path()
    fun show(b: Bitmap?, ic: String?) { bmp = b; icon = if (ic != null) Icon(ic, Th.sub, 1.5f) else null; invalidate() }
    override fun onDraw(c: android.graphics.Canvas) {
        val pad = dpf(5); val aw = width - 2 * pad; val ah = height - 2 * pad - dpf(2); if (aw <= 4 || ah <= 4) return
        val b = bmp; val ar = if (b != null && b.height > 0) b.width / b.height.toFloat() else 0.75f
        var w = aw; var h = w / ar; if (h > ah) { h = ah; w = h * ar }
        val l = (width - w) / 2f; val t = pad + (ah - h) / 2f; r.set(l, t, l + w, t + h)
        val k = (w / dpf(172)).coerceIn(0.25f, 1.2f)
        val small = dpf(5) * k.coerceAtLeast(0.6f); val big = if (book) dpf(12) * k.coerceAtLeast(0.5f) else small
        clip.rewind(); clip.addRoundRect(r, floatArrayOf(small, small, big, big, big, big, small, small), android.graphics.Path.Direction.CW)
        p.style = android.graphics.Paint.Style.FILL; p.shader = null; p.color = if (Th.dark && b == null) Th.card else android.graphics.Color.WHITE
        p.setShadowLayer(dpf(5) * k.coerceAtLeast(0.5f), 0f, dpf(2) * k.coerceAtLeast(0.5f), col(0x42000000)); c.drawPath(clip, p); p.clearShadowLayer()
        if (b != null && !b.isRecycled) {
            c.save(); c.clipPath(clip); c.drawBitmap(b, null, r, p)
            if (book) {
                val sw = dpf(9) * k
                p.shader = android.graphics.LinearGradient(l, 0f, l + sw, 0f, intArrayOf(col(0x4D000000), col(0x24FFFFFF), 0), floatArrayOf(0f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(l, t, l + sw, t + h, p); p.shader = null
                val lb = label
                if (lb != null && w > dpf(110)) {
                    val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.color = android.graphics.Color.WHITE; tp.textSize = dpf(16) * k; tp.typeface = android.graphics.Typeface.create(Th.family, android.graphics.Typeface.BOLD); tp.setShadowLayer(dpf(4), 0f, dpf(1), col(0x73000000))
                    val lay = android.text.StaticLayout.Builder.obtain(lb, 0, lb.length, tp, (w - dpf(26) * k).toInt().coerceAtLeast(10)).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                    c.save(); c.translate(l + dpf(14) * k, t + h - lay.height - dpf(14) * k); lay.draw(c); c.restore()
                }
            }
            c.restore()
        } else icon?.let { val s = (kotlin.math.min(w, h) * 0.42f).toInt(); it.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt()); it.draw(c) }
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Th.hair; c.drawPath(clip, p)
    }
}

/** The yellow folder of the first Larpful: tab on top, up to four page previews inside. */
class FolderTile(c: android.content.Context) : View(c) {
    private var folder: Folder? = null
    private val thumbs = arrayOfNulls<Bitmap>(4); private var n = 0; private var cover: Bitmap? = null
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val path = android.graphics.Path(); private val r = android.graphics.RectF()
    fun bind(f: Folder) {
        folder = f; cover = null; for (i in 0..3) thumbs[i] = null
        val ns = if (f.locked) emptyList() else Lib.sorted(Lib.notesUnder(f.id)).filter { !it.locked && !it.cover }.take(4); n = ns.size
        ns.forEachIndexed { i, m -> Thumbs.cover(m) { b -> if (folder === f) { thumbs[i] = b; invalidate() } } }
        if (f.cover && !f.locked) Thumbs.folderCover(f) { b -> if (folder === f) { cover = b; invalidate() } }
        invalidate()
    }
    override fun onDraw(c: android.graphics.Canvas) {
        val f = folder ?: return
        val bw = kotlin.math.min(width - dpf(10), dpf(190)); val k = bw / dpf(190); val bh = dpf(150) * k; val tab = dpf(14) * k
        val l = (width - bw) / 2f; val t = (height - bh - tab) / 2f + tab
        // tab
        p.shader = null; p.style = android.graphics.Paint.Style.FILL; p.color = col(0xFFF5A623)
        path.rewind(); r.set(l, t - tab, l + dpf(74) * k, t + dpf(10) * k); path.addRoundRect(r, floatArrayOf(dpf(9) * k, dpf(9) * k, dpf(9) * k, dpf(9) * k, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW); c.drawPath(path, p)
        // body
        r.set(l, t, l + bw, t + bh); path.rewind(); val a = dpf(6) * k; val b = dpf(16) * k
        path.addRoundRect(r, floatArrayOf(a, a, b, b, b, b, b, b), android.graphics.Path.Direction.CW)
        p.shader = android.graphics.LinearGradient(l, t, l + bw * 0.4f, t + bh, col(0xFFFFD978), col(0xFFF5A623), android.graphics.Shader.TileMode.CLAMP)
        p.setShadowLayer(dpf(6) * k, 0f, dpf(3) * k, col(0x40000000)); c.drawPath(path, p); p.clearShadowLayer(); p.shader = null
        val cv = cover
        if (cv != null) { c.save(); c.clipPath(path); c.drawBitmap(cv, android.graphics.Rect(0, cv.height / 2 - (cv.width * bh / bw / 2).toInt().coerceAtMost(cv.height / 2), cv.width, cv.height / 2 + (cv.width * bh / bw / 2).toInt().coerceAtMost(cv.height / 2)), r, p); c.restore() }
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dpf(1); p.color = col(0x66FFFFFF); c.drawLine(l + b, t + dpf(1), l + bw - b, t + dpf(1), p); p.style = android.graphics.Paint.Style.FILL
        if (f.locked) { val ic = Icon("lock", android.graphics.Color.WHITE, 1.8f); val s = (dpf(40) * k).toInt(); ic.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt()); ic.draw(c); return }
        if (cv != null) return
        if (n == 0) { val ic = Icon("folderf", col(0xCCFFFFFF)); val s = (dpf(46) * k).toInt(); ic.setBounds((r.centerX() - s / 2).toInt(), (r.centerY() - s / 2).toInt(), (r.centerX() + s / 2).toInt(), (r.centerY() + s / 2).toInt()); ic.draw(c); return }
        // 2 x 2 previews
        val px = dpf(16) * k; val py = dpf(13) * k; val gap = dpf(6) * k; val cw = (bw - 2 * px - gap) / 2f; val ch = (bh - 2 * py - gap) / 2f
        for (i in 0 until n) {
            val bm = thumbs[i] ?: continue; if (bm.isRecycled) continue
            val one = n == 1; val two = n == 2
            val cx = if (one) l + bw / 2f else l + px + (i % 2) * (cw + gap) + cw / 2f; val cy = if (one || two) t + bh / 2f else t + py + (i / 2) * (ch + gap) + ch / 2f
            val mh = if (one || two) bh - 2 * py else ch; val mw = if (one) bw - 2 * px else cw
            val ar = bm.width / bm.height.toFloat(); var h = mh; var w = h * ar; if (w > mw) { w = mw; h = w / ar }
            r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            p.color = android.graphics.Color.WHITE; p.setShadowLayer(dpf(2) * k, 0f, dpf(1) * k, col(0x59000000)); c.drawRoundRect(r, dpf(2) * k, dpf(2) * k, p); p.clearShadowLayer()
            c.drawBitmap(bm, null, r, p)
        }
    }
}

/** Cover picker: the gradient covers of the first Larpful, or your own picture. */
object CoverUi {
    val COVERS = listOf("Sunset" to intArrayOf(col(0xFFFF9A5A), col(0xFFFF4D6D)), "Ocean" to intArrayOf(col(0xFF4FACFE), col(0xFF0052D4)), "Forest" to intArrayOf(col(0xFF56AB2F), col(0xFF134E5E)), "Grape" to intArrayOf(col(0xFFA18CD1), col(0xFF5B247A)),
        "Rose" to intArrayOf(col(0xFFF6A6C1), col(0xFFC2185B)), "Gold" to intArrayOf(col(0xFFFDDB92), col(0xFFD1913C)), "Slate" to intArrayOf(col(0xFF4B5563), col(0xFF111827)), "Mint" to intArrayOf(col(0xFFA8EDEA), col(0xFF2BB5A0)),
        "Berry" to intArrayOf(col(0xFFEE0979), col(0xFF6A11CB)), "Sky" to intArrayOf(col(0xFF89F7FE), col(0xFF66A6FF)), "Sand" to intArrayOf(col(0xFFE6D5B8), col(0xFFA9865B)), "Night" to intArrayOf(col(0xFF232526), col(0xFF414345)))
    fun bitmap(colors: IntArray, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b); val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), colors[0], colors[1], android.graphics.Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); return b
    }
    fun open(id: String, folder: Boolean, has: Boolean) {
        val c = act; val body = vbox(c); lateinit var layer: Layer
        val grid = GridLayout(c); grid.columnCount = 4; grid.setPadding(dp(14), dp(10), dp(14), dp(4))
        for ((name, cols) in COVERS) {
            val cell = vbox(c); cell.gravity = Gravity.CENTER_HORIZONTAL; cell.fx(10f)
            val tv = ThumbView(c); tv.book = true; tv.show(bitmap(cols, 120, 160), null)
            cell.addView(tv, lp(dp(104), dp(134))); cell.addView(txt(c, name, 13f, Th.sub).apply { setPadding(0, dp(2), 0, dp(6)) })
            cell.setOnClickListener { layer.dismiss(); Store.work {
                val f = if (folder) Store.folderCover(id) else Store.coverFile(id); f.parentFile?.mkdirs()
                f.outputStream().use { bitmap(cols, 480, 640).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Store.main { Thumbs.dropCover(id); if (folder) Lib.folder(id)?.cover = true else Lib.note(id)?.cover = true; Thumbs.bump(); Lib.changed() } } }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = dp(118); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        body.addView(grid)
        val acts = ArrayList<Opt>(); acts.add(Act("Choose a picture…", "image") { layer.dismiss(); act.pickCover(id, folder) })
        if (has) acts.add(Act("Remove cover", "close", true) { layer.dismiss(); if (folder) Lib.folder(id)?.cover = false else Lib.note(id)?.cover = false; Thumbs.dropCover(id); Thumbs.bump(); Lib.changed() })
        body.addView(SettingsUi.panel(c, listOf(Group("", acts))))
        layer = sheet("Cover", body, 560)
    }
}

/** an image that keeps a fixed height : width ratio (page previews in the sidebar) */
class RatioImage(c: android.content.Context, private val ratio: Float) : ImageView(c) {
    override fun onMeasure(ws: Int, hs: Int) { val w = View.MeasureSpec.getSize(ws); setMeasuredDimension(w, (w * ratio).toInt().coerceAtLeast(1)) }
}
