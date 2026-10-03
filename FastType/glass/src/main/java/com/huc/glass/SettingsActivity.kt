package com.huc.glass

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The settings screen — and the icon that appears in the app list.
 *
 * The home screen answers CATEGORY_HOME only, so tapping "Glass" in a drawer brings
 * him here rather than to a home screen he is already looking at.
 */
class SettingsActivity : Activity() {

    private var dark = false
    private var list: LinearLayout? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        GStore.init(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(if (dark) 0xFF0B0D11.toInt() else 0xFFF2F3F7.toInt())
        scroll.isFillViewport = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(40))
        scroll.addView(
            col,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setContentView(scroll)
        list = col

        window.statusBarColor = if (dark) 0xFF0B0D11.toInt() else 0xFFF2F3F7.toInt()
        window.navigationBarColor = if (dark) 0xFF0B0D11.toInt() else 0xFFF2F3F7.toInt()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // ---- the list ----------------------------------------------------------

    private fun rebuild() {
        val col = list ?: return
        col.removeAllViews()

        title(col, getString(R.string.app_name))

        header(col, getString(R.string.sec_speed))
        val card1 = card(col)
        row(
            card1, getString(R.string.battery_title),
            if (batteryExempt()) "✔ " + getString(R.string.battery_desc)
            else getString(R.string.battery_desc)
        ) { askBattery() }
        toggle(
            card1, getString(R.string.keep_title), getString(R.string.keep_desc),
            GStore.keepAlive
        ) { on ->
            GStore.keepAlive = on
            if (on && Build.VERSION.SDK_INT >= 33) askNotifications()
            KeepService.apply(this)
        }
        row(card1, getString(R.string.autostart_title), getString(R.string.autostart_desc)) {
            openAppDetails()
        }
        row(card1, getString(R.string.rebuild_title), getString(R.string.rebuild_desc)) {
            Apps.forgetIcons(this)
            Toast.makeText(this, getString(R.string.rebuild_done), Toast.LENGTH_SHORT).show()
        }

        header(col, getString(R.string.sec_home))
        val card2 = card(col)
        row(
            card2, getString(R.string.default_home),
            if (isDefaultHome()) "✔" else ""
        ) { open(Settings.ACTION_HOME_SETTINGS) }
        toggle(
            card2, getString(R.string.labels_title), getString(R.string.labels_desc),
            GStore.labels
        ) { on -> GStore.labels = on }
        row(card2, getString(R.string.dock_reset), getString(R.string.dock_reset_desc)) {
            GStore.dock = ArrayList()
            Toast.makeText(this, "✔", Toast.LENGTH_SHORT).show()
        }
        row(card2, getString(R.string.wallpaper), "") { pickWallpaper() }

        header(col, getString(R.string.sec_about))
        val card3 = card(col)
        row(card3, getString(R.string.version, versionName()), "") {}
    }

    // ---- building blocks ---------------------------------------------------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private val ink: Int get() = if (dark) 0xFFEEF1F6.toInt() else 0xFF14171C.toInt()
    private val ink2: Int get() = if (dark) 0xFF99A1B1.toInt() else 0xFF697083.toInt()
    private val panel: Int get() = if (dark) 0xFF171A20.toInt() else 0xFFFFFFFF.toInt()
    private val line: Int get() = if (dark) 0x1AFFFFFF else 0x14000000

    private fun title(parent: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(ink)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        t.setPadding(dp(6), 0, dp(6), dp(10))
        parent.addView(t)
    }

    private fun header(parent: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(ink2)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.setPadding(dp(8), dp(18), dp(8), dp(7))
        parent.addView(t)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(panel)
        bg.cornerRadius = dp(16).toFloat()
        bg.setStroke(1, line)
        c.background = bg
        parent.addView(
            c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return c
    }

    private fun divider(parent: LinearLayout) {
        if (parent.childCount == 0) return
        val v = View(this)
        v.setBackgroundColor(line)
        parent.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun texts(title: String, desc: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(ink)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        box.addView(t)
        if (desc.isNotEmpty()) {
            val d = TextView(this)
            d.text = desc
            d.setTextColor(ink2)
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            d.setPadding(0, dp(3), 0, 0)
            box.addView(d)
        }
        return box
    }

    private fun row(parent: LinearLayout, title: String, desc: String, tap: () -> Unit) {
        divider(parent)
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(14), dp(16), dp(14))
        r.isClickable = true
        r.setOnClickListener { tap() }
        val box = texts(title, desc)
        r.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(
            r, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun toggle(
        parent: LinearLayout, title: String, desc: String,
        on: Boolean, changed: (Boolean) -> Unit
    ) {
        divider(parent)
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(14), dp(16), dp(14))
        val box = texts(title, desc)
        r.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        @Suppress("DEPRECATION")
        val sw = Switch(this)
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v -> changed(v) }
        r.addView(sw)
        r.isClickable = true
        r.setOnClickListener { sw.isChecked = !sw.isChecked }
        parent.addView(
            r, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ---- actions -----------------------------------------------------------

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Throwable) {
            "1.0"
        }
    }

    private fun batteryExempt(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("BatteryLife")
    private fun askBattery() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + packageName)
            startActivity(i)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                openAppDetails()
            }
        }
    }

    private fun askNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun openAppDetails() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + packageName)
            startActivity(i)
        } catch (_: Throwable) {
        }
    }

    private fun pickWallpaper() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), ""))
        } catch (_: Throwable) {
        }
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
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
            false
        }
    }
}
