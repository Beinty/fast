# -*- coding: utf-8 -*-
import sys, random, math, time
sys.path.insert(0,'/tmp/claude-0/bench')
from engine import *

def nb_full(ch, reach=1.45):
    if ch not in KEY: return ""
    x,y=KEY[ch]; w=WIDTH[ch]*reach
    out=[]
    for c,(cx,cy) in KEY.items():
        if c==ch: continue
        dx=cx-x; dy=cy-y
        if math.hypot(dx,dy*0.95) <= max(w, ROW_H*1.15): out.append((math.hypot(dx,dy),c))
    out.sort()
    return "".join(c for _,c in out[:6])

def sample_words(n=500, max_rank=20000, lo=4, hi=11, seed=7):
    random.seed(seed)
    pool=[(F[i],S[i]) for i in range(len(F)) if R[i]<max_rank and lo<=len(F[i])<=hi]
    return random.sample(pool, min(n,len(pool)))

WORDS=sample_words()

def make_cases(p=0.22, seed=11):
    random.seed(seed)
    cases=[]
    for folded, shown in WORDS:
        out=[]
        for ch in folded:
            if ch in KEY and random.random()<p:
                x,y=KEY[ch]
                out.append(nearest(x+random.gauss(0,0.42*WIDTH[ch]),
                                   y+random.gauss(0,0.42*ROW_H)))
            else: out.append(ch)
        typed="".join(out)
        if typed!=folded: cases.append((typed, shown))
    return cases

CASES=make_cases()
print("typo cases:",len(CASES))

def score(label, **kw):
    hit=0; t0=time.time()
    for typed, shown in CASES:
        nears=[nb_full(c) for c in typed]
        fix,_=correct(typed, nears, **kw)
        if fix==shown: hit+=1
    dt=(time.time()-t0)/len(CASES)*1000
    print(f"{label:<40} {100*hit/len(CASES):5.1f}%   {dt:.1f}ms")
    return hit/len(CASES)

base=score("vertical neighbours (new baseline)")
for far in (2.0, 2.2, 2.6, 3.2, 4.0):
    score(f"  W_FAR={far}", w_far=far)
for near in (0.7, 0.85, 1.0):
    score(f"  W_NEAR={near}", w_near=near)
for swap in (1.2, 1.8, 2.4):
    score(f"  W_SWAP={swap}", w_swap=swap)
for add in (3.5, 5.0, 7.0):
    score(f"  W_ADD={add}", w_add=add)
for drop in (2.5, 3.6, 5.0):
    score(f"  W_DROP={drop}", w_drop=drop)
for cut in (20000, 30000, 45000, 65000):
    score(f"  CUT={cut}", cut=cut)
