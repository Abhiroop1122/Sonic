"""Builds the Runout RG-1 v3 deck for one colourway from Hermes' v2 source.

Run inside Blender with THEME set (white_teal, mono_white or dark) after atlas.py has written
textures/prints_<THEME>.png:
    SCRIPTS = ".../scripts/deck-v3"; THEME = "white_teal"; PREVIEW = True
    exec(open(SCRIPTS + "/build.py").read())

Reads  v2-original/runout_turntable_<THEME>.blend
Writes runout_turntable_<THEME>.blend and .glb (and preview renders when PREVIEW).

Kept from v2: Turntable_Platter, Tonearm_Arm, Tonearm_Base (moved by DECK_DX), LED_Status (moved),
every material. Rebuilt: chassis modules, seam, screws, feet, key tray and keys, LED windows,
LED strip, printed labels, contact shadow.
"""
import importlib
import math
import sys

import bmesh
import bpy
import numpy as np

sys.path.insert(0, SCRIPTS)  # noqa: F821 (set by the caller)
import layout as L  # noqa: E402

importlib.reload(L)

bpy.ops.wm.open_mainfile(filepath=f"{L.V3}/v2-original/runout_turntable_{THEME}.blend")  # noqa: F821
root = bpy.data.objects["Modular_Turntable_Root"]
coll = bpy.data.collections["Runout v2 Master"]
coll.name = "Runout v3 Master"
M = bpy.data.materials

for name in ("Chassis_Main", "Chassis_Trim", "Chassis_Underside", "Prints", "Key_Eject", "Key_Next",
             "Key_Prev", "Key_StartStop", "LED_Strip", "Screen_Display", "Screen_Glass", "Shadow_Plane"):
    ob = bpy.data.objects[name]
    me = ob.data
    bpy.data.objects.remove(ob)
    if me.users == 0:
        bpy.data.meshes.remove(me)

for name in ("Turntable_Platter", "Tonearm_Arm", "Tonearm_Base"):
    bpy.data.objects[name].location.x += L.DECK_DX
bpy.data.objects["LED_Status"].location.x, bpy.data.objects["LED_Status"].location.y = L.LED_STATUS


# --- materials -------------------------------------------------------------------------------

def principled(m):
    return next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")


def drop_baked_orm(m):
    """v2's ORM was baked for the v2 footprint; the rebuilt chassis has no UV layout for it."""
    nt = m.node_tree
    b = principled(m)
    for n in [n for n in nt.nodes if n.type == "TEX_IMAGE" and n.image and "chassis_orm" in n.image.name]:
        nt.nodes.remove(n)
    for n in [n for n in nt.nodes if n.type in ("SEPARATE_COLOR", "SEPRGB", "SEPARATE_RGB")]:
        if not any(link.to_node for o in n.outputs for link in o.links):
            nt.nodes.remove(n)
    for k, v in (("Roughness", 0.5), ("Metallic", None)):
        sock = b.inputs[k]
        for link in list(sock.links):
            nt.links.remove(link)
        if v is not None:
            sock.default_value = v
    # glTF Material Output (occlusion) group, if any, loses its link with the image node above.


ROUGH = {"Chassis_Mat": 0.42, "Trim_Mat": 0.38, "Rubber_Mat": 0.8}
for name, r in ROUGH.items():
    drop_baked_orm(M[name])
    principled(M[name]).inputs["Roughness"].default_value = r

# Red LED windows: smoked red acrylic over an emissive red display.
glass = principled(M["Screen_Glass_Mat"])
glass.inputs["Base Color"].default_value = (0.012, 0.001, 0.001, 0.55)
glass.inputs["Alpha"].default_value = 0.55
glass.inputs["Roughness"].default_value = 0.1
scr = principled(M["Screen_Mat"])
scr.inputs["Emission Color"].default_value = (1.0, 0.09, 0.035, 1.0)
scr.inputs["Emission Strength"].default_value = 1.0
scr.inputs["Roughness"].default_value = 0.9
placeholder = bpy.data.images["screen_placeholder.png"]
placeholder.scale(*L.SCREEN_TEX)
placeholder.pixels.foreach_set(np.tile(np.array([0.0, 0.0, 0.0, 1.0], np.float32), L.SCREEN_TEX[0] * L.SCREEN_TEX[1]))
placeholder.pack()

prints_img = bpy.data.images.load(f"{L.V3}/textures/prints_{THEME}.png")  # noqa: F821
prints_img.name = f"prints_{THEME}.png"  # noqa: F821
prints_img.alpha_mode = "STRAIGHT"
prints_img.pack()
old_prints = None
for n in M["Prints_Mat"].node_tree.nodes:
    if n.type == "TEX_IMAGE":
        old_prints, n.image = n.image, prints_img
if old_prints and old_prints.users == 0:
    bpy.data.images.remove(old_prints)


# --- mesh helpers ----------------------------------------------------------------------------

def new_object(name, bm, mats, location=(0.0, 0.0, 0.0)):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for m in mats:
        me.materials.append(M[m])
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    ob.parent = root
    ob.location = location
    for p in me.polygons:
        p.use_smooth = False
    return ob


def add_box(bm, x0, x1, y0, y1, z0, z1, bevel=0.0, segments=3, mat=0, origin=(0.0, 0.0, 0.0)):
    ox, oy, oz = origin
    geom = bmesh.ops.create_cube(bm, size=1.0)
    verts = geom["verts"]
    for v in verts:
        v.co.x = (x0 if v.co.x < 0 else x1) - ox
        v.co.y = (y0 if v.co.y < 0 else y1) - oy
        v.co.z = (z0 if v.co.z < 0 else z1) - oz
    faces = list({f for v in verts for f in v.link_faces})
    for f in faces:
        f.material_index = mat
    if bevel > 0:
        edges = list({e for v in verts for e in v.link_edges})
        bmesh.ops.bevel(bm, geom=edges + verts, offset=bevel, segments=segments, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
    return verts


def add_cylinder(bm, cx, cy, z0, z1, r, segs=24, mat=0):
    geom = bmesh.ops.create_cone(bm, cap_ends=True, segments=segs, radius1=r, radius2=r, depth=z1 - z0)
    for v in geom["verts"]:
        v.co.x += cx
        v.co.y += cy
        v.co.z += (z0 + z1) / 2
    for f in {f for v in geom["verts"] for f in v.link_faces}:
        f.material_index = mat


def add_quad(bm, x0, x1, y0, y1, z, uv_fn, mat=0, origin=(0.0, 0.0, 0.0)):
    ox, oy, oz = origin
    vs = [bm.verts.new((x - ox, y - oy, z - oz)) for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1))]
    f = bm.faces.new(vs)
    f.material_index = mat
    uv = bm.loops.layers.uv.verify()
    for loop, (x, y) in zip(f.loops, ((x0, y0), (x1, y0), (x1, y1), (x0, y1))):
        loop[uv].uv = uv_fn(x, y)
    return f


def box_uvs(bm):
    """Planar top-down UVs so every exported primitive has TEXCOORD_0."""
    uv = bm.loops.layers.uv.verify()
    for f in bm.faces:
        for loop in f.loops:
            if loop[uv].uv.length == 0:
                loop[uv].uv = L.atlas_uv(loop.vert.co.x, loop.vert.co.y)


Y0, Y1 = L.DECK_Y
Z0, Z1 = L.DECK_Z

# --- chassis ---------------------------------------------------------------------------------

bm = bmesh.new()
add_box(bm, *L.MAIN_X, Y0, Y1, Z0, Z1, bevel=0.03, segments=4)
add_box(bm, *L.PANEL_X, Y0, Y1, Z0, Z1, bevel=0.03, segments=4)
box_uvs(bm)
new_object("Chassis_Main", bm, ["Chassis_Mat"])

bm = bmesh.new()
# seam insert between the modules
add_box(bm, L.SEAM_X - 0.0045, L.SEAM_X + 0.0045, Y0 + 0.065, Y1 - 0.065, Z0 + 0.044, Z1 - 0.016)
# screws
for x0, x1 in (L.MAIN_X, L.PANEL_X):
    for sx in (x0 + 0.1, x1 - 0.1):
        for sy in (Y0 + 0.12, Y1 - 0.12):
            add_cylinder(bm, sx, sy, Z1 - 0.002, Z1 + 0.004, 0.018, segs=16)
# LED strip channel
add_box(bm, L.LED_STRIP_X[0] - 0.015, L.LED_STRIP_X[1] + 0.015, L.LED_STRIP_Y[0] - 0.008,
        L.LED_STRIP_Y[1] + 0.008, Z1 - 0.01, Z1 - 0.004)
# LED window bezels (dark trays the displays sit in)
for wx in (L.TITLE_X, L.TIME_X):
    add_box(bm, wx[0] - L.BEZEL, wx[1] + L.BEZEL, L.WIN_Y[0] - L.BEZEL, L.WIN_Y[1] + L.BEZEL,
            Z1 - 0.012, Z1 + 0.004, bevel=0.008, segments=2)
# key tray
tx0, tx1, ty0, ty1 = L.TRAY
add_box(bm, tx0, tx1, ty0, ty1, Z1 - 0.012, Z1 + 0.004, bevel=0.008, segments=2)
box_uvs(bm)
new_object("Chassis_Trim", bm, ["Trim_Mat"])

bm = bmesh.new()
for x0, x1 in (L.MAIN_X, L.PANEL_X):
    for fx in (x0 + 0.27, x1 - 0.27):
        for fy in (Y0 + 0.27, Y1 - 0.27):
            add_cylinder(bm, fx, fy, 0.0, Z0 + 0.005, 0.14, segs=32)
box_uvs(bm)
new_object("Chassis_Underside", bm, ["Rubber_Mat"])

# --- LED windows -----------------------------------------------------------------------------

wy0, wy1 = L.WIN_Y
ZS = Z1 + 0.0055
bm = bmesh.new()
add_quad(bm, *L.TITLE_X, wy0, wy1, ZS,
         lambda x, y: ((x - L.TITLE_X[0]) / (L.TITLE_X[1] - L.TITLE_X[0]),
                       0.5 + 0.5 * (y - wy0) / (wy1 - wy0)))
new_object("Screen_Display", bm, ["Screen_Mat"])
bm = bmesh.new()
add_quad(bm, *L.TIME_X, wy0, wy1, ZS,
         lambda x, y: (L.TIME_U * (x - L.TIME_X[0]) / (L.TIME_X[1] - L.TIME_X[0]),
                       0.5 * (y - wy0) / (wy1 - wy0)))
new_object("Screen_Time", bm, ["Screen_Mat"])
bm = bmesh.new()
for wx in (L.TITLE_X, L.TIME_X):
    add_box(bm, wx[0], wx[1], wy0, wy1, ZS + 0.001, ZS + 0.004)
box_uvs(bm)
new_object("Screen_Glass", bm, ["Screen_Glass_Mat"])

# LED strip (progress): u runs left to right along the front.
bm = bmesh.new()
sx0, sx1 = L.LED_STRIP_X
add_box(bm, sx0, sx1, *L.LED_STRIP_Y, Z1 - 0.009, Z1 + 0.001)
uv = bm.loops.layers.uv.verify()
for f in bm.faces:
    for loop in f.loops:
        loop[uv].uv = ((loop.vert.co.x - sx0) / (sx1 - sx0), 0.5)
new_object("LED_Strip", bm, ["LED_Strip_Mat"])

# --- keys ------------------------------------------------------------------------------------

key_mats = {"Key_StartStop": "Signal_Orange_Mat"}
for name, ((x0, x1), (y0, y1)) in L.KEYS.items():
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    origin = (cx, cy, L.KEY_TOP)
    bm = bmesh.new()
    add_box(bm, x0, x1, y0, y1, L.KEY_Z0, L.KEY_TOP, bevel=L.KEY_BEVEL, segments=3, origin=origin)
    i = L.KEY_BEVEL * 0.7
    add_quad(bm, x0 + i, x1 - i, y0 + i, y1 - i, L.KEY_TOP + 0.0008, L.atlas_uv, mat=1, origin=origin)
    box_uvs(bm)
    new_object(name, bm, [key_mats.get(name, "Key_Mat"), "Prints_Mat"], location=origin)

# --- printed labels (static) -----------------------------------------------------------------

bm = bmesh.new()
ZP = Z1 + 0.0005
LABEL_BOXES = [
    (L.MAIN_X[0] + 0.17, L.MAIN_X[0] + 1.2, 1.15, 1.26),      # RG-1
    (L.BRAND_BOX[0], L.BRAND_BOX[1], L.BRAND_BOX[2], L.BRAND_BOX[3]),  # SONIC.
    (L.MAIN_X[1] - 0.8, L.MAIN_X[1] - 0.08, -1.24, -1.14),     # 33⅓ RPM
    (L.LED_STATUS[0] + 0.04, L.LED_STATUS[0] + 0.40, L.LED_STATUS[1] - 0.05, L.LED_STATUS[1] + 0.05),
]
for x0, x1, y0, y1 in LABEL_BOXES:
    add_quad(bm, x0, x1, y0, y1, ZP, L.atlas_uv)
new_object("Prints", bm, ["Prints_Mat"])

# --- contact shadow --------------------------------------------------------------------------

PAD = 0.2
sx0, sx1, sy0, sy1 = L.MAIN_X[0] - PAD, L.PANEL_X[1] + PAD, Y0 - PAD, Y1 + PAD
bm = bmesh.new()
add_quad(bm, sx0, sx1, sy0, sy1, -0.002,
         lambda x, y: ((x - sx0) / (sx1 - sx0), (y - sy0) / (sy1 - sy0)))
new_object("Shadow_Plane", bm, ["Shadow_Mat"])

shadow_img = next(n.image for n in M["Shadow_Mat"].node_tree.nodes if n.type == "TEX_IMAGE")
W, H = 1024, int(1024 * (sy1 - sy0) / (sx1 - sx0))
xs = np.linspace(sx0, sx1, W)
ys = np.linspace(sy0, sy1, H)
X, Y = np.meshgrid(xs, ys)
# signed distance to the chassis footprint (rounded rectangle)
hx, hy = (L.PANEL_X[1] - L.MAIN_X[0]) / 2, (Y1 - Y0) / 2
cx, cy = (L.PANEL_X[1] + L.MAIN_X[0]) / 2, (Y1 + Y0) / 2
qx, qy = np.abs(X - cx) - hx + 0.05, np.abs(Y - cy) - hy + 0.05
d = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - 0.05
shade = np.clip(1.0 - (d + 0.02) / 0.24, 0.0, 1.0) ** 2.2 * 0.55
old = np.array(shadow_img.pixels[:], np.float32).reshape(shadow_img.size[1], shadow_img.size[0], 4)
v2_dark_is_white = old[old.shape[0] // 2, old.shape[1] // 2, 0] > old[2, 2, 0]
rgba = np.zeros((H, W, 4), np.float32)
val = shade if v2_dark_is_white else 1.0 - shade
rgba[..., 0] = rgba[..., 1] = rgba[..., 2] = val
rgba[..., 3] = 1.0 if old[2, 2, 3] > 0.5 else shade
shadow_img.scale(W, H)
shadow_img.pixels.foreach_set(rgba.ravel())
shadow_img.pack()

# --- tidy and save ---------------------------------------------------------------------------

for img in list(bpy.data.images):
    if img.users == 0:
        bpy.data.images.remove(img)
for me in list(bpy.data.meshes):
    if me.users == 0:
        bpy.data.meshes.remove(me)

out = f"{L.V3}/runout_turntable_{THEME}"  # noqa: F821
bpy.ops.wm.save_as_mainfile(filepath=out + ".blend", compress=True)
win = bpy.context.window_manager.windows[0]
with bpy.context.temp_override(window=win, screen=win.screen, scene=bpy.context.scene,
                               view_layer=bpy.context.scene.view_layers[0]):
    bpy.ops.export_scene.gltf(filepath=out + ".glb", export_format="GLB", use_selection=False,
                              export_apply=True, export_yup=True, export_cameras=False, export_lights=False,
                              export_extras=False, export_animations=False)

info = {
    "shadow_v2_dark_is_white": bool(v2_dark_is_white),
    "objects": sorted(o.name for o in coll.objects),
    "arm_x": [round(v, 3) for v in (bpy.data.objects["Tonearm_Arm"].location.x,
                                    bpy.data.objects["Turntable_Platter"].location.x)],
}

if globals().get("PREVIEW"):
    import preview as render_lib
    importlib.reload(render_lib)
    prev = bpy.data.images.load(f"{L.V3}/textures/screen_preview.png")
    for n in M["Screen_Mat"].node_tree.nodes:
        if n.type == "TEX_IMAGE":
            n.image = prev
    sb = principled(M["Screen_Mat"])
    nt = M["Screen_Mat"].node_tree
    tex = next(n for n in nt.nodes if n.type == "TEX_IMAGE")
    nt.links.new(tex.outputs["Color"], sb.inputs["Emission Color"])
    sb.inputs["Emission Strength"].default_value = 3.0
    R = f"{L.V3}/previews/"
    render_lib.shoot(R + f"v3_{THEME}_top.png", "top", ortho_scale=5.9)  # noqa: F821
    render_lib.shoot(R + f"v3_{THEME}_tilt.png", "tilt")  # noqa: F821
    bpy.ops.wm.revert_mainfile()

result = info
