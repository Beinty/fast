package com.huc.fasttype

import android.graphics.Color

/** One keyboard colour scheme. All values are resolved ARGB ints. */
class KbTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val panel: Int,
    val panelEdge: Int,
    val sugg: Int,
    val key: Int,
    val keyDown: Int,
    val keyDark: Int,
    val go: Int,
    val goIcon: Int,
    val text: Int,
    val dim: Int,
    val onBg: Int,
    val onText: Int,
    val outer: Int,
    val lightKeys: Boolean = false
)

object Themes {

    private fun c(s: String) = Color.parseColor(s)

    /**
     * Colour of the thin dividers in the prediction strip. Measured off a real iOS
     * screenshot: #D0D1D5 over a #E3E4E7 panel, i.e. the text colour at about 8%.
     */
    fun hairline(t: KbTheme): Int {
        val a = 0.085f
        val r = (Color.red(t.panel) * (1 - a) + Color.red(t.text) * a).toInt()
        val g = (Color.green(t.panel) * (1 - a) + Color.green(t.text) * a).toInt()
        val b = (Color.blue(t.panel) * (1 - a) + Color.blue(t.text) * a).toInt()
        return Color.rgb(r, g, b)
    }

    val all: List<KbTheme> = listOf(
        // All three measured off a real iOS screenshot: every key white, only the
        // action key tinted. They differ only in how dark the panel behind them is.
        KbTheme(
            "iosLight", "iOS — المقيس",
            c("#E3E4E7"), c("#E3E4E7"), c("#E3E4E7"), c("#E3E4E7"),
            c("#FFFFFF"), c("#D4D5D9"), c("#FFFFFF"),
            c("#3478F7"), c("#FFFFFF"),
            c("#000000"), c("#8A8A8E"), c("#000000"), c("#FFFFFF"), c("#3C3C43"),
            lightKeys = true
        ),
        KbTheme(
            "iosCrisp", "iOS — أوضح",
            c("#D7D9DE"), c("#D7D9DE"), c("#D7D9DE"), c("#D7D9DE"),
            c("#FFFFFF"), c("#E9EAEE"), c("#FFFFFF"),
            c("#3478F7"), c("#FFFFFF"),
            c("#000000"), c("#6C6E74"), c("#000000"), c("#FFFFFF"), c("#2E3036"),
            lightKeys = true
        ),
        KbTheme(
            "iosDeep", "iOS — أقوى",
            c("#C9CCD3"), c("#C9CCD3"), c("#C9CCD3"), c("#C9CCD3"),
            c("#FFFFFF"), c("#E6E7EB"), c("#FFFFFF"),
            c("#2A6AE8"), c("#FFFFFF"),
            c("#000000"), c("#5A5C62"), c("#000000"), c("#FFFFFF"), c("#25272C"),
            lightKeys = true
        ),
        KbTheme(
            "iosDark", "iOS ليلي",
            c("#1C1C1E"), c("#1C1C1E"), c("#1C1C1E"), c("#1C1C1E"),
            c("#4A4A4C"), c("#6B6B6E"), c("#4A4A4C"),
            c("#3478F7"), c("#FFFFFF"),
            c("#FFFFFF"), c("#8A8A8E"), c("#FFFFFF"), c("#1C1C1E"), c("#D1D1D6")
        ),
        KbTheme(
            "black", "أسود",
            c("#000000"), c("#0B0B0B"), c("#1E1E1E"), c("#1A1A1A"),
            c("#4B4B4B"), c("#6A6A6A"), c("#1C1C1C"),
            c("#6EA2F0"), c("#0A1A30"),
            c("#FFFFFF"), c("#8D8D8D"), c("#FFFFFF"), c("#000000"), c("#CFCFCF")
        ),
        KbTheme(
            "charcoal", "فحمي",
            c("#141414"), c("#1D1D1F"), c("#2B2B2E"), c("#2A2A2D"),
            c("#3A3A3C"), c("#58585A"), c("#242426"),
            c("#4F7FD4"), c("#FFFFFF"),
            c("#F2F2F2"), c("#8F8F8F"), c("#F2F2F2"), c("#111111"), c("#D6D6D6")
        ),
        KbTheme(
            "midnight", "أزرق ليلي",
            c("#0B1220"), c("#121B2C"), c("#1F2B42"), c("#1A2436"),
            c("#2A3850"), c("#3E5070"), c("#161F31"),
            c("#4F7FD4"), c("#FFFFFF"),
            c("#E8EEF8"), c("#7D8AA0"), c("#E8EEF8"), c("#0B1220"), c("#AEBBD0")
        ),
        KbTheme(
            "huc", "HUC",
            c("#0A0F0D"), c("#101814"), c("#1B2A23"), c("#16211D"),
            c("#1F2F29"), c("#2F473E"), c("#131D19"),
            c("#1D9E75"), c("#FFFFFF"),
            c("#E9F2EE"), c("#7D9089"), c("#4FBF9C"), c("#06110D"), c("#A9BDB6")
        ),
        KbTheme(
            "plum", "بنفسجي",
            c("#120B18"), c("#1A1023"), c("#2A1A36"), c("#241630"),
            c("#2E1F3C"), c("#453058"), c("#1B1023"),
            c("#8A5CC0"), c("#FFFFFF"),
            c("#F0E8F6"), c("#9985A6"), c("#D9BDF0"), c("#1A0F22"), c("#C0ABCD")
        ),
        KbTheme(
            "light", "نهاري",
            c("#FFFFFF"), c("#E4E5E9"), c("#D2D4DA"), c("#FFFFFF"),
            c("#FFFFFF"), c("#E0E1E5"), c("#B4B8C0"),
            c("#2A6AD4"), c("#FFFFFF"),
            c("#111111"), c("#7C7C80"), c("#111111"), c("#FFFFFF"), c("#3C3C43"),
            lightKeys = true
        ),
        KbTheme(
            "glassDark", "زجاج غامق",
            c("#0D0D0D"), c("#1C1C1C"), c("#333333"), c("#242424"),
            c("#2E2E2E"), c("#4D4D4D"), c("#1A1A1A"),
            c("#E0E0E0"), c("#000000"),
            c("#FFFFFF"), c("#9A9A9A"), c("#FFFFFF"), c("#000000"), c("#E3E3E3")
        ),
        KbTheme(
            "glassLight", "زجاج فاتح",
            c("#F2F2F2"), c("#E8E8E8"), c("#FFFFFF"), c("#F6F6F6"),
            c("#FBFBFB"), c("#E2E2E2"), c("#CFCFCF"),
            c("#161616"), c("#FFFFFF"),
            c("#0E0E0E"), c("#6E6E6E"), c("#161616"), c("#FFFFFF"), c("#2B2B2B"),
            lightKeys = true
        )
    )

    fun byId(id: String): KbTheme = all.firstOrNull { it.id == id } ?: all[0]
}
