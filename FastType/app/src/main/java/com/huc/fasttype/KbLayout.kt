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
}

class Key(
    val label: String = "",
    val out: String = "",
    val weight: Float = 1f,
    val style: Int = Style.NORMAL,
    val code: Int = Code.CHAR,
    val icon: Int = Ico.NONE,
    val arabic: Boolean = false,
    val smallText: Boolean = false
) {
    // filled in on layout
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f

    fun hit(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

object Pages {
    const val LETTERS = 0
    const val SYM1 = 1
    const val SYM2 = 2
    const val NPAD = 3
    const val EMOJI = 4
}

object KbLayout {

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

    private fun del() = Key(weight = 1.45f, style = Style.DARK, code = Code.DEL, icon = Ico.DEL)
    private fun enter() = Key(weight = 1.45f, style = Style.GO, code = Code.ENTER, icon = Ico.ENTER)
    private fun emoji() = Key(style = Style.DARK, code = Code.TO_EMOJI, icon = Ico.SMILE)

    private fun space(ar: Boolean) =
        Key(label = if (ar) "ع" else "EN", out = " ", weight = 5f, code = Code.SPACE)

    private fun lastRow(ar: Boolean, first: Key): MutableList<Key> =
        mutableListOf(first, emoji(), space(ar), enter())

    private fun lastRowWithNpad(ar: Boolean, first: Key): MutableList<Key> =
        mutableListOf(
            first, emoji(), space(ar),
            Key(if (ar) "١٢٣٤" else "1234", "", 1f, Style.DARK, Code.TO_NPAD,
                arabic = ar, smallText = true),
            enter()
        )

    private fun symKey(ar: Boolean) = Key(
        label = if (ar) "؟١٢٣" else "?123",
        style = Style.DARK, code = Code.TO_SYM, arabic = ar, smallText = true
    )

    private fun abcKey(ar: Boolean) = Key(
        label = if (ar) "أبج" else "ABC",
        style = Style.DARK, code = Code.TO_ABC, arabic = ar, smallText = true
    )

    private fun numberRow(ar: Boolean): MutableList<Key> {
        val show = if (ar) "١٢٣٤٥٦٧٨٩٠" else "1234567890"
        val west = "1234567890"
        val out = ArrayList<Key>(10)
        for (i in show.indices) out.add(Key(label = show[i].toString(), out = west[i].toString()))
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
                    r.add(chars(EN2, false, up))
                    val third = ArrayList<Key>()
                    third.add(
                        Key(
                            weight = 1.45f, style = Style.DARK, code = Code.SHIFT,
                            icon = if (shift == 2) Ico.CAPS else Ico.SHIFT
                        )
                    )
                    third.addAll(chars(EN3, false, up))
                    third.add(del())
                    r.add(third)
                }
                r.add(lastRow(arabic, symKey(arabic)))
            }

            Pages.SYM1 -> {
                val show = if (arabic) "١٢٣٤٥٦٧٨٩٠" else "1234567890"
                val west = "1234567890"
                val row1 = ArrayList<Key>(10)
                for (i in show.indices) row1.add(Key(show[i].toString(), west[i].toString()))
                r.add(row1)
                r.add(chars("@#$_&-+()/", false))
                val row3 = ArrayList<Key>()
                row3.add(Key("=\\<", "", 1.45f, Style.DARK, Code.TO_SYM2, smallText = true))
                row3.addAll(chars(if (arabic) "*\"':؛!؟" else "*\"':;!?", arabic))
                row3.add(del())
                r.add(row3)
                r.add(lastRowWithNpad(arabic, abcKey(arabic)))
            }

            Pages.SYM2 -> {
                r.add(chars("~`|•√π÷×¶Δ", false))
                r.add(chars("£¢€¥^°={}\\", false))
                val row3 = ArrayList<Key>()
                row3.add(
                    Key(
                        if (arabic) "؟١٢٣" else "?123", "", 1.45f,
                        Style.DARK, Code.TO_SYM, arabic = arabic, smallText = true
                    )
                )
                row3.addAll(chars("%©®™✓[]", false))
                row3.add(del())
                r.add(row3)
                r.add(lastRow(arabic, abcKey(arabic)))
            }

            Pages.NPAD -> {
                r.add(
                    mutableListOf(
                        Key("+", "+", 1f, Style.DARK), Key("1", "1"), Key("2", "2"),
                        Key("3", "3"), Key("%", "%", 1f, Style.DARK)
                    )
                )
                r.add(
                    mutableListOf(
                        Key("−", "-", 1f, Style.DARK), Key("4", "4"), Key("5", "5"),
                        Key("6", "6"),
                        Key("", " ", 1f, Style.DARK, Code.SPACE, Ico.SPACE)
                    )
                )
                r.add(
                    mutableListOf(
                        Key("×", "*", 1f, Style.DARK), Key("7", "7"), Key("8", "8"),
                        Key("9", "9"),
                        Key("", "", 1f, Style.DARK, Code.DEL, Ico.DEL)
                    )
                )
                r.add(
                    mutableListOf(
                        abcKey(arabic), Key("÷", "/", 1f, Style.DARK), Key("0", "0"),
                        Key("=", "=", 1f, Style.DARK), Key(".", "."), enter()
                    )
                )
            }
        }
        return r
    }

    /** Bottom row for the emoji page. */
    fun emojiBottom(arabic: Boolean): List<Key> = mutableListOf(
        abcKey(arabic),
        space(arabic),
        Key(weight = 1.45f, style = Style.DARK, code = Code.DEL, icon = Ico.DEL)
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
