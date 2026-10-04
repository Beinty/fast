package com.huc.fasttype

/**
 * How a name should sound.
 *
 * The announcement used to be handed to the engine exactly as the contact was
 * saved, and an Arabic voice cannot read Latin letters — it spells them, or
 * invents something. An Arabic name with no vowel marks is barely better: the
 * engine guesses, and guesses wrong on the names people actually have.
 *
 * So the name is cleaned, then each word is turned into something an Arabic
 * voice can read properly: from a table of real names where one exists, by
 * letter rules where it does not, and left to an English voice when it is not
 * an Arabic name at all.
 */
object Phon {

    /** What to do with a Latin word that is not in the table. */
    object Mode {
        const val AUTO = 0      // an English voice reads it
        const val ARABIC = 1    // spelled into Arabic letters
        const val ENGLISH = 2   // every Latin word goes to the English voice
    }

    class Part(val text: String, val arabic: Boolean)

    // latin spellings | ... = the vowelled Arabic, which doubles as the
    // vowelling for the same name written in Arabic without marks
    private const val TABLE = """
mohammed|mohamed|muhammad|mohammad|muhamed|mhmd|mohmmed=مُحَمَّد
ahmed|ahmad|ahmd=أَحْمَد
ali|aly|aliy=عَلِي
hassan|hasan=حَسَن
hussein|hussain|husain|hussien|hosain|husein=حُسَيْن
hamza|hamzah|hamzeh=حَمْزَة
omar|umar|oumar=عُمَر
othman|osman|uthman=عُثْمان
khalid|khaled|khaled=خالِد
abbas|abas=عَبَّاس
abdullah|abdulla|abdallah|abdalla=عَبْدُ الله
abdulrahman|abdurrahman|abdelrahman=عَبْدُ الرَّحْمٰن
abdulkarim|abdelkarim=عَبْدُ الكَرِيم
abdulaziz|abdelaziz=عَبْدُ العَزِيز
abdulhadi=عَبْدُ الهادِي
mustafa|moustafa|mostafa|mustapha=مُصْطَفى
mahmoud|mahmood|mahmud=مَحْمُود
ibrahim|ibraheem|brahim=إِبْراهِيم
yousef|yusuf|youssef|yousif|yusif|yousuf=يُوسُف
yaser|yasir|yasser|yassir=ياسِر
karrar|karar=كَرَّار
haider|haidar|hayder|heider|hyder=حَيْدَر
jaafar|jafar|jaffar|jaber=جَعْفَر
sajjad|sajad=سَجَّاد
murtadha|mortadha|murtaza|mortaza|murtadh=مُرْتَضى
muntadher|muntather|muntadhar=مُنْتَظَر
ammar|amar=عَمَّار
anmar=أَنْمار
basim|basem|bassem|bassim=باسِم
hadi=هادِي
mahdi|mehdi=مَهْدِي
mohannad|muhannad=مُهَنَّد
mujtaba=مُجْتَبى
noor|nour|nur|noura=نُور
qasim|qassim|kassim|kasim=قاسِم
rasul|rasool=رَسُول
saad|sad=سَعْد
salam=سَلام
salah|salah=صَلاح
samir|sameer=سَمِير
tariq|tareq|tarek|tarik=طارِق
waleed|walid=وَلِيد
zaid|zayd|zaed=زَيْد
ziad|zyad|ziyad=زِياد
akram=أَكْرَم
amjad=أَمْجَد
anas=أَنَس
ayman=أَيْمَن
bilal|belal=بِلال
emad|imad=عِماد
essam|isam|issam=عِصام
fadhil|fadil|fadel=فاضِل
faris|fares=فارِس
ghaith|gaith=غَيْث
hakim|hakeem=حَكِيم
hamid|hameed=حَمِيد
harith|harth=حارِث
hashim|hashem=هاشِم
ihab|ehab=إِيهاب
ismail|ismaeel|esmail=إِسْماعِيل
jamal=جَمال
kamal=كَمال
kareem|karim=كَرِيم
laith|layth|lait=لَيْث
maher|mahir=ماهِر
majid|majed=ماجِد
malik|malek=مالِك
mazin|mazen=مازِن
mohsin|mohsen|muhsin=مُحْسِن
munir|muneer=مُنِير
nabil|nabeel=نَبِيل
nader|nadir=نادِر
naji=ناجِي
nasser|naser|nasir|nassir=ناصِر
rafid=رافِد
raed|raid=رائِد
rami=رامِي
rashid|rasheed=رَشِيد
riyadh|riad|riyad=رِياض
saif|seif|sayf|sief=سَيْف
salim|saleem|salem=سَلِيم
sami=سامِي
sattar|satar=سَتّار
shakir|shaker=شاكِر
sinan=سِنان
sultan=سُلْطان
taha=طه
talib|taleb=طالِب
wissam|wisam|wesam=وِسام
yahya|yehia=يَحْيى
zuhair|zoheir|zuhayr=زُهَيْر
adel|adil=عادِل
amir|ameer=أَمِير
arkan=أَرْكان
ayad|eyad|iyad=إِياد
azhar=أَزْهَر
baqir|bakir|baqer=باقِر
diyar=دِيار
ghazi=غازِي
hazim|hazem=حازِم
jassim|jasim|jassem=جاسِم
kadhim|kazim|kadim|kadhem=كاظِم
luay|louay|loay=لُؤَي
maytham|maitham=مَيْثَم
nawfal=نَوْفَل
osama|usama|oussama=أُسامَة
qusay|qusai|kusay=قُصَي
sabah=صَباح
shihab|shehab=شِهاب
suhaib|sohaib|souhaib=صُهَيْب
thaer|thair=ثائِر
ubaid|obaid=عُبَيْد
yaqoob|yacoub|yakoub|yaqub=يَعْقُوب
sadiq|sadeq|sadek=صادِق
raheem|rahim=رَحِيم
saeed|said|sayid=سَعِيد
shaker=شاكِر
sabri=صَبْرِي
fahd|fahad=فَهْد
faisal|faysal=فَيْصَل
ghassan=غَسّان
hani=هانِي
jalal=جَلال
kamil|kamel=كامِل
mansour|mansur=مَنْصُور
marwan=مَرْوان
mohab|muhab=مُهاب
nizar=نِزار
rabee|rabi=رَبِيع
saleh|salih=صالِح
shadi=شادِي
sharif|shareef=شَرِيف
tamer|tamir=تامِر
wael|wail=وائِل
yousry=يُسْرِي
zakaria|zakariya=زَكَرِيّا
ziyad=زِياد
fatima|fatema|fatma|fatimah=فاطِمَة
zahra|zahraa|zehra=زَهْراء
zainab|zaynab|zeinab|zeynab=زَيْنَب
mariam|maryam|mariyam|marium=مَرْيَم
aisha|ayesha|aysha=عائِشَة
khadija|khadeeja|khadeja=خَدِيجَة
sara|sarah|sarra=سارَة
hiba|heba|hebah=هِبَة
huda|hoda=هُدى
amal=أَمَل
rana=رَنا
rania|ranya=رانِيَة
dina|deena=دِينا
lina|leena=لِينا
layla|laila|leila|lila=لَيْلى
nada=نَدى
rasha=رَشا
reem|rim|ream=رِيم
ruba|rouba=رُبى
shahad=شَهَد
shaimaa|shaima=شَيْماء
tabarak|tabaruk=تَبارَك
wafaa|wafa=وَفاء
yasmin|yasmeen|jasmin|yasameen=ياسْمِين
banin|baneen=بَنِين
bushra|boshra=بُشْرى
duaa|dua|doaa=دُعاء
esraa|israa|isra=إِسْراء
ghufran|ghofran=غُفْران
hanan=حَنان
hawraa|hawra|hawraa=حَوْراء
kawthar|kawther=كَوْثَر
marwa=مَرْوَة
nawal=نَوال
noora|nora|nourah=نُورَة
rawan=رَوان
rusul|rusl=رُسُل
saja=سَجى
samar=سَمَر
sawsan=سَوْسَن
shams=شَمْس
suha|soha=سُها
tuqa|tuka=تُقى
zeena|zena|zina|zeina=زِينَة
asmaa|asma=أَسْماء
batool|batul=بَتُول
dalia|dalya=دالْيا
eman|iman|emaan=إِيمان
farah=فَرَح
hajar|hagar=هاجَر
hala=هالَة
hind=هِنْد
jana=جَنى
lamees|lamis=لَمِيس
maha=مَها
nagham=نَغَم
noof=نُوف
rand=رَنْد
razan=رَزان
rahma=رَحْمَة
safaa|safa=صَفاء
sana=سَناء
shatha=شَذى
sundus=سُنْدُس
taghreed=تَغْرِيد
walaa|wala=وَلاء
zahraa=زَهْراء
abu|abo|aboo=أَبُو
um|umm|om=أُم
baba=بابا
mama=ماما
khalo|khalu|khaloo=خالُو
amo|ammo|amu=عَمُّو
jidi|jiddi=جِدِّي
bibi=بِيبِي
dr|doctor|doktor=دُكْتُور
eng|engineer|mohandis=مُهَنْدِس
ustad|ustath|ostaz=أُسْتاذ
haj|hajj|hajji=حاج
sayed|sayyid|seyed=سَيِّد
sheikh|shiekh|shaikh=شَيْخ
"""

    private const val MARKS = "\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652\u0670\u0640"

    private val tables by lazy { build() }
    private val latin: HashMap<String, String> get() = tables.first
    private val arabic: HashMap<String, String> get() = tables.second

    private fun bare(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (MARKS.indexOf(c) < 0) sb.append(c)
        return sb.toString()
    }

    private fun build(): Pair<HashMap<String, String>, HashMap<String, String>> {
        val l = HashMap<String, String>(600)
        val a = HashMap<String, String>(260)
        for (raw in TABLE.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val value = line.substring(eq + 1).trim()
            for (sp in line.substring(0, eq).split('|')) {
                val k = sp.trim()
                if (k.isNotEmpty()) l[k] = value
            }
            a[bare(value)] = value
        }
        return Pair(l, a)
    }

    // ---- turning latin letters into something an Arabic voice can read ----

    private val DI = mapOf(
        "ph" to "ف", "sh" to "ش", "ch" to "تْش", "kh" to "خ", "gh" to "غ",
        "th" to "ث", "dh" to "ذ", "ck" to "ك", "qu" to "كو",
        "aa" to "ا", "ee" to "ي", "ea" to "ي", "oo" to "و", "ou" to "و",
        "ai" to "اي", "ay" to "اي", "ei" to "ي", "ey" to "ي", "ie" to "ي",
        "oa" to "و", "au" to "و"
    )
    private val CON = mapOf(
        'b' to "ب", 'c' to "ك", 'd' to "د", 'f' to "ف", 'g' to "ج", 'h' to "ه",
        'j' to "ج", 'k' to "ك", 'l' to "ل", 'm' to "م", 'n' to "ن", 'p' to "ب",
        'q' to "ق", 'r' to "ر", 's' to "س", 't' to "ت", 'v' to "ف", 'w' to "و",
        'x' to "كس", 'y' to "ي", 'z' to "ز"
    )
    private val HAR = mapOf('a' to "\u064E", 'e' to "\u0650", 'i' to "\u0650",
        'o' to "\u064F", 'u' to "\u064F")
    private val INIT = mapOf('a' to "أَ", 'e' to "إِ", 'i' to "إِ", 'o' to "أُ", 'u' to "أُ")
    private const val LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوية"

    fun translit(word: String): String {
        val w = StringBuilder()
        for (c in word.lowercase()) if (c in 'a'..'z') w.append(c)
        if (w.isEmpty()) return ""
        val out = StringBuilder(w.length * 2)
        var i = 0
        while (i < w.length) {
            if (i + 1 < w.length) {
                val two = w.substring(i, i + 2)
                val d = DI[two]
                if (d != null) { out.append(d); i += 2; continue }
            }
            val c = w[i]
            val last = i == w.length - 1
            val h = HAR[c]
            if (h != null) {
                when {
                    i == 0 -> out.append(INIT[c])
                    last && c == 'a' -> out.append("ا")
                    last && (c == 'e' || c == 'i') -> out.append("ي")
                    last -> out.append("و")
                    else -> out.append(h)
                }
            } else {
                CON[c]?.let { out.append(it) }
            }
            i++
        }
        // a consonant straight against another consonant takes a sukun, which is
        // what stops the voice inventing a vowel between them
        val fin = StringBuilder(out.length + 4)
        for (k in out.indices) {
            fin.append(out[k])
            val a = out[k]
            val b = if (k + 1 < out.length) out[k + 1] else ' '
            if (LETTERS.indexOf(a) >= 0 && LETTERS.indexOf(b) >= 0 &&
                a != 'ا' && a != 'و' && a != 'ي'
            ) fin.append('\u0652')
        }
        return fin.toString()
    }

    // ---- what people actually save in their contacts ----

    private val JUNK = hashSetOf(
        "whatsapp", "واتساب", "واتس", "telegram", "تلكرام", "تليجرام", "تلغرام",
        "عمل", "work", "home", "بيت", "mobile", "موبايل", "جوال", "new", "جديد",
        "old", "قديم", "viber", "فايبر", "رقم", "tel", "phone", "زين", "اسياسيل",
        "كورك", "asiacell", "zain", "korek"
    )

    private fun keep(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' ||
            (c in '\u0621'..'\u064A') || c == '\u0671' ||
            c == '\u067E' || c == '\u0686' || c == '\u06A4' || c == '\u06AF' ||
            c == ' '

    fun clean(raw: String): String {
        val s = StringBuilder(raw.length)
        var depth = 0
        for (c in raw) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth > 0) depth--; s.append(' ') }
                else -> if (depth == 0) s.append(if (keep(c)) c else ' ')
            }
        }
        // Every word he saved gets read. Only the filing comes off: the brackets,
        // the emoji, the digits, the carrier's name. What is left is his name for
        // that person, however many words it runs to.
        return s.toString().split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in JUNK }
            .joinToString(" ")
    }

    private fun isLatin(w: String): Boolean {
        for (c in w) if (c in 'a'..'z' || c in 'A'..'Z') return true
        return false
    }

    /** The announcement, split into the pieces each voice should read. */
    fun parts(raw: String, mode: Int): List<Part> {
        val out = ArrayList<Part>(4)
        for (w in clean(raw).split(' ')) {
            if (w.isEmpty()) continue
            if (isLatin(w)) {
                val hit = latin[w.lowercase()]
                when {
                    hit != null -> out.add(Part(hit, true))
                    mode == Mode.ARABIC -> out.add(Part(translit(w), true))
                    else -> out.add(Part(w, false))
                }
            } else {
                out.add(Part(arabic[bare(w)] ?: w, true))
            }
        }
        return out
    }

    /** Everything in one Arabic string — for engines with no English voice. */
    fun flatten(parts: List<Part>): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(if (p.arabic) p.text else translit(p.text))
        }
        return sb.toString()
    }
}
