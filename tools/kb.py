# -*- coding: utf-8 -*-
"""The keyboard's geometry, exactly as KeyboardView lays it out.

Every number here is read off the real code, not chosen: key height and gap
come from Store's defaults, sideMargin and vGap from KeyboardView's getters,
and the row contents from KbLayout. Earlier models of this guessed the
geometry and so measured a keyboard that does not exist.
"""

AR = ["ضصثقفغعهخحج", "شسيبلاتنمكط", "ذءؤرىةوزظد"]
EN = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

SCREEN = 411.0          # dp, the usual phone the app is built for
KEY_H = 44.0            # Store.kbKeyHeight default
GAP = 5.0               # Store.kbGap default
SIDE = KEY_H * 0.164    # KeyboardView.sideMargin
VGAP = KEY_H * 0.265    # KeyboardView.vGap
W_MOD = 1.42            # shift / delete weight
GAP_MOD = 2.0           # the doubled gap beside them
INSET = 0.5             # English middle row sits half a key in


def _row_geom(weights, gaps, y):
    """Mirrors KeyboardView.rebuild(): weight-shared width, gaps taken off first."""
    usable = SCREEN - 2 * SIDE
    total_gap = sum(gaps[:-1]) * GAP if gaps else 0.0
    unit = (usable - total_gap) / sum(weights)
    out = []
    x = SIDE
    for i, w in enumerate(weights):
        kw = unit * w
        out.append((x, y, kw, KEY_H))
        x += kw + (GAP * gaps[i] if i < len(weights) - 1 else 0.0)
    return out


def board(arabic=True):
    """{letter: (cx, cy, w, h)} for the letter rows of the main page."""
    keys = {}
    y = 0.0
    rows = AR if arabic else EN
    for r, letters in enumerate(rows):
        if arabic:
            if r == 2:
                weights = [1.0] * len(letters) + [W_MOD]
                gaps = [1.0] * len(letters) + [1.0]
                gaps[len(letters) - 1] = GAP_MOD
                labels = list(letters) + [None]
            else:
                weights = [1.0] * len(letters)
                gaps = [1.0] * len(letters)
                labels = list(letters)
        else:
            if r == 0:
                weights = [1.0] * len(letters); gaps = [1.0] * len(letters)
                labels = list(letters)
            elif r == 1:
                weights = [INSET] + [1.0] * len(letters) + [INSET]
                gaps = [1.0] * (len(letters) + 2)
                labels = [None] + list(letters) + [None]
            else:
                weights = [W_MOD] + [1.0] * len(letters) + [W_MOD]
                gaps = [GAP_MOD] + [1.0] * len(letters) + [1.0]
                gaps[len(letters)] = GAP_MOD
                labels = [None] + list(letters) + [None]
        geo = _row_geom(weights, gaps, y)
        for lab, (x, yy, w, h) in zip(labels, geo):
            if lab:
                keys[lab] = (x + w / 2, yy + h / 2, w, h)
        y += KEY_H + VGAP
    return keys


AR_BOARD = board(True)
EN_BOARD = board(False)


def nearest(board_, px, py):
    best, bd = None, 1e18
    for c, (x, y, w, h) in board_.items():
        d = (px - x) ** 2 + (py - y) ** 2
        if d < bd:
            bd, best = d, c
    return best


def neighbours(board_, ch, reach_w=1.45, reach_h=1.25, keep=6):
    """KeyboardView.neighbours(): the nearest letters within reach, six at most."""
    if ch not in board_:
        return ""
    cx, cy, w, h = board_[ch]
    reach = max(w * reach_w, h * reach_h)
    r2 = reach * reach
    found = []
    for c, (x, y, _w, _h) in board_.items():
        if c == ch:
            continue
        d2 = (x - cx) ** 2 + (y - cy) ** 2
        if d2 <= r2:
            found.append((d2, c))
    found.sort()
    return "".join(c for _, c in found[:keep])


def report():
    b = AR_BOARD
    w = b["ل"][2]
    print("key %.1f x %.1f dp, row pitch %.1f dp" % (w, KEY_H, KEY_H + VGAP))
    print("reach now = max(w*1.45, h*1.25) = %.1f dp" % max(w * 1.45, KEY_H * 1.25))
    print("distance to the key directly above  = %.1f dp" % (KEY_H + VGAP))
    print("distance to the key diagonally above = %.1f dp"
          % ((KEY_H + VGAP) ** 2 + (w + GAP) ** 2) ** 0.5)
    print("distance to the key beside           = %.1f dp" % (w + GAP))
    for r in (1.25, 1.35, 1.45, 1.55, 1.65):
        print("  h*%.2f -> %-2s" % (r, len(neighbours(b, "ل", 1.45, r))),
              neighbours(b, "ل", 1.45, r))


if __name__ == "__main__":
    report()
