# -*- coding: utf-8 -*-
"""Named engine settings, so a measurement can be repeated exactly."""
import eng

SETS = {
    # exactly what v9.0 ships, with the reach the real geometry gives it
    "shipped": (eng.Cfg(), 1.25),
    # the vertical neighbour the shipped reach misses by 0.7dp
    "reach": (eng.Cfg(), 1.45),
    # the dialect guard correctNear never had
    "iraqi": (eng.Cfg(iraqi=True), 1.45),
    # the bigram table the app ships but never consults when correcting
    "ctx": (eng.Cfg(iraqi=True, use_shipped_ctx=True), 1.45),
    # everything measured in this round, together
    "v10": (eng.Cfg(iraqi=True, shape=True, use_shipped_ctx=True,
                    w_near=0.85, w_far=4.6, w_swap=0.85, w_drop=3.2, w_add=0.6,
                    w_two=1.8, w_sound=1.2, w_three=5.5, cut=300000.0,
                    conf_scale=300000.0, gate2=0.12, gate3=0.34,
                    three=True), 1.45),
    # the same, on dictionaries with the typos taken out of them
    "v10p": (eng.Cfg(iraqi=True, shape=True, use_shipped_ctx=True,
                     w_near=0.85, w_far=4.6, w_swap=0.85, w_drop=3.2, w_add=0.6,
                     w_two=1.8, w_sound=1.2, w_three=5.5, cut=300000.0,
                     conf_scale=300000.0, gate2=0.12, gate3=0.34, three=True,
                     ), 1.45),
    # plus two-edit shapes other than two near slips
    "mixed": (eng.Cfg(mixed=True), 1.45),
    # plus three near slips
    "three": (eng.Cfg(mixed=True, three=True), 1.45),
}


def get(name):
    return SETS[name]
