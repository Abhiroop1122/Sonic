"""Renders the printed-label atlas (and a preview screen image) for one colourway.

Run inside Blender with THEME and SCRIPTS (this folder) set:
    SCRIPTS = ".../scripts/deck-v3"; THEME = "white_teal"; exec(open(SCRIPTS + "/atlas.py").read())
Writes textures/prints_<theme>.png (2048x1536, straight alpha) and textures/screen_preview.png.
"""
import importlib
import math
import sys

import bpy

sys.path.insert(0, SCRIPTS)  # noqa: F821 (set by the caller)
import layout as L  # noqa: E402

importlib.reload(L)

theme = L.THEMES[THEME]  # noqa: F821 (set by the caller)
bpy.ops.wm.read_homefile(use_empty=True)
scene = bpy.context.scene
font = bpy.data.fonts.load(L.FONT)
font_medium = bpy.data.fonts.load(L.FONT_MEDIUM)


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def emission(name, rgb, strength=1.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*[srgb_to_linear(c) for c in rgb], 1.0)
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs[0], out.inputs[0])
    return m


INK = emission("Ink", theme["ink"])
KEY_INK = emission("KeyInk", theme["key_ink"])


def text(body, x, y, size, mat, align="LEFT", fnt=None, spacing=1.0):
    cu = bpy.data.curves.new("t", "FONT")
    cu.body = body
    cu.font = fnt or font
    cu.size = size
    cu.align_x = align
    cu.align_y = "CENTER"
    cu.space_character = spacing
    ob = bpy.data.objects.new("t", cu)
    ob.location = (x, y, 0.0)
    ob.data.materials.append(mat)
    scene.collection.objects.link(ob)
    return ob


def poly(points, mat):
    me = bpy.data.meshes.new("p")
    me.from_pydata([(px, py, 0.0) for px, py in points], [], [list(range(len(points)))])
    ob = bpy.data.objects.new("p", me)
    ob.data.materials.append(mat)
    scene.collection.objects.link(ob)
    return ob


def rect(x0, y0, x1, y1, mat):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], mat)


def tri(cx, cy, w, h, direction, mat):
    if direction == "right":
        pts = [(cx - w / 2, cy - h / 2), (cx + w / 2, cy), (cx - w / 2, cy + h / 2)]
    elif direction == "left":
        pts = [(cx + w / 2, cy - h / 2), (cx + w / 2, cy + h / 2), (cx - w / 2, cy)]
    else:  # up
        pts = [(cx - w / 2, cy - h / 2), (cx + w / 2, cy - h / 2), (cx, cy + h / 2)]
    return poly(pts, mat)


def key_box(name):
    (x0, x1), (y0, y1) = L.KEYS[name]
    return x0, y0, x1, y1, (x0 + x1) / 2, (y0 + y1) / 2


# Static labels on the chassis.
words = theme.get("words", True)
if words:
    text("RG-1", L.MAIN_X[0] + 0.195, 1.205, 0.06, INK, fnt=font_medium, spacing=1.25)
    text("33⅓ RPM", L.MAIN_X[1] - 0.11, -1.19, 0.055, INK, align="RIGHT", spacing=1.15)
text("playing", L.LED_STATUS[0] + 0.06, L.LED_STATUS[1], 0.058, INK)

# The wordmark, as the app sets it: SONIC in Array Bold, the full stop a signal-orange disc on the
# baseline, as wide as the letters' stroke (0.2 of the type size).
brand = text("SONIC", 0.0, L.BRAND_BASELINE, L.BRAND_SIZE, INK, fnt=bpy.data.fonts.load(L.FONT_BRAND))
brand.data.align_y = "BOTTOM_BASELINE"
bpy.context.view_layer.update()
dot = L.BRAND_SIZE * 0.2
gap = dot * 0.6
width = brand.dimensions.x
brand.location.x = L.BRAND_RIGHT - dot - gap - width
SIGNAL_INK = emission("Signal", L.SIGNAL)
poly([(L.BRAND_RIGHT - dot / 2 + dot / 2 * math.cos(a), L.BRAND_BASELINE + dot / 2 + dot / 2 * math.sin(a))
      for a in (i * 2 * math.pi / 48 for i in range(48))], SIGNAL_INK)

# Keys: a lowercase word in the top-left corner, a large icon in the middle (TE style).
LABELS = {"Key_Prev": "prev", "Key_Next": "next", "Key_StartStop": "start / stop", "Key_Eject": "eject"}
for name, word in LABELS.items() if words else ():
    x0, y0, x1, y1, cx, cy = key_box(name)
    text(word, x0 + 0.05, y1 - 0.065, 0.058, KEY_INK)

# prev |◀   next ▶|
s = 0.15
x0, y0, x1, y1, cx, cy = key_box("Key_Prev")
cy -= 0.03
rect(cx - s * 0.62, cy - s * 0.5, cx - s * 0.47, cy + s * 0.5, KEY_INK)
tri(cx + s * 0.12, cy, s * 0.95, s, "left", KEY_INK)
x0, y0, x1, y1, cx, cy = key_box("Key_Next")
cy -= 0.03
rect(cx + s * 0.47, cy - s * 0.5, cx + s * 0.62, cy + s * 0.5, KEY_INK)
tri(cx - s * 0.12, cy, s * 0.95, s, "right", KEY_INK)
# start / stop ▶ ■
x0, y0, x1, y1, cx, cy = key_box("Key_StartStop")
cy -= 0.02
s = 0.24
tri(cx - s * 0.55, cy, s * 0.88, s, "right", KEY_INK)
rect(cx + s * 0.18, cy - s * 0.42, cx + s * 1.02, cy + s * 0.42, KEY_INK)
# eject ⏏
x0, y0, x1, y1, cx, cy = key_box("Key_Eject")
cy -= 0.025
s = 0.15
tri(cx, cy + s * 0.18, s * 1.25, s * 0.62, "up", KEY_INK)
rect(cx - s * 0.62, cy - s * 0.42, cx + s * 0.62, cy - s * 0.27, KEY_INK)


def render(path, rect_xywh, px):
    x0, y0, w, h = rect_xywh
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(w, h)
    cam.location = (x0 + w / 2, y0 + h / 2, 5.0)
    scene.camera = cam
    r = scene.render
    r.engine = "BLENDER_EEVEE"
    r.resolution_x, r.resolution_y = px
    r.resolution_percentage = 100
    r.film_transparent = True
    r.filter_size = 1.0
    r.image_settings.file_format = "PNG"
    r.image_settings.color_mode = "RGBA"
    r.image_settings.color_depth = "8"
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.eevee.taa_render_samples = 32
    r.filepath = path
    bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(cam)


render(f"{L.V3}/textures/prints_{THEME}.png", L.ATLAS_RECT, L.ATLAS_PX)  # noqa: F821

# Preview screen (renders only; the exported model carries a black placeholder the app draws into).
for ob in list(scene.collection.objects):
    bpy.data.objects.remove(ob)
RED = emission("Red", (1.0, 0.22, 0.12), 1.0)
GHOST = emission("Ghost", (0.16, 0.03, 0.02), 1.0)
# Texture space: 8 x 2 units, title in the top half (y 1..2), time in the bottom-left (y 0..1).
text("A1   OPENING", 0.35, 1.5, 0.62, RED, fnt=font_medium, spacing=1.2)
tw = L.TIME_U * 8
SEGS = {"0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc", "5": "afgcd", "6": "afgedc",
        "7": "abc", "8": "abcdefg", "9": "abcdfg"}


def seven(ch, x, y, w, h, t, z=0.0):
    """One 7-segment digit, bottom-left at (x, y), slanted like an LED tube."""
    k = 0.10  # slant
    def sk(px, py):
        return (px + k * (py - y), py)
    hx = [(t / 2, 0), (t, t / 2), (t, -t / 2), (t / 2, 0)]
    def bar(x0, y0, x1, y1):  # horizontal or vertical bar with pointed ends
        if y0 == y1:
            pts = [(x0, y0), (x0 + t / 2, y0 + t / 2), (x1 - t / 2, y0 + t / 2), (x1, y0), (x1 - t / 2, y0 - t / 2), (x0 + t / 2, y0 - t / 2)]
        else:
            pts = [(x0, y0), (x0 + t / 2, y0 + t / 2), (x0 + t / 2, y1 - t / 2), (x0, y1), (x0 - t / 2, y1 - t / 2), (x0 - t / 2, y0 + t / 2)]
        return [sk(px, py) for px, py in pts]
    g = t * 0.12
    m = y + h / 2
    shapes = {
        "a": bar(x + g, y + h, x + w - g, y + h), "g": bar(x + g, m, x + w - g, m), "d": bar(x + g, y, x + w - g, y),
        "f": bar(x, m + g, x, y + h - g), "b": bar(x + w, m + g, x + w, y + h - g),
        "e": bar(x, y + g, x, m - g), "c": bar(x + w, y + g, x + w, m - g),
    }
    for name, pts in shapes.items():
        ob = poly(pts, RED if name in SEGS.get(ch, "") else GHOST)
        ob.location.z = z


text("A1", 0.3, 0.5, 0.62, RED, fnt=font_medium, spacing=1.2)
dw, dh, th, gap, colon = 0.36, 0.62, 0.075, 0.13, 0.22
total = 4 * dw + 2 * gap + colon
x = tw - 0.3 - total
y = 0.5 - dh / 2
for i, ch in enumerate("0028"):
    seven(ch, x, y, dw, dh, th)
    x += dw + (gap if i != 1 else 0)
    if i == 1:
        cx = x + colon / 2
        for cyy in (y + dh * 0.3, y + dh * 0.7):
            rect(cx - th / 2 + 0.03 * (cyy - y), cyy - th / 2, cx + th / 2 + 0.03 * (cyy - y), cyy + th / 2, RED)
        x += colon
scene.world = bpy.data.worlds.new("w")
render(f"{L.V3}/textures/screen_preview.png", (0, 0, 8, 2), L.SCREEN_TEX)
result = {"ok": True}
