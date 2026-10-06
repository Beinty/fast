# -*- coding: utf-8 -*-
import sys, random, math, time
sys.path.insert(0,'/tmp/claude-0/bench')
from engine import *

random.seed(7)

def sample_words(n=400, max_rank=20000, lo=4, hi=10):
    pool=[(F[i],S[i]) for i in range(len(F)) if R[i]<max_rank and lo<=len(F[i])<=hi]
    return random.sample(pool, min(n,len(pool)))

def slip(word, p=0.22, sx=0.42, sy=0.42):
    """A finger aimed at each letter and landing a little off."""
    out=[]; nears=[]
    for ch in word:
        if ch not in KEY: out.append(ch); nears.append(""); continue
        if random.random()<p:
            x,y=KEY[ch]
            px=x+random.gauss(0,sx*WIDTH[ch]); py=y+random.gauss(0,sy*ROW_H)
            got=nearest(px,py)
        else:
            got=ch
        out.append(got); nears.append(neighbours(got))
    return "".join(out), nears

def run(label, nb=None, **kw):
    words=sample_words()
    hit=typos=0; t0=time.time(); conf_hit=[]; conf_miss=[]
    for folded, shown in words:
        typed, nears = slip(folded)
        if typed==folded: continue
        typos+=1
        if nb is not None: nears=[nb(c) for c in typed]
        fix, conf = correct(typed, nears, **kw)
        if fix==shown or (fix and fix==shown):
            hit+=1; conf_hit.append(conf)
        else: conf_miss.append(conf)
    dt=(time.time()-t0)/max(1,typos)*1000
    print(f"{label:<34} typos {typos:3d}  top-1 {100*hit/max(1,typos):5.1f}%  "
          f"conf hit {sum(conf_hit)/max(1,len(conf_hit)):.2f} miss {sum(conf_miss)/max(1,len(conf_miss)):.2f}  "
          f"{dt:.0f}ms/word")
    return hit/max(1,typos)

print("=== baseline, as shipped ===")
run("current")

def nb_full(ch, reach=1.45):
    """Neighbours including the rows above and below."""
    if ch not in KEY: return ""
    x,y=KEY[ch]; w=WIDTH[ch]*reach
    out=[]
    for c,(cx,cy) in KEY.items():
        if c==ch: continue
        dx=cx-x; dy=(cy-y)
        if math.hypot(dx,dy*0.95) <= max(w, ROW_H*1.15): out.append((math.hypot(dx,dy),c))
    out.sort()
    return "".join(c for _,c in out[:6])

print("\n=== with the keys above and below counted as neighbours ===")
run("vertical neighbours too", nb=nb_full)
