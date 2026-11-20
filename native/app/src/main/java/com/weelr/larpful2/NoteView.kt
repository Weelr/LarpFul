package com.weelr.larpful2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.*

object Tools {
    /** the original's toolbar order */
    val BASE = listOf("pen", "ball", "hl", "eraser", "lasso", "shape", "tape", "text", "image", "move", "shot", "ruler")
    val ALL: List<String> get() = all()
    var cur = "pen"
    var prev = "pen"
    var ruler = false
    val listeners = ArrayList<() -> Unit>()
    /** "pen2" is a second pen: same kind of tool, its own colour / size */
    fun base(t: String): String = t.trimEnd { it in '0'..'9' }
    fun isPen(t: String): Boolean { val b = base(t); return b == "pen" || b == "ball" || b == "hl" }
    fun extras(): List<String> = P.s("tb.inst").split(',').filter { it.isNotEmpty() }
    fun all(): List<String> {
        val l = ArrayList<String>(); val ex = extras()
        for (t in BASE) { if (t != "move" && !P.b("tb.show.$t")) continue; l.add(t); if (isPen(t)) l.addAll(ex.filter { base(it) == t }) }
        val o = P.s("tb.order").split(',').filter { it.isNotEmpty() }
        return if (o.isEmpty()) l else l.sortedBy { val i = o.indexOf(it); if (i < 0) 999 else i }
    }
    fun pinned(): List<String> = P.s("tb.favs").split(',').filter { it.isNotEmpty() }
    fun isBall(t: String): Boolean = base(t) != "hl" && P.s("$t.style") == "bp"
    fun icon(t: String): String = when (base(t)) { "pen", "ball" -> if (isBall(t)) "ball" else "pen"; "move" -> "mv"; "shot" -> "camera"; "ruler" -> "rulerh"; else -> base(t) }
    fun name(t: String): String = when (base(t)) { "pen", "ball" -> if (isBall(t)) "Ballpoint" else "Pen"; "hl" -> "Highlighter"; "eraser" -> "Eraser"; "lasso" -> "Lasso select"; "shape" -> "Shapes"; "tape" -> "Study tape"
        "text" -> "Text box"; "image" -> "Insert image"; "move" -> "Move / resize"; "shot" -> "Screenshot"; "ruler" -> "Ruler"; else -> t }
    fun addPen(kind: String): String {
        val b = if (kind == "hl") "hl" else if (kind == "bp") "ball" else "pen"
        var n = 2; val have = extras(); while ("$b$n" in have) n++
        val id = "$b$n"; P.set("tb.inst", (have + id).joinToString(","), false)
        val o = P.s("tb.order"); if (o.isNotEmpty()) { val l = ArrayList(o.split(',')); val at = l.indexOfLast { base(it) == b }; l.add(if (at < 0) l.size else at + 1, id); P.set("tb.order", l.joinToString(","), false) }
        return id
    }
    fun removePen(id: String) {
        P.set("tb.inst", extras().filter { it != id }.joinToString(","), false)
        if (cur == id) { cur = base(id); P.set("tool", cur, false) }; if (prev == id) prev = base(id)
        for (l in ArrayList(listeners)) l()
    }
    fun set(t: String) {
        if (t != cur) { if (cur != "eraser" || t != "eraser") prev = cur; cur = t; P.set("tool", t, false) }
        for (l in ArrayList(listeners)) l()
    }
    fun toggleEraser() { if (cur == "eraser") set(if (prev == "eraser") "pen" else prev) else set("eraser") }
}

object PenState { var lastT = 0L; var down = false }

class Part(val page: Page, val removed: List<Item>, val added: List<Item>)
class Edit(val parts: List<Part>)

class Selection(var page: Int, var items: List<Item>) {
    val bounds = RectF(); val m = Matrix()
    var bmp: Bitmap? = null; val bmpRect = RectF()
    fun compute() { if (items.isEmpty()) return; bounds.set(items[0].bounds); for (it in items) bounds.union(it.bounds) }
}

private class PC { var bmp: Bitmap? = null; var scale = 0f; var pending = false }

class NoteView(c: Context, val pane: Pane) : View(c) {
    companion object {
        const val M_NONE = 0; const val M_DRAW = 1; const val M_ERASE = 2; const val M_LASSO = 3; const val M_SELMOVE = 4; const val M_SELSCALE = 5
        const val M_PAN = 6; const val M_PINCH = 7; const val M_RULER = 8; const val M_IGNORE = 9; const val M_TAPTOOL = 10; const val M_SHOT = 11; const val M_SHAPE = 12
        const val ADDZONE = 64f
    }

    var note: Note? = null
    private var tops = FloatArray(0); private var lefts = FloatArray(0)
    private var docW = 595f; private var docH = 842f
    var scale = 1f; var tx = 0f; var ty = 0f
    private var fit = 0f
    val zoom: Float get() = if (fit > 0) scale / fit else 1f

    private val caches = HashMap<String, PC>()
    private val remGen = HashMap<String, Int>()
    private val ip = InkPainter()
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpR = RectF()
    private val scroller = OverScroller(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()

    // sharp patch for zoom levels above the page cache resolution
    private var patch: Bitmap? = null; private var pScale = 1f; private var pTx = 0f; private var pTy = 0f
    private var patchValid = false; private var patchGen = 0; private var patchPending = false

    val undo = ArrayList<Edit>(); val redo = ArrayList<Edit>()
    var sel: Selection? = null
    private var selSet: HashSet<Item>? = null

    // input state
    private var mode = M_NONE
    private var activeId = -1; private var activeStylus = false; private var tempTool = false
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f; private var downT = 0L
    private var moved = false; private var maxPtr = 1; private var swallow = false
    private var vt: VelocityTracker? = null
    private var gesturing = false
    private var pinD = 0f; private var pinX = 0f; private var pinY = 0f; private var pinMoved = false
    private var lastTapT = 0L; private var lastTapX = 0f; private var lastTapY = 0f

    // live stroke
    private var live = false
    private var buf = FloatArray(3 * 512); private var bn = 0
    private var lTool = "pen"; private var lKind = K_PEN; private var lColor = 0; private var lWidth = 1f; private var lPage = 0; private var lTime = 0L
    private var lHl = false; private var lSmooth = 0f; private var lSens = 0f; private var lVar = false; private var lShape = false; private var lShapeMode = "off"
    private var smx = 0f; private var smy = 0f; private var smf = 1f; private var rawX = 0f; private var rawY = 0f
    private var holdX = 0f; private var holdY = 0f
    private var gain = 1f; private var curve = 0
    private var snapOn = false; private var snapSide = 1f

    private var tipT = 0L; private var tipX = 0f; private var tipY = 0f; private var tipEdit: Edit? = null; private var tipSwallow = 0L
    // eraser
    private val gParts = LinkedHashMap<Page, Pair<ArrayList<Item>, ArrayList<Item>>>()
    private var erX = -1f; private var erY = -1f; private var erShow = false
    /** "Highlight strokes before erasing": strokes the eraser touched, removed when the pen lifts */
    private val erPend = LinkedHashMap<Stroke, Int>()

    // lasso
    private val lasso = ArrayList<Float>(); private var lassoPage = -1
    private val lassoPath = Path()
    private var selHandle = -1; private val selStart = RectF(); private val selM0 = Matrix()

    // ruler (screen space)
    var rX = 0f; var rY = 0f; var rAng = 0f
    private var rA0 = 0f; private var rAng0 = 0f
    private val rH: Float get() = dpf(70)
    private val rLen: Float get() = max(width, height) * 1.6f

    private val dirty = HashMap<Int, RectF>()
    private val shot = RectF(); private var shotPage = -1
    /** search highlights: page id -> rectangles in page points */
    val marks = HashMap<String, List<RectF>>()
    private var flushPosted = false
    private var hudT = 0L; private var hudMs = 0f

    init { isFocusable = true; setWillNotDraw(false) }

    /* ================= document ================= */
    private var pendingVp = 0   // 1 = restore the saved position if the setting allows, 2 = always (jump to a page)
    fun show(n: Note, force: Boolean = false) {
        note = n; clearCaches(); undo.clear(); redo.clear(); sel = null; selSet = null; live = false; mode = M_NONE
        tops = FloatArray(0); lefts = FloatArray(0); fit = 0f; scale = 1f; tx = 0f; ty = 0f; pendingVp = if (force) 2 else 1
        relayout(); loadPages(); invalidate()
        n.pages.getOrNull(n.vpPage)?.let { pg -> Thumbs.load(n.id, pg.id) { if (note === n) invalidate() } }
    }
    private fun loadPages() {
        val n = note ?: return; val start = n.vpPage.coerceIn(0, max(0, n.pages.size - 1))
        val order = ArrayList<Page>(); var a = start; var b = start + 1
        while (a >= 0 || b < n.pages.size) { if (a >= 0) order.add(n.pages[a]); if (b < n.pages.size) order.add(n.pages[b]); a--; b++ }
        Store.work { for (pg in order) { if (note !== n) break; if (!pg.loaded) { Store.loadPage(n, pg); postInvalidate() } } }
    }
    fun clearCaches() { for (pc in caches.values) if (!pc.pending) pc.bmp?.recycle(); caches.clear(); patchValid = false; patchGen++ }
    fun refreshAll() { for (k in caches.keys) remGen[k] = (remGen[k] ?: 0) + 1; for (pc in caches.values) { pc.scale = -1f }; patchValid = false; patchGen++; invalidate(); settle() }

    fun relayout() {
        val n = note ?: return; val cnt = n.pages.size
        if (width == 0) return
        // keep the document point at the top of the view stable
        val anchorDoc = if (fit > 0f) (dpf(8) - ty) / scale else 0f
        val anchorPage = if (fit > 0f && tops.isNotEmpty()) pageAtDocY(anchorDoc) else -1
        val anchorOff = if (anchorPage in tops.indices) anchorDoc - tops[anchorPage] else 0f
        tops = FloatArray(cnt); lefts = FloatArray(cnt); docW = 1f
        for (pg in n.pages) docW = max(docW, pg.w)
        var y = 0f; val gap = P.f("doc.gap")
        for (i in 0 until cnt) { val pg = n.pages[i]; lefts[i] = (docW - pg.w) / 2f; tops[i] = y; y += pg.h + gap }
        docH = y - gap + (if (P.b("doc.addPage")) ADDZONE + gap else 0f)
        val old = fit; fit = max(0.05f, (width - dpf(16)) / docW)
        scale = if (old > 0f) scale / old * fit else fit
        if (anchorPage in 0 until cnt) ty = -(tops[anchorPage] + anchorOff) * scale + dpf(8)
        if (pendingVp != 0) {
            if ((pendingVp == 2 || P.b("doc.resume")) && n.vpPage in 0 until cnt) {
                if (pendingVp == 1) scale = fit * n.vpZoom.coerceIn(0.5f, P.f("g.maxZoom"))
                ty = -(tops[n.vpPage] + (if (pendingVp == 1) n.vpOff else 0f)) * scale + dpf(8)
            } else ty = dpf(8)
            pendingVp = 0; post { pane.ed.onViewChanged(pane) }
        }
        clamp()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (rX == 0f) { rX = w / 2f; rY = h / 2f }; relayout(); patchValid = false; settle() }

    private fun clamp() {
        val maxS = fit * P.f("g.maxZoom"); val minS = fit * 0.5f
        scale = scale.coerceIn(minS, max(minS, maxS))
        val cw = docW * scale; val ch = docH * scale; val pad = dpf(8)
        tx = if (cw <= width - 2 * pad || (P.b("g.hscroll") && cw <= width + 1)) (width - cw) / 2f else tx.coerceIn(width - cw - pad, pad)
        ty = if (ch <= height - 2 * pad) pad else ty.coerceIn(height - ch - pad, pad)
    }

    private fun pLeft(i: Int) = lefts[i] * scale + tx
    private fun pTop(i: Int) = tops[i] * scale + ty
    fun pageAtDocY(dy: Float): Int {
        if (tops.isEmpty()) return -1
        var lo = 0; var hi = tops.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (tops[mid] <= dy) lo = mid else hi = mid - 1 }
        return lo
    }
    /** page under a screen point; strict = must be inside the page rectangle */
    fun pageAt(sx: Float, sy: Float, strict: Boolean): Int {
        val n = note ?: return -1
        val i = pageAtDocY((sy - ty) / scale); if (i < 0) return -1
        if (strict) { val pg = n.pages[i]; val x = (sx - tx) / scale - lefts[i]; val y = (sy - ty) / scale - tops[i]; if (x < 0 || y < 0 || x > pg.w || y > pg.h) return -1 }
        return i
    }
    fun px(i: Int, sx: Float) = (sx - tx) / scale - lefts[i]
    fun sx(i: Int, x: Float) = (lefts[i] + x) * scale + tx
    fun sy(i: Int, y: Float) = (tops[i] + y) * scale + ty
    val busy: Boolean get() = mode != M_NONE
    fun py(i: Int, sy: Float) = (sy - ty) / scale - tops[i]
    fun curPage(): Int = max(0, pageAtDocY((height * 0.4f - ty) / scale))
    fun scrollToPage(i: Int, off: Float = 0f) { if (i !in tops.indices) return; scroller.forceFinished(true); ty = -(tops[i] + off) * scale + dpf(8); clamp(); invalidate(); settle(); pane.ed.onViewChanged(pane) }
    fun saveViewport() {
        val n = note ?: return; if (tops.isEmpty()) return
        val d = (dpf(8) - ty) / scale; val i = max(0, pageAtDocY(d))
        if (n.vpPage != i || abs(n.vpZoom - zoom) > 0.01f || abs(n.vpOff - (d - tops[i])) > 2f) { n.vpPage = i; n.vpOff = d - tops[i]; n.vpZoom = zoom; n.metaDirty = true }
    }
    fun zoomTo(z: Float, cx: Float, cy: Float) {
        val from = scale; val to = (fit * z)
        val a = ValueAnimator.ofFloat(0f, 1f); a.duration = if (P.b("ui.anim")) 170 else 1
        gesturing = true
        a.addUpdateListener { val s = from + (to - from) * (it.animatedValue as Float); val k = s / scale; tx = cx - (cx - tx) * k; ty = cy - (cy - ty) * k; scale = s; clamp(); invalidate()
            if (it.animatedFraction >= 1f) { gesturing = false; settle(); pane.ed.onViewChanged(pane) } }
        a.start()
    }

    /* ================= caches ================= */
    private fun maxPx(): Float = when (P.s("perf.sharp")) { "battery" -> 3e6f; "max" -> 9e6f; else -> 5.5e6f }
    private fun cacheScale(pg: Page): Float = min(max(scale, fit), sqrt(maxPx() / (pg.w * pg.h)))
    private fun skipFor(i: Int): Set<Item>? { val s = sel; return if (s != null && s.page == i) selSet else null }

    private fun ensure(i: Int) {
        val n = note ?: return; val pg = n.pages[i]; if (!pg.loaded) return
        val cs = cacheScale(pg)
        var pc = caches[pg.id]; if (pc == null) { pc = PC(); caches[pg.id] = pc }
        if (pc.pending) return
        if (pc.bmp == null || (!gesturing && abs(pc.scale - cs) > cs * 0.04f)) bake(i, pg, pc, cs)
    }

    private fun bake(i: Int, pg: Page, pc: PC, cs: Float) {
        val n = note ?: return
        pc.pending = true
        val snap = ArrayList(pg.items); val skip = skipFor(i)?.let { HashSet(it) }; val gen = remGen[pg.id] ?: 0
        val bw = max(1, (pg.w * cs).toInt()); val bh = max(1, (pg.h * cs).toInt())
        Store.bake {
            val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null }
            if (bmp != null) Render.page(bmp, n, pg, cs, 0f, 0f, snap, skip, Render.bgIp)
            Store.main {
                pc.pending = false
                if (bmp == null || note !== n || caches[pg.id] !== pc) { bmp?.recycle(); return@main }
                val stale = (remGen[pg.id] ?: 0) != gen
                if (!stale && pg.items.size != snap.size) {
                    val known = HashSet(snap); val sk = skipFor(n.pages.indexOf(pg)); val cv = Canvas(bmp); cv.scale(cs, cs)
                    Render.items(cv, n, pg, cs, null, pg.items.filter { it !in known }, sk, ip)
                }
                pc.bmp?.recycle(); pc.bmp = bmp; pc.scale = cs
                if (stale) pc.scale = -1f   // forces a fresh bake on the next draw
                invalidate()
            }
        }
    }

    /** something other than "a new item on top" changed inside rect (page coords) */
    fun markDirty(i: Int, r: RectF) {
        val n = note ?: return; if (i !in n.pages.indices) return
        val id = n.pages[i].id; remGen[id] = (remGen[id] ?: 0) + 1
        val d = dirty[i]; if (d == null) dirty[i] = RectF(r) else d.union(r)
        patchValid = false; patchGen++
        if (!flushPosted) { flushPosted = true; postOnAnimation { flushPosted = false; flush() } }
    }
    private fun flush() {
        val n = note ?: return
        for ((i, r) in dirty) {
            if (i !in n.pages.indices) continue
            val pg = n.pages[i]; val pc = caches[pg.id] ?: continue; val target = pc.bmp ?: continue
            val cs = pc.scale; if (cs <= 0f) continue
            val l = max(0, floor(r.left * cs).toInt() - 3); val t = max(0, floor(r.top * cs).toInt() - 3)
            val rr = min(target.width, ceil(r.right * cs).toInt() + 3); val bb = min(target.height, ceil(r.bottom * cs).toInt() + 3)
            if (rr <= l || bb <= t) continue
            val snap = ArrayList(pg.items); val skip = skipFor(i)?.let { HashSet(it) }; val rev = pg.rev
            Store.bake {
                val tmp = try { Bitmap.createBitmap(rr - l, bb - t, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return@bake
                Render.page(tmp, n, pg, cs, l.toFloat(), t.toFloat(), snap, skip, Render.bgIp)
                Store.main {
                    if (note === n && pc.bmp === target && !target.isRecycled) {
                        val cv = Canvas(target); cv.drawBitmap(tmp, l.toFloat(), t.toFloat(), null)
                        if (pg.rev != rev) {
                            val known = HashSet(snap); val extra = pg.items.filter { it !in known }
                            if (extra.isNotEmpty()) { cv.clipRect(l, t, rr, bb); cv.scale(cs, cs); Render.items(cv, n, pg, cs, null, extra, skipFor(n.pages.indexOf(pg)), ip) }
                        }
                        invalidate()
                    }
                    tmp.recycle()
                }
            }
        }
        dirty.clear(); settle()
    }

    private fun addToCaches(i: Int, items: List<Item>) {
        val n = note ?: return; val pg = n.pages[i]
        val pc = caches[pg.id]; val b = pc?.bmp
        if (pc != null && b != null && pc.scale > 0f && !b.isRecycled) { val cv = Canvas(b); cv.scale(pc.scale, pc.scale); Render.items(cv, n, pg, pc.scale, null, items, null, ip) }
        val pt = patch
        if (patchValid && pt != null && !patchPending) {
            val cv = Canvas(pt); cv.translate(pTx + lefts[i] * pScale, pTy + tops[i] * pScale); cv.scale(pScale, pScale); cv.clipRect(0f, 0f, pg.w, pg.h)
            Render.items(cv, n, pg, pScale, null, items, null, ip)
        } else { if (patchPending) patchGen++; settle() }
    }

    private val settleR = Runnable { makePatch() }
    fun settle() { removeCallbacks(settleR); postDelayed(settleR, 130) }
    private fun makePatch() {
        val n = note ?: return
        if (gesturing || live || mode == M_PINCH || !scroller.isFinished || width == 0) return
        invalidate()   // lets ensure() re-bake page caches at the new scale
        val first = firstVisible(); val last = lastVisible(); if (first < 0) return
        var need = false
        for (i in first..last) if (cacheScale(n.pages[i]) < scale * 0.97f) need = true
        if (!need) { if (patchValid) { patchValid = false; invalidate() }; return }
        if (patchValid && pScale == scale && pTx == tx && pTy == ty) return
        val s = scale; val x = tx; val y = ty; val w = width; val h = height; val gen = ++patchGen
        class Job(val pg: Page, val snap: List<Item>, val skip: Set<Item>?, val l: Float, val t: Float)
        val jobs = ArrayList<Job>()
        for (i in first..last) { val pg = n.pages[i]; if (!pg.loaded) continue; jobs.add(Job(pg, ArrayList(pg.items), skipFor(i)?.let { HashSet(it) }, pLeft(i), pTop(i))) }
        patchPending = true
        Store.bake {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null }
            if (bmp != null && gen == patchGen) {
                val cv = Canvas(bmp)
                for (j in jobs) {
                    val vl = max(0, floor(j.l).toInt()); val vt = max(0, floor(j.t).toInt())
                    val vr = min(w, ceil(j.l + j.pg.w * s).toInt()); val vb = min(h, ceil(j.t + j.pg.h * s).toInt())
                    if (vr <= vl || vb <= vt) continue
                    val tmp = try { Bitmap.createBitmap(vr - vl, vb - vt, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: continue
                    Render.page(tmp, n, j.pg, s, vl - j.l, vt - j.t, j.snap, j.skip, Render.bgIp)
                    cv.drawBitmap(tmp, vl.toFloat(), vt.toFloat(), null); tmp.recycle()
                }
            }
            Store.main {
                patchPending = false
                if (bmp != null && note === n && gen == patchGen && scale == s && tx == x && ty == y) { patch?.recycle(); patch = bmp; pScale = s; pTx = x; pTy = y; patchValid = true; invalidate() }
                else { bmp?.recycle(); if (note === n && gen != patchGen) settle() }
            }
        }
    }

    private fun firstVisible(): Int = if (tops.isEmpty()) -1 else max(0, pageAtDocY((0 - ty) / scale))
    private fun lastVisible(): Int = if (tops.isEmpty()) -1 else max(0, pageAtDocY((height - ty) / scale))

    /* ================= drawing ================= */
    override fun onDraw(c: Canvas) {
        val t0 = if (P.b("perf.hud")) System.nanoTime() else 0L
        c.drawColor(Th.canvas)
        val n = note ?: return
        if (tops.size != n.pages.size) relayout()
        if (tops.isEmpty()) return
        val first = firstVisible(); val last = lastVisible()
        for (i in first..last) {
            val pg = n.pages[i]; val l = pLeft(i); val t = pTop(i)
            tmpR.set(l, t, l + pg.w * scale, t + pg.h * scale)
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1)   // soft page shadow (three cheap outlines, no blur pass)
            for (k in 1..3) { p.color = ((0x16 - k * 5) shl 24); val o = dpf(k) - dpf(0.5f); c.drawRect(tmpR.left - o, tmpR.top - o + dpf(1), tmpR.right + o, tmpR.bottom + o + dpf(1), p) }
            val pc = caches[pg.id]; val b = pc?.bmp
            if (b != null && !b.isRecycled) c.drawBitmap(b, null, tmpR, bmpP)
            else {
                p.style = Paint.Style.FILL; p.color = if (pg.src != null) Color.WHITE else pg.paperColor; c.drawRect(tmpR, p)
                val th = Thumbs.cached(n.id + "/" + pg.id); if (th != null) c.drawBitmap(th, null, tmpR, bmpP)
            }
        }
        for (i in max(0, first - 1)..min(n.pages.size - 1, last + 1)) ensure(i)
        if (caches.size > last - first + 5) {
            val keep = HashSet<String>(); for (i in max(0, first - 2)..min(n.pages.size - 1, last + 2)) keep.add(n.pages[i].id)
            val it = caches.entries.iterator(); while (it.hasNext()) { val e = it.next(); if (e.key !in keep && !e.value.pending) { e.value.bmp?.recycle(); it.remove() } }
        }
        val pt = patch
        if (patchValid && pt != null && !pt.isRecycled) { val k = scale / pScale; c.save(); c.translate(tx - pTx * k, ty - pTy * k); c.scale(k, k); c.drawBitmap(pt, 0f, 0f, bmpP); c.restore() }

        if (live && bn > 0 && lPage in n.pages.indices) {
            val pg = n.pages[lPage]
            c.save(); c.translate(pLeft(lPage), pTop(lPage)); c.scale(scale, scale); c.clipRect(0f, 0f, pg.w, pg.h)
            ip.sc = scale; ip.mult = P.b("hl.under") && (pg.src != null || pg.bg != null || lum(pg.paperColor) > 0.5f)
            ip.raw(c, buf, bn, if (lShape && lKind != K_HL && lKind != K_TAPE) K_SHAPE else lKind, lColor, lWidth)
            c.restore()
        }
        drawSelection(c, n)
        if (mode == M_LASSO && lasso.size >= 4) {
            lassoPath.rewind(); lassoPath.moveTo(lasso[0], lasso[1]); var i = 2; while (i < lasso.size) { lassoPath.lineTo(lasso[i], lasso[i + 1]); i += 2 }
            if (P.b("ls.tint")) { p.style = Paint.Style.FILL; p.color = (P.i("ls.color") and 0xFFFFFF) or (0x18 shl 24); c.drawPath(lassoPath, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(P.f("ls.w").coerceIn(1f, 10f) * 0.55f); p.color = P.i("ls.color"); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.pathEffect = if (P.s("ls.style") == "dash") DashPathEffect(floatArrayOf(dpf(6), dpf(5)), 0f) else null
            c.drawPath(lassoPath, p); p.pathEffect = null; p.strokeCap = Paint.Cap.BUTT; p.strokeJoin = Paint.Join.MITER
        }
        if (P.b("doc.addPage")) {
            val i = n.pages.size - 1; val pg = n.pages[i]
            val l = pLeft(i); val t = pTop(i) + (pg.h + P.f("doc.gap")) * scale
            if (t < height) {
                tmpR.set(l, t, l + pg.w * scale, t + ADDZONE * scale)
                p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.2f); p.color = Th.sub; p.pathEffect = DashPathEffect(floatArrayOf(dpf(6), dpf(6)), 0f)
                c.drawRoundRect(tmpR, dpf(10), dpf(10), p); p.pathEffect = null
                val cx = tmpR.centerX(); val cy = tmpR.centerY(); val a = dpf(9); p.strokeWidth = dpf(2); c.drawLine(cx - a, cy, cx + a, cy, p); c.drawLine(cx, cy - a, cx, cy + a, p)
            }
        }
        if (marks.isNotEmpty()) {
            p.style = Paint.Style.FILL; p.color = (P.i("sr.hl") and 0xFFFFFF) or ((P.f("sr.alpha") * 2.55f).toInt().coerceIn(20, 255) shl 24); p.blendMode = BlendMode.MULTIPLY
            for (i in first..last) { val rs = marks[n.pages[i].id] ?: continue; val l = pLeft(i); val t = pTop(i)
                for (r in rs) c.drawRoundRect(l + r.left * scale - 2f, t + r.top * scale - 2f, l + r.right * scale + 2f, t + r.bottom * scale + 2f, 4f, 4f, p) }
            p.blendMode = null
        }
        if (mode == M_SHOT) {
            p.style = Paint.Style.FILL; p.color = (P.i("shot.color") and 0xFFFFFF) or (0x1A shl 24); c.drawRect(min(shot.left, shot.right), min(shot.top, shot.bottom), max(shot.left, shot.right), max(shot.top, shot.bottom), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.6f); p.color = P.i("shot.color"); p.pathEffect = DashPathEffect(floatArrayOf(dpf(6), dpf(4)), 0f)
            c.drawRect(min(shot.left, shot.right), min(shot.top, shot.bottom), max(shot.left, shot.right), max(shot.top, shot.bottom), p); p.pathEffect = null
        }
        if (Tools.ruler) drawRuler(c)
        if (erPend.isNotEmpty()) {
            val pc = (P.i("er.prevColor") and 0xFFFFFF) or (0x99 shl 24)
            for ((s, i) in erPend) { if (i !in n.pages.indices) continue
                c.save(); c.translate(pLeft(i), pTop(i)); c.scale(scale, scale); ip.sc = scale
                ip.raw(c, s.pts, s.pts.size, K_SHAPE, pc, s.width + 3f / scale); c.restore() }
        }
        if (erShow && P.b("er.cursor") && erX >= 0) {
            val r = P.f("er.size") / 2f * scale; val sq = P.s("er.shape") == "square" && P.s("er.mode") != "precise"
            if (P.b("er.fill")) { p.style = Paint.Style.FILL; p.color = (P.i("er.cursorColor") and 0xFFFFFF) or (0x22 shl 24); if (sq) c.drawRect(erX - r, erY - r, erX + r, erY + r, p) else c.drawCircle(erX, erY, r, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = P.f("er.cursorW") * DENS / 2f + 1f; p.color = P.i("er.cursorColor")
            if (P.b("er.dash")) p.pathEffect = DashPathEffect(floatArrayOf(dpf(4), dpf(3)), 0f)
            if (sq) c.drawRect(erX - r, erY - r, erX + r, erY + r, p) else c.drawCircle(erX, erY, r, p)
            p.pathEffect = null
            if (P.b("er.cross")) { val a = min(r * 0.4f, dpf(6)); c.drawLine(erX - a, erY, erX + a, erY, p); c.drawLine(erX, erY - a, erX, erY + a, p) }
        }
        if (pane.ed.multi && pane.ed.focused === pane && P.b("split.ring")) {
            p.style = Paint.Style.STROKE; p.strokeWidth = dpf(2.5f); p.color = Th.accent; c.drawRect(dpf(1), dpf(1), width - dpf(1), height - dpf(1), p)
        }
        if (t0 != 0L) {
            val now = System.nanoTime(); hudMs = hudMs * 0.9f + (now - t0) / 1e6f * 0.1f
            p.style = Paint.Style.FILL; p.color = col(0xAA000000); c.drawRect(0f, height - dpf(22), dpf(230), height.toFloat(), p)
            p.color = Color.WHITE; p.textSize = dpf(12); c.drawText(String.format("draw %.2f ms · zoom %.2f× · pages %d", hudMs, zoom, caches.size), dpf(6), height - dpf(7), p)
        }
    }

    private fun drawSelection(c: Canvas, n: Note) {
        val s = sel ?: return; if (s.page !in n.pages.indices) return
        c.save(); c.translate(pLeft(s.page), pTop(s.page)); c.scale(scale, scale); c.concat(s.m)
        val b = s.bmp
        if (b != null && !b.isRecycled) c.drawBitmap(b, null, s.bmpRect, bmpP)
        else { ip.sc = scale; ip.mult = false; for (it in s.items) ip.item(c, it, n.dir) }
        c.restore()
        val r = selScreenRect()
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.4f); p.color = P.i("ls.color"); p.pathEffect = DashPathEffect(floatArrayOf(dpf(5), dpf(4)), 0f)
        c.drawRoundRect(r, dpf(4), dpf(4), p); p.pathEffect = null
        for (k in 0..3) {
            val hx = if (k == 0 || k == 3) r.left else r.right; val hy = if (k < 2) r.top else r.bottom
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(hx, hy, dpf(6), p)
            p.style = Paint.Style.STROKE; p.color = P.i("ls.color"); p.strokeWidth = dpf(2); c.drawCircle(hx, hy, dpf(6), p)
        }
    }
    private fun selScreenRect(): RectF {
        val s = sel!!; val r = RectF(s.bounds); s.m.mapRect(r)
        val l = pLeft(s.page); val t = pTop(s.page)
        return RectF(l + r.left * scale - dpf(4), t + r.top * scale - dpf(4), l + r.right * scale + dpf(4), t + r.bottom * scale + dpf(4))
    }

    private fun drawRuler(c: Canvas) {
        c.save(); c.translate(rX, rY); c.rotate(rAng)
        val h = rH; val len = rLen; val alpha = (P.f("ru.alpha") * 2.55f).toInt().coerceIn(40, 255)
        p.style = Paint.Style.FILL; p.color = Color.argb(alpha, 255, 214, 10)
        c.drawRect(-len / 2, -h / 2, len / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.color = col(0xAA222222); p.strokeWidth = dpf(1); c.drawLine(-len / 2, -h / 2, len / 2, -h / 2, p); c.drawLine(-len / 2, h / 2, len / 2, h / 2, p)
        val inch = P.s("ru.units") == "in"
        val unit = (if (inch) 72f / 8f else 72f / 25.4f) * scale   // px per small tick
        val big = if (inch) 8 else 10; val midT = if (inch) 4 else 5
        if (unit > 2.5f) {
            val cnt = (len / 2 / unit).toInt(); p.strokeWidth = dpf(0.8f); p.textSize = dpf(10)
            for (k in -cnt..cnt) {
                val x = k * unit; val a = abs(k)
                val tl = if (a % big == 0) dpf(14) else if (a % midT == 0) dpf(10) else dpf(6)
                c.drawLine(x, -h / 2, x, -h / 2 + tl, p); c.drawLine(x, h / 2, x, h / 2 - tl, p)
                if (a % big == 0 && k != 0) { p.style = Paint.Style.FILL; c.drawText((a / big).toString(), x + dpf(2), -h / 2 + dpf(24), p); p.style = Paint.Style.STROKE }
            }
        }
        p.style = Paint.Style.FILL; p.color = col(0xFF222222); p.textSize = dpf(13); p.textAlign = Paint.Align.CENTER
        var a = ((-rAng % 180) + 180) % 180; if (a > 90) a = 180 - a
        c.drawText(String.format("%.0f°", a), 0f, dpf(5), p); p.textAlign = Paint.Align.LEFT
        c.restore()
    }
    private fun rulerLocal(x: Float, y: Float): FloatArray {
        val r = Math.toRadians(-rAng.toDouble()); val dx = x - rX; val dy = y - rY
        return floatArrayOf((dx * cos(r) - dy * sin(r)).toFloat(), (dx * sin(r) + dy * cos(r)).toFloat())
    }
    private fun rulerHit(x: Float, y: Float): Boolean { val l = rulerLocal(x, y); return abs(l[1]) <= rH / 2 }

    /* ================= input ================= */
    private fun isStylus(e: MotionEvent, i: Int): Boolean { val t = e.getToolType(i); return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (isStylus(e, 0)) {
            PenState.lastT = SystemClock.uptimeMillis()
            val er = Tools.cur == "eraser" || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            if (er && e.actionMasked != MotionEvent.ACTION_HOVER_EXIT) { erX = e.x; erY = e.y; erShow = true; invalidate() } else if (erShow) { erShow = false; invalidate() }
        }
        return true
    }

    private val longR = Runnable { if (mode == M_PAN && !moved && maxPtr == 1) { mode = M_IGNORE; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); pane.ed.onLongPress(pane, downX, downY) } }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        note ?: return false
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            swallow = false
            pane.ed.commitText()
            if (pane.ed.focused !== pane) { val multi = pane.ed.multi; pane.ed.focus(pane); if (multi && P.b("split.focusTap")) swallow = true }
        }
        if (swallow) return true
        when (a) {
            MotionEvent.ACTION_DOWN -> { downT = e.eventTime; maxPtr = 1; moved = false; pinMoved = false; scroller.forceFinished(true); parent?.requestDisallowInterceptTouchEvent(true); startPointer(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPtr = max(maxPtr, e.pointerCount); removeCallbacks(longR)
                val idx = e.actionIndex
                if (isStylus(e, idx)) { if (!(activeStylus && isToolMode())) { cancelGesture(); startPointer(e, idx) } }
                else if (mode == M_PAN || (isToolMode() && !activeStylus)) { cancelGesture(); beginPinch(e) }
                else if (mode == M_RULER) { rA0 = angle2(e); rAng0 = rAng }
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                if (e.getPointerId(idx) == activeId && isToolMode()) { finish(e, idx); mode = M_IGNORE }
                else if (mode == M_PINCH && e.pointerCount <= 2) {
                    // continue as a one-finger pan with the remaining pointer
                    val rest = if (idx == 0) 1 else 0
                    gesturing = false; mode = M_PAN; activeId = e.getPointerId(rest); lastX = e.getX(rest); lastY = e.getY(rest); downX = lastX; downY = lastY; moved = pinMoved
                    vt?.recycle(); vt = VelocityTracker.obtain(); settle()
                }
            }
            MotionEvent.ACTION_UP -> { removeCallbacks(longR); finish(e, e.actionIndex); mode = M_NONE; PenState.down = false }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longR); cancelGesture(); mode = M_NONE; PenState.down = false }
        }
        return true
    }
    private fun isToolMode() = mode == M_DRAW || mode == M_ERASE || mode == M_LASSO || mode == M_SELMOVE || mode == M_SELSCALE || mode == M_TAPTOOL || mode == M_SHOT || mode == M_SHAPE

    private fun startPointer(e: MotionEvent, idx: Int) {
        val tt = e.getToolType(idx); val stylus = isStylus(e, idx)
        val x = e.getX(idx); val y = e.getY(idx)
        activeId = e.getPointerId(idx); activeStylus = stylus; tempTool = false
        downX = x; downY = y; lastX = x; lastY = y
        val now = SystemClock.uptimeMillis()
        if (stylus) { PenState.lastT = now; PenState.down = true; if (P.b("perf.unbuffered")) requestUnbufferedDispatch(e) }
        else if (P.b("pen.palm") && (PenState.down || now - PenState.lastT < P.f("pen.palmMs"))) { mode = M_IGNORE; return }
        if (!stylus && Tools.ruler && rulerHit(x, y)) { mode = M_RULER; return }
        val s = sel
        if (s != null) {
            val r = selScreenRect(); val hr = dpf(22)
            for (k in 0..3) { val hx = if (k == 0 || k == 3) r.left else r.right; val hy = if (k < 2) r.top else r.bottom
                if (abs(x - hx) < hr && abs(y - hy) < hr) { mode = M_SELSCALE; selHandle = k; selStart.set(s.bounds); selM0.set(s.m); pane.hideSelBar(); return } }
            if (r.contains(x, y)) { mode = M_SELMOVE; pane.hideSelBar(); return }
        }
        val drawer = stylus || !P.b("pen.only") || tt == MotionEvent.TOOL_TYPE_MOUSE
        if (!drawer) {
            mode = M_PAN; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            postDelayed(longR, 520); return
        }
        var tool = Tools.cur
        if (tt == MotionEvent.TOOL_TYPE_ERASER) { tool = "eraser"; tempTool = true }
        else if (stylus && (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0) {
            when (P.s("pen.barrel")) { "eraser", "hold" -> { tool = "eraser"; tempTool = true }; "lasso" -> { tool = "lasso"; tempTool = true }; "toggle" -> { Tools.toggleEraser(); tool = Tools.cur } }
        }
        if (s != null) deselect()
        when (Tools.base(tool)) {
            "pen", "ball", "hl" -> beginStroke(tool, e, idx)
            "eraser" -> { mode = M_ERASE; gParts.clear(); erShow = true; pane.ed.bar.onInk(); eraseAt(x, y) }
            "lasso" -> { mode = M_LASSO; lasso.clear(); lasso.add(x); lasso.add(y); lassoPage = pageAt(x, y, false) }
            "shape" -> beginShape(x, y)
            "tape" -> beginShape(x, y, true)
            "move" -> {
                // tap an image or a text box: it is picked up at once and follows the pen
                val i = pageAt(x, y, true); val n = note
                val hit = if (n != null && i >= 0 && n.pages[i].loaded) n.pages[i].items.lastOrNull { (it is ImageItem || it is TextItem) && it.bounds.contains(px(i, x), py(i, y)) } else null
                if (hit != null) { select(i, listOf(hit)); mode = M_SELMOVE; pane.hideSelBar() } else mode = M_IGNORE
            }
            "text" -> mode = M_TAPTOOL
            "shot" -> { shotPage = pageAt(x, y, true); if (shotPage < 0) mode = M_IGNORE else { mode = M_SHOT; shot.set(x, y, x, y) } }
            else -> { mode = M_PAN; vt?.recycle(); vt = VelocityTracker.obtain() }
        }
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            M_DRAW -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return
                for (h in 0 until e.historySize) drawPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h))
                drawPoint(e.getX(idx), e.getY(idx), e.getPressure(idx)); invalidate()
            }
            M_ERASE -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return
                for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseAt(e.getX(idx), e.getY(idx)); invalidate()
            }
            M_LASSO -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return
                if (P.s("ls.mode") == "rect") {
                    val x = e.getX(idx); val y = e.getY(idx); lasso.clear()
                    lasso.add(downX); lasso.add(downY); lasso.add(x); lasso.add(downY); lasso.add(x); lasso.add(y); lasso.add(downX); lasso.add(y); lasso.add(downX); lasso.add(downY)
                } else {
                    for (h in 0 until e.historySize) { lasso.add(e.getHistoricalX(idx, h)); lasso.add(e.getHistoricalY(idx, h)) }
                    lasso.add(e.getX(idx)); lasso.add(e.getY(idx))
                }
                invalidate()
            }
            M_SHAPE -> { val idx = e.findPointerIndex(activeId); if (idx >= 0) { shapeTo(e.getX(idx), e.getY(idx)); invalidate() } }
            M_SELMOVE -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return; val s = sel ?: return
                val x = e.getX(idx); val y = e.getY(idx); s.m.postTranslate((x - lastX) / scale, (y - lastY) / scale); lastX = x; lastY = y
                // auto-scroll near the top / bottom edge so a selection can be carried to another page
                if (y < dpf(40)) { ty += dpf(10); clamp() } else if (y > height - dpf(40)) { ty -= dpf(10); clamp() }
                invalidate()
            }
            M_SELSCALE -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return; val s = sel ?: return
                val b = RectF(selStart); selM0.mapRect(b)
                val ax = if (selHandle == 0 || selHandle == 3) b.right else b.left; val ay = if (selHandle < 2) b.bottom else b.top
                val hx = if (selHandle == 0 || selHandle == 3) b.left else b.right; val hy = if (selHandle < 2) b.top else b.bottom
                val cx = px(s.page, e.getX(idx)); val cy = py(s.page, e.getY(idx))
                var sx = if (abs(hx - ax) > 0.01f) (cx - ax) / (hx - ax) else 1f; var sy = if (abs(hy - ay) > 0.01f) (cy - ay) / (hy - ay) else 1f
                if (P.b("ls.keep")) { val k = max(abs(sx), abs(sy)); sx = k; sy = k }
                sx = max(0.05f, sx); sy = max(0.05f, sy)
                s.m.set(selM0); s.m.postScale(sx, sy, ax, ay); invalidate()
            }
            M_PAN -> {
                val idx = e.findPointerIndex(activeId); if (idx < 0) return
                val x = e.getX(idx); val y = e.getY(idx)
                if (!moved && hypot(x - downX, y - downY) > slop) { moved = true; removeCallbacks(longR); lastX = x; lastY = y }
                if (moved) { tx += x - lastX; ty += y - lastY; lastX = x; lastY = y; clamp(); vt?.addMovement(e); invalidate(); pane.ed.onViewChanged(pane) }
            }
            M_PINCH -> if (e.pointerCount >= 2) pinch(e)
            M_RULER -> {
                if (e.pointerCount >= 2) { rAng = rAng0 + (angle2(e) - rA0); moved = true }
                else { val x = e.getX(0); val y = e.getY(0); rX += x - lastX; rY += y - lastY; lastX = x; lastY = y; if (hypot(x - downX, y - downY) > slop) moved = true }
                invalidate()
            }
            M_TAPTOOL -> { val idx = e.findPointerIndex(activeId); if (idx >= 0 && hypot(e.getX(idx) - downX, e.getY(idx) - downY) > slop * 2) moved = true }
            M_SHOT -> { val idx = e.findPointerIndex(activeId); if (idx >= 0) { shot.right = e.getX(idx); shot.bottom = e.getY(idx); invalidate() } }
        }
    }
    private fun angle2(e: MotionEvent): Float = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun finish(e: MotionEvent, idx: Int) {
        val x = e.getX(idx); val y = e.getY(idx)
        when (mode) {
            M_DRAW -> {
                val tapLike = activeStylus && !tempTool && P.b("pen.tipTap") && e.eventTime - downT < 220 && hypot(x - downX, y - downY) < slop
                val tp = if (P.b("tape.pen") && e.eventTime - downT < 300 && hypot(x - downX, y - downY) < slop && lPage >= 0) tapeAt(lPage, px(lPage, downX), py(lPage, downY)) else null
                if (tp != null) { live = false; bn = 0; removeCallbacks(holdR); tipT = 0; toggleTape(lPage, tp) }
                else if (tapLike && downT - tipSwallow < 400) { live = false; bn = 0; removeCallbacks(holdR); tipSwallow = 0; invalidate() }   // second tap of a double tap that already left the eraser
                else if (tapLike && downT - tipT < 400 && hypot(downX - tipX, downY - tipY) < dpf(40)) {
                    // second quick tap: drop both dots and switch to the eraser
                    live = false; bn = 0; removeCallbacks(holdR); tipT = 0
                    val ed0 = tipEdit; if (ed0 != null && undo.lastOrNull() === ed0) { doUndo(); redo.remove(ed0) }
                    tipEdit = null; invalidate(); Tools.toggleEraser()
                } else { addPoint(x, y, smfRaw); val before = undo.lastOrNull(); endStroke(); if (tapLike) { tipT = downT; tipX = downX; tipY = downY; tipEdit = undo.lastOrNull().takeIf { it !== before } } else tipT = 0 }
            }
            M_ERASE -> {
                val tapLike = activeStylus && !tempTool && P.b("pen.tipTap") && e.eventTime - downT < 220 && hypot(x - downX, y - downY) < slop
                val dbl = tapLike && downT - tipT < 400 && hypot(downX - tipX, downY - tipY) < dpf(40)
                val wasEraser = Tools.cur == "eraser" && !tempTool
                endErase()
                if (tapLike && wasEraser) {
                    if (Tools.cur != "eraser") { tipSwallow = downT; tipT = 0 }   // auto-switch already went back to the pen
                    else if (dbl) { tipT = 0; Tools.toggleEraser() }
                    else { tipT = downT; tipX = downX; tipY = downY; tipEdit = null }
                } else tipT = 0
            }
            M_LASSO -> endLasso()
            M_SHAPE -> { shapeTo(x, y); endShape() }
            M_SELMOVE, M_SELSCALE -> commitSel()
            M_PAN -> {
                if (!moved && e.eventTime - downT < 350) { if (maxPtr == 1) tap(x, y) else multiTap() }
                else if (moved) {
                    val v = vt; if (v != null) { v.addMovement(e); v.computeCurrentVelocity(1000); val k = P.f("g.fling") / 100f
                        val cw = docW * scale; val ch = docH * scale; val pad = dpf(8).toInt()
                        val minX = if (cw <= width) tx.toInt() else (width - cw - pad).toInt(); val maxX = if (cw <= width) tx.toInt() else pad
                        val minY = if (ch <= height) ty.toInt() else (height - ch - pad).toInt(); val maxY = if (ch <= height) ty.toInt() else pad
                        scroller.fling(tx.toInt(), ty.toInt(), (v.xVelocity * k).toInt(), (v.yVelocity * k).toInt(), minX, maxX, minY, maxY); postInvalidateOnAnimation() }
                    settle()
                }
                vt?.recycle(); vt = null
            }
            M_PINCH -> { gesturing = false; if (!pinMoved && e.eventTime - downT < 350) multiTap(); settle(); pane.ed.onViewChanged(pane) }
            M_RULER -> { if (P.b("ru.angle")) { rAng = (rAng / 15f).roundToInt() * 15f; invalidate() } }
            M_TAPTOOL -> if (!moved) { val i = pageAt(x, y, true); if (i >= 0) pane.ed.editText(pane, i, textAt(i, px(i, x), py(i, y)), px(i, x), py(i, y)) }
            M_SHOT -> {
                val i = shotPage; val n = note
                if (n != null && i in n.pages.indices && abs(shot.width()) > dpf(12) && abs(shot.height()) > dpf(12)) {
                    val pg = n.pages[i]
                    val r = RectF(px(i, min(shot.left, shot.right)).coerceIn(0f, pg.w), py(i, min(shot.top, shot.bottom)).coerceIn(0f, pg.h), px(i, max(shot.left, shot.right)).coerceIn(0f, pg.w), py(i, max(shot.top, shot.bottom)).coerceIn(0f, pg.h))
                    if (r.width() > 2f && r.height() > 2f) pane.ed.onShot(pane, i, r, max(shot.left, shot.right), max(shot.top, shot.bottom))
                }
                invalidate()
            }
        }
    }
    private fun cancelGesture() {
        when (mode) {
            M_DRAW, M_SHAPE -> { live = false; bn = 0; removeCallbacks(holdR); invalidate() }
            M_ERASE -> endErase()
            M_LASSO -> { lasso.clear(); invalidate() }
            M_SELMOVE, M_SELSCALE -> commitSel()
            M_SHOT -> invalidate()
            M_PINCH -> { gesturing = false; settle() }
        }
        mode = M_NONE
    }

    private fun beginPinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        mode = M_PINCH; gesturing = true
        pinD = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)); pinX = (e.getX(0) + e.getX(1)) / 2f; pinY = (e.getY(0) + e.getY(1)) / 2f
        pin0D = pinD; pin0X = pinX; pin0Y = pinY; pinMoved = false
    }
    private var pin0D = 0f; private var pin0X = 0f; private var pin0Y = 0f
    private fun pinch(e: MotionEvent) {
        val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)); val anch = P.b("g.zAnch")
        val cx = if (anch) (e.getX(0) + e.getX(1)) / 2f else width / 2f; val cy = if (anch) (e.getY(0) + e.getY(1)) / 2f else height / 2f
        if (!anch) { pinX = cx; pinY = cy }
        val locked = P.b("g.zLock")
        if (locked && P.b("g.zLockScroll")) { pinD = d; pinX = cx; pinY = cy; return }
        if (locked) { tx += cx - pinX; ty += cy - pinY }   // two fingers still slide the page, they just cannot change its size
        else if (pinD > 10f && d > 10f) {
            val old = scale; var s = scale * d / pinD
            s = s.coerceIn(fit * 0.5f, fit * P.f("g.maxZoom")); val k = s / old
            tx = cx - (pinX - tx) * k; ty = cy - (pinY - ty) * k; scale = s
        }
        if (abs(d - pin0D) > slop || hypot(cx - pin0X, cy - pin0Y) > slop) pinMoved = true
        pinD = d; pinX = cx; pinY = cy; clamp(); invalidate()
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat(); clamp(); postInvalidateOnAnimation(); pane.ed.onViewChanged(pane); if (scroller.isFinished) settle() }
    }

    private fun multiTap() {
        if (maxPtr == 2 && P.b("g.two")) doUndo() else if (maxPtr >= 3 && P.b("g.two")) doRedo()
    }
    private fun tap(x: Float, y: Float) {
        val n = note ?: return
        if (sel != null) { deselect(); return }
        if (marks.isNotEmpty() && !P.b("sr.keep")) { marks.clear(); invalidate() }
        // "add page" zone
        if (P.b("doc.addPage")) { val i = n.pages.size - 1; val t = pTop(i) + (n.pages[i].h + P.f("doc.gap")) * scale
            if (y > t && y < t + ADDZONE * scale && x > pLeft(i) && x < pLeft(i) + n.pages[i].w * scale) { pane.ed.addPage(pane, i); return } }
        val i = pageAt(x, y, true)
        if (i >= 0 && n.pages[i].loaded) {
            val qx = px(i, x); val qy = py(i, y)
            if (P.b("tape.finger")) tapeAt(i, qx, qy)?.let { toggleTape(i, it); return }
            val trig = P.s("lk.trig")
            if (trig == "tap" || trig == "double") { val l = linkAt(i, qx, qy)
                if (l != null) {
                    if (trig == "tap") { pane.ed.onLink(pane, l, x, y); return }
                    val t2 = SystemClock.uptimeMillis()
                    if (t2 - lastTapT < 350 && hypot(x - lastTapX, y - lastTapY) < dpf(40)) { lastTapT = 0; pane.ed.onLink(pane, l, x, y) } else { lastTapT = t2; lastTapX = x; lastTapY = y }
                    return
                } }
            if (pane.ed.audioWantsTap()) {
                val reach = P.f("au.reach")
                for (it in n.pages[i].items.asReversed()) if (it is Stroke && it.hit(qx, qy, reach)) { if (pane.ed.audioSeek(pane, it)) return }
            }
        }
        val now = SystemClock.uptimeMillis()
        if (P.b("g.dzoom") && !P.b("g.zLock") && now - lastTapT < 320 && hypot(x - lastTapX, y - lastTapY) < dpf(40)) { lastTapT = 0; zoomTo(if (zoom > 1.3f) 1f else 2.2f, x, y) }
        else { lastTapT = now; lastTapX = x; lastTapY = y }
    }
    fun linkAt(i: Int, x: Float, y: Float): LinkItem? { val n = note ?: return null; if (i !in n.pages.indices || !n.pages[i].loaded) return null
        for (it in n.pages[i].items.asReversed()) if (it is LinkItem && it.rect.contains(x, y)) return it; return null }
    fun selRect(): RectF? = if (sel != null) selScreenRect() else null
    fun textAt(i: Int, x: Float, y: Float): TextItem? { val n = note ?: return null; for (it in n.pages[i].items.asReversed()) if (it is TextItem && it.bounds.contains(x, y)) return it; return null }

    /* ================= pen ================= */
    private var smfRaw = 0.5f
    private val holdR = Runnable {
        if (mode == M_DRAW && !lShape && bn >= 30) {
            val xy = Geo.shape(buf.copyOf(bn), P.b("$lTool.shapeSnap"))
            if (xy != null) { val pts = Geo.toPts(xy); if (pts.size > buf.size) buf = FloatArray(pts.size); System.arraycopy(pts, 0, buf, 0, pts.size); bn = pts.size; lShape = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate() }
        }
    }
    private fun beginStroke(tool: String, e: MotionEvent, idx: Int) = beginStrokeAt(tool, e.getX(idx), e.getY(idx), e.getPressure(idx))
    /** pen point while drawing: once the pen is inside another slide, the stroke ends on the old slide and goes on as a new stroke there */
    private fun drawPoint(sx: Float, sy: Float, pr: Float) {
        if (!lShape && !snapOn && P.b("g.xPage")) {
            val n = note; val j = pageAt(sx, sy, true)
            if (n != null && j >= 0 && j != lPage && n.pages[j].loaded) {
                val t = lTool; endStroke(); tipT = 0; tipEdit = null
                beginStrokeAt(t, sx, sy, pr); return
            }
        }
        addPoint(sx, sy, pr)
    }
    private fun beginStrokeAt(tool: String, x: Float, y: Float, pr0: Float) {
        val i = pageAt(x, y, true); val n = note ?: return
        if (i < 0 || !n.pages[i].loaded) { mode = M_IGNORE; return }
        mode = M_DRAW; live = true; bn = 0; lShape = false
        pane.ed.bar.onInk()
        lTool = tool; lPage = i; lTime = System.currentTimeMillis()
        lHl = Tools.base(tool) == "hl"
        lKind = if (lHl) K_HL else if (Tools.isBall(tool)) K_BALL else K_PEN
        lWidth = P.f("$tool.width")
        lColor = if (lHl) (P.i("$tool.color") and 0xFFFFFF) or (((P.f("$tool.alpha") * 2.55f).toInt().coerceIn(20, 255)) shl 24)
            else { val pa = P.f("$tool.alpha"); (P.i("$tool.color") and 0xFFFFFF) or ((if (pa <= 0f || pa >= 99.5f) 255 else (pa * 2.55f).toInt().coerceIn(26, 255)) shl 24) }
        lSmooth = (P.f("$tool.smooth") / 100f * 0.85f).coerceIn(0f, 0.9f)
        lVar = !lHl && activeStylus && P.b("$tool.press"); lSens = P.f("$tool.sens") / 100f
        gain = P.f("pen.pressure") / 100f; curve = when (P.s("pen.curve")) { "soft" -> 1; "firm" -> 2; else -> 0 }
        lShapeMode = P.s("$tool.shape")
        holdX = x; holdY = y
        snapOn = false
        if (Tools.ruler) { val l = rulerLocal(x, y); val d = abs(abs(l[1]) - rH / 2)
            if (abs(l[1]) <= rH / 2 || d < P.f("ru.snap") * DENS / 2f) { snapOn = true; snapSide = if (l[1] < 0) -1f else 1f } }
        addPoint(x, y, pr0)
        invalidate()
    }
    private fun factor(pr: Float): Float {
        if (!lVar) return 1f
        var g = (pr * gain).coerceIn(0f, 1f)
        if (curve == 1) g = g.pow(0.6f) else if (curve == 2) g = g.pow(1.6f)
        return max(0.22f, 1f - lSens + 2f * lSens * g)
    }
    private fun push(x: Float, y: Float, f: Float) {
        if (bn + 3 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bn] = x; buf[bn + 1] = y; buf[bn + 2] = f; bn += 3
    }
    private fun addPoint(sx0: Float, sy0: Float, pr: Float) {
        if (lShape) return
        smfRaw = pr
        var sx = sx0; var sy = sy0
        if (snapOn) {
            val l = rulerLocal(sx, sy); val r = Math.toRadians(rAng.toDouble()); val ly = snapSide * rH / 2
            sx = (rX + l[0] * cos(r) - ly * sin(r)).toFloat(); sy = (rY + l[0] * sin(r) + ly * cos(r)).toFloat()
        }
        val qx = px(lPage, sx); val qy = py(lPage, sy)
        rawX = qx; rawY = qy
        val f = factor(pr)
        if (bn == 0) { smx = qx; smy = qy; smf = f; push(qx, qy, f); return }
        val k = 1f - lSmooth
        val nx = smx + (qx - smx) * k; val ny = smy + (qy - smy) * k; val nf = smf + (f - smf) * 0.45f
        val dx = nx - buf[bn - 3]; val dy = ny - buf[bn - 2]; val minD = 0.35f / scale
        smx = nx; smy = ny; smf = nf
        if (dx * dx + dy * dy < minD * minD) return
        push(nx, ny, nf)
        if (lShapeMode == "hold" && hypot(sx0 - holdX, sy0 - holdY) > dpf(4)) { holdX = sx0; holdY = sy0; removeCallbacks(holdR); postDelayed(holdR, P.f("$lTool.holdMs").toLong()) }
    }
    /* ---- shapes tool: drag out a line, rectangle or ellipse ---- */
    private fun beginShape(x: Float, y: Float, tape: Boolean = false) {
        val i = pageAt(x, y, true); val n = note
        if (n == null || i < 0 || !n.pages[i].loaded) { mode = M_IGNORE; return }
        pane.ed.bar.onInk()
        mode = M_SHAPE; live = true; bn = 0; lShape = true; lPage = i; lTime = System.currentTimeMillis(); lKind = if (tape) K_TAPE else K_SHAPE; lHl = false; lTool = if (tape) "tape" else "shp"
        if (tape) { lWidth = P.f("tape.width"); lColor = P.i("tape.color") or (0xFF shl 24) }
        else { lWidth = P.f("shp.width"); val pa = P.f("shp.alpha")
            lColor = (P.i("shp.color") and 0xFFFFFF) or ((if (pa <= 0f || pa >= 99.5f) 255 else (pa * 2.55f).toInt().coerceIn(26, 255)) shl 24) }
        shapeTo(x, y)
    }
    /** a picture of part of a page (PDF, paper, ink — everything) written straight into the shelf folder; done(file name, area) */
    fun areaImage(i: Int, r0: RectF, done: (String, RectF) -> Unit) {
        val n = note ?: return; val pg = n.pages.getOrNull(i) ?: return
        val pad = P.f("sh.pad"); val r = RectF(r0.left - pad, r0.top - pad, r0.right + pad, r0.bottom + pad)
        if (!r.intersect(0f, 0f, pg.w, pg.h)) return
        val cs = P.s("sh.q").toFloatOrNull() ?: 2.5f; val jpg = P.s("sh.fmt") == "jpg"
        val name = "snip_" + newId() + if (jpg) ".jpg" else ".png"; val snap = ArrayList(pg.items)
        Store.work {
            val bw = (r.width() * cs).toInt().coerceIn(8, 5000); val bh = (r.height() * cs).toInt().coerceIn(8, 5000)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Render.page(bmp, n, pg, cs, r.left * cs, r.top * cs, snap, null, InkPainter())
            val dir = java.io.File(Store.root, "shelf"); dir.mkdirs()
            java.io.File(dir, name).outputStream().use { bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpg) 90 else 100, it) }
            bmp.recycle(); Store.main { done(name, r) }
        }
    }
    /** the tape under a page point (topmost first) */
    fun tapeAt(i: Int, x: Float, y: Float): Stroke? { val n = note ?: return null; if (i !in n.pages.indices || !n.pages[i].loaded) return null
        for (it in n.pages[i].items.asReversed()) if (it is Stroke && it.tool == K_TAPE && it.hit(x, y, 2f)) return it; return null }
    /** peels a tape back / sticks it down again */
    fun toggleTape(i: Int, s: Stroke) { Tape.toggle(s); markDirty(i, s.bounds); invalidate(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    /** "Reveal all" / "Cover all" on the page in view */
    fun allTapes(show: Boolean) { val n = note ?: return; val i = curPage(); val pg = n.pages.getOrNull(i) ?: return
        val l = pg.items.filterIsInstance<Stroke>().filter { it.tool == K_TAPE }; if (l.isEmpty()) { toast("No tape on this page"); return }
        Tape.setAll(l, show); val r = RectF(l[0].bounds); for (s in l) r.union(s.bounds); markDirty(i, r); invalidate() }
    private fun shapeTo(sx: Float, sy: Float) {
        val n = note ?: return; val pg = n.pages.getOrNull(lPage) ?: return
        val ax = px(lPage, downX).coerceIn(0f, pg.w); val ay = py(lPage, downY).coerceIn(0f, pg.h); val bx = px(lPage, sx).coerceIn(0f, pg.w); val by = py(lPage, sy).coerceIn(0f, pg.h)
        bn = 0
        if (lKind == K_TAPE) {   // tape: a straight band; "Keep tape level" snaps it flat or upright within 8°
            var ex = bx; var ey = by
            if (P.b("tape.level")) { if (abs(ey - ay) < abs(ex - ax) * 0.14f) ey = ay else if (abs(ex - ax) < abs(ey - ay) * 0.14f) ex = ax }
            push(ax, ay, 1f); push(ex, ey, 1f); return
        }
        when (P.s("shp.kind")) {
            "rect" -> { push(ax, ay, 1f); push(bx, ay, 1f); push(bx, by, 1f); push(ax, by, 1f); push(ax, ay, 1f) }
            "ell" -> { val cx = (ax + bx) / 2f; val cy = (ay + by) / 2f; val rx = abs(bx - ax) / 2f; val ry = abs(by - ay) / 2f
                for (k in 0..48) { val t = k / 48.0 * 2.0 * PI; push(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat(), 1f) } }
            else -> { push(ax, ay, 1f); push(bx, by, 1f) }
        }
    }
    private fun endShape() {
        val pts = buf.copyOf(bn); live = false; bn = 0; lShape = false
        var l = pts[0]; var t = pts[1]; var r = l; var b = t; var q = 0
        while (q < pts.size) { l = min(l, pts[q]); r = max(r, pts[q]); t = min(t, pts[q + 1]); b = max(b, pts[q + 1]); q += 3 }
        if (hypot(r - l, b - t) < 3f) {   // a tap, not a shape (with the tape tool: peel the tape that was tapped)
            if (lKind == K_TAPE) tapeAt(lPage, pts[0], pts[1])?.let { toggleTape(lPage, it) }
            invalidate(); return }
        commitAdd(lPage, listOf(Stroke(lKind, lColor, lWidth, pts, lTime)))
    }

    private fun endStroke() {
        removeCallbacks(holdR)
        val n = note; if (n == null || bn == 0) { live = false; return }
        if (!lShape && lSmooth > 0f && bn >= 3) { val dx = rawX - buf[bn - 3]; val dy = rawY - buf[bn - 2]; if (dx * dx + dy * dy > 0.01f) push(rawX, rawY, smf) }
        var pts = buf.copyOf(bn); var shape = lShape
        if (!shape && lShapeMode == "always" && bn >= 12) { val xy = Geo.shape(pts, P.b("$lTool.shapeSnap")); if (xy != null) { pts = Geo.toPts(xy); shape = true } }
        if (!shape && lHl && P.b("hl.straight") && bn >= 6) {
            var ex = pts[bn - 3]; var ey = pts[bn - 2]; val ax = pts[0]; val ay = pts[1]
            if (abs(ey - ay) < abs(ex - ax) * 0.18f) ey = ay else if (abs(ex - ax) < abs(ey - ay) * 0.18f) ex = ax
            pts = floatArrayOf(ax, ay, 1f, ex, ey, 1f)
        }
        val kind: Int
        if (shape) kind = if (lHl) K_HL else K_SHAPE
        else {
            kind = lKind
            if (P.b("$lTool.refine") && !lHl) pts = Geo.refine(pts, lSmooth + 0.25f)
            if (!lHl) pts = Geo.straighten(pts, P.f("$lTool.straight"), lWidth)
            if (lVar && P.b("$lTool.taper") && pts.size >= 18) {
                val cnt = pts.size / 3; val m = min(5, cnt / 3)
                for (q in 0 until m) { val f = 0.45f + 0.55f * q / m; pts[q * 3 + 2] *= f; pts[(cnt - 1 - q) * 3 + 2] *= f }
            }
        }
        live = false; bn = 0
        if (!shape && !lHl && P.b("g.strike") && lPage in n.pages.indices && scribble(pts)) {
            val pg = n.pages[lPage]; val hitSet = LinkedHashSet<Item>()
            val sb = RectF(pts[0], pts[1], pts[0], pts[1]); var q = 0; while (q < pts.size) { sb.union(pts[q], pts[q + 1]); q += 3 }
            for (it in pg.items) { if (it !is Stroke || !RectF.intersects(sb, it.bounds)) continue
                var k = 0; while (k < pts.size) { if (it.hit(pts[k], pts[k + 1], 1.5f)) { hitSet.add(it); break }; k += 3 } }
            if (hitSet.isNotEmpty()) {
                val removed = hitSet.toList(); pg.items.removeAll { it in hitSet }; pg.dirty = true; pg.rev++
                pushUndo(Edit(listOf(Part(pg, removed, emptyList()))))
                val r = RectF(removed[0].bounds); for (it in removed) r.union(it.bounds); markDirty(lPage, r)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate(); pane.ed.onDocChanged(pane); return
            }
        }
        commitAdd(lPage, listOf(Stroke(kind, lColor, lWidth, pts, lTime)))
    }
    /** a quick horizontal zig-zag: at least three direction changes, wider than tall */
    private fun scribble(pts: FloatArray): Boolean {
        val cnt = pts.size / 3; if (cnt < 12) return false
        var minX = pts[0]; var maxX = pts[0]; var minY = pts[1]; var maxY = pts[1]
        var turns = 0; var dir = 0; var ext = pts[0]; var k = 3
        while (k < pts.size) {
            val x = pts[k]; val y = pts[k + 1]; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            if (dir == 0) { if (x - ext > 6f) { dir = 1; ext = x } else if (ext - x > 6f) { dir = -1; ext = x } }
            else if (dir == 1) { if (x > ext) ext = x else if (ext - x > 6f) { dir = -1; ext = x; turns++ } }
            else { if (x < ext) ext = x else if (x - ext > 6f) { dir = 1; ext = x; turns++ } }
            k += 3
        }
        val w = maxX - minX; val h = maxY - minY
        return turns >= 3 && w > 18f && h < w * 0.9f
    }

    /** adds finished items on top of a page (ink, pasted items, text…) */
    fun commitAdd(i: Int, items: List<Item>, select: Boolean = false) {
        val n = note ?: return; if (i !in n.pages.indices) return; val pg = n.pages[i]
        pg.items.addAll(items); pg.dirty = true; pg.rev++
        pushUndo(Edit(listOf(Part(pg, emptyList(), items))))
        if (select) select(i, items) else addToCaches(i, items)
        invalidate(); pane.ed.onDocChanged(pane)
    }
    fun replaceItem(i: Int, old: Item, nw: Item?) {
        val n = note ?: return; val pg = n.pages[i]; val k = pg.items.indexOf(old); if (k < 0) return
        if (nw != null) pg.items[k] = nw else pg.items.removeAt(k)
        pg.dirty = true; pg.rev++
        pushUndo(Edit(listOf(Part(pg, listOf(old), if (nw != null) listOf(nw) else emptyList()))))
        val r = RectF(old.bounds); if (nw != null) r.union(nw.bounds); markDirty(i, r); pane.ed.onDocChanged(pane)
    }
    private fun pushUndo(e: Edit) { undo.add(e); if (undo.size > 300) undo.removeAt(0); redo.clear(); pane.ed.onUndoState() }

    fun doUndo() { if (undo.isEmpty()) return; val e = undo.removeAt(undo.size - 1); apply(e, true); redo.add(e); pane.ed.onUndoState() }
    fun doRedo() { if (redo.isEmpty()) return; val e = redo.removeAt(redo.size - 1); apply(e, false); undo.add(e); pane.ed.onUndoState() }
    private fun apply(e: Edit, un: Boolean) {
        val n = note ?: return; deselect()
        for (pt in e.parts) {
            val rem = if (un) pt.added else pt.removed; val add = if (un) pt.removed else pt.added
            if (rem.isNotEmpty()) { val set = HashSet(rem); pt.page.items.removeAll { it in set } }
            pt.page.items.addAll(add); pt.page.dirty = true; pt.page.rev++
            val i = n.pages.indexOf(pt.page); if (i < 0) continue
            val all = rem + add; if (all.isEmpty()) continue
            val r = RectF(all[0].bounds); for (it in all) r.union(it.bounds); markDirty(i, r)
        }
        invalidate(); pane.ed.onDocChanged(pane)
    }

    /* ================= eraser ================= */
    private fun record(pg: Page, orig: Item, pieces: List<Item>) {
        val g = gParts.getOrPut(pg) { Pair(ArrayList(), ArrayList()) }
        if (!g.second.remove(orig)) g.first.add(orig)
        g.second.addAll(pieces)
    }
    private fun eraseAt(sx: Float, sy: Float) {
        erX = sx; erY = sy
        val n = note ?: return; val i = pageAt(sx, sy, false); if (i < 0) return
        val pg = n.pages[i]; if (!pg.loaded) return
        val x = px(i, sx); val y = py(i, sy); val r = P.f("er.size") / 2f
        val target = P.s("er.target"); val precise = P.s("er.mode") == "precise"
        val square = !precise && P.s("er.shape") == "square"; val prev = !precise && P.b("er.prev")
        val items = pg.items; var changed = false; var k = items.size - 1
        while (k >= 0) {
            val it = items[k]
            if (it is Stroke) {
                val ok = erKind(it, target)
                if (ok && (if (square) it.hitBox(x, y, r) else it.hit(x, y, r))) {
                    if (prev) { if (!erPend.containsKey(it)) { erPend[it] = i; invalidate() }; k--; continue }
                    if (precise && it.tool != K_TAPE) { val pieces = split(it, x, y, r + it.width / 2f); items.removeAt(k); items.addAll(k, pieces); record(pg, it, pieces) }
                    else { items.removeAt(k); record(pg, it, emptyList()) }
                    markDirty(i, it.bounds); changed = true
                }
            } else if (it !is LinkItem && (target == "all" || target == "media") && it.bounds.contains(x, y)) { items.removeAt(k); record(pg, it, emptyList()); markDirty(i, it.bounds); changed = true }
            k--
        }
        if (changed) { pg.dirty = true; pg.rev++ }
    }
    private fun erKind(it: Item, target: String): Boolean = when (target) {
        "ink" -> it is Stroke && it.tool != K_HL && it.tool != K_SHAPE && it.tool != K_TAPE; "hl" -> it is Stroke && it.tool == K_HL; "shapes" -> it is Stroke && (it.tool == K_SHAPE || it.tool == K_TAPE)
        "media" -> it is ImageItem || it is TextItem; else -> it !is LinkItem }
    /** "Clear page": removes everything the eraser is set to erase from the page in view (one undo step) */
    fun clearPage() {
        val n = note ?: return; val i = curPage(); val pg = n.pages.getOrNull(i) ?: return; if (!pg.loaded) return
        val target = P.s("er.target"); val gone = pg.items.filter { erKind(it, target) }; if (gone.isEmpty()) return
        deselect(); val set = HashSet(gone); pg.items.removeAll { it in set }; pg.dirty = true; pg.rev++
        pushUndo(Edit(listOf(Part(pg, gone, emptyList())))); markDirty(i, RectF(0f, 0f, pg.w, pg.h)); invalidate(); pane.ed.onDocChanged(pane)
    }
    /** Smaller / Larger buttons of the selection card */
    fun selScale(k: Float) { val s = sel ?: return; s.m.postScale(k, k, s.bounds.centerX(), s.bounds.centerY()); commitSel(); invalidate() }
    /** cuts the part of a stroke that lies inside the circle (cx,cy,R) and returns the remaining pieces */
    private fun split(s: Stroke, cx: Float, cy: Float, R: Float): List<Item> {
        val pts = s.pts; val n = pts.size / 3; val out = ArrayList<Item>(); var run = ArrayList<Float>()
        val r2 = R * R
        fun inside(i: Int): Boolean { val dx = pts[i * 3] - cx; val dy = pts[i * 3 + 1] - cy; return dx * dx + dy * dy <= r2 }
        fun close() { if (run.size >= 6) out.add(Stroke(s.tool, s.color, s.width, run.toFloatArray(), s.time)); run = ArrayList() }
        /** parameters where segment a->b crosses the circle */
        fun cross(a: Int, b: Int): FloatArray? {
            val ax = pts[a * 3] - cx; val ay = pts[a * 3 + 1] - cy; val dx = pts[b * 3] - pts[a * 3]; val dy = pts[b * 3 + 1] - pts[a * 3 + 1]
            val qa = dx * dx + dy * dy; if (qa < 1e-9f) return null
            val qb = 2 * (ax * dx + ay * dy); val qc = ax * ax + ay * ay - r2; val disc = qb * qb - 4 * qa * qc; if (disc < 0) return null
            val sq = sqrt(disc); return floatArrayOf((-qb - sq) / (2 * qa), (-qb + sq) / (2 * qa))
        }
        fun addAt(a: Int, b: Int, t: Float) { val u = t.coerceIn(0f, 1f)
            run.add(pts[a * 3] + (pts[b * 3] - pts[a * 3]) * u); run.add(pts[a * 3 + 1] + (pts[b * 3 + 1] - pts[a * 3 + 1]) * u); run.add(pts[a * 3 + 2] + (pts[b * 3 + 2] - pts[a * 3 + 2]) * u) }
        var prevIn = inside(0)
        if (!prevIn) { run.add(pts[0]); run.add(pts[1]); run.add(pts[2]) }
        for (i in 1 until n) {
            val curIn = inside(i)
            if (!prevIn && !curIn) {
                val t = cross(i - 1, i)
                if (t != null && t[0] > 0f && t[1] < 1f && t[0] < t[1]) { addAt(i - 1, i, t[0]); close(); addAt(i - 1, i, t[1]) }
                run.add(pts[i * 3]); run.add(pts[i * 3 + 1]); run.add(pts[i * 3 + 2])
            } else if (!prevIn && curIn) { val t = cross(i - 1, i); if (t != null) addAt(i - 1, i, t[0]); close() }
            else if (prevIn && !curIn) { val t = cross(i - 1, i); if (t != null) addAt(i - 1, i, t[1]); run.add(pts[i * 3]); run.add(pts[i * 3 + 1]); run.add(pts[i * 3 + 2]) }
            prevIn = curIn
        }
        close(); return out
    }
    private fun endErase() {
        erShow = false
        if (erPend.isNotEmpty()) {   // the strokes that were shown in red go now, as one undo step with the rest
            val n = note
            if (n != null) for ((s, i) in erPend) { val pg = n.pages.getOrNull(i) ?: continue; if (pg.items.remove(s)) { record(pg, s, emptyList()); pg.dirty = true; pg.rev++; markDirty(i, s.bounds) } }
            erPend.clear()
        }
        if (gParts.isNotEmpty()) {
            val parts = gParts.map { Part(it.key, ArrayList(it.value.first), ArrayList(it.value.second)) }.filter { it.removed.isNotEmpty() || it.added.isNotEmpty() }
            gParts.clear()
            if (parts.isNotEmpty()) { pushUndo(Edit(parts)); pane.ed.onDocChanged(pane) }
        }
        invalidate()
        if (!tempTool && P.b("er.back") && Tools.cur == "eraser" && Tools.prev != "eraser") Tools.set(Tools.prev)
    }

    /* ================= lasso / selection ================= */
    private fun endLasso() {
        val n = note; val i = lassoPage
        if (n == null || i !in n.pages.indices || !n.pages[i].loaded) { lasso.clear(); invalidate(); return }
        val pg = n.pages[i]; val what = P.s("ls.what")
        fun allowed(it: Item) = it !is LinkItem || what == "all" && true
        fun kindOk(it: Item) = when (what) { "ink" -> it is Stroke; "media" -> it !is Stroke; else -> true }
        var len = 0f; var q = 2; while (q < lasso.size) { len += hypot(lasso[q] - lasso[q - 2], lasso[q + 1] - lasso[q - 1]); q += 2 }
        val picked = ArrayList<Item>()
        if (len < dpf(12)) {
            if (P.b("ls.tap")) { val x = px(i, lasso[0]); val y = py(i, lasso[1])
                for (it in pg.items.asReversed()) { if (!kindOk(it)) continue; if ((it is Stroke && it.hit(x, y, 6f / scale + 2f)) || (it !is Stroke && it.bounds.contains(x, y))) { picked.add(it); break } } }
        } else {
            val poly = FloatArray(lasso.size); q = 0; while (q < lasso.size) { poly[q] = px(i, lasso[q]); poly[q + 1] = py(i, lasso[q + 1]); q += 2 }
            val pb = RectF(poly[0], poly[1], poly[0], poly[1]); q = 2; while (q < poly.size) { pb.union(poly[q], poly[q + 1]); q += 2 }
            val th = P.f("ls.thresh") / 100f
            for (it in pg.items) {
                if (!kindOk(it) || !allowed(it) || !RectF.intersects(pb, it.bounds)) continue
                if (it is Stroke) { var ins = 0; val cnt = it.n; var k = 0; while (k < it.pts.size) { if (Geo.inside(poly, it.pts[k], it.pts[k + 1])) ins++; k += 3 }
                    if (ins >= max(1f, cnt * th)) picked.add(it) }
                else if (Geo.inside(poly, it.bounds.centerX(), it.bounds.centerY())) picked.add(it)
            }
        }
        lasso.clear()
        if (picked.isNotEmpty()) select(i, picked) else invalidate()
    }

    fun select(i: Int, items: List<Item>) {
        deselect()
        val s = Selection(i, items); s.compute(); sel = s; selSet = HashSet(items)
        markDirty(i, s.bounds); renderSel(); invalidate(); pane.ed.onSelection(pane)
    }
    fun deselect() {
        val s = sel ?: return
        if (!s.m.isIdentity) commitSel()
        sel = null; selSet = null; s.bmp?.recycle(); s.bmp = null
        markDirty(s.page, s.bounds); invalidate(); pane.ed.onSelection(pane)
    }
    private fun renderSel() {
        val s = sel ?: return; val n = note ?: return
        s.bmp?.recycle(); s.bmp = null
        if (s.items.size < 12) return   // few items: drawn as crisp vectors every frame
        val r = RectF(s.bounds); r.inset(-2f, -2f)
        val sc = min(scale, sqrt(4e6f / max(1f, r.width() * r.height())))
        val bw = (r.width() * sc).toInt(); val bh = (r.height() * sc).toInt(); if (bw < 1 || bh < 1) return
        val b = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { return }
        val cv = Canvas(b); cv.scale(sc, sc); cv.translate(-r.left, -r.top); ip.sc = sc; ip.mult = false
        for (it in s.items) ip.item(cv, it, n.dir)
        s.bmp = b; s.bmpRect.set(r)
    }
    private fun commitSel() {
        val s = sel ?: return; val n = note ?: return
        if (s.m.isIdentity) return
        val pg = n.pages[s.page]; val old = s.items; var nw = old.map { it.transformed(s.m) }
        val scaled = abs(s.m.mapRadius(1f) - 1f) > 0.001f
        val nb = RectF(s.bounds); s.m.mapRect(nb)
        val j = pageAtDocY(tops[s.page] + nb.centerY())
        if (j != s.page && j in n.pages.indices && n.pages[j].loaded) {
            val off = Matrix(); off.setTranslate(lefts[s.page] - lefts[j], tops[s.page] - tops[j]); nw = nw.map { it.transformed(off) }
            val set = HashSet(old); pg.items.removeAll { it in set }; n.pages[j].items.addAll(nw)
            pushUndo(Edit(listOf(Part(pg, old, emptyList()), Part(n.pages[j], emptyList(), nw))))
            pg.dirty = true; pg.rev++; n.pages[j].dirty = true; n.pages[j].rev++; s.page = j
        } else {
            val map = HashMap<Item, Item>(); for (k in old.indices) map[old[k]] = nw[k]
            for (k in pg.items.indices) { val r = map[pg.items[k]]; if (r != null) pg.items[k] = r }
            pushUndo(Edit(listOf(Part(pg, old, nw)))); pg.dirty = true; pg.rev++
        }
        s.items = nw; s.m.reset(); s.compute(); selSet = HashSet(nw)
        if (scaled || s.bmp == null) renderSel() else { val r = RectF(s.bounds); r.inset(-2f, -2f); s.bmpRect.set(r) }
        invalidate(); pane.ed.onDocChanged(pane); pane.updateSelBar()
    }

    fun selCopy(): Boolean { val s = sel ?: return false; Clip.items = s.items; Clip.fromNote = note?.id
        if (P.s("ls.autoShelf").let { it == "copy" || it == "both" }) note?.let { Shelf.add(it, s.items) }; return true }
    fun selDelete() {
        val s = sel ?: return; val n = note ?: return; val pg = n.pages[s.page]
        val set = HashSet(s.items); pg.items.removeAll { it in set }; pg.dirty = true; pg.rev++
        pushUndo(Edit(listOf(Part(pg, s.items, emptyList()))))
        sel = null; selSet = null; s.bmp?.recycle(); invalidate(); pane.ed.onSelection(pane); pane.ed.onDocChanged(pane)
    }
    fun selCut() { val s = sel ?: return; Clip.items = s.items; Clip.fromNote = note?.id
        if (P.s("ls.autoShelf").let { it == "cut" || it == "both" }) note?.let { Shelf.add(it, s.items) }; selDelete() }
    fun selDuplicate() {
        val s = sel ?: return; val m = Matrix(); m.setTranslate(14f, 14f); val copies = s.items.map { it.transformed(m) }; val i = s.page
        deselect(); commitAdd(i, copies, true)
    }
    fun selRecolor(c: Int) {
        val s = sel ?: return; val n = note ?: return; val pg = n.pages[s.page]
        val nw = s.items.map { when (it) { is Stroke -> it.recolored(c); is TextItem -> TextItem(it.text, it.x, it.y, it.w, it.size, c, it.bold); else -> it } }
        val map = HashMap<Item, Item>(); for (k in s.items.indices) map[s.items[k]] = nw[k]
        for (k in pg.items.indices) { val r = map[pg.items[k]]; if (r != null) pg.items[k] = r }
        pushUndo(Edit(listOf(Part(pg, s.items, nw)))); pg.dirty = true; pg.rev++
        s.items = nw; selSet = HashSet(nw); renderSel(); invalidate(); pane.ed.onDocChanged(pane)
    }
    /** drops items (page-neutral coordinates starting at 0,0 or copied from a page) centred on a screen point */
    fun insertAt(items: List<Item>, sx: Float, sy: Float) {
        val n = note ?: return; if (items.isEmpty()) return
        var i = pageAt(sx, sy, false); if (i < 0) i = curPage(); if (!n.pages[i].loaded) return
        val b = RectF(items[0].bounds); for (it in items) b.union(it.bounds)
        val pg = n.pages[i]
        val cx = px(i, sx).coerceIn(b.width() / 2f, max(b.width() / 2f, pg.w - b.width() / 2f)); val cy = py(i, sy).coerceIn(b.height() / 2f, max(b.height() / 2f, pg.h - b.height() / 2f))
        val m = Matrix(); m.setTranslate(cx - b.centerX(), cy - b.centerY())
        commitAdd(i, items.map { it.transformed(m) }, true)
    }
    fun paste() { pasteAt(width / 2f, height / 2f) }
    fun pasteAt(sx: Float, sy: Float) {
        val n = note ?: return; if (Clip.items.isEmpty()) return
        val src = Clip.fromNote
        if (src != null && src != n.id) for (it in Clip.items) if (it is ImageItem) { val a = java.io.File(Store.noteDir(src), it.file); val b = java.io.File(n.dir, it.file); try { if (a.exists() && !b.exists()) a.copyTo(b) } catch (e: Exception) { } }
        insertAt(Clip.items, sx, sy)
    }

    /* ================= page structure ================= */
    fun structureChanged() { val n = note ?: return; n.metaDirty = true; relayout(); patchValid = false; patchGen++; invalidate(); settle(); pane.ed.onDocChanged(pane); pane.ed.onViewChanged(pane) }
    fun pageChanged(i: Int) { val n = note ?: return; if (i !in n.pages.indices) return; val pg = n.pages[i]; caches[pg.id]?.scale = -1f; remGen[pg.id] = (remGen[pg.id] ?: 0) + 1; patchValid = false; patchGen++; n.metaDirty = true; invalidate(); settle() }
    fun count(): Int = note?.pages?.size ?: 0
}
