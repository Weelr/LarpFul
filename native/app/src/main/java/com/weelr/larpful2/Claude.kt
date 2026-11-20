package com.weelr.larpful2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/* ================= notebook summaries with the Claude API =================
   The internet is used only here, only while a summary is being written.
   The API key lives in this tablet's private settings: never in backups, never in GitHub. */
object Ai {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val MAX_PAGES = 100
    val MODELS = listOf("claude-haiku-5-5" to "Haiku", "claude-sonnet-5-5" to "Sonnet", "claude-opus-5-5" to "Opus")
    val DEFAULTS = mapOf("ai.lang" to "ar", "ai.len" to "medium", "ai.style" to "bullets", "ai.model" to "claude-sonnet-5-5", "ai.res" to "normal", "ai.extra" to "", "ai.fs" to 16f)
    fun key(): String = P.s("ai.key").trim()
    fun modelName(id: String): String = MODELS.firstOrNull { it.first == id }?.second ?: id

    class Job { @Volatile var cancelled = false; @Volatile var conn: HttpURLConnection? = null
        fun cancel() { cancelled = true; try { conn?.disconnect() } catch (e: Throwable) { } } }

    /** background thread: one page as a JPEG (handwriting, PDF and pictures together), base64 */
    fun pageJpeg(n: Note, pg: Page, longSide: Int): String {
        Store.loadPage(n, pg)
        val s = longSide / max(pg.w, pg.h)
        val bmp = Bitmap.createBitmap(max(1, (pg.w * s).toInt()), max(1, (pg.h * s).toInt()), Bitmap.Config.ARGB_8888)
        Render.page(bmp, n, pg, s, 0f, 0f, pg.items, null, InkPainter(), true)
        val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo); bmp.recycle()
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    fun system(): String {
        val lang = when (P.s("ai.lang")) {
            "en" -> "Write the summary in English."
            "same" -> "Write the summary in the same language the notes are written in."
            else -> "Write the explanations in Arabic, but keep every technical term, name and symbol in English exactly as it appears in the notes (do not translate the terms into Arabic)."
        }
        val len = when (P.s("ai.len")) { "short" -> "Keep it short: only the most important ideas, about a page."; "long" -> "Be detailed: cover every idea, definition, equation and example on the pages."; else -> "Medium length: all main ideas and definitions, without filler." }
        val style = when (P.s("ai.style")) {
            "para" -> "Write short clear paragraphs, one per topic, each starting with a heading line."
            "exam" -> "Organise it for exam revision with these sections: Definitions (each term and a one-line explanation), Key equations (each equation with what every symbol means and its unit), Key ideas, and Likely exam questions (a few questions with short answers)."
            else -> "Use a heading line for each topic and bullet points under it."
        }
        return "You summarise pages of a student's notebook (handwriting, slides and PDF pages) so they can study from the summary. " +
            "Read the handwriting carefully; if a word is unreadable, skip it rather than guess. Do not invent content that is not on the pages. " +
            "$lang $len $style " +
            "Formatting: plain text only, no Markdown (no #, no **, no tables). Put each heading on its own line. Start bullet points with \"• \". " +
            "Write equations in plain text, e.g. V = I × R. Start directly with the summary, with no introduction or closing remarks."
    }

    /**
     * Streams a summary of [idx] pages of [n]. Runs on its own thread; callbacks come on the UI thread.
     * onText gets each new piece of text, onDone(error or null, truncated).
     */
    fun summarize(n: Note, idx: List<Int>, job: Job, onStage: (String) -> Unit, onText: (String) -> Unit, onDone: (String?, Boolean) -> Unit) {
        val model = P.s("ai.model"); val side = if (P.s("ai.res") == "high") 1568 else 1150
        val maxTok = when (P.s("ai.len")) { "short" -> 2500; "long" -> 12000; else -> 6000 }
        val extra = P.s("ai.extra").trim()
        Thread {
            var err: String? = null; var cut = false
            try {
                val content = JSONArray()
                for ((k, i) in idx.withIndex()) {
                    if (job.cancelled) return@Thread
                    Store.main { onStage("Preparing page ${k + 1} of ${idx.size}…") }
                    val pg = n.pages[i]
                    content.put(JSONObject().put("type", "text").put("text", "Page ${i + 1}" + (pg.label?.let { " — $it" } ?: "")))
                    content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", pageJpeg(n, pg, side))))
                }
                val title = n.meta?.title ?: "Notebook"
                content.put(JSONObject().put("type", "text").put("text", "Summarise these ${idx.size} page(s) of the notebook “$title”." + (if (extra.isNotEmpty()) "\nExtra instructions from me: $extra" else "")))
                val body = JSONObject().put("model", model).put("max_tokens", maxTok).put("stream", true).put("system", system())
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                if (job.cancelled) return@Thread
                Store.main { onStage("Sending to Claude ${modelName(model)}…") }
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection; job.conn = c
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
                c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-api-key", key()); c.setRequestProperty("anthropic-version", "2023-06-01")
                c.setChunkedStreamingMode(64 * 1024)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val msg = try { JSONObject(c.errorStream.bufferedReader().readText()).optJSONObject("error")?.optString("message") } catch (e: Throwable) { null }
                    err = when (code) { 401 -> "The API key is not right. Check it in Settings › Claude."; 403 -> "This API key is not allowed to do that. " + (msg ?: "")
                        429 -> "Too many requests right now (rate limit). Wait a minute and try again."; 529, 503 -> "Claude is busy right now. Try again in a moment."
                        413 -> "Too many pages at once. Choose fewer pages or Normal image detail."
                        else -> "Error $code: " + (msg ?: "the request failed") }
                } else {
                    Store.main { onStage("Writing…") }
                    c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (!job.cancelled) {
                            val line = r.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val o = try { JSONObject(line.substring(5).trim()) } catch (e: Throwable) { continue }
                            when (o.optString("type")) {
                                "content_block_delta" -> { val d = o.optJSONObject("delta"); if (d != null && d.optString("type") == "text_delta") { val t = d.optString("text"); if (t.isNotEmpty()) Store.main { if (!job.cancelled) onText(t) } } }
                                "message_delta" -> if (o.optJSONObject("delta")?.optString("stop_reason") == "max_tokens") cut = true
                                "error" -> err = o.optJSONObject("error")?.optString("message") ?: "Claude stopped with an error"
                            }
                        }
                    }
                }
            } catch (e: java.net.UnknownHostException) { err = "No internet connection." }
            catch (e: java.net.SocketTimeoutException) { err = "The connection timed out. Try again." }
            catch (e: OutOfMemoryError) { err = "Not enough memory for that many pages. Choose fewer pages." }
            catch (e: Throwable) { if (!job.cancelled) err = e.message ?: e.javaClass.simpleName }
            finally { try { job.conn?.disconnect() } catch (e: Throwable) { } }
            if (!job.cancelled) { val e2 = err; val c2 = cut; Store.main { onDone(e2, c2) } }
        }.start()
    }

    /** the summary written as text pages (paginated to the page size of new pages) */
    fun toPages(title: String, text: String): List<Page> {
        val out = ArrayList<Page>()
        val proto = Store.newPage(); val m = 44f; val w = proto.w - 2 * m
        val dark = lum(proto.paperColor) < 0.5f; val ink = if (dark) Color.WHITE else col(0xFF111111)
        val fs = P.f("ai.fs").coerceIn(10f, 30f)
        val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.textSize = fs
        fun height(s: String): Float = StaticLayout.Builder.obtain(s, 0, s.length, tp, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build().height.toFloat()
        val paras = text.trim().split('\n').map { it.trimEnd() }
        var cur = StringBuilder(); var first = true
        fun top(): Float = if (first) m + fs * 2.6f else m
        fun flush() {
            if (cur.isBlank() && !first) return
            val p = Store.newPage(); p.dirty = true
            val items = ArrayList<Item>()
            if (first) items.add(TextItem(title, m, m, w, fs * 1.5f, ink, true))
            items.add(TextItem(cur.toString().trim('\n'), m, top(), w, fs, ink, false))
            p.items = items; out.add(p); first = false; cur = StringBuilder()
        }
        for (para in paras) {
            val trial = if (cur.isEmpty()) para else cur.toString() + "\n" + para
            if (cur.isNotEmpty() && top() + height(trial) > proto.h - m) { flush(); cur.append(para) }
            else { cur.setLength(0); cur.append(trial) }
        }
        if (cur.isNotBlank() || out.isEmpty()) flush()
        return out
    }
}

object AiUi {
    private fun lastFile(id: String) = File(Store.noteDir(id), "summary.txt")

    /* ---------- API key (main Settings) ---------- */
    fun keyLabel(): String = if (Ai.key().isEmpty()) "Claude API key… (not set)" else "Claude API key… (••••" + Ai.key().takeLast(4) + ")"
    fun keyDialog(then: (() -> Unit)? = null) {
        lateinit var e: EditText
        val save = { hideKeyboard(e); P.set("ai.key", e.text.toString().trim()); toast(if (Ai.key().isEmpty()) "Key removed" else "Key saved"); if (Ai.key().isNotEmpty()) then?.invoke() }
        lateinit var layer: Layer
        val (f, ed) = iosField(Ai.key(), "sk-ant-…", false, true) { if (layer.shown) { layer.dismiss(); save() } }
        e = ed; e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val wrap = vbox(act); wrap.addView(f, lp(MP, WC))
        val show = hbox(act); show.setPadding(0, dp(10), 0, 0)
        show.addView(txt(act, "Show the key", 13f, Th.text), lp(0, WC, 1f))
        show.addView(Sw(act, false) { on -> e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD); e.setSelection(e.text.length) })
        wrap.addView(show, lp(MP, WC))
        layer = iosAlert("Claude API key", "Paste your key from console.anthropic.com. It stays on this tablet only — it is not put in backups.", wrap,
            listOf(AB("Save", bold = true) { save() }, AB("Remove key", danger = true) { hideKeyboard(e); P.set("ai.key", ""); toast("Key removed") }, AB("Cancel", cancel = true) { hideKeyboard(e) }))
        showKeyboard(e)
    }
    fun testKey() {
        if (Ai.key().isEmpty()) { keyDialog { testKey() }; return }
        toast("Checking the key…")
        Thread {
            val msg = try {
                val c = URL("https://api.anthropic.com/v1/models?limit=1").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000; c.setRequestProperty("x-api-key", Ai.key()); c.setRequestProperty("anthropic-version", "2023-06-01")
                val code = c.responseCode; c.disconnect()
                when (code) { in 200..299 -> "The key works ✓"; 401 -> "The key is not right"; else -> "Answer $code from Claude" }
            } catch (e: java.net.UnknownHostException) { "No internet connection" } catch (e: Throwable) { "Could not check: " + (e.message ?: "") }
            toast(msg)
        }.start()
    }

    /* ---------- step 1: options + pick the pages from their previews ---------- */
    fun open(m: NoteMeta) {
        if (Ai.key().isEmpty()) { keyDialog { open(m) }; return }
        if (m.locked && m.id !in act.unlocked) { act.askPin { act.unlocked.add(m.id); open(m) }; return }
        act.ed.flush()
        Store.io {
            val n = Store.openNote(m.id); if (n == null) { toast("Could not open that notebook"); return@io }
            val last = lastFile(m.id).let { if (it.exists()) try { it.readText() } catch (e: Exception) { null } else null }
            Store.main { pick(m, n, last) }
        }
    }

    private fun optRow(c: Context, box: LinearLayout, label: String, key: String, choices: List<Pair<String, String>>) {
        box.addView(txt(c, label.uppercase(), 12.5f, Th.sub).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val sel = choices.indexOfFirst { it.first == P.s(key) }.coerceAtLeast(0)
        box.addView(segmented(c, choices.map { it.second }, sel) { i -> P.set(key, choices[i].first) }, lp(MP, WC))
    }

    private fun pick(m: NoteMeta, n: Note, last: String?) {
        val c = act; lateinit var l: Layer
        val sel = java.util.TreeSet<Int>(); for (i in 0 until minOf(n.pages.size, Ai.MAX_PAGES)) sel.add(i)
        val wide = act.root.width >= dp(820)
        val root = if (wide) hbox(c) else vbox(c)
        root.gravity = Gravity.TOP

        // options
        val opts = vbox(c); opts.setPadding(dp(16), dp(4), dp(16), dp(16))
        optRow(c, opts, "Language", "ai.lang", listOf("ar" to "Arabic", "en" to "English", "same" to "As notes"))
        opts.addView(txt(c, "Arabic keeps the terms in English", 12f, Th.sub).apply { setPadding(dp(4), dp(4), 0, 0) })
        optRow(c, opts, "Length", "ai.len", listOf("short" to "Short", "medium" to "Medium", "long" to "Detailed"))
        optRow(c, opts, "Format", "ai.style", listOf("bullets" to "Bullets", "para" to "Paragraphs", "exam" to "Exam prep"))
        optRow(c, opts, "Model", "ai.model", Ai.MODELS)
        opts.addView(txt(c, "Haiku is fastest and cheapest, Opus reads messy handwriting best", 12f, Th.sub).apply { setPadding(dp(4), dp(4), 0, 0) })
        optRow(c, opts, "Image detail", "ai.res", listOf("normal" to "Normal", "high" to "High"))
        opts.addView(txt(c, "EXTRA INSTRUCTIONS", 12.5f, Th.sub).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        val ex = EditText(c); ex.setText(P.s("ai.extra")); ex.hint = "e.g. focus on the equations"; ex.textSize = 14f; ex.background = rr(Th.card, dpf(10)); ex.setPadding(dp(12), dp(10), dp(12), dp(10)); ex.setTextColor(Th.text); ex.setHintTextColor(Th.sub)
        ex.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        ex.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { P.set("ai.extra", s?.toString() ?: "", false) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
        })
        opts.addView(ex, lp(MP, WC))
        if (last != null) {
            val lb = hbox(c); lb.background = rr(Th.card, dpf(12)); lb.setPadding(dp(14), 0, dp(14), 0); lb.fx(12f)
            lb.addView(ImageView(c).apply { setImageDrawable(Icon("history", Th.accent)) }, lp(dp(22), dp(22)).apply { rightMargin = dp(12) })
            lb.addView(txt(c, "Open the last summary", 15f), lp(0, WC, 1f))
            lb.setOnClickListener { l.dismiss(); result(m, n, emptyList(), last) }
            opts.addView(lb, lp(MP, dp(48)).apply { topMargin = dp(18) })
        }
        val optScroll = ScrollView(c); optScroll.isVerticalScrollBarEnabled = false; optScroll.addView(opts, FrameLayout.LayoutParams(MP, WC))

        // page previews
        val right = vbox(c)
        val grid = GridView(c); grid.numColumns = if (wide) 4 else 5; grid.verticalSpacing = dp(12); grid.horizontalSpacing = dp(10); grid.setPadding(dp(12), dp(10), dp(16), dp(10)); grid.clipToPadding = false; grid.selector = android.graphics.drawable.ColorDrawable(0)
        val go = txt(c, "Summarize", 15f, Color.WHITE, true); val cnt = txt(c, "", 13f, Th.sub); val all = txt(c, "", 14f, Th.text)
        fun upd() { cnt.text = "${sel.size} of ${n.pages.size} pages"; go.text = if (sel.isEmpty()) "Summarize" else "Summarize ${sel.size} page" + (if (sel.size == 1) "" else "s"); go.alpha = if (sel.isEmpty()) 0.4f else 1f; all.text = if (sel.size == minOf(n.pages.size, Ai.MAX_PAGES)) "Clear" else "Select all" }
        val ad = object : BaseAdapter() {
            override fun getCount() = n.pages.size
            override fun getItem(k: Int) = n.pages[k]
            override fun getItemId(k: Int) = k.toLong()
            override fun getView(k: Int, cv: View?, parent: ViewGroup?): View {
                val pg = n.pages[k]; val on = k in sel; val cell = FrameLayout(c); val iv = RatioImage(c, pg.h / pg.w); iv.scaleType = ImageView.ScaleType.FIT_XY
                iv.background = rr(if (pg.src != null) -1 else pg.paperColor, dpf(6), if (on) Th.accent else Th.sep, if (on) dp(3) else 1); iv.setPadding(dp(3), dp(3), dp(3), dp(3))
                Thumbs.load(n.id, pg.id) { b -> if (b != null) iv.setImageBitmap(b) }
                iv.alpha = if (on) 1f else 0.55f
                cell.addView(iv, flp(MP, WC))
                cell.addView(txt(c, if (on) "✓" else "", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rr(if (on) Th.accent else col(0x73000000), dpf(12), Color.WHITE, dp(2)) }, flp(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { topMargin = dp(6); leftMargin = dp(6) })
                cell.addView(txt(c, (pg.label ?: (k + 1).toString()).take(14), 11f, Th.text, true).apply { background = rr(Th.glass, dpf(100)); setPadding(dp(7), dp(2), dp(7), dp(2)) }, flp(WC, WC, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(6); bottomMargin = dp(6) })
                cell.setOnClickListener {
                    if (!sel.remove(k)) { if (sel.size >= Ai.MAX_PAGES) { toast("Up to ${Ai.MAX_PAGES} pages at once"); return@setOnClickListener }; sel.add(k) }
                    notifyDataSetChanged(); upd() }
                return cell
            }
        }
        grid.adapter = ad; right.addView(grid, lp(MP, 0, 1f))
        val bar = hbox(c); bar.setPadding(dp(16), dp(8), dp(16), dp(12))
        all.background = rr(Th.fill, dpf(100)); all.setPadding(dp(14), dp(8), dp(14), dp(8)); all.fx(100f)
        all.setOnClickListener { val cap = minOf(n.pages.size, Ai.MAX_PAGES); if (sel.size == cap) sel.clear() else { sel.clear(); for (i in 0 until cap) sel.add(i) }; ad.notifyDataSetChanged(); upd() }
        go.background = rr(Th.accent, dpf(100)); go.setPadding(dp(18), dp(8), dp(18), dp(8)); go.fx(100f)
        go.setOnClickListener { if (sel.isEmpty()) return@setOnClickListener; val idx = sel.toList(); l.dismiss(); result(m, n, idx, null) }
        bar.addView(all); bar.addView(cnt, lp(0, WC, 1f).apply { leftMargin = dp(12) }); bar.addView(go)
        right.addView(bar, lp(MP, WC))
        upd()

        val h = (act.root.height * 0.74f).toInt()
        if (wide) { root.addView(optScroll, lp(dp(330), h)); root.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(1, h)); root.addView(right, lp(0, h, 1f)) }
        else { root.addView(optScroll, lp(MP, (h * 0.45f).toInt())); root.addView(right, lp(MP, (h * 0.55f).toInt())) }
        l = sheet("Summarize · " + m.title.take(28), root, if (wide) 980 else 640)
    }

    /* ---------- step 2: the summary as it is written, with copy / save ---------- */
    fun result(m: NoteMeta, n: Note, idx: List<Int>, ready: String?) {
        val c = act; val body = vbox(c); lateinit var l: Layer
        val job = Ai.Job(); var done = ready != null
        val stage = hbox(c); stage.setPadding(dp(18), dp(12), dp(18), dp(4))
        val spin = ProgressBar(c, null, android.R.attr.progressBarStyleSmall)
        val st = txt(c, if (ready != null) "Last summary" else "Starting…", 13.5f, Th.sub)
        stage.addView(spin, lp(dp(18), dp(18)).apply { rightMargin = dp(10) }); stage.addView(st, lp(0, WC, 1f))
        if (ready != null) spin.visibility = View.GONE
        body.addView(stage, lp(MP, WC))
        val tv = TextView(c); tv.textSize = 16.5f; tv.setTextColor(Th.text); tv.setLineSpacing(dpf(3), 1f); tv.setTextIsSelectable(true)
        tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG; tv.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
        tv.typeface = android.graphics.Typeface.create(Th.family, android.graphics.Typeface.NORMAL)
        tv.setPadding(dp(22), dp(12), dp(22), dp(18)); tv.background = rr(Th.card, dpf(14))
        if (ready != null) tv.text = ready
        body.addView(tv, lp(MP, WC).apply { leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(10) })

        val bar = hbox(c); bar.setPadding(dp(14), dp(4), dp(14), dp(16))
        fun pill(icon: String, label: String, accent: Boolean = false, on: () -> Unit): LinearLayout {
            val b = hbox(c); b.background = rr(if (accent) Th.accent else Th.fill, dpf(100)); b.setPadding(dp(14), dp(8), dp(16), dp(8)); b.fx(100f)
            b.addView(ImageView(c).apply { setImageDrawable(Icon(icon, if (accent) Color.WHITE else Th.accent)) }, lp(dp(18), dp(18)).apply { rightMargin = dp(7) })
            b.addView(txt(c, label, 14f, if (accent) Color.WHITE else Th.text, true))
            b.setOnClickListener { if (done) on() else toast("Wait until the summary is finished") }
            bar.addView(b, lp(WC, WC).apply { rightMargin = dp(8) }); return b
        }
        pill("copy", "Copy") { val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("Summary", tv.text)); toast("Copied") }
        pill("addpg", "Add as pages to this notebook", true) { l.dismiss(); savePages(m, tv.text.toString(), false) }
        pill("noteplus", "New notebook") { l.dismiss(); savePages(m, tv.text.toString(), true) }
        pill("share", "Share") { val i = android.content.Intent(android.content.Intent.ACTION_SEND); i.type = "text/plain"; i.putExtra(android.content.Intent.EXTRA_TEXT, tv.text.toString()); try { act.startActivity(android.content.Intent.createChooser(i, "Share summary")) } catch (e: Exception) { } }
        val again = txt(c, "Summarize again", 14f, Th.accent, true).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); fx(10f) }
        again.setOnClickListener { job.cancel(); l.dismiss(); open(m) }
        bar.addView(space(c, weight = 1f)); bar.addView(again)
        body.addView(bar, lp(MP, WC))

        l = sheet("Summary · " + m.title.take(28), body, 760, onDismiss = { job.cancel() })
        if (ready != null) return

        val acc = StringBuilder()
        Ai.summarize(n, idx, job, { s -> st.text = s }, { t -> acc.append(t); tv.append(t) }) { err, cut ->
            spin.visibility = View.GONE
            if (err != null) {
                st.text = "Could not summarize"; st.setTextColor(Th.danger)
                if (acc.isEmpty()) { tv.text = err; done = false } else { tv.append("\n\n— $err"); done = true }
                return@summarize
            }
            done = true
            st.text = "${idx.size} page" + (if (idx.size == 1) "" else "s") + " · Claude ${Ai.modelName(P.s("ai.model"))}" + (if (cut) " · stopped at the length limit — choose Detailed for more" else "")
            val text = acc.toString(); val id = m.id
            Store.io { try { Store.atomic(lastFile(id), text) } catch (e: Exception) { } }
        }
    }

    /* ---------- save ---------- */
    fun savePages(m: NoteMeta, text: String, newNotebook: Boolean) {
        if (text.isBlank()) return
        val pages = Ai.toPages(if (newNotebook) "Summary — " + m.title else "Summary", text)
        if (newNotebook) {
            val n = Store.createNote("Summary — " + m.title, m.folder, pages[0])
            for (p in pages.drop(1)) n.pages.add(p)
            for (p in n.pages) p.dirty = true
            n.metaDirty = true; Store.save(n); toast("Saved as “Summary — ${m.title.take(24)}”")
            act.ed.open(n.id); return
        }
        val pane = act.ed.panes.firstOrNull { it.note?.id == m.id }
        if (pane != null) {
            val n = pane.note!!
            for (p in pages) act.ed.addPage(pane, n.pages.size - 1, p)
            act.lib.sideChanged(); toast("Added ${pages.size} page" + (if (pages.size == 1) "" else "s") + " at the end"); return
        }
        Store.io {
            val n = Store.openNote(m.id) ?: run { toast("Could not open that notebook"); return@io }
            Store.main { n.pages.addAll(pages); n.metaDirty = true; Store.save(n) { Store.main { Lib.changed() } }; toast("Added ${pages.size} page" + (if (pages.size == 1) "" else "s") + " at the end of “${m.title.take(24)}”") }
        }
    }
}
