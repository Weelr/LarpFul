package com.weelr.larpful2

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Compact floating toolbar. Press and hold it, then drag to any edge (as in the original Larpful): it turns vertical on the
 *  sides and horizontal at top/bottom. */
class PenBar(val ed: Editor) {
    /** The bar itself. A long press anywhere on it picks it up; a normal tap goes to the buttons. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    inner class BarView : LinearLayout(act) {
        private var ox = 0f; private var oy = 0f; private var sx = 0f; private var sy = 0f; private var armed = false
        private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
        private val pick = Runnable {
            if (armed && !P.b("tb.lock")) {
                armed = false; dragging = true; ctx.visibility = View.INVISIBLE; animate().cancel(); alpha = 0.85f
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        private fun down(e: MotionEvent) { armTimers(); sx = e.rawX; sy = e.rawY; ox = e.rawX - translationX; oy = e.rawY - translationY; armed = true; removeCallbacks(pick); postDelayed(pick, 350) }
        private fun disarm() { armed = false; removeCallbacks(pick) }
        private fun moved(e: MotionEvent) = abs(e.rawX - sx) > slop || abs(e.rawY - sy) > slop
        /** The rebuild is posted: removing the buttons while their touch is still being delivered used to crash. */
        private fun drop() {
            dragging = false; alpha = 1f
            val w = ed.root.width.toFloat(); val h = ed.root.height.toFloat(); val top = ed.topInset().toFloat()
            val cx = translationX + width / 2f; val cy = translationY + height / 2f
            val dl = cx; val dr = w - cx; val dt = cy - top; val db = h - cy
            val m = min(min(dl, dr), min(dt, db))
            val ne = if (m == dl) "left" else if (m == dr) "right" else if (m == dt) "top" else "bottom"
            val pos = if (ne == "left" || ne == "right") ((cy - top) / max(1f, h - top)) else (cx / max(1f, w))
            P.set("tb.pos", pos.coerceIn(0.05f, 0.95f), false); P.set("tb.edge", ne, false)
            post { rebuild() }
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(e)
                MotionEvent.ACTION_MOVE -> { if (dragging) return true; if (armed && moved(e)) disarm() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { disarm(); if (dragging) { drop(); return true } }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(e)
                MotionEvent.ACTION_MOVE -> { if (dragging) { translationX = e.rawX - ox; translationY = e.rawY - oy } else if (armed && moved(e)) disarm() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { disarm(); if (dragging) drop() }
            }
            return true
        }
    }
    val bar: LinearLayout = BarView()
    val ctx = LinearLayout(act)
    private var edge = "left"
    private val toolBtns = HashMap<String, View>()
    private var dragging = false
    private var undoBtn: ImageView? = null; private var redoBtn: ImageView? = null
    private var sliding = false
    private val endSlide = Runnable { sliding = false }
    private val K = 595f / 794f   // the original measured sizes in screen px of an A4 page 794 px wide; pages here are 595 pt

    init {
        ed.root.addView(bar, flp(WC, WC)); ed.root.addView(ctx, flp(WC, WC))
        val relay = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) place(false) }
        ed.root.addOnLayoutChangeListener(relay); bar.addOnLayoutChangeListener(relay); ctx.addOnLayoutChangeListener(relay); ed.top.addOnLayoutChangeListener(relay); ed.audioSlot.addOnLayoutChangeListener(relay)
        ed.tabBar.addOnLayoutChangeListener(relay)
        Tools.listeners.add { ctxHidden = !P.b("cb.auto"); rebuild() }
        P.listeners.add { k -> if (k == "*" || k == "favs" || k == "g.strike" || listOf("tb.", "cb.", "pen", "ball", "hl", "er.", "shp.", "tx.", "ls.", "ui.").any { k.startsWith(it) }) { if (!sliding) rebuild() } }
        rebuild()
    }
    private val vertical get() = edge == "left" || edge == "right"
    private fun bs(): Int = when (P.s("tb.size")) { "regular" -> 44; "large" -> 52; else -> 38 }
    /** the tool card folds away when writing starts / after a delay, and comes back when a tool is picked (Smart Toolbar settings) */
    private var ctxHidden = false
    private var autoMin = false
    private val hideCtx = Runnable { if (ed.focused?.view?.sel == null) { ctxHidden = true; place(false) } }
    private val idleMin = Runnable { if (P.f("tb.autoMin") > 0f && !P.b("tb.min")) { autoMin = true; rebuild() } }
    fun onInk() { if (!ctxHidden && P.s("cb.collapse") == "draw" && ed.focused?.view?.sel == null) { ctxHidden = true; if (ctx.visibility != View.GONE) ctx.visibility = View.GONE } }
    private fun armTimers() {
        ctx.removeCallbacks(hideCtx); if (P.s("cb.collapse") == "timer" && !ctxHidden) ctx.postDelayed(hideCtx, (P.f("cb.delay") * 1000).toLong().coerceAtLeast(1000))
        bar.removeCallbacks(idleMin); val am = P.f("tb.autoMin"); if (am > 0f && !autoMin) bar.postDelayed(idleMin, (am * 1000).toLong())
    }
    private fun penTool(): String = if (Tools.isPen(Tools.cur)) Tools.cur else if (Tools.isPen(Tools.prev)) Tools.prev else "pen"

    /** Look of the original Larpful: frosted-white rounded bar, 38pt buttons, the active tool tinted blue, a thin colour strip on each pen. */
    fun rebuild() {
        edge = P.s("tb.edge")
        val c = act; val s = bs()
        for (b in listOf(bar, ctx)) { b.removeAllViews(); b.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; b.gravity = Gravity.CENTER; b.elevation = dpf(7) }
        bar.background = rr(Th.glass, dpf(18), Th.hair, 1); bar.setPadding(dp(3), dp(3), dp(3), dp(3))
        toolBtns.clear(); undoBtn = null; redoBtn = null
        val cur = Tools.cur; val all = Tools.all()
        if (P.b("tb.min") || autoMin) {
            // "Collapse": only the tool in hand and a button to bring the bar back
            if (cur in all) bar.addView(toolButton(cur, s))
            bar.addView(small("max", s, true) { autoMin = false; if (P.b("tb.min")) P.set("tb.min", false) else rebuild() })
        } else {
            val favOnly = P.b("tb.favOnly"); val pinned = Tools.pinned()
            for (t in all) { if (favOnly && t !in pinned && t != cur) continue; bar.addView(toolButton(t, s)) }
            val favs = Favs.list()
            if (!favOnly && P.b("tb.presets") && favs.length() > 0) {
                bar.addView(sep())
                for (i in 0 until min(3, favs.length())) {
                    val wrap = FrameLayout(c); val d = View(c); d.background = rr(favs.getJSONObject(i).optInt("c") or col(0xFF000000), dpf(8), Th.sep, dp(2))
                    wrap.addView(d, flp(dp(16), dp(16), Gravity.CENTER)); wrap.fx(11f); wrap.setOnClickListener { Favs.use(i) }
                    bar.addView(wrap, lp(dp(s), dp(s)))
                }
            }
            bar.addView(sep())
            if (P.b("tb.undo")) {
                val pv = ed.focused?.view
                val u = small("undo", s, (pv?.undo?.isNotEmpty() == true)) { ed.focused?.view?.doUndo() }; val r = small("redo", s, (pv?.redo?.isNotEmpty() == true)) { ed.focused?.view?.doRedo() }
                bar.addView(u); bar.addView(r); undoBtn = u as ImageView; redoBtn = r as ImageView
            }
            if (P.b("tb.dot")) {
                val dc = when (Tools.base(cur)) { "shape" -> P.i("shp.color"); else -> P.i(penTool() + ".color") }
                val wrap = FrameLayout(c); val dot = View(c); dot.background = rr(dc or col(0xFF000000), dpf(10), Th.sep, dp(2))
                wrap.addView(dot, flp(dp(20), dp(20), Gravity.CENTER)); wrap.fx(11f)
                wrap.setOnClickListener { if (Tools.base(Tools.cur) == "shape") shapePop() else penPop(penTool()) }
                bar.addView(wrap, lp(dp(s), dp(s)))
            }
            if (P.b("tb.sets")) bar.addView(small("sets", s, true) { setsMenu() })
            bar.addView(small("more", s, true) { morePop() })
        }
        buildCtx()
        // "Tool options panel size": the whole card is scaled, anchored on the side that touches the bar
        val k = (P.s("cb.sz").toFloatOrNull() ?: 85f) / 85f; ctx.scaleX = k; ctx.scaleY = k
        place(false); armTimers()
    }
    private fun toolButton(t: String, s: Int): View {
        val c = act; val b = Tools.base(t)
        val active = if (b == "ruler") Tools.ruler else Tools.cur == t
        val v = ImageView(c); v.setImageDrawable(Icon(Tools.icon(t), if (active) Th.accent else Th.text, if (active) 2f else 1.8f))
        val pad = (s - 21) / 2; v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        v.background = if (active) rr(Th.sel, dpf(11)) else null
        v.fx(11f); v.setOnClickListener { tapTool(t, v) }
        toolBtns[t] = v
        val strip = if (!P.b("tb.mark")) 0 else if (Tools.isPen(t)) P.i("$t.color") else if (b == "shape") P.i("shp.color") else if (b == "tape") P.i("tape.color") else 0
        if (strip == 0) { v.layoutParams = lp(dp(s), dp(s)); return v }
        val wrap = FrameLayout(c); wrap.addView(v, flp(dp(s), dp(s)))   // thin strip in the tool's ink colour
        val sv = View(c); sv.background = rr(strip or col(0xFF000000), dpf(2), col(0x40000000), 1)
        wrap.addView(sv, flp(MP, dp(3), Gravity.TOP).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(2) })
        wrap.layoutParams = lp(dp(s), dp(s)); return wrap
    }
    private fun sep(): View = View(act).apply { setBackgroundColor(Th.sep); layoutParams = if (vertical) lp(dp(20), 1).apply { topMargin = dp(3); bottomMargin = dp(3) } else lp(1, dp(20)).apply { leftMargin = dp(3); rightMargin = dp(3) } }
    private fun small(icon: String, s: Int, enabled: Boolean, on: () -> Unit): View {
        val v = ImageView(act); v.setImageDrawable(Icon(icon, if (enabled) Th.text else Th.fill2)); val pad = (s - 20) / 2; v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        v.layoutParams = lp(dp(s), dp(s)); v.fx(11f); v.setOnClickListener { on() }; return v
    }

    /** positions the bar from the saved edge + position; the tool card sits beside it, level with the bar's start (as in the original) */
    fun place(animate: Boolean) {
        if (dragging) return
        val w = ed.root.width; val h = ed.root.height; if (w == 0 || h == 0) return
        val top = ed.topInset(); val m = dp(10); val pos = P.f("tb.pos")
        val bw = bar.width; val bh = bar.height
        var x = 0f; var y = 0f
        when (edge) {
            "left" -> { x = m.toFloat(); y = top + (h - top) * pos - bh / 2f }
            "right" -> { x = (w - bw - m).toFloat(); y = top + (h - top) * pos - bh / 2f }
            "top" -> { y = (top + m).toFloat(); x = w * pos - bw / 2f }
            else -> { y = (h - bh - m).toFloat(); x = w * pos - bw / 2f }
        }
        x = x.coerceIn(m.toFloat(), max(m, w - bw - m).toFloat()); y = y.coerceIn((top + m).toFloat(), max(top + m, h - bh - m).toFloat())
        bar.translationX = x; bar.translationY = y
        val sk = ctx.scaleX; val cw = (ctx.width * sk).toInt(); val ch = (ctx.height * sk).toInt(); var cx = 0f; var cy = 0f
        ctx.pivotX = 0f; ctx.pivotY = 0f
        when (edge) {
            "left" -> { cx = x + bw + dp(10); cy = y }
            "right" -> { cx = x - cw - dp(10); cy = y }
            "top" -> { cy = y + bh + dp(10); cx = x }
            else -> { cy = y - ch - dp(10); cx = x }
        }
        ctx.translationX = cx.coerceIn(dp(8).toFloat(), max(dp(8), w - cw - dp(8)).toFloat()); ctx.translationY = cy.coerceIn((top + dp(8)).toFloat(), max(top + dp(8), h - ch - dp(8)).toFloat())
        val vis = if (ctx.childCount > 0 && P.b("tb.ctx") && !ctxHidden && bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (ctx.visibility != vis) ctx.visibility = vis
    }
    fun updateUndo() {
        val pv = ed.focused?.view
        undoBtn?.setImageDrawable(Icon("undo", if (pv?.undo?.isNotEmpty() == true) Th.text else Th.fill2))
        redoBtn?.setImageDrawable(Icon("redo", if (pv?.redo?.isNotEmpty() == true) Th.text else Th.fill2))
    }

    private fun tapTool(t: String, v: View) {
        ed.commitText()
        when (Tools.base(t)) {
            "ruler" -> { Tools.ruler = !Tools.ruler; ed.invalidatePanes(); rebuild() }
            "image" -> act.pickImage()
            else -> if (Tools.cur != t) Tools.set(t)
                else if (ctxHidden && P.b("tb.ctx") && ctx.childCount > 0) { ctxHidden = false; place(false); armTimers() }   // first tap brings the card back
                else openPop(t, v)
        }
    }
    /** tapping the tool that is already in hand opens its full card */
    private fun openPop(t: String, v: View) {
        when (Tools.base(t)) { "pen", "ball", "hl" -> penPop(t); "eraser" -> eraserPop(); "shape" -> shapePop(); "lasso", "text", "shot", "tape" -> toolSettings(t, v) }
    }
    fun toolSettings(t: String, v: View) { SettingsUi.tool(t) { ed.invalidatePanes() } }

    /* ---------- small building blocks shared by the cards ---------- */
    private fun keepAlive() { sliding = true; ctx.removeCallbacks(endSlide); ctx.postDelayed(endSlide, 700) }
    private fun half(v: Float): String = if (v == Math.round(v).toFloat()) Math.round(v).toString() else (Math.round(v * 100f) / 100f).toString()
    /** "Size ——o—— 3" on one line (the compact card) */
    private fun inlineSlider(row: LinearLayout, label: String, steps: Int, value: Int, numW: Int, show: (Int) -> String, on: (Int) -> Unit) {
        val c = act; val lab = txt(c, label, 12f, Th.sub); val num = txt(c, show(value.coerceIn(0, steps)), 12f, Th.text, true); num.gravity = Gravity.END
        val sl = Slider(c, steps, value.coerceIn(0, steps)) { v -> keepAlive(); num.text = show(v); on(v); ed.invalidatePanes() }
        row.addView(lab, lp(WC, WC)); row.addView(sl, lp(0, dp(34), 1f)); row.addView(num, lp(dp(numW), WC).apply { rightMargin = dp(8) })
    }
    /** "Thickness 3" above a full-width slider (the full card) */
    private fun stackSlider(box: LinearLayout, label: String, steps: Int, value: Int, show: (Int) -> String, on: (Int) -> Unit) {
        val c = act; val head = hbox(c); head.addView(txt(c, "$label ", 13f, Th.sub)); val num = txt(c, show(value.coerceIn(0, steps)), 13f, Th.text, true); head.addView(num)
        box.addView(head, lp(MP, WC).apply { topMargin = dp(10); leftMargin = dp(4) })
        box.addView(Slider(c, steps, value.coerceIn(0, steps)) { v -> num.text = show(v); on(v); ed.invalidatePanes() }, lp(MP, dp(34)))
    }
    private fun chip(label: String, on: Boolean = false, icon: String? = null, danger: Boolean = false, run: (View) -> Unit): LinearLayout {
        val c = act; val h = hbox(c); h.gravity = Gravity.CENTER; h.background = rr(if (on) Th.accent else Th.fill, dpf(100)); h.setPadding(dp(12), dp(6), dp(12), dp(6)); h.fx(100f)
        val col2 = if (on) Color.WHITE else if (danger) Th.danger else Th.text
        if (icon != null) h.addView(ImageView(c).apply { setImageDrawable(Icon(icon, col2)) }, lp(dp(16), dp(16)).apply { rightMargin = dp(6) })
        h.addView(txt(c, label, 14f, col2)); h.setOnClickListener { run(it) }; return h
    }
    private fun wide(v: View): View { v.layoutParams = lp(MP, WC).apply { topMargin = dp(8) }; return v }
    private fun endRow(v: View, w: Int = MP): LinearLayout { val r = hbox(act); r.gravity = Gravity.END; r.addView(v, lp(WC, WC)); r.layoutParams = lp(w, WC).apply { topMargin = dp(8) }; return r }
    private fun switchRow(label: String, value: Boolean, cb: (Boolean) -> Unit): LinearLayout {
        val c = act; val v = vbox(c); val r = hbox(c); r.setPadding(dp(12), dp(8), dp(12), dp(8)); r.minimumHeight = dp(54)
        r.addView(txt(c, label, 16f), lp(0, WC, 1f).apply { rightMargin = dp(10) }); r.addView(Sw(c, value, cb)); v.addView(r, lp(MP, WC))
        v.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { leftMargin = dp(12) }); return v
    }
    private fun popTitle(s: String): View = txt(act, s, 15f, Th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(2)) }
    /** the quick colours: saved favourites first, then the palette (the original's rule) */
    private fun quick(n: Int): List<Int> {
        val l = LinkedHashSet<Int>(); val f = Favs.list(); if (P.s("cb.src") != "palette") for (i in 0 until f.length()) l.add(f.getJSONObject(i).optInt("c") or col(0xFF000000)); for (pc in PALETTE) l.add(pc)
        return l.take(n)
    }
    private fun swatches(n: Int, cur: Int, plus: ((View) -> Unit)?, pick: (Int) -> Unit): LinearLayout {
        val c = act; val row = hbox(c)
        for (sc in quick(n)) { val sw = Swatch(c, sc, (sc and 0xFFFFFF) == (cur and 0xFFFFFF)); sw.fx(15f); sw.setOnClickListener { pick(sc) }; row.addView(sw, lp(dp(30), dp(30)).apply { rightMargin = dp(7) }) }
        if (plus != null) { val p = ImageView(c); p.setImageDrawable(Icon("plus", Th.sub)); p.setPadding(dp(8), dp(8), dp(8), dp(8)); p.background = rr(Th.fill, dpf(15), Th.sep, 1); p.fx(15f); p.setOnClickListener { plus(it) }; row.addView(p, lp(dp(30), dp(30))) }
        return row
    }
    /** all 24 colours, seven to a row, then the colour well + hex field */
    private fun palette(box: LinearLayout, cur: Int, pick: (Int) -> Unit) {
        val c = act; val g = android.widget.GridLayout(c); g.columnCount = 7
        for (pc in PALETTE) { val sw = Swatch(c, pc, (pc and 0xFFFFFF) == (cur and 0xFFFFFF)); sw.fx(16f); sw.setOnClickListener { pick(pc) }
            g.addView(sw, android.widget.GridLayout.LayoutParams().apply { width = dp(30); height = dp(30); setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
        box.addView(g, lp(WC, WC).apply { topMargin = dp(8) })
        val row = hbox(c); val well = View(c); well.background = rr(cur or col(0xFF000000), dpf(4), Th.sep, 1); well.fx(4f)
        well.setOnClickListener { v -> colorPicker(v, cur or col(0xFF000000)) { nc -> pick(nc) } }
        val hex = txt(c, String.format("#%06x", cur and 0xFFFFFF), 15f); hex.background = rr(Th.fill, dpf(8)); hex.setPadding(dp(8), dp(8), dp(8), dp(8)); hex.fx(8f)
        hex.setOnClickListener { prompt("Hex colour", hex.text.toString()) { s -> try { pick(Color.parseColor(if (s.startsWith("#")) s else "#$s") or col(0xFF000000)) } catch (e: Exception) { toast("Invalid colour") } } }
        row.addView(well, lp(dp(32), dp(22)).apply { leftMargin = dp(6); rightMargin = dp(12) }); row.addView(hex, lp(dp(96), WC)); box.addView(row, lp(MP, WC).apply { topMargin = dp(10) })
    }
    /** a card beside the toolbar, in the place the original opens its pop-up */
    private fun showPop(content: View, widthDp: Int): Layer {
        val box = vbox(act); box.background = rr(Th.card, dpf(22), Th.hair, 1); box.elevation = dpf(14); box.clipToOutline = true
        val sv = android.widget.ScrollView(act); sv.isVerticalScrollBarEnabled = false; sv.addView(content, FrameLayout.LayoutParams(MP, WC)); box.addView(sv, lp(MP, WC))
        val rw = act.root.width; val rh = act.root.height; val w = min(dp(widthDp), rw - dp(16))
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(rh - dp(16), View.MeasureSpec.AT_MOST)); val h = box.measuredHeight
        val a = IntArray(2); bar.getLocationInWindow(a); val r = IntArray(2); act.root.getLocationInWindow(r); val bx = a[0] - r[0]; val by = a[1] - r[1]
        var x = bx; var y = by
        when (edge) { "left" -> x = bx + bar.width + dp(10); "right" -> x = bx - w - dp(10); "top" -> y = by + bar.height + dp(10); else -> y = by - h - dp(10) }
        val p = flp(w, WC); p.leftMargin = x.coerceIn(dp(8), max(dp(8), rw - w - dp(8))); p.topMargin = y.coerceIn(dp(8), max(dp(8), rh - h - dp(8)))
        sliding = true
        return Layer(box, dim = false, onDismiss = { sliding = false; rebuild() }).show(p, 0)
    }

    /* ---------- full cards (the original's pop-up) ---------- */
    fun penPop(t: String) {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(12), dp(16), dp(16)); val isHl = Tools.base(t) == "hl"
        lateinit var l: Layer
        fun fill() {
            body.removeAllViews()
            body.addView(popTitle(if (isHl) "Highlighter" else if (Tools.isBall(t)) "Ballpoint" else "Ink pen"))
            if (isHl) stackSlider(body, "Thickness", 36, Math.round(P.f("$t.width") / (3f * K) - 4f), { v -> (4 + v).toString() }) { v -> P.set("$t.width", (4 + v) * 3f * K) }
            else stackSlider(body, "Thickness", 46, Math.round((P.f("$t.width") / K - 1f) * 2f), { v -> half(1f + v * 0.5f) }) { v -> P.set("$t.width", (1f + v * 0.5f) * K) }
            stackSlider(body, "Opacity", 18, Math.round((P.f("$t.alpha") - 10f) / 5f), { v -> half((10 + v * 5) / 100f) }) { v -> P.set("$t.alpha", 10f + v * 5f) }
            palette(body, P.i("$t.color")) { nc -> P.set("$t.color", nc); fill() }
            if (!isHl) {
                val st = hbox(c); st.addView(chip("Ink pen", !Tools.isBall(t)) { P.set("$t.style", "ink"); fill() }, lp(WC, WC).apply { rightMargin = dp(8) })
                st.addView(chip("Ballpoint", Tools.isBall(t)) { P.set("$t.style", "bp"); fill() }); body.addView(st, lp(MP, WC).apply { topMargin = dp(12) })
            }
            body.addView(switchRow("Auto-shape (hold to snap)", P.s("$t.shape") != "off") { on -> P.set("$t.shape", if (on) "hold" else "off") }, lp(MP, WC).apply { topMargin = dp(6) })
            if (!isHl) body.addView(switchRow("Pressure sensitivity", P.b("$t.press")) { on -> P.set("$t.press", on) })
            body.addView(wide(chip("★ Save as favorite") { Favs.save(t); toast("Saved to favorites") }))
            body.addView(wide(chip(if (isHl) "Highlighter settings" else "Pen settings", false, "gear") { v -> toolSettings(t, v) }))
            if (t != Tools.base(t)) body.addView(wide(chip("Remove this " + (if (isHl) "highlighter" else "pen"), false, "trash", true) { l.dismiss(); Tools.removePen(t) }))
        }
        fill(); l = showPop(body, 290)
    }
    fun eraserPop() {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(12), dp(16), dp(16))
        fun fill() {
            body.removeAllViews(); body.addView(popTitle("Eraser"))
            stackSlider(body, "Eraser size", 54, Math.round(P.f("er.size")) - 6, { v -> (6 + v).toString() }) { v -> P.set("er.size", (6 + v).toFloat()) }
            val st = hbox(c); val m = P.s("er.mode")
            st.addView(chip("Whole stroke", m == "stroke") { P.set("er.mode", "stroke"); fill() }, lp(WC, WC).apply { rightMargin = dp(8) }); st.addView(chip("Brush", m == "precise") { P.set("er.mode", "precise"); fill() })
            body.addView(st, lp(MP, WC).apply { topMargin = dp(8) })
            body.addView(switchRow("Strikethrough to delete (pen)", P.b("g.strike")) { on -> P.set("g.strike", on) }, lp(MP, WC).apply { topMargin = dp(6) })
            body.addView(switchRow("Auto-switch back to previous tool", P.b("er.back")) { on -> P.set("er.back", on) })
            body.addView(wide(chip("Eraser settings", false, "gear") { v -> toolSettings("eraser", v) }))
        }
        fill(); showPop(body, 336)
    }
    fun shapePop() {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(12), dp(16), dp(16))
        fun fill() {
            body.removeAllViews(); body.addView(popTitle("Shapes"))
            stackSlider(body, "Thickness", 46, Math.round((P.f("shp.width") / K - 1f) * 2f), { v -> half(1f + v * 0.5f) }) { v -> P.set("shp.width", (1f + v * 0.5f) * K) }
            stackSlider(body, "Opacity", 18, Math.round((P.f("shp.alpha") - 10f) / 5f), { v -> half((10 + v * 5) / 100f) }) { v -> P.set("shp.alpha", 10f + v * 5f) }
            palette(body, P.i("shp.color")) { nc -> P.set("shp.color", nc); fill() }
            val st = hbox(c); val k = P.s("shp.kind")
            for (v in listOf("line", "rect", "ell")) st.addView(chip(v, k == v) { P.set("shp.kind", v); fill() }, lp(WC, WC).apply { rightMargin = dp(8) })
            body.addView(st, lp(MP, WC).apply { topMargin = dp(12) })
        }
        fill(); showPop(body, 290)
    }
    /** ⋯ : the original's "Toolbar" card — pin, reorder, add pens, favourite colours, collapse */
    fun morePop() {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(12), dp(16), dp(16))
        lateinit var l: Layer
        fun order(id: String, d: Int) { val o = ArrayList(Tools.all()); val i = o.indexOf(id); val j = i + d; if (i < 0 || j < 0 || j >= o.size) return; o[i] = o[j]; o[j] = id; P.set("tb.order", o.joinToString(",")) }
        fun fill() {
            body.removeAllViews(); body.addView(popTitle("Toolbar"))
            body.addView(switchRow("Show only pinned tools", P.b("tb.favOnly")) { on -> P.set("tb.favOnly", on) })
            body.addView(switchRow("Show colour circle", P.b("tb.dot")) { on -> P.set("tb.dot", on) })
            val all = Tools.all(); val pinned = Tools.pinned()
            all.forEachIndexed { i, t ->
                val row = hbox(c); row.setPadding(dp(12), 0, dp(4), 0)
                val name = hbox(c); name.fx(8f)
                name.addView(ImageView(c).apply { setImageDrawable(Icon(Tools.icon(t), Th.text)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(12) })
                name.addView(txt(c, Tools.name(t), 16f).oneLine(), lp(0, WC, 1f))
                name.setOnClickListener { v -> l.dismiss(); tapTool(t, v) }
                row.addView(name, lp(0, MP, 1f))
                fun arrow(icon: String, on: Boolean, d: Int) { row.addView(ImageView(c).apply { setImageDrawable(Icon(icon, Th.text)); alpha = if (on) 1f else 0.25f; setPadding(dp(9), dp(9), dp(9), dp(9)); fx(8f); setOnClickListener { if (on) { order(t, d); fill() } } }, lp(dp(30), dp(34))) }
                arrow("tri", i > 0, -1); arrow("trid", i < all.size - 1, 1)
                val pin = t in pinned
                row.addView(ImageView(c).apply { setImageDrawable(Icon(if (pin) "starf" else "star", if (pin) Th.accent else Th.sub)); setPadding(dp(7), dp(7), dp(7), dp(7)); fx(8f)
                    setOnClickListener { P.set("tb.favs", (if (pin) pinned - t else pinned + t).joinToString(",")); fill() } }, lp(dp(34), dp(34)))
                body.addView(row, lp(MP, dp(54))); body.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { leftMargin = dp(12) })
            }
            val fl = Flow(c)
            fl.addView(chip("+ Pen") { Tools.set(Tools.addPen("pen")); fill() }); fl.addView(chip("+ Ballpoint") { val id = Tools.addPen("bp"); P.set("$id.style", "bp", false); Tools.set(id); fill() })
            fl.addView(chip("+ Highlighter") { Tools.set(Tools.addPen("hl")); fill() })
            fl.addView(chip("★ Colors") { l.dismiss(); favsPop() }); fl.addView(chip("Collapse") { l.dismiss(); P.set("tb.min", true) })
            fl.addView(chip("Toolbar settings", false, "gear") { v -> toolSettings("toolbar", v) }); fl.addView(chip("Ruler settings", false, "gear") { v -> toolSettings("ruler", v) })
            body.addView(fl, lp(MP, WC).apply { topMargin = dp(10) })
        }
        fill(); l = showPop(body, 300)
    }
    fun favsPop() {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(12), dp(16), dp(16))
        lateinit var l: Layer
        fun fill() {
            body.removeAllViews(); body.addView(popTitle("Favorite colors"))
            val f = Favs.list()
            if (f.length() == 0) body.addView(txt(c, "No favorites yet. Open a pen's More options… and tap ★ Save as favorite.", 13f, Th.sub).apply { setPadding(dp(4), dp(8), dp(4), dp(4)); setLineSpacing(dpf(3), 1f) })
            for (i in 0 until f.length()) {
                val o = f.getJSONObject(i); val row = hbox(c); row.setPadding(dp(8), 0, dp(2), 0); row.fx(10f)
                row.addView(Swatch(c, o.optInt("c") or col(0xFF000000), false), lp(dp(30), dp(30)))
                val hl = o.optString("k") == "hl"; val w = o.optDouble("w", 2.0).toFloat()
                row.addView(txt(c, (if (hl) "Highlighter" else "Pen") + " · " + half(if (hl) Math.round(w / (3f * K)).toFloat() else Math.round(w / K * 2f) / 2f) + " · " + Math.round(o.optDouble("a", 100.0)) + "%", 15f).oneLine().apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WC, 1f))
                row.addView(ibtn(c, "trash", 34, Th.sub, 9) { Favs.remove(i); fill() })
                row.setOnClickListener { l.dismiss(); Favs.use(i) }
                body.addView(row, lp(MP, dp(46)))
            }
        }
        fill(); l = showPop(body, 300)
    }

    /* ---------- the card that follows the tool in hand ---------- */
    private fun buildCtx() {
        val t = Tools.cur; val c = act; val b = Tools.base(t)
        ctx.orientation = LinearLayout.VERTICAL; ctx.gravity = Gravity.START
        ctx.background = rr(Th.glass, dpf(18), Th.hair, 1); ctx.setPadding(dp(12), dp(8), dp(10), dp(10))
        if (P.b("tb.min")) return
        fun hint(s: String, w: Int) { ctx.addView(txt(c, s, 12.5f, Th.sub).apply { setLineSpacing(dpf(2), 1f); setPadding(dp(2), dp(4), dp(2), dp(2)) }, lp(dp(w), WC)) }
        val labels = P.b("cb.labels")
        fun iconBtn(row: LinearLayout, icon: String, label: String, active: Boolean = false, enabled: Boolean = true, tint: Int = Th.text, on: (View) -> Unit) {
            val v = ImageView(c); v.setImageDrawable(Icon(icon, if (active) Th.accent else if (enabled) tint else Th.fill2)); v.setPadding(dp(8), dp(8), dp(8), dp(8))
            if (!labels) { v.background = if (active) rr(Th.sel, dpf(10)) else null; v.fx(10f); v.setOnClickListener { if (enabled) on(it) }; row.addView(v, lp(dp(38), dp(38)).apply { rightMargin = dp(2) }); return }
            val col2 = vbox(c); col2.gravity = Gravity.CENTER_HORIZONTAL; col2.background = if (active) rr(Th.sel, dpf(10)) else null; col2.fx(10f); col2.setOnClickListener { if (enabled) on(it) }
            col2.addView(v, lp(dp(36), dp(34))); col2.addView(txt(c, label, 9.5f, Th.sub).oneLine().apply { gravity = Gravity.CENTER }, lp(dp(48), WC).apply { bottomMargin = dp(3) }); row.addView(col2, lp(dp(48), WC).apply { rightMargin = dp(1) })
        }
        val showT = P.b("cb.thick"); val showO = P.b("cb.opac"); val showC = P.b("cb.colors")
        when {
            Tools.isPen(t) -> {
                // Size and Opacity, the quick colours with "+", "More options…" and the tool's settings
                val isHl = b == "hl"; val cardW = dp(400); val row = hbox(c)
                if (showT) { if (isHl) inlineSlider(row, "Size", 36, Math.round(P.f("$t.width") / (3f * K) - 4f), 30, { v -> (4 + v).toString() }) { v -> P.set("$t.width", (4 + v) * 3f * K) }
                    else inlineSlider(row, "Size", 46, Math.round((P.f("$t.width") / K - 1f) * 2f), 30, { v -> half(1f + v * 0.5f) }) { v -> P.set("$t.width", (1f + v * 0.5f) * K) } }
                if (showO) inlineSlider(row, "Opacity", 18, Math.round((P.f("$t.alpha") - 10f) / 5f), 30, { v -> half((10 + v * 5) / 100f) }) { v -> P.set("$t.alpha", 10f + v * 5f) }
                if (showT || showO) ctx.addView(row, lp(cardW, WC))
                if (showC) ctx.addView(swatches(P.i("tb.swatches").coerceIn(3, 8), P.i("$t.color"), { penPop(t) }) { sc -> P.set("$t.color", sc) }, lp(cardW, WC).apply { topMargin = dp(4) })
                ctx.addView(endRow(chip("More options…") { penPop(t) }, cardW))
                ctx.addView(endRow(chip(if (isHl) "Highlighter settings" else "Pen settings", false, "gear") { v -> toolSettings(t, v) }, cardW))
            }
            b == "shape" -> {
                val cardW = dp(400); val kinds = hbox(c); val k = P.s("shp.kind")
                iconBtn(kinds, "shLine", "Line", k == "line") { P.set("shp.kind", "line") }; iconBtn(kinds, "shRect", "Rectangle", k == "rect") { P.set("shp.kind", "rect") }; iconBtn(kinds, "shEll", "Ellipse", k == "ell") { P.set("shp.kind", "ell") }
                ctx.addView(kinds, lp(cardW, WC))
                val row = hbox(c)
                inlineSlider(row, "Size", 46, Math.round((P.f("shp.width") / K - 1f) * 2f), 30, { v -> half(1f + v * 0.5f) }) { v -> P.set("shp.width", (1f + v * 0.5f) * K) }
                inlineSlider(row, "Opacity", 18, Math.round((P.f("shp.alpha") - 10f) / 5f), 30, { v -> half((10 + v * 5) / 100f) }) { v -> P.set("shp.alpha", 10f + v * 5f) }
                ctx.addView(row, lp(cardW, WC))
                if (showC) ctx.addView(swatches(P.i("tb.swatches").coerceIn(3, 8), P.i("shp.color"), { shapePop() }) { sc -> P.set("shp.color", sc) }, lp(cardW, WC).apply { topMargin = dp(4) })
            }
            b == "eraser" -> {
                val cardW = dp(360)
                ctx.addView(segmented(c, listOf("Stroke", "Pixel"), if (P.s("er.mode") == "precise") 1 else 0) { i -> P.set("er.mode", if (i == 1) "precise" else "stroke") }, lp(cardW, WC))
                ctx.addView(txt(c, "ERASE ONLY", 10.5f, Th.sub, true).apply { setPadding(dp(2), dp(8), 0, dp(6)) })
                val tg = hbox(c); val cur = P.s("er.target")
                for ((v, lab) in listOf("all" to "All", "ink" to "Ink", "hl" to "Highlights", "shapes" to "Shapes", "media" to "Text/Images")) tg.addView(chip(lab, cur == v) { P.set("er.target", v) }, lp(WC, WC).apply { rightMargin = dp(6) })
                val hs = android.widget.HorizontalScrollView(c); hs.isHorizontalScrollBarEnabled = false; hs.addView(tg); ctx.addView(hs, lp(cardW, WC))
                val row = hbox(c); inlineSlider(row, "Size", 74, Math.round(P.f("er.size")) - 6, 30, { v -> (6 + v).toString() }) { v -> P.set("er.size", (6 + v).toFloat()) }
                ctx.addView(row, lp(cardW, WC).apply { topMargin = dp(4) })
                val what = when (cur) { "ink" -> "ink"; "hl" -> "highlights"; "shapes" -> "shapes"; "media" -> "text/images"; else -> "page" }
                ctx.addView(endRow(chip("Clear $what") { ed.focused?.view?.clearPage() }, cardW))
                ctx.addView(endRow(chip("Eraser settings", false, "gear") { v -> toolSettings("eraser", v) }, cardW))
            }
            b == "lasso" -> {
                val pv = ed.focused?.view
                if (pv?.sel != null) {
                    val cardW = dp(250)
                    val r1 = hbox(c)
                    iconBtn(r1, "copy", "Copy") { pv.selCopy(); toast("Copied") }; iconBtn(r1, "cut", "Cut") { pv.selCut() }; iconBtn(r1, "paste", "Paste", false, Clip.items.isNotEmpty()) { pv.paste() }
                    iconBtn(r1, "dup", "Duplicate") { pv.selDuplicate() }; iconBtn(r1, "trash", "Delete", false, true, Th.danger) { pv.selDelete() }
                    ctx.addView(r1, lp(cardW, WC))
                    val r2 = hbox(c)
                    iconBtn(r2, "minus", "Smaller") { pv.selScale(0.9f) }; iconBtn(r2, "plus", "Larger") { pv.selScale(1.1f) }; iconBtn(r2, "snip", "Snip") { ed.selToShelf() }; iconBtn(r2, "link", "Link…") { ed.selLink() }; iconBtn(r2, "share", "Share") { ed.selExport() }
                    ctx.addView(r2, lp(cardW, WC))
                    val r3 = hbox(c); r3.addView(swatches(5, 0, null) { sc -> pv.selRecolor(sc) }, lp(0, WC, 1f)); r3.addView(chip("Done") { pv.deselect() })
                    ctx.addView(r3, lp(cardW, WC).apply { topMargin = dp(6) })
                } else {
                    val cardW = dp(226)
                    hint("Draw around ink, a diagram or a PDF area to select it", 226)
                    ctx.addView(segmented(c, listOf("Freehand", "Rectangle"), if (P.s("ls.mode") == "rect") 1 else 0) { i -> P.set("ls.mode", if (i == 1) "rect" else "free") }, lp(cardW, WC).apply { topMargin = dp(6) })
                    if (Clip.items.isNotEmpty()) ctx.addView(endRow(chip("Paste", false, "paste") { ed.focused?.view?.paste() }, cardW))
                    ctx.addView(endRow(chip("Snippet Shelf", false, "snip") { ShelfUi.toggle(ed) }, cardW))
                    ctx.addView(endRow(chip("Lasso settings", false, "gear") { v -> toolSettings("lasso", v) }, cardW))
                }
            }
            b == "text" -> {
                val cardW = dp(230); val row = hbox(c)
                inlineSlider(row, "Size", 84, Math.round(P.f("tx.size") / K) - 12, 26, { v -> (12 + v).toString() }) { v -> P.set("tx.size", (12 + v) * K) }
                ctx.addView(row, lp(cardW, WC))
                ctx.addView(swatches(5, P.i("tx.color"), { v -> colorPicker(v, P.i("tx.color") or col(0xFF000000)) { nc -> P.set("tx.color", nc) } }) { sc -> P.set("tx.color", sc) }, lp(cardW, WC).apply { topMargin = dp(4) })
                ctx.addView(endRow(chip("Text settings", false, "gear") { v -> toolSettings("text", v) }, cardW))
            }
            b == "tape" -> {
                val cardW = dp(300); val row = hbox(c)
                inlineSlider(row, "Size", 62, Math.round(P.f("tape.width")) - 8, 30, { v -> (8 + v).toString() }) { v -> P.set("tape.width", (8 + v).toFloat()) }
                ctx.addView(row, lp(cardW, WC))
                if (showC) ctx.addView(swatches(P.i("tb.swatches").coerceIn(3, 8), P.i("tape.color"), { v -> colorPicker(v, P.i("tape.color") or col(0xFF000000)) { nc -> P.set("tape.color", nc) } }) { sc -> P.set("tape.color", sc) }, lp(cardW, WC).apply { topMargin = dp(4) })
                val r2 = hbox(c); r2.addView(chip("Reveal all", false, "eye") { ed.focused?.view?.allTapes(true) }, lp(WC, WC).apply { rightMargin = dp(8) }); r2.addView(chip("Cover all", false, "tape") { ed.focused?.view?.allTapes(false) })
                ctx.addView(r2, lp(cardW, WC).apply { topMargin = dp(6) })
                ctx.addView(endRow(chip("Tape settings", false, "gear") { v -> toolSettings("tape", v) }, cardW))
            }
            b == "move" -> hint("Tap an image or text box to move / resize it", 250)
            b == "shot" -> { hint("Drag over any part of a page to keep it as an image", 230); ctx.addView(endRow(chip("Screenshot settings", false, "gear") { v -> toolSettings("shot", v) }, dp(230))) }
        }
    }

    /* ---------- tool sets: saved combinations of pens, colours and eraser ---------- */
    private fun setKeys(): List<String> = Tools.all().filter { Tools.isPen(it) }.flatMap { t -> listOf("$t.width", "$t.color", "$t.smooth", "$t.alpha", "$t.style") } +
        listOf("pen.sens", "ball.sens", "pen.taper", "ball.taper", "er.size", "er.mode", "er.target", "shp.width", "shp.color", "shp.kind", "tape.width", "tape.color")
    fun setsMenu() {
        val c = act; val box = vbox(c); box.setPadding(dp(16), dp(12), dp(16), dp(16))
        val arr = try { org.json.JSONArray(P.s("toolsets")) } catch (e: Exception) { org.json.JSONArray() }
        lateinit var l: Layer
        fun dots(get: (String) -> Any?): View {
            val r = hbox(c)
            for (t in listOf("pen", "ball", "hl")) { val w = (get("$t.width") as? Number)?.toFloat() ?: 2f; val col2 = (get("$t.color") as? Number)?.toInt() ?: 0
                r.addView(WidthDot(c, w / (if (t == "hl") 40f else 12f) + 0.25f, false, col2 or col(0xFF000000)), lp(dp(26), dp(26))) }
            return r
        }
        box.addView(popTitle("Tool sets"))
        if (arr.length() == 0) box.addView(txt(c, "No sets yet. Choose your pens, then save them here.", 13f, Th.sub).apply { setPadding(dp(4), dp(10), dp(4), dp(8)); setLineSpacing(dpf(3), 1f) })
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val v = o.getJSONObject("v")
            val row = hbox(c); row.setPadding(dp(8), 0, dp(2), 0); row.fx(10f)
            row.addView(dots { k -> v.opt(k) }); row.addView(txt(c, o.optString("name"), 15f, Th.text).oneLine().apply { setPadding(dp(8), 0, 0, 0) }, lp(0, WC, 1f))
            row.addView(ibtn(c, "trash", 34, Th.sub, 9) { arr.remove(i); P.set("toolsets", arr.toString(), false); l.dismiss() })
            row.setOnClickListener {
                for (k in setKeys()) { if (!v.has(k)) continue; val d = P.get(k); val x = v.get(k)
                    when (d) { is Float -> (x as? Number)?.let { P.set(k, it.toFloat(), false) }; is Int -> (x as? Number)?.let { P.set(k, it.toInt(), false) }; is Boolean -> (x as? Boolean)?.let { P.set(k, it, false) }; else -> P.set(k, x.toString(), false) } }
                l.dismiss(); rebuild(); ed.invalidatePanes(); toast("Tool set “" + o.optString("name") + "”")
            }
            box.addView(row, lp(MP, dp(44)))
        }
        box.addView(wide(chip("＋ Save current tools as a set") { l.dismiss(); prompt("Name this tool set", "Set " + (arr.length() + 1)) { name ->
            val v = org.json.JSONObject(); for (k in setKeys()) P.get(k)?.let { v.put(k, it) }
            arr.put(org.json.JSONObject().put("name", name.ifEmpty { "Set" }).put("v", v)); P.set("toolsets", arr.toString(), false); toast("Saved") } }))
        l = showPop(box, 300)
    }
}

/** favourite pen presets ("★ Save as favorite"): colour + size + opacity; the first three sit on the toolbar */
object Favs {
    fun list(): org.json.JSONArray = try { org.json.JSONArray(P.s("favs")) } catch (e: Exception) { org.json.JSONArray() }
    fun save(t: String) {
        val a = list(); val o = org.json.JSONObject().put("c", P.i("$t.color")).put("w", P.f("$t.width").toDouble()).put("a", P.f("$t.alpha").toDouble()).put("k", if (Tools.base(t) == "hl") "hl" else "pen")
        val out = org.json.JSONArray(); out.put(o); for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optInt("c") != o.optInt("c") || x.optString("k") != o.optString("k")) out.put(x) }
        P.set("favs", out.toString())
    }
    fun remove(i: Int) { val a = list(); a.remove(i); P.set("favs", a.toString()) }
    fun use(i: Int) {
        val o = list().optJSONObject(i) ?: return; val hl = o.optString("k") == "hl"
        val cur = Tools.cur
        val t = if (Tools.isPen(cur) && (Tools.base(cur) == "hl") == hl) cur else if (hl) "hl" else (if (Tools.isPen(Tools.prev) && Tools.base(Tools.prev) != "hl") Tools.prev else "pen")
        P.set("$t.color", o.optInt("c"), false); P.set("$t.width", o.optDouble("w", 2.0).toFloat(), false); P.set("$t.alpha", o.optDouble("a", 100.0).toFloat(), false)
        if (Tools.cur == t) P.set("$t.color", o.optInt("c")) else Tools.set(t)
    }
}

class WidthDot(c: android.content.Context, val frac: Float, val selected: Boolean, val color: Int, val ring: Boolean = false) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        if (selected) { p.style = Paint.Style.FILL; p.color = Th.fill; cv.drawCircle(cx, cy, r - dpf(1), p) }
        val rad = (dpf(2) + (r - dpf(8)) * frac.coerceIn(0.05f, 1f))
        if (ring) { p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.6f) } else p.style = Paint.Style.FILL
        p.color = if (kotlin.math.abs(lum(color) - lum(Th.bg)) < 0.2f && !ring) Th.sub else color; cv.drawCircle(cx, cy, rad, p)
        if (selected) { p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.5f); p.color = Th.accent; cv.drawCircle(cx, cy, r - dpf(1.5f), p) }
    }
}

class Swatch(c: android.content.Context, val color: Int, val selected: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - dpf(2)
        val rr2 = if (selected) r - dpf(4) else r - dpf(1)
        p.style = Paint.Style.FILL; p.color = color; cv.drawCircle(cx, cy, rr2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1); p.color = if (Th.dark) col(0x66FFFFFF) else col(0x2E000000); cv.drawCircle(cx, cy, rr2, p)
        if (selected) { p.strokeWidth = dpf(2.2f); p.color = Th.accent; cv.drawCircle(cx, cy, r - dpf(0.5f), p) }
    }
}
