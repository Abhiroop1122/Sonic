"""Builds the neon colourway: the RG-1 in OLED black, drawn by white neon lines.

After the look of a Gemini sketch (an edge-lit mixer and turntable: near-black housings, thin
white emissive edges at strength 2.5), on the RG-1's own parts, so every node, origin and key zone
is the dark build's and the app drives it unchanged. Run inside Blender after build.py made the
dark deck and atlas.py (THEME = "neon") wrote textures/prints_neon.png:
    SCRIPTS = ".../scripts/deck-v3"; exec(open(SCRIPTS + "/neon.py").read())

Reads  runout_turntable_dark.blend
Writes runout_turntable_neon.blend and .glb (and previews/v3_neon_*.png when PREVIEW).

Two kinds of line:
- Tubes along the chassis' edges: each module's top and bottom outline and its outer corners, the
  key tray and the LED window bezels. They are drawn where the vision has lines, also where two
  faces meet in view (a silhouette alone would miss the front top edge). The modules' outlines stay
  open at the seam, marked instead by one quiet line: the tonearm crosses the seam in view, and
  three bright lines there bloomed into a bar under it.
- An inverted hull for the parts that move or curve (platter, tonearm, keys): a copy of the mesh
  pushed out along its normals and turned inside out. Only its far side draws, so it shows as a
  rim at the part's outline from any angle. It is part of the part's own mesh: it turns with the
  platter and goes down with a key.

The contact shadow goes: on black there is nothing for it to fall on.
"""
import importlib
import math
import sys

import bmesh
import bpy

sys.path.insert(0, SCRIPTS)  # noqa: F821 (set by the caller)
import layout as L  # noqa: E402

importlib.reload(L)

bpy.ops.wm.open_mainfile(filepath=f"{L.V3}/runout_turntable_dark.blend")
root = bpy.data.objects["Modular_Turntable_Root"]
coll = root.users_collection[0]
M = bpy.data.materials

TUBE_R = 0.0075        # neon tube radius (deck is 5.145 wide)
HULL = 0.011           # how far the hull stands off the surface
NEON = 2.5             # emissive strength, as in the sketch
BLACK = (0.01, 0.01, 0.012, 1.0)


def principled(m):
    return next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")


# --- materials -------------------------------------------------------------------------------

for name in ("Chassis_Mat", "Trim_Mat", "Rubber_Mat", "Key_Mat", "Signal_Orange_Mat", "Tonearm_Mat", "Felt_Mat"):
    m = M[name]
    nt = m.node_tree
    b = principled(m)
    for sock in ("Base Color", "Roughness", "Metallic"):
        for link in list(b.inputs[sock].links):
            nt.links.remove(link)
    b.inputs["Base Color"].default_value = BLACK
    b.inputs["Roughness"].default_value = 0.35
    b.inputs["Metallic"].default_value = 0.0
    for n in [n for n in nt.nodes if n.type == "TEX_IMAGE"]:
        if not any(link.to_node for o in n.outputs for link in o.links):
            nt.nodes.remove(n)

prints_img = bpy.data.images.load(f"{L.V3}/textures/prints_neon.png")
prints_img.name = "prints_neon.png"
prints_img.alpha_mode = "STRAIGHT"
prints_img.pack()
for n in M["Prints_Mat"].node_tree.nodes:
    if n.type == "TEX_IMAGE":
        n.image = prints_img


def neon_material(name, cull):
    m = M.get(name) or M.new(name)
    m.use_nodes = True
    b = principled(m)
    b.inputs["Base Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    b.inputs["Emission Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    b.inputs["Emission Strength"].default_value = NEON
    b.inputs["Roughness"].default_value = 0.5
    # glTF's doubleSided comes from this: the hull must cull its near side.
    m.use_backface_culling = cull
    return m


neon = neon_material("Neon_Edge_Mat", cull=False)
# The seam between the modules: a quiet line under the bloom, so the tonearm, which crosses it in
# view, reads over it rather than melting into a bright bar.
seam_mat = neon_material("Neon_Seam_Mat", cull=False)
principled(seam_mat).inputs["Emission Strength"].default_value = 0.55
principled(seam_mat).inputs["Base Color"].default_value = (0.0, 0.0, 0.0, 1.0)
hull_mat = neon_material("Neon_Hull_Mat", cull=True)


# --- inverted hulls --------------------------------------------------------------------------

def add_hull(ob, offset=HULL):
    me = ob.data
    if hull_mat.name not in [s.material.name for s in ob.material_slots if s.material]:
        me.materials.append(hull_mat)
    slot = [s.material.name for s in ob.material_slots].index(hull_mat.name)
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.normal_update()
    src = list(bm.faces)
    copies = {}
    for v in list(bm.verts):
        copies[v] = bm.verts.new(v.co + v.normal * offset)
    for f in src:
        nf = bm.faces.new([copies[v] for v in reversed(f.verts)])
        nf.material_index = slot
        nf.smooth = True
    uv = bm.loops.layers.uv.active
    if uv is not None:
        for f in bm.faces:
            if f.material_index == slot:
                for loop in f.loops:
                    loop[uv].uv = (0.5, 0.5)
    bm.to_mesh(me)
    bm.free()


# Round parts get rings instead: the platter's flat top is a fan of triangles (a hull draws its
# spokes), and a hull round the tonearm's base outlines only the sides of its discs, half circles.
for name in ("Tonearm_Arm", "Key_Prev", "Key_Next", "Key_StartStop", "Key_Eject"):
    add_hull(bpy.data.objects[name], HULL * (0.7 if name.startswith("Key") else 1.0))


# --- tubes -----------------------------------------------------------------------------------

def rounded_rect(x0, x1, y0, y1, r, z, steps=6):
    pts = []
    corners = ((x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180), (x1 - r, y0 + r, 270))
    for cx, cy, a0 in corners:
        for i in range(steps + 1):
            a = math.radians(a0 + 90 * i / steps)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a), z))
    return pts


def open_at_seam(pts, side):
    """A closed outline as one open path, its straight side towards the seam left out."""
    xs = [p[0] for p in pts]
    edge = max(xs) if side == "right" else min(xs)
    keep = [abs(p[0] - edge) > 0.02 for p in pts]
    i = keep.index(False)
    while not keep[i]:
        i = (i + 1) % len(pts)
    out = []
    while keep[i]:
        out.append(pts[i])
        i = (i + 1) % len(pts)
    return out


paths = []  # (points, closed)
seam = []
Y0, Y1 = L.DECK_Y
Z0, Z1 = L.DECK_Z
inset = TUBE_R * 0.6
for (x0, x1), side in ((L.MAIN_X, "right"), (L.PANEL_X, "left")):
    for z in (Z1 - inset, Z0 + inset):
        paths.append((open_at_seam(rounded_rect(x0 + inset, x1 - inset, Y0 + inset, Y1 - inset, 0.03, z), side), False))
    x = x0 + inset + 0.009 if side == "right" else x1 - inset - 0.009  # outer corners only
    for y in (Y0 + inset + 0.009, Y1 - inset - 0.009):
        paths.append(([(x, y, Z0 + inset), (x, y, Z1 - inset)], False))
# One quiet line along the seam: across the top, and down the front.
seam.append(([(L.SEAM_X, Y0 + 0.03, Z1), (L.SEAM_X, Y1 - 0.03, Z1)], False))
seam.append(([(L.SEAM_X, Y0, Z0 + inset), (L.SEAM_X, Y0, Z1 - 0.01)], False))
# Key tray and LED window bezels, at their top.
tx0, tx1, ty0, ty1 = L.TRAY
paths.append((rounded_rect(tx0, tx1, ty0, ty1, 0.01, Z1 + 0.004), True))
for wx in (L.TITLE_X, L.TIME_X):
    paths.append((rounded_rect(wx[0] - L.BEZEL, wx[1] + L.BEZEL, L.WIN_Y[0] - L.BEZEL, L.WIN_Y[1] + L.BEZEL,
                               0.01, Z1 + 0.004), True))

def tube_mesh(name, paths, mat=None):
    """Neon tubes along [paths] ((points, closed) each) as a mesh, in the paths' own coordinates."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = TUBE_R
    cu.bevel_resolution = 1
    cu.use_fill_caps = True
    for pts, closed in paths:
        sp = cu.splines.new("POLY")
        sp.points.add(len(pts) - 1)
        for p, (x, y, z) in zip(sp.points, pts):
            p.co = (x, y, z, 1.0)
        sp.use_cyclic_u = closed
    cu.materials.append(mat or neon)
    tmp = bpy.data.objects.new(name, cu)
    coll.objects.link(tmp)
    deps = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(tmp.evaluated_get(deps))
    bpy.data.objects.remove(tmp)
    bpy.data.curves.remove(cu)
    bm = bmesh.new()
    bm.from_mesh(me)
    uv = bm.loops.layers.uv.verify()
    for f in bm.faces:
        for loop in f.loops:
            loop[uv].uv = (0.5, 0.5)
    bm.to_mesh(me)
    bm.free()
    return me


edges = bpy.data.objects.new("Neon_Edges", tube_mesh("Neon_Edges", paths))
coll.objects.link(edges)
edges.parent = root
seam_ob = bpy.data.objects.new("Neon_Seam", tube_mesh("Neon_Seam", seam, seam_mat))
coll.objects.link(seam_ob)
seam_ob.parent = root


def circle(r, z, steps=96, cx=0.0, cy=0.0):
    return [(cx + r * math.cos(2 * math.pi * i / steps), cy + r * math.sin(2 * math.pi * i / steps), z) for i in range(steps)]


def add_rings(ob, paths):
    """Neon tubes along [paths], in [ob]'s own space, as part of its mesh: they move with it."""
    rings = tube_mesh(ob.name + "_Rings", paths)
    ob.data.materials.append(neon)
    slot = len(ob.data.materials) - 1
    bm = bmesh.new()
    bm.from_mesh(ob.data)
    before = len(bm.faces)
    bm.from_mesh(rings)
    bm.faces.ensure_lookup_table()
    for f in bm.faces[before:]:
        f.material_index = slot
    bm.to_mesh(ob.data)
    bm.free()
    bpy.data.meshes.remove(rings)


# Platter rings, in the platter's own space (origin on the spindle axis at the mat's top), so they
# turn with it: the rim's top and bottom edges, the mat's lip, the spindle.
add_rings(bpy.data.objects["Turntable_Platter"], [
    (circle(1.19 - TUBE_R, -0.004 - TUBE_R), True), (circle(1.19 - TUBE_R, -0.08), True),
    (circle(1.165, 0.002), True), (circle(0.022 + TUBE_R * 0.5, 0.055 - TUBE_R), True)])

# The tonearm's base, in its own space: the round plate's top and bottom edges, the step on it,
# the pivot post's top and the arm rest's cap.
R = TUBE_R
# Each tube rides the edge itself, a little proud of both faces, so neither hides it.
add_rings(bpy.data.objects["Tonearm_Base"], [
    (circle(0.39 + R * 0.3, 0.046 + R * 0.3, 128), True), (circle(0.39 + R * 0.3, -0.046 + R, 128), True),
    (circle(0.26 + R * 0.3, 0.069 + R * 0.3), True),
    (circle(0.085 + R * 0.3, 0.249 + R * 0.3, 48, -0.04, 0.13), True), (circle(0.085 + R * 0.3, 0.074 + R, 48, -0.04, 0.13), True),
    (circle(0.09 + R * 0.3, 0.234 + R * 0.3, 48, 0.375, -1.23), True)])

# --- no shadow -------------------------------------------------------------------------------

shadow = bpy.data.objects["Shadow_Plane"]
shadow_me = shadow.data
bpy.data.objects.remove(shadow)
bpy.data.meshes.remove(shadow_me)

# --- tidy and save ---------------------------------------------------------------------------

for img in list(bpy.data.images):
    if img.users == 0:
        bpy.data.images.remove(img)
for m in list(bpy.data.materials):
    if m.users == 0:
        bpy.data.materials.remove(m)

out = f"{L.V3}/runout_turntable_neon"
bpy.ops.wm.save_as_mainfile(filepath=out + ".blend", compress=True)
win = bpy.context.window_manager.windows[0]
with bpy.context.temp_override(window=win, screen=win.screen, scene=bpy.context.scene,
                               view_layer=bpy.context.scene.view_layers[0]):
    bpy.ops.export_scene.gltf(filepath=out + ".glb", export_format="GLB", use_selection=False,
                              export_apply=True, export_yup=True, export_cameras=False, export_lights=False,
                              export_extras=False, export_animations=False)

info = {"objects": sorted(o.name for o in coll.objects), "tris": sum(len(o.data.polygons) for o in coll.objects if o.type == "MESH")}

if globals().get("PREVIEW"):
    sc = bpy.context.scene
    sc.world = sc.world or bpy.data.worlds.new("W")
    sc.world.use_nodes = True
    bg = sc.world.node_tree.nodes.get("Background")
    bg.inputs[0].default_value = (0, 0, 0, 1)
    bg.inputs[1].default_value = 0.0
    import preview as render_lib
    importlib.reload(render_lib)
    prev = bpy.data.images.load(f"{L.V3}/textures/screen_preview.png")
    nt = M["Screen_Mat"].node_tree
    tex = next(n for n in nt.nodes if n.type == "TEX_IMAGE")
    tex.image = prev
    sb = principled(M["Screen_Mat"])
    nt.links.new(tex.outputs["Color"], sb.inputs["Emission Color"])
    sb.inputs["Emission Strength"].default_value = 3.0
    R = f"{L.V3}/previews/"
    render_lib.shoot(R + "v3_neon_tilt.png", "tilt", dark=True)
    render_lib.shoot(R + "v3_neon_top.png", "top", ortho_scale=5.9, dark=True)
    bpy.ops.wm.revert_mainfile()

result = info
