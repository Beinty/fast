package com.huc.fasttype

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0E0F11")
    private val CARD = Color.parseColor("#17191C")
    private val TXT = Color.parseColor("#FFFFFF")
    private val MUT = Color.parseColor("#9AA0A6")
    private val ACC = Color.parseColor("#1D9E75")
    private val RED = Color.parseColor("#E24B4A")
    private val WARN = Color.parseColor("#BA7517")
    private val CHIP = Color.parseColor("#13241F")

    private val REQ_EXPORT = 11
    private val REQ_IMPORT = 12
    private val REQ_PERMS = 21

    private val PERMS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )

    private lateinit var status: TextView
    private lateinit var countView: TextView
    private lateinit var list: ListView
    private lateinit var adapter: Adapter

    private lateinit var tabShortcuts: TextView
    private lateinit var tabCaller: TextView
    private lateinit var panelShortcuts: LinearLayout
    private lateinit var panelCaller: ScrollView

    private lateinit var permBanner: TextView
    private lateinit var repeatValue: TextView
    private lateinit var callerNote: TextView
    private lateinit var eventView: TextView

    private var data: MutableList<Shortcut> = mutableListOf()
    private var onCallerTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        data = Store.items.toMutableList()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(16), dp(28), dp(16), dp(12))

        val title = TextView(this)
        title.text = "كتابة سريعة"
        title.setTextColor(TXT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "HUC"
        sub.setTextColor(MUT)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        sub.setPadding(0, dp(2), 0, dp(14))
        root.addView(sub)

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabShortcuts = makeTab("الاختصارات") { showTab(false) }
        tabCaller = makeTab("نطق المتصل") { showTab(true) }
        val tp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp1.marginEnd = dp(4)
        val tp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp2.marginStart = dp(4)
        tabs.addView(tabShortcuts, tp1)
        tabs.addView(tabCaller, tp2)
        root.addView(tabs, lp(true, bottom = dp(14)))

        val content = FrameLayout(this)
        panelShortcuts = buildShortcutsPanel()
        panelCaller = buildCallerPanel()
        content.addView(panelShortcuts)
        content.addView(panelCaller)
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        showTab(false)
        refreshCount()
    }

    // ---------- tab 1 : shortcuts ----------

    private fun buildShortcutsPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.layoutDirection = View.LAYOUT_DIRECTION_RTL

        status = TextView(this)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = round(CARD)
        status.setOnClickListener { openAccessibilitySettings() }
        p.addView(status, lp(true, bottom = dp(10)))

        p.addView(
            switchRow("تشغيل الاستبدال", null, Store.enabled) { Store.setEnabled(this, it) },
            lp(true, bottom = dp(8))
        )
        p.addView(
            switchRow(
                "تبديل فوري",
                "مطفي = يتبدل بعد المسافة أو الانتر",
                Store.instant
            ) { Store.setInstant(this, it) },
            lp(true, bottom = dp(14))
        )

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        countView = TextView(this)
        countView.setTextColor(MUT)
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        bar.addView(
            countView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(smallButton("تصدير") { exportFile() })
        bar.addView(smallButton("استيراد") { importFile() })
        p.addView(bar, lp(true, bottom = dp(8)))

        list = ListView(this)
        list.divider = null
        list.dividerHeight = dp(8)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> editDialog(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ -> deleteDialog(pos); true }
        p.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val add = Button(this)
        add.text = "+  إضافة اختصار"
        add.setTextColor(Color.WHITE)
        add.background = round(ACC)
        add.setOnClickListener { editDialog(-1) }
        p.addView(add, lp(true, top = dp(10)))

        return p
    }

    // ---------- tab 2 : caller announce ----------

    private fun buildCallerPanel(): ScrollView {
        val sv = ScrollView(this)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.layoutDirection = View.LAYOUT_DIRECTION_RTL

        p.addView(
            switchRow(
                "نطق اسم المتصل",
                "يحچي اسم المتصل وقت الرنين",
                Store.callerSpeak
            ) {
                Store.setCallerSpeak(this, it)
                if (it && !permsOk()) requestPermissions(PERMS, REQ_PERMS)
                refreshPermBanner()
            },
            lp(true, bottom = dp(10))
        )

        permBanner = TextView(this)
        permBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        permBanner.setPadding(dp(12), dp(12), dp(12), dp(12))
        permBanner.background = round(CARD)
        permBanner.setOnClickListener {
            if (!permsOk()) requestPermissions(PERMS, REQ_PERMS)
            else openAppDetails()
        }
        p.addView(permBanner, lp(true, bottom = dp(12)))

        val prefixLabel = TextView(this)
        prefixLabel.text = "النص قبل الاسم"
        prefixLabel.setTextColor(MUT)
        prefixLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        prefixLabel.setPadding(dp(4), 0, dp(4), dp(6))
        p.addView(prefixLabel)

        val prefixIn = EditText(this)
        prefixIn.setText(Store.callerPrefix)
        prefixIn.setSingleLine(true)
        prefixIn.setTextColor(TXT)
        prefixIn.background = round(CARD)
        prefixIn.setPadding(dp(12), dp(10), dp(12), dp(10))
        prefixIn.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                Store.setCallerPrefix(this@MainActivity, s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        p.addView(prefixIn, lp(true, bottom = dp(12)))

        val rep = LinearLayout(this)
        rep.orientation = LinearLayout.HORIZONTAL
        rep.gravity = Gravity.CENTER_VERTICAL
        rep.background = round(CARD)
        rep.setPadding(dp(12), dp(10), dp(12), dp(10))

        val repLabel = TextView(this)
        repLabel.text = "عدد مرات التكرار"
        repLabel.setTextColor(TXT)
        repLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        rep.addView(
            repLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val minus = stepButton("−") {
            Store.setCallerRepeat(this, Store.callerRepeat - 1)
            repeatValue.text = Store.callerRepeat.toString()
        }
        repeatValue = TextView(this)
        repeatValue.text = Store.callerRepeat.toString()
        repeatValue.setTextColor(TXT)
        repeatValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        repeatValue.gravity = Gravity.CENTER
        repeatValue.minWidth = dp(28)
        val plus = stepButton("+") {
            Store.setCallerRepeat(this, Store.callerRepeat + 1)
            repeatValue.text = Store.callerRepeat.toString()
        }
        rep.addView(minus)
        rep.addView(repeatValue)
        rep.addView(plus)
        p.addView(rep, lp(true, bottom = dp(8)))

        p.addView(
            switchRow(
                "نطق الرقم إذا مو محفوظ",
                "يقرا الرقم رقم رقم",
                Store.callerSayNumber
            ) { Store.setCallerSayNumber(this, it) },
            lp(true, bottom = dp(8))
        )

        p.addView(
            switchRow(
                "اسكت بالوضع الصامت",
                "ما ينطق إذا الجهاز صامت أو اهتزاز",
                Store.callerRespectSilent
            ) { Store.setCallerRespectSilent(this, it) },
            lp(true, bottom = dp(14))
        )

        val test = Button(this)
        test.text = "تجربة الصوت"
        test.setTextColor(Color.WHITE)
        test.background = round(ACC)
        test.setOnClickListener {
            val prefix = Store.callerPrefix.trim()
            val sample = if (prefix.isEmpty()) "أحمد" else "$prefix أحمد"
            Speaker.test(this, sample) { msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        p.addView(test, lp(true, bottom = dp(12)))

        eventView = TextView(this)
        eventView.setTextColor(MUT)
        eventView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        eventView.setPadding(dp(12), dp(10), dp(12), dp(10))
        eventView.background = round(CARD)
        eventView.setOnClickListener { refreshPermBanner() }
        p.addView(eventView, lp(true, bottom = dp(10)))

        callerNote = TextView(this)
        callerNote.setTextColor(MUT)
        callerNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        callerNote.setPadding(dp(4), 0, dp(4), dp(16))
        p.addView(callerNote, lp(true))

        sv.addView(p)
        return sv
    }

    private fun showTab(caller: Boolean) {
        onCallerTab = caller
        panelShortcuts.visibility = if (caller) View.GONE else View.VISIBLE
        panelCaller.visibility = if (caller) View.VISIBLE else View.GONE
        styleTab(tabShortcuts, !caller)
        styleTab(tabCaller, caller)
        if (caller) refreshPermBanner()
    }

    private fun refreshPermBanner() {
        val ok = permsOk()
        if (ok) {
            permBanner.text = "الأذونات ممنوحة"
            permBanner.setTextColor(ACC)
        } else {
            permBanner.text = "يحتاج إذن الهاتف وجهات الاتصال — اضغط للمنح"
            permBanner.setTextColor(WARN)
        }

        Store.load(this)
        val ev = Store.lastEvent
        eventView.text = if (ev.isBlank())
            "آخر حدث مكالمة: (ما وصل شي بعد) — اضغط هنا للتحديث"
        else
            "آخر حدث مكالمة:\n$ev\n(اضغط للتحديث)"

        callerNote.text = if (isServiceOn())
            "الخدمة شغالة. إذا ما سمعت الاسم، تأكد إن صوت الرنين مرفوع، وإن محرك النطق يدعم العربية من إعدادات النظام ← إمكانية الوصول ← تحويل النص إلى كلام."
        else
            "تنبيه: خدمة إمكانية الوصول مطفية. نطق المتصل ما يشتغل بدونها — شغّلها من تبويب الاختصارات."
    }

    private fun permsOk(): Boolean =
        PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) refreshPermBanner()
    }

    private fun openAppDetails() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    // ---------- shared ----------

    override fun onResume() {
        super.onResume()
        val on = isServiceOn()
        status.text = if (on)
            "الخدمة شغالة — الاستبدال فعّال"
        else
            "الخدمة متوقفة — اضغط هنا لتفعيل إمكانية الوصول"
        status.setTextColor(if (on) ACC else RED)
        if (onCallerTab) refreshPermBanner()
    }

    private fun isServiceOn(): Boolean {
        val id = "$packageName/${ExpanderService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(id, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            toast("ما كدرت أفتح الإعدادات")
        }
    }

    private fun editDialog(pos: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.setPadding(dp(20), dp(12), dp(20), dp(4))

        val tIn = EditText(this)
        tIn.hint = "الاختصار (مثال: ببب)"
        tIn.setSingleLine(true)
        box.addView(tIn)

        val pIn = EditText(this)
        pIn.hint = "الكلمة الكاملة"
        box.addView(pIn)

        if (pos >= 0) {
            tIn.setText(data[pos].trigger)
            pIn.setText(data[pos].phrase)
        }

        AlertDialog.Builder(this)
            .setTitle(if (pos >= 0) "تعديل اختصار" else "اختصار جديد")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val t = tIn.text.toString().trim()
                val p = pIn.text.toString()
                if (t.isEmpty() || p.isEmpty()) {
                    toast("املأ الحقلين")
                    return@setPositiveButton
                }
                val item = Shortcut(t, p, true)
                if (pos >= 0) data[pos] = item else data.add(item)
                persist()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun deleteDialog(pos: Int) {
        AlertDialog.Builder(this)
            .setTitle("حذف")
            .setMessage("تحذف الاختصار \"${data[pos].trigger}\"؟")
            .setPositiveButton("حذف") { _, _ ->
                data.removeAt(pos)
                persist()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun persist() {
        Store.saveItems(this, data)
        adapter.notifyDataSetChanged()
        refreshCount()
    }

    private fun refreshCount() {
        countView.text = "الاختصارات (${data.size})"
    }

    private fun exportFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "fasttype_backup.json")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (resultCode != RESULT_OK) return
        val uri: Uri = intent?.data ?: return
        try {
            if (requestCode == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Store.serialize(data).toByteArray(Charsets.UTF_8))
                }
                toast("تم التصدير")
            } else if (requestCode == REQ_IMPORT) {
                val raw = contentResolver.openInputStream(uri)?.use { inp ->
                    inp.readBytes().toString(Charsets.UTF_8)
                } ?: return
                val incoming = Store.parse(raw)
                if (incoming.isEmpty()) {
                    toast("الملف فارغ أو صيغته غلط")
                    return
                }
                val map = LinkedHashMap<String, Shortcut>()
                for (s in data) map[s.trigger] = s
                for (s in incoming) map[s.trigger] = s
                data = map.values.toMutableList()
                persist()
                toast("تم استيراد ${incoming.size} اختصار")
            }
        } catch (e: Exception) {
            toast("خطأ: ${e.message}")
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(p: Int): Any = data[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.layoutDirection = View.LAYOUT_DIRECTION_RTL
            row.background = round(CARD)
            row.setPadding(dp(12), dp(12), dp(12), dp(12))

            val item = data[p]

            val trig = TextView(this@MainActivity)
            trig.text = item.trigger
            trig.setTextColor(ACC)
            trig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            trig.setPadding(dp(8), dp(3), dp(8), dp(3))
            trig.background = round(CHIP)
            row.addView(trig)

            val arrow = TextView(this@MainActivity)
            arrow.text = "  ←  "
            arrow.setTextColor(MUT)
            row.addView(arrow)

            val phrase = TextView(this@MainActivity)
            phrase.text = item.phrase
            phrase.setTextColor(TXT)
            phrase.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            phrase.maxLines = 1
            phrase.ellipsize = TextUtils.TruncateAt.END
            row.addView(
                phrase,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            return row
        }
    }

    // ---------- small builders ----------

    private fun makeTab(label: String, cb: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.setPadding(0, dp(10), 0, dp(10))
        t.setOnClickListener { cb() }
        return t
    }

    private fun styleTab(t: TextView, active: Boolean) {
        t.background = round(if (active) ACC else CARD)
        t.setTextColor(if (active) Color.WHITE else MUT)
    }

    private fun switchRow(
        label: String,
        subLabel: String?,
        value: Boolean,
        cb: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = round(CARD)
        row.setPadding(dp(12), dp(8), dp(12), dp(8))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val t = TextView(this)
        t.text = label
        t.setTextColor(TXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        col.addView(t)

        if (subLabel != null) {
            val s = TextView(this)
            s.text = subLabel
            s.setTextColor(MUT)
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            col.addView(s)
        }

        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, v -> cb(v) }
        row.addView(sw)
        return row
    }

    private fun stepButton(label: String, cb: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(ACC)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(2), dp(14), dp(2))
        t.setOnClickListener { cb() }
        return t
    }

    private fun smallButton(label: String, cb: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setTextColor(ACC)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        b.background = null
        b.setPadding(dp(8), 0, dp(8), 0)
        b.setOnClickListener { cb() }
        return b
    }

    private fun round(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(10).toFloat()
        return d
    }

    private fun lp(matchW: Boolean, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            if (matchW) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = top
        p.bottomMargin = bottom
        return p
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
