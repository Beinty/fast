# -*- coding: utf-8 -*-
"""A faithful model of the keyboard's correction engine, so changes can be
measured before they are shipped."""
import json, math, random, struct

AR1="ضصثقفغعهخحج"; AR2="شسيبلاتنمكط"; AR3="ذءؤرىةوزظد"
ROW_H=0.125                      # key height in board-width units (44dp of 411dp + gap)
KEY={}; WIDTH={}
for r,row in enumerate([AR1,AR2,AR3]):
    n=len(row)+(0.374 if r==2 else 0)   # row 3 carries the delete key
    total=len(row)+ (1.374 if r==2 else 0)
    x=0.0
    for ch in row:
        KEY[ch]=((x+0.5)/total, r*ROW_H+ROW_H/2)
        WIDTH[ch]=1.0/total
        x+=1.0

def nearest(px,py):
    best=None;bd=1e9
    for c,(x,y) in KEY.items():
        d=(px-x)**2+(py-y)**2
        if d<bd: bd=d; best=c
    return best

def neighbours(ch, reach=1.35):
    """What the view hands the corrector: keys within reach of the one pressed."""
    if ch not in KEY: return ""
    x,y=KEY[ch]; w=WIDTH[ch]*reach
    out=[]
    for c,(cx,cy) in KEY.items():
        if c==ch: continue
        if abs(cy-y)>ROW_H*1.2: continue
        if (cx-x)**2+(cy-y)**2 <= w*w: out.append(c)
    return "".join(out[:5])

# ---- dictionary -----------------------------------------------------------
d=json.load(open("/tmp/claude-0/ar_dict.json"))
F,S,R=d["f"],d["s"],d["r"]
IDX={w:i for i,w in enumerate(F)}
LET="ابتثجحخدذرزسشصضطظعغفقكلمنهويچگپڤ"

W_NEAR,W_FAR,W_SWAP,W_DROP,W_ADD,CUT=1.0,2.6,1.8,3.6,5.0,30000.0

def candidates(word, nears, topn=6, w_near=W_NEAR, w_far=W_FAR,
               w_swap=W_SWAP, w_drop=W_DROP, w_add=W_ADD, cut=CUT):
    n=len(word)
    top=[]
    def offer(cand,weight):
        i=IDX.get(cand)
        if i is None: return
        sc=(R[i]+40)*weight
        if sc>=cut: return
        for k,(s2,i2) in enumerate(top):
            if i2==i:
                if sc<s2: top[k]=(sc,i)
                return
        top.append((sc,i)); top.sort(key=lambda t:t[0]); del top[topn:]
    for i in range(n):
        nb=nears[i] if i<len(nears) else ""
        for c in LET:
            if c==word[i]: continue
            offer(word[:i]+c+word[i+1:], w_near if c in nb else w_far)
    for i in range(n-1):
        offer(word[:i]+word[i+1]+word[i]+word[i+2:], w_swap)
    if n>4:
        for i in range(n): offer(word[:i]+word[i+1:], w_drop)
    for i in range(n+1):
        for c in LET: offer(word[:i]+c+word[i:], w_add)
    return top

def correct(word, nears, **kw):
    if word in IDX: return None, 0.0
    if len(word)<4 or len(word)>18: return None, 0.0
    top=candidates(word, nears, **kw)
    if not top: return None, 0.0
    best=top[0][0]; second=top[1][0] if len(top)>1 else float("inf")
    q=max(0.0,min(1.0,1-best/kw.get("cut",CUT)))
    m=1.0 if second==float("inf") else max(0.0,min(1.0,(second-best)/(second+1)))
    return S[top[0][1]], 0.42+0.38*q+0.20*m
