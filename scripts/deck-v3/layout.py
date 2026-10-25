"""Runout RG-1 v3 layout, shared by atlas.py and build.py.

Blender coordinates: x to the right, y to the back, z up (glTF: x, z, -y). Units as in Hermes' v2.

v3 moves the control module to the right of the tonearm, adds a front band with a long red LED
title display and a time display (one 1024x256 screen texture), and fills the control module with
flush TE-style keys. Platter, tonearm and record are Hermes' v2 parts, moved together so the arm
geometry relative to the platter is unchanged.
"""
import os

DECK_DX = -1.5125  # v2 platter/tonearm shift (platter centre 0.255 -> -1.2575)

# Chassis modules: (x0, x1), shared y and z. 5.145 x 3.10: wide and shallow.
MAIN_X = (-2.5725, 0.5625)
PANEL_X = (0.5725, 2.5725)
DECK_Y = (-1.76, 1.34)
DECK_Z = (0.064, 0.468)
SEAM_X = 0.5675
TOP = DECK_Z[1]

# Front band: two LED windows on one line, tight under the platter.
WIN_Y = (-1.659, -1.315)
TITLE_X = (-2.3775, 0.3725)  # 2.75 x 0.344 -> 8:1, top half of the 1024x256 screen texture
TIME_X = (0.7725, 2.3725)  # 1.6 x 0.344 -> bottom half, u 0..TIME_U
TIME_U = (TIME_X[1] - TIME_X[0]) / (WIN_Y[1] - WIN_Y[0]) * 128 / 1024
BEZEL = 0.035
SCREEN_TEX = (1024, 256)

LED_STRIP_X = (-2.44, 2.44)
LED_STRIP_Y = (-1.733, -1.708)

# Key grid, aligned with the time window.
GRID_X = TIME_X
GAP = 0.018
COL_W = (GRID_X[1] - GRID_X[0] - GAP) / 2
GRID_TOP = 0.98
ROW_H = (0.62, 0.92, 0.50)  # prev|next, start/stop, eject
KEY_Z0 = 0.472
KEY_TOP = 0.522
KEY_BEVEL = 0.014


def _rows():
    y = GRID_TOP
    out = []
    for h in ROW_H:
        out.append((y - h, y))
        y -= h + GAP
    return out


ROWS = _rows()
C0 = (GRID_X[0], GRID_X[0] + COL_W)
C1 = (GRID_X[1] - COL_W, GRID_X[1])
KEYS = {
    "Key_Prev": (C0, ROWS[0]),
    "Key_Next": (C1, ROWS[0]),
    "Key_StartStop": (GRID_X, ROWS[1]),
    "Key_Eject": (GRID_X, ROWS[2]),
}
TRAY = (GRID_X[0] - 0.02, GRID_X[1] + 0.02, ROWS[2][0] - 0.02, GRID_TOP + 0.02)
LED_STATUS = (0.83, 1.14)

# Printed labels: one top-down atlas over this rectangle (same aspect as the texture).
ATLAS_PX = (2048, 1240)
ATLAS_RECT = (-2.6, -1.78, 5.2, 5.2 * 1240 / 2048)  # x0, y0, w, h


def atlas_uv(x, y):
    x0, y0, w, h = ATLAS_RECT
    return ((x - x0) / w, (y - y0) / h)


THEMES = {
    "white_teal": {"ink": (0.11, 0.13, 0.14), "key_ink": (0.07, 0.09, 0.10)},
    "mono_white": {"ink": (0.11, 0.12, 0.12), "key_ink": (0.07, 0.08, 0.08)},
    "dark": {"ink": (0.80, 0.82, 0.82), "key_ink": (0.88, 0.90, 0.90)},
    # OLED black with white neon edges (neon.py): icons only, no words, as the lines are the look.
    "neon": {"ink": (0.86, 0.87, 0.88), "key_ink": (0.94, 0.95, 0.96), "words": False},
}

# Working folder: v2-original/ (Hermes' .blend files) in, the v3 .blend/.glb and textures/ out.
V3 = os.environ.get("RUNOUT_DECK_V3", os.path.expanduser("~/Downloads/runout_turntable_v3"))
_FONTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/res/font")
FONT = os.path.join(_FONTS, "inter_regular.ttf")
FONT_MEDIUM = os.path.join(_FONTS, "inter_medium.ttf")
# The wordmark's dot-matrix face (licensed, kept out of git: app/src/main/assets/fonts/licensed).
FONT_BRAND = os.path.join(_FONTS, "../../assets/fonts/licensed/Array-Bold.ttf")
SIGNAL = (0.941, 0.353, 0.141)  # #F05A24, the brand's full stop

# SONIC. printed in the control module's top-right corner, right edge on the key grid's.
BRAND_RIGHT = 2.3725
BRAND_BASELINE = 1.095
BRAND_SIZE = 0.15
BRAND_BOX = (1.55, 2.44, 1.05, 1.26)
