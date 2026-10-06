# -*- coding: utf-8 -*-
"""The typing engine's test suite: eleven kinds of mistake, measured apart.

Every case is built from the shipped dictionary and the shipped bigram tables,
so the words tested are words he would actually type. Run it with no argument
for the shipped engine, or pass a config name from cfgs.py.
"""
import random, sys, time
import eng, kb, lex

random.seed(20261006)

AR = True
BOARD = kb.AR_BOARD
LETTERS = lex.AR_LETTERS


def nears_for(word):
    return [kb.neighbours(BOARD, c) for c in word]


def nears_cfg(word, rh):
    return [kb.neighbours(BOARD, c, 1.45, rh) for c in word]


# ---- the words we test on -------------------------------------------------

def test_words(n=1500, lo=4, hi=9):
    """Common words, long enough to mistype, short enough to be ordinary."""
    L = lex.lang(AR)
    out = []
    for i, w in enumerate(L.fold):
        if lo <= len(w) <= hi and L.rank[i] < 20000 and all(c in LETTERS for c in w):
            out.append(w)
    random.shuffle(out)
    return out[:n]


WORDS = test_words()


# ---- the eleven kinds of mistake ------------------------------------------

def sub_random(w, k):
    cs = list(w)
    for i in random.sample(range(len(cs)), k):
        cs[i] = random.choice([c for c in LETTERS if c != cs[i]])
    return "".join(cs)


def sub_near(w, k, rh=1.25):
    cs = list(w)
    spots = [i for i in range(len(cs)) if kb.neighbours(BOARD, cs[i], 1.45, rh)]
    if len(spots) < k:
        return None
    for i in random.sample(spots, k):
        cs[i] = random.choice(kb.neighbours(BOARD, cs[i], 1.45, rh))
    return "".join(cs)


def sub_sound(w, k=1):
    """A letter swapped for one that sounds like it: ض for ظ, س for ص."""
    cs = list(w)
    spots = [i for i in range(len(cs)) if eng.SOUND_OF.get(cs[i])]
    if len(spots) < k:
        return None
    for i in random.sample(spots, k):
        cs[i] = random.choice(sorted(eng.SOUND_OF[cs[i]]))
    return "".join(cs)


def drop(w):
    i = random.randrange(len(w))
    return w[:i] + w[i + 1:]


def add(w):
    i = random.randrange(len(w) + 1)
    return w[:i] + random.choice(LETTERS) + w[i:]


def swap(w):
    i = random.randrange(len(w) - 1)
    if w[i] == w[i + 1]:
        return None
    return w[:i] + w[i + 1] + w[i] + w[i + 2:]


NORM = {"ا": "أ", "ي": "ى", "ه": "ة", "و": "ؤ"}


def denorm(w):
    spots = [i for i, c in enumerate(w) if c in NORM]
    if not spots:
        return None
    i = random.choice(spots)
    return w[:i] + NORM[w[i]] + w[i + 1:]


GEN_RH = 1.45     # how far a finger really strays: fixed, so every config is
                  # measured on the same mistakes


def cases(rh=GEN_RH, N=500):
    """{category: [(typed, intended)]}. A case is kept only when the mistake
    actually produced a non-word, since a word is never touched."""
    def make(fn, want):
        out = []
        for w in WORDS:
            t = fn(w)
            if not t or t == w or eng.known(t, AR):
                continue
            out.append((t, w))
            if len(out) >= want:
                break
        return out

    return {
        "حرف واحد غلط": make(lambda w: sub_random(w, 1), N),
        "حرفين غلط": make(lambda w: sub_random(w, 2), N),
        "ثلاثة حروف غلط": make(lambda w: sub_random(w, 3), N),
        "حرف ناقص": make(drop, N),
        "حرف زائد": make(add, N),
        "حرفين مقلوبين": make(swap, N),
        "حرف يشبهه صوتا": make(lambda w: sub_sound(w, 1), N),
        "زر مجاور واحد": make(lambda w: sub_near(w, 1, rh), N),
        "زرين مجاورين": make(lambda w: sub_near(w, 2, rh), N),
        "ثلاثة أزرار مجاورة": make(lambda w: sub_near(w, 3, rh), N),
    }


def norm_cases(want=500):
    """أ ى ة ؤ ئ: the word must be left exactly as he wrote it, not 'repaired'."""
    out = []
    for w in WORDS:
        t = denorm(w)
        if not t or t == w:
            continue
        out.append(t)
        if len(out) >= want:
            break
    return out


def ctx_rate(prev, cfg):
    """What the IME hands the corrector as `rate`, modelled on UserDict.contextWeight.
    `use_shipped` adds the bigram table the app ships but never consults here."""
    big = lex.bigrams(AR)
    after = big.get(lex.fold(prev, AR), [])
    def rate(cand):
        w = 1.0
        if getattr(cfg, "use_shipped_ctx", False):
            k = lex.fold(cand, AR)
            if k in after[:3]:
                w *= 0.45
            elif k in after:
                w *= 0.65
        return w
    return rate


def context_cases(cfg, rh, want=500):
    """A mistyped word whose best spelling is the wrong word, where the word
    before it says which was meant. Built from the shipped bigram table."""
    big = lex.bigrams(AR)
    L = lex.lang(AR, cfg.rank)
    plain = eng.Cfg(); plain.__dict__.update(cfg.__dict__); plain.use_shipped_ctx = False
    n = ok = 0
    for prev, after in big.items():
        if n >= want:
            break
        for want_w in after[:2]:
            if not (4 <= len(want_w) <= 9) or L.find(want_w) < 0:
                continue
            if not all(c in LETTERS for c in want_w):
                continue
            typed = sub_near(want_w, 1, GEN_RH)
            if not typed or typed == want_w or eng.known(typed, AR, cfg):
                continue
            ns = nears_cfg(typed, rh)
            blind = eng.correct(typed, ns, AR, rate=lambda w: 1.0, cfg=plain)
            if blind is None or lex.fold(blind, AR) == want_w:
                continue    # spelling alone already got it; nothing for context to do
            n += 1
            got = eng.correct(typed, ns, AR, rate=ctx_rate(prev, cfg), cfg=cfg)
            if got and lex.fold(got, AR) == want_w:
                ok += 1
            break
    return n, ok


# ---- running ---------------------------------------------------------------

def run(cfg, rh=1.25, show=True):
    data = cases(GEN_RH)
    rows = []
    total_hit = total = 0
    t0 = time.time()
    nwords = 0
    for name, items in data.items():
        hit = sure_hit = 0
        for typed, want in items:
            ns = nears_cfg(typed, rh)
            got = eng.correct(typed, ns, AR, rate=lambda w: 1.0, cfg=cfg)
            nwords += 1
            if got and lex.fold(got, AR) == want:
                hit += 1
                if eng.last_confidence >= eng.SURE:
                    sure_hit += 1
        rows.append((name, len(items), hit, sure_hit))
        total_hit += hit
        total += len(items)
    ms = (time.time() - t0) * 1000.0 / max(1, nwords)

    # أ ى ة: the shape he wrote must survive untouched
    norm = norm_cases()
    norm_ok = 0
    for t in norm:
        if eng.known(t, AR, cfg) and eng.correct(t, nears_cfg(t, rh), AR,
                                                 rate=lambda w: 1.0, cfg=cfg) is None:
            norm_ok += 1
    rows.append(("همزة وتاء وياء", len(norm), norm_ok, norm_ok))

    # context: two candidates one edit away, the sentence has to pick
    ctx_n, ctx_ok = context_cases(cfg, rh)
    if ctx_n:
        rows.append(("يعتمد على الجملة", ctx_n, ctx_ok, ctx_ok))

    # Iraqi dialect: nothing here may be changed
    bad = []
    for w in sorted(lex.IRAQI):
        if len(w) < 4:
            continue
        got = eng.correct(w, nears_for(w), AR, rate=lambda x: 1.0, cfg=cfg)
        if got and lex.fold(got, AR) != w:
            bad.append((w, got))
    iraqi_n = len([w for w in lex.IRAQI if len(w) >= 4])

    if show:
        print("%-22s %5s %7s %7s" % ("الحالة", "عدد", "صحّح", "ونفّذ"))
        for name, n, hit, sure in rows:
            print("%-22s %5d %6.1f%% %6.1f%%" % (name, n, 100.0 * hit / n,
                                                 100.0 * sure / n))
        print("%-22s %5d %6.1f%%" % ("الكل", total, 100.0 * total_hit / total))
        print("عراقي سليم: %d/%d" % (iraqi_n - len(bad), iraqi_n),
              ("أخطأ بـ " + ", ".join("%s→%s" % b for b in bad[:6])) if bad else "")
        print("زمن الكلمة: %.2f ms" % ms)
    return 100.0 * total_hit / total, ms, len(bad), rows


if __name__ == "__main__":
    import cfgs
    name = sys.argv[1] if len(sys.argv) > 1 else "shipped"
    cfg, rh = cfgs.get(name)
    print("== %s ==" % name)
    run(cfg, rh)
