package app.runout.ui.deck

import app.runout.ui.DeckSkin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio

/**
 * Draws the 3D turntable into a [TextureView] with Filament, over a transparent background so the
 * room's light shows around it. The deck's state comes in through the setters; frames are drawn
 * only when something changed, and the frame callback is only armed while there is something to
 * draw, so a still deck costs no work per vsync. Everything here runs on the main thread.
 */
class DeckRenderer(private val context: Context, textureView: TextureView, model: String = DeckSkin.WHITE_TEAL.asset!!, glow: Boolean = false) : Choreographer.FrameCallback {
    private val engine = FilamentShared.engine
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null

    private val deck: FilamentAsset = FilamentShared.load(context, model)
    private val record: FilamentAsset = FilamentShared.load(context, "deck/record.glb")
    private val platter = deck.getFirstEntityByName(DeckModel.PLATTER)
    private val tonearm = deck.getFirstEntityByName(DeckModel.TONEARM)
    private val keyEntities = DeckModel.keys.associateWith { deck.getFirstEntityByName(it.node) }
    private val statusLamp = materialOf(DeckModel.LED_STATUS)
    private val strip = materialOf(DeckModel.LED_STRIP)
    /** The title and time windows share one material in the model; gltfio may still split it. */
    private val screens = listOfNotNull(materialOf(DeckModel.SCREEN), materialOf(DeckModel.SCREEN_TIME)).distinct()
    private val lights: StudioLights
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val flatSampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private var faceTexture: Texture? = null
    private var surfaceTextures: List<Texture> = emptyList()
    private var recordShown = false
    private val screenPixels = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)
    private val screenTexture = flatTexture(screenPixels)
    private val stripPixels = Bitmap.createBitmap(256, 8, Bitmap.Config.ARGB_8888)
    private val stripTexture = flatTexture(stripPixels)
    private val pixelFont: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/silkscreen.ttf") }.getOrDefault(Typeface.MONOSPACE)

    private var platterDegrees = 0f
    private var flip = 0f
    private var liftOff = 0f
    private var tilt = DeckCamera.TILT_DEG
    private var viewW = 0
    private var viewH = 0
    private var armAngle = DeckModel.angleRest
    private var armDrop = 0f
    private val keyPress = DeckModel.keys.associateWith { 0f }.toMutableMap()
    private var dirty = true
    /** Frames still to draw after a change: a TextureView can drop a lone first frame. */
    private var settle = 0
    private var running = false
    /** A frame callback is posted and not yet run. */
    private var scheduled = false

    /** Called on each frame with work to do, so the room redraws and the TextureView takes the frame. */
    var onFrame: (() -> Unit)? = null
    /** Called once, when the deck is on screen. */
    var onReady: (() -> Unit)? = null
    private var drawn = 0

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.antiAliasing = View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = true; radius = 0.25f; intensity = 0.8f }
        // Linear, at an exposure of 1, with the lights below summing to 1 on a surface facing up: a
        // record lying on the platter shows its face in the very colours the room paints it in your
        // hand. A film curve would shift them as it lands.
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
        camera.setExposure(1f)
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        lights = StudioLights(engine, scene, shadows = true)
        // The neon deck's edges are brighter than white: bloom gives them their halo.
        if (glow) view.bloomOptions = view.bloomOptions.apply { enabled = true; strength = 0.22f; threshold = true; levels = 6 }

        scene.addEntities(deck.entities)
        val rm = engine.renderableManager
        // Solid parts cast and catch shadows. Decals, lights, the screen and the baked shadow lie a
        // hair above a surface: casting would print a grey box under each, catching gives acne.
        val flat = setOf("Prints", "Shadow_Plane", "Screen_Glass", DeckModel.SCREEN, DeckModel.SCREEN_TIME, DeckModel.LED_STRIP, DeckModel.LED_STATUS)
        (deck.entities + record.entities).forEach { e ->
            if (!rm.hasComponent(e)) return@forEach
            val i = rm.getInstance(e)
            val decal = deck.getName(e) in flat
            rm.setCastShadows(i, !decal)
            rm.setReceiveShadows(i, !decal)
            for (p in 0 until rm.getPrimitiveCount(i)) {
                val m = rm.getMaterialInstanceAt(i, p)
                // A key's printed glyph is a second see-through layer on a see-through cap: draw it last.
                if (m.name == "Prints_Mat") rm.setBlendOrderAt(i, p, 1)
            }
        }
        // The screen and the strip are drawn by the app: start them dark.
        screens.forEach {
            useTexture(it, screenTexture, "baseColorMap", "emissiveMap")
            it.setParameter("emissiveFactor", 1f, 1f, 1f)
        }
        strip?.let { useTexture(it, stripTexture, "emissiveMap") }
        setScreen("", null, null)
        setStrip(0f, false)
        setStatusLamp(false)
        applyTransforms()

        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                displayHelper.attach(renderer, textureView.display)
                warmUp()
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                viewW = width
                viewH = height
                frame(DeckCamera(width.toFloat(), height.toFloat(), tilt))
                warmUp()
            }
        }
        uiHelper.attachTo(textureView)
    }

    /** The view's tilt off vertical, in degrees (see [DeckCamera]); 0 looks straight down. */
    fun setTilt(degrees: Float) {
        if (degrees == tilt) return
        tilt = degrees
        if (viewW > 0 && viewH > 0) frame(DeckCamera(viewW.toFloat(), viewH.toFloat(), tilt))
        invalidate()
    }

    /** Matches Filament's camera to the one the touch mapping uses. */
    private fun frame(c: DeckCamera) {
        camera.setProjection(c.fovHorizontalDegrees, (c.width / c.height).toDouble(), c.near.toDouble(), c.far.toDouble(), Camera.Fov.HORIZONTAL)
        camera.lookAt(
            c.eye.x.toDouble(), c.eye.y.toDouble(), c.eye.z.toDouble(),
            c.target.x.toDouble(), c.target.y.toDouble(), c.target.z.toDouble(),
            c.viewUp.x.toDouble(), c.viewUp.y.toDouble(), c.viewUp.z.toDouble(),
        )
    }

    /** Keeps drawing for a moment after the surface appears or changes size. */
    private fun warmUp() {
        dirty = false
        settle = WARM_UP_FRAMES
        requestFrame()
    }

    /** Something changed: draw it on the next frame. */
    private fun invalidate() {
        dirty = true
        requestFrame()
    }

    private fun requestFrame() {
        if (!running || scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun setPlatter(degrees: Float) {
        if (degrees != platterDegrees) { platterDegrees = degrees; invalidate() }
    }

    /** The record turning over, 0 to 1 (see [DeckModel.flipTransform]); 0 lies on the mat. */
    fun setFlip(t: Float) {
        if (t == flip) return
        flip = t
        invalidate()
    }

    /** The record being taken off the platter, 0 to 1 (see [DeckModel.liftOffTransform]). */
    fun setLiftOff(t: Float) {
        if (t == liftOff) return
        liftOff = t
        invalidate()
    }

    fun setArm(angle: Float, drop: Float) {
        if (angle != armAngle || drop != armDrop) { armAngle = angle; armDrop = drop; invalidate() }
    }

    fun setKey(key: DeckKey, pressed: Float) {
        if (keyPress[key] != pressed) { keyPress[key] = pressed; invalidate() }
    }

    /**
     * The baked shadow under the deck. On the dark room's true black its grey would glow round the
     * plinth rather than shade the floor, so there it's taken out of the scene.
     */
    /** The studio lights on or out (see [StudioLights.setLit]). */
    fun setLit(on: Boolean) {
        lights.setLit(on)
        invalidate()
    }

    fun setGroundShadow(visible: Boolean) {
        val e = deck.getFirstEntityByName("Shadow_Plane").takeIf { it != 0 } ?: return
        if (visible) scene.addEntity(e) else scene.removeEntity(e)
        invalidate()
    }

    /**
     * For [DeckCapture]: the named [part] shown or hidden, and with [only] every other part hidden.
     * A null part puts the whole deck back.
     */
    internal fun showPart(part: String?, visible: Boolean, only: Boolean = false) {
        deck.entities.forEach { e ->
            val show = when {
                part == null -> true
                deck.getName(e) == part -> visible
                else -> !only
            }
            if (show) scene.addEntity(e) else scene.removeEntity(e)
        }
        invalidate()
    }

    /** The orange lamp by START/STOP: lit while a record plays. */
    fun setStatusLamp(lit: Boolean) {
        val m = statusLamp ?: return
        // Off, the lens is dark amber glass; lit, it glows signal orange without blowing out white.
        if (lit) {
            m.setParameter("baseColorFactor", 1f, 0.254f, 0.072f, 1f)
            m.setParameter("emissiveFactor", 1f, 0.254f, 0.072f)
            glow(m, 2f)
        } else {
            m.setParameter("baseColorFactor", 0.16f, 0.06f, 0.035f, 1f)
            m.setParameter("emissiveFactor", 0f, 0f, 0f)
        }
        invalidate()
    }

    /**
     * The LED strip along the front: lit from the left as far as the side has played, the rest a
     * faint glow; brighter while the record turns.
     */
    fun setStrip(progress: Float, playing: Boolean) {
        val m = strip ?: return
        val c = Canvas(stripPixels)
        val p = Paint().apply { isAntiAlias = false }
        c.drawColor(android.graphics.Color.rgb(40, 34, 30))
        if (progress > 0f) {
            p.color = android.graphics.Color.rgb(255, 214, 180)
            c.drawRect(0f, 0f, stripPixels.width * progress.coerceIn(0f, 1f), stripPixels.height.toFloat(), p)
        }
        TextureHelper.setBitmap(engine, stripTexture, 0, stripPixels)
        glow(m, if (playing) 5f else 2f)
        invalidate()
    }

    /**
     * The red LED windows along the front: [title] in the pixel font across the long one; the track
     * [code] and the [time] ("m:ss", or null for none) in seven-segment digits in the short one,
     * the unlit segments faintly showing, as on a real display.
     */
    fun setScreen(title: String, code: String?, time: String?) {
        val c = Canvas(screenPixels)
        c.drawColor(android.graphics.Color.BLACK)
        val p = Paint().apply {
            isAntiAlias = true
            typeface = pixelFont
            textSize = 62f
            letterSpacing = 0.04f
            color = LED_ON
        }
        c.drawText(fit(title, p, 1024f - 2 * 36f), 36f, 64f + 22f, p)
        if (code != null) c.drawText(fit(code, p, 140f), 36f, 128f + 64f + 22f, p)
        drawTime(c, time)
        TextureHelper.setBitmap(engine, screenTexture, 0, screenPixels)
        screens.forEach { glow(it, 1.6f) }
        invalidate()
    }

    /** "m:ss" as four seven-segment digits and a colon, at the right of the time window's part of the texture. */
    private fun drawTime(c: Canvas, time: String?) {
        val digits = time?.filter { it.isDigit() }?.padStart(4, '0')?.takeLast(4)
        val unit = 128f
        val w = 0.36f * unit
        val h = 0.62f * unit
        val t = 0.075f * unit
        val gap = 0.13f * unit
        val colon = 0.22f * unit
        val region = DeckModel.TIME_U * screenPixels.width
        var x = region - 36f - (4 * w + 2 * gap + colon)
        val bottom = 128f + 64f + h / 2f
        val on = Paint().apply { isAntiAlias = true; color = LED_ON }
        val off = Paint().apply { isAntiAlias = true; color = LED_GHOST }
        for (i in 0 until 4) {
            val lit = digits?.get(i)?.let { SEGMENTS.getValue(it) } ?: ""
            for (seg in "abcdefg") c.drawPath(segment(seg, x, bottom, w, h, t), if (seg in lit) on else off)
            x += w + if (i == 1) 0f else gap
            if (i == 1) {
                for (fy in floatArrayOf(0.3f, 0.7f)) {
                    val cy = bottom - h * fy
                    val cx = x + colon / 2f + SLANT * (bottom - cy)
                    c.drawRect(cx - t / 2f, cy - t / 2f, cx + t / 2f, cy + t / 2f, if (digits != null) on else off)
                }
                x += colon
            }
        }
    }

    /** One segment of a digit whose bottom-left corner is at ([x], [bottom]), leaning right as it rises. */
    private fun segment(seg: Char, x: Float, bottom: Float, w: Float, h: Float, t: Float): Path {
        val g = t * 0.12f
        val mid = bottom - h / 2f
        val top = bottom - h
        val (x0, y0, x1, y1) = when (seg) {
            'a' -> listOf(x + g, top, x + w - g, top)
            'g' -> listOf(x + g, mid, x + w - g, mid)
            'd' -> listOf(x + g, bottom, x + w - g, bottom)
            'f' -> listOf(x, mid - g, x, top + g)
            'b' -> listOf(x + w, mid - g, x + w, top + g)
            'e' -> listOf(x, bottom - g, x, mid + g)
            else -> listOf(x + w, bottom - g, x + w, mid + g)
        }
        val half = t / 2f
        val pts = if (y0 == y1) {
            listOf(x0 to y0, x0 + half to y0 - half, x1 - half to y0 - half, x1 to y0, x1 - half to y0 + half, x0 + half to y0 + half)
        } else {
            listOf(x0 to y0, x0 + half to y0 - half, x0 + half to y1 + half, x0 to y1, x0 - half to y1 + half, x0 - half to y0 - half)
        }
        return Path().apply {
            pts.forEachIndexed { i, (px, py) ->
                val sx = px + SLANT * (bottom - py)
                if (i == 0) moveTo(sx, py) else lineTo(sx, py)
            }
            close()
        }
    }

    private fun fit(text: String, p: Paint, width: Float): String {
        var t = text.uppercase()
        while (t.isNotEmpty() && p.measureText(t) > width) t = t.dropLast(1)
        return t
    }

    /** The record's [face] (vinyl, label, track gaps), or null for an empty platter. */
    fun setRecord(face: Bitmap?) {
        if (face == null) {
            if (recordShown) { scene.removeEntities(record.entities); recordShown = false; invalidate() }
            return
        }
        val t = FilamentShared.mipmapped(face, srgb = true)
        record.instance.materialInstances.forEach { it.setParameter("baseColorMap", t, sampler) }
        faceTexture?.let { engine.destroyTexture(it) }
        faceTexture = t
        if (!recordShown) { scene.addEntities(record.entities); recordShown = true }
        invalidate()
    }

    /** The record's grooves and glossy bands between tracks, from [RecordMaps]. */
    fun setRecordSurface(normal: Bitmap, roughness: Bitmap) {
        val n = FilamentShared.mipmapped(normal, srgb = false)
        val r = FilamentShared.mipmapped(roughness, srgb = false)
        record.instance.materialInstances.forEach {
            if (it.material.hasParameter("normalMap")) it.setParameter("normalMap", n, sampler)
            if (it.material.hasParameter("normalScale")) it.setParameter("normalScale", 1f)
            if (it.material.hasParameter("metallicRoughnessMap")) it.setParameter("metallicRoughnessMap", r, sampler)
        }
        surfaceTextures.forEach { engine.destroyTexture(it) }
        surfaceTextures = listOf(n, r)
        invalidate()
    }

    fun resume() {
        if (!running) { running = true; invalidate() }
    }

    fun pause() {
        running = false
        scheduled = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!running) return
        // No surface yet: onNativeWindowChanged asks for frames again when one arrives.
        val chain = swapChain ?: return
        if (dirty) { settle = maxOf(settle, SETTLE_FRAMES); dirty = false }
        // Nothing to draw: the callback stays off until a setter asks again. Not before it's shown,
        // though: a still deck's few settling frames can end while Filament is still skipping.
        if (settle <= 0 && ready) return
        if (!uiHelper.isReadyToRender) { requestFrame(); return }
        applyTransforms()
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            settle--
            // The first frames wait on the GPU building its shaders (seconds on an emulator), and
            // Filament skips frames until they're done. A few frames through means it's showing.
            if (++drawn == READY_FRAMES) onReady?.invoke()
        }
        // Request a Compose redraw even when Filament skips a frame.
        onFrame?.invoke()
        if (settle > 0 || !ready) requestFrame()
    }

    /** Frames are reaching the screen ([onReady] has been called, or would have been). */
    val ready: Boolean get() = drawn >= READY_FRAMES

    private fun applyTransforms() {
        val tm = engine.transformManager
        if (platter != 0) tm.setTransform(tm.getInstance(platter), DeckModel.platterTransform(platterDegrees))
        if (tonearm != 0) tm.setTransform(tm.getInstance(tonearm), DeckModel.armTransform(armAngle, armDrop))
        keyEntities.forEach { (key, e) -> if (e != 0) tm.setTransform(tm.getInstance(e), DeckModel.keyTransform(key, keyPress[key] ?: 0f)) }
        tm.setTransform(
            tm.getInstance(record.root),
            if (liftOff > 0f) DeckModel.liftOffTransform(platterDegrees, liftOff) else DeckModel.flipTransform(platterDegrees, flip),
        )
    }

    private fun materialOf(node: String): MaterialInstance? {
        val e = deck.getFirstEntityByName(node)
        val rm = engine.renderableManager
        if (e == 0 || !rm.hasComponent(e)) return null
        return rm.getMaterialInstanceAt(rm.getInstance(e), 0)
    }

    private fun useTexture(m: MaterialInstance, t: Texture, vararg params: String) {
        params.forEach { if (m.material.hasParameter(it)) m.setParameter(it, t, flatSampler) }
    }

    /** Emissive strength (KHR_materials_emissive_strength), where the material has it. */
    private fun glow(m: MaterialInstance, strength: Float) {
        if (m.material.hasParameter("emissiveStrength")) m.setParameter("emissiveStrength", strength)
    }

    private fun flatTexture(b: Bitmap): Texture = Texture.Builder()
        .width(b.width)
        .height(b.height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .build(engine)

    fun destroy() {
        pause()
        onFrame = null
        onReady = null
        uiHelper.detach()
        if (recordShown) scene.removeEntities(record.entities)
        scene.removeEntities(deck.entities)
        faceTexture?.let { engine.destroyTexture(it) }
        surfaceTextures.forEach { engine.destroyTexture(it) }
        engine.destroyTexture(screenTexture)
        engine.destroyTexture(stripTexture)
        FilamentShared.loader.destroyAsset(deck)
        FilamentShared.loader.destroyAsset(record)
        lights.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        // The engine is shared (FilamentShared) and lives on.
    }

    companion object {
        private const val SETTLE_FRAMES = 3
        private const val WARM_UP_FRAMES = 30
        private const val READY_FRAMES = 4


        private val LED_ON = android.graphics.Color.rgb(255, 46, 20)
        private val LED_GHOST = android.graphics.Color.rgb(42, 7, 5)
        /** How far a seven-segment digit leans, in x per unit of height. */
        private const val SLANT = 0.10f
        private val SEGMENTS = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
            '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
        )

        /**
         * Whether Filament's native code can run here. Off under Robolectric (the JVM tests), where
         * the deck falls back to a flat stand-in drawn in Compose.
         */
        val available: Boolean by lazy {
            if (Build.FINGERPRINT == "robolectric") return@lazy false
            runCatching { Filament.init(); Gltfio.init() }.isSuccess
        }
    }
}
