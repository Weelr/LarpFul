package com.weelr.larpful2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Drives the real activity end to end (no device needed) so crashes are caught by CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1400dp-h900dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmokeTest {
    private fun settle(n: Int = 6) { repeat(n) { Thread.sleep(120); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) } }
    private fun log(s: String) = println("SMOKE: $s")

    private fun me(down: Long, t: Long, action: Int, tool: Int, vararg xy: Float): MotionEvent {
        val n = xy.size / 2
        val props = Array(n) { MotionEvent.PointerProperties().apply { id = it; toolType = tool } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { x = xy[i * 2]; y = xy[i * 2 + 1]; pressure = 0.3f + 0.05f * (i % 5); size = 0.1f } }
        return MotionEvent.obtain(down, t, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    private fun drag(v: View, tool: Int, pts: List<Pair<Float, Float>>) {
        val t0 = SystemClock.uptimeMillis(); var t = t0
        v.dispatchTouchEvent(me(t0, t, MotionEvent.ACTION_DOWN, tool, pts[0].first, pts[0].second))
        for (p in pts.drop(1)) { t += 8; v.dispatchTouchEvent(me(t0, t, MotionEvent.ACTION_MOVE, tool, p.first, p.second)) }
        t += 8; v.dispatchTouchEvent(me(t0, t, MotionEvent.ACTION_UP, tool, pts.last().first, pts.last().second))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
    }
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 24) = (0..n).map { (x0 + (x1 - x0) * it / n) to (y0 + (y1 - y0) * it / n) }
    private fun paint() { val r = act.root; val b = Bitmap.createBitmap(r.width.coerceAtLeast(1), r.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888); r.draw(Canvas(b)); b.recycle() }
    private fun shot(name: String) {
        try { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)); Thread.sleep(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            val r = act.root; val b = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888); r.draw(Canvas(b))
            val d = File("build/shots"); d.mkdirs(); File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // also print a small copy into the log so it can be reviewed without downloading artifacts
            val sm = Bitmap.createScaledBitmap(b, r.width * 2 / 3, r.height * 2 / 3, true); val bo = ByteArrayOutputStream(); sm.compress(Bitmap.CompressFormat.JPEG, 62, bo)
            val b64 = java.util.Base64.getEncoder().encodeToString(bo.toByteArray()); var i = 0; var k = 0
            while (i < b64.length) { println("SHOT|$name|$k|" + b64.substring(i, minOf(b64.length, i + 3000))); i += 3000; k++ }
            sm.recycle(); b.recycle() } catch (e: Throwable) { log("shot $name failed: $e") }
    }
    private fun closeLayers() { var g = 0; while (act.layers.isNotEmpty() && g++ < 10) { act.layers.last().dismiss(); settle(1) } }

    @Test
    fun fullFlow() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = ctl.get(); settle()
        log("root ${a.root.width}x${a.root.height}"); assertTrue("root laid out", a.root.width > 0)
        paint(); shot("01-home-empty")

        // ---- new note + ink
        a.lib.newNote(); settle()
        assertEquals(1, a.ed.panes.size)
        val pane = a.ed.focused!!; val v = pane.view; val note = pane.note!!
        var g = 0; while (!note.pages[0].loaded && g++ < 20) settle(1)
        assertTrue("page loaded", note.pages[0].loaded)
        log("view ${v.width}x${v.height} scale ${v.scale}")
        val pen = MotionEvent.TOOL_TYPE_STYLUS; val finger = MotionEvent.TOOL_TYPE_FINGER
        val cx = v.width / 2f
        for (t in listOf("pen", "ball", "hl")) { Tools.set(t); drag(v, pen, line(cx - 150, 200f + 60 * Tools.ALL.indexOf(t), cx + 150, 230f + 60 * Tools.ALL.indexOf(t))) }
        settle(2); paint()
        assertEquals("three strokes", 3, note.pages[0].items.size); shot("02-editor-pen")
        // a circle held at the end becomes a tidy shape
        Tools.set("pen"); P.set("pen.shape", "always")
        drag(v, pen, (0..40).map { (cx + 80 * Math.cos(it * Math.PI / 20).toFloat()) to (500f + 80 * Math.sin(it * Math.PI / 20).toFloat()) })
        P.set("pen.shape", "hold"); settle(1)
        assertEquals(4, note.pages[0].items.size); log("shape kind=" + (note.pages[0].items[3] as Stroke).tool)

        // ---- undo / redo
        v.doUndo(); settle(1); assertEquals(3, note.pages[0].items.size); v.doRedo(); settle(1); assertEquals(4, note.pages[0].items.size)

        // ---- eraser (whole stroke, then precise) + auto return to the pen
        Tools.set("eraser"); P.set("er.mode", "stroke")
        drag(v, pen, line(cx, 150f, cx, 215f)); settle(2)
        assertEquals("stroke erased", 3, note.pages[0].items.size); assertEquals("back to previous tool", "pen", Tools.cur)
        Tools.set("eraser"); P.set("er.mode", "precise"); P.set("er.back", false)
        drag(v, pen, line(cx, 240f, cx, 300f)); settle(2); log("after precise erase: ${note.pages[0].items.size} items")
        assertTrue("precise erase split a stroke", note.pages[0].items.size >= 3)
        P.set("er.back", true); P.set("er.mode", "stroke"); paint()

        // ---- lasso
        Tools.set("lasso")
        drag(v, pen, listOf(cx - 200 to 380f, cx + 200 to 380f, cx + 200 to 620f, cx - 200 to 620f, cx - 200 to 380f).flatMap { listOf(it) }.let { c -> (0 until c.size - 1).flatMap { line(c[it].first, c[it].second, c[it + 1].first, c[it + 1].second, 8) } })
        settle(2); assertTrue("lasso selected the circle", v.sel != null); paint(); shot("03-lasso-selection")
        drag(v, pen, line(cx, 500f, cx + 40, 540f)); settle(1); assertTrue(v.sel != null)   // move
        v.selRecolor(0xFFFF0000.toInt()); v.selCopy(); v.selDuplicate(); settle(1); paint()
        a.ed.selToShelf(); settle(3); assertTrue("shelf has a snippet", Shelf.list.isNotEmpty())
        v.selCut(); settle(1); v.paste(); settle(1); assertTrue(v.sel != null); v.deselect(); settle(2)
        closeLayers()

        // ---- text, link, image-less insert
        a.ed.editText(pane, 0, null, 60f, 60f); settle(1); a.ed.commitText()
        v.commitAdd(0, listOf(TextItem("Hello مرحبا search-me", 60f, 700f, 260f, 14f, -16777216, false)))
        v.commitAdd(0, listOf(LinkItem(RectF(60f, 700f, 200f, 720f), note.id, note.pages[0].id, "self")))
        a.ed.addPage(pane, 0); settle(2); assertEquals(2, note.pages.size)
        paint()

        // ---- gestures: pan, pinch, two-finger tap undo
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        drag(v, finger, line(cx, 600f, cx, 300f)); settle(2)
        val t0 = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(me(t0, t0, MotionEvent.ACTION_DOWN, finger, cx - 100, 400f))
        v.dispatchTouchEvent(me(t0, t0 + 8, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, cx - 100, 400f, cx + 100, 400f))
        for (k in 1..10) v.dispatchTouchEvent(me(t0, t0 + 8 + k * 8L, MotionEvent.ACTION_MOVE, finger, cx - 100 - k * 12, 400f, cx + 100 + k * 12, 400f))
        v.dispatchTouchEvent(me(t0, t0 + 100, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, cx - 220, 400f, cx + 220, 400f))
        v.dispatchTouchEvent(me(t0, t0 + 110, MotionEvent.ACTION_UP, finger, cx - 220, 400f))
        settle(3); log("zoom after pinch ${v.zoom}"); assertTrue("pinch zoomed in", v.zoom > 1.2f); paint()
        Tools.set("pen"); drag(v, pen, line(cx - 50, 300f, cx + 50, 320f)); settle(2); paint()
        v.zoomTo(1f, cx, 300f); settle(3)
        // two-finger tap = undo
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        log("pre-tap: items=" + note.pages.map { it.items.size } + " links=" + note.pages.map { p -> p.items.count { it is LinkItem } } + " undo=${v.undo.size}")
        val cntBefore = note.pages.sumOf { it.items.size }; val t1 = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(me(t1, t1, MotionEvent.ACTION_DOWN, finger, cx - 60, 500f))
        v.dispatchTouchEvent(me(t1, t1 + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, cx - 60, 500f, cx + 60, 500f))
        v.dispatchTouchEvent(me(t1, t1 + 60, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, cx - 60, 500f, cx + 60, 500f))
        v.dispatchTouchEvent(me(t1, t1 + 70, MotionEvent.ACTION_UP, finger, cx - 60, 500f))
        fun st(tag: String) = log("$tag: items=" + note.pages.map { it.items.size } + " links=" + note.pages.map { p -> p.items.count { it is LinkItem } } + " undo=${v.undo.size} redo=${v.redo.size}")
        st("before tap (cnt=$cntBefore)")
        settle(2); st("after tap"); assertEquals("two-finger tap undoes", cntBefore - 1, note.pages.sumOf { it.items.size }); v.doRedo(); settle(1); st("after redo")

        // ---- scribble-to-delete, screenshot tool, comment, tool sets, tabs
        v.scrollToPage(0); settle(2)
        P.set("g.strike", true); Tools.set("pen")
        drag(v, pen, line(cx - 120, 900f, cx + 120, 900f)); settle(1)
        val beforeStrike = note.pages[0].items.size
        drag(v, pen, listOf(cx - 130 to 895f, cx + 130 to 905f, cx - 130 to 898f, cx + 130 to 903f, cx - 130 to 900f, cx + 130 to 902f).let { c -> (0 until c.size - 1).flatMap { line(c[it].first, c[it].second, c[it + 1].first, c[it + 1].second, 10) } })
        settle(2); log("strike: before=$beforeStrike after=${note.pages[0].items.size}"); assertEquals("scribble deleted the line under it", beforeStrike - 1, note.pages[0].items.size)
        P.set("g.strike", false)
        // ---- study tape: a drag lays a tape, a tap peels it back, another tap covers it again
        Tools.set("tape"); val tapeBefore = note.pages[0].items.size
        drag(v, pen, line(cx - 140, 600f, cx + 140, 603f)); settle(2)
        val tp = note.pages[0].items.lastOrNull() as? Stroke
        assertTrue("tape laid", tp != null && tp.tool == K_TAPE && note.pages[0].items.size == tapeBefore + 1); paint(); shot("37-tape-covered")
        drag(v, pen, listOf(cx to 601f)); settle(2); assertTrue("tap peels the tape", Tape.isOpen(tp!!)); paint(); shot("38-tape-peeled")
        drag(v, pen, listOf(cx to 601f)); settle(2); assertTrue("second tap covers it", !Tape.isOpen(tp)); assertEquals("tapping adds nothing", tapeBefore + 1, note.pages[0].items.size)
        Tools.set("pen")
        val shelfBefore = Shelf.list.size; P.set("shot.after", "shelf"); Tools.set("shot")
        drag(v, pen, line(cx - 150, 180f, cx + 150, 420f)); settle(6); log("shelf after capture ${Shelf.list.size}"); assertEquals("screenshot saved to the shelf", shelfBefore + 1, Shelf.list.size)
        P.set("shot.after", "ask"); drag(v, pen, line(cx - 150, 180f, cx + 150, 420f)); settle(4); paint(); closeLayers()
        Tools.set("lasso"); drag(v, pen, line(cx - 100, 700f * 0 + 232f, cx - 99, 232f, 2)); settle(2)
        if (v.sel == null) { v.select(0, listOf(note.pages[0].items[0])); settle(1) }
        pane.updateSelBar(); paint(); a.ed.comment(pane); settle(1); v.deselect(); settle(1)
        P.set("toolsets", "[{\"name\":\"Exam\",\"v\":{\"pen.width\":3.5,\"pen.color\":-65536,\"er.mode\":\"precise\"}}]"); a.ed.bar.rebuild(); settle(1); paint()

        a.ed.flush(); settle(6)
        assertTrue("ink saved", Store.inkFile(note.id, note.pages[0].id).exists())
        assertTrue("thumbnail saved", Store.thumbFile(note.id, note.pages[0].id).exists())
        val hits = Search.run("search-me", null); log("search hits ${hits.size}"); assertTrue("typed text is searchable", hits.isNotEmpty())
        assertTrue("arabic search", Search.run("مرحبا", null).isNotEmpty())
        P.set("sr.fuzzy", true); assertTrue("typo-tolerant search", Search.run("serch", null).isNotEmpty() && Search.run("seerch", null).isNotEmpty()); P.set("sr.fuzzy", false)
        assertTrue("exact search rejects the typo", Search.run("qqsearchzz", null).isEmpty())

        // ---- every panel / sheet opens and draws
        val m = Lib.note(note.id)!!
        val panels = listOf<Pair<String, () -> Unit>>("pages" to { PagesUi.open(a.ed) }, "search" to { SearchUi.open(a.ed) }, "shelf" to { ShelfUi.open(a.ed) }, "beside" to { OpenBeside.open(a.ed) },
            "paper" to { PaperUi.open(a.ed, pane) }, "history" to { HistoryUi.open(m) }, "settings" to { SettingsUi.main() }, "backup" to { BackupUi.center() }, "pentest" to { SettingsUi.penTest() },
            "links" to { LinksUi.panel(a.ed) }, "audio" to { a.ed.audio.toggleBar() }, "notemenu" to { a.lib.noteMenu(m, null) }, "move" to { a.lib.movePicker("Move", null) { } },
            "linkTap" to { LinksUi.tapped(a.ed, pane, note.pages[0].items.filterIsInstance<LinkItem>().first(), 300, 300) })
        for ((name, open) in panels) { open(); settle(2); paint(); shot("10-panel-$name"); closeLayers(); log("panel ok: $name") }
        a.ed.audio.toggleBar(); settle(1)
        for (t in listOf("pen", "ball", "hl", "eraser", "lasso", "ruler", "text", "shot", "toolbar")) { a.ed.bar.toolSettings(t, a.ed.bar.bar); settle(1); paint(); if (t == "pen") shot("11-pen-settings"); if (t == "lasso") shot("11-lasso-settings"); closeLayers() }
        // every section of the main settings unfolds and draws
        P.set("set.open", (0..15).joinToString(","), false); SettingsUi.main(); settle(2); shot("11-settings-open"); closeLayers(); P.set("set.open", "0,1,2,3,4", false)
        // double-tap with the pen tip: pen -> eraser, and no dots are left behind
        run {
            Tools.set("pen"); settle(1); val before = note.pages[0].items.size
            fun tapAt(x: Float, y: Float) { val t0 = SystemClock.uptimeMillis(); v.dispatchTouchEvent(me(t0, t0, MotionEvent.ACTION_DOWN, pen, x, y)); v.dispatchTouchEvent(me(t0, t0 + 30, MotionEvent.ACTION_UP, pen, x, y)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)) }
            tapAt(cx + 250, 660f); tapAt(cx + 251, 661f); settle(1)
            assertEquals("double tap with the pen tip switches to the eraser", "eraser", Tools.cur); assertEquals("and leaves no dots", before, note.pages[0].items.size)
            Tools.set("pen"); settle(1)
        }
        Tools.set("eraser"); settle(1); shot("12-eraser-context"); Tools.set("pen"); settle(1)
        // the original's tools and cards: shapes, extra pens, the full cards, the ⋯ card, lasso / text / move cards
        run {
            val before = note.pages[0].items.size
            Tools.set("shape"); settle(1)
            for ((i, k) in listOf("line", "rect", "ell").withIndex()) { P.set("shp.kind", k); drag(v, pen, line(cx + 200, 420f + 70 * i, cx + 330, 470f + 70 * i)) }
            settle(2); assertEquals("three shapes drawn", before + 3, note.pages[0].items.size)
            assertEquals("shapes are shape strokes", K_SHAPE, (note.pages[0].items.last() as Stroke).tool)
            Tools.set("pen"); Tools.set("shape"); settle(1); shot("21-shape-card")
            a.ed.bar.shapePop(); settle(1); shot("22-shape-pop"); closeLayers()
            repeat(3) { v.doUndo() }; settle(1); assertEquals("shapes undone", before, note.pages[0].items.size)
            Tools.set("pen"); a.ed.bar.penPop("pen"); settle(1); shot("23-pen-pop"); closeLayers()
            a.ed.bar.eraserPop(); settle(1); paint(); closeLayers()
            a.ed.bar.morePop(); settle(1); shot("24-toolbar-pop"); closeLayers()
            a.ed.bar.setsMenu(); settle(1); paint(); closeLayers(); a.ed.bar.favsPop(); settle(1); paint(); closeLayers()
            val id = Tools.addPen("bp"); P.set("$id.style", "bp", false); Tools.set(id); settle(1); drag(v, pen, line(cx + 200, 600f, cx + 300, 620f)); settle(1)
            assertEquals("an added ballpoint draws ballpoint ink", K_BALL, (note.pages[0].items.last() as Stroke).tool); v.doUndo(); Tools.removePen(id); settle(1)
            assertTrue("removed pen is gone", id !in Tools.all())
            Favs.save("pen"); settle(1); paint(); Favs.use(0); settle(1); Favs.remove(0); settle(1)
            P.set("tb.min", true); settle(1); paint(); P.set("tb.min", false); settle(1)
            Tools.set("lasso"); settle(1); shot("25-lasso-card"); Tools.set("text"); settle(1); paint(); Tools.set("move"); settle(1); paint(); Tools.set("shot"); settle(1); paint(); Tools.set("pen"); settle(1)
        }
        Tools.ruler = true; a.ed.invalidatePanes(); paint(); Tools.ruler = false
        for (e in listOf("top", "right", "bottom", "left")) { P.set("tb.edge", e); settle(1); paint() }
        // pen opacity: a 50% stroke must be an even 50% everywhere (no darker beads where its segments overlap)
        run {
            val bmp = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
            InkPainter().raw(cv, floatArrayOf(20f, 40f, 1f, 100f, 40f, 1f, 180f, 40f, 1f), 9, K_PEN, 0x80FF0000.toInt(), 12f)
            val a1 = bmp.getPixel(100, 40) ushr 24; val a2 = bmp.getPixel(60, 40) ushr 24
            log("translucent ink alpha at joint=$a1 mid-segment=$a2")
            assertTrue("pen opacity is even along the stroke", a1 in 110..145 && a2 in 110..145)
            bmp.recycle()
            P.set("pen.alpha", 50f); Tools.set("hl"); settle(1); Tools.set("pen"); settle(1); paint(); shot("20-pen-card"); P.set("pen.alpha", 100f)
        }
        // drag the toolbar by its grip to every edge, through the bar's real touch dispatch (what a finger does).
        // Releasing the grip used to rebuild the bar from inside its own touch delivery and crash on the next frame.
        run {
            val bar = a.ed.bar.bar; val er = a.ed.root
            for (want in listOf("right", "bottom", "top", "left")) {
                val gv = bar.getChildAt(0); val lx = gv.left + gv.width / 2f; val ly = gv.top + gv.height / 2f
                val W = er.width.toFloat(); val H = er.height.toFloat(); val top = a.ed.topInset().toFloat()
                val (cx, cy) = when (want) { "right" -> (W - 20f) to (top + (H - top) / 2f); "bottom" -> (W / 2f) to (H - 20f); "top" -> (W / 2f) to (top + 20f); else -> 20f to (top + (H - top) / 2f) }
                val dx = cx - (bar.translationX + bar.width / 2f); val dy = cy - (bar.translationY + bar.height / 2f)
                // press and hold a button (the bar picks itself up after the hold), then drag and release
                val t0 = SystemClock.uptimeMillis(); bar.dispatchTouchEvent(me(t0, t0, MotionEvent.ACTION_DOWN, finger, lx, ly))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); var tt = t0 + 500
                for (q in line(lx, ly, lx + dx, ly + dy, 14).drop(1)) { tt += 8; bar.dispatchTouchEvent(me(t0, tt, MotionEvent.ACTION_MOVE, finger, q.first, q.second)) }
                bar.dispatchTouchEvent(me(t0, tt + 8, MotionEvent.ACTION_UP, finger, lx + dx, ly + dy)); settle(2); paint()
                val kids = (0 until bar.childCount).count { bar.getChildAt(it) == null }
                log("toolbar dragged to ${P.s("tb.edge")} (wanted $want) children=${bar.childCount} null=$kids")
                assertEquals("toolbar edge after drag", want, P.s("tb.edge")); assertEquals("no broken toolbar children", 0, kids)
                if (want == "top") shot("18-toolbar-top")
            }
            // a quick swipe across the buttons (no hold) must not move the bar
            run { val gv = bar.getChildAt(1); drag(bar, finger, line(gv.left + 8f, gv.top + 8f, gv.left + 300f, gv.top + 8f, 8)); settle(1); assertEquals("no move without a hold", "left", P.s("tb.edge")) }
            shot("18-toolbar-left")
        }
        P.set("ui.theme", "dark"); settle(3); paint(); shot("13-dark")
        // iPhone-style pop-ups follow the theme (the system dialog showed dark text on a dark box)
        run {
            val nl = act.layers.size; var got = ""; prompt("Rename", "lab report 3") { got = it }; settle(2); shot("41-rename-dark")
            val dl = act.layers.last(); val tv = ArrayList<android.widget.EditText>()
            fun walk(v: android.view.View) { if (v is android.widget.EditText) tv.add(v); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(dl.content); assertTrue("rename field exists", tv.isNotEmpty()); assertEquals("field text is light in dark mode", Th.text, tv[0].currentTextColor)
            tv[0].setText("lab report 4"); tv[0].onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE); settle(2)
            assertEquals("Done on the keyboard saves", "lab report 4", got); assertTrue("Done closed the pop-up", act.layers.size == nl)
            confirm("Delete forever?", "This cannot be undone.", "Delete") { }; settle(2); shot("42-delete-dark"); act.layers.last().dismiss(); settle(1); assertEquals("only the pop-up closed", nl, act.layers.size)
        }
        P.set("ui.theme", "light"); settle(2)
        run { prompt("Rename", "lab report 3") { }; settle(2); shot("43-rename-light"); act.layers.last().dismiss(); settle(1) }

        // ---- folders, second note, split view
        val f = Folder(newId()); f.name = "Folder A"; Lib.folders.add(f); Lib.changed(); settle(1)
        val n2 = Store.createNote("Second", f.id); a.ed.open(n2.id, "right"); settle(3)
        assertEquals("split view has two windows", 2, a.ed.panes.size); paint(); shot("14-split")
        log("tabs=${a.ed.tabs.size} visible=${a.ed.tabBar.visibility == View.VISIBLE}"); assertTrue("tabs bar lists both notes", a.ed.tabs.size >= 2 && a.ed.tabBar.visibility == View.VISIBLE)
        Perf.report(); settle(4); paint(); closeLayers()
        // a third note replaces the focused window; closing its tab brings the previous note back into that window
        val n3 = Store.createNote("Third", null); a.ed.open(n3.id, "replace"); settle(3)
        assertEquals(3, a.ed.tabs.size); assertTrue(a.ed.isOpen(n3.id) && !a.ed.isOpen(n2.id))
        a.ed.closeTab(n3.id); settle(4)
        assertTrue("closing a tab switches the window to another tab", a.ed.panes.size == 2 && a.ed.isOpen(n2.id) && n3.id !in a.ed.tabs)
        g = 0; while (a.ed.focused!!.note!!.pages[0].loaded.not() && g++ < 20) settle(1)
        val v2 = a.ed.focused!!.view
        Tools.set("pen")
        drag(v2, pen, line(v2.width / 2f - 60, 200f, v2.width / 2f + 60, 220f)); settle(2)
        assertEquals(1, a.ed.focused!!.note!!.pages[0].items.size)
        // first tap on the other window only focuses it
        drag(v, pen, line(100f, 200f, 200f, 220f)); settle(1); assertTrue("focus moved", a.ed.focused === pane)
        a.ed.open(n2.id, "top"); settle(2); paint()

        // ---- text -> PDF -> import (PDF pages + text search); tolerated to fail under the test runtime
        try {
            val pdf = File(a.cacheDir, "t.pdf"); Convert.text("Lecture notes\nfindable-pdf-word\n".repeat(80), pdf)
            log("text->pdf bytes ${pdf.length()}")
            val id = Io.importFile(pdf, "Lecture.pdf", null); settle(4)
            log("imported pdf note $id pages=" + Lib.note(id!!)?.pages + " pdfHits=" + Search.run("findable-pdf-word", null).size)
            a.ed.open(id, "replace"); settle(4); paint()
        } catch (e: Throwable) { log("PDF import not testable here: $e") }
        try { Io.exportDialog(note, 0); settle(1) } catch (e: Throwable) { log("export dialog: $e") }

        // ---- FreeNotes backup (synthetic file written in the layout of a real backup: entry → holder → record → style, transform, path)
        run {
            fun dbl(vararg v: Double): ByteArray { val bb = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN); for (x in v) bb.putDouble(x); return bb.array() }
            fun pt(x: Double, y: Double, size: Double, pr: Double?) = msg(2, msg(1, f64(1, x) + f64(2, y)) + msg(3, f64(1, size) + f64(2, size)) + (if (pr != null) f64(4, 1.0) + f64(7, pr) else ByteArray(0)) + f64(10, 1.0))
            fun rec(tool: Int, color: Long, path: ByteArray) = msg(3, str(1, "obj") + msg(2, msg(1, str(1, "id") + msg(2, (if (tool != 0) vint(1, tool.toLong()) else ByteArray(0)) + vint(2, color) + vint(7, 255)) +
                msg(3, f64(1, 1.0) + f64(4, 1.0)) + msg(4, str(1, "p") + path + vint(6, 3))) + str(2, "a") + str(3, "b")))
            val png = ByteArrayOutputStream().also { Bitmap.createBitmap(90, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val zip = File(a.cacheDir, "t.freebackup"); ZipOutputStream(zip.outputStream()).use { z ->
                fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
                val ink = str(1, "ink1") + str(2, "x") +
                    rec(1, 0x000000FFL, pt(10.0, 10.0, 2.392, 0.1) + pt(50.0, 40.0, 2.392, 0.9) + pt(90.0, 20.0, 2.392, 1.6)) +          // pen: one width whatever the pressure
                    rec(0, 0x000000FFL, pt(10.0, 60.0, 2.5116, 0.2) + pt(50.0, 90.0, 2.7, 0.9) + pt(90.0, 70.0, 2.201, 0.1)) +            // fountain pen: its own width in every point
                    rec(2, 0xFFCF2F7FL, pt(10.0, 120.0, 8.5333, null) + pt(90.0, 120.0, 8.5333, null)) +                                  // highlighter, points without pressure
                    rec(1, 0x000000FFL, pt(535.7, 103.1, 2.392, 0.2) + pt(533.2, 105.6, 2.392, 0.5) + msg(4, vint(1, 1001) + msg(2, dbl(535.73, 103.14, 516.09, 122.78)) + f64(3, 2.392))) +   // straightened line
                    rec(1, 0x000000FFL, pt(517.4, 279.3, 2.392, 0.2) + msg(4, vint(1, 1002) + msg(2, dbl(517.36, 279.32, 498.7, 283.49, 505.58, 292.32)) + f64(3, 2.392)))                    // curve: start, end, bend
                put("n1.frnote/FNPages/ink1.dat", ink)
                put("n1.frnote/FNPagesObject/objects-0001.dat", msg(3, msg(2, msg(1, str(1, "i") + str(5, "placed01.png") + msg(6, msg(1, f64(1, 100.0) + f64(2, 200.0)) + msg(2, f64(1, 150.0) + f64(2, 90.0)))))))
                put("n1.frnote/Objects/Images/placed01.png", png)
                put("n1.frnote/Objects/Images/loose0002.png", png)
                put("n1.frnote/document.dat", msg(4, msg(1, str(1, "pg2") + vint(3, 1)) + msg(1, str(1, "pg1") + str(6, "ink1") + str(7, "objects-0001"))))
                put("n1.frnote/layout.dat", msg(3, msg(1, f64(1, 612.0) + f64(2, 792.0)) + str(5, "pg1")))
                put("n1.frnote/metadata.dat", str(3, "FN note"))
                put("foldertree.frdata", "{\"folderList\":[{\"id\":\"f1\",\"name\":\"Uni\",\"parentId\":\"unnamed\"}],\"noteList\":[{\"id\":\"n1\",\"name\":\"From FreeNotes\",\"parentId\":\"f1\",\"createDate\":\"2024-01-02 10:00:00\"}]}".toByteArray())
            }
            val shelf0 = Shelf.list.size
            FreeNotes.importFile(zip, "t"); settle(4)
            val fn = Lib.notes.firstOrNull { it.fid == "n1" }
            assertTrue("FreeNotes note imported", fn != null); assertEquals("From FreeNotes", fn!!.title); assertEquals("Uni", Lib.folder(fn.folder)?.name)
            assertEquals("FreeNotes pages follow their saved order", 2, fn.pages)
            val items = InkIO.readFile(Store.inkFile(fn.id, fn.first!!))
            val st = items.filterIsInstance<Stroke>(); val im = items.filterIsInstance<ImageItem>()
            log("freenotes items=${items.size} widths=${st.map { it.width }} tools=${st.map { it.tool }} pics=${im.map { it.rect }} shelf=${Shelf.list.size - shelf0}")
            assertEquals("FreeNotes: every stroke is kept", 5, st.size)
            assertEquals("pen is a third of the nib", 0.7894f, st[0].width, 0.002f); assertTrue("pen keeps one width", (0 until st[0].n).all { st[0].pts[it * 3 + 2] == 1f })
            assertEquals("fountain pen base", 0.8288f, st[1].width, 0.002f); assertEquals("fountain pen swells per point", 2.7f / 2.5116f, st[1].pts[5], 0.002f); assertEquals(2.201f / 2.5116f, st[1].pts[8], 0.002f)
            assertEquals(K_HL, st[2].tool); assertEquals("highlighter width", 2.816f, st[2].width, 0.01f); assertEquals(127, Color.alpha(st[2].color)); assertEquals(2, st[2].n)
            assertEquals(K_SHAPE, st[3].tool); assertEquals("line: two ends", 2, st[3].n); assertEquals(516.09f, st[3].pts[3], 0.01f); assertEquals(122.78f, st[3].pts[4], 0.01f)
            assertEquals(K_SHAPE, st[4].tool); assertTrue("curve is sampled", st[4].n >= 17)
            run { val k = (st[4].n - 1) / 2 * 3; assertEquals("curve passes its middle", 506.8f, st[4].pts[k], 0.6f); assertEquals(286.86f, st[4].pts[k + 1], 0.6f) }
            assertEquals("curve ends at its end", 498.7f, st[4].pts[(st[4].n - 1) * 3], 0.01f)
            assertEquals("picture placed on the page", 1, im.size); assertEquals(RectF(100f, 200f, 250f, 290f), im[0].rect); assertTrue(File(Store.noteDir(fn.id), im[0].file).exists())
            assertTrue("pictures come before the ink", items[0] is ImageItem)
            assertEquals("the picture that is on no page waits in the shelf", 1, Shelf.list.size - shelf0)
            // the page as the real painter draws it, saved next to the other shots
            try { val k = 2f; val b = Bitmap.createBitmap((612 * k).toInt(), (320 * k).toInt(), Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); val c = Canvas(b); c.scale(k, k)
                val ip = InkPainter(); ip.sc = k; for (it in items) ip.item(c, it, Store.noteDir(fn.id))
                val d = File("build/shots"); d.mkdirs(); File(d, "36-freenotes-import.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Throwable) { log("freenotes shot failed: $e") }
        }

        // ---- restore a backup made by the original (web) Larpful: old single-document style, then the tablet's line style.
        // The fixtures were written by the real original app (its own backup code), so this checks the real format.
        run {
            fun res(name: String): File { val f = File(a.cacheDir, name); javaClass.classLoader!!.getResourceAsStream(name)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; return f }
            val before = Lib.notes.size
            val f1 = res("larpful1-v1.larpful"); assertTrue("recognised as a Larpful backup", Larpful1.isBackup(f1))
            Larpful1.importFile(f1); settle(4)
            assertEquals("6 notebooks restored", before + 6, Lib.notes.size)
            val sn = Lib.notes.first { it.title == "Study Notes" && it.fid?.startsWith("lf1:") == true }
            assertEquals("Drawing", Lib.folder(sn.folder)?.name); assertEquals("Sample", Lib.folder(Lib.folder(sn.folder)?.parent)?.name)
            val its = InkIO.readFile(Store.inkFile(sn.id, sn.first!!))
            log("larpful1 restore: Study Notes items=${its.size} kinds=" + its.groupBy { it.javaClass.simpleName }.map { "${it.key}:${it.value.size}" })
            assertEquals("all 11 objects of the page", 11, its.size)
            assertEquals(1, its.count { it is ImageItem }); assertEquals(1, its.count { it is TextItem }); assertEquals(3, its.count { it is Stroke && it.tool == K_SHAPE }); assertEquals(1, its.count { it is Stroke && it.tool == K_BALL })
            assertTrue("inserted picture saved", File(Store.noteDir(sn.id), (its.first { it is ImageItem } as ImageItem).file).length() > 500)
            val tx = its.first { it is TextItem } as TextItem
            assertTrue("both text lines kept, box only as wide as the text", tx.text.contains("\n") && tx.bounds.width() < 400f && tx.bounds.height() > tx.size * 1.8f)
            val ramp = its.first { it is Stroke && it.tool == K_PEN && it.n == 60 && it.color == 0xFF111111.toInt() } as Stroke
            log("larpful1 restore: pressure ramp first=${ramp.pts[2]} last=${ramp.pts[ramp.pts.size - 1]} width=${ramp.width}")
            assertTrue("pressure kept (thin start, thick end)", ramp.pts[2] < 0.5f && ramp.pts[ramp.pts.size - 1] > 1.4f)
            val sk = Lib.notes.first { it.title == "Sketchbook" && it.fid?.startsWith("lf1:") == true }
            assertTrue("pinned kept", sk.pinned)
            val skn = Store.openNote(sk.id)!!
            assertTrue("page picture restored", skn.pages[0].bg != null && File(skn.dir, skn.pages[0].bg!!).length() > 500); assertEquals("recording restored", 1, skn.recs.size)
            a.ed.open(sn.id, "replace"); settle(4); paint(); shot("19-larpful1-restore")
            a.ed.open(sk.id, "replace"); settle(4); paint(); shot("19-larpful1-restore-pdfpage")
            Larpful1.importFile(res("larpful1-v2.larpful")); settle(4)
            assertEquals("line-style backup restored too", before + 12, Lib.notes.size)
            val s2 = Lib.notes.first { it.title == "Study Notes 2" }
            assertEquals(11, InkIO.readFile(Store.inkFile(s2.id, s2.first!!)).size)
            Larpful1.importFile(res("larpful1-v1.larpful")); settle(4)
            assertEquals("restoring the same backup twice adds nothing", before + 12, Lib.notes.size)
        }

        // ---- Word text, PDF text extraction (hand-made PDF), PNG export
        val docx = File(a.cacheDir, "t.docx"); ZipOutputStream(docx.outputStream()).use { z -> z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Title here</w:t></w:r></w:p><w:p><w:r><w:t>Body مرحبا</w:t></w:r></w:p></w:body></w:document>".toByteArray()); z.closeEntry() }
        val wt = Convert.docx(docx).toString(); log("docx text: " + wt.replace('\n', '|')); assertTrue(wt.contains("Title here") && wt.contains("Body"))
        val pdfFile = File(a.cacheDir, "hand.pdf"); pdfFile.writeBytes(tinyPdf("Hello findable text"))
        try { val out = File(a.cacheDir, "hand.txt"); PdfText.extract(pdfFile, out); log("pdfbox text: " + out.readText().trim()); assertTrue("pdfbox extracts text", out.readText().contains("findable")) }
        catch (e: AssertionError) { throw e } catch (e: Throwable) { log("pdfbox failed: $e"); throw e }
        val found = PdfText.find(pdfFile, 0, listOf("findable"), false); log("pdf word boxes: $found"); assertTrue("PDF word position found", found.isNotEmpty() && found[0].width() > 5f)
        val png = ByteArrayOutputStream(); Io.write(note.id, 0, true, png); log("png export bytes ${png.size()}"); assertTrue(png.size() > 1000)

        // ---- version history
        val before = note.pages[0].items.size
        a.ed.open(note.id, "replace"); settle(3)
        Store.io { Store.snapshot(note.id, true) }; settle(3)
        val snaps = Store.snapshots(note.id); log("snapshots ${snaps.size}"); assertTrue(snaps.isNotEmpty())
        val pv = a.ed.panes.first { it.note?.id == note.id }.view; val live = a.ed.panes.first { it.note?.id == note.id }.note!!
        Tools.set("pen"); a.ed.focus(a.ed.panes.first { it.note?.id == note.id }); settle(1)
        pv.scrollToPage(0); settle(2)
        drag(pv, pen, line(pv.width / 2f - 80, 150f, pv.width / 2f + 80, 150f)); settle(1); drag(pv, pen, line(pv.width / 2f - 80, 150f, pv.width / 2f + 80, 150f)); settle(1)
        log("items before=$before now=${live.pages[0].items.size}")
        a.ed.flush(); settle(5)
        Store.io { Store.restoreSnapshot(note.id, snaps[0]) }; settle(3); a.ed.reload(note.id); settle(4)
        val rest = a.ed.panes.first { it.note?.id == note.id }.note!!; log("after restore items=${rest.pages[0].items.size}")
        assertTrue("history restore brought the old ink back", rest.pages[0].loaded && rest.pages[0].items.size < live.pages[0].items.size)

        // ---- backup: one file, then merge + replace restore
        a.ed.flush(); settle(5); Store.io { Lib.saveNow() }; settle(2)
        val bk = File(a.cacheDir, "all.larpful2"); bk.outputStream().use { BackupUi.write(it) }; log("backup bytes ${bk.length()}")
        val count = Lib.notes.size; val victim = Lib.note(n2.id)!!
        a.lib.purge(victim); settle(4); assertEquals(count - 1, Lib.notes.size)
        val c1 = File(a.cacheDir, "c1"); bk.copyTo(c1, true); BackupUi.restore(c1, false); settle(8)
        assertEquals("merge restore brings the deleted note back", count, Lib.notes.size); assertTrue(File(Store.noteDir(n2.id), "note.json").exists())
        val c2 = File(a.cacheDir, "c2"); bk.copyTo(c2, true); BackupUi.restore(c2, true); settle(10)
        assertEquals("replace restore", count, Lib.notes.size); assertTrue(Store.inkFile(note.id, note.pages[0].id).exists()); paint()
        a.ed.open(note.id); settle(4)

        // ---- tabs: closing the tab of an open note switches that window to another tab
        val openIds = a.ed.panes.mapNotNull { it.note?.id }; log("tabs before close ${a.ed.tabs} open=$openIds")
        if (a.ed.tabs.size >= 2) { val victimTab = a.ed.tabs.first { it in openIds }; a.ed.closeTab(victimTab); settle(3); assertTrue("tab closed", victimTab !in a.ed.tabs); assertTrue(a.ed.panes.none { it.note?.id == victimTab }) }
        a.ed.open(note.id); settle(3)

        // ---- library operations
        a.lib.browser.open(f.id); settle(1); paint(); a.lib.browser.up(); settle(2)
        // hold a notebook on the home grid and drag it onto a folder
        a.ed.goHome(); settle(3); assertTrue("home shown, tabs kept", a.ed.panes.isEmpty() && a.ed.tabs.isNotEmpty())
        run {
            val lv = a.lib.browser.list; val rows = a.lib.browser.rows
            val fi = rows.indexOfFirst { it is Folder }; val ni = rows.indexOfFirst { it is NoteMeta && it.folder == null }
            val fv = lv.getChildAt(fi - lv.firstVisiblePosition); val nv = lv.getChildAt(ni - lv.firstVisiblePosition)
            log("sidebar rows=${rows.size} folderRow=$fi noteRow=$ni views=${fv != null}/${nv != null}")
            log("DIAG focus=${a.currentFocus?.javaClass?.simpleName} layers=${a.layers.size} rootKids=${a.root.childCount} touchMode=${a.root.isInTouchMode} lvShown=${lv.isShown} nvLongClick=${nv?.isLongClickable}")
            if (fv != null && nv != null) {
                val moved = rows[ni] as NoteMeta; val dest = rows[fi] as Folder
                val la = IntArray(2); nv.getLocationOnScreen(la); val lb = IntArray(2); fv.getLocationOnScreen(lb)
                val x0 = la[0] + nv.width / 2f; val y0 = la[1] + nv.height / 2f; val x1 = lb[0] + fv.width / 2f; val y1 = lb[1] + fv.height / 2f
                val td = SystemClock.uptimeMillis()
                a.root.dispatchTouchEvent(me(td, td, MotionEvent.ACTION_DOWN, finger, x0, y0))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
                for (k in 1..8) a.root.dispatchTouchEvent(me(td, td + 900 + k * 10L, MotionEvent.ACTION_MOVE, finger, x0 + (x1 - x0) * k / 8, y0 + (y1 - y0) * k / 8))
                a.root.dispatchTouchEvent(me(td, td + 1000, MotionEvent.ACTION_UP, finger, x1, y1)); settle(2)
                log("DIAG after layers=${a.layers.size} ord=${moved.ord}");                log("dragged “${moved.title}” -> folder=${Lib.folder(moved.folder)?.name}"); assertEquals("note dropped into the folder", dest.id, moved.folder)
                moved.folder = null; Lib.changed(); settle(1)
            }
        }
        // hold a notebook and drop it beside another one: "My own order"
        run {
            val lv = a.lib.browser.list; val rows = a.lib.browser.rows; val ns = rows.filterIsInstance<NoteMeta>()
            if (ns.size >= 2) {
                val first = ns[0]; val last = ns[1]   // the second one: the last card can sit under the round + button
                val v0 = lv.getChildAt(rows.indexOf(last) - lv.firstVisiblePosition); val v1 = lv.getChildAt(rows.indexOf(first) - lv.firstVisiblePosition)
                if (v0 != null && v1 != null) {
                    val la = IntArray(2); v0.getLocationOnScreen(la); val lb = IntArray(2); v1.getLocationOnScreen(lb)
                    val x0 = la[0] + v0.width / 2f; val y0 = la[1] + v0.height / 2f; val x1 = lb[0] + v1.width * 0.2f; val y1 = lb[1] + v1.height / 2f
                    val td = SystemClock.uptimeMillis()
                    a.root.dispatchTouchEvent(me(td, td, MotionEvent.ACTION_DOWN, finger, x0, y0))
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
                    for (k in 1..8) a.root.dispatchTouchEvent(me(td, td + 900 + k * 10L, MotionEvent.ACTION_MOVE, finger, x0 + (x1 - x0) * k / 8, y0 + (y1 - y0) * k / 8))
                    paint(); shot("30-home-reorder")
                    a.root.dispatchTouchEvent(me(td, td + 1000, MotionEvent.ACTION_UP, finger, x1, y1)); settle(2)
                    val now = a.lib.browser.rows.filterIsInstance<NoteMeta>()
                    log("reorder: ${ns.map { it.title }} -> ${now.map { it.title }} sort=${P.s("lib.sort")}")
                    assertEquals("own order is on", "manual", P.s("lib.sort")); assertEquals("the dragged notebook is first now", last.id, now[0].id)
                    P.set("lib.sort", "modified"); settle(1)
                }
            }
        }
        // the original's sidebar filters, select mode and the search box
        for (flt in listOf("pin", "unt", "unf", "trash", "all")) { a.lib.showFilter(flt); settle(1); paint(); assertEquals(flt, a.lib.filter) }
        a.lib.showFilter("t:" + f.id); settle(1); paint(); assertEquals("folder filter opens the folder", f.id, a.lib.browser.folder); a.lib.showFilter("all"); settle(1)
        a.lib.selectMode(true); settle(1); a.lib.browser.selecting!!.add(note.id); a.lib.browser.refresh(); settle(1); shot("26-home-select"); a.lib.selectMode(false); settle(1)
        a.lib.browser.query = "untitled"; a.lib.browser.refresh(); settle(1); paint(); assertTrue("search finds by name", a.lib.browser.rows.isNotEmpty()); a.lib.browser.query = ""; a.lib.browser.refresh()
        // home search also looks inside notes: a hit inside a notebook brings its card, with the page on a chip
        run { val hits = Search.run("a", null).filter { it.kind != "Name" }; log("inside-notes hits=${hits.size}")
            if (hits.isNotEmpty()) { val h = hits[0]; a.lib.browser.query = "zzqq"; a.lib.browser.inside[h.note.id] = h; a.lib.browser.refresh(); settle(1); shot("35-home-search-inside")
                assertTrue("a notebook found by its content is listed", a.lib.browser.rows.any { (it as? NoteMeta)?.id == h.note.id }) }
            a.lib.browser.query = ""; a.lib.browser.inside.clear(); a.lib.browser.refresh(); settle(1) }
        a.ed.open(note.id); settle(3); assertTrue("an open note keeps the full width", a.lib.view.parent !== a.main)
        a.lib.toggle(); settle(2); shot("27-editor-sidebar"); assertTrue("sidebar slides over the note", a.lib.over); a.lib.toggle(); settle(2)
        // page manager in the sidebar: Edit shows ✕ / Add file; a tap outside closes the sidebar; leaving the note shows one logo only
        run {
            a.lib.pgEdit = true; a.lib.toggle(); settle(2); shot("31-pages-edit")
            val t0 = SystemClock.uptimeMillis(); a.root.dispatchTouchEvent(me(t0, t0, MotionEvent.ACTION_DOWN, finger, a.root.width - 60f, a.root.height / 2f)); a.root.dispatchTouchEvent(me(t0, t0 + 40, MotionEvent.ACTION_UP, finger, a.root.width - 60f, a.root.height / 2f)); settle(2)
            assertTrue("a tap outside closes the sidebar", !a.lib.over); a.lib.pgEdit = false
            a.lib.toggle(); settle(1); a.ed.goHome(); settle(3); shot("32-home-after-sidebar"); assertTrue("sidebar is docked again, not floating", !a.lib.over && a.lib.view.parent === a.main)
            a.ed.open(note.id); settle(3)
            // zoom lock: a pinch must not change the zoom
            P.set("g.zLock", true); settle(1); shot("33-zoom-lock"); val pv = a.ed.focused!!.view; val z0 = pv.zoom; val t1 = SystemClock.uptimeMillis()
            pv.dispatchTouchEvent(me(t1, t1, MotionEvent.ACTION_DOWN, finger, 500f, 500f))
            pv.dispatchTouchEvent(me(t1, t1 + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, 500f, 500f, 600f, 500f))
            for (k in 1..6) pv.dispatchTouchEvent(me(t1, t1 + 10 + k * 12L, MotionEvent.ACTION_MOVE, finger, 500f - k * 14, 500f, 600f + k * 14, 500f))
            pv.dispatchTouchEvent(me(t1, t1 + 100, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger, 416f, 500f, 684f, 500f))
            pv.dispatchTouchEvent(me(t1, t1 + 110, MotionEvent.ACTION_UP, finger, 416f, 500f)); settle(1)
            assertEquals("zoom is locked", z0, a.ed.focused!!.view.zoom, 0.001f); P.set("g.zLock", false); settle(1)
            // paper sheet: the tick follows the tapped paper
            PaperUi.open(a.ed, a.ed.focused!!); settle(1)
            fun find(v: View, s: String): View? { if (v is android.widget.TextView && v.text.toString() == s) return v; if (v is android.view.ViewGroup) for (k in 0 until v.childCount) find(v.getChildAt(k), s)?.let { return it }; return null }
            (find(a.root, "Grid")?.parent as? View)?.performClick(); settle(2); shot("34-paper-grid")
            assertEquals("paper applied", "grid", a.ed.focused!!.note!!.pages[0].paper)
            (find(a.root, "Lined")?.parent as? View)?.performClick(); settle(1); closeLayers()
        }
        a.ed.audio.recordings(); settle(1); shot("28-recordings"); closeLayers()
        a.ed.focused!!.note!!.pages[0].label = "Lecture 1"; a.ed.focused!!.view.pageChanged(0); settle(2); shot("29-editor"); a.ed.focused!!.note!!.pages[0].label = null; a.ed.focused!!.view.pageChanged(0)
        val m3 = Lib.note(note.id)!!
        Store.duplicateNote(m3) { }; settle(4)
        m3.trashed = System.currentTimeMillis(); a.ed.closeNote(m3.id); Lib.changed(); settle(2); paint()
        m3.trashed = 0; Lib.changed(); settle(1)
        Lib.note(note.id)?.let { CoverUi.open(it.id, false, false); settle(2); shot("17-cover-picker"); closeLayers() }
        // Claude summary: options + page previews, then the result sheet and saving it as text pages (no network here)
        run {
            P.set("ai.key", "sk-ant-test")
            assertTrue("the API key is never put in backups", !P.exportJson().has("ai.key"))
            val sm = Lib.note(note.id)!!; AiUi.open(sm); settle(4); shot("39-summary-pick"); closeLayers()
            val sn = Store.openNote(note.id)!!
            val demo = "Transformers\n• Definition: a Transformer changes AC Voltage using mutual induction.\n• المحول يرفع أو يخفض الجهد (Voltage) بدون تغيير التردد.\n\nKey equations\n• V1 / V2 = N1 / N2\n• P = V × I"
            AiUi.result(sm, sn, emptyList(), demo); settle(2); shot("40-summary-result"); closeLayers()
            val long = (1..120).joinToString("\n") { "• Line $it of a long summary about Voltage and Current" }
            val pgs = Ai.toPages("Summary", long); log("summary pages=${pgs.size}")
            assertTrue("a long summary spreads over several pages", pgs.size >= 2)
            assertTrue("every summary page holds text", pgs.all { p -> p.items.any { it is TextItem } })
            val before = a.ed.focused?.note?.pages?.size ?: 0
            AiUi.savePages(sm, demo, false); settle(3)
            if (a.ed.focused?.note?.id == note.id) assertEquals("summary added as a page", before + 1, a.ed.focused!!.note!!.pages.size)
            P.set("ai.key", "")
        }
        run { val f2 = Store.coverFile(n2.id); f2.outputStream().use { CoverUi.bitmap(CoverUi.COVERS[1].second, 480, 640).compress(Bitmap.CompressFormat.JPEG, 90, it) }; Lib.note(n2.id)?.cover = true; Lib.note(n2.id)?.pinned = true; Thumbs.bump(); Lib.changed(); settle(2) }
        for (p in ArrayList(a.ed.panes)) a.ed.closePane(p); settle(3); shot("15-home-library")
        a.lib.browser.open(f.id); settle(2); shot("16-home-folder"); a.lib.browser.up(); settle(1)
        a.ed.open(note.id); settle(3)
        a.lib.toggle(); settle(2); paint(); a.lib.toggle(); settle(2)

        // ---- restart: the session comes back with the ink that was written
        a.ed.flush(); settle(6)
        ctl.pause().stop().destroy()
        val a2 = Robolectric.buildActivity(MainActivity::class.java).setup().get(); settle(6)
        log("after restart panes=${a2.ed.panes.size} notes=${Lib.notes.size}")
        assertTrue("session restored", a2.ed.panes.isNotEmpty())
        val re = Store.openNote(note.id)!!; Store.loadAll(re); log("reloaded items=${re.pages[0].items.size}")
        assertTrue("ink persisted", re.pages[0].items.size >= 5)
        val b = Bitmap.createBitmap(a2.root.width, a2.root.height, Bitmap.Config.ARGB_8888); a2.root.draw(Canvas(b))
        log("DONE")
    }

    private fun tinyPdf(text: String): ByteArray {
        val objs = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "STREAM", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val sb = StringBuilder("%PDF-1.4\n"); val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o -> offs.add(sb.length); sb.append("${i + 1} 0 obj\n")
            if (o == "STREAM") { val c = "BT /F1 18 Tf 20 100 Td ($text) Tj ET"; sb.append("<< /Length ${c.length} >>\nstream\n$c\nendstream") } else sb.append(o)
            sb.append("\nendobj\n") }
        val xref = sb.length; sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) sb.append(String.format("%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    // minimal protobuf writer for the synthetic FreeNotes file
    private fun vi(v: Long): ByteArray { val o = ByteArrayOutputStream(); var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { o.write(x.toInt()); break }; o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; return o.toByteArray() }
    private fun vint(f: Int, v: Long) = vi((f.toLong() shl 3)) + vi(v)
    private fun msg(f: Int, b: ByteArray) = vi((f.toLong() shl 3) or 2) + vi(b.size.toLong()) + b
    private fun str(f: Int, s: String) = msg(f, s.toByteArray())
    private fun f64(f: Int, d: Double) = vi((f.toLong() shl 3) or 1) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array()
}
