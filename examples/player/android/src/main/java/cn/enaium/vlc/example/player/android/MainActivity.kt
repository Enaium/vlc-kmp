package cn.enaium.vlc.example.player.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import cn.enaium.sdl.SDL
import cn.enaium.vlc.example.player.PlayerApp
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * JVM Android entry point. SDLActivity loads the SDL and libvlc_jni bridges
 * and invokes [main] on SDL's dedicated thread. The player is the same shared
 * PlayerApp used by desktop and native targets.
 */
class MainActivity : SDLActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun getLibraries(): Array<String> = arrayOf("sdl_jni", "vlc_jni")

    override fun main() {
        try {
            val sample = File(cacheDir, "sample.mp4")
            if (!sample.isFile) {
                assets.open("sample.mp4").use { input ->
                    sample.outputStream().use { output -> input.copyTo(output) }
                }
            }
            PlayerApp(
                initialPath = sample.absolutePath,
                uiScaleProvider = { resources.displayMetrics.density },
            ).run()
        } catch (t: Throwable) {
            Log.e(TAG, "vlc-kmp player failed", t)
        } finally {
            SDL.quit()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private companion object {
        const val TAG = "VlcPlayerActivity"
    }
}
