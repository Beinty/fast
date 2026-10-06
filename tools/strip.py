# -*- coding: utf-8 -*-
"""The suggestion strip, as HucKeyboard.refreshSugg builds it, measured on text
the keyboard has never seen.

The question it answers: as he types a word letter by letter, after how many
letters does the word he wants appear among the three on the strip? That number
is what "the keyboard knows a lot of words" actually feels like.
"""
import os, re, sys, time
import lex

MAX_SUGG = 3
CORPUS = "/tmp/corp/padt.conllu"


def sentences(path=CORPUS, limit=500):
    """The plain text lines of a treebank, as words."""
    out = []
    for ln in open(path, encoding="utf-8"):
        if not ln.startswith("# text = "):
            continue
        t = ln[9:].strip()
        ws = [w for w in re.split(r"[^ء-ي]+", t) if len(w) >= 2]
        if len(ws) >= 4:
            out.append([lex.fold(w, True) for w in ws])
        if len(out) >= limit:
            break
    return out


# ---- the tables the app ships ---------------------------------------------

def tables():
    big = lex.bigrams(True)
    tri = {}
    path = os.path.join(lex.ASSETS, "trigrams_ar.txt")
    for ln in open(path, encoding="utf-8"):
        p = ln.split()
        if len(p) > 2:
            tri[(lex.fold(p[0], True), lex.fold(p[1], True))] = \
                [lex.fold(x, True) for x in p[2:]]
    return big, tri


BIG, TRI = tables()
L = lex.lang(True)
SORTED = L.fold          # the .bin is sorted, so a prefix is one binary search


IDXS = None


def lower_bound(p):
    lo, hi = 0, len(SORTED)
    while lo < hi:
        mid = (lo + hi) // 2
        if SORTED[mid] < p:
            lo = mid + 1
        else:
            hi = mid
    return lo


def predict_prefix(prefix, n=MAX_SUGG, scanned_cap=150000):
    """Dict.predict: every word in the dictionary starting with the prefix,
    commonest first. No list, no cap on vocabulary."""
    if not prefix:
        return []
    i = lower_bound(prefix)
    best = []
    seen = 0
    while i < len(SORTED) and seen < scanned_cap:
        w = SORTED[i]
        if not w.startswith(prefix):
            break
        best.append((L.rank[i], w))
        i += 1
        seen += 1
    best.sort()
    return [w for _, w in best[:n] if w != prefix]


_pcache = {}


def predict_cached(prefix, n=MAX_SUGG):
    key = (prefix, n)
    v = _pcache.get(key)
    if v is None:
        v = predict_prefix(prefix, n)
        _pcache[key] = v
    return v


def next_after(prev2, prev, n=6):
    """Dict.nextAfter: the trigram table first, then the bigram table."""
    out = []
    if prev2:
        for w in TRI.get((prev2, prev), []):
            if w not in out:
                out.append(w)
            if len(out) >= n:
                return out
    for w in BIG.get(prev, []):
        if w not in out:
            out.append(w)
        if len(out) >= n:
            break
    return out


def next_after_prefix(prev2, prev, prefix, n=MAX_SUGG):
    """What the app does NOT do: the previous word AND the letters so far,
    together. This is the whole of "هلا ش" -> "هلا شلونك"."""
    out = []
    if not prefix:
        return out
    if prev2:
        for w in TRI.get((prev2, prev), []):
            if w.startswith(prefix) and w not in out:
                out.append(w)
    for w in BIG.get(prev, []):
        if w.startswith(prefix) and w not in out:
            out.append(w)
    return out[:n]


# ---- the strip -------------------------------------------------------------

def strip(word, prev, prev2, order):
    """The three zones. `order` names which arrangement is being measured."""
    zones = []

    def put(ws):
        for w in ws:
            if len(zones) >= MAX_SUGG:
                return
            if w and w != word and w not in zones:
                zones.append(w)

    if not word:
        put(next_after(prev2, prev))
        return zones

    known = L.find(word) >= 0 or word in lex.IRAQI or word in lex.KEEP

    if order == "A":            # v10.0, as shipped
        if known:
            put(next_after(prev2, word))
        put(predict_cached(word))
    elif order == "B":          # history+prefix, then what follows the word
        if prev:
            put(next_after_prefix(prev2, prev, word))
        if known:
            put(next_after(prev2, word))
        put(predict_cached(word))
    elif order == "C":          # history+prefix, completions, then the rest
        if prev:
            put(next_after_prefix(prev2, prev, word))
        put(predict_cached(word))
        if known:
            put(next_after(prev2, word))
    elif order == "E":          # a finished word asks what follows it, first
        if known:
            put(next_after(prev2, word))
        if prev:
            put(next_after_prefix(prev2, prev, word))
        put(predict_cached(word))
    elif order == "D":          # history+prefix only, then completions
        if prev:
            put(next_after_prefix(prev2, prev, word))
        put(predict_cached(word))
    return zones


def run(order, sents=None, show=True):
    sents = sents or sentences()
    # hits[k] = the word was on the strip after k letters typed
    need = [0] * 9
    total = 0
    found_at = []
    t0 = time.time()
    calls = 0
    for ws in sents:
        for i in range(1, len(ws)):
            want = ws[i]
            if len(want) < 3:
                continue
            prev, prev2 = ws[i - 1], (ws[i - 2] if i >= 2 else "")
            total += 1
            at = None
            for k in range(0, min(len(want), 8) + 1):
                z = strip(want[:k], prev, prev2, order)
                calls += 1
                if want in z:
                    at = k
                    break
            found_at.append(at if at is not None else 99)
    ms = (time.time() - t0) * 1000 / max(1, calls)
    for a in found_at:
        if a <= 8:
            need[a] += 1
    if show:
        print("كلمات مجرّبة %d، زمن الاقتراح %.2f ms" % (total, ms))
        run_tot = 0
        for k in range(9):
            run_tot += need[k]
            label = "بدون حرف" if k == 0 else "بعد %d حرف" % k
            print("  %-12s %5.1f%%   (تراكمي %5.1f%%)"
                  % (label, 100.0 * need[k] / total, 100.0 * run_tot / total))
        print("  ما ظهرت أبدا  %5.1f%%" % (100.0 * (total - run_tot) / total))
    return need, total, ms


if __name__ == "__main__":
    s = sentences()
    names = {"A": "كما يشتغل الآن", "B": "سابقة+بادئة، ثم ما يجي بعد الكلمة",
             "C": "سابقة+بادئة، ثم الإكمال، ثم الباقي",
             "D": "سابقة+بادئة، ثم الإكمال فقط",
             "E": "الكلمة الكاملة تسأل عن التالي أولا"}
    for o in (sys.argv[1:] or ["A", "B", "C", "D"]):
        print("\n== %s ==" % names[o])
        run(o, s)
