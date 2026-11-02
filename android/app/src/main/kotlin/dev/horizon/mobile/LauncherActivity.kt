package dev.horizon.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** The launcher: one Play button, the device guard's verdict, and a switch to turn the guard off. */
class LauncherActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var guard: Switch

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(res: Int, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setText(res)
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 16, 20))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(text(R.string.app_name, 36f, Color.WHITE, bold = true))
        root.addView(text(R.string.subtitle, 14f, Color.LTGRAY).apply { setPadding(0, dp(4), 0, dp(24)) })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(status)

        guard = Switch(this).apply {
            setText(R.string.guard_switch)
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("guard", true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("guard", on).apply()
                refresh()
            }
        }
        root.addView(guard)
        root.addView(text(R.string.guard_hint, 12f, Color.GRAY).apply { setPadding(0, dp(4), 0, dp(24)) })

        play = Button(this).apply {
            setText(R.string.play)
            textSize = 22f
            setOnClickListener { launch() }
        }
        root.addView(play, LinearLayout.LayoutParams(dp(240), dp(72)))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val v = DeviceGuard.check(this)
        val lines = ArrayList<String>()
        lines += v.memoryLine
        if (v.blockers.isNotEmpty()) {
            lines += getString(R.string.blocked_title)
            v.blockers.forEach { lines += "• $it" }
        }
        if (v.warnings.isNotEmpty()) {
            lines += getString(R.string.warn_title)
            v.warnings.forEach { lines += "• $it" }
        }
        if (v.ok && v.warnings.isEmpty()) lines += getString(R.string.status_ok)
        status.text = lines.joinToString("\n")
        // With the guard off the person decides, except where the game cannot run at all.
        play.isEnabled = !v.fatal && (v.ok || !guard.isChecked)
        play.visibility = View.VISIBLE
    }

    private fun launch() {
        refresh()
        if (!play.isEnabled) return
        startActivity(
            Intent(this, HorizonGameActivity::class.java)
                .putExtra(HorizonGameActivity.EXTRA_GUARD, guard.isChecked),
        )
    }
}
