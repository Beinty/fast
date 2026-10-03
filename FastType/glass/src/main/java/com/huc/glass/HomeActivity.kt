package com.huc.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Toast

/**
 * The home screen itself.
 *
 * A launcher is an ordinary activity that happens to answer CATEGORY_HOME. Once it
 * is the chosen home, the home button lands here instead of on ColorOS, which is
 * why the glass dock can exist at all — the system's own dock is not being hidden,
 * it is simply not on screen any more.
 */
class HomeActivity : Activity(), HomeView.Host {

    companion object {
        private const val REQ_WALL = 71
    }

    private var view: HomeView? = null
    private var pkgWatch: BroadcastReceiver? = null
    private var hinted = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        GStore.init(this)

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val v = HomeView(this)
        v.host = this
        v.deviceDark = isDark()
        setContentView(v)
        view = v

        v.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setInsets(b.top, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            }
            insets
        }

        loadApps()
        watchPackages()
    }

    private fun loadApps() {
        val app = applicationContext
        Thread {
            Apps.load(app)
            runOnUiThread { view?.appsChanged() }
        }.start()
    }

    private fun watchPackages() {
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addDataScheme("package")
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                loadApps()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(r, f)
            }
            pkgWatch = r
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        val r = pkgWatch
        if (r != null) {
            try { unregisterReceiver(r) } catch (_: Throwable) {}
            pkgWatch = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        view?.closeMenu()
        if (!hinted && !isDefaultHome()) {
            hinted = true
            Toast.makeText(
                this,
                "حتى يصير شاشتك الرئيسية: ضغطة مطوّلة على الخلفية ← اختر الشاشة الرئيسية",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Home pressed while already here: shut the sheet, otherwise go back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val v = view ?: return
        if (!v.closeMenu()) v.toTop()
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // a home screen has nowhere to go back to; back only shuts the sheet
        view?.closeMenu()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        v.deviceDark = isDark()
        Wall.invalidate()
        v.wallChanged()
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isDefaultHome(): Boolean {
        return try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            val p = packageManager
                .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            p == packageName
        } catch (_: Throwable) {
            true
        }
    }

    // ---- what the view asks of us -----------------------------------------

    override fun openApp(e: AppEntry) {
        if (!Apps.launch(this, e)) {
            Toast.makeText(this, "ما كدرت أفتح " + e.label, Toast.LENGTH_SHORT).show()
        }
    }

    override fun appInfo(e: AppEntry) {
        Apps.info(this, e)
    }

    override fun pickWall() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            startActivityForResult(i, REQ_WALL)
        } catch (_: Throwable) {
            val g = Intent(Intent.ACTION_GET_CONTENT)
            g.type = "image/*"
            try { startActivityForResult(g, REQ_WALL) } catch (_: Throwable) {}
        }
    }

    override fun forgetWall() {
        Wall.forget(this)
        view?.wallChanged()
    }

    override fun homeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_WALL || res != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        Thread {
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    ok = Wall.adopt(app, ins, 2400)
                }
            } catch (_: Throwable) {
            }
            val done = ok
            runOnUiThread {
                if (done) {
                    view?.wallChanged()
                } else {
                    Toast.makeText(this, "ما كدرت أقرا الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
