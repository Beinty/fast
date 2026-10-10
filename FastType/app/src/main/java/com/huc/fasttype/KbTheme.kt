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
    val lightKeys: Boolean = false,
    /** True for the dark half of a pair. */
    val dark: Boolean = false,
    /** The id of this theme's opposite number, used when following the device. */
    val twin: String = "",

    // ---- how a key is told apart from a background of its own colour ----
    //
    // A white key on a white panel has no edge of its own, so one has to be drawn.
    // These three are the only ways to do it that a canvas can draw cheaply, and
    // each gives a different kind of keyboard: a hairline is flat and
    // architectural, a shadow makes the keys sit on top of the surface, and a
    // carve makes them sit inside it. Zero means "not this one".

    /** A hairline drawn around every key. */
    val edge: Int = 0,
    /** A shadow under every key: the keys look lifted off the panel. */
    val lift: Int = 0,
    /** A line inside the top of every key: the keys look pressed into the panel. */
    val carve: Int = 0,
    /** The light along the bottom of a carved key, which is what sells it. */
    val carveLight: Int = 0,

    // ---- glass ----
    //
    // Glass is not a colour, it is three things together: the app showing
    // through, a bright line catching the light along the top of every key, and
    // a soft shadow under it. A theme that sets these turns transparency on by
    // itself when it is picked, at the depth that suits it — a milky theme wants
    // far less of it than a clear one, and asking him to find that number with a
    // slider every time is how the settings got into the state they are in.

    /** This theme is glass: picking it switches transparency on. */
    val glass: Boolean = false,
    /** How solid the panel is, 20..100. Zero leaves his own setting alone. */
    val glassPanel: Int = 0,
    /** How solid the keys are, 20..100. */
    val glassKey: Int = 0,
    /** The light along the top inside edge of every key. */
    val sheen: Int = 0,

    // ---- depth ----
    //
    // A keyboard painted in flat colours is the same keyboard every time, and
    // no amount of choosing nicer colours changes that. These four are what a
    // surface actually has: a gradient down the panel, a gradient inside the
    // key, soft fields of colour behind the keys, and a halo on the one key
    // that is allowed to be bright.

    /** A vertical gradient for the panel; it replaces the flat [panel]. */
    val panelTop: Int = 0,
    val panelBottom: Int = 0,
    /** A vertical gradient inside every letter key. */
    val keyTop: Int = 0,
    val keyBottom: Int = 0,
    /** Colour fields behind the keys: colour, then centre and radius as fractions. */
    val aura1: Int = 0,
    val aura1x: Float = 0f,
    val aura1y: Float = 0f,
    val aura1r: Float = 0f,
    val aura2: Int = 0,
    val aura2x: Float = 0f,
    val aura2y: Float = 0f,
    val aura2r: Float = 0f,
    /** A halo around the enter key. */
    val glow: Int = 0,
    /** Shown at the top of the list, under its own heading. */
    val star: Boolean = false
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
        KbTheme(
            "iosCrisp", "iOS — أوضح",
            c("#D7D9DE"), c("#D7D9DE"), c("#D7D9DE"), c("#D7D9DE"),
            c("#FFFFFF"), c("#E9EAEE"), c("#FFFFFF"),
            c("#3478F7"), c("#FFFFFF"),
            c("#000000"), c("#6C6E74"), c("#000000"), c("#FFFFFF"), c("#2E3036"),
            lightKeys = true, twin = "iosDark"
        ),
        KbTheme(
            "iosLight", "iOS — المقيس",
            c("#E3E4E7"), c("#E3E4E7"), c("#E3E4E7"), c("#E3E4E7"),
            c("#FFFFFF"), c("#D4D5D9"), c("#FFFFFF"),
            c("#3478F7"), c("#FFFFFF"),
            c("#000000"), c("#8A8A8E"), c("#000000"), c("#FFFFFF"), c("#3C3C43"),
            lightKeys = true, twin = "iosDark"
        ),
        KbTheme(
            "iosDeep", "iOS — أقوى",
            c("#C9CCD3"), c("#C9CCD3"), c("#C9CCD3"), c("#C9CCD3"),
            c("#FFFFFF"), c("#E6E7EB"), c("#FFFFFF"),
            c("#2A6AE8"), c("#FFFFFF"),
            c("#000000"), c("#5A5C62"), c("#000000"), c("#FFFFFF"), c("#25272C"),
            lightKeys = true, twin = "iosDark"
        ),
        KbTheme(
            "iosDark", "iOS ليلي",
            c("#1C1C1E"), c("#1C1C1E"), c("#1C1C1E"), c("#1C1C1E"),
            c("#4A4A4C"), c("#6B6B6E"), c("#4A4A4C"),
            c("#3478F7"), c("#FFFFFF"),
            c("#FFFFFF"), c("#8A8A8E"), c("#FFFFFF"), c("#1C1C1E"), c("#D1D1D6"),
            dark = true, twin = "iosCrisp"
        ),
        KbTheme(
            "light", "نهاري",
            c("#FFFFFF"), c("#D6D8DD"), c("#C6C9D0"), c("#D6D8DD"),
            c("#FFFFFF"), c("#E0E1E5"), c("#B4B8C0"),
            c("#2A6AD4"), c("#FFFFFF"),
            c("#111111"), c("#7C7C80"), c("#111111"), c("#FFFFFF"), c("#3C3C43"),
            lightKeys = true, twin = "black"
        ),
        KbTheme(
            "black", "أسود",
            c("#000000"), c("#0B0B0B"), c("#1E1E1E"), c("#0B0B0B"),
            c("#4B4B4B"), c("#6A6A6A"), c("#1C1C1C"),
            c("#6EA2F0"), c("#0A1A30"),
            c("#FFFFFF"), c("#8D8D8D"), c("#FFFFFF"), c("#000000"), c("#CFCFCF"),
            dark = true, twin = "light"
        ),
        KbTheme(
            "charcoal", "فحمي",
            c("#141414"), c("#1D1D1F"), c("#2B2B2E"), c("#1D1D1F"),
            c("#3A3A3C"), c("#58585A"), c("#242426"),
            c("#4F7FD4"), c("#FFFFFF"),
            c("#F2F2F2"), c("#8F8F8F"), c("#F2F2F2"), c("#111111"), c("#D6D6D6"),
            dark = true, twin = "light"
        ),
        KbTheme(
            "midnightDay", "أزرق نهاري",
            c("#D6DCE8"), c("#D6DCE8"), c("#C3CBDC"), c("#D6DCE8"),
            c("#FFFFFF"), c("#E7EBF3"), c("#B3BDD2"),
            c("#2A5CB8"), c("#FFFFFF"),
            c("#0B1220"), c("#5A6678"), c("#0B1220"), c("#FFFFFF"), c("#28344A"),
            lightKeys = true, twin = "midnight"
        ),
        KbTheme(
            "midnight", "أزرق ليلي",
            c("#0B1220"), c("#121B2C"), c("#1F2B42"), c("#121B2C"),
            c("#2A3850"), c("#3E5070"), c("#161F31"),
            c("#4F7FD4"), c("#FFFFFF"),
            c("#E8EEF8"), c("#7D8AA0"), c("#E8EEF8"), c("#0B1220"), c("#AEBBD0"),
            dark = true, twin = "midnightDay"
        ),
        KbTheme(
            "hucDay", "HUC نهاري",
            c("#D5E3DD"), c("#D5E3DD"), c("#C2D4CC"), c("#D5E3DD"),
            c("#FFFFFF"), c("#E8F0EC"), c("#AEC6BC"),
            c("#1D9E75"), c("#FFFFFF"),
            c("#06110D"), c("#5A6F66"), c("#0E7A59"), c("#FFFFFF"), c("#24352E"),
            lightKeys = true, twin = "huc"
        ),
        KbTheme(
            "huc", "HUC",
            c("#0A0F0D"), c("#101814"), c("#1B2A23"), c("#101814"),
            c("#1F2F29"), c("#2F473E"), c("#131D19"),
            c("#1D9E75"), c("#FFFFFF"),
            c("#E9F2EE"), c("#7D9089"), c("#4FBF9C"), c("#06110D"), c("#A9BDB6"),
            dark = true, twin = "hucDay"
        ),
        KbTheme(
            "plumDay", "بنفسجي نهاري",
            c("#DFD6E6"), c("#DFD6E6"), c("#CDC0D6"), c("#DFD6E6"),
            c("#FFFFFF"), c("#EDE7F2"), c("#BFAFCB"),
            c("#7B4FB0"), c("#FFFFFF"),
            c("#1A0F22"), c("#6B5A78"), c("#7B4FB0"), c("#FFFFFF"), c("#2E2139"),
            lightKeys = true, twin = "plum"
        ),
        KbTheme(
            "plum", "بنفسجي",
            c("#120B18"), c("#1A1023"), c("#2A1A36"), c("#1A1023"),
            c("#2E1F3C"), c("#453058"), c("#1B1023"),
            c("#8A5CC0"), c("#FFFFFF"),
            c("#F0E8F6"), c("#9985A6"), c("#D9BDF0"), c("#1A0F22"), c("#C0ABCD"),
            dark = true, twin = "plumDay"
        ),
        // ---- white on white -------------------------------------------------
        //
        // Five keyboards whose panel and keys are the same white. What separates a
        // key from the surface is a hairline, a shadow, a carve, or nothing at all
        // but the gap — and that choice, not the colour, is the whole design.

        KbTheme(
            "hair", "خيط",
            c("#FFFFFF"), c("#FFFFFF"), c("#E6E6E2"), c("#FFFFFF"),
            c("#FFFFFF"), c("#F1F1EF"), c("#FFFFFF"),
            c("#121212"), c("#FFFFFF"),
            c("#0B0B0A"), c("#8E8E88"), c("#0B0B0A"), c("#FFFFFF"), c("#2A2A28"),
            lightKeys = true, twin = "hairNight", edge = c("#E6E6E2")
        ),
        KbTheme(
            "hairNight", "خيط ليلي",
            c("#0C0C0D"), c("#0C0C0D"), c("#27272A"), c("#0C0C0D"),
            c("#0C0C0D"), c("#1E1E20"), c("#0C0C0D"),
            c("#F2F2EF"), c("#0C0C0D"),
            c("#F7F7F5"), c("#76766F"), c("#F7F7F5"), c("#0C0C0D"), c("#CFCFCB"),
            dark = true, twin = "hair", edge = c("#27272A")
        ),

        KbTheme(
            "float", "طافي",
            c("#FFFFFF"), c("#FFFFFF"), c("#EDEDEA"), c("#FFFFFF"),
            c("#FFFFFF"), c("#F0F0EE"), c("#FFFFFF"),
            c("#1A1A19"), c("#FFFFFF"),
            c("#0A0A09"), c("#90908A"), c("#0A0A09"), c("#FFFFFF"), c("#2A2A28"),
            lightKeys = true, twin = "floatNight", lift = c("#26000000")
        ),
        KbTheme(
            "floatNight", "طافي ليلي",
            c("#0A0A0B"), c("#0A0A0B"), c("#242427"), c("#0A0A0B"),
            c("#141416"), c("#242427"), c("#141416"),
            c("#EFEFEC"), c("#0A0A0B"),
            c("#F8F8F6"), c("#79797A"), c("#F8F8F6"), c("#0A0A0B"), c("#CECECA"),
            dark = true, twin = "float", lift = c("#8C000000")
        ),

        KbTheme(
            "carve", "محفور",
            c("#FAFAF8"), c("#FAFAF8"), c("#ECECE8"), c("#FAFAF8"),
            c("#FFFFFF"), c("#F2F2F0"), c("#FFFFFF"),
            c("#17170F"), c("#FFFFFF"),
            c("#0C0C0B"), c("#8C8C86"), c("#0C0C0B"), c("#FFFFFF"), c("#2B2B29"),
            lightKeys = true, twin = "carveNight",
            carve = c("#18000000"), carveLight = c("#F2FFFFFF")
        ),
        KbTheme(
            "carveNight", "محفور ليلي",
            c("#0B0B0C"), c("#0B0B0C"), c("#232326"), c("#0B0B0C"),
            c("#131315"), c("#1F1F22"), c("#131315"),
            c("#EDEDEA"), c("#0B0B0C"),
            c("#F6F6F4"), c("#7A7A74"), c("#F6F6F4"), c("#0B0B0C"), c("#CDCDC9"),
            dark = true, twin = "carve",
            carve = c("#1FFFFFFF"), carveLight = c("#99000000")
        ),

        KbTheme(
            "brass", "نحاس",
            c("#FFFEFC"), c("#FFFEFC"), c("#EADFC9"), c("#FFFEFC"),
            c("#FFFEFC"), c("#F6F1E8"), c("#FFFEFC"),
            c("#A4823F"), c("#FFFEFC"),
            c("#140F08"), c("#938876"), c("#140F08"), c("#FFFEFC"), c("#2E2619"),
            lightKeys = true, twin = "brassNight", edge = c("#EADFC9")
        ),
        KbTheme(
            "brassNight", "نحاس ليلي",
            c("#0C0A07"), c("#0C0A07"), c("#2B2418"), c("#0C0A07"),
            c("#0C0A07"), c("#1E1A13"), c("#0C0A07"),
            c("#C79A4A"), c("#0C0A07"),
            c("#F8F3E9"), c("#7E7465"), c("#F8F3E9"), c("#0C0A07"), c("#CFC6B4"),
            dark = true, twin = "brass", edge = c("#2B2418")
        ),

        KbTheme(
            "gapLight", "فجوة",
            c("#EFEFED"), c("#EFEFED"), c("#DEDEDB"), c("#EFEFED"),
            c("#FFFFFF"), c("#E4E4E1"), c("#FFFFFF"),
            c("#111111"), c("#FFFFFF"),
            c("#09090A"), c("#86867F"), c("#09090A"), c("#FFFFFF"), c("#292928"),
            lightKeys = true, twin = "gapNight"
        ),
        KbTheme(
            "gapNight", "فجوة ليلي",
            c("#0A0A0B"), c("#0A0A0B"), c("#232327"), c("#0A0A0B"),
            c("#17171A"), c("#232327"), c("#17171A"),
            c("#F3F3F0"), c("#0A0A0B"),
            c("#FAFAF8"), c("#76766F"), c("#FAFAF8"), c("#0A0A0B"), c("#D0D0CC"),
            dark = true, twin = "gapLight"
        ),

        KbTheme(
            "glassLight", "زجاج فاتح",
            c("#F2F2F2"), c("#E8E8E8"), c("#D8D8D8"), c("#E8E8E8"),
            c("#FBFBFB"), c("#E2E2E2"), c("#CFCFCF"),
            c("#161616"), c("#FFFFFF"),
            c("#0E0E0E"), c("#6E6E6E"), c("#161616"), c("#FFFFFF"), c("#2B2B2B"),
            lightKeys = true, twin = "glassDark"
        ),
        KbTheme(
            "glassDark", "زجاج غامق",
            c("#0D0D0D"), c("#1C1C1C"), c("#333333"), c("#1C1C1C"),
            c("#2E2E2E"), c("#4D4D4D"), c("#1A1A1A"),
            c("#E0E0E0"), c("#000000"),
            c("#FFFFFF"), c("#9A9A9A"), c("#FFFFFF"), c("#000000"), c("#E3E3E3"),
            dark = true, twin = "glassLight"
        ),

        // ---- five light schemes, each with its night twin ----
        //
        // Every one keeps the iOS proportions and only moves colour: the point of
        // the set is that the light half is distinct enough to choose between,
        // where the earlier light themes were all the same cool grey.

        // Warm off-white, the colour of paper rather than glass. Softly lifted.
        KbTheme(
            "pearl", "لؤلؤ",
            c("#EBE8E4"), c("#EBE8E4"), c("#D5D0CA"), c("#EBE8E4"),
            c("#FFFEFC"), c("#F0ECE6"), c("#DAD5CF"),
            c("#3478F7"), c("#FFFFFF"),
            c("#1A1817"), c("#8B8580"), c("#1A1817"), c("#FFFFFF"), c("#2A2724"),
            lightKeys = true, twin = "pearlNight", lift = c("#2E5A5048")
        ),
        KbTheme(
            "pearlNight", "لؤلؤ ليلي",
            c("#191715"), c("#191715"), c("#332E29"), c("#191715"),
            c("#2C2825"), c("#35312D"), c("#201D1A"),
            c("#3478F7"), c("#FFFFFF"),
            c("#F6F2ED"), c("#8E867E"), c("#F6F2ED"), c("#191715"), c("#0F0E0D"),
            dark = true, twin = "pearl", lift = c("#59000000")
        ),

        // Cool and flat: a hairline round every key instead of a shadow.
        KbTheme(
            "mist", "ضباب",
            c("#DCE2E9"), c("#DCE2E9"), c("#C7CDD5"), c("#DCE2E9"),
            c("#FBFCFE"), c("#E8ECF2"), c("#C3CBD5"),
            c("#2F7DF6"), c("#FFFFFF"),
            c("#141C25"), c("#7A8694"), c("#141C25"), c("#FFFFFF"), c("#252E38"),
            lightKeys = true, twin = "mistNight", edge = c("#E2E7ED")
        ),
        KbTheme(
            "mistNight", "ضباب ليلي",
            c("#10151B"), c("#10151B"), c("#2A333D"), c("#10151B"),
            c("#212932"), c("#2A333D"), c("#192027"),
            c("#2F7DF6"), c("#FFFFFF"),
            c("#E9EFF6"), c("#7E8A98"), c("#E9EFF6"), c("#10151B"), c("#090D11"),
            dark = true, twin = "mist", edge = c("#2B343E")
        ),

        // Cream and brass, keys carved into the surface rather than sitting on it.
        KbTheme(
            "sand", "رمل",
            c("#E7E0D4"), c("#E7E0D4"), c("#D2C9B9"), c("#E7E0D4"),
            c("#FFFDF7"), c("#F2EDE2"), c("#D3C9B7"),
            c("#B8853A"), c("#FFFFFF"),
            c("#282015"), c("#8E8470"), c("#282015"), c("#FFFFFF"), c("#332B1E"),
            lightKeys = true, twin = "sandNight",
            carve = c("#1A000000"), carveLight = c("#F0FFFFFF")
        ),
        KbTheme(
            "sandNight", "رمل ليلي",
            c("#1C1813"), c("#1C1813"), c("#342C21"), c("#1C1813"),
            c("#2C261C"), c("#362F24"), c("#221D15"),
            c("#C08A3E"), c("#FFFFFF"),
            c("#F3EADA"), c("#948872"), c("#F3EADA"), c("#1C1813"), c("#110E0A"),
            dark = true, twin = "sand",
            carve = c("#38000000"), carveLight = c("#1AFFFFFF")
        ),

        // Neutral with just enough green that it reads as fresh, not as coloured.
        KbTheme(
            "mint", "نعناع",
            c("#DEE7E2"), c("#DEE7E2"), c("#C9D3CD"), c("#DEE7E2"),
            c("#FCFEFD"), c("#E9F0EC"), c("#C5D0CA"),
            c("#0E9E6A"), c("#FFFFFF"),
            c("#101B16"), c("#798780"), c("#101B16"), c("#FFFFFF"), c("#1F2A24"),
            lightKeys = true, twin = "mintNight", lift = c("#2B28503F")
        ),
        KbTheme(
            "mintNight", "نعناع ليلي",
            c("#0F1613"), c("#0F1613"), c("#27332C"), c("#0F1613"),
            c("#1E2923"), c("#27332C"), c("#17201B"),
            c("#11A06B"), c("#FFFFFF"),
            c("#E6F3EC"), c("#7C8D84"), c("#E6F3EC"), c("#0F1613"), c("#080D0A"),
            dark = true, twin = "mint", lift = c("#59000000")
        ),

        // No colour anywhere, including the enter key. The highest contrast here.
        KbTheme(
            "graphite", "فحم فاتح",
            c("#CFD1D6"), c("#CFD1D6"), c("#BBBEC4"), c("#CFD1D6"),
            c("#FFFFFF"), c("#E7E8EC"), c("#B4B7BE"),
            c("#1C1C1F"), c("#FFFFFF"),
            c("#000000"), c("#6E7177"), c("#000000"), c("#FFFFFF"), c("#26262A"),
            lightKeys = true, twin = "graphiteNight", lift = c("#382D3037")
        ),
        KbTheme(
            "graphiteNight", "فحم ليلي",
            c("#0C0C0E"), c("#0C0C0E"), c("#2A2A2F"), c("#0C0C0E"),
            c("#1F1F23"), c("#2A2A2F"), c("#161619"),
            c("#EDEDEF"), c("#0C0C0E"),
            c("#FFFFFF"), c("#80838A"), c("#FFFFFF"), c("#0C0C0E"), c("#070708"),
            dark = true, twin = "graphite", lift = c("#66000000")
        ),

        // ---- Material: flat, every key the same, one violet enter ----
        //
        // The departure from the iOS set is that there is no second key colour:
        // shift, backspace and ?123 are the same as the letters, and the only
        // colour anywhere is the enter key. Nothing is lifted, edged or carved —
        // keys are told apart from the panel by value alone.
        //
        // These read best with rounder corners and a slightly wider gap than the
        // iOS themes want; both are his own settings, not part of a theme.
        KbTheme(
            "nebulaDay", "سديم نهاري",
            c("#F0F0F2"), c("#F0F0F2"), c("#E2E2E6"), c("#F0F0F2"),
            c("#FFFFFF"), c("#E4E4E9"), c("#FFFFFF"),
            c("#6B61E8"), c("#FFFFFF"),
            c("#121214"), c("#5F6066"), c("#121214"), c("#FFFFFF"), c("#26262A"),
            lightKeys = true, twin = "nebula"
        ),
        KbTheme(
            "nebula", "سديم ليلي",
            c("#000000"), c("#000000"), c("#1C1C1C"), c("#000000"),
            c("#1E1E1E"), c("#2E2E2E"), c("#1E1E1E"),
            c("#6B61E8"), c("#FFFFFF"),
            c("#FFFFFF"), c("#9AA0A6"), c("#FFFFFF"), c("#000000"), c("#E3E3E3"),
            dark = true, twin = "nebulaDay"
        ),

        // ================= زجاج =================
        //
        // Five of one idea, separated by how much light each one lets through.
        // The sheen is what makes the difference read as glass rather than as a
        // pale key: a single bright line along the top inside edge, the way light
        // catches a real edge. Without it transparency just looks washed out.

        KbTheme(
            "glassClear", "زجاج صافي",
            c("#D3D8E4"), c("#D3D8E4"), c("#C6CCDA"), c("#D3D8E4"),
            c("#FFFFFF"), c("#EDF0F7"), c("#E4E8F1"),
            c("#20222E"), c("#FFFFFF"),
            c("#0F0F16"), c("#5C6273"), c("#0F0F16"), c("#FFFFFF"), c("#2A2D3A"),
            lightKeys = true, twin = "glassSmoke",
            lift = c("#2814182C"), edge = c("#66FFFFFF"), sheen = c("#D9FFFFFF"),
            glass = true, glassPanel = 46, glassKey = 74
        ),
        KbTheme(
            "glassMilk", "زجاج حليبي",
            c("#E6E9F1"), c("#E6E9F1"), c("#DCE0EA"), c("#E6E9F1"),
            c("#FFFFFF"), c("#F4F5FA"), c("#EDEFF6"),
            c("#636780"), c("#FFFFFF"),
            c("#2A2A35"), c("#777C8C"), c("#2A2A35"), c("#FFFFFF"), c("#3A3D4C"),
            lightKeys = true, twin = "glassSmoke",
            lift = c("#1E1C2040"), sheen = c("#A6FFFFFF"),
            glass = true, glassPanel = 82, glassKey = 94
        ),
        KbTheme(
            "glassDrop", "زجاج قطرة",
            c("#CBD5E6"), c("#CBD5E6"), c("#BCC7DC"), c("#CBD5E6"),
            c("#FFFFFF"), c("#E6EBF6"), c("#DCE3F0"),
            c("#333750"), c("#FFFFFF"),
            c("#0D0D14"), c("#565C70"), c("#0D0D14"), c("#FFFFFF"), c("#272B3C"),
            lightKeys = true, twin = "glassSmoke",
            lift = c("#38141A33"), edge = c("#8CFFFFFF"), sheen = c("#FFFFFFFF"),
            glass = true, glassPanel = 40, glassKey = 86
        ),
        KbTheme(
            "glassSmoke", "زجاج دخاني",
            c("#101119"), c("#101119"), c("#1C1E2A"), c("#101119"),
            c("#2B2E3C"), c("#3E4254"), c("#22242F"),
            c("#EDEEF5"), c("#15161F"),
            c("#F2F2F7"), c("#9FA4B5"), c("#F2F2F7"), c("#15161F"), c("#C9CCD8"),
            dark = true, twin = "glassClear",
            lift = c("#50000000"), edge = c("#26FFFFFF"), sheen = c("#4DFFFFFF"),
            glass = true, glassPanel = 56, glassKey = 64        ),

        // ================= الخمسة =================
        //
        // iOS is not a palette, it is a set of proportions: the key is paler
        // than the board, it carries one hard line under it rather than a soft
        // shadow, and exactly one key is allowed to be a colour. Each of these
        // keeps that and spends its invention on the board behind the keys,
        // where a wrong decision costs nothing in reading.

        KbTheme(
            // iOS itself, measured rather than remembered: the board is a cool
            // grey that darkens downward, the key is pure white, and under each
            // key sits one hard line — not a blur. That line is the whole trick.
            "iosTrue", "ضوء",
            c("#D6D9E0"), c("#D6D9E0"), c("#C6CAD3"), c("#D6D9E0"),
            c("#FFFFFF"), c("#E7E9EF"), c("#B9BEC9"),
            c("#0A84FF"), c("#FFFFFF"),
            c("#000000"), c("#6E7179"), c("#000000"), c("#FFFFFF"), c("#2A2C31"),
            lightKeys = true, twin = "auroraNight",
            panelTop = c("#DFE2E8"), panelBottom = c("#CDD1D9"),
            keyTop = c("#FFFFFF"), keyBottom = c("#F6F7FA"),
            lift = c("#59000000"), sheen = c("#FFFFFFFF"),
            star = true
        ),
        KbTheme(
            // Night sky over water: indigo gathering at one corner, teal at the
            // far one, and the keys held back to almost nothing so the colour
            // behind them is what you see.
            "auroraNight", "شفق",
            c("#070A16"), c("#070A16"), c("#1A2140"), c("#070A16"),
            c("#272F4D"), c("#3A4470"), c("#1A2038"),
            c("#5E5CE6"), c("#FFFFFF"),
            c("#F3F4FB"), c("#9AA2C4"), c("#F3F4FB"), c("#0A0D1A"), c("#C9CEE6"),
            dark = true, twin = "iosTrue",
            panelTop = c("#0B1026"), panelBottom = c("#05070F"),
            keyTop = c("#2E3759"), keyBottom = c("#222942"),
            aura1 = c("#664C3BCF"), aura1x = 0.18f, aura1y = 0.10f, aura1r = 1.15f,
            aura2 = c("#4D0E7C7B"), aura2x = 0.86f, aura2y = 0.92f, aura2r = 1.05f,
            sheen = c("#59FFFFFF"), edge = c("#1FFFFFFF"), glow = c("#A65E5CE6"),
            star = true
        ),
        KbTheme(
            // Sunset on the Shatt: the board warms from peach down into rose,
            // the keys stay white, and their shadow is warm rather than grey —
            // a cool shadow on a warm board is the thing that looks wrong.
            "sunsetBasra", "غروب",
            c("#F3CFC2"), c("#F3CFC2"), c("#E3B3AC"), c("#F3CFC2"),
            c("#FFFFFF"), c("#F7E6DF"), c("#EFCFC4"),
            c("#D9553F"), c("#FFFFFF"),
            c("#2B1A16"), c("#8A655C"), c("#2B1A16"), c("#FFFFFF"), c("#4A2E26"),
            lightKeys = true, twin = "onyxViolet",
            panelTop = c("#F8DCC9"), panelBottom = c("#EFC1C6"),
            keyTop = c("#FFFFFF"), keyBottom = c("#FDF4EF"),
            aura1 = c("#59F2A65C"), aura1x = 0.22f, aura1y = 0.08f, aura1r = 0.95f,
            lift = c("#403A1C14"), sheen = c("#E6FFFFFF"),
            star = true
        ),
        KbTheme(
            // Mother of pearl: almost white, but rose leans in from one side and
            // blue from the other, so the board shifts as your eye crosses it
            // and never reads as grey.
            "pearlShell", "لؤلؤ",
            c("#F4F3F8"), c("#F4F3F8"), c("#E6E4EE"), c("#F4F3F8"),
            c("#FFFFFF"), c("#EFEDF6"), c("#E2E0EC"),
            c("#2E2C38"), c("#FFFFFF"),
            c("#14131A"), c("#78757F"), c("#14131A"), c("#FFFFFF"), c("#2A2833"),
            lightKeys = true, twin = "onyxViolet",
            panelTop = c("#FAF8FC"), panelBottom = c("#EDEAF3"),
            keyTop = c("#FFFFFF"), keyBottom = c("#F8F6FC"),
            aura1 = c("#40E8A8C8"), aura1x = 0.14f, aura1y = 0.12f, aura1r = 0.9f,
            aura2 = c("#3D8FB8E8"), aura2x = 0.88f, aura2y = 0.88f, aura2r = 0.9f,
            lift = c("#2E1A1830"), sheen = c("#FFFFFFFF"), edge = c("#14000000"),
            star = true
        ),
        KbTheme(
            // Black stone with one violet vein: the board is nearly black, a
            // single deep violet field sits under the middle rows, and the
            // enter key is the only lit thing on the board.
            "onyxViolet", "عقيق",
            c("#08070C"), c("#08070C"), c("#1A1726"), c("#08070C"),
            c("#1A1824"), c("#2B2740"), c("#131120"),
            c("#7A5CFF"), c("#FFFFFF"),
            c("#F2F0FA"), c("#908BA8"), c("#F2F0FA"), c("#0A0910"), c("#CFCBE0"),
            dark = true, twin = "pearlShell",
            panelTop = c("#0C0A14"), panelBottom = c("#050408"),
            keyTop = c("#211E2E"), keyBottom = c("#171522"),
            aura1 = c("#595B3BD6"), aura1x = 0.5f, aura1y = 0.55f, aura1r = 1.2f,
            sheen = c("#4DFFFFFF"), edge = c("#1AFFFFFF"), glow = c("#B37A5CFF"),
            star = true
        )
    )

    fun byId(id: String): KbTheme = all.firstOrNull { it.id == id } ?: all[0]

    /**
     * The theme to actually paint with. When the keyboard follows the device, a
     * light theme hands over to its dark twin at night and back again — so one
     * choice covers both, instead of asking for the same decision twice.
     */
    fun resolve(id: String, follow: Boolean, deviceDark: Boolean): KbTheme {
        val t = byId(id)
        if (!follow || t.twin.isEmpty() || t.dark == deviceDark) return t
        return all.firstOrNull { it.id == t.twin } ?: t
    }
}
