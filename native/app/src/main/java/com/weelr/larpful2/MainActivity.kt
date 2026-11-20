package com.weelr.larpful2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

class MainActivity : Activity() {
    lateinit var root: FrameLayout
    lateinit var main: LinearLayout
    val layers = ArrayList<Layer>()
    lateinit var ed: Editor
    lateinit var lib: Library
    val unlocked = HashSet<String>()
    var keyProbe: ((Int, Int) -> Boolean)? = null
    private var pickCb: ((List<Uri>) -> Unit)? = null
    private var createCb: ((Uri) -> Unit)? = null
    private var permCb: ((Boolean) -> Unit)? = null
    private var lastPenKey = 0L
    var startupMs = 0L

    val hasEd: Boolean get() = ::ed.isInitialized
    /** invisible sheet behind the sidebar when it is open over a note: a tap on it closes the sidebar */
    private val catcher: View by lazy { View(this).apply { setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN && lib.over) lib.toggle(); true } } }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        act = this; DENS = resources.displayMetrics.density
        P.init(this); Store.init(filesDir); Th.load(); Lib.load(); Shelf.load()
        if (!P.b("tk.keep")) P.reset(listOf("pen", "ball", "hl").flatMap { t -> listOf("$t.width", "$t.color", "$t.alpha") } + listOf("shp.width", "shp.color", "shp.alpha", "er.size"))
        Tools.cur = P.s("tool").let { if (it in Tools.ALL && it != "ruler" && it != "image") it else "pen" }; Tools.prev = Tools.cur
        root = FrameLayout(this); setContentView(root)
        main = LinearLayout(this); root.addView(main, flp(MP, MP))
        lib = Library(); ed = Editor()
        layoutMain(); applyWindow()
        P.listeners.add { k -> onPref(k) }
        ed.restoreSession()
        root.post { startupMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() }
        // the original's reminder: a short note when the last backup is old (at most once a day)
        root.postDelayed({ val now = System.currentTimeMillis()
            if (P.b("bk.remind") && Lib.notes.any { it.trashed == 0L } && now - P.l("bk.nag") > 86400000L) {
                if (Lib.lastBackup == 0L && P.l("bk.ask") == 0L) P.set("bk.ask", now, false)
                val since = if (Lib.lastBackup == 0L) P.l("bk.ask") else Lib.lastBackup
                if (now - since > P.f("bk.days") * 86400000L) { P.set("bk.nag", now, false); toast("Time to back up your notebooks (Settings → Backup & Safety)") }
            } }, 6000)
        if (P.b("ui.splash")) {
            val sp = FrameLayout(this); sp.isClickable = true; sp.elevation = dpf(300)
            sp.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(col(0xFF18BEFF), col(0xFF6C2CF6)))
            val box = vbox(this); box.gravity = android.view.Gravity.CENTER
            box.addView(ImageView(this).apply { setImageResource(R.drawable.ic_fg) }, lp(dp(190), dp(190)))
            box.addView(txt(this, "Larpful", 26f, android.graphics.Color.WHITE, true))
            sp.addView(box, flp(WC, WC, android.view.Gravity.CENTER)); root.addView(sp, flp(MP, MP))
            root.postDelayed({ sp.animate().alpha(0f).setDuration(320).withEndAction { root.removeView(sp) }.start() }, P.f("ui.splashMs").toLong())
        }
        handleIntent(intent)
        if (Migrate.needed()) root.postDelayed({ Migrate.run(false) }, 700)   // installed over the first Larpful: bring its notebooks in
    }

    fun layoutMain() {
        main.removeAllViews(); root.setBackgroundColor(Th.bg); main.setBackgroundColor(Th.bg)
        (lib.view.parent as? android.view.ViewGroup)?.removeView(lib.view); (ed.root.parent as? android.view.ViewGroup)?.removeView(ed.root)
        val inNote = ed.panes.isNotEmpty()
        if (!inNote) lib.over = false
        if (!inNote && lib.docked) {
            lib.view.elevation = 0f
            main.addView(lib.view, lp(dp(P.f("lib.w")), MP))
            main.addView(View(this).apply { setBackgroundColor(Th.sep) }, lp(1, MP))
        }
        main.addView(ed.root, lp(0, MP, 1f))
        // while a note is open the sidebar is an overlay opened from the top bar (as in the original), so the page keeps the full width
        (catcher.parent as? android.view.ViewGroup)?.removeView(catcher)
        if (inNote && lib.over) {
            lib.view.elevation = dpf(16); lib.view.isClickable = true
            if (P.b("ui.panelOut")) { catcher.elevation = dpf(15); root.addView(catcher, 1, flp(MP, MP)) }
            root.addView(lib.view, if (catcher.parent != null) 2 else 1, flp(dp(P.f("lib.w")), MP))
        }
        lib.homeLogo?.visibility = if (lib.docked) View.GONE else View.VISIBLE
        ed.bar.place(false)
    }

    @Suppress("DEPRECATION")
    fun applyWindow() {
        if (P.b("ui.wake")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Th.bg; window.navigationBarColor = Th.bg
        var f = 0
        if (P.b("ui.fullscreen")) f = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        else if (!Th.dark) f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) applyWindow() }

    private fun onPref(k: String) {
        if (k == "bk.auto" && P.b("bk.auto") && P.s("bk.tree").isEmpty()) AutoBackup.chooseFolder()
        when {
            k == "*" || k.startsWith("ui.") -> { Th.load(); applyWindow(); layoutMain(); lib.applyTheme(); ed.applyTheme(); ed.bar.rebuild(); ed.refreshPanes() }
            k == "lib.w" -> layoutMain()
            k.startsWith("lib.") -> lib.refresh()
            k.startsWith("bk.") -> lib.refresh()
            k == "g.zLock" -> ed.refreshPanes()
            k == "doc.gap" || k == "doc.addPage" || k.startsWith("paper.") || k.startsWith("lk.") || k == "hl.under" || k == "perf.sharp" -> ed.refreshPanes()
            k.startsWith("split.") || k.startsWith("er.") || k.startsWith("ru.") || k == "perf.hud" || k.startsWith("ls.") -> ed.invalidatePanes()
        }
    }

    /** the yellow “L” of the first Larpful: opens Settings */
    fun logoButton(): View {
        val v = txt(this, "L", 18f, android.graphics.Color.WHITE, true); v.gravity = android.view.Gravity.CENTER
        v.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(col(0xFFFFD60A), col(0xFFF5A300))).apply { cornerRadius = dpf(9) }
        v.elevation = dpf(1.5f); v.fx(9f); v.setOnClickListener { SettingsUi.main() }
        val wrap = FrameLayout(this); wrap.addView(v, flp(dp(34), dp(34), android.view.Gravity.CENTER)); wrap.layoutParams = lp(dp(42), dp(44)); return wrap
    }

    /* ---------- lifecycle ---------- */
    override fun onPause() { super.onPause(); if (::ed.isInitialized) { ed.flush(); ed.audio.onPause() }; Store.io { Lib.saveNow() } }
    override fun onStop() { super.onStop(); AutoBackup.maybe() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val u: Uri? = when (i.action) { Intent.ACTION_VIEW -> i.data; Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM); else -> null }
        if (u != null) { i.action = Intent.ACTION_MAIN; root.post { Io.importUris(listOf(u), null) } }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (layers.isNotEmpty()) { layers.last().dismiss(); return }
        if (ed.panes.isNotEmpty()) { ed.goHome(); return }
        if (lib.browser.selecting != null) { lib.selectMode(false); return }
        if (lib.browser.canUp) { lib.browser.up(); return }
        moveTaskToBack(true)
    }

    /* ---------- pencil double-tap + keyboard ---------- */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val system = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME
        if (!system && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val fromPen = (e.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
            val odd = code > KeyEvent.getMaxKeyCode() || code == KeyEvent.KEYCODE_UNKNOWN
            if (P.b("pen.dbg") && (fromPen || odd)) toast("Pen key code $code")
            val probe = keyProbe
            if (probe != null && (fromPen || odd || e.device?.isVirtual == false && e.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC)) { if (probe(code, e.source)) return true }
            val learned = P.i("pen.dtapKey")
            if ((learned != 0 && code == learned) || (learned == 0 && (fromPen || odd))) {
                val now = SystemClock.uptimeMillis(); if (now - lastPenKey > 250) { lastPenKey = now; penDoubleTap() }
                return true
            }
            if (e.isCtrlPressed && currentFocus !is android.widget.EditText) {
                when (code) {
                    KeyEvent.KEYCODE_Z -> { if (e.isShiftPressed) ed.focused?.view?.doRedo() else ed.focused?.view?.doUndo(); return true }
                    KeyEvent.KEYCODE_Y -> { ed.focused?.view?.doRedo(); return true }
                }
            }
        }
        if (!system && e.action == KeyEvent.ACTION_UP) { val learned = P.i("pen.dtapKey"); if (learned != 0 && code == learned) return true }
        return super.dispatchKeyEvent(e)
    }
    private fun penDoubleTap() {
        if (!P.b("pen.barrel2")) return
        when (P.s("pen.dtap")) {
            "eraser" -> Tools.toggleEraser()
            "last" -> Tools.set(Tools.prev)
            "lasso" -> Tools.set(if (Tools.cur == "lasso") (if (Tools.prev == "lasso") "pen" else Tools.prev) else "lasso")
            "undo" -> ed.focused?.view?.doUndo()
        }
    }

    /* ---------- lock code ---------- */
    fun askPin(ok: () -> Unit) {
        if (Lib.pin.isEmpty()) prompt("Create a lock code", "", "Digits", password = true) { a -> if (a.length < 3) toast("Use at least 3 digits") else prompt("Repeat the lock code", "", "", password = true) { b2 -> if (a == b2) { Lib.pin = Lib.hash(a); Lib.changed(false); ok() } else toast("The codes did not match") } }
        else prompt("Enter lock code", "", "", password = true) { a -> if (Lib.hash(a) == Lib.pin) ok() else toast("Wrong code") }
    }

    /* ---------- pickers ---------- */
    @Suppress("DEPRECATION")
    fun pick(mimes: Array<String>, multiple: Boolean, cb: (List<Uri>) -> Unit) {
        pickCb = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.type = if (mimes.size == 1) mimes[0] else "*/*"
        if (mimes.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, 11) } catch (e: Exception) { toast("No file picker available") }
    }
    @Suppress("DEPRECATION")
    fun createDoc(name: String, mime: String, cb: (Uri) -> Unit) {
        createCb = cb
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.type = mime; i.putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, 12) } catch (e: Exception) { toast("No file picker available") }
    }
    private var treeCb: ((Uri) -> Unit)? = null
    @Suppress("DEPRECATION")
    fun pickTree(cb: (Uri) -> Unit) {
        treeCb = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, 13) } catch (e: Exception) { toast("No folder picker available") }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, res: Int, data: Intent?) {
        super.onActivityResult(rq, res, data)
        if (res != RESULT_OK || data == null) return
        if (rq == 11) {
            val out = ArrayList<Uri>(); val cd = data.clipData
            if (cd != null) for (k in 0 until cd.itemCount) out.add(cd.getItemAt(k).uri) else data.data?.let { out.add(it) }
            val cb = pickCb; pickCb = null; if (out.isNotEmpty()) cb?.invoke(out)
        } else if (rq == 12) { val cb = createCb; createCb = null; data.data?.let { cb?.invoke(it) } }
        else if (rq == 13) { val u = data.data ?: return; try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
            val cb = treeCb; treeCb = null; cb?.invoke(u) }
    }
    fun share(f: File, mime: String) {
        val i = Intent(Intent.ACTION_SEND); i.type = mime; i.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(f)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri(f.name, ShareProvider.uriFor(f))
        try { startActivity(Intent.createChooser(i, "Share")) } catch (e: Exception) { toast("Nothing can receive this file") }
    }
    fun withMic(cb: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) cb(true)
        else { permCb = cb; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21) }
    }
    override fun onRequestPermissionsResult(rq: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rq, perms, res)
        if (rq == 21) { val cb = permCb; permCb = null; cb?.invoke(res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) }
    }

    fun pickFiles(intoNote: String?) { pick(arrayOf("*/*"), true) { uris -> Io.importUris(uris, intoNote) } }
    fun pickImport() { pick(arrayOf("*/*"), false) { uris -> FreeNotes.importUri(uris[0]) } }
    fun pickImage() {
        val p = ed.focused ?: return; val n = p.note ?: return
        pick(arrayOf("image/*"), false) { uris ->
            Store.work {
                val bmp = decode(uris[0], 2200) ?: run { toast("Could not read the image"); return@work }
                val name = "img_" + newId() + (if (bmp.hasAlpha()) ".png" else ".jpg")
                File(n.dir, name).outputStream().use { bmp.compress(if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
                val w = bmp.width * 0.5f; val h = bmp.height * 0.5f
                Store.main { ed.insertImage(name, w, h) }
            }
        }
    }
    fun pickCover(id: String, folder: Boolean) {
        pick(arrayOf("image/*"), false) { uris ->
            Store.work {
                val src = decode(uris[0], 1200) ?: run { toast("Could not read the image"); return@work }
                val tw = 480; val th = 640; val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888); val c = Canvas(out)
                val s = maxOf(tw / src.width.toFloat(), th / src.height.toFloat()); val sw = (tw / s).toInt(); val sh = (th / s).toInt()
                val l = (src.width - sw) / 2; val t = (src.height - sh) / 2
                c.drawBitmap(src, Rect(l, t, l + sw, t + sh), Rect(0, 0, tw, th), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                val f = if (folder) Store.folderCover(id) else Store.coverFile(id); f.parentFile?.mkdirs()
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                Store.main { Thumbs.dropCover(id); if (folder) Lib.folder(id)?.cover = true else Lib.note(id)?.cover = true; Thumbs.bump(); Lib.changed() }
            }
        }
    }
    /** decodes an image Uri, downsampled so the longest side is about maxSide */
    fun decode(u: Uri, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var ss = 1; while (o.outWidth / (ss * 2) >= maxSide || o.outHeight / (ss * 2) >= maxSide) ss *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = ss
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Throwable) { null }

    fun onShelfChanged() { ShelfUi.refresh() }
}
