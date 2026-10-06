# -*- coding: utf-8 -*-
"""Does the same mistake get the same repair, the hundredth time as the first?

This walks the whole path the keyboard walks — fixFor, isOwn, the dictionary,
the personal repairs, then the corrector — and keeps the stores on disk between
runs, so a restart is a real restart.
"""
import json, os, sys
import eng, kb, lex

STORE = "/tmp/userdict.json"
OWN, SHOW, STUBBORN, REJECTS_TO_KEEP, MAX_FIX = 4, 2, 25, 2, 1500


class UserDict:
    def __init__(self, path=STORE, fresh=False):
        self.path = path
        self.counts, self.fixes, self.fix_used = {}, {}, {}
        self.rejects, self.keep = {}, set()
        self.tick = 0
        if not fresh and os.path.exists(path):
            d = json.load(open(path, encoding="utf-8"))
            self.counts = d["counts"]; self.fixes = d["fixes"]
            self.fix_used = d["fixUsed"]; self.rejects = d["rejects"]
            self.keep = set(d["keep"]); self.tick = d["tick"]

    def save(self):
        json.dump({"counts": self.counts, "fixes": self.fixes,
                   "fixUsed": self.fix_used, "rejects": self.rejects,
                   "keep": sorted(self.keep), "tick": self.tick},
                  open(self.path, "w", encoding="utf-8"))

    def seen(self, w, ar):
        k = lex.fold(w, ar)
        if k in self.fixes:          # a slip with an answer is not vocabulary
            return
        self.counts[k] = self.counts.get(k, 0) + 1
        self.save()

    def learn_fix(self, bad, good, ar):
        k = lex.fold(bad, ar)
        if k == lex.fold(good, ar) or k in self.keep:
            return
        self.tick += 1
        self.fix_used[k] = self.tick
        self.counts.pop(k, None)
        if len(self.fixes) > MAX_FIX:
            cold = sorted(self.fixes, key=lambda x: self.fix_used.get(x, 0))
            for c in cold[:len(self.fixes) - MAX_FIX * 3 // 4]:
                self.fixes.pop(c, None); self.fix_used.pop(c, None)
        self.fixes[k] = good
        self.save()

    def fix_for(self, w, ar):
        k = lex.fold(w, ar)
        if k in self.keep:
            return None
        out = self.fixes.get(k)
        if out is None or out == w:
            return None
        self.fix_used[k] = self.tick
        return out

    def keep_as_is(self, w, ar):
        k = lex.fold(w, ar)
        self.fixes.pop(k, None)
        n = self.rejects.get(k, 0) + 1
        self.rejects[k] = n
        if n < REJECTS_TO_KEEP:
            self.save(); return False
        self.keep.add(k)
        self.counts[k] = self.counts.get(k, 0) + OWN
        self.save(); return True

    def is_own(self, w, ar, cfg):
        k = lex.fold(w, ar)
        if k in self.keep:
            return True
        c = self.counts.get(k, 0)
        if c < OWN:
            return False
        if c < STUBBORN and not eng.known(w, ar, cfg) and has_strong_fix(w, ar, cfg):
            return False
        return True


_memo = {}


def has_strong_fix(w, ar, cfg):
    key = (ar, w)
    if key in _memo:
        return _memo[key]
    if eng.known(w, ar, cfg):
        _memo[key] = False
        return False
    fix = eng.correct(w, [], ar, cfg=cfg)
    out = bool(fix) and fix != w and eng.last_confidence >= 0.70
    _memo[key] = out
    return out


def find_fix(ud, typed, near, ar, cfg):
    """HucKeyboard.findFix, in order."""
    own = ud.fix_for(typed, ar)
    if own is not None:
        return own, 1.0
    if ud.is_own(typed, ar, cfg):
        return None, 0.0
    if eng.known(typed, ar, cfg):
        return None, 0.0
    out = eng.correct(typed, near, ar, rate=lambda w: 1.0, cfg=cfg)
    return out, (eng.last_confidence if out else 0.0)


def run_tap(word, times=100, cfg=None, restart_every=20):
    """He taps the strip the first time. From then on it must need no tap at all.

    A repair below the applying line is not a repair that failed — it is one the
    keyboard will not make silently because two answers were tied. One tap
    settles which, and the tap is what must last.
    """
    cfg = cfg or eng.Cfg()
    if os.path.exists(STORE):
        os.remove(STORE)
    ud = UserDict(fresh=True)
    near = [kb.neighbours(kb.AR_BOARD, c, 1.45, 1.45) for c in word]
    pick = eng.correct(word, near, True, rate=lambda w: 1.0, cfg=cfg)
    hits = []
    for i in range(1, times + 1):
        if restart_every and i % restart_every == 0:
            ud.save(); ud = UserDict()
        fix, conf = find_fix(ud, word, near, True, cfg)
        if i == 1:
            ud.learn_fix(word, pick, True)     # the one tap
            ud.seen(pick, True)
            hits.append(True)
            continue
        hits.append(bool(fix) and conf >= eng.SURE and fix == pick)
        if hits[-1]:
            ud.learn_fix(word, fix, True); ud.seen(fix, True)
        else:
            ud.seen(word, True)
    return pick, hits


def run(word, want, times=100, cfg=None, restart_every=20, reject_at=None):
    cfg = cfg or eng.Cfg()
    if os.path.exists(STORE):
        os.remove(STORE)
    ud = UserDict(fresh=True)
    near = [kb.neighbours(kb.AR_BOARD, c, 1.45, 1.45) for c in word]
    hits = []
    for i in range(1, times + 1):
        if restart_every and i % restart_every == 0:
            ud.save()
            ud = UserDict()           # the keyboard was killed and came back
        fix, conf = find_fix(ud, word, near, True, cfg)
        applied = bool(fix) and conf >= eng.SURE
        hits.append(applied and fix == want)
        if applied:
            ud.learn_fix(word, fix, True)
            ud.seen(fix, True)
        else:
            ud.seen(word, True)
        if reject_at and i == reject_at:
            ud.keep_as_is(fix or word, True)
    return hits


if __name__ == "__main__":
    cases = [("اكلظ", None), ("اقلض", None), ("مشكوو", None), ("حياتب", None)]
    cfgname = sys.argv[1] if len(sys.argv) > 1 else "v10"
    import cfgs
    cfg, rh = cfgs.get(cfgname)
    print("== %s ==" % cfgname)
    for word, _ in cases:
        near = [kb.neighbours(kb.AR_BOARD, c, 1.45, rh) for c in word]
        first = eng.correct(word, near, True, rate=lambda w: 1.0, cfg=cfg)
        if first is None:
            print("%-8s لا يصلحه أصلا" % word)
            continue
        hits = run(word, first, 100, cfg)
        pick, taps = run_tap(word, 100, cfg)
        print("%-8s → %-8s  تلقائي %3d/100   بعد لمسة واحدة %3d/100"
              % (word, first, sum(hits), sum(taps)))
