package com.weelr.larpful2

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Audio recording tied to the ink: every stroke carries its time, so tapping ink jumps the recording to that moment. */
class AudioCtl(val ed: Editor) {
    var recording = false
    private var visible = false
    private var rec: MediaRecorder? = null
    private var recNote: Note? = null; private var recFile = ""; private var recStart = 0L
    private var player: MediaPlayer? = null
    private var cur: Rec? = null; private var curNote: Note? = null
    private var timeLabel: TextView? = null; private var seek: Slider? = null; private var playBtn: View? = null
    private var dragging = false

    private val tick = object : Runnable { override fun run() { update(); if (visible) ed.root.postDelayed(this, 400) } }

    fun wantsTap(): Boolean = visible && P.b("au.seek") && (ed.focused?.note?.recs?.isNotEmpty() == true)
    fun toggleBar() { visible = !visible; if (!visible) { stopPlay(); dim(null, 0) }; build(); ed.bar.place(false) }

    private fun fmt(ms: Long): String { val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }
    /** the microphone in the top bar: starts recording at once, a second tap stops (as in the original) */
    fun micTap() { if (!visible) { visible = true; build(); ed.bar.place(false) }; if (recording) stopRec() else startRec() }
    /** the headphones in the top bar: every recording of this note */
    fun recordings() {
        val n = ed.focused?.note ?: return; val c = act; val body = vbox(c); body.setPadding(dp(16), dp(10), dp(16), dp(18))
        lateinit var l: Layer
        fun fill() {
            body.removeAllViews()
            if (n.recs.isEmpty()) { body.addView(txt(c, "No recordings yet. Tap the mic, then write — every stroke is stamped to the audio.", 15f, Th.sub).apply { setPadding(dp(8), dp(18), dp(8), dp(18)); setLineSpacing(dpf(3), 1f) }); return }
            val f = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()); val box = vbox(c); box.background = rr(Th.card, dpf(14)); box.clipToOutline = true
            n.recs.asReversed().forEachIndexed { i, r ->
                if (i > 0) box.addView(View(c).apply { setBackgroundColor(Th.sep) }, lp(MP, 1).apply { leftMargin = dp(56) })
                val row = hbox(c); row.setPadding(dp(8), 0, dp(4), 0); row.fx(0f)
                row.addView(ImageView(c).apply { setImageDrawable(Icon("play", android.graphics.Color.WHITE)); background = rr(Th.accent, dpf(18)); setPadding(dp(10), dp(10), dp(10), dp(10)) }, lp(dp(36), dp(36)).apply { rightMargin = dp(12) })
                val col = vbox(c); col.addView(txt(c, f.format(Date(r.start)), 16f)); col.addView(txt(c, fmt(r.dur), 13f, Th.sub), lp(WC, WC).apply { topMargin = dp(2) }); row.addView(col, lp(0, WC, 1f))
                row.addView(ibtn(c, "share", 40, Th.accent, 10) { val src = File(n.dir, r.file); val out = File(ShareProvider.dir(act), "recording-" + r.start + "." + src.extension.ifEmpty { "m4a" }); try { src.copyTo(out, true); act.share(out, "audio/*") } catch (e: Exception) { toast("Could not share") } })
                row.addView(ibtn(c, "trash", 40, Th.danger, 10) { confirm("Delete this recording?", "The ink stays; only the audio is removed.", "Delete") { if (cur === r) { releasePlayer(); cur = null }; n.recs.remove(r); File(n.dir, r.file).delete(); n.metaDirty = true; ed.doSave(); if (visible) build(); fill() } })
                row.setOnClickListener { l.dismiss(); cur = r; curNote = n; releasePlayer(); visible = true; build(); ed.bar.place(false); togglePlay() }
                box.addView(row, lp(MP, dp(60)))
            }
            body.addView(box, lp(MP, WC))
            body.addView(txt(c, "Tap a recording to play it. With the player open, tap any ink to hear what was said while you wrote it.", 13f, Th.sub).apply { setPadding(dp(8), dp(10), dp(8), 0); setLineSpacing(dpf(2), 1f) })
        }
        fill(); l = sheet("Recordings", body, 520, extra = ibtn(c, "gear", 40, Th.sub, 10) { SettingsUi.sub("Audio", Reg.audio) { applySpeed() } })
    }

    private fun build() {
        val slot = ed.audioSlot; slot.removeAllViews(); ed.root.removeCallbacks(tick)
        if (!visible) return
        val c = act; val n = ed.focused?.note
        val bar = hbox(c); bar.background = rr(Th.bg, dpf(22), Th.hair, 1); bar.elevation = dpf(10); bar.setPadding(dp(8), dp(4), dp(6), dp(4))
        bar.addView(ibtn(c, if (recording) "stop" else "mic", 42, if (recording) Th.danger else Th.accent, 10) { if (recording) stopRec() else startRec() })
        val tl = txt(c, "", 13.5f, if (recording) Th.danger else Th.sub); tl.setPadding(dp(4), 0, dp(10), 0); bar.addView(tl); timeLabel = tl
        seek = null; playBtn = null
        if (n != null && n.recs.isNotEmpty() && !recording) {
            if (cur == null || curNote !== n || !n.recs.contains(cur!!)) { cur = n.recs.last(); curNote = n; releasePlayer() }
            val r = cur!!
            bar.addView(ibtn(c, "skipb", 38, Th.text, 9) { skip(-1) })
            val pb = ibtn(c, if (player?.isPlaying == true) "pause" else "play", 46, android.graphics.Color.WHITE, 12) { togglePlay() }; pb.background = rr(Th.accent, dpf(23)); bar.addView(pb, lp(dp(46), dp(46)).apply { leftMargin = dp(4); rightMargin = dp(4) }); playBtn = pb
            bar.addView(ibtn(c, "skipf", 38, Th.text, 9) { skip(1) })
            val sb = Slider(c, r.dur.toInt().coerceAtLeast(1), 0) { p -> ensurePlayer()?.seekTo(p); update() }
            sb.onTrack = { dragging = it }
            bar.addView(sb, lp(dp(220), WC)); seek = sb
            val sp = txt(c, SettingsUi.fmt(P.f("au.speed"), 0.25f, "×"), 13.5f, Th.accent, true); sp.setPadding(dp(8), dp(8), dp(8), dp(8)); sp.fx()
            sp.setOnClickListener { val list = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f); val nx = list[(list.indexOfFirst { it >= P.f("au.speed") - 0.01f }.coerceAtLeast(0) + 1) % list.size]; P.set("au.speed", nx); sp.text = SettingsUi.fmt(nx, 0.25f, "×"); applySpeed() }
            bar.addView(sp)
            bar.addView(ibtn(c, "clock", 38, Th.text, 9) { v -> recMenu(v, n) })
        } else if (!recording) bar.addView(txt(c, if (n == null) "" else "Tap the microphone to record while you write", 14f, Th.sub).apply { setPadding(dp(4), 0, dp(8), 0) })
        bar.addView(ibtn(c, "gear", 38, Th.sub, 9) { SettingsUi.sub("Audio", Reg.audio) { applySpeed() } })
        bar.addView(ibtn(c, "close", 38, Th.sub, 9) { toggleBar() })
        slot.addView(bar, android.widget.FrameLayout.LayoutParams(WC, dp(56)))
        update(); ed.root.postDelayed(tick, 400)
    }
    private var lastDim = 0L
    private fun dim(r: Rec?, pos: Int) {
        val playing = try { player?.isPlaying == true } catch (e: Exception) { false }
        if (visible && playing && r != null && P.b("au.dim")) {
            val t = r.start + pos
            if (kotlin.math.abs(t - lastDim) > 600) {
                lastDim = t; Render.bgIp.dimEnd = r.start + r.dur + 1500; Render.bgIp.dimAlpha = (P.f("au.dimAlpha") * 2.55f).toInt().coerceIn(8, 255); Render.bgIp.dimBefore = t
                for (pn in ed.panes) if (pn.note === curNote) pn.view.refreshAll()
            }
        } else if (lastDim != 0L) { lastDim = 0L; Render.bgIp.dimBefore = 0L; for (pn in ed.panes) pn.view.refreshAll() }
    }
    private var followPage = -1; private var lastFollow = 0L
    /** turns the pane to the page with the ink written most recently before moment t of the replay */
    private fun follow(t: Long) {
        val n = curNote ?: return; val pane = ed.panes.firstOrNull { it.note === n } ?: return
        var best = -1; var bt = 0L
        n.pages.forEachIndexed { i, pg -> if (pg.loaded) for (it in pg.items) if (it is Stroke && it.time in (bt + 1)..t) { bt = it.time; best = i } }
        if (best >= 0 && best != followPage) { followPage = best; if (pane.view.curPage() != best) pane.view.scrollToPage(best) }
    }
    private fun update() {
        val r = cur; val p = player
        val pos = try { p?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        dim(r, pos)
        if (r != null && P.b("au.follow") && (try { p?.isPlaying == true } catch (e: Exception) { false })) { val t = r.start + pos; if (kotlin.math.abs(t - lastFollow) > 900) { lastFollow = t; follow(t) } }
        val tl = timeLabel ?: return
        if (recording) { tl.text = "● REC " + fmt(System.currentTimeMillis() - recStart); return }
        if (r == null) { tl.text = ""; return }
        tl.text = fmt(pos.toLong()) + " / " + fmt(r.dur)
        if (!dragging) seek?.set(pos)
    }
    private fun recMenu(v: View, n: Note) {
        val f = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()); val items = ArrayList<MI?>()
        for (r in n.recs.asReversed()) items.add(MI(if (r === cur) "check" else "play", f.format(Date(r.start)) + " · " + fmt(r.dur)) { cur = r; curNote = n; releasePlayer(); build() })
        val c0 = cur
        if (c0 != null) { items.add(null); items.add(MI("trash", "Delete this recording", true) { confirm("Delete this recording?", "The ink stays; only the audio is removed.", "Delete") { releasePlayer(); n.recs.remove(c0); File(n.dir, c0.file).delete(); n.metaDirty = true; cur = null; ed.doSave(); build() } }) }
        showMenu(v, items)
    }

    /* ---------- recording ---------- */
    private fun startRec() {
        val n = ed.focused?.note ?: return
        act.withMic { ok ->
            if (!ok) { toast("Microphone permission is needed to record"); return@withMic }
            stopPlay()
            try {
                val opus = P.s("au.fmt") == "ogg" && android.os.Build.VERSION.SDK_INT >= 29
                val name = "aud_" + newId() + if (opus) ".ogg" else ".m4a"
                @Suppress("DEPRECATION") val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(act) else MediaRecorder()
                val mode = if (P.has("au.src")) P.s("au.src") else if (P.b("au.ns")) "voice" else "mic"
                r.setAudioSource(when (mode) { "voice" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION; "far" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    "raw" -> if (android.os.Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION; else -> MediaRecorder.AudioSource.MIC })
                if (opus) { r.setOutputFormat(MediaRecorder.OutputFormat.OGG); r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS) }
                else { r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC) }
                r.setAudioEncodingBitRate((P.f("au.kbps") * 1000).toInt()); r.setAudioSamplingRate(if (opus) 48000 else P.s("au.rate").toIntOrNull() ?: 44100); r.setAudioChannels(if (P.s("au.ch") == "stereo") 2 else 1)
                r.setOutputFile(File(n.dir, name).path); r.prepare(); r.start()
                rec = r; recNote = n; recFile = name; recStart = System.currentTimeMillis(); recording = true
                build(); ed.refreshTop()
            } catch (e: Throwable) { toast("Recording could not start: " + (e.message ?: "")); rec = null; recording = false }
        }
    }
    fun stopRec() {
        val r = rec ?: return; val n = recNote
        try { r.stop() } catch (e: Throwable) { }
        try { r.release() } catch (e: Throwable) { }
        rec = null; recording = false
        if (n != null) {
            val x = Rec(recFile, recStart, System.currentTimeMillis() - recStart)
            if (x.dur > 500 && File(n.dir, recFile).exists()) { n.recs.add(x); n.metaDirty = true; cur = x; curNote = n; releasePlayer(); Store.save(n) }
        }
        recNote = null; build(); ed.refreshTop()
    }

    /* ---------- playback ---------- */
    private fun releasePlayer() { try { player?.release() } catch (e: Throwable) { }; player = null }
    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val r = cur ?: return null; val n = curNote ?: return null
        return try {
            val p = MediaPlayer(); p.setDataSource(File(n.dir, r.file).path); p.prepare()
            if (p.duration > 0) r.dur = p.duration.toLong()
            p.setOnCompletionListener { build() }
            player = p; seek?.let { it.maxV = r.dur.toInt().coerceAtLeast(1); it.invalidate() }; p
        } catch (e: Throwable) { toast("This recording cannot be played"); null }
    }
    private fun applySpeed() { val p = player ?: return; val sp = P.f("au.speed").coerceIn(0.5f, 3f)
        try { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(sp).setPitch(if (P.b("au.pitch")) 1f else sp) } catch (e: Throwable) { } }
    private fun togglePlay() { val p = ensurePlayer() ?: return; if (p.isPlaying) p.pause() else { p.start(); applySpeed() }; build() }
    private fun stopPlay() { try { player?.let { if (it.isPlaying) it.pause() } } catch (e: Throwable) { } }
    private fun skip(dir: Int) { val p = ensurePlayer() ?: return; p.seekTo((p.currentPosition + dir * (P.f("au.skip") * 1000).toInt()).coerceIn(0, p.duration)); update() }

    /** tap on ink: jump to the moment that stroke was written */
    fun seekTo(pane: Pane, s: Stroke): Boolean {
        val n = pane.note ?: return false; if (s.time == 0L) return false
        val r = n.recs.firstOrNull { s.time >= it.start - 500 && s.time <= it.start + it.dur + 500 } ?: return false
        if (cur !== r || curNote !== n) { cur = r; curNote = n; releasePlayer(); build() }
        val p = ensurePlayer() ?: return false
        val t0 = startOf(n, s, r)
        p.seekTo((t0 - r.start - (P.f("au.lead") * 1000).toLong()).coerceIn(0, r.dur).toInt())
        if (P.b("au.autoplay") && !p.isPlaying) { p.start(); applySpeed(); build() } else update()
        return true
    }
    /** "jump to the start of the word / line": walks back through the strokes written just before this one on the same page */
    private fun startOf(n: Note, s: Stroke, r: Rec): Long {
        val g = P.s("au.gran"); if (g == "stroke") return s.time
        val pg = n.pages.firstOrNull { it.items.contains(s) } ?: return s.time
        val list = pg.items.filterIsInstance<Stroke>().filter { it.time in r.start..s.time && it.tool != K_TAPE }.sortedBy { it.time }
        var k = list.indexOf(s); if (k < 0) return s.time
        val lh = maxOf(s.bounds.height(), 8f)
        while (k > 0) {
            val a = list[k - 1]; val b = list[k]
            val sameLine = kotlin.math.abs(a.bounds.centerY() - b.bounds.centerY()) < lh * 0.9f
            val ok = if (g == "line") sameLine && b.time - a.time < 20000 else sameLine && b.time - a.time < 1200 && b.bounds.left - a.bounds.right < lh * 0.8f
            if (!ok) break; k--
        }
        return list[k].time
    }
    fun onClose(p: Pane) { if (recording && recNote === p.note && P.b("au.stopClose")) stopRec(); if (curNote === p.note) { releasePlayer(); cur = null; curNote = null; if (visible) build() } }
    fun onPause() { if (recording) stopRec(); stopPlay() }
}
