"""Preview renders of the deck for build.py: straight down, and at the app's tilt."""
import bpy, math, mathutils
def shoot(path, mode="top", res=(1600,1000), ortho_scale=5.2, tilt=25, dark=False):
    """dark: a black room with the lights off (the neon deck), else a light grey one with a sun."""
    sc = bpy.context.scene
    cam = bpy.data.objects.get("_Cam")
    if cam is None:
        cam = bpy.data.objects.new("_Cam", bpy.data.cameras.new("_Cam")); sc.collection.objects.link(cam)
    sun = bpy.data.objects.get("_Sun")
    if sun is None:
        sun = bpy.data.objects.new("_Sun", bpy.data.lights.new("_Sun", "SUN")); sc.collection.objects.link(sun)
        sun.data.energy = 3.0; sun.rotation_euler = (math.radians(35), math.radians(10), math.radians(-30))
    sc.camera = cam
    if mode == "top":
        cam.data.type = "ORTHO"; cam.data.ortho_scale = ortho_scale
        cam.location = (0, -0.21, 10); cam.rotation_euler = (0, 0, 0)
    else:
        cam.data.type = "PERSP"; cam.data.lens_unit = "FOV"; cam.data.angle = math.radians(30)
        t = math.radians(90 - tilt)  # elevation from horizontal = 90 - tilt from top
        el = math.radians(65)
        d = 12.5
        cam.location = (0, -0.21-d*math.cos(el), d*math.sin(el)+0.3)
        direction = mathutils.Vector((0, -0.21, 0.3)) - cam.location
        cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    sc.render.engine = "BLENDER_EEVEE"
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.film_transparent = False
    if sc.world is None: sc.world = bpy.data.worlds.new("W")
    sc.world.use_nodes = True
    bg = sc.world.node_tree.nodes.get("Background")
    if bg: bg.inputs[0].default_value = (0,0,0,1) if dark else (0.9,0.9,0.9,1); bg.inputs[1].default_value = 0.0 if dark else 0.8
    sun.data.energy = 0.0 if dark else 3.0
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
