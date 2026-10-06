# -*- coding: utf-8 -*-
"""Rebuilds the rank file on a compressed scale.

The shipped file stores a word's position in the frequency list, straight, in
two bytes. Two hundred thousand words do not fit in sixty-five thousand
values, so 72.5% of the dictionary sits on the ceiling, 65535 — and since a
candidate's score is (rank + 40) x the weight of the slip, a word on the
ceiling costs 236,000 to reach by an inserted letter against a ceiling of
65,000. Those words were not merely unlikely. They were unreachable.

This writes a cost on a curve instead: the commonest word still costs nothing,
the rarest word costs `top`, and nothing is capped, so every word in the
dictionary can be reached by every kind of slip.
"""
import math, os, struct, sys
import lex

FREQ = os.path.join(os.path.dirname(__file__), "freq_ar.txt")


def positions(path=FREQ):
    pos = {}
    with open(path, encoding="utf-8") as f:
        for i, ln in enumerate(f):
            w = lex.fold(ln.strip(), True)
            if w and w not in pos:
                pos[w] = i
    return pos


def build(out, top=11000.0, power=1.0, arabic=True, pos=None):
    L = lex.lang(arabic)
    pos = pos if pos is not None else positions()
    n = max(pos.values()) + 1 if pos else 1
    den = math.log(1.0 + n)
    vals = []
    for i, w in enumerate(L.fold):
        p = pos.get(w)
        if p is None:
            p = n                     # unknown to the corpus: as rare as it gets
        v = top * (math.log(1.0 + p) / den) ** power
        vals.append(min(65535, int(round(v))))
    with open(out, "wb") as f:
        f.write(struct.pack(">%dH" % len(vals), *vals))
    return vals


if __name__ == "__main__":
    pos = positions()
    for top, power in [(8000, 1.0), (11000, 1.0), (12900, 1.0),
                       (11000, 1.5), (11000, 2.0), (12900, 2.0)]:
        name = "/tmp/rnk_%d_%s.rnk" % (top, str(power).replace(".", ""))
        v = build(name, top, power, pos=pos)
        v2 = sorted(v)
        print("%-28s min %5d median %5d max %5d" % (os.path.basename(name),
                                                    v2[0], v2[len(v2) // 2], v2[-1]))
