package com.huc.fasttype

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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

    private val REQ_EXPORT = 11
    private val REQ_IMPORT = 12

    private lateinit var status: TextView
    private lateinit var countView: TextView
    private lateinit var list: ListView
    private lateinit var adapter: Adapter

    private var data: MutableList<Shortcut> = mutableListOf()

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
        sub.text = "HUC — استبدال الاختصارات داخل أي تطبيق"
        sub.setTextColor(MUT)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        sub.setPadding(0, dp(2), 0, dp(14))
        root.addView(sub)

        status = TextView(this)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = round(CARD)
        status.setOnClickListener { openAccessibilitySettings() }
        root.addView(status, lp(matchW = true, bottom = dp(10)))

        val swEnabled = switchRow("تشغيل الاستبدال", Store.enabled) { v ->
            Store.setEnabled(this, v)
        }
        root.addView(swEnabled, lp(matchW = true, bottom = dp(8)))

        val swInstant = switchRow("تبديل فوري (بدون انتظار المسافة)", Store.instant) { v ->
            Store.setInstant(this, v)
        }
        root.addView(swInstant, lp(matchW = true, bottom = dp(14)))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        countView = TextView(this)
        countView.setTextColor(MUT)
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        bar.addView(countView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(smallButton("تصدير") { exportFile() })
        bar.addView(smallButton("استيراد") { importFile() })
        root.addView(bar, lp(matchW = true, bottom = dp(8)))

        list = ListView(this)
        list.divider = null
        list.dividerHeight = dp(8)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> editDialog(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ -> deleteDialog(pos); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val add = Button(this)
        add.text = "+  إضافة اختصار"
        add.setTextColor(Color.WHITE)
        add.background = round(ACC)
        add.setOnClickListener { editDialog(-1) }
        root.addView(add, lp(matchW = true, top = dp(10)))

        setContentView(root)
        refreshCount()
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceOn()
        status.text = if (on)
            "الخدمة شغالة — الاستبدال فعّال"
        else
            "الخدمة متوقفة — اضغط هنا لتفعيل إمكانية الوصول"
        status.setTextColor(if (on) ACC else RED)
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
        } catch (e: Exception) {
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
            trig.background = round(Color.parseColor("#13241F"))
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

    private fun switchRow(label: String, value: Boolean, cb: (Boolean) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = round(CARD)
        row.setPadding(dp(12), dp(6), dp(12), dp(6))

        val t = TextView(this)
        t.text = label
        t.setTextColor(TXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, v -> cb(v) }
        row.addView(sw)
        return row
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
