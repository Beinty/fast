package com.huc.fasttype

/** Key action codes. */
object Code {
    const val CHAR = 0
    const val SHIFT = 1
    const val DEL = 2
    const val ENTER = 3
    const val SPACE = 4
    const val LANG = 5
    const val TO_SYM = 6
    const val TO_ABC = 7
    const val TO_EMOJI = 8
    const val TO_NPAD = 9
    const val TO_SYM2 = 10

    /**
     * One key, every page. A tap moves to the next page in a fixed ring; a hold
     * jumps straight home (or, from the symbols, to the second symbol page, which
     * is the only place that key used to live).
     */
    const val CYCLE = 11
}

/** Key visual style. */
object Style {
    const val NORMAL = 0
    const val DARK = 1
    const val GO = 2
}

/** Vector icon ids drawn by the view. */
object Ico {
    const val NONE = 0
    const val SHIFT = 1
    const val CAPS = 2
    const val DEL = 3
    const val ENTER = 4
    const val SPACE = 5
    const val SMILE = 6
    const val GLOBE = 7
    const val MIC = 8
    const val CLIP = 9
    const val GEAR = 10
    const val TRANS = 11
    const val BACK = 12
    const val SWAP = 13
    const val COG = 14
}

class Key(
    val label: String = "",
    val out: String = "",
    val weight: Float = 1f,
    val style: Int = Style.NORMAL,
    val code: Int = Code.CHAR,
    val icon: Int = Ico.NONE,
    val arabic: Boolean = false,
    val smallText: Boolean = false,
    /**
     * Multiplier on the gap that follows this key. iOS puts twice the normal gap
     * between shift / delete and the letters, which is why A never sits straight
     * above the shift arrow. Measured: 40px against a normal 19.5px.
     */
    val gapAfter: Float = 1f,
    /** A blank slot that reserves width without drawing or catching touches. */
    val spacer: Boolean = false
) {
    // filled in on layout — the drawn rectangle
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f

    // filled in on layout — the touch rectangle, grown into the gaps so no pixel is dead
    var tx = 0f
    var ty = 0f
    var tw = 0f
    var th = 0f

    fun hit(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h

    /** Touch test. Uses the grown rectangle, so a light tap in a gap still lands. */
    fun hitT(px: Float, py: Float) = px >= tx && px <= tx + tw && py >= ty && py <= ty + th

    /** Squared distance from a point to the touch rectangle; 0 when inside. */
    fun distT(px: Float, py: Float): Float {
        val dx = when {
            px < tx -> tx - px
            px > tx + tw -> px - (tx + tw)
            else -> 0f
        }
        val dy = when {
            py < ty -> ty - py
            py > ty + th -> py - (ty + th)
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

object Pages {
    const val LETTERS = 0
    const val SYM1 = 1
    const val SYM2 = 2
    const val NPAD = 3
    const val EMOJI = 4
    const val CLIP = 5
    const val LANGS = 6
}

object KbLayout {

    /**
     * What a long press offers. The first entry is always the key's own character,
     * so lifting without sliding types exactly what was pressed.
     *
     * Arabic needs this more than most: أ إ آ are nowhere on the layout, which left
     * them impossible to type at all. The Iraqi letters چ گ پ ڤ ride along.
     */
    val alts: Map<String, List<String>> = mapOf(
        "ا" to listOf("ا", "أ", "إ", "آ", "ٱ"),
        "ه" to listOf("ه", "ة"),
        "و" to listOf("و", "ؤ"),
        "ي" to listOf("ي", "ى", "ئ"),
        "ى" to listOf("ى", "ي", "ئ"),
        "ة" to listOf("ة", "ه"),
        "ء" to listOf("ء", "أ", "إ", "ؤ", "ئ"),
        "ل" to listOf("ل", "لا", "لأ", "لإ", "لآ"),
        "ج" to listOf("ج", "چ"),
        "ك" to listOf("ك", "گ"),
        "ب" to listOf("ب", "پ"),
        "ف" to listOf("ف", "ڤ"),
        "ز" to listOf("ز", "ژ"),
        "a" to listOf("a", "à", "á", "â", "ä", "å"),
        "e" to listOf("e", "è", "é", "ê", "ë"),
        "i" to listOf("i", "ì", "í", "î", "ï"),
        "o" to listOf("o", "ò", "ó", "ô", "ö"),
        "u" to listOf("u", "ù", "ú", "û", "ü"),
        "c" to listOf("c", "ç"),
        "n" to listOf("n", "ñ"),
        "A" to listOf("A", "À", "Á", "Â", "Ä"),
        "E" to listOf("E", "È", "É", "Ê", "Ë"),
        "I" to listOf("I", "Ì", "Í", "Î", "Ï"),
        "O" to listOf("O", "Ò", "Ó", "Ô", "Ö"),
        "U" to listOf("U", "Ù", "Ú", "Û", "Ü"),
        "C" to listOf("C", "Ç"),
        "N" to listOf("N", "Ñ")
    )

    /**
     * What a hold on the full stop offers.
     *
     * Arabic vowel marks are nowhere on a phone keyboard and there is no room to
     * give each of them a key, so they live behind the one key that is always
     * within reach of the thumb. English gets the punctuation instead — the same
     * key, the same gesture, whatever he is writing in.
     */
    val arMarks: List<String> = listOf(
        "\u064E", "\u064F", "\u0650", "\u0652",
        "\u0651", "\u064B", "\u064C", "\u064D",
        "\u0654", "\u0655", "\u0653", "\u0670",
        "\u0640", "\u060C", "\u061F", "!"
    )

    val enMarks: List<String> = listOf(
        ",", "?", "!", ":", ";", "'", "\"", "-",
        "_", "(", ")", "/", "@", "#", "&", "\u2026"
    )

    /** The alternates for a key, or null when a long press should do nothing. */
    fun altsFor(k: Key): List<String>? {
        if (k.code != Code.CHAR || k.out.isEmpty()) return null
        if (k.out == ".") return if (k.arabic) arMarks else enMarks
        return alts[k.out]
    }


    private const val AR1 = "ضصثقفغعهخحج"
    private const val AR2 = "شسيبلاتنمكط"
    private const val AR3 = "ذءؤرىةوزظد"
    private const val EN1 = "qwertyuiop"
    private const val EN2 = "asdfghjkl"
    private const val EN3 = "zxcvbnm"

    private fun chars(s: String, arabic: Boolean, upper: Boolean = false): MutableList<Key> {
        val out = ArrayList<Key>(s.length)
        for (ch in s) {
            val t = if (upper) ch.uppercaseChar().toString() else ch.toString()
            out.add(Key(label = t, out = t, arabic = arabic))
        }
        return out
    }

    // Every width below is measured off a real iOS screenshot, as a multiple of a
    // letter key: shift/delete 117.5/85.5, 123 110/85.5, emoji 111/85.5,
    // action 244/85.5, space 503/85.5. The doubled gap is 40/19.5.
    const val W_MOD = 1.374f
    const val W_SYM = 1.29f
    const val W_EMOJI = 1.30f
    // The action key was measured off iOS, where its label is a word. Here it is
    // one arrow, so some of its width goes to the space bar, which carries the
    // language name and is the key most often reached for.
    const val W_GO = 2.42f
    const val W_SPACE = 6.31f

    // Measured off an iOS screenshot of the three-key bottom row: against a
    // letter key of 87px, the 123 key is 229px, the space bar 545px and the
    // return 240px. The row carries nothing else, so these are the whole row.
    const val W_IOS_SYM = 2.60f
    const val W_IOS_SPACE = 6.30f
    const val W_IOS_GO = 2.70f

    /** iOS has three keys down there: 123, space, return. Nothing else. */
    @Volatile
    var iosRow = false
    const val W_DOT = 1.30f
    const val W_ROW2_PAD = 0.62f
    const val GAP_MOD = 2.05f

    /** Set by the view before building rows: put the language key in the last row. */
    @Volatile
    var globeInRow = false

    /** The full stop between the space bar and the action key. */
    @Volatile
    var dotInRow = true

    /**
     * A full stop the moment one is about to be needed.
     *
     * Set while he is writing something a dot belongs in — an address, a
     * domain — and cleared when he is not. It overrides both the setting and
     * the iOS row, because the whole point is that it appears where it
     * otherwise would not.
     */
    @Volatile
    var dotNow = false

    private fun del() = Key(weight = W_MOD, style = Style.DARK, code = Code.DEL, icon = Ico.DEL)
    private fun enter() =
        Key(weight = if (iosRow) W_IOS_GO else W_GO,
            style = Style.GO, code = Code.ENTER, icon = Ico.ENTER)
    private fun globe() = Key(weight = W_EMOJI, style = Style.DARK, code = Code.LANG, icon = Ico.GLOBE)
    private fun pad() = Key(weight = W_ROW2_PAD, spacer = true)

    private fun dot(ar: Boolean) = Key(label = ".", out = ".", weight = W_DOT, arabic = ar)

    /** Whether the row has a full stop in it at this moment. */
    private fun dotShown(): Boolean = dotNow || (dotInRow && !iosRow)

    private fun spaceWeight(): Float {
        // the space bar is what gives the dot its room and takes it back
        var w = if (iosRow) W_IOS_SPACE else W_SPACE
        if (!iosRow && !globeInRow) w += W_EMOJI
        if (dotShown()) w -= W_DOT
        return w
    }

    private fun space(ar: Boolean) =
        Key(label = if (ar) "العربية" else "English", out = " ",
            weight = spaceWeight(), code = Code.SPACE, arabic = ar)

    /**
     * The ring the switch key walks: letters, symbols, numbers, faces, back.
     * The second symbol page counts as symbols, so a tap there still reaches the
     * number pad rather than stranding him.
     */
    fun nextPage(page: Int): Int = when (page) {
        Pages.LETTERS -> Pages.SYM1
        Pages.SYM1, Pages.SYM2 -> Pages.NPAD
        // the faces live in the tool strip now, so the ring is one step shorter
        // and getting home from the number pad costs one tap instead of two
        else -> Pages.LETTERS
    }

    /** Where a hold on the switch key lands. */
    fun holdPage(page: Int): Int =
        if (page == Pages.SYM1) Pages.SYM2 else Pages.LETTERS

    /**
     * The switch key. Its face says where the next tap goes, so he never has to
     * remember the order — and it keeps the same cell on every page, bottom left of
     * the last row, because a key that moves is a key the thumb has to look for.
     */
    /** The 123 key is wider on the iOS row, where it is one of only three. */
    private fun symWeight(): Float = if (iosRow) W_IOS_SYM else W_SYM

    private fun cycle(ar: Boolean, page: Int, w: Float = symWeight()): Key {
        val to = nextPage(page)
        return when (to) {
            Pages.NPAD -> Key(
                label = if (ar) "١٢٣٤" else "1234",
                weight = w, style = Style.DARK, code = Code.CYCLE,
                arabic = ar, smallText = true
            )
            Pages.EMOJI -> Key(
                weight = w, style = Style.DARK, code = Code.CYCLE, icon = Ico.SMILE
            )
            Pages.LETTERS -> Key(
                label = if (ar) "أبج" else "ABC",
                weight = w, style = Style.DARK, code = Code.CYCLE,
                arabic = ar, smallText = true
            )
            else -> Key(
                label = if (ar) "؟١٢٣" else "?123",
                weight = w, style = Style.DARK, code = Code.CYCLE,
                arabic = ar, smallText = true
            )
        }
    }

    /**
     * Switch, language, space, action — the same four on letters and on symbols.
     * The face key is gone; the globe stands exactly where it stood, and the
     * remaining width goes back to the space bar, which puts it in the middle of
     * the screen with equal room on both sides.
     */
    private fun lastRow(ar: Boolean, page: Int): MutableList<Key> {
        val out = mutableListOf(cycle(ar, page))
        // On the iOS row the language key lives in the strip above and there is
        // no full stop at all — three keys, and the space bar takes the room.
        if (globeInRow && !iosRow) out.add(globe())
        out.add(space(ar))
        if (dotShown()) out.add(dot(ar))
        out.add(enter())
        return out
    }

    /** Row two is inset by 0.62 of a key on each side, exactly like iOS. */
    private fun inset(keys: MutableList<Key>): MutableList<Key> {
        keys.add(0, pad())
        keys.add(pad())
        return keys
    }

    /** ٠١٢٣ on the Arabic layout, 0123 on the English one. */
    fun digits(ar: Boolean): String = if (ar) "٠١٢٣٤٥٦٧٨٩" else "0123456789"

    private fun numberRow(ar: Boolean): MutableList<Key> {
        // The key used to show ١ and type 1. What a key shows is what it should type.
        val d = digits(ar)
        val order = "1234567890"
        val out = ArrayList<Key>(10)
        for (c in order) {
            val t = d[c - '0'].toString()
            out.add(Key(label = t, out = t))
        }
        return out
    }

    /** Builds the rows for a page. */
    fun rows(page: Int, arabic: Boolean, shift: Int, numRow: Boolean): List<List<Key>> {
        val r = ArrayList<List<Key>>(5)

        when (page) {
            Pages.LETTERS -> {
                if (numRow) r.add(numberRow(arabic))
                if (arabic) {
                    r.add(chars(AR1, true))
                    r.add(chars(AR2, true))
                    val third = chars(AR3, true)
                    third.add(del())
                    r.add(third)
                } else {
                    val up = shift > 0
                    r.add(chars(EN1, false, up))
                    r.add(inset(chars(EN2, false, up)))
                    val third = ArrayList<Key>()
                    third.add(
                        Key(
                            weight = W_MOD, style = Style.DARK, code = Code.SHIFT,
                            icon = if (shift == 2) Ico.CAPS else Ico.SHIFT,
                            gapAfter = GAP_MOD
                        )
                    )
                    val mid = chars(EN3, false, up)
                    // the last letter carries the doubled gap that sits before delete
                    mid[mid.size - 1] = Key(
                        label = mid[mid.size - 1].label, out = mid[mid.size - 1].out,
                        gapAfter = GAP_MOD
                    )
                    third.addAll(mid)
                    third.add(del())
                    r.add(third)
                }
                r.add(lastRow(arabic, Pages.LETTERS))
            }

            Pages.SYM1 -> {
                val d = digits(arabic)
                val row1 = ArrayList<Key>(10)
                for (c in "1234567890") {
                    val t = d[c - '0'].toString()
                    row1.add(Key(t, t))
                }
                r.add(row1)
                r.add(chars("@#\$_&-+()/", false))
                val row3 = ArrayList<Key>()
                // The =\< key is gone. One tap home is worth more than a page he
                // opened by accident; its characters are a hold on the switch key.
                row3.add(
                    Key(
                        label = if (arabic) "أبج" else "ABC",
                        weight = W_MOD, style = Style.DARK, code = Code.TO_ABC,
                        arabic = arabic, smallText = true
                    )
                )
                row3.addAll(chars(if (arabic) "*\"':؛!؟" else "*\"':;!?", arabic))
                row3.add(del())
                r.add(row3)
                r.add(lastRow(arabic, Pages.SYM1))
            }

            Pages.SYM2 -> {
                r.add(chars("~`|•√π÷×¶Δ", false))
                r.add(chars("£¢€¥^°={}\\", false))
                val row3 = ArrayList<Key>()
                row3.add(
                    Key(
                        if (arabic) "؟١٢٣" else "?123", "", W_MOD,
                        Style.DARK, Code.TO_SYM, arabic = arabic, smallText = true
                    )
                )
                row3.addAll(chars("%©®™✓[]", false))
                row3.add(del())
                r.add(row3)
                r.add(lastRow(arabic, Pages.SYM2))
            }

            Pages.NPAD -> {
                // Five columns, every key one wide, so the zero sits directly under
                // the eight. The bottom row used to carry six keys against the other
                // rows' five, which put every digit in it out of line with the column
                // above. The operators own the first column; the keys that are not
                // numbers at all own the last.
                // The switch key belongs in the same corner it holds everywhere else —
                // bottom left — so the thumb learns one place. Division moves up beside
                // multiplication to free that cell; no key is lost and no digit moves.
                val d = digits(arabic)
                fun dig(n: Int) = Key(d[n].toString(), d[n].toString())
                r.add(
                    mutableListOf(
                        Key("+", "+", 1f, Style.DARK), dig(1), dig(2), dig(3),
                        Key("", "", 1f, Style.DARK, Code.DEL, Ico.DEL)
                    )
                )
                r.add(
                    mutableListOf(
                        Key("−", "-", 1f, Style.DARK), dig(4), dig(5), dig(6),
                        Key("", " ", 1f, Style.DARK, Code.SPACE, Ico.SPACE)
                    )
                )
                r.add(
                    mutableListOf(
                        Key("×", "*", 1f, Style.DARK), dig(7), dig(8), dig(9),
                        Key("÷", "/", 1f, Style.DARK)
                    )
                )
                r.add(
                    mutableListOf(
                        cycle(arabic, Pages.NPAD, 1f), Key(".", "."), dig(0),
                        Key(weight = 2f, style = Style.GO, code = Code.ENTER, icon = Ico.ENTER)
                    )
                )
            }
        }
        return r
    }

    /** Bottom row for the emoji page. */
    fun emojiBottom(arabic: Boolean): List<Key> = mutableListOf(
        cycle(arabic, Pages.EMOJI),
        Key(label = if (arabic) "العربية" else "English", out = " ",
            weight = W_SPACE + W_EMOJI + (W_GO - W_MOD), code = Code.SPACE, arabic = arabic),
        Key(weight = W_MOD, style = Style.DARK, code = Code.DEL, icon = Ico.DEL)
    )
}

object Emoji {
    val tabs = listOf("😀", "👋", "🐶", "🍎", "⚽", "🚗", "💡", "❤️", "🏳️")

    val sets: List<List<String>> = listOf(
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮 😯 😴 🥱 😪 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😢 😭 😤 😠 😡 🤬 😈 💀 💩 👻 👽 🤖 😺",
        "👋 🤚 ✋ 🖖 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 💪 👂 👃 👀 🧠 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙍 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 🕵️ 💂 👷 🤴 👸 👳 🧕 🤵 👰 🤰 🎅 🦸 🦹 🧙 👪",
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🦖 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🦓 🦍 🐘 🐪 🦒 🐄 🌵 🌲 🌴 🌿 🍀 🍁 🌺 🌸 🌼 🌻 🌞 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ⛄ 💧 🌊",
        "🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🥐 🥯 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥙 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍢 🍡 🍧 🍨 🍦 🥧 🍰 🎂 🧁 🍫 🍬 🍭 ☕ 🍵 🧃 🥤 🧉",
        "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🎱 🏓 🏸 🏒 🏑 🏏 🥅 ⛳ 🪁 🏹 🎣 🥊 🥋 🎽 🛹 🛼 ⛸️ 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🚣 🧗 🚴 🚵 🏆 🥇 🥈 🥉 🏅 🎖️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩",
        "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏔️ ⛰️ 🌋 🏕️ 🕌 🕋 ⛩️ 🏛️ 🏗️ 🏘️ 🏠 🏢 🏬 🏥 🏦 🏨 🏪 🏫",
        "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📷 📸 📹 🎥 📽️ 📞 ☎️ 📟 📠 📺 📻 🎙️ ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 🧯 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ ⚰️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 💊 💉 🩸 🩹 🩺 🚪 🪞 🪟 🛏️ 🛋️ 🪑 🚽 🚿 🛁 🧴 🧷 🧹 🧺 🧻 🪣 🧼 🪥 🧽 🔑 🗝️ 🔒 🔓 📝 📋 📌 📎 ✂️ 📏 📐 📚 📖 📒 📓 📰 🗂️ 📁 📂 🗓️ 📅",
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ☪️ 🕋 📿 ☸️ ✡️ 🔯 ☯️ ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ ☢️ ☣️ 📴 📳 ✴️ 🆚 💮 ㊙️ ㊗️ 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🔞 📵 🚭 ❗ ❓ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 🌀 💤 🏧 🚾 ♿ 🅿️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜",
        "🏳️ 🏴 🏁 🚩 🇮🇶 🇸🇦 🇦🇪 🇰🇼 🇶🇦 🇧🇭 🇴🇲 🇾🇪 🇯🇴 🇱🇧 🇸🇾 🇵🇸 🇪🇬 🇱🇾 🇹🇳 🇩🇿 🇲🇦 🇲🇷 🇸🇩 🇸🇴 🇩🇯 🇰🇲 🇹🇷 🇮🇷 🇵🇰 🇦🇫 🇮🇳 🇧🇩 🇮🇩 🇲🇾 🇨🇳 🇯🇵 🇰🇷 🇷🇺 🇩🇪 🇫🇷 🇬🇧 🇮🇹 🇪🇸 🇳🇱 🇧🇪 🇨🇭 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇵🇱 🇺🇦 🇬🇷 🇵🇹 🇺🇸 🇨🇦 🇲🇽 🇧🇷 🇦🇷 🇦🇺 🇳🇿 🇿🇦 🇳🇬 🇰🇪 🇪🇹"
    ).map { it.split(" ").filter { s -> s.isNotBlank() } }
}
