package com.huc.glass

import android.app.Activity
import android.app.WallpaperManager
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
 * The window is set to show the live wallpaper, which does two jobs at once: his real
 * wallpaper is what appears, with no permission to read it, and there is no black
 * frame when an app closes — the wallpaper is already on screen before anything of
 * ours has been drawn.
 */
class HomeActivity : Activity(), HomeView.Host {

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

        // the remembered list first: the screen is complete before PackageManager
        // has even been asked, which is what removes the wait after leaving an app
        Apps.loadCached(this)

        val v = HomeView(this)
        v.host = this
        v.lightWall = wallpaperIsPale()
        setContentView(v)
        view = v
        if (Apps.ready) v.appsChanged()

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
        KeepService.apply(this)
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
        view?.appsChanged()
        view?.lightWall = wallpaperIsPale()
        view?.invalidate()
        if (!hinted && !isDefaultHome()) {
            hinted = true
            Toast.makeText(
                this,
                getString(R.string.hint_default),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val v = view ?: return
        if (!v.closeMenu()) v.toFirstPage()
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        view?.closeMenu()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.lightWall = wallpaperIsPale()
        view?.invalidate()
    }

    /** Pale wallpaper means the dock has to darken rather than lighten. */
    private fun wallpaperIsPale(): Boolean {
        if (Build.VERSION.SDK_INT < 27) return false
        return try {
            val wc = WallpaperManager.getInstance(this)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return false
            val p = wc.primaryColor.toArgb()
            (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) > 150.0
        } catch (_: Throwable) {
            false
        }
    }

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
            Toast.makeText(this, getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show()
        }
    }

    override fun appInfo(e: AppEntry) {
        Apps.info(this, e)
    }

    override fun pickWallpaper() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    override fun homeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    override fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (_: Throwable) {
        }
    }

    override fun search() {
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    /** Slides the live wallpaper along with the pages, the way a launcher should. */
    override fun pageOffset(fraction: Float) {
        val v = view ?: return
        val token = v.windowToken ?: return
        try {
            WallpaperManager.getInstance(this).setWallpaperOffsets(token, fraction, 0f)
        } catch (_: Throwable) {
        }
    }
}
