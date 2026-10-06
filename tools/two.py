# -*- coding: utf-8 -*-
import sys, random, math, time
sys.path.insert(0,'/tmp/claude-0/bench')
from engine import *

def nb_full(ch, reach=1.45, cap=6):
    if ch not in KEY: return ""
    x,y=KEY[ch]; w=WIDTH[ch]*reach
    out=[]
    for c,(cx,cy) in KEY.items():
        if c==ch: continue
        dx=cx-x; dy=cy-y
        if math.hypot(dx,dy*0.95) <= max(w, ROW_H*1.15): out.append((math.hypot(dx,dy),c))
    out.sort()
    return "".join(c for _,c in out[:cap])

W2 = 2.4   # price of a second slipped letter

def correct2(word, nears, topn=6, two=True, w2=W2):
    if word in IDX: return None,0.0
    n=len(word)
    if n<4 or n>18: return None,0.0
    top=[]
    def offer(cand,weight):
        i=IDX.get(cand)
        if i is None: return
        sc=(R[i]+40)*weight
        if sc>=CUT: return
        for k,(s2,i2) in enumerate(top):
            if i2==i:
                if sc<s2: top[k]=(sc,i)
                return
        top.append((sc,i)); top.sort(key=lambda t:t[0]); del top[topn:]
    for i in range(n):
        nb=nears[i] if i<len(nears) else ""
        for c in LET:
            if c!=word[i]: offer(word[:i]+c+word[i+1:], W_NEAR if c in nb else W_FAR)
    for i in range(n-1):
        offer(word[:i]+word[i+1]+word[i]+word[i+2:], W_SWAP)
    if n>4:
        for i in range(n): offer(word[:i]+word[i+1:], W_DROP)
    for i in range(n+1):
        for c in LET: offer(word[:i]+c+word[i:], W_ADD)
    if two:
        # two letters that both landed on a neighbouring key: the commonest way a
        # word comes out wrong, and the one case a single-edit search cannot reach
        for i in range(n):
            nbi=nears[i] if i<len(nears) else ""
            if not nbi: continue
            for ci in nbi:
                if ci==word[i]: continue
                w1=word[:i]+ci+word[i+1:]
                for j in range(i+1,n):
                    nbj=nears[j] if j<len(nears) else ""
                    for cj in nbj:
                        if cj==word[j]: continue
                        offer(w1[:j]+cj+w1[j+1:], w2)
    if not top: return None,0.0
    best=top[0][0]; second=top[1][0] if len(top)>1 else float("inf")
    q=max(0.0,min(1.0,1-best/CUT))
    m=1.0 if second==float("inf") else max(0.0,min(1.0,(second-best)/(second+1)))
    return S[top[0][1]], 0.42+0.38*q+0.20*m

def build(p=0.22, seed=11, n=500):
    random.seed(7)
    pool=[(F[i],S[i]) for i in range(len(F)) if R[i]<20000 and 4<=len(F[i])<=11]
    words=random.sample(pool,n)
    random.seed(seed)
    cases=[]
    for folded, shown in words:
        out=[];wrong=0
        for ch in folded:
            if ch in KEY and random.random()<p:
                x,y=KEY[ch]
                g=nearest(x+random.gauss(0,0.42*WIDTH[ch]), y+random.gauss(0,0.42*ROW_H))
                if g!=ch: wrong+=1
                out.append(g)
            else: out.append(ch)
        t="".join(out)
        if t!=folded: cases.append((t,shown,wrong))
    return cases

CASES=build()
from collections import Counter
def run(label, **kw):
    tot=Counter();hit=Counter();t0=time.time()
    for typed,shown,wrong in CASES:
        nears=[nb_full(c) for c in typed]
        fix,_=correct2(typed,nears,**kw)
        tot[wrong]+=1
        if fix==shown: hit[wrong]+=1
    dt=(time.time()-t0)/len(CASES)*1000
    T=sum(tot.values()); H=sum(hit.values())
    per=" ".join(f"{k}:{100*hit[k]/tot[k]:.0f}%" for k in sorted(tot))
    print(f"{label:<30} {100*H/T:5.1f}%  [{per}]  {dt:.1f}ms/word")

run("one edit only", two=False)
for w in (1.8, 2.4, 3.0, 4.0):
    run(f"+ two near slips  w2={w}", w2=w)
