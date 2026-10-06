# -*- coding: utf-8 -*-
"""The shipped dictionary, read from the real asset files."""
import os, struct

ASSETS = os.path.join(os.path.dirname(__file__), "..", "FastType",
                      "app", "src", "main", "assets")

AR_LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهويچگپڤ"
EN_LETTERS = "abcdefghijklmnopqrstuvwxyz"


def fold(s, arabic):
    if not arabic:
        return s.lower()
    out = []
    for c in s:
        if c in "أإآٱ":
            out.append("ا")
        elif c == "ى":
            out.append("ي")
        elif c == "ؤ":
            out.append("و")
        elif c == "ئ":
            out.append("ي")
        elif c == "ة":
            out.append("ه")
        elif c == "ـ":
            pass
        elif "ً" <= c <= "ْ":
            pass
        else:
            out.append(c)
    return "".join(out)


class Lang:
    def __init__(self, base, rankfile=None, binfile=None):
        blob = open(binfile or os.path.join(ASSETS, base + ".bin"), "rb").read()
        rnk = open(rankfile or os.path.join(ASSETS, base + ".rnk"), "rb").read()
        lines = blob.split(b"\n")
        self.fold = []
        self.shown = []
        for ln in lines:
            if not ln:
                continue
            if b"\t" in ln:
                a, b = ln.split(b"\t", 1)
                self.fold.append(a.decode("utf-8"))
                self.shown.append(b.decode("utf-8"))
            else:
                w = ln.decode("utf-8")
                self.fold.append(w)
                self.shown.append(w)
        n = min(len(self.fold), len(rnk) // 2)
        self.fold = self.fold[:n]
        self.shown = self.shown[:n]
        self.rank = list(struct.unpack(">%dH" % n, rnk[:n * 2]))
        self.idx = {w: i for i, w in enumerate(self.fold)}
        self.count = n

    def find(self, folded):
        return self.idx.get(folded, -1)


_cache = {}


def lang(arabic, rankfile=None, binfile=None):
    key = (arabic, rankfile, binfile)
    if key not in _cache:
        _cache[key] = Lang("dict_ar" if arabic else "dict_en", rankfile, binfile)
    return _cache[key]


IRAQI = set()


def load_iraqi():
    """The dialect set from Dict.kt, read straight out of the Kotlin source."""
    src = os.path.join(os.path.dirname(__file__), "..", "FastType", "app", "src",
                       "main", "java", "com", "huc", "fasttype", "Dict.kt")
    txt = open(src, encoding="utf-8").read()
    at = txt.index("IRAQI")
    end = txt.index("fun known", at)
    body = txt[at:end]
    out = set()
    for piece in body.split('"')[1::2]:
        if piece and all(c not in " \t\n" for c in piece):
            out.add(fold(piece, True))
    return out


IRAQI = load_iraqi()


def load_keep():
    """Brand, app and place names: vocabulary he types that no corpus carries."""
    out = set()
    try:
        for ln in open(os.path.join(ASSETS, "keep_ar.txt"), encoding="utf-8"):
            for w in ln.split():
                f = fold(w, True)
                if len(f) >= 3:
                    out.add(f)
    except OSError:
        pass
    return out


KEEP = load_keep()


_next = {}


def bigrams(arabic=True):
    """The shipped 'what follows what' table."""
    key = ("b", arabic)
    if key not in _next:
        m = {}
        path = os.path.join(ASSETS, "bigrams_ar.txt" if arabic else "bigrams_en.txt")
        for ln in open(path, encoding="utf-8"):
            p = ln.split()
            if len(p) > 1:
                m[fold(p[0], arabic)] = [fold(x, arabic) for x in p[1:]]
        _next[key] = m
    return _next[key]


# ---- Iraqi morphology ------------------------------------------------------
# The dialect is not a word list. "your" said to a woman is ـج on the end of
# any noun at all: بيتج، سيارتج، تلفونج، شغلج — and ـلج for "to you". A list of
# dialect words can never hold them, so the shape is recognised instead: strip
# the ending, and if what is left is a word, the whole thing was a word.
SUFFIX = [
    ("لج", ["لك", "ل", ""]), ("لچ", ["لك", "ل", ""]),
    ("تج", ["تك", "ته", "ت"]),
    ("ج", ["ك", "ه", ""]), ("چ", ["ك", "ه", ""]),
    ("كم", ["كم"]), ("هم", ["هم"]),
]
PREFIX = ["د", "ما", "مو", "ب", "ع", "لل", "و", "ف"]


def iraqi_shape(word, is_word):
    """True when the word is dialect built on a word: ends in the feminine ـج,
    or carries one of the little prefixes the dialect puts on a verb."""
    w = word
    for end, repls in SUFFIX:
        if not w.endswith(end) or len(w) - len(end) < 3:
            continue
        stem = w[:len(w) - len(end)]
        for r in repls:
            if is_word(stem + r):
                return True
    for p in PREFIX:
        if w.startswith(p) and len(w) - len(p) >= 3 and is_word(w[len(p):]):
            return True
    return False
