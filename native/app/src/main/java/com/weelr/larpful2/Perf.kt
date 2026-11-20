package com.weelr.larpful2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** One-tap report: startup time, library size, how fast the current page draws and saves. Copy it and send it. */
object Perf {
    @Suppress("DEPRECATION")
    fun report() {
        val c = act; val body = vbox(c); body.setPadding(dp(18), dp(8), dp(18), dp(18))
        val t = txt(c, "Measuring…", 13.5f, Th.text); t.typeface = Typeface.MONOSPACE; t.setLineSpacing(dpf(4), 1f); t.setTextIsSelectable(true); body.addView(t)
        val copy = txt(c, "Copy report", 15.5f, Th.accent, true); copy.gravity = Gravity.CENTER; copy.background = rr(Th.card, dpf(12)); copy.setPadding(0, dp(13), 0, dp(13)); copy.fx(12f)
        copy.setOnClickListener { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Larpful report", t.text)); toast("Copied") }
        body.addView(copy, lp(MP, WC).apply { topMargin = dp(14) })
        sheet("Performance report", body, 540)
        val pane = act.ed.focused; val n = pane?.note; val i = pane?.view?.curPage() ?: 0
        val pg = n?.pages?.getOrNull(i); val items = if (pg != null && pg.loaded) ArrayList(pg.items) else null
        val dm = c.resources.displayMetrics; val hz = c.windowManager.defaultDisplay.refreshRate
        val ver = c.packageManager.getPackageInfo(c.packageName, 0).versionName
        Store.work {
            val sb = StringBuilder()
            sb.append("Larpful  ").append(ver).append('\n')
            sb.append("Device     ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("  (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("Screen     ").append(dm.widthPixels).append('×').append(dm.heightPixels).append("  density ").append(dm.density).append("  ").append(hz.toInt()).append(" Hz\n")
            sb.append("Startup    ").append(c.startupMs).append(" ms to first screen\n")
            val rt = Runtime.getRuntime()
            sb.append("Memory     ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append(" / ").append(rt.maxMemory() / 1048576).append(" MB\n")
            var bytes = 0L; var files = 0; for (f in Store.root.walkTopDown()) if (f.isFile) { bytes += f.length(); files++ }
            sb.append("Library    ").append(Lib.notes.size).append(" notes, ").append(Lib.folders.size).append(" folders, ").append(Lib.notes.sumOf { it.pages }).append(" pages, ").append(bytes / 1048576).append(" MB in ").append(files).append(" files\n")
            if (n != null && pg != null && items != null) {
                var pts = 0; for (it in items) if (it is Stroke) pts += it.n
                sb.append("Page ").append(i + 1).append("     ").append(items.size).append(" objects, ").append(pts).append(" ink points").append(if (pg.src != null) ", PDF background" else "").append('\n')
                try {
                    val s = 2000f / pg.w; val bmp = Bitmap.createBitmap(2000, (pg.h * s).toInt().coerceIn(100, 4000), Bitmap.Config.ARGB_8888)
                    val t0 = System.nanoTime(); Render.page(bmp, n, pg, s, 0f, 0f, items, null, InkPainter()); val ms = (System.nanoTime() - t0) / 1e6; bmp.recycle()
                    sb.append("Full draw  ").append(String.format("%.1f", ms)).append(" ms at 2000 px wide\n")
                } catch (e: Throwable) { sb.append("Full draw  failed: ").append(e.javaClass.simpleName).append('\n') }
                val bo = ByteArrayOutputStream(); var t0 = System.nanoTime(); DataOutputStream(bo).use { InkIO.write(it, items) }; val w = (System.nanoTime() - t0) / 1e6
                t0 = System.nanoTime(); DataInputStream(ByteArrayInputStream(bo.toByteArray())).use { InkIO.read(it) }; val r = (System.nanoTime() - t0) / 1e6
                sb.append("Ink save   ").append(String.format("%.2f", w)).append(" ms  load ").append(String.format("%.2f", r)).append(" ms  (").append(bo.size() / 1024).append(" KB)\n")
            } else sb.append("Open a note before running the report to measure drawing speed.\n")
            try {
                val f = File(c.cacheDir, "perf.tmp"); val buf = ByteArray(1 shl 20); val t0 = System.nanoTime(); f.outputStream().use { o -> repeat(8) { o.write(buf) }; o.fd.sync() }; val ms = (System.nanoTime() - t0) / 1e6; f.delete()
                sb.append("Storage    ").append(String.format("%.0f", 8000.0 / ms)).append(" MB/s write\n")
            } catch (e: Throwable) { }
            sb.append("Settings   sharpness=").append(P.s("perf.sharp")).append(" smoothing=").append(P.i("pen.smooth")).append("% unbuffered=").append(P.b("perf.unbuffered")).append(" palm=").append(P.b("pen.palm"))
            Store.main { t.text = sb.toString() }
        }
    }
}
