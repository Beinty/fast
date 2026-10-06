# -*- coding: utf-8 -*-
"""Finds the weights by measuring them, one at a time, against a realistic mix
of mistakes rather than against equal numbers of each.

The weights in Dict.kt were reasoned about, not measured: "a dropped letter is
less likely than a neighbouring key". The reasoning is sound and the numbers
were still wrong — a missing letter cost 5.0 against 0.85 for a neighbour, so
a wrong common word one key away beat the right word with one letter put back,
and only one missing letter in six was recovered.
"""
import sys
import eng, suite

# How often each kind of slip really happens to a thumb. A finger that lands
# beside the key, or skips one, is ordinary; three letters wrong at random is
# not a typing slip at all, so it is counted but barely.
MIX = {
    "حرف يشبهه صوتا": 10,
    "زر مجاور واحد": 25, "زرين مجاورين": 10, "ثلاثة أزرار مجاورة": 2,
    "حرف ناقص": 22, "حرف زائد": 18, "حرفين مقلوبين": 12,
    "حرف واحد غلط": 8, "حرفين غلط": 2, "ثلاثة حروف غلط": 1,
}

import os
CAP = int(os.environ.get("TUNE_CAP", "0"))     # fewer cases while searching
DATA = suite.cases()
NEARS = {k: [(t, w, suite.nears_cfg(t, suite.GEN_RH)) for t, w in (v[:CAP] if CAP else v)]
         for k, v in DATA.items()}


def score(cfg, detail=False):
    import lex
    tot = num = 0.0
    out = {}
    for name, weight in MIX.items():
        items = NEARS[name]
        hit = 0
        for typed, want, ns in items:
            got = eng.correct(typed, ns, True, rate=lambda w: 1.0, cfg=cfg)
            if got and lex.fold(got, True) == want:
                hit += 1
        pc = 100.0 * hit / len(items)
        out[name] = pc
        tot += pc * weight
        num += weight
    # a dialect word changed is worse than a typo left standing
    bad = 0
    for w in lex.IRAQI:
        if len(w) < 4:
            continue
        got = eng.correct(w, suite.nears_for(w), True, rate=lambda x: 1.0, cfg=cfg)
        if got and lex.fold(got, True) != w:
            bad += 1
    val = tot / num - 2.0 * bad
    return (val, out, bad) if detail else val


FIELDS = {
    "w_near": [0.85, 1.0, 1.15, 1.3, 1.5],
    "w_far":  [2.6, 3.4, 4.2, 5.5, 7.0],
    "w_swap": [0.7, 0.85, 1.0, 1.2, 1.5],
    "w_drop": [2.4, 2.8, 3.2, 3.8, 4.6],
    "w_add":  [0.6, 0.8, 1.0, 1.3, 1.8],
    "w_two":  [1.2, 1.8, 2.4, 3.0, 3.8],
    "w_sound": [1.2, 1.5, 1.8, 2.2, 2.8],
    "w_mix_drop": [2.0, 3.0, 4.4, 6.0],
    "w_mix_add": [2.0, 3.0, 4.4, 6.0],
    "w_mix_swap": [1.6, 2.4, 3.2, 4.4],
    "w_three": [2.0, 3.0, 4.0, 5.5],
    "gate2": [0.12, 0.2, 0.34, 0.5],
    "gate3": [0.06, 0.12, 0.2, 0.34],
    "cut":    [170000.0, 230000.0, 300000.0],
}


def descend(cfg, rounds=3):
    best = score(cfg)
    print("البداية %.2f" % best)
    for r in range(rounds):
        moved = False
        for f, vals in FIELDS.items():
            cur = getattr(cfg, f)
            bv, bs = cur, best
            for v in vals:
                if v == cur:
                    continue
                s = score(cfg.copy(**{f: v}))
                if s > bs + 0.05:
                    bv, bs = v, s
            if bv != cur:
                setattr(cfg, f, bv)
                best = bs
                moved = True
                print("  %-7s %-8s -> %-8s  %.2f" % (f, cur, bv, best))
        if not moved:
            break
    return cfg, best


if __name__ == "__main__":
    start = eng.Cfg(iraqi=True, shape=True, use_shipped_ctx=True,
                    w_near=1.3, w_far=5.5, w_swap=0.85, w_drop=3.8, w_add=0.8,
                    w_two=1.8, w_sound=1.8, cut=230000.0, gate2=0.2,
                    gate3=0.18, w_three=5.5,
                    mixed="mixed" in sys.argv, three="three" in sys.argv)
    cfg, best = descend(start)
    val, out, bad = score(cfg, True)
    print("\nالأوزان:", " ".join("%s=%s" % (f, getattr(cfg, f)) for f in FIELDS))
    for k in MIX:
        print("  %-22s %5.1f%%" % (k, out[k]))
    print("الدرجة الموزونة %.2f، عراقي متضرر %d" % (val, bad))
