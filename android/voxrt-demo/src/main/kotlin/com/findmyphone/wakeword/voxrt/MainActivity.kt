package com.findmyphone.wakeword.voxrt

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * One-screen test app for VoxRT, laid out like the QA app so results are easy to compare.
 * Fixed phrase "Hey Assistant"; live mic level and score bar; every detection with its score.
 */
class MainActivity : Activity() {

    private val bg = Color.rgb(18, 18, 20)
    private val fg = Color.rgb(235, 235, 240)
    private val dim = Color.rgb(140, 140, 150)
    private val green = Color.rgb(34, 197, 94)
    private val amber = Color.rgb(245, 158, 11)
    private val red = Color.rgb(239, 68, 68)

    private val app get() = application as VoxrtApp
    private lateinit var normal: RadioButton
    private lateinit var sensitive: RadioButton
    private lateinit var srcVoice: RadioButton
    private lateinit var srcMic: RadioButton
    private lateinit var ring: Switch
    private lateinit var startStop: Button
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var levelText: TextView
    private lateinit var scoreBar: ProgressBar
    private lateinit var scoreText: TextView
    private lateinit var perfText: TextView
    private lateinit var panel: TextView
    private lateinit var counter: TextView
    private lateinit var log: TextView
    private var flash: ValueAnimator? = null
    private var sessionCount = 0
    private var starting = false

    private val onState: (VoxrtApp.State) -> Unit = { starting = false; render(it) }
    private val onDetect: (VoxrtApp.Event) -> Unit = { showDetection(it) }
    private val onMeter: (VoxrtApp.Meter) -> Unit = { m ->
        level.progress = ((m.levelDb + 70f) / 70f * 100f).toInt().coerceIn(0, 100)
        levelText.text = "%.0f dB".format(m.levelDb)
        scoreBar.progress = (m.score * 100).toInt().coerceIn(0, 100)
        scoreText.text = "%.2f".format(m.score)
        perfText.text = "inference %.2f ms per 100 ms of audio".format(m.msPerBlock)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        setContentView(buildUi())
        val p = VoxrtApp.prefs(this)
        if (p.getBoolean(VoxrtApp.PREF_SENSITIVE, false)) sensitive.isChecked = true else normal.isChecked = true
        if (p.getString(VoxrtApp.PREF_MIC_SOURCE, null) == VoxrtService.SOURCE_MIC) srcMic.isChecked = true else srcVoice.isChecked = true
        ring.isChecked = p.getBoolean(VoxrtApp.PREF_RING, false)
        ring.setOnCheckedChangeListener { _, on -> p.edit().putBoolean(VoxrtApp.PREF_RING, on).apply() }
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        app.stateListeners.add(onState)
        app.meterListeners.add(onMeter)
        app.detectionListeners.add(onDetect)
        render(app.state)
        refreshLog()
    }

    override fun onPause() {
        app.stateListeners.remove(onState)
        app.meterListeners.remove(onMeter)
        app.detectionListeners.remove(onDetect)
        super.onPause()
    }

    private fun listening() = app.state is VoxrtApp.State.Listening || starting

    private fun onStartStop() {
        if (listening()) {
            VoxrtService.stop(this)
            starting = false
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS) else start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else { status.text = "Microphone permission denied — can't listen"; status.setTextColor(red) }
    }

    private fun start() {
        val source = if (srcMic.isChecked) VoxrtService.SOURCE_MIC else VoxrtService.SOURCE_VOICE_RECOGNITION
        VoxrtApp.prefs(this).edit()
            .putBoolean(VoxrtApp.PREF_SENSITIVE, sensitive.isChecked)
            .putString(VoxrtApp.PREF_MIC_SOURCE, source)
            .apply()
        sessionCount = 0
        counter.text = "0 detections this session"
        starting = true
        VoxrtService.start(this, if (sensitive.isChecked) VoxrtApp.SENSITIVE else VoxrtApp.NORMAL, source)
        status.text = "Starting…"; status.setTextColor(amber)
        startStop.text = "Stop"
    }

    private fun render(s: VoxrtApp.State) {
        val busy = listening()
        startStop.text = if (busy) "Stop" else "Start listening"
        for (v in listOf(normal, sensitive, srcVoice, srcMic)) v.isEnabled = !busy
        when (s) {
            is VoxrtApp.State.Listening -> {
                status.text = "Listening for \"${VoxrtApp.PHRASE}\" (threshold ${s.threshold}, ${s.source}) — works with the screen off too"
                status.setTextColor(green)
            }
            VoxrtApp.State.Stopped -> if (!starting) {
                status.text = "Stopped"; status.setTextColor(dim)
                onMeter(VoxrtApp.Meter(-90f, 0f, 0f))
            }
            is VoxrtApp.State.Error -> { status.text = "Error: ${s.message}"; status.setTextColor(red) }
        }
    }

    private fun showDetection(e: VoxrtApp.Event) {
        sessionCount++
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.wallTimeMs))
        panel.text = "DETECTED\n\"${VoxrtApp.PHRASE}\"\nscore %.2f · %s".format(e.score, time)
        panel.setTextColor(Color.BLACK)
        flash?.cancel()
        flash = ValueAnimator.ofObject(ArgbEvaluator(), green, Color.rgb(32, 32, 36)).apply {
            startDelay = 1200
            duration = 1500
            addUpdateListener { (panel.background as GradientDrawable).setColor(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { panel.setTextColor(fg) }
            })
        }
        (panel.background as GradientDrawable).setColor(green)
        flash?.start()
        counter.text = "$sessionCount detection${if (sessionCount == 1) "" else "s"} this session"
        refreshLog()
    }

    private fun refreshLog() {
        val events = app.recentDetections
        val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        log.text = if (events.isEmpty()) "No detections yet." else events.asReversed().joinToString("\n") {
            "${fmt.format(Date(it.wallTimeMs))}  score %.2f  mic %.0f dB".format(it.score, it.levelDb)
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        fun label(text: String, size: Float = 14f, color: Int = dim, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
        fun radio(text: String) = RadioButton(this).apply { this.text = text; setTextColor(fg); id = View.generateViewId() }
        fun bar(): Pair<LinearLayout, Pair<ProgressBar, TextView>> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            row.addView(pb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val t = label("—", 12f).apply { setPadding(dp(8), 0, 0, 0) }
            row.addView(t, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
            return row to (pb to t)
        }

        col.addView(label("Wake word test — VoxRT", 24f, fg, bold = true))
        col.addView(gap(12))
        col.addView(label("Phrase"))
        col.addView(label("\"Hey Assistant\"", 20f, fg, bold = true))
        col.addView(label("VoxRT's free model knows only this phrase. Custom phrases are a paid VoxRT service (help@voxrt.com).", 12f))
        col.addView(gap(12))

        val thresholds = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        normal = radio("Normal (${VoxrtApp.NORMAL})"); sensitive = radio("Sensitive (${VoxrtApp.SENSITIVE})")
        thresholds.addView(normal); thresholds.addView(sensitive)
        col.addView(thresholds)
        col.addView(label("Mic source", 12f))
        val sources = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        srcVoice = radio("Voice recognition"); srcMic = radio("Mic (VoxRT's example)")
        sources.addView(srcVoice); sources.addView(srcMic)
        col.addView(sources)
        ring = Switch(this).apply { text = "Ring on detection"; setTextColor(fg) }
        col.addView(ring, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(12))

        startStop = Button(this).apply { text = "Start listening"; setOnClickListener { onStartStop() } }
        col.addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = label("Stopped", 14f)
        col.addView(status)
        col.addView(gap(16))

        col.addView(label("Microphone level", 12f))
        bar().let { (row, v) -> level = v.first; levelText = v.second; col.addView(row) }
        col.addView(label("Score (detects above the threshold)", 12f))
        bar().let { (row, v) -> scoreBar = v.first; scoreText = v.second; col.addView(row) }
        perfText = label("", 12f)
        col.addView(perfText)
        col.addView(gap(16))

        panel = label("Say \"Hey Assistant\"…", 22f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(32, 32, 36)) }
            setPadding(dp(16), dp(28), dp(16), dp(28))
            setOnClickListener { app.ringer.stop() }
        }
        col.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(label("Tap the panel to stop ringing", 12f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(16))
        counter = label("0 detections this session", 14f, fg, bold = true)
        col.addView(counter)
        col.addView(label("Recent (including while the app was closed)", 12f))
        log = label("", 14f, fg).apply { typeface = Typeface.MONOSPACE }
        col.addView(log)

        return ScrollView(this).apply { setBackgroundColor(bg); addView(col) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val REQ_PERMS = 1 }
}
