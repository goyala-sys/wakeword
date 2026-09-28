package com.findmyphone.wakeword.davoice

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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * One-screen test app for DaVoice models: pick a bundled model, paste the licence key, start,
 * and see every detection. Same look as :demo-app so the two are easy to compare side by side.
 * DaVoice models are fixed phrases: a new phrase means a new .dm file from DaVoice and a rebuild.
 */
class MainActivity : Activity() {

    private val bg = Color.rgb(18, 18, 20)
    private val fg = Color.rgb(235, 235, 240)
    private val dim = Color.rgb(140, 140, 150)
    private val green = Color.rgb(34, 197, 94)
    private val amber = Color.rgb(245, 158, 11)
    private val red = Color.rgb(239, 68, 68)

    private val app get() = application as DaVoiceApp
    private lateinit var modelGroup: RadioGroup
    private lateinit var licence: EditText
    private lateinit var sensitive: RadioButton
    private lateinit var normal: RadioButton
    private lateinit var ring: Switch
    private lateinit var startStop: Button
    private lateinit var status: TextView
    private lateinit var panel: TextView
    private lateinit var counter: TextView
    private lateinit var log: TextView
    private var flash: ValueAnimator? = null
    private var sessionCount = 0
    private val modelIds = HashMap<Int, String>()

    private val onState: (DaVoiceApp.State) -> Unit = { renderState(it) }
    private val onDetect: (DaVoiceApp.Event) -> Unit = { showDetection(it.model) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        setContentView(buildUi())
        val p = DaVoiceApp.prefs(this)
        val saved = p.getString(DaVoiceApp.PREF_MODEL, null)
        modelIds.entries.firstOrNull { it.value == saved }?.let { modelGroup.check(it.key) }
            ?: modelIds.keys.firstOrNull()?.let { modelGroup.check(it) }
        licence.setText(p.getString(DaVoiceApp.PREF_LICENCE, null) ?: BuildConfig.DAVOICE_LICENSE)
        if (p.getBoolean(DaVoiceApp.PREF_SENSITIVE, false)) sensitive.isChecked = true else normal.isChecked = true
        ring.isChecked = p.getBoolean(DaVoiceApp.PREF_RING, false)
        ring.setOnCheckedChangeListener { _, on -> p.edit().putBoolean(DaVoiceApp.PREF_RING, on).apply() }
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        app.stateListeners.add(onState)
        app.detectionListeners.add(onDetect)
        renderState(app.state)
        refreshLog()
    }

    override fun onPause() {
        app.stateListeners.remove(onState)
        app.detectionListeners.remove(onDetect)
        super.onPause()
    }

    // ---- actions ------------------------------------------------------------

    private fun onStartStop() {
        if (app.state is DaVoiceApp.State.Listening || app.state is DaVoiceApp.State.Starting) {
            app.stop()
            return
        }
        if (selectedModel() == null) return
        if (licence.text.isBlank()) {
            status.text = "Paste the licence key from DaVoice first"
            status.setTextColor(red)
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            status.text = "Microphone permission denied — can't listen"
            status.setTextColor(red)
        }
    }

    private fun start() {
        val model = selectedModel() ?: return
        val key = licence.text.toString().trim()
        DaVoiceApp.prefs(this).edit()
            .putString(DaVoiceApp.PREF_MODEL, model)
            .putString(DaVoiceApp.PREF_LICENCE, key)
            .putBoolean(DaVoiceApp.PREF_SENSITIVE, sensitive.isChecked)
            .apply()
        sessionCount = 0
        counter.text = "0 detections this session"
        app.start(model, if (sensitive.isChecked) SENSITIVE else NORMAL, key)
    }

    private fun selectedModel(): String? = modelIds[modelGroup.checkedRadioButtonId]

    // ---- rendering ----------------------------------------------------------

    private fun renderState(s: DaVoiceApp.State) {
        val busy = s is DaVoiceApp.State.Listening || s is DaVoiceApp.State.Starting
        startStop.text = if (busy) "Stop" else "Start listening"
        for (i in 0 until modelGroup.childCount) modelGroup.getChildAt(i).isEnabled = !busy
        licence.isEnabled = !busy
        normal.isEnabled = !busy
        sensitive.isEnabled = !busy
        when (s) {
            is DaVoiceApp.State.Listening -> {
                status.text = "Listening for \"${DaVoiceApp.label(s.model)}\" (threshold ${s.threshold}) — works with the screen off too"
                status.setTextColor(green)
            }
            DaVoiceApp.State.Starting -> { status.text = "Starting…"; status.setTextColor(amber) }
            DaVoiceApp.State.Stopped -> { status.text = "Stopped"; status.setTextColor(dim) }
            is DaVoiceApp.State.Error -> { status.text = "Error: ${s.message}"; status.setTextColor(red) }
        }
    }

    private fun showDetection(model: String) {
        sessionCount++
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        panel.text = "DETECTED\n\"${DaVoiceApp.label(model)}\"\n$time"
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
        refreshLog()
    }

    private fun refreshLog() {
        val events = app.recentDetections
        val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        log.text = if (events.isEmpty()) {
            "No detections yet."
        } else {
            events.asReversed().joinToString("\n") { "${fmt.format(Date(it.wallTimeMs))}   ${DaVoiceApp.label(it.model)}" }
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

        col.addView(label("Wake word test — DaVoice", 24f, fg, bold = true))
        col.addView(gap(16))
        col.addView(label("Phrase (one trained model per phrase)"))
        modelGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val models = app.models()
        for (m in models) {
            val rb = RadioButton(this).apply { text = DaVoiceApp.label(m); setTextColor(fg); id = View.generateViewId() }
            modelIds[rb.id] = m
            modelGroup.addView(rb)
        }
        col.addView(modelGroup)
        if (models.isEmpty()) col.addView(label("No models in this build — run fetch_davoice.sh and rebuild.", 13f, red))
        col.addView(label("Other phrases need a model from DaVoice (info@davoice.io); put the .dm in davoice-demo/models/ and rebuild.", 12f))
        col.addView(gap(12))

        col.addView(label("Licence key"))
        licence = EditText(this).apply {
            setTextColor(fg)
            setHintTextColor(dim)
            hint = "paste the key DaVoice sent you"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
            textSize = 13f
        }
        col.addView(licence, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(gap(12))

        val radios = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        normal = RadioButton(this).apply { text = "Normal ($NORMAL)"; setTextColor(fg); id = View.generateViewId() }
        sensitive = RadioButton(this).apply { text = "Sensitive ($SENSITIVE)"; setTextColor(fg); id = View.generateViewId() }
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
        col.addView(label("Recent (including while the app was closed)", 12f))
        log = label("", 14f, fg).apply { typeface = Typeface.MONOSPACE }
        col.addView(log)

        return ScrollView(this).apply { setBackgroundColor(bg); addView(col) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PERMS = 1
        /** DaVoice's examples all use 0.99; lower = easier to trigger. Tune once real tests are in. */
        const val NORMAL = 0.99f
        const val SENSITIVE = 0.95f
    }
}
