package com.weelr.larpful2

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Moves the notebooks of the first Larpful (the HTML app, package com.larpful.notes) into this app when it is installed over it.
 *
 * The old app kept everything in the browser database of its web view. That database can only be read by a web view
 * opened on the same address, so one hidden page is loaded once, writes every record out in the old backup format,
 * and the normal backup reader (Larpful1) turns it into notebooks. The old data is never deleted, and notebooks that
 * were already moved are skipped, so this is safe to run again from Settings.
 */
object Migrate {
    private fun idbDir(): File = File(act.applicationInfo.dataDir, "app_webview/Default/IndexedDB")
    /** the first Larpful left its data in this app's private folder */
    fun oldDataExists(): Boolean = try { idbDir().listFiles()?.any { it.name.contains("localhost") } == true } catch (e: Exception) { false }
    fun needed(): Boolean = !P.b("mig.done") && P.i("mig.tries") < 3 && oldDataExists()

    private const val PAGE = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>(async()=>{try{" +
        "const open=()=>new Promise((ok,no)=>{const r=indexedDB.open('larpful');let fresh=false;r.onupgradeneeded=()=>{fresh=true;try{r.transaction.abort()}catch(e){}};r.onsuccess=()=>fresh?no(new Error('nodb')):ok(r.result);r.onerror=()=>no(fresh?new Error('nodb'):r.error);r.onblocked=()=>no(new Error('blocked'))});" +
        "let d;try{d=await open()}catch(e){if(String(e.message)==='nodb'){LF.none();return}throw e}" +
        "if(!d.objectStoreNames.contains('kv')){LF.none();return}" +
        "const rq=q=>new Promise((ok,no)=>{q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});" +
        "const keys=(await rq(d.transaction('kv').objectStore('kv').getAllKeys())).filter(k=>typeof k==='string'&&k!=='idx');" +
        "if(!keys.some(k=>k.startsWith('nb:'))){LF.none();return}" +
        "const r2=x=>Math.round(x*100)/100,r3=x=>Math.round(x*1000)/1000;" +
        "const fix=o=>{if(o&&Array.isArray(o.pages))for(const p of o.pages)if(p&&Array.isArray(p.strokes))for(const s of p.strokes)if(s&&s.pts&&ArrayBuffer.isView(s.pts)){const f=s.pts,n=f.length/3|0,P=new Array(n);for(let i=0,j=0;i<n;i++,j+=3){const pr=f[j+2];P[i]=pr===pr?{x:r2(f[j]),y:r2(f[j+1]),p:r3(pr)}:{x:r2(f[j]),y:r2(f[j+1])}}s.pts=P}};" +
        "const plain=(k,v)=>{if(!v||typeof v!=='object'||!(k.startsWith('nb:')||k.startsWith('hist:')))return v;Array.isArray(v)?v.forEach(fix):fix(v);return v};" +
        "const CH=262144;const put=s=>{let i=0;while(i<s.length){let c=Math.min(s.length,i+CH);const u=s.charCodeAt(c-1);if(c<s.length&&u>=0xD800&&u<=0xDBFF)c++;LF.put(s.slice(i,c));i=c}};" +
        "let st={};try{st=JSON.parse(localStorage.getItem('lf')||'{}')||{}}catch(e){}" +
        "LF.begin(keys.length);put('{\"app\":\"larpful\",\"v\":2,\"t\":'+Date.now()+',\"st\":'+JSON.stringify(st)+'}\\n');" +
        "for(let i=0;i<keys.length;i++){const k=keys[i];if(k.startsWith('hist:'))continue;const v=await rq(d.transaction('kv').objectStore('kv').get(k));if(v===undefined)continue;put(JSON.stringify([k,plain(k,v)])+'\\n');if(i%4===0)LF.prog(i+1)}" +
        "LF.end()}catch(e){LF.fail(String(e&&e.message||e))}})()</script></body></html>"

    private var running = false

    /** [manual]: started from Settings (always reports what happened) */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(manual: Boolean) {
        if (running) return
        if (!oldDataExists()) { if (manual) toast("No data of the previous Larpful was found on this tablet"); P.set("mig.done", true, false); return }
        running = true; if (!manual) P.set("mig.tries", P.i("mig.tries") + 1, false)
        val c = act
        // a small card that cannot be dismissed while the notebooks are being moved
        val box = vbox(c); box.setPadding(dp(26), dp(22), dp(26), dp(22)); box.gravity = Gravity.CENTER_HORIZONTAL; box.background = rr(Th.card, dpf(20), Th.hair, 1); box.elevation = dpf(20)
        box.addView(txt(c, "Moving your notebooks", 19f, Th.text, true))
        val msg: TextView = txt(c, "Reading the notebooks of the previous Larpful…", 14.5f, Th.sub); msg.gravity = Gravity.CENTER; msg.setPadding(0, dp(10), 0, dp(4)); msg.setLineSpacing(dpf(3), 1f); box.addView(msg, lp(dp(330), WC))
        box.addView(txt(c, "Nothing is deleted from the old data.", 12.5f, Th.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        val scrim = FrameLayout(c); scrim.setBackgroundColor(col(0x59000000)); scrim.isClickable = true; scrim.elevation = dpf(250); scrim.addView(box, flp(WC, WC, Gravity.CENTER)); act.root.addView(scrim, flp(MP, MP))

        val out = File(act.cacheDir, "previous-larpful.larpful"); out.delete()
        var w: BufferedWriter? = null; var total = 0; var wv: WebView? = null; var closed = false
        val schemes = arrayListOf("https", "http")
        fun close() { if (closed) return; closed = true; running = false; try { w?.close() } catch (e: Exception) { }; try { val v = wv; if (v != null) { (v.parent as? ViewGroup)?.removeView(v); v.destroy() } } catch (e: Throwable) { }; act.root.removeView(scrim) }
        fun failed(why: String) { close(); out.delete(); android.util.Log.w("Larpful", "migrate: $why")
            confirm("The notebooks could not be moved automatically", "They are still safe on this tablet. You can try again from Settings › Import & Backup, or restore a backup file made in the previous Larpful.\n\n($why)", "Try again") { run(true) } }
        lateinit var load: () -> Unit
        val bridge = object {
            @JavascriptInterface fun begin(n: Int) { total = n; try { w = out.bufferedWriter(Charsets.UTF_8) } catch (e: Exception) { } }
            @JavascriptInterface fun put(s: String) { try { w?.write(s) } catch (e: Exception) { } }
            @JavascriptInterface fun prog(i: Int) { Store.main { if (!closed) msg.text = "Reading the notebooks of the previous Larpful… " + (if (total > 0) (i * 100 / total).coerceIn(0, 100).toString() + "%" else "") } }
            @JavascriptInterface fun none() { Store.main { if (closed) return@main; if (schemes.isNotEmpty()) load() else { close(); P.set("mig.done", true, false); if (manual) toast("The previous Larpful has no notebooks to move") } } }
            @JavascriptInterface fun fail(m: String) { Store.main { if (!closed) { if (schemes.isNotEmpty() && w == null) load() else failed(m) } } }
            @JavascriptInterface fun end() {
                try { w?.close() } catch (e: Exception) { }; w = null
                Store.main {
                    if (closed) return@main
                    msg.text = "Building your notebooks…"
                    Store.work {
                        var err: String? = null; val before = Lib.notes.size
                        try { Larpful1.importFile(out) } catch (t: Throwable) { err = t.message ?: t.javaClass.simpleName }
                        out.delete()
                        Store.main { if (err != null) failed(err!!) else { close(); P.set("mig.done", true, false); val n = Lib.notes.size - before
                            toast(if (n > 0) "$n notebook" + (if (n == 1) "" else "s") + " moved from the previous Larpful" else "Your notebooks are already here") } }
                    }
                }
            }
        }
        load = {
            val scheme = schemes.removeAt(0)
            try { val old = wv; if (old != null) { (old.parent as? ViewGroup)?.removeView(old); old.destroy() } } catch (e: Throwable) { }
            val v = WebView(c); wv = v
            v.settings.javaScriptEnabled = true; v.settings.domStorageEnabled = true; v.settings.databaseEnabled = true; v.settings.allowFileAccess = false
            v.addJavascriptInterface(bridge, "LF")
            v.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val p = req.url.path ?: ""
                    return if (req.url.host == "localhost" && p == "/__larpful_move.html") WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(PAGE.toByteArray()))
                    else WebResourceResponse("text/plain", "utf-8", 404, "Not found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            act.root.addView(v, 0, FrameLayout.LayoutParams(dp(10), dp(10)))
            v.loadUrl("$scheme://localhost/__larpful_move.html")
        }
        try { load() } catch (t: Throwable) { failed(t.message ?: "web view not available") }
        act.root.postDelayed({ if (!closed && w == null && total == 0) failed("no answer from the old data") }, 60000)
    }
}
