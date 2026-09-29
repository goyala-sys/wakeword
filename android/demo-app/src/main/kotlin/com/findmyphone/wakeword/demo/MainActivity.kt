package com.findmyphone.wakeword.demo

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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.findmyphone.wakeword.WakeWord
import com.findmyphone.wakeword.core.KeywordSpec
import com.findmyphone.wakeword.demo.DemoApp.Engine
import java.text.DateFormat
import java.util.Date

/**
 * One-screen QA app with two engines, one at a time (both need the mic):
 *  - Open vocabulary: type any phrase; live mic level, VAD dot and detections.
 *  - DaVoice: pick a trained model bundled in the APK, paste the licence key.
 * Every detection flashes green and lands in one log, tagged by engine.
 * Built in code with platform widgets: no XML, no AndroidX.
 */
class MainActivity : Activity() {

    private val bg = Color.rgb(18, 18, 20)
    private val fg = Color.rgb(235, 235, 240)
    private val dim = Color.rgb(140, 140, 150)
    private val green = Color.rgb(34, 197, 94)
    private val amber = Color.rgb(245, 158, 11)
    private val red = Color.rgb(239, 68, 68)

    private val app get() = application as DemoApp
    private lateinit var engineGroup: RadioGroup
    private lateinit var openVocabRadio: RadioButton
    private lateinit var davoiceRadio: RadioButton
    private lateinit var openVocabSection: LinearLayout
    private lateinit var davoiceSection: LinearLayout
    private lateinit var keyword: EditText
    private lateinit var hint: TextView
    private lateinit var modelGroup: RadioGroup
    private lateinit var licence: EditText
    private lateinit var normal: RadioButton
    private lateinit var sensitive: RadioButton
    private lateinit var ring: Switch
    private lateinit var startStop: Button
    private lateinit var status: TextView
    private lateinit var meterSection: LinearLayout
    private lateinit var level: ProgressBar
    private lateinit var levelText: TextView
    private lateinit var speechDot: TextView
    private lateinit var panel: TextView
    private lateinit var counter: TextView
    private lateinit var log: TextView
    private var flash: ValueAnimator? = null
    private var sessionCount = 0
    private val modelIds = HashMap<Int, String>()

    private val engine get() = if (davoiceRadio.isChecked) Engine.DAVOICE else Engine.OPEN_VOCAB

    private val onDetect = WakeWord.Listener { d -> showDetection(d.keyword.replace('_', ' ').lowercase()) }
    private val onState = WakeWord.StateListener { render() }
    private val onDaVoiceState: (DaVoiceEngine.State) -> Unit = { render() }
    private val onDaVoiceDetect: (String) -> Unit = { showDetection(DaVoiceEngine.label(it)) }
    private val onMeter = WakeWord.MeterListener { m ->
        level.progress = ((m.levelDb + 70f) / 70f * 100f).toInt().coerceIn(0, 100)
        levelText.text = "%.0f dB".format(m.levelDb)
        speechDot.setTextColor(if (m.speech) green else dim)
        speechDot.text = if (m.speech) "●  speech — spotter running" else "○  quiet — spotter idle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        setContentView(buildUi())
        val p = DemoApp.prefs(this)
        keyword.setText(p.getString(DemoApp.PREF_KEYWORD, "hey buddy"))
        val savedModel = p.getString(DemoApp.PREF_DV_MODEL, null)
        (modelIds.entries.firstOrNull { it.value == savedModel }?.key ?: modelIds.keys.firstOrNull())?.let { modelGroup.check(it) }
        licence.setText(p.getString(DemoApp.PREF_DV_LICENCE, null) ?: BuildConfig.DAVOICE_LICENSE)
        if (p.getBoolean(DemoApp.PREF_SENSITIVE, false)) sensitive.isChecked = true else normal.isChecked = true
        if (p.getString(DemoApp.PREF_ENGINE, null) == Engine.DAVOICE.name) davoiceRadio.isChecked = true
        else openVocabRadio.isChecked = true
        engineGroup.setOnCheckedChangeListener { _, _ ->
            p.edit().putString(DemoApp.PREF_ENGINE, engine.name).apply()
            render()
        }
        ring.isChecked = p.getBoolean(DemoApp.PREF_RING, false)
        ring.setOnCheckedChangeListener { _, on -> p.edit().putBoolean(DemoApp.PREF_RING, on).apply() }
        validate()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        WakeWord.addListener(onDetect)
        WakeWord.addStateListener(onState)
        WakeWord.addMeterListener(onMeter)
        app.davoice.stateListeners.add(onDaVoiceState)
        app.davoice.detectionListeners.add(onDaVoiceDetect)
        WakeWord.ensureRunning(this) // the launcher pattern: revive the service whenever visible
        render()
        refreshLog()
    }

    override fun onPause() {
        WakeWord.removeListener(onDetect)
        WakeWord.removeStateListener(onState)
        WakeWord.removeMeterListener(onMeter) // metering stops when nobody is watching
        app.davoice.stateListeners.remove(onDaVoiceState)
        app.davoice.detectionListeners.remove(onDaVoiceDetect)
        super.onPause()
    }

    // ---- actions ------------------------------------------------------------

    private fun running() = WakeWord.isEnabled(this) || app.davoice.isActive

    private fun onStartStop() {
        if (running()) {
            WakeWord.stop(this)
            app.davoice.stop()
            render()
            return
        }
        when (engine) {
            Engine.OPEN_VOCAB -> if (!validate()) return
            Engine.DAVOICE -> {
                if (selectedModel() == null) return
                if (licence.text.isBlank()) {
                    status.text = "Paste the licence key from DaVoice first"
                    status.setTextColor(red)
                    return
                }
            }
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS) else start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            status.text = "Microphone permission denied — can't listen"
            status.setTextColor(red)
        }
    }

    private fun start() {
        val p = DemoApp.prefs(this).edit()
            .putString(DemoApp.PREF_ENGINE, engine.name)
            .putBoolean(DemoApp.PREF_SENSITIVE, sensitive.isChecked)
        sessionCount = 0
        counter.text = "0 detections this session"
        when (engine) {
            Engine.OPEN_VOCAB -> {
                val text = keyword.text.toString()
                p.putString(DemoApp.PREF_KEYWORD, text).apply()
                WakeWord.start(this, listOf(spec(text)))
                status.text = "Starting…"
                status.setTextColor(amber)
            }
            Engine.DAVOICE -> {
                val model = selectedModel() ?: return
                val key = licence.text.toString().trim()
                p.putString(DemoApp.PREF_DV_MODEL, model).putString(DemoApp.PREF_DV_LICENCE, key).apply()
                app.davoice.start(model, if (sensitive.isChecked) DaVoiceEngine.SENSITIVE else DaVoiceEngine.NORMAL, key)
            }
        }
        render()
    }

    /** Normal = defaults; Sensitive = boost 2.0 (more catches in noise, ~3x false alarms; see README). */
    private fun spec(text: String) = KeywordSpec(text, boost = if (sensitive.isChecked) 2.0f else 1.0f)

    private fun selectedModel(): String? = modelIds[modelGroup.checkedRadioButtonId]

    private fun validate(): Boolean {
        val v = WakeWord.validate(this, spec(keyword.text.toString()))
        when {
            v.error != null -> { hint.text = v.error; hint.setTextColor(red) }
            v.warning != null -> { hint.text = v.warning; hint.setTextColor(amber) }
            else -> { hint.text = "Good phrase."; hint.setTextColor(green) }
        }
        return v.ok
    }

    // ---- rendering ----------------------------------------------------------

    private fun render() {
        val busy = running()
        // While listening, show the engine that's actually running.
        if (WakeWord.isEnabled(this)) openVocabRadio.isChecked = true
        if (app.davoice.isActive) davoiceRadio.isChecked = true
        val dv = engine == Engine.DAVOICE
        openVocabSection.visibility = if (dv) View.GONE else View.VISIBLE
        davoiceSection.visibility = if (dv) View.VISIBLE else View.GONE
        meterSection.visibility = if (dv) View.GONE else View.VISIBLE
        normal.text = if (dv) "Normal (${DaVoiceEngine.NORMAL})" else "Normal"
        sensitive.text = if (dv) "Sensitive (${DaVoiceEngine.SENSITIVE})" else "Sensitive"
        startStop.text = if (busy) "Stop" else "Start listening"
        for (v in listOf(openVocabRadio, davoiceRadio, keyword, licence, normal, sensitive)) v.isEnabled = !busy
        for (i in 0 until modelGroup.childCount) modelGroup.getChildAt(i).isEnabled = !busy
        if (dv) renderDaVoice(app.davoice.state) else renderOpenVocab(WakeWord.state)
    }

    private fun renderOpenVocab(s: WakeWord.State) {
        val enabled = WakeWord.isEnabled(this)
        when (s) {
            WakeWord.State.Listening -> {
                status.text = "Listening for \"${keyword.text}\" — works with the screen off too"
                status.setTextColor(green)
            }
            WakeWord.State.Stopped -> {
                status.text = if (enabled) "Starting…" else "Stopped"
                status.setTextColor(if (enabled) amber else dim)
                if (!enabled) onMeter.onMeter(WakeWord.Meter(-90f, false))
            }
            is WakeWord.State.Error -> {
                status.text = "Error: ${s.message}"
                status.setTextColor(red)
            }
        }
    }

    private fun renderDaVoice(s: DaVoiceEngine.State) {
        when (s) {
            is DaVoiceEngine.State.Listening -> {
                status.text = "Listening for \"${DaVoiceEngine.label(s.model)}\" (threshold ${s.threshold}) — works with the screen off too"
                status.setTextColor(green)
            }
            DaVoiceEngine.State.Starting -> { status.text = "Starting…"; status.setTextColor(amber) }
            DaVoiceEngine.State.Stopped -> { status.text = "Stopped"; status.setTextColor(dim) }
            is DaVoiceEngine.State.Error -> { status.text = "Error: ${s.message}"; status.setTextColor(red) }
        }
    }

    private fun showDetection(phrase: String) {
        sessionCount++
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        panel.text = "DETECTED\n\"$phrase\"\n$time"
        panel.setTextColor(Color.BLACK)
        flash?.cancel()
        flash = ValueAnimator.ofObject(ArgbEvaluator(), green, Color.rgb(32, 32, 36)).apply {
            startDelay = 1200
            duration = 1500
            addUpdateListener { (panel.background as GradientDrawable).setColor(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    panel.setTextColor(fg)
                }
            })
        }
        (panel.background as GradientDrawable).setColor(green)
        flash?.start()
        counter.text = "$sessionCount detection${if (sessionCount == 1) "" else "s"} this session"
        panel.post { refreshLog() } // after DemoApp has recorded it
    }

    private fun refreshLog() {
        val events = app.recentDetections
        val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        log.text = if (events.isEmpty()) {
            "No detections yet."
        } else {
            events.asReversed().joinToString("\n") {
                "${fmt.format(Date(it.wallTimeMs))}  ${if (it.engine == Engine.DAVOICE) "DV" else "OV"}  ${it.phrase}"
            }
        }
    }

    // ---- layout -------------------------------------------------------------

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
        fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(label("Wake word test", 24f, fg, bold = true))
        col.addView(gap(12))
        col.addView(label("Engine"))
        engineGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        openVocabRadio = RadioButton(this).apply { text = Engine.OPEN_VOCAB.label; setTextColor(fg); id = View.generateViewId() }
        davoiceRadio = RadioButton(this).apply { text = Engine.DAVOICE.label; setTextColor(fg); id = View.generateViewId() }
        engineGroup.addView(openVocabRadio); engineGroup.addView(davoiceRadio)
        col.addView(engineGroup)
        col.addView(gap(12))

        openVocabSection = section()
        openVocabSection.addView(label("Phrase (any words)"))
        keyword = EditText(this).apply {
            setTextColor(fg)
            setHintTextColor(dim)
            hint = "e.g. hey buddy"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { validate() }
            })
        }
        openVocabSection.addView(keyword, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        hint = label("", 13f)
        openVocabSection.addView(hint)
        col.addView(openVocabSection)

        davoiceSection = section()
        davoiceSection.addView(label("Phrase (one trained model per phrase)"))
        modelGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val models = app.davoice.models()
        for (m in models) {
            val rb = RadioButton(this).apply { text = DaVoiceEngine.label(m); setTextColor(fg); id = View.generateViewId() }
            modelIds[rb.id] = m
            modelGroup.addView(rb)
        }
        davoiceSection.addView(modelGroup)
        if (models.isEmpty()) davoiceSection.addView(label("No DaVoice models in this build — run fetch_davoice.sh and rebuild.", 13f, red))
        davoiceSection.addView(label("Other phrases need a model from DaVoice; put the .dm in android/davoice-models/ and rebuild.", 12f))
        davoiceSection.addView(gap(8))
        davoiceSection.addView(label("Licence key"))
        licence = EditText(this).apply {
            setTextColor(fg)
            setHintTextColor(dim)
            hint = "paste the key DaVoice sent you"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
            textSize = 13f
        }
        davoiceSection.addView(licence, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(davoiceSection)
        col.addView(gap(12))

        val radios = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        normal = RadioButton(this).apply { text = "Normal"; setTextColor(fg); id = View.generateViewId() }
        sensitive = RadioButton(this).apply { text = "Sensitive"; setTextColor(fg); id = View.generateViewId() }
        radios.addView(normal); radios.addView(sensitive)
        col.addView(radios)
        ring = Switch(this).apply { text = "Ring on detection"; setTextColor(fg) }
        col.addView(ring, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(12))

        startStop = Button(this).apply {
            text = "Start listening"
            setOnClickListener { onStartStop() }
        }
        col.addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = label("Stopped", 14f)
        col.addView(status)
        col.addView(gap(16))

        meterSection = section()
        val meterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        meterRow.addView(level, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        levelText = label("—", 12f).apply { setPadding(dp(8), 0, 0, 0) }
        meterRow.addView(levelText, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        meterSection.addView(label("Microphone level", 12f))
        meterSection.addView(meterRow)
        speechDot = label("○  quiet — spotter idle", 13f)
        meterSection.addView(speechDot)
        meterSection.addView(gap(16))
        col.addView(meterSection)

        panel = label("Say your phrase…", 22f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(32, 32, 36)) }
            setPadding(dp(16), dp(28), dp(16), dp(28))
            setOnClickListener { app.ringer.stop() } // tap to silence
        }
        col.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(label("Tap the panel to stop ringing", 12f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(16))
        counter = label("0 detections this session", 14f, fg, bold = true)
        col.addView(counter)
        col.addView(label("Recent, both engines (OV = open vocabulary, DV = DaVoice)", 12f))
        log = label("", 14f, fg).apply { typeface = Typeface.MONOSPACE }
        col.addView(log)

        return ScrollView(this).apply { setBackgroundColor(bg); addView(col) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val REQ_PERMS = 1 }
}
