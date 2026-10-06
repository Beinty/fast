# -*- coding: utf-8 -*-
"""A faithful model of Dict.correctNear, with every pass and weight switchable
so a change can be measured before it is shipped."""
import lex

# Letters an Arabic speaker mixes up because they sound alike, not because the
# keys sit together. ض for ظ, س for ص, ت for ط: these are spelling doubts, and
# they are far commoner than a random letter, so they are priced apart.
SOUND = ["ضظزذدط", "سصثش", "كقگ", "هحخ", "اع", "جچ", "تط", "فڤپ",
         # English: the vowels people mix up, and the letters that share a sound
         "aeiy", "eiy", "ou", "cks", "sz", "fv", "mn", "bp", "dt", "gj"]
SOUND_OF = {}
for g in SOUND:
    for c in g:
        SOUND_OF.setdefault(c, set()).update(x for x in g if x != c)


class Cfg:
    def __init__(self, **kw):
        self.w_near = 0.85
        self.w_far = 2.6
        self.w_swap = 1.8
        self.w_drop = 3.6
        self.w_add = 5.0
        self.w_two = 1.8
        self.w_sound = 2.6
        self.sound_multi = True   # a second slipped letter may be a sound-alike        # a letter that sounds like the one meant
        self.cut = 65000.0        # how far a candidate may be reached
        self.conf_scale = 65000.0 # the scale confidence is judged on, kept apart
                                  # from reachability so widening the search does
                                  # not quietly make every repair look surer
        self.two_gate = 22000.0
        # the passes added while measuring; off means "as shipped in v9.0"
        self.mixed = False        # near-sub + drop, near-sub + insert, near-sub + swap
        self.three = False        # three near slips
        self.three_gate = 12000.0
        self.gate2 = 0.34         # gates as a share of cut, so widening the
        self.gate3 = 0.18         # search does not disable them
        self.w_mix_drop = 4.4
        self.w_mix_add = 5.6
        self.w_mix_swap = 3.2
        self.w_three = 3.0
        self.rank = None          # alternative .rnk file to test
        self.bin = None           # alternative .bin file to test
        self.iraqi = False        # leave a dialect word alone, as known() does
        self.shape = False        # and leave alone anything built like dialect
        self.use_shipped_ctx = False
        self.topn = 6
        self.__dict__.update(kw)

    def copy(self, **kw):
        c = Cfg()
        c.__dict__.update(self.__dict__)
        c.__dict__.update(kw)
        return c


SHIPPED = Cfg()

last_confidence = 0.0
probes = 0


def known(word, arabic, cfg=SHIPPED):
    L = lex.lang(arabic, cfg.rank, cfg.bin)
    f = lex.fold(word, arabic)
    if not f:
        return True
    if arabic and (f in lex.IRAQI or f in lex.KEEP):
        return True
    if L.find(f) >= 0:
        return True
    if arabic and getattr(cfg, "shape", False):
        return lex.iraqi_shape(f, lambda w: L.find(w) >= 0 or w in lex.IRAQI
                               or w in lex.KEEP)
    return False


def correct(word, nears, arabic, rate=None, cfg=SHIPPED):
    """Returns the repaired word, or None. `nears[i]` is what sat beside letter i."""
    global last_confidence, probes
    L = lex.lang(arabic, cfg.rank, cfg.bin)
    if len(word) < 4 or len(word) > 18:
        return None
    f = lex.fold(word, arabic)
    n = len(f)
    if n < 4:
        return None
    base = list(f)
    if L.find(f) >= 0:
        return None
    if cfg.iraqi and arabic and (f in lex.IRAQI or f in lex.KEEP):
        return None
    if cfg.shape and arabic and lex.iraqi_shape(
            f, lambda w: L.find(w) >= 0 or w in lex.IRAQI or w in lex.KEEP):
        return None

    letters = lex.AR_LETTERS if arabic else lex.EN_LETTERS
    topn = 1 if rate is None else cfg.topn
    top = []           # [(score, index)] best first

    def offer(buf, weight):
        global probes
        probes += 1
        i = L.find("".join(buf))
        if i < 0:
            return
        sc = (L.rank[i] + 40) * weight
        if sc >= cfg.cut:
            return
        if top and sc >= top[-1][0] and len(top) >= topn:
            return
        for k, (s2, i2) in enumerate(top):
            if i2 == i:
                if sc < s2:
                    top[k] = (sc, i)
                    top.sort()
                return
        top.append((sc, i))
        top.sort()
        del top[topn:]

    def near_at(i):
        return nears[i] if i < len(nears) else ""

    # one letter replaced by another
    for i in range(n):
        nb = near_at(i)
        buf = base[:]
        for c in letters:
            if c == base[i]:
                continue
            buf[i] = c
            if c in nb:
                offer(buf, cfg.w_near)
            elif c in SOUND_OF.get(base[i], ()):
                offer(buf, cfg.w_sound)
            else:
                offer(buf, cfg.w_far)

    # two letters the wrong way round
    for i in range(n - 1):
        buf = base[:]
        buf[i], buf[i + 1] = base[i + 1], base[i]
        offer(buf, cfg.w_swap)

    # one letter too many
    if n > 4:
        for i in range(n):
            offer(base[:i] + base[i + 1:], cfg.w_drop)

    # one letter missing
    for i in range(n + 1):
        for c in letters:
            offer(base[:i] + [c] + base[i:], cfg.w_add)

    def best():
        return top[0][0] if top else None

    # What a second or third slipped letter is allowed to be: the keys beside it
    # and the letters that sound like it. Not every letter — two free letters is
    # a million spellings per word, and almost none of them is what he meant.
    def alts(i):
        c = base[i]
        return set(near_at(i)) | (SOUND_OF.get(c, set()) if cfg.sound_multi else set())

    gate2 = cfg.cut * cfg.gate2
    gate3 = cfg.cut * cfg.gate3

    # two letters that both slipped
    if (not top) or top[0][0] > gate2:
        for i in range(n):
            for ci in alts(i):
                if ci == base[i]:
                    continue
                b1 = base[:]
                b1[i] = ci
                for j in range(i + 1, n):
                    keep = b1[j]
                    for cj in alts(j):
                        if cj == base[j]:
                            continue
                        b1[j] = cj
                        offer(b1, cfg.w_two)
                    b1[j] = keep

    # one slipped letter plus one lost, gained or turned around
    if cfg.mixed and ((not top) or top[0][0] > gate2):
        for i in range(n):
            for ci in alts(i):
                if ci == base[i]:
                    continue
                b1 = base[:]
                b1[i] = ci
                if n > 4:
                    for j in range(n):
                        if j == i:
                            continue
                        offer(b1[:j] + b1[j + 1:], cfg.w_mix_drop)
                for j in range(n + 1):
                    for c in letters:
                        offer(b1[:j] + [c] + b1[j:], cfg.w_mix_add)
                for j in range(n - 1):
                    if j == i or j + 1 == i:
                        continue
                    b2 = b1[:]
                    b2[j], b2[j + 1] = b1[j + 1], b1[j]
                    offer(b2, cfg.w_mix_swap)

    # three letters that all slipped
    if cfg.three and ((not top) or top[0][0] > gate3):
        for i in range(n):
            for ci in alts(i):
                if ci == base[i]:
                    continue
                b1 = base[:]
                b1[i] = ci
                for j in range(i + 1, n):
                    for cj in alts(j):
                        if cj == base[j]:
                            continue
                        b2 = b1[:]
                        b2[j] = cj
                        for k in range(j + 1, n):
                            keep = b2[k]
                            for ck in alts(k):
                                if ck == base[k]:
                                    continue
                                b2[k] = ck
                                offer(b2, cfg.w_three)
                            b2[k] = keep

    if not top:
        return None

    pick = top[0][1]
    if rate is not None:
        bestsc = None
        for sc, i in top:
            s = sc * rate(L.shown[i])
            if bestsc is None or s < bestsc:
                bestsc, pick = s, i

    out = L.shown[pick]
    if out == word:
        return None
    second = top[1][0] if len(top) > 1 else None
    last_confidence = confidence(top[0][0], second, cfg)
    return out


def confidence(best, second, cfg=SHIPPED):
    """How sure the repair is, from the search alone.

    Measured against five thousand real slips, how good the winner looks on its
    own carries no information at all: precision is the same 64% whether the
    winner scored a tenth of the ceiling or nine tenths of it. What does carry
    information is how far ahead of the runner-up it is, and as a ratio, not a
    difference — a winner at 10,000 against 11,000 is a coin toss whatever the
    numbers, and the old formula called it certain.
    """
    if second is None or second <= 0:
        m = 1.0
    else:
        m = min(1.0, max(0.0, 1.0 - best / second))
    return min(1.0, max(0.0, 0.45 + 0.55 * m))


SURE = 0.72
