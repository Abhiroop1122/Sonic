package app.runout.ui.deck

import android.content.Context
import android.graphics.Bitmap
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Filament engine for everything 3D in the room: the deck, and the record while it's in your
 * hand ([DiscRenderer]). Sharing it means the record's material is built once, and the disc you
 * carry is the same model, lit the same way, as the one that lands on the platter. It lives as long
 * as the process; each renderer destroys only what it made. Main thread only.
 */
object FilamentShared {
    val engine: Engine by lazy { Engine.create() }
    private val materials by lazy { UbershaderProvider(engine) }
    val loader by lazy { AssetLoader(engine, materials, EntityManager.get()) }
    private val resources by lazy { ResourceLoader(engine) }

    /** A glTF binary from the app's assets, with its textures loaded. */
    fun load(context: Context, path: String): FilamentAsset {
        val bytes = context.assets.open(path).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
        val asset = loader.createAsset(buffer) ?: error("Couldn't read $path")
        resources.loadResources(asset)
        asset.releaseSourceData()
        return asset
    }

    /** A bitmap as a mipmapped texture: sRGB for colour, linear for data (normals, roughness). */
    fun mipmapped(b: Bitmap, srgb: Boolean): Texture {
        val levels = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(b.width, b.height)))
        val texture = Texture.Builder()
            .width(b.width)
            .height(b.height)
            .levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        TextureHelper.setBitmap(engine, texture, 0, b)
        texture.generateMipmaps(engine)
        return texture
    }
}

/**
 * The room's studio light, added to [scene]: a high key light from the front left, a dimmer fill
 * from the right, a glint from behind and an even ambient, scaled so they sum to 1 on an upward face
 * (Lambert: Σ light·cos/π + ambient). With linear tone mapping at an exposure of 1, a record lying
 * flat shows its face in the very colours the room paints it in. Over the platter hangs a lamp: a
 * soft spot up behind it, placed so the camera sees its reflection on the record. It adds little
 * light, but the vinyl's glossy bands catch it, and the highlight stays put while the record turns.
 */
class StudioLights(private val engine: Engine, private val scene: Scene, shadows: Boolean) {
    private val sun = EntityManager.get().create()
    private val fill = EntityManager.get().create()
    private val glint = EntityManager.get().create()
    private val lamp = EntityManager.get().create()
    private val ambient: IndirectLight

    init {
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.98f, 0.95f)
            .intensity(SUN)
            .direction(0.35f, -1f, -0.45f)
            .sunAngularRadius(2.4f)
            .castShadows(shadows)
            .build(engine, sun)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.92f, 0.95f, 1f)
            .intensity(FILL)
            .direction(-0.6f, -0.5f, -0.2f)
            .build(engine, fill)
        // Low from behind the deck, so its mirror image off the record comes back to the camera:
        // the grooves glint and the smooth bands between tracks show. Barely lights anything else.
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(GLINT)
            .direction(0.1f, -0.9f, 0.42f)
            .build(engine, glint)
        // The lamp: high up behind the platter and a little left, the mirror of the camera off the
        // back of the record, aimed at the platter's centre with a soft-edged cone.
        LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
            .color(1f, 0.96f, 0.9f)
            .intensityCandela(LAMP)
            .position(-1.55f, 4.2f, -2.3f)
            .direction(0.29f, -3.61f, 2.3f)
            .spotLightCone(0.2f, 0.36f)
            .falloff(10f)
            .castShadows(false)
            .build(engine, lamp)
        ambient = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(AMBIENT)
            .build(engine)
        scene.indirectLight = ambient
        scene.addEntity(sun)
        scene.addEntity(fill)
        scene.addEntity(glint)
        scene.addEntity(lamp)
    }

    /**
     * Lights on, or out: out leaves only a dim ambient, so a record's face still reads on a deck
     * that draws itself (the neon one's edges glow on their own).
     */
    fun setLit(on: Boolean) {
        if (on == lit) return
        lit = on
        listOf(sun, fill, glint, lamp).forEach { if (on) scene.addEntity(it) else scene.removeEntity(it) }
        ambient.intensity = if (on) AMBIENT else AMBIENT_OUT
    }

    private var lit = true

    fun destroy() {
        scene.removeEntity(sun)
        scene.removeEntity(fill)
        scene.removeEntity(glint)
        scene.removeEntity(lamp)
        scene.indirectLight = null
        engine.destroyEntity(sun)
        engine.destroyEntity(fill)
        engine.destroyEntity(glint)
        engine.destroyEntity(lamp)
        engine.destroyIndirectLight(ambient)
        EntityManager.get().destroy(sun)
        EntityManager.get().destroy(fill)
        EntityManager.get().destroy(glint)
        EntityManager.get().destroy(lamp)
    }

    private companion object {
        const val SUN = 1.40f
        const val FILL = 0.41f
        const val GLINT = 0.35f
        const val AMBIENT = 0.47f
        /** With the lights out: enough for a record's label, little for anything else. */
        const val AMBIENT_OUT = 0.32f
        const val LAMP = 12f
    }
}
