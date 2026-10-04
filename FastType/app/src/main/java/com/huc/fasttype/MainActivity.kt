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
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

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
    private val REQ_PICS = 22

    private fun picsOk(): Boolean = Pics.allowed(this)

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
    private lateinit var tabKb: TextView
    private lateinit var panelKb: LinearLayout
    private var kbPreview: KeyboardView? = null
    private var kbOuterRow: View? = null
    private var voiceReport: TextView? = null
    private var googleBtn: View? = null

    private lateinit var permBanner: TextView
    private lateinit var repeatValue: TextView
    private lateinit var callerNote: TextView
    private lateinit var eventView: TextView

    private var data: MutableList<Shortcut> = mutableListOf()
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        UserDict.load(this)
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
        tabShortcuts = makeTab("الاختصارات") { showTab(0) }
        tabCaller = makeTab("نطق المتصل") { showTab(1) }
        tabKb = makeTab("الكيبورد") { showTab(2) }
        val tp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp1.marginEnd = dp(4)
        val tp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp2.marginStart = dp(4)
        val tp3 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp3.marginStart = dp(4)
        tabs.addView(tabShortcuts, tp1)
        tabs.addView(tabCaller, tp2)
        tabs.addView(tabKb, tp3)
        root.addView(tabs, lp(true, bottom = dp(14)))

        val content = FrameLayout(this)
        panelShortcuts = buildShortcutsPanel()
        panelCaller = buildCallerPanel()
        panelKb = buildKbPanel()
        content.addView(panelShortcuts)
        content.addView(panelCaller)
        content.addView(panelKb)
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        showTab(0)
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

        val voiceRow = LinearLayout(this)
        voiceRow.orientation = LinearLayout.HORIZONTAL
        voiceRow.gravity = Gravity.CENTER_VERTICAL
        voiceRow.background = round(CARD)
        voiceRow.setPadding(dp(12), dp(12), dp(12), dp(12))
        val voiceLabel = TextView(this)
        voiceLabel.text = "اختيار الصوت"
        voiceLabel.setTextColor(TXT)
        voiceLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        voiceRow.addView(
            voiceLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val voiceArrow = TextView(this)
        voiceArrow.text = "اختر ›"
        voiceArrow.setTextColor(ACC)
        voiceArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        voiceRow.addView(voiceArrow)
        voiceRow.setOnClickListener { pickVoice() }
        p.addView(voiceRow, lp(true, bottom = dp(8)))

        p.addView(
            sliderRow("سرعة الكلام", Store.callerRate) { v ->
                Store.setCallerRate(this, v)
                previewVoice()
            },
            lp(true, bottom = dp(8))
        )

        p.addView(
            sliderRow("نبرة الصوت", Store.callerPitch) { v ->
                Store.setCallerPitch(this, v)
                previewVoice()
            },
            lp(true, bottom = dp(8))
        )

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

    private fun showTab(which: Int) {
        tab = which
        panelShortcuts.visibility = if (which == 0) View.VISIBLE else View.GONE
        panelCaller.visibility = if (which == 1) View.VISIBLE else View.GONE
        panelKb.visibility = if (which == 2) View.VISIBLE else View.GONE
        styleTab(tabShortcuts, which == 0)
        styleTab(tabCaller, which == 1)
        styleTab(tabKb, which == 2)
        if (which == 1) refreshPermBanner()
        if (which == 2) refreshKbBanner()
    }

    private fun refreshPermBanner() {
        if (!::permBanner.isInitialized) return
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
        if (requestCode == REQ_PICS && !picsOk()) {
            // he said no; the switch must not sit there claiming it is on
            Store.setKbFlag(this, "pics", false)
            recreate()
        }
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
        if (tab == 1) refreshPermBanner()
        if (tab == 2) refreshKbBanner()
    }

    private fun isServiceOn(): Boolean {
        return try {
            val id = "$packageName/${ExpanderService::class.java.name}"
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { it.equals(id, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
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


    // ---------- tab 3 : keyboard ----------

    private lateinit var kbBanner: TextView

    private fun buildKbPanel(): LinearLayout {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val sv = ScrollView(this)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.layoutDirection = View.LAYOUT_DIRECTION_RTL

        kbBanner = TextView(this)
        kbBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        kbBanner.setPadding(dp(12), dp(12), dp(12), dp(12))
        kbBanner.background = round(CARD)
        kbBanner.setOnClickListener { openKbSettings() }
        p.addView(kbBanner, lp(true, bottom = dp(8)))

        val pick = Button(this)
        pick.text = "اختيار الكيبورد الافتراضي"
        pick.setTextColor(Color.WHITE)
        pick.background = round(ACC)
        pick.setOnClickListener {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) {
                toast("ما كدرت أفتح القائمة")
            }
        }
        p.addView(pick, lp(true, bottom = dp(14)))

        // ---- voice diagnostics, shown here because ColorOS hides app logs ----
        val vd = TextView(this)
        vd.setTextColor(MUT)
        vd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        vd.setPadding(dp(12), dp(12), dp(12), dp(12))
        vd.background = round(CARD)
        vd.setLineSpacing(dp(3).toFloat(), 1f)
        voiceReport = vd
        refreshVoiceReport()
        p.addView(vd, lp(true, bottom = dp(8)))

        val vb = Button(this)
        vb.text = "فحص المايك من جديد"
        vb.isAllCaps = false
        vb.setTextColor(TXT)
        vb.background = round(CARD)
        vb.setOnClickListener {
            val v = Voice(this)
            if (!v.hasPermission()) v.askPermission()
            v.rescan()
            refreshVoiceReport()
        }
        p.addView(vb, lp(true, bottom = dp(8)))

        val gb = Button(this)
        gb.text = "نزّل تطبيق Google (للإدخال الصوتي)"
        gb.isAllCaps = false
        gb.setTextColor(Color.WHITE)
        gb.background = round(ACC)
        gb.visibility = if (Voice(this).hasRealEngine()) View.GONE else View.VISIBLE
        googleBtn = gb
        gb.setOnClickListener { openStore(Voice.GOOGLE) }
        p.addView(gb, lp(true, bottom = dp(14)))

        val tl = TextView(this)
        tl.text = "الثيم"
        tl.setTextColor(MUT)
        tl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tl.setPadding(dp(4), 0, dp(4), dp(6))
        p.addView(tl)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        var rowBox: LinearLayout? = null
        Themes.all.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                rowBox = LinearLayout(this)
                rowBox!!.orientation = LinearLayout.HORIZONTAL
                val rp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rp.bottomMargin = dp(6)
                grid.addView(rowBox, rp)
            }
            val b = Button(this)
            b.text = t.name
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            b.setTextColor(if (Store.kbTheme == t.id) Color.WHITE else TXT)
            b.background = round(if (Store.kbTheme == t.id) ACC else CARD)
            b.setOnClickListener {
                Store.setKbTheme(this, t.id)
                refreshThemeButtons(grid)
                syncPreview()
                toast("تم اختيار: ${t.name}")
            }
            b.tag = t.id
            val bp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 2 == 0) bp.marginEnd = dp(3) else bp.marginStart = dp(3)
            rowBox!!.addView(b, bp)
        }
        p.addView(grid, lp(true, bottom = dp(14)))

        p.addView(sliderRow("ارتفاع الزر", Store.kbKeyHeight, 34, 58) {
            Store.setKbInt(this, "h", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("المسافة بين الأزرار", Store.kbGap, 2, 10) {
            Store.setKbInt(this, "gap", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("دوران زوايا الأزرار", Store.kbRadius, 2, 18) {
            Store.setKbInt(this, "rad", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("إطار اللوحة (صفر = لحافة الشاشة)", Store.kbInset, 0, 14) {
            Store.setKbInt(this, "inset", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("انحناء أعلى اللوحة", Store.kbPanelRadius, 0, 44) {
            Store.setKbInt(this, "prad", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("دوران شريط الاقتراحات (صفر = مسطّح)", Store.kbSuggRad, 0, 22) {
            Store.setKbInt(this, "srad", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("ارتفاع شريط الاقتراحات", Store.kbSuggH, 22, 60) {
            Store.setKbInt(this, "sh", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("طول الخط الفاصل", Store.kbHairH, 20, 90) {
            Store.setKbInt(this, "hairh", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("سماكة الخط الفاصل", Store.kbHairW, 1, 4) {
            Store.setKbInt(this, "hairw", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("حجم الحرف", Store.kbLetter, 40, 58) {
            Store.setKbInt(this, "letter", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        val outerRow = sliderRow("ارتفاع الشريط السفلي", Store.kbOuterH, 0, 60) {
            Store.setKbInt(this, "outer", it); syncPreview()
        }
        outerRow.visibility = if (Store.kbGlobeRow) View.GONE else View.VISIBLE
        kbOuterRow = outerRow
        p.addView(outerRow, lp(true, bottom = dp(8)))
        p.addView(sliderRow("المسافة من أسفل الشاشة", Store.kbBottomPad, 0, 48) {
            Store.setKbInt(this, "bottom", it); syncPreview()
        }, lp(true, bottom = dp(14)))

        p.addView(switchRow("التنبؤات", "يقترح كلمات وأنت تكتب، ودوس عليها لتنكتب",
            Store.kbPredict) {
            Store.setKbFlag(this, "predict", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("التصحيح التلقائي", "يصحّح الكلمة الغلط عند المسافة — ورجعة وحدة تلغيه",
            Store.kbCorrect) {
            Store.setKbFlag(this, "correct", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow(
            "يتعلّم من كتابتك",
            "يحفظ الكلمات اللي تكتبها ويقدّمها، وما يصحّح كلمة ترفض تصحيحها — تعلّم ${UserDict.learned()} كلمة",
            Store.kbLearn
        ) {
            Store.setKbFlag(this, "learn", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("الخطان الفاصلان", "يقسّمان الشريط ثلاث خانات مثل الآيفون",
            Store.kbHair) {
            Store.setKbFlag(this, "hair", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("المايك بالشريط العلوي", "بدل الشريط السفلي", Store.kbMicStrip) {
            Store.setKbFlag(this, "micstrip", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("زر اللغة جنب الإيموجي", "ينشال الشريط السفلي ويقصر الكيبورد",
            Store.kbGlobeRow) {
            Store.setKbFlag(this, "globerow", it)
            if (!it && Store.kbOuterH == 0) Store.setKbInt(this, "outer", 40)
            kbOuterRow?.visibility = if (it) View.GONE else View.VISIBLE
            syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("المسافة السفلية شفافة",
            "مطفي = المسافة تحت الأزرار بلون الكيبورد",
            Store.kbClearBottom) {
            Store.setKbFlag(this, "clear", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("بدائل الحروف بضغطة مطوّلة",
            "اهبط على ا تطلع أ إ آ — وعلى ج تطلع چ. اسحب وارفع",
            Store.kbAlts) {
            Store.setKbFlag(this, "alts", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("مسافتين = نقطة",
            "ضغطتين سريعتين على المسافة تحطّ نقطة ومسافة",
            Store.kbDoubleSpace) {
            Store.setKbFlag(this, "dots", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("زر الحافظة",
            "ضغطة تلصق آخر نسخة، وضغطة مطوّلة تفتح كل اللي نسخته",
            Store.kbClip) {
            Store.setKbFlag(this, "clip", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("الصور بالحافظة",
            "آخر لقطة شاشة أو صورة نسختها تقعد بزر الحافظة — ضغطة وحدة تدزّها. يحتاج إذن قراءة الصور مرّة وحدة",
            Store.kbPics) {
            Store.setKbFlag(this, "pics", it)
            if (it && !picsOk()) requestPermissions(arrayOf(Pics.permission()), REQ_PICS)
            syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(sliderRow("مسح الحافظة بعد (دقيقة، صفر = تبقى)",
            Store.kbClipExpire, 0, 720) {
            Store.setKbInt(this, "clipexp", it)
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("يتبع ثيم الجهاز",
            "التليفون ليلي؟ الكيبورد ليلي. نهاري؟ نهاري — بنفس الثيم اللي اخترته",
            Store.kbFollowSystem) {
            Store.setKbFlag(this, "follow", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("إخفاء الحروف بضغطة مطوّلة",
            "دوس مطوّلاً على المسافة تختفي الحروف — وترجع بأي ضغطة",
            Store.kbBlankHold) {
            Store.setKbFlag(this, "blank", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("تظليل الزر عند الضغط", "طفّيه لأسرع استجابة ممكنة",
            Store.kbPressFx) {
            Store.setKbFlag(this, "pressfx", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("صف الأرقام", "صف فوق الحروف", Store.kbNumberRow) {
            Store.setKbFlag(this, "num", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("شريط الاقتراحات", "يعرض الاختصار قبل التبديل", Store.kbSuggBar) {
            Store.setKbFlag(this, "sugg", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("يبدي بالعربي", "لغة الكيبورد عند الفتح", Store.kbArabicFirst) {
            Store.setKbFlag(this, "arfirst", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("صوت الضغط", null, Store.kbSound) {
            Store.setKbFlag(this, "sound", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("اهتزاز الضغط", null, Store.kbVibrate) {
            Store.setKbFlag(this, "vib", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("لمسة فورية", "الحرف ينكتب لحظة اللمس مو عند الرفع", Store.kbFast) {
            Store.setKbFlag(this, "fast", it); syncPreview()
        }, lp(true, bottom = dp(14)))

        p.addView(switchRow("الاختصارات داخل الكيبورد", "بدون خدمة إمكانية الوصول", Store.kbExpand) {
            Store.setKbFlag(this, "expand", it); syncPreview()
        }, lp(true, bottom = dp(8)))
        p.addView(switchRow("تبديل فوري", "مطفي = يتبدل بعد المسافة", Store.kbExpandInstant) {
            Store.setKbFlag(this, "inst", it); syncPreview()
        }, lp(true, bottom = dp(16)))

        val note = TextView(this)
        note.text = "لما تستخدم كيبورد HUC، الاختصارات تشتغل من داخله مباشرة — " +
            "وما تحتاج خدمة إمكانية الوصول أبداً. خدمة إمكانية الوصول تبقى " +
            "للاختصارات مع الكيبوردات الثانية ولنطق المتصل."
        note.setTextColor(MUT)
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        note.setPadding(dp(4), 0, dp(4), dp(16))
        p.addView(note, lp(true))

        sv.addView(p)
        wrap.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pvLabel = TextView(this)
        pvLabel.text = "معاينة حية — نفس الكيبورد الحقيقي"
        pvLabel.setTextColor(MUT)
        pvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        pvLabel.setPadding(dp(4), dp(8), dp(4), dp(6))
        wrap.addView(pvLabel, lp(true))

        val pv = KeyboardView(this)
        pv.listener = object : KeyboardView.Listener {
            override fun onChar(s: String) {}
            override fun onDelete() {}
            override fun onEnter() {}
            override fun onShift() {
                pv.shift = if (pv.shift > 0) 0 else 1
                pv.rebuild()
            }
            override fun onLang() { pv.arabic = !pv.arabic; pv.rebuild() }
            override fun onPage(page: Int) { pv.page = page; pv.rebuild() }
            override fun onSuggestionTap() {}
            override fun onPredictionTap(index: Int) {}
            override fun onMic() {}
            override fun onClipTap() {}
            override fun onClipHold() {}
            override fun onClipPick(index: Int) {}
            override fun onClipClose() {}
            override fun onDeleteWord() {}
            override fun onRepeatState(active: Boolean) {}
            // the preview shows the strip; none of its tools do anything here
            override fun onTool(which: Int) {}
            override fun onTransClose() {}
            override fun onTransSwap() {}
            override fun onTransLang(dst: Boolean) {}
            override fun onLangPick(code: String) {}
            override fun onPicPick(index: Int) {}
        }
        pv.arabic = Store.kbArabicFirst
        pv.suggText = "ببب  ←  بسم الله الرحمن الرحيم"
        pv.suggs = listOf("\u201Cببب\u201D", "بسم الله الرحمن الرحيم", "بسم")
        pv.applySettings()
        pv.rebuild()
        kbPreview = pv
        wrap.addView(pv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return wrap
    }

    /** Re-reads the saved settings into the preview so every change shows at once. */
    /** Re-reads what the phone offers for voice input and shows it plainly. */
    private fun refreshVoiceReport() {
        val t = voiceReport ?: return
        val v = Voice(this)
        t.text = try {
            "حالة الإدخال الصوتي\n" + v.report()
        } catch (e: Exception) {
            "ما كدرت أفحص: " + e.message
        }
        googleBtn?.visibility = try {
            if (v.hasRealEngine()) View.GONE else View.VISIBLE
        } catch (e: Exception) {
            View.VISIBLE
        }
    }

    /** Opens a package's page, in the Play Store when it is there. */
    private fun openStore(pkg: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                toast("ما كدرت أفتح المتجر")
            }
        }
    }

    private fun syncPreview() {
        val pv = kbPreview ?: return
        Store.load(this)
        pv.applySettings()
        pv.rebuild()
    }

    private fun refreshThemeButtons(grid: LinearLayout) {
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val b = row.getChildAt(j) as? Button ?: continue
                val on = b.tag == Store.kbTheme
                b.setTextColor(if (on) Color.WHITE else TXT)
                b.background = round(if (on) ACC else CARD)
            }
        }
    }

    private fun refreshKbBanner() {
        if (!::kbBanner.isInitialized) return
        val on = isKbEnabled()
        val def = isKbDefault()
        when {
            def -> { kbBanner.text = "كيبورد HUC فعّال ومختار"; kbBanner.setTextColor(ACC) }
            on -> { kbBanner.text = "مفعّل بالنظام — اضغط لاختياره كيبورد افتراضي"; kbBanner.setTextColor(WARN) }
            else -> { kbBanner.text = "غير مفعّل — اضغط هنا لتفعيله من إعدادات النظام"; kbBanner.setTextColor(RED) }
        }
    }

    /** Uses the documented InputMethodManager API; some OEM builds refuse the raw Secure read. */
    private fun isKbEnabled(): Boolean {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun isKbDefault(): Boolean {
        return try {
            val cur = Settings.Secure.getString(
                contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            )
            cur != null && cur.startsWith(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun openKbSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            toast("ما كدرت أفتح الإعدادات")
        }
    }

    private fun sliderRow(label: String, value: Int, lo: Int, hi: Int, cb: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = round(CARD)
        row.setPadding(dp(12), dp(10), dp(12), dp(6))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val t = TextView(this)
        t.text = label
        t.setTextColor(TXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val vv = TextView(this)
        vv.text = value.toString()
        vv.setTextColor(MUT)
        vv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        head.addView(vv)
        row.addView(head)

        val bar = SeekBar(this)
        bar.max = hi - lo
        bar.progress = (value - lo).coerceIn(0, hi - lo)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                vv.text = (lo + pr).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                cb(lo + (sb?.progress ?: 0))
            }
        })
        row.addView(bar, lp(true))
        return row
    }

    // ---------- small builders ----------

    private fun previewVoice() {
        val prefix = Store.callerPrefix.trim()
        val sample = if (prefix.isEmpty()) "أحمد" else "$prefix أحمد"
        Speaker.preview(this, sample)
    }

    private fun pickVoice() {
        toast("جاري قراءة الأصوات المتاحة…")
        Speaker.arabicVoices(this) { names ->
            runOnUiThread {
                if (names.isEmpty()) {
                    toast("ما لكيت أصوات عربية — تأكد إن محرك Google هو المحرك المفضل")
                    return@runOnUiThread
                }
                val labels = names.mapIndexed { i, n ->
                    val kind = if (n.contains("network", true)) "إنترنت" else "محلي"
                    "صوت ${i + 1}  ($kind)"
                }.toTypedArray()
                val current = names.indexOf(Store.callerVoice)
                AlertDialog.Builder(this)
                    .setTitle("اختر الصوت — اضغط لتسمعه")
                    .setSingleChoiceItems(labels, current) { _, which ->
                        Store.setCallerVoice(this, names[which])
                        previewVoice()
                    }
                    .setPositiveButton("تم", null)
                    .setNeutralButton("الافتراضي") { _, _ ->
                        Store.setCallerVoice(this, "")
                        previewVoice()
                    }
                    .show()
            }
        }
    }

    private fun sliderRow(label: String, value: Float, cb: (Float) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = round(CARD)
        row.setPadding(dp(12), dp(10), dp(12), dp(6))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val t = TextView(this)
        t.text = label
        t.setTextColor(TXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val valView = TextView(this)
        valView.setTextColor(MUT)
        valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        valView.text = String.format(Locale.US, "%.1f", value)
        head.addView(valView)
        row.addView(head)

        val bar = SeekBar(this)
        bar.max = 150
        bar.progress = ((value - 0.5f) * 100f).toInt().coerceIn(0, 150)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valView.text = String.format(Locale.US, "%.1f", 0.5f + p / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                cb(0.5f + (sb?.progress ?: 50) / 100f)
            }
        })
        row.addView(bar, lp(true))
        return row
    }

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
