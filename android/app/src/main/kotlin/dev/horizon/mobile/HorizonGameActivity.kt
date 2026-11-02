package dev.horizon.mobile

import android.app.NativeActivity
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast

/**
 * Runs the game: a NativeActivity that loads libhorizon_android.so (the meta-data in the manifest),
 * whose `android_main` starts the Rust game. While it runs, the device guard watches the phone and
 * closes the game, saving the world, before it overheats or runs out of memory.
 */
class HorizonGameActivity : NativeActivity() {
    companion object {
        const val EXTRA_GUARD = "guard"
        private const val GUARD_PERIOD_MS = 5_000L
        /** After the activity closes, the process is ended if the game has not finished saving by then. */
        private const val SAVE_GRACE_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var guardOn = true
    private var stopping = false

    private val guardTick = object : Runnable {
        override fun run() {
            val reason = DeviceGuard.runtimeStop(this@HorizonGameActivity)
            if (reason != null) {
                stopGame(reason.message)
            } else {
                handler.postDelayed(this, GUARD_PERIOD_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        guardOn = intent.getBooleanExtra(EXTRA_GUARD, true)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (guardOn && !stopping) handler.postDelayed(guardTick, GUARD_PERIOD_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(guardTick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (guardOn && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            stopGame(R.string.stop_memory)
        }
    }

    /** Closes the activity the normal way, so the game's shutdown saves the world. */
    private fun stopGame(message: Int) {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(guardTick)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, SAVE_GRACE_MS)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
