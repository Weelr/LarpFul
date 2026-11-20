package com.weelr.larpful2

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Word / PowerPoint -> PDF that keeps the document's own look (fonts, colours, tables, pictures, slide layout).
 *
 * The file is laid out by the same two renderers the first Larpful used (docx-preview and pptx-preview), inside a
 * hidden system web view that never touches the network, and every page is then painted into the PDF.
 * This is the only place the app uses a web view; if anything goes wrong the caller falls back to the plain converter.
 */
object OfficeWeb {
    private const val HOST = "convert.larpful.invalid"
    private const val CSS_W = 1200          // wide enough for landscape A4 / Letter
    private const val SHARP = 2f            // picture pixels per CSS pixel
    private var whole = false

    fun available(): Boolean = try { act.assets.open("conv/docx-preview.min.js").close(); true } catch (e: Exception) { false }

    /** call off the main thread; true when [out] holds the finished PDF */
    fun convert(f: File, kind: String, out: File): Boolean {
        if (!P.b("imp.rich") || !available()) return false
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        Store.main { try { start(f, kind, out) { r -> ok.set(r); latch.countDown() } } catch (t: Throwable) { latch.countDown() } }
        latch.await(240, TimeUnit.SECONDS)
        return ok.get() && out.length() > 300
    }

    private fun none() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(f: File, kind: String, out: File, done: (Boolean) -> Unit) {
        if (!whole) { whole = true; try { WebView.enableSlowWholeDocumentDraw() } catch (t: Throwable) { } }
        val wv = WebView(act); val wpx = (CSS_W * DENS).toInt()
        var finished = false
        fun finish(r: Boolean) { if (finished) return; finished = true; try { (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (t: Throwable) { }; done(r) }
        val s = wv.settings
        s.javaScriptEnabled = true; s.allowFileAccess = false; s.allowContentAccess = false; s.useWideViewPort = false; s.loadWithOverviewMode = false; s.textZoom = 100; s.blockNetworkLoads = false
        wv.setBackgroundColor(Color.WHITE); wv.isVerticalScrollBarEnabled = false; wv.isHorizontalScrollBarEnabled = false
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun done(json: String) { Store.main { if (!finished) try { lay(wv, JSONObject(json), kind, out) { finish(it) } } catch (t: Throwable) { finish(false) } } }
            @JavascriptInterface fun fail(msg: String) { android.util.Log.w("Larpful", "office: $msg"); Store.main { finish(false) } }
        }, "LF")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url; val path = u.path ?: ""
                try {
                    if (u.host == HOST) return when {
                        path == "/page.html" -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(page(kind).toByteArray()))
                        path == "/in" -> WebResourceResponse("application/octet-stream", null, FileInputStream(f))
                        path.endsWith(".js") -> WebResourceResponse("application/javascript", "utf-8", act.assets.open("conv" + path))
                        else -> none()
                    }
                } catch (t: Throwable) { }
                return none()   // nothing is ever loaded from the internet
            }
        }
        act.root.addView(wv, 0, FrameLayout.LayoutParams(wpx, dp(900)))   // behind the whole app
        wv.loadUrl("https://$HOST/page.html")
        act.root.postDelayed({ finish(false) }, 200000)
    }

    private class Slice(val x: Float, val y: Float, val w: Float, val h: Float, val pageW: Float, val pageH: Float, val offY: Float)

    /** JS has finished: stretch the view over the whole document, then paint it page by page */
    private fun lay(wv: WebView, o: JSONObject, kind: String, out: File, fin: (Boolean) -> Unit) {
        val arr = o.getJSONArray("pages"); val slices = ArrayList<Slice>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i); val x = p.getDouble("x").toFloat(); val y = p.getDouble("y").toFloat(); val w = p.getDouble("w").toFloat(); val h = p.getDouble("h").toFloat()
            if (w < 20f || h < 20f) continue
            if (kind == "pptx") { slices.add(Slice(x, y, w, h, w, h, 0f)); continue }
            val ph = p.optDouble("ph", 0.0).toFloat().let { if (it < 100f) w * 1.4142f else it }; val pt = p.optDouble("pt", 0.0).toFloat(); val pb = p.optDouble("pb", 0.0).toFloat()
            if (h <= ph * 1.02f) { slices.add(Slice(x, y, w, h, w, maxOf(h, ph), 0f)); continue }
            // a section longer than one sheet: cut it between paragraphs / table rows, never through a line of text
            val cj = p.optJSONArray("cuts"); val n = (cj?.length() ?: 0) / 2; val tops = FloatArray(n) { cj!!.getDouble(it * 2).toFloat() }; val bots = FloatArray(n) { cj!!.getDouble(it * 2 + 1).toFloat() }
            val cap = (ph - pt - pb).coerceAtLeast(200f); val end = h - pb; var pos = pt; var guard = 0
            while (pos < end - 1f && guard++ < 2000) {
                val target = pos + cap; var cut = -1f
                if (end <= target) cut = end
                else { for (k in 0 until n) if (tops[k] >= pos - 0.5f && bots[k] <= target && bots[k] > cut) cut = bots[k]; if (cut <= pos + 20f) cut = target }
                slices.add(Slice(x, y + pos, w, cut - pos, w, ph, pt))
                var next = cut; var best = Float.MAX_VALUE
                for (k in 0 until n) if (tops[k] >= cut - 0.5f && tops[k] < best) best = tops[k]
                if (best != Float.MAX_VALUE && best - cut < 80f) next = best
                pos = maxOf(next, pos + 1f)
            }
        }
        if (slices.isEmpty()) { fin(false); return }
        val hpx = ceil(o.optDouble("H", 1000.0) * DENS).toInt().coerceIn(200, 3_000_000)
        wv.layoutParams = FrameLayout.LayoutParams(wv.width, hpx)
        val doc = PdfDocument(); val hasText = o.optInt("T", 1) > 0
        fun step(i: Int) {
            try {
                if (i >= slices.size || i >= 600) { out.outputStream().use { doc.writeTo(it) }; doc.close(); fin(true); return }
                val s = slices[i]; val bw = (s.pageW * SHARP).roundToInt().coerceIn(50, 4000); val bh = (s.pageH * SHARP).roundToInt().coerceIn(50, 6000)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); c.drawColor(Color.WHITE)
                c.save(); c.clipRect(0f, s.offY * SHARP, bw.toFloat(), (s.offY + s.h) * SHARP)
                val k = SHARP / DENS; c.scale(k, k); c.translate(-s.x * DENS, -(s.y - s.offY) * DENS)
                wv.draw(c); c.restore()
                if (i == 0 && hasText && blank(bmp)) { bmp.recycle(); doc.close(); fin(false); return }   // the web view painted nothing: use the plain converter instead
                val pw = (s.pageW * 0.75f).roundToInt().coerceAtLeast(10); val ph = (s.pageH * 0.75f).roundToInt().coerceAtLeast(10)
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                pg.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)); doc.finishPage(pg); bmp.recycle()
                wv.post { step(i + 1) }   // one page per frame keeps the app responsive
            } catch (t: Throwable) { android.util.Log.w("Larpful", "office paint", t); try { doc.close() } catch (e: Throwable) { }; fin(false) }
        }
        wv.postDelayed({ step(0) }, 600)
    }
    private fun blank(b: Bitmap): Boolean {
        val px = IntArray(b.width); var y = 0
        while (y < b.height) { b.getPixels(px, 0, b.width, 0, y, b.width, 1); for (v in px) if ((v and 0xFFFFFF) != 0xFFFFFF) return false; y += 7 }
        return true
    }

    private fun page(kind: String): String {
        val head = "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;background:#fff}#h{background:#fff}" +
            "section.docx{box-shadow:none!important;margin:0 0 24px 0!important;background:#fff!important}" +
            ".pptx-preview-wrapper{height:auto!important;max-height:none!important;overflow:visible!important;background:#fff!important;margin:0!important}" +
            "</style>"
        val wait = "if(document.fonts&&document.fonts.ready)await document.fonts.ready;await Promise.all([...document.images].map(i=>i.complete?0:new Promise(r=>{i.onload=i.onerror=r})));"
        val tail = "LF.done(JSON.stringify({pages:out,H:Math.max(document.documentElement.scrollHeight,document.body.scrollHeight),T:(h.innerText||'').trim().length}))}catch(e){LF.fail(String(e&&e.stack||e))}})()</script></body></html>"
        return if (kind == "docx") head + "<script src=\"/jszip.min.js\"></script><script src=\"/docx-preview.min.js\"></script></head><body><div id=\"h\"></div><script>(async()=>{try{" +
            "const h=document.getElementById('h');const ab=await (await fetch('/in')).arrayBuffer();" +
            "await docx.renderAsync(ab,h,null,{breakPages:true,inWrapper:false,ignoreLastRenderedPageBreak:false,experimental:true,useBase64URL:true});" + wait +
            "await new Promise(r=>setTimeout(r,250));const out=[];" +
            "for(const s of h.querySelectorAll('section.docx')){const r=s.getBoundingClientRect(),cs=getComputedStyle(s),top=r.top+scrollY,left=r.left+scrollX;const cuts=[];" +
            "s.querySelectorAll('p,tr,img,li,h1,h2,h3,h4,h5,h6').forEach(e=>{if(e.tagName!=='TR'&&e.closest('td'))return;const b=e.getBoundingClientRect();if(b.height>0)cuts.push(Math.round((b.top+scrollY-top)*10)/10,Math.round((b.bottom+scrollY-top)*10)/10)});" +
            "out.push({x:left,y:top,w:r.width,h:r.height,ph:parseFloat(cs.minHeight)||0,pt:parseFloat(cs.paddingTop)||0,pb:parseFloat(cs.paddingBottom)||0,cuts:cuts})}" + tail
        else head + "<script src=\"/pptx-preview.umd.js\"></script></head><body><div id=\"h\"></div><script>(async()=>{try{" +
            "const h=document.getElementById('h');const ab=await (await fetch('/in')).arrayBuffer();" +
            "const pv=pptxPreview.init(h,{width:960,height:540});await pv.preview(ab);await new Promise(r=>setTimeout(r,500));" + wait +
            "const w=h.querySelector('.pptx-preview-wrapper')||h.firstElementChild;const els=w?[...w.children]:[];const out=[];" +
            "for(const e of els){const r=e.getBoundingClientRect();if(r.width<50||r.height<50)continue;out.push({x:r.left+scrollX,y:r.top+scrollY,w:r.width,h:r.height})}" + tail
    }
}
