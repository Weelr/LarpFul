package com.weelr.larpful2

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/* ---------- preference store (memory-cached: reads are free while drawing) ---------- */
object P {
    lateinit var sp: SharedPreferences
    val def = HashMap<String, Any>()
    private val cache = HashMap<String, Any>()
    val listeners = ArrayList<(String) -> Unit>()
    fun init(c: Context) {
        sp = c.getSharedPreferences("larpful2", Context.MODE_PRIVATE)
        for ((k, v) in sp.all) if (v != null) cache[k] = v
        Reg.register()
    }
    fun get(k: String): Any? = cache[k] ?: def[k] ?: alias(k)?.let { def[it] }
    /** "pen2.width" falls back to the default of "pen.width": added pens start as copies of the first one */
    private fun alias(k: String): String? { val d = k.indexOf('.'); if (d < 2 || k[d - 1] !in '0'..'9') return null; return k.substring(0, d).trimEnd { it in '0'..'9' } + k.substring(d) }
    fun b(k: String): Boolean = (get(k) as? Boolean) ?: false
    fun f(k: String): Float = (get(k) as? Number)?.toFloat() ?: 0f
    fun i(k: String): Int = (get(k) as? Number)?.toInt() ?: 0
    fun l(k: String): Long = (get(k) as? Number)?.toLong() ?: 0L
    fun s(k: String): String = get(k)?.toString() ?: ""
    fun has(k: String) = cache.containsKey(k)
    fun set(k: String, v: Any, notify: Boolean = true) {
        cache[k] = v
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v); else -> e.putString(k, v.toString()) }
        e.apply()
        if (notify) for (l in ArrayList(listeners)) l(k)
    }
    fun reset(keys: Collection<String>) {
        val e = sp.edit(); for (k in keys) { cache.remove(k); e.remove(k) }; e.apply()
        for (l in ArrayList(listeners)) l("*")
    }
    /** the Claude API key never leaves the tablet: it is left out of every backup */
    fun exportJson(): org.json.JSONObject { val o = org.json.JSONObject(); for ((k, v) in cache) if (k != "ai.key") o.put(k, v); return o }
    fun importJson(o: org.json.JSONObject) {
        for (k in o.keys()) { val v = o.get(k); val d = def[k]
            when {
                d is Boolean && v is Boolean -> set(k, v, false)
                d is Int && v is Number -> set(k, v.toInt(), false)
                d is Float && v is Number -> set(k, v.toFloat(), false)
                d is String -> set(k, v.toString(), false)
                d == null && v is String -> set(k, v, false)
                d == null && v is Boolean -> set(k, v, false)
                d == null && v is Int -> set(k, v, false)
            } }
        for (l in ArrayList(listeners)) l("*")
    }
}

/* ---------- schema ---------- */
sealed class Opt(val key: String, val label: String)
class Tog(key: String, label: String, val d: Boolean) : Opt(key, label)
class Num(key: String, label: String, val d: Float, val min: Float, val max: Float, val step: Float, val unit: String = "") : Opt(key, label)
class Pick(key: String, label: String, val d: String, val choices: List<Pair<String, String>>) : Opt(key, label)
class Colr(key: String, label: String, val d: Int) : Opt(key, label)
class Act(label: String, val icon: String = "chev", val danger: Boolean = false, val run: (View) -> Unit) : Opt("", label)
class Info(label: String) : Opt("", label)
class Group(val title: String, val opts: List<Opt>, val footer: String = "")

object Reg {
    private val toolColors = mapOf(
        "pen" to intArrayOf(col(0xFF111111), col(0xFF0040DD), col(0xFFFF3B30), col(0xFF34C759), col(0xFFAF52DE)),
        "ball" to intArrayOf(col(0xFF1C1C1E), col(0xFF0A3D91), col(0xFFC81E1E), col(0xFF00A86B), col(0xFF8E8E93)),
        "hl" to intArrayOf(col(0xFFFFD60A), col(0xFF8BD17C), col(0xFFFF8FB1), col(0xFF32ADE6), col(0xFFFF9500)))
    private val toolWidths = mapOf("pen" to floatArrayOf(1.2f, 2.2f, 4f), "ball" to floatArrayOf(0.8f, 1.3f, 2.2f), "hl" to floatArrayOf(8f, 18f, 22f))

    fun penGroups(t0: String): List<Group> {
        val t = Tools.base(t0)
        return penGroups0(t).map { g -> Group(g.title, g.opts.map { o -> if (t0 == t) o else when (o) {
            is Tog -> Tog(t0 + o.key.substring(t.length), o.label, o.d); is Num -> Num(t0 + o.key.substring(t.length), o.label, o.d, o.min, o.max, o.step, o.unit)
            is Pick -> Pick(t0 + o.key.substring(t.length), o.label, o.d, o.choices); is Colr -> Colr(t0 + o.key.substring(t.length), o.label, o.d); else -> o } }, g.footer) }
    }
    private fun penGroups0(t: String): List<Group> {
        val hl = t == "hl"
        val a = ArrayList<Opt>()
        if (!hl) { a.add(Tog("$t.press", "Pressure sensitivity", true)); a.add(Num("$t.sens", "Pressure curve", if (t == "pen") 60f else 15f, 0f, 100f, 5f, "%")) }
        a.add(Pick("$t.shape", "Hold to snap shapes", if (hl) "off" else "hold", listOf("off" to "Off", "hold" to "Hold at the end", "always" to "Always")))
        a.add(Num("$t.holdMs", "Hold time", 450f, 200f, 1200f, 50f, " ms"))
        a.add(Tog("$t.shapeSnap", "Snap lines to 0° / 45° / 90°", true))
        if (!hl) { a.add(Tog("g.strike", "Strikethrough to delete strokes (pen)", false)); a.add(Tog("$t.taper", "Tapered stroke ends", t == "pen")) }
        else { a.add(Tog("hl.straight", "Keep highlighter lines straight", false)); a.add(Tog("hl.under", "Blend with the text (no washed-out letters)", true)) }
        val b = ArrayList<Opt>()
        b.add(Num("$t.smooth", "Smoothing intensity", if (hl) 20f else 35f, 0f, 90f, 5f, "%"))
        b.add(Tog("$t.refine", "Refine after lifting the pen", true))
        if (!hl) b.add(Num("$t.straight", "Straighten almost-straight strokes", 30f, 0f, 100f, 5f, "%"))
        val c = ArrayList<Opt>()
        c.add(Num("$t.width", "Thickness", toolWidths[t]!![1], if (hl) 3f else 0.3f, if (hl) 40f else 18.5f, if (hl) 1f else 0.1f, " pt"))
        c.add(Colr("$t.color", "Colour", toolColors[t]!![0]))
        c.add(Num("$t.alpha", "Opacity", if (hl) 35f else 100f, 10f, 100f, 5f, "%"))
        return listOf(Group("Writing", a), Group("Handwriting beautifier", b, "Smoothing only steadies the line you draw. It never guesses or rewrites your handwriting."), Group("Ink", c))
    }

    val eraser by lazy { listOf(Group("Behaviour", listOf(
        Pick("er.mode", "Eraser mode", "stroke", listOf("stroke" to "Stroke", "precise" to "Pixel")),
        Tog("er.back", "Eraser auto-switch back", true),
        Pick("er.target", "Eraser removes", "all", listOf("all" to "Everything", "ink" to "Pen ink only", "hl" to "Highlights only", "shapes" to "Shapes & tape only", "media" to "Text & images only")),
        Tog("er.prev", "Highlight strokes before erasing (they go when you lift the pen)", false), Colr("er.prevColor", "Highlight colour", col(0xFFFF3B30)))),
        Group("Eraser look & size", listOf(Num("er.size", "Eraser size", 14f, 4f, 80f, 1f, ""),
            Tog("er.cursor", "Show boundary", true), Colr("er.cursorColor", "Boundary color", col(0xFF8E8E93)),
            Num("er.cursorW", "Boundary thickness", 2f, 1f, 6f, 0.5f, ""), Tog("er.fill", "Fill the eraser area", true),
            Pick("er.shape", "Eraser shape", "round", listOf("round" to "Round", "square" to "Square")),
            Tog("er.dash", "Dashed boundary", false), Tog("er.cross", "Show a + in the middle", false)),
            "The square shape and the highlight preview work in Stroke mode; the Pixel eraser always cuts a round hole.")) }

    val lasso by lazy { listOf(Group("Lasso & snippet shelf", listOf(
        Pick("ls.mode", "Lasso shape", "free", listOf("free" to "Freehand", "rect" to "Rectangle")),
        Pick("ls.what", "Lasso selects", "all", listOf("all" to "Everything", "ink" to "Ink only", "media" to "Text & images")),
        Tog("ls.tap", "Tap an object to select it", true),
        Tog("ls.menu", "Floating menu above a selection (Copy, Cut, Comment…)", true), Colr("ls.comment", "Comment colour", col(0xFFFF9500)),
        Num("ls.commentSize", "Comment text size", 11f, 8f, 20f, 1f, " pt"),
        Pick("ls.style", "Lasso line style", "solid", listOf("solid" to "Like the pen (solid, smooth)", "dash" to "Dashed outline (old)")),
        Num("ls.w", "Lasso line thickness", 3f, 1f, 10f, 1f, ""),
        Colr("ls.color", "Lasso line colour", col(0xFF0A84FF)), Tog("ls.tint", "Tint the area being selected", true),
        Num("ls.thresh", "Select a stroke when this much is inside", 55f, 10f, 100f, 5f, "%"),
        Pick("ls.autoShelf", "Auto-save to Shelf on", "off", listOf("off" to "Never", "copy" to "Copy", "cut" to "Cut", "both" to "Copy and Cut")),
        Tog("ls.keep", "Keep proportions while resizing", true))),
        Group("Shelf", shelf[0].opts), shelf[1], Group("Shelf file", shelf[2].opts)) }

    val shot by lazy { listOf(Group("Screenshot tool", listOf(
        Pick("shot.q", "Image sharpness", "2", listOf("1" to "Normal", "2" to "Sharp", "3" to "Maximum")),
        Pick("shot.after", "After capturing", "ask", listOf("ask" to "Ask me", "shelf" to "Save to Shelf", "share" to "Share", "copy" to "Copy")),
        Colr("shot.color", "Frame colour", col(0xFFFF3B30))), "Drag a rectangle over any part of a page — PDF content included — to keep it as an image.")) }

    val ruler by lazy { listOf(Group("Ruler", listOf(
        Pick("ru.units", "Units", "cm", listOf("cm" to "Centimetres", "in" to "Inches")),
        Num("ru.snap", "Snap distance", 26f, 8f, 60f, 2f, " px"),
        Tog("ru.angle", "Snap rotation to 15° steps", true), Tog("ru.label", "Show length while drawing", true),
        Num("ru.alpha", "Ruler opacity", 82f, 30f, 100f, 2f, "%")))) }

    val tape by lazy { listOf(Group("Study tape", listOf(
        Num("tape.width", "Tape thickness", 22f, 8f, 70f, 1f, " pt"), Colr("tape.color", "Tape colour", col(0xFFFFB340)),
        Pick("tape.look", "Tape look", "stripes", listOf("stripes" to "Striped", "solid" to "Solid")),
        Pick("tape.peeled", "A peeled tape shows", "outline", listOf("outline" to "Dashed outline", "faint" to "Faint tint", "hidden" to "Nothing")),
        Tog("tape.level", "Keep tape level (snap flat or upright)", true),
        Tog("tape.finger", "Tap with a finger to peel / cover", true), Tog("tape.pen", "Tap with the pen to peel / cover", true),
        Act("Reveal every tape on this page", "eye") { act.ed.focused?.view?.allTapes(true) },
        Act("Cover every tape on this page", "tape") { act.ed.focused?.view?.allTapes(false) }),
        "Drag the tape over answers or definitions. Tap it to peel it back, tap again to cover. A notebook always opens with every tape covering.")) }

    val text by lazy { listOf(Group("Text", listOf(
        Num("tx.size", "Size", 21f, 9f, 72f, 1f, " pt"), Colr("tx.color", "Colour", col(0xFF000000)), Tog("tx.bold", "Bold", false),
        Num("tx.width", "New box width", 260f, 120f, 500f, 10f, " pt")))) }

    val shelf by lazy { listOf(Group("Shelf", listOf(
        Pick("sh.side", "Shelf side", "right", listOf("right" to "Right", "left" to "Left")),
        Num("sh.w", "Shelf width", 300f, 240f, 460f, 10f, ""), Num("sh.cols", "Columns", 2f, 1f, 3f, 1f),
        Pick("sh.sort", "Sort snippets", "new", listOf("new" to "Newest first", "old" to "Oldest first")),
        Tog("sh.close", "Close shelf after dropping", false), Tog("sh.confirm", "Confirm before deleting", true),
        Num("sh.max", "Maximum snippets", 100f, 10f, 500f, 10f),
        Tog("sh.dock", "Pin the shelf beside the page (the page makes room)", false))),
        Group("New snippets", listOf(
            Pick("sh.what", "A snippet keeps", "items", listOf("items" to "The ink & objects (editable)", "image" to "A picture of the area (PDF & paper included)")),
            Pick("sh.q", "Picture sharpness", "2.5", listOf("1.5" to "Normal", "2.5" to "Sharp", "4" to "Maximum")),
            Num("sh.pad", "Extra margin around a picture", 6f, 0f, 40f, 2f, " pt"),
            Pick("sh.fmt", "Picture format", "png", listOf("png" to "PNG (crisp)", "jpg" to "JPEG (smaller)")),
            Tog("sh.openAfter", "Open the shelf after saving a snippet", true)),
            "The picture settings apply when a snippet keeps a picture of the area."),
        Group("Shelf file", listOf(Act("Export shelf…", "share") { ShelfUi.exportFile() }, Act("Import shelf…", "noteplus") { ShelfUi.importFile() },
            Act("Clear shelf", "trash", true) { confirm("Delete every snippet on the shelf?", "This cannot be undone.", "Clear") { for (s in ArrayList(Shelf.list)) Shelf.remove(s); ShelfUi.refresh() } }))) }

    val audio by lazy { listOf(Group("Recording", listOf(
        Num("au.kbps", "Quality", 64f, 16f, 256f, 16f, " kbps"), Pick("au.ch", "Channels", "mono", listOf("mono" to "Mono", "stereo" to "Stereo")),
        Pick("au.rate", "Sample rate", "44100", listOf("16000" to "16 kHz", "22050" to "22 kHz", "44100" to "44.1 kHz", "48000" to "48 kHz")),
        Pick("au.src", "Microphone mode", "mic", listOf("mic" to "Standard", "voice" to "Voice — removes noise and echo", "far" to "Lecture — speaker far away, no processing", "raw" to "Unprocessed (if the tablet supports it)")),
        Pick("au.fmt", "File format", "m4a", listOf("m4a" to "M4A / AAC (plays everywhere)", "ogg" to "Opus (about half the size)")),
        Tog("au.stopClose", "Stop recording when I close the note", true))),
        Group("Playback", listOf(Num("au.speed", "Speed", 1f, 0.5f, 3f, 0.25f, "×"), Num("au.skip", "Skip interval", 10f, 5f, 30f, 5f, " s"),
            Num("au.lead", "Start this long before the stroke", 1.5f, 0f, 6f, 0.5f, " s"), Num("au.reach", "Tap reach", 14f, 6f, 40f, 2f, " pt"),
            Tog("au.autoplay", "Play right after jumping", true), Tog("au.seek", "Tap ink to jump while the player is open", true),
            Tog("au.dim", "Replay: dim ink that was not written yet", true), Num("au.dimAlpha", "Dimmed ink visibility", 25f, 5f, 60f, 5f, "%"),
            Pick("au.gran", "Tapping ink jumps to the start of", "stroke", listOf("stroke" to "That stroke", "word" to "The word", "line" to "The line")),
            Tog("au.pitch", "Keep the voice natural at other speeds", true),
            Tog("au.follow", "Follow the writing: turn to the page being written", false)))) }

    val links by lazy { listOf(Group("Links", listOf(
        Pick("lk.trig", "Follow a link by", "tap", listOf("tap" to "Tapping it", "double" to "Double-tapping it", "long" to "Pressing and holding", "off" to "Links panel only")),
        Pick("lk.act", "Opening a link", "preview", listOf("preview" to "Preview card", "jump" to "Jump")),
        Pick("lk.open", "Jump opens", "same", listOf("same" to "Here", "beside" to "Beside")),
        Pick("lk.style", "Link style", "underline", listOf("underline" to "Underline", "highlight" to "Highlight", "box" to "Box", "none" to "Invisible")),
        Colr("lk.color", "Link colour", col(0xFF007AFF)), Num("lk.w", "Line thickness", 1.5f, 0.5f, 5f, 0.5f, " pt"),
        Num("lk.pvW", "Preview card width", 340f, 240f, 520f, 20f, " dp"), Tog("lk.marker", "Show ↗ marker", true),
        Num("lk.pvAuto", "Close the preview by itself after", 0f, 0f, 20f, 1f, " s (0 = never)"),
        Tog("lk.back", "Show a Back button after following a link", true), Num("lk.backSec", "Back button stays for", 12f, 0f, 60f, 2f, " s (0 = until tapped)")))) }

    val search by lazy { listOf(Group("Search", listOf(
        Pick("sr.scope", "Search in", "all", listOf("all" to "All notes", "note" to "This note")),
        Pick("sr.mode", "Several words", "all", listOf("phrase" to "Exact phrase", "all" to "All words", "any" to "Any word")),
        Tog("sr.case", "Match case", false), Tog("sr.word", "Whole words only", false), Tog("sr.fuzzy", "Tolerate small typos", false), Tog("sr.ar", "Arabic: ignore diacritics and letter variants", true),
        Tog("sr.mark", "Highlight the match on the page", true), Colr("sr.hl", "Highlight colour", col(0xFFFFD60A)), Tog("sr.keep", "Keep highlights until the next search", false),
        Tog("sr.names", "Search names", true), Tog("sr.typed", "Search typed text", true), Tog("sr.pdf", "Search PDF text", true),
        Num("sr.max", "Maximum results", 200f, 50f, 1000f, 50f), Num("sr.ctx", "Text shown around a match", 40f, 10f, 120f, 10f, " chars"),
        Num("sr.w", "Panel width", 340f, 280f, 460f, 10f, " dp"),
        Num("sr.alpha", "Highlight strength", 40f, 15f, 90f, 5f, "%"),
        Tog("sr.links", "Search link names", true),
        Pick("sr.sort", "Order results by", "recent", listOf("recent" to "Recently edited notebooks first", "name" to "Notebook name, then page")),
        Tog("sr.first", "The keyboard's Search key opens the first result", true),
        Num("sr.min", "Start searching after", 1f, 1f, 4f, 1f, " letters"), Num("sr.delay", "Wait after typing", 250f, 0f, 1000f, 50f, " ms")))) }

    val paper by lazy { listOf(Group("Paper", listOf(
        Num("paper.sp", "Spacing", 34f, 20f, 60f, 2f, " px"), Num("paper.line", "Line strength", 100f, 40f, 160f, 5f, "%"),
        Tog("paper.margin", "Margin line on lined paper", false),
        Pick("paper.apply", "Apply choice to", "all", listOf("page" to "This page", "all" to "Whole notebook", "new" to "New pages")),
        Pick("doc.size", "New page size", "a4", listOf("a4" to "A4", "letter" to "Letter", "a4l" to "A4 landscape", "sq" to "Square", "wide" to "16 : 9")))) ) }

    val pages by lazy { listOf(Group("Pages panel", listOf(Num("pg.cols", "Columns", 2f, 1f, 3f, 1f), Tog("pg.numbers", "Show page numbers", true),
        Tog("pg.jump", "Tap the page counter to jump by number", true)))) }

    val split by lazy { listOf(Group("Split view", listOf(Tog("split.focusTap", "First tap only selects the window", true), Tog("split.ring", "Outline the active window", true),
        Num("split.ratio", "Default size of the new window", 50f, 25f, 75f, 5f, "%"), Tog("split.keep", "Reopen split windows at launch", true)))) }

    val backup by lazy { listOf(Group("Backup", listOf(Tog("bk.remind", "Remind me to back up", true), Num("bk.days", "Remind after", 7f, 1f, 60f, 1f, " days"),
        Tog("bk.settings", "Include settings when restoring", true))), Group("Automatic backup", listOf(Tog("bk.auto", "Back up automatically", false),
            Pick("bk.every", "How often", "1", listOf("1" to "Every day", "3" to "Every 3 days", "7" to "Every week")),
            Num("bk.keep", "Keep the newest", 5f, 1f, 30f, 1f, " backups"),
            Act("Backup folder…", "folder") { AutoBackup.chooseFolder() }, Act("Back up now", "save") { AutoBackup.run(true) },
            Tog("bk.autoWarn", "Tell me if an automatic backup fails", true)),
            "Runs quietly when you leave the app, into a folder you choose (for example Documents or a Drive-synced folder). Older automatic copies are removed; your own backup files are never touched.")) }

    val history by lazy { listOf(Group("Version history", listOf(Tog("hist.on", "Keep a history of every note", true),
        Num("hist.every", "Save a version every", 5f, 1f, 60f, 1f, " min"), Num("hist.max", "Versions kept per note", 12f, 3f, 40f, 1f)))) }

    private val PAPER_NAMES = listOf("lined" to "ruled", "grid" to "grid", "dots" to "dots", "blank" to "blank", "graph" to "graph", "cornell" to "cornell", "music" to "music", "iso" to "isometric", "dark" to "dark", "black" to "black")
    /** Main settings: the original's sections, in its order and with its wording. The first five are open, the rest unfold when tapped. */
    fun general(): List<Group> = listOf(
        Group("Appearance", listOf(Pick("ui.theme", "Theme", "system", listOf("system" to "System", "light" to "Light", "dark" to "Dark")))),
        Group("Stylus — M-Pencil 3", listOf(
            Tog("pen.palm", "Smart palm rejection", true), Tog("pen.only", "Stylus-only drawing (fingers scroll)", true),
            Pick("pen.barrel", "Pen side button / M-Pencil tap", "hold", listOf("hold" to "Hold", "toggle" to "Toggle", "off" to "Off")),
            Tog("pen.tipTap", "Double-tap pen tip = pen/eraser", true),
            Tog("perf.unbuffered", "Low-latency rendering", true),
            Tog("pen.barrel2", "Barrel double-tap toggles pen/eraser", true),
            Tog("pen.dbg", "Pen diagnostics (show button codes)", false),
            Num("pen.pressure", "Overall pressure response", 100f, 40f, 200f, 5f, "%"),
            Pick("pen.curve", "Pressure feel", "linear", listOf("soft" to "Soft", "linear" to "Linear", "firm" to "Firm")),
            Num("pen.palmMs", "Ignore touches after the pencil for", 400f, 100f, 1500f, 50f, " ms")),
            "If the double-tap does nothing: in tablet Settings › Accessibility features › Stylus, turn off the “Open shortcut menu” action, then run the pen writing test under Diagnostics."),
        Group("Tools", listOf(
            Tog("tb.favOnly", "Toolbar: show only favorite tools", false),
            Tog("tb.show.pen", "Ink pen", true), Tog("tb.show.ball", "Ballpoint pen", true), Tog("tb.show.hl", "Highlighter", true), Tog("tb.show.eraser", "Eraser", true), Tog("tb.show.shape", "Shapes", true), Tog("tb.show.tape", "Study tape", true),
            Tog("tb.show.ruler", "Ruler", true), Tog("tb.show.text", "Text box", true), Tog("tb.show.image", "Insert image", true), Tog("tb.show.shot", "Screenshot tool", true),
            Tog("tb.presets", "Favorite tool presets", true), Tog("tk.keep", "Remember each tool's colour and size between sessions and updates", true),
            Tog("tb.show.lasso", "Show Lasso tool in toolbar", true))),
        Group("Gesture Settings", listOf(
            Tog("g.two", "Two-finger tap undo / three-finger redo", true), Tog("doc.addPage", "Pull-to-add page", true),
            Tog("g.dzoom", "Double-tap with a finger = fit / zoom", true))),
        Group("Document Editing", listOf(
            Pick("ru.units", "Ruler units", "cm", listOf("cm" to "Cm", "in" to "In")),
            Pick("doc.paper", "New page template", "lined", PAPER_NAMES),
            Tog("g.xPage", "Keep drawing when the pen crosses to another slide", true),
            Tog("imp.open", "Open imported documents automatically", true), Tog("imp.rich", "Keep the layout of Word / PowerPoint files when converting to PDF", true),
            Tog("ui.fullscreen", "Full-screen mode", true),
            Tog("doc.auto3", "Auto-save every 3 min", true),
            Pick("tb.edge", "Toolbar position", "left", listOf("left" to "left", "right" to "right", "top" to "top", "bottom" to "bottom")),
            Tog("tb.lock", "Lock toolbar position", false), Tog("ui.anim", "Smooth animations", true), Tog("tb.tabs", "Top tabs bar for open files", true),
            Tog("ui.audioBtns", "Audio recording buttons", true), Tog("ui.splitBtn", "Split-view button", true), Tog("ui.shareBtn", "Quick share button", true),
            Pick("exp.scope", "Quick export covers", "page", listOf("page" to "Page", "notebook" to "Notebook")),
            Tog("g.zAnch", "Pinch zoom stays on the point you pinch", true),
            Tog("ui.pgMgr", "Page manager in the pages sidebar (edit / delete / add file)", true),
            Tog("doc.resume", "Reopen a note where I stopped (page, position and zoom)", true),
            Tog("ui.sheetOut", "Tap outside Settings / sheets to close them", true),
            Tog("ui.dlgSel", "Pop-up text fields (rename…): select the text when they open", true),
            Tog("ui.dlgClear", "Pop-up text fields: show the ✕ clear button", true),
            Tog("ui.panelOut", "Tap outside side panels (pages, shelf…) to close them", true),
            Tog("ui.wake", "Always wake up (screen stays on while the app is open)", true),
            Num("doc.gap", "Space between pages", 12f, 0f, 40f, 2f, " pt"), Num("tb.tabsMax", "Maximum tabs", 8f, 2f, 16f, 1f),
            Pick("doc.size", "New page size", "a4", listOf("a4" to "A4", "letter" to "Letter", "a4l" to "A4 landscape", "sq" to "Square", "wide" to "16 : 9")),
            Act("Split view settings", "split") { SettingsUi.sub("Split view", split) }, Act("Version history settings", "history") { SettingsUi.sub("Version history", history) })),
        Group("Smart Toolbar", listOf(
            Tog("tb.ctx", "Context-aware tool options", true), Tog("cb.auto", "Open options when I pick a tool", true),
            Pick("cb.collapse", "Auto-collapse options", "draw", listOf("draw" to "When I start drawing", "timer" to "After a delay", "never" to "Never")),
            Num("cb.delay", "Collapse delay", 4f, 2f, 15f, 1f, " s"),
            Tog("cb.labels", "Show labels under icons", false), Tog("cb.thick", "Show thickness", true), Tog("cb.opac", "Show opacity", true), Tog("cb.colors", "Show color swatches", true),
            Num("tb.swatches", "Swatches", 5f, 3f, 8f, 1f), Pick("cb.src", "Swatch source", "favorites", listOf("favorites" to "Favorites + palette", "palette" to "Palette")),
            Pick("tb.size", "Toolbar button size", "compact", listOf("compact" to "Compact (38)", "regular" to "Regular (44)", "large" to "Large (52)")),
            Tog("tb.undo", "Undo / redo buttons", true), Tog("tb.dot", "Color button", true), Tog("tb.mark", "Colour line above tool icons", true), Tog("tb.presets", "Pen presets", true),
            Num("tb.autoMin", "Auto-collapse toolbar when idle", 0f, 0f, 60f, 5f, " s (0 = off)"),
            Pick("cb.sz", "Tool options panel size", "85", listOf("100" to "Normal", "85" to "Smaller", "70" to "Small")))),
        Group("Performance", listOf(
            Pick("perf.sharp", "Canvas sharpness", "balanced", listOf("battery" to "Battery saver", "balanced" to "Balanced", "max" to "Maximum sharpness")),
            Num("doc.autosave", "Save this long after you stop writing", 2f, 1f, 30f, 1f, " s"),
            Tog("perf.hud", "Show performance meter", false)),
            "Ink is drawn the instant the pencil reports it. Larpful never predicts where the pencil will go."),
        Group("Paper", listOf(
            Pick("paper.apply", "Apply paper choice to", "all", listOf("page" to "This page", "all" to "Whole notebook", "new" to "New pages only")),
            Num("paper.sp", "Line / grid spacing", 34f, 20f, 60f, 2f, " px"), Num("paper.line", "Line strength", 100f, 40f, 160f, 5f, "%"),
            Tog("paper.margin", "Margin line on lined paper", false),
            Act("Open the paper gallery…", "paper") { val p = act.ed.focused; if (p != null) PaperUi.open(act.ed, p) else toast("Open a notebook first") })),
        Group("Backup & Safety", listOf(
            Tog("bk.remind", "Remind me to back up", true), Num("bk.days", "Remind after", 7f, 1f, 60f, 1f, " days"),
            Tog("bk.settings", "Include settings when restoring", true), Act("Open the backup center…", "save") { BackupUi.center() })),
        Group("Automatic backup", listOf(Tog("bk.auto", "Back up automatically", false),
            Pick("bk.every", "How often", "1", listOf("1" to "Every day", "3" to "Every 3 days", "7" to "Every week")),
            Num("bk.keep", "Keep the newest", 5f, 1f, 30f, 1f, " backups"),
            Act("Backup folder…", "folder") { AutoBackup.chooseFolder() }, Act("Back up now", "save") { AutoBackup.run(true) },
            Tog("bk.autoWarn", "Tell me if an automatic backup fails", true)),
            "Runs quietly when you leave the app, into a folder you choose (for example Documents or a Drive-synced folder). Older automatic copies are removed; your own backup files are never touched."),
        Group("Claude summaries", listOf(Act(AiUi.keyLabel(), "lock") { AiUi.keyDialog() }, Act("Check the key", "check") { AiUi.testKey() },
            Num("ai.fs", "Text size of saved summary pages", 16f, 10f, 30f, 1f, " pt")),
            "Hold a notebook › Summarize with Claude. The internet is used only while a summary is written. Your key stays on this tablet only and is never put in backups. Language, length, format and model are chosen in the summary sheet."),
        Group("Diagnostics", listOf(Act("Full performance report (send to Claude)…", "bolt") { Perf.report() }, Act("Pen writing test…", "target") { SettingsUi.penTest() })),
        Group("Look & Feel", listOf(
            Pick("ui.accentN", "App colour", "blue", listOf("blue" to "Blue (default)", "teal" to "Teal", "green" to "Green", "purple" to "Purple", "pink" to "Pink", "orange" to "Orange", "red" to "Red", "graphite" to "Graphite", "custom" to "Custom (colour below)")),
            Colr("ui.accent", "Custom colour", col(0xFF007AFF)), Tog("ui.black", "True black in dark mode (saves battery on OLED)", false),
            Pick("ui.font", "Interface font", "default", listOf("default" to "Device default", "medium" to "Clean sans", "casual" to "Rounded", "serif" to "Serif", "naskh" to "Arabic Naskh", "kufi" to "Arabic Kufi", "mono" to "Monospace")),
            Pick("ui.trans", "Screen transitions", "slide", listOf("none" to "None", "fade" to "Fade", "slide" to "Slide", "zoom" to "Zoom")),
            Num("ui.speed", "Transition speed", 100f, 50f, 200f, 10f, "%"),
            Tog("ui.splash", "Splash screen when the app opens", true), Num("ui.splashMs", "Minimum splash time", 350f, 0f, 1500f, 50f, " ms"),
            Tog("ui.zoomBtns", "Zoom buttons on the page", true), Tog("ui.pageInd", "Page counter on the page", true), Tog("ui.saved", "Show “Saved ✓” after saving", true))),
        Group("Library & Tools", listOf(
            Tog("lib.drag", "Drag notebooks onto folders", true),
            Tog("lib.qs", "Home search also looks inside notes", true), Num("lib.qsMax", "Max results inside notes", 8f, 3f, 30f, 1f),
            Tog("tb.sets", "Show “Tool sets” button in the toolbar", true),
            Pick("lib.sort", "Sort notebooks by", "modified", listOf("modified" to "Last edited", "created" to "Created", "name" to "Name", "manual" to "My own order (hold and drag)")),
            Tog("lib.reorder", "Hold a notebook and drag it to reorder", true),
            Tog("lib.thumbs", "Show page previews", true), Tog("lib.resume", "Reopen my notes when the app starts", true),
            Tog("lib.foldersFirst", "Folders first", true), Tog("lib.confirmDel", "Confirm before deleting", true),
            Num("lib.w", "Sidebar width", 300f, 260f, 420f, 10f, ""))),
        Group("Zoom", listOf(
            Tog("ui.zoomLockBtn", "Zoom lock button next to the zoom buttons", true), Tog("g.zLock", "Zoom lock (pinching and double-tap do not zoom)", false),
            Tog("g.zLockScroll", "Zoom lock also stops scrolling with fingers", false),
            Num("g.maxZoom", "Maximum zoom", 6f, 2f, 12f, 0.5f, "×"), Num("g.fling", "Scroll momentum", 100f, 30f, 200f, 10f, "%"),
            Tog("g.hscroll", "Lock sideways scrolling at fit-width", true))),
        Group("FreeNotes Import", listOf(
            Num("imp.fnThick", "Pen thickness (100% = as in FreeNotes)", 100f, 50f, 250f, 5f, "%"),
            Num("imp.fnHl", "Highlighter thickness (100% = as in FreeNotes)", 100f, 50f, 300f, 10f, "%"),
            Pick("imp.fnPics", "Pictures that are on no page", "shelf", listOf("shelf" to "Keep in Shelf", "skip" to "Skip")),
            Tog("imp.fnTrash", "Keep notes deleted in FreeNotes in Trash", false),
            Act("Restore imported notes from Trash", "undo") { var k = 0; for (m in Lib.notes) if (m.trashed != 0L && m.fid != null) { m.trashed = 0; k++ }; Lib.changed(); toast(if (k == 0) "Nothing to restore" else "$k restored") })),
        Group("Import & Backup", listOf(Act("Import FreeNotes backup (.freebackup)", "share") { act.pickImport() },
            Act("Bring the notebooks of the previous Larpful", "undo") { Migrate.run(true) })),
        Group("", listOf(Act("Reset all settings", "undo", true) { confirm("Reset all settings?", "Your notes are not affected.", "Reset") { P.reset(P.def.keys.toList()) } }), "Larpful · " + act.packageManager.getPackageInfo(act.packageName, 0).versionName)
    )

    private fun reg(gs: List<Group>) {
        for (g in gs) for (o in g.opts) when (o) {
            is Tog -> P.def[o.key] = o.d; is Num -> P.def[o.key] = o.d; is Pick -> P.def[o.key] = o.d; is Colr -> P.def[o.key] = o.d
            else -> {}
        }
    }
    fun register() {
        for (t in listOf("pen", "ball", "hl")) {
            reg(penGroups(t))
            for (i in 0..4) P.def["$t.c$i"] = toolColors[t]!![i]
            for (i in 0..2) P.def["$t.w$i"] = toolWidths[t]!![i]
        }
        for (g in listOf(eraser, lasso, ruler, tape, text, shelf, audio, links, search, paper, pages, split, backup, history, shot)) reg(g)
        P.def.putAll(Ai.DEFAULTS)
        // general() needs the activity for the version label, so register its defaults by hand-free reflection of the same list later
        P.def.putAll(mapOf("ui.theme" to "system", "ui.accent" to col(0xFF007AFF), "ui.black" to false, "ui.fullscreen" to true, "ui.anim" to true, "ui.wake" to true,
            "ui.zoomBtns" to true, "ui.zoomLockBtn" to true, "ui.pageInd" to true, "ui.saved" to true, "ui.font" to "default", "ui.trans" to "slide", "ui.speed" to 100f, "ui.splash" to true, "ui.splashMs" to 350f, "g.strike" to false, "g.xPage" to true,
            "tb.sets" to true, "tb.dot" to true, "pen.alpha" to 100f, "ball.alpha" to 100f, "tb.tabs" to true, "tb.tabsMax" to 8f, "lib.drag" to true, "toolsets" to "[]", "tabs" to "[]",
            "pen.pressure" to 100f, "pen.curve" to "linear", "pen.only" to true, "pen.palm" to true, "pen.palmMs" to 400f, "pen.dtap" to "eraser", "pen.barrel" to "hold",
            "g.two" to true, "g.three" to true, "g.dzoom" to true, "g.maxZoom" to 6f, "g.fling" to 100f, "g.hscroll" to true,
            "tb.size" to "compact", "tb.favOnly" to false, "tb.ctx" to true, "tb.undo" to true, "tb.lock" to false, "tb.swatches" to 5f,
            "lib.sort" to "modified", "lib.row" to "regular", "lib.thumbs" to true, "lib.resume" to true, "lib.foldersFirst" to true, "lib.qs" to true, "lib.qsMax" to 8f, "lib.reorder" to true, "lib.confirmDel" to true, "lib.w" to 300f,
            "doc.autosave" to 2f, "doc.resume" to true, "doc.gap" to 12f, "doc.addPage" to true, "imp.open" to true, "imp.fnTrash" to false, "imp.fnThick" to 100f, "imp.fnHl" to 100f, "imp.fnPics" to "shelf",
            "perf.sharp" to "balanced", "perf.unbuffered" to true, "perf.hud" to false,
            "doc.paper" to "lined", "doc.paperColor" to Color.WHITE, "lib.fo" to true, "lib.op" to "", "tool" to "pen", "pen.dtapKey" to 0,
            "tb.edge" to "left", "tb.pos" to 0.35f, "lib.open" to true))
        P.def.putAll(mapOf("pen.tipTap" to true, "pen.barrel2" to true, "pen.dbg" to false, "tk.keep" to true, "doc.auto3" to true, "ui.audioBtns" to true, "ui.splitBtn" to true, "ui.shareBtn" to true,
            "exp.scope" to "page", "sr.kind" to "all", "mig.done" to false, "mig.tries" to 0, "imp.rich" to true, "ui.panelOut" to true, "g.zLock" to false, "g.zLockScroll" to false, "g.zAnch" to true, "ui.pgMgr" to true, "ui.sheetOut" to true, "ui.dlgSel" to true, "ui.dlgClear" to true, "cb.auto" to true, "cb.collapse" to "draw", "cb.delay" to 4f, "cb.labels" to false, "cb.thick" to true,
            "cb.opac" to true, "cb.colors" to true, "cb.src" to "favorites", "tb.mark" to true, "tb.autoMin" to 0f, "cb.sz" to "85", "ui.accentN" to "blue", "doc.size" to "a4"))
        for (t in listOf("pen", "ball", "hl", "eraser", "shape", "tape", "ruler", "text", "image", "shot", "lasso")) P.def["tb.show.$t"] = true
        P.def.putAll(mapOf("pen.style" to "ink", "ball.style" to "bp", "hl.style" to "ink", "pen.press" to true, "ball.press" to true, "hl.press" to false,
            "shp.kind" to "line", "shp.width" to 3f * 595f / 794f, "shp.alpha" to 100f, "shp.color" to col(0xFF111111), "ls.mode" to "free",
            "tb.inst" to "", "tb.order" to "", "tb.favs" to "pen,hl,eraser,lasso", "tb.min" to false, "tb.presets" to true, "favs" to "[]"))
    }
}

/* ---------- settings renderer (iPhone-style grouped lists) ---------- */
object SettingsUi {
    fun fmt(v: Float, step: Float, unit: String): String {
        val s = if (step >= 1f) v.roundToInt().toString() else if (step >= 0.1f) String.format("%.1f", v) else String.format("%.2f", v)
        return s + unit
    }

    fun panel(c: Context, groups: List<Group>, onChange: ((String) -> Unit)? = null, narrow: Boolean = false, flush: Boolean = false): LinearLayout {
        val root = vbox(c); root.setPadding(dp(16), dp(2), dp(16), if (flush) 0 else dp(18))
        for (g in groups) {
            if (g.title.isNotEmpty()) root.addView(txt(c, g.title.uppercase(), 13f, Th.sub).apply { setPadding(dp(16), dp(20), dp(16), dp(7)) })
            else if (!flush) root.addView(space(c, 0, dp(18)))
            val box = vbox(c); box.background = rr(Th.card, dpf(14)); box.clipToOutline = true
            g.opts.forEachIndexed { i, o ->
                if (i > 0) box.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { leftMargin = dp(16) })
                box.addView(row(c, o, onChange, narrow))
            }
            root.addView(box, lp(MP, WC))
            if (g.footer.isNotEmpty()) root.addView(txt(c, g.footer, 13f, Th.sub).apply { setPadding(dp(16), dp(7), dp(16), 0); setLineSpacing(dpf(2), 1f) })
        }
        return root
    }

    private fun row(c: Context, o: Opt, onChange: ((String) -> Unit)?, narrow: Boolean): View {
        fun changed(k: String, v: Any) { P.set(k, v); onChange?.invoke(k) }
        when (o) {
            is Tog -> {
                val r = hbox(c); r.setPadding(dp(16), dp(7), dp(14), dp(7)); r.minimumHeight = dp(46)
                r.addView(txt(c, o.label, 17f), lp(0, WC, 1f).apply { rightMargin = dp(10) })
                r.addView(Sw(c, P.b(o.key)) { changed(o.key, it) }); return r
            }
            is Num -> {
                val r = hbox(c); r.setPadding(dp(16), dp(4), dp(8), dp(4)); r.minimumHeight = dp(46)
                r.addView(txt(c, o.label, 17f), lp(0, WC, 1f).apply { rightMargin = dp(8) })
                val vt = txt(c, fmt(P.f(o.key), o.step, o.unit), 15f, Th.sub); vt.gravity = Gravity.END
                val steps = ((o.max - o.min) / o.step).roundToInt()
                val sl = Slider(c, steps, ((P.f(o.key) - o.min) / o.step).roundToInt()) { p -> val nv = o.min + p * o.step; vt.text = fmt(nv, o.step, o.unit); changed(o.key, nv) }
                if (narrow) {   // label + value on one line, slider underneath
                    val v = vbox(c); v.setPadding(dp(16), dp(10), dp(6), dp(2))
                    val topRow = hbox(c); (r.getChildAt(0).parent as LinearLayout).removeAllViews()
                    topRow.addView(txt(c, o.label, 17f), lp(0, WC, 1f)); topRow.addView(vt, lp(WC, WC).apply { rightMargin = dp(10) })
                    v.addView(topRow, lp(MP, WC)); v.addView(sl, lp(MP, WC)); return v
                }
                r.addView(vt, lp(dp(64), WC)); r.addView(sl, lp(dp(190), WC)); return r
            }
            is Pick -> {
                val labels = o.choices.map { it.second }; val cur = o.choices.indexOfFirst { it.first == P.s(o.key) }.coerceAtLeast(0)
                if (o.choices.size <= 3 && labels.sumOf { it.length } <= (if (narrow) 16 else 24)) {
                    val r = hbox(c); r.setPadding(dp(16), dp(7), dp(12), dp(7)); r.minimumHeight = dp(46)
                    r.addView(txt(c, o.label, 17f), lp(0, WC, 1f).apply { rightMargin = dp(10) })
                    r.addView(segmented(c, labels, cur) { changed(o.key, o.choices[it].first) }); return r
                }
                // iPhone-style option row: current value on the right, tap to choose from a checklist
                val v = vbox(c)
                val head = hbox(c); head.setPadding(dp(16), 0, dp(10), 0); head.fx(0f)
                head.addView(txt(c, o.label, 17f), lp(0, WC, 1f).apply { rightMargin = dp(8) })
                val vt = txt(c, labels[cur], 17f, Th.sub).oneLine(); head.addView(vt)
                head.addView(ImageView(c).apply { setImageDrawable(Icon("updown", Th.sub)) }, lp(dp(16), dp(18)).apply { leftMargin = dp(4) })
                v.addView(head, lp(MP, dp(46)))
                val list = vbox(c); list.visibility = View.GONE
                fun fill() {
                    list.removeAllViews()
                    for ((val0, lab) in o.choices) {
                        list.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { leftMargin = dp(32) })
                        val r = hbox(c); r.setPadding(dp(32), 0, dp(16), 0); r.fx(0f)
                        r.addView(txt(c, lab, 17f), lp(0, WC, 1f))
                        if (val0 == P.s(o.key)) r.addView(ImageView(c).apply { setImageDrawable(Icon("check", Th.accent, 2.2f)) }, lp(dp(20), dp(20)))
                        r.setOnClickListener { changed(o.key, val0); vt.text = lab; fill() }
                        list.addView(r, lp(MP, dp(44)))
                    }
                }
                head.setOnClickListener { if (list.visibility == View.GONE) { fill(); list.visibility = View.VISIBLE } else list.visibility = View.GONE }
                v.addView(list); return v
            }
            is Colr -> {
                val r = hbox(c); r.setPadding(dp(16), 0, dp(14), 0); r.fx(0f)
                r.addView(txt(c, o.label, 17f), lp(0, WC, 1f))
                val sw = View(c); sw.background = rr(P.i(o.key), dpf(15), col(0x2E000000), 1); r.addView(sw, lp(dp(30), dp(30)))
                r.setOnClickListener { colorPicker(sw, P.i(o.key)) { nc -> sw.background = rr(nc, dpf(15), col(0x2E000000), 1); changed(o.key, nc) } }
                r.layoutParams = lp(MP, dp(46)); return r
            }
            is Act -> {
                val r = hbox(c); r.setPadding(dp(16), 0, dp(12), 0); r.fx(0f)
                r.addView(txt(c, o.label, 17f, if (o.danger) Th.danger else Th.text), lp(0, WC, 1f))
                r.addView(ImageView(c).apply { setImageDrawable(Icon(if (o.icon == "chev" || o.danger) o.icon else "chev", if (o.danger) Th.danger else Th.sub)) }, lp(dp(18), dp(18)))
                r.setOnClickListener { o.run(it) }
                r.minimumHeight = dp(46); return r
            }
            is Info -> return txt(c, o.label, 15f, Th.sub).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setLineSpacing(dpf(2), 1f) }
        }
        @Suppress("UNREACHABLE_CODE")
        return View(c)
    }

    fun keysOf(groups: List<Group>): List<String> = groups.flatMap { g -> g.opts.map { it.key } }.filter { it.isNotEmpty() }

    /** a settings sheet for one feature, with "reset this section" */
    fun sub(title: String, groups: List<Group>, onChange: ((String) -> Unit)? = null) {
        val body = vbox(act)
        fun build() {
            body.removeAllViews(); body.addView(panel(act, groups, onChange))
            val reset = txt(act, "Reset this section", 17f, Th.danger); reset.gravity = Gravity.CENTER; reset.setPadding(0, dp(4), 0, dp(22)); reset.fx()
            reset.setOnClickListener { P.reset(keysOf(groups)); build() }
            body.addView(reset, lp(MP, WC))
        }
        build(); sheet(title, body, 520)
    }

    /** Settings: sections fold like in the original; a section is only built when it is opened, so the sheet appears at once. */
    fun main() {
        val c = act; val root = vbox(c); root.setPadding(0, dp(4), 0, dp(18)); val groups = Reg.general()
        fun openSet(): MutableSet<String> = P.s("set.open").split(',').filter { it.isNotEmpty() }.toMutableSet()
        groups.forEachIndexed { i, g ->
            if (g.title.isEmpty() || g.title == "Import & Backup") { root.addView(panel(c, listOf(g))); return@forEachIndexed }
            val head = hbox(c); head.setPadding(dp(32), dp(18), dp(28), dp(7)); head.fx(0f)
            head.addView(txt(c, g.title.uppercase(), 13f, Th.sub), lp(0, WC, 1f))
            val chev = ImageView(c); chev.setImageDrawable(Icon("chev", Th.sub, 2f)); head.addView(chev, lp(dp(14), dp(14)))
            val slot = android.widget.FrameLayout(c); var built = false
            fun show(on: Boolean) { chev.rotation = if (on) 90f else 0f; slot.visibility = if (on) View.VISIBLE else View.GONE
                if (on && !built) { built = true; val body = vbox(c); body.addView(panel(c, listOf(Group("", g.opts, g.footer)), null, false, true))
                    if (i >= 5) { val reset = txt(c, "Reset this section to defaults", 15f, Th.danger); reset.setPadding(dp(32), dp(10), dp(32), dp(4)); reset.fx(); reset.setOnClickListener { P.reset(keysOf(listOf(g))) }; body.addView(reset) }
                    slot.addView(body) } }
            head.setOnClickListener { val s = openSet(); val k = i.toString(); val on = slot.visibility != View.VISIBLE; if (on) s.add(k) else s.remove(k); P.set("set.open", s.joinToString(","), false); show(on) }
            root.addView(head, lp(MP, WC)); root.addView(slot, lp(MP, WC)); show(i.toString() in openSet())
        }
        sheet("Settings", root, 600)
    }
    /** a tool's own settings ("Pen settings", "Eraser settings"…), as a sheet */
    fun tool(t: String, onChange: ((String) -> Unit)? = null) {
        val b = Tools.base(t)
        val (title, groups) = when (b) { "pen", "ball", "hl" -> "Pen & Highlighter settings" to Reg.penGroups(t); "eraser" -> "Eraser settings" to Reg.eraser; "lasso" -> "Lasso & Shelf settings" to Reg.lasso
            "ruler" -> "Ruler settings" to Reg.ruler; "tape" -> "Study tape settings" to Reg.tape; "text" -> "Text settings" to Reg.text; "shot" -> "Screenshot settings" to Reg.shot; else -> "Toolbar settings" to Reg.general().filter { it.title == "Smart Toolbar" || it.title == "Tools" } }
        val body = vbox(act)
        fun build() {
            body.removeAllViews(); body.addView(panel(act, groups, onChange))
            val reset = txt(act, "Reset these settings to defaults", 17f, Th.danger); reset.gravity = Gravity.CENTER; reset.setPadding(0, dp(4), 0, dp(22)); reset.fx()
            reset.setOnClickListener { P.reset(keysOf(groups)); build() }
            body.addView(reset, lp(MP, WC))
        }
        build(); sheet(title, body, 540)
    }

    /** live pencil diagnostics + double-tap learning */
    fun penTest() {
        val c = act; val body = vbox(c); body.setPadding(dp(16), dp(8), dp(16), dp(16))
        val info = txt(c, "Write in the box. Then double-tap the pencil body twice to teach Larpful the gesture.", 14.5f, Th.sub); info.setLineSpacing(dpf(3), 1f)
        body.addView(info)
        val pad = PenTestView(c); body.addView(pad, lp(MP, dp(260)).apply { topMargin = dp(12) })
        val stat = txt(c, "Waiting for the pencil…", 14f); stat.setPadding(0, dp(12), 0, 0); stat.setLineSpacing(dpf(4), 1f); body.addView(stat)
        val key = txt(c, "Double-tap key: " + (if (P.i("pen.dtapKey") != 0) "code ${P.i("pen.dtapKey")}" else "not learned yet (automatic detection is on)"), 14f, Th.accent)
        key.setPadding(0, dp(10), 0, 0); body.addView(key)
        val forget = txt(c, "Forget learned gesture", 14.5f, Th.danger); forget.setPadding(0, dp(14), 0, dp(6)); forget.fx()
        forget.setOnClickListener { P.set("pen.dtapKey", 0); key.text = "Double-tap key: not learned yet (automatic detection is on)" }
        body.addView(forget)
        pad.onStat = { stat.text = it }
        act.keyProbe = { code, src ->
            P.set("pen.dtapKey", code); key.text = "Double-tap key: code $code  ✓ learned"; pad.flash(); true
        }
        sheet("Pencil test", body, 520, onDismiss = { act.keyProbe = null })
    }
}

class PenTestView(c: Context) : View(c) {
    var onStat: ((String) -> Unit)? = null
    private val path = android.graphics.Path(); private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var n = 0; private var t0 = 0L; private var hz = 0f; private var flashT = 0L
    init { background = rr(Th.card, dpf(12), Th.sep, dp(1)) }
    fun flash() { flashT = System.currentTimeMillis(); invalidate() }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        val tool = when (e.getToolType(0)) { android.view.MotionEvent.TOOL_TYPE_STYLUS -> "Pencil"; android.view.MotionEvent.TOOL_TYPE_ERASER -> "Pencil eraser"; android.view.MotionEvent.TOOL_TYPE_FINGER -> "Finger"; else -> "Other" }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { path.moveTo(e.x, e.y); n = 0; t0 = e.eventTime }
            android.view.MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) path.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                path.lineTo(e.x, e.y); n += e.historySize + 1
                val dt = e.eventTime - t0; if (dt > 150) hz = n * 1000f / dt
            }
        }
        onStat?.invoke("$tool · pressure ${String.format("%.2f", e.pressure)} · tilt ${Math.toDegrees(e.getAxisValue(android.view.MotionEvent.AXIS_TILT).toDouble()).toInt()}° · buttons ${e.buttonState}\nSampling ${hz.toInt()} points/s · touch size ${String.format("%.0f", e.touchMajor)} px")
        invalidate(); return true
    }
    override fun onDraw(cv: android.graphics.Canvas) {
        if (System.currentTimeMillis() - flashT < 500) { p.style = android.graphics.Paint.Style.FILL; p.color = mix(Th.card, Th.green, 0.35f); cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dpf(12), dpf(12), p); postInvalidateDelayed(520) }
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dpf(2); p.color = Th.text; p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
        cv.drawPath(path, p)
    }
}
