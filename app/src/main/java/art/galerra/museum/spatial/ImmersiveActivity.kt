package art.galerra.museum.spatial

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.physics.Physics
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.physics.PhysicsState
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioType
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.Quad
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.SupportsLocomotion
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Museum VR — v0.2: scene actually renders.
 *
 * - Walkable env: gallery .glb loaded over HTTPS via NetworkedAssetLoader.
 * - Paintings: placeholder quads (1.6 × 1.0) at the saved positions/rotations.
 * - Videos: placeholder quads (1.6 × 0.9).
 * - 3D objects: glb loaded from PocketBase file URL.
 *
 * Next iterations:
 * - Real image textures (Compose panels with Coil) instead of blank quads.
 * - ExoPlayer video panels.
 * - Hotspot click → info modal (Compose panel).
 */
class ImmersiveActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var exhibitionId: String = DEFAULT_EXHIBITION_ID
    private var pendingObjects: List<SceneObjectRecord>? = null
    private var sceneReady = false

    // Single reusable info-panel entity (Compose-backed). Created in onSceneReady and
    // toggled visible/repositioned on hotspot click. See [showModalFor].
    private var infoPanelEntity: Entity? = null

    // Welcome / scene-picker panel (Compose-backed). Created in onSceneReady, visible until
    // the user picks a scene; on pick we toggle Visible(false) and trigger [loadExhibition].
    // See [spawnWelcomePanelEntity].
    private var welcomePanelEntity: Entity? = null

    // Active ExoPlayer instances for video panels, kept alive for the lifetime of the
    // activity so the GL texture stream keeps flowing. The Surface itself is owned by the
    // VideoSurfacePanelRegistration's surfaceConsumer callback so we don't need to retain it.
    private val videoPlayers = mutableListOf<ExoPlayer>()

    // Disposable IDs for runtime-registered video panels. Starts above R.id range
    // (mirrors PremiumMediaSample's `temporalID = 1500000` pattern) so we don't collide
    // with any compile-time resource IDs.
    private var nextVideoPanelId: Int = 1_500_000

    // Spatial audio: feature instance is shared across all audio emitters so each
    // ExoPlayer's audioSessionId can be registered with the spatializer. Each emitter
    // gets a unique session id slot (1, 2, 3, …) so they can play simultaneously and
    // be positioned independently in 3D.
    private val spatialAudioFeature = SpatialAudioFeature()
    private val audioPlayers = mutableListOf<ExoPlayer>()
    private var nextAudioSessionSlot = 1

    // Global mute toggled by the floating mute-button panel. Affects both audio emitters
    // (spatial audio via the underlying ExoPlayer.volume) and video panel players. Flipped
    // by [toggleAudio]; the button glyph is driven by [muteButtonState].
    private var audioMuted = false

    companion object {
        private const val TAG = "MuseumSpatial"
        private const val DEFAULT_EXHIBITION_ID = "ah1bngq5143ujhw"

        // Info panel dimensions (Quest meters) — large enough to read a paragraph at ~1.5 m.
        private const val INFO_PANEL_WIDTH_M = 1.4f
        private const val INFO_PANEL_HEIGHT_M = 0.9f
        private const val INFO_PANEL_DISTANCE_M = 1.5f
        private const val INFO_PANEL_EYE_HEIGHT_M = 1.55f

        // Floating mute-button (Quest meters). Small circular panel, parked slightly right of
        // the player at eye level — always visible, single tap toggles all ExoPlayer volumes.
        private const val MUTE_BUTTON_SIZE_M = 0.18f

        // Welcome / scene-picker panel (Quest meters). Larger than the info modal because it
        // hosts a scrollable list of exhibitions.
        private const val WELCOME_PANEL_WIDTH_M = 1.6f
        private const val WELCOME_PANEL_HEIGHT_M = 1.4f
    }

    override fun registerFeatures(): List<SpatialFeature> =
        // PhysicsFeature drives gravity + rigid-body collision for entities with a Physics
        // component, and (because we leave useGrabbablePhysics at its default of true) also
        // integrates with toolkit Grabbable: physics pauses while an object is grabbed and
        // resumes with the controller's release velocity, so grab→throw "just works".
        listOf(VRFeature(this), spatialAudioFeature, ComposeFeature(), PhysicsFeature(spatial))

    /**
     * One reusable Compose panel that hosts the info modal. The panel itself is registered
     * here (size 1.4 m × 0.9 m); the actual entity is spawned in [onSceneReady] and toggled
     * visible on hotspot click. The Compose content reads [infoPanelState], so updating
     * that state from [showModalFor] is what swaps title/description.
     */
    override fun registerPanels(): List<PanelRegistration> = listOf(
        ComposeViewPanelRegistration(
            R.id.info_panel,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply { setContent { InfoPanel() } }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = INFO_PANEL_WIDTH_M, height = INFO_PANEL_HEIGHT_M),
                    // Transparent platform theme so only the Compose-painted dark translucent
                    // background shows through (defined in InfoPanel.kt). Without this, the
                    // panel Activity window would render its default opaque white behind it.
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
            },
        ),
        // Small always-visible mute-toggle button. 0.18 m × 0.18 m, hovered eye-level slightly
        // right of center in [spawnInfoPanelEntity]'s neighborhood (see onSceneReady).
        ComposeViewPanelRegistration(
            R.id.mute_button,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply { setContent { MuteButtonPanel() } }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = MUTE_BUTTON_SIZE_M, height = MUTE_BUTTON_SIZE_M),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
            },
        ),
        // Welcome / scene-picker panel — shown on launch, replaces the auto-loaded
        // DEFAULT_EXHIBITION_ID. Compose content reads [welcomeState]; clicks bubble through
        // [welcomeOnPick]. Hidden once the user picks a scene (see [spawnWelcomePanelEntity]).
        ComposeViewPanelRegistration(
            R.id.welcome_panel,
            composeViewCreator = { _, ctx ->
                ComposeView(ctx).apply { setContent { WelcomePanel() } }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = WELCOME_PANEL_WIDTH_M, height = WELCOME_PANEL_HEIGHT_M),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
            },
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable loading meshes and textures from https URLs (PocketBase).
        NetworkedAssetLoader.init(
            File(applicationContext.cacheDir.canonicalPath),
            OkHttpAssetFetcher(),
        )

        intent?.data?.lastPathSegment?.let { id ->
            if (id.isNotBlank()) exhibitionId = id
        }

        Log.i(TAG, "Museum VR booting · exhibitionId=$exhibitionId (waiting for scene pick)")
        // NB: We no longer auto-load DEFAULT_EXHIBITION_ID here. The welcome panel (spawned in
        // onSceneReady) shows the list of scenes from PocketBase and only triggers
        // [loadExhibition] once the user picks one. See [spawnWelcomePanelEntity].
    }

    override fun onSceneReady() {
        super.onSceneReady()
        sceneReady = true

        // Reference space so headset tracking starts at floor level.
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        // Basic 3-point lighting so the gallery glb is visible.
        scene.setLightingEnvironment(
            ambientColor = Vector3(0.55f, 0.55f, 0.55f),
            sunColor = Vector3(2.0f, 2.0f, 2.0f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.6f,
        )

        // Spawn the user a couple meters in front of the gallery centroid, looking forward.
        scene.setViewOrigin(0.0f, 0.0f, 0.0f, 0.0f)

        // Spawn the single reusable info-panel entity now that the scene is up. It starts
        // hidden — [showModalFor] flips Visible(true) and updates its Transform on click.
        spawnInfoPanelEntity()

        // Spawn the always-visible mute-button panel slightly right of center at eye level.
        spawnMuteButtonEntity()

        // Spawn the welcome / scene-picker panel and kick off the scenes-list fetch. The user
        // must pick a scene before any exhibition objects are loaded — see [welcomeOnPick].
        spawnWelcomePanelEntity()

        // If exhibition already loaded before the scene was ready, place objects now.
        pendingObjects?.let {
            placeObjects(it)
            pendingObjects = null
        }
    }

    private fun spawnInfoPanelEntity() {
        // Park the panel ~1.5 m in front of the LOCAL_FLOOR origin at eye level. Repositioned
        // on every click so the player always sees it dead-ahead.
        val initialPose = Pose(
            Vector3(0f, INFO_PANEL_EYE_HEIGHT_M, -INFO_PANEL_DISTANCE_M),
        )
        infoPanelEntity = Entity.create(
            Panel(R.id.info_panel),
            PanelDimensions(Vector2(INFO_PANEL_WIDTH_M, INFO_PANEL_HEIGHT_M)),
            Transform(initialPose),
            Visible(false),
        )
        // The Close button in InfoPanel.kt calls this; hide the entity and clear state.
        infoPanelOnDismiss = {
            Log.i(TAG, "🖱️  info panel dismissed")
            infoPanelState.value = infoPanelState.value.copy(visible = false)
            infoPanelEntity?.setComponent(Visible(false))
        }
    }

    /**
     * Spawn the always-visible mute-toggle button. Parked slightly right of center (0.5 m right
     * of the LOCAL_FLOOR origin) at eye level, 1.3 m in front of the player. The button is a
     * Compose-backed panel registered in [registerPanels] (id = R.id.mute_button); clicks bubble
     * up via [muteButtonOnClick] into [toggleAudio].
     */
    private fun spawnMuteButtonEntity() {
        val pose = Pose(Vector3(0.5f, 1.4f, -1.3f))
        Entity.create(
            Panel(R.id.mute_button),
            PanelDimensions(Vector2(MUTE_BUTTON_SIZE_M, MUTE_BUTTON_SIZE_M)),
            Transform(pose),
        )
        muteButtonOnClick = { toggleAudio() }
    }

    /**
     * Spawn the welcome panel ~2 m in front of the player at eye level. Visible from launch.
     * Kicks off [PocketBaseClient.loadScenes] in the background and populates [welcomeState] so
     * the Compose tree re-renders with the list of scenes. The button taps inside the panel
     * route through [welcomeOnPick]: set the picked id, hide the panel, then trigger
     * [loadExhibition] which fans out objects into the scene.
     */
    private fun spawnWelcomePanelEntity() {
        val pose = Pose(Vector3(0f, 1.6f, -2f))
        welcomePanelEntity = Entity.create(
            Panel(R.id.welcome_panel),
            PanelDimensions(Vector2(WELCOME_PANEL_WIDTH_M, WELCOME_PANEL_HEIGHT_M)),
            Transform(pose),
            Visible(true),
        )

        welcomeOnPick = { id ->
            Log.i(TAG, "🖱️  welcome panel pick → $id")
            exhibitionId = id
            welcomeState.value = welcomeState.value.copy(visible = false)
            welcomePanelEntity?.setComponent(Visible(false))
            loadExhibition()
        }

        // Fire off the scenes-list fetch; results populate [welcomeState] on Main.
        scope.launch {
            try {
                val scenes = PocketBaseClient.loadScenes()
                Log.i(TAG, "✓ Loaded ${scenes.size} scenes for welcome panel")
                welcomeState.value = WelcomeState(scenes = scenes, loading = false, visible = true)
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to load scenes list", e)
                welcomeState.value = WelcomeState(scenes = emptyList(), loading = false, visible = true)
            }
        }
    }

    /**
     * Flip the global mute flag and apply the new volume to every ExoPlayer we've spawned for
     * spatial audio emitters and video panels. Spatial-audio attenuation is computed by the
     * spatializer from the entity Transform, but the underlying player's `volume` still gates
     * the signal — so setting it to 0 silences the emitter even though spatialization stays on.
     */
    fun toggleAudio() {
        audioMuted = !audioMuted
        val newVolume = if (audioMuted) 0f else 1f
        Log.i(TAG, "🔇 toggleAudio → muted=$audioMuted (volume=$newVolume)")
        audioPlayers.forEach { runCatching { it.volume = newVolume } }
        videoPlayers.forEach { runCatching { it.volume = newVolume } }
        muteButtonState.value = audioMuted
    }

    private fun loadExhibition() {
        scope.launch {
            try {
                val data = PocketBaseClient.loadExhibition(exhibitionId)
                Log.i(TAG, "✓ Loaded scene '${data.scene.name}' with ${data.objects.size} objects")
                if (sceneReady) placeObjects(data.objects) else pendingObjects = data.objects
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to load exhibition", e)
            }
        }
    }

    private fun placeObjects(objects: List<SceneObjectRecord>) {
        // Re-center the player on the environment (the big model) so they don't spawn
        // below or above the gallery floor. Web viewer does the same via spawnAtEnvironmentCenter.
        val env = objects.firstOrNull {
            it.expand?.asset?.type == "model" &&
                (it.walkable || it.interaction == "none" ||
                    it.scale.maxOrNull()?.let { s -> s > 1.5f } == true)
        } ?: objects.firstOrNull { it.expand?.asset?.type == "model" }
        if (env != null) {
            val ex = env.position[0]
            val ey = env.position[1]
            val ez = env.position[2]
            Log.i(TAG, "→ centering player on env '${env.title}' at ($ex, $ey, $ez)")
            // Place the player at the gallery's reference height (its origin), facing +Z.
            scene.setViewOrigin(ex, ey, ez, 0.0f)
        }

        for (obj in objects) {
            val asset = obj.expand?.asset
            if (asset == null) {
                Log.w(TAG, "skip ${obj.id}: no expanded asset")
                continue
            }
            try {
                val pose = poseFor(obj)
                val scaleVec = Vector3(obj.scale[0], obj.scale[1], obj.scale[2])
                when (asset.type) {
                    "image" -> spawnImage(asset, pose, scaleVec, obj.title, obj)
                    "video" -> spawnVideo(asset, pose, scaleVec, obj.title)
                    // The environment glb (gallery room) gets a STATIC physics collider so other
                    // objects collide with its floor/walls; non-environment models become DYNAMIC
                    // grabbable rigid bodies (see [spawnGlb]). We use referential equality against
                    // the same `env` already computed above for player re-centering, which keeps
                    // env detection in lockstep with the existing walkable/maxDim heuristic.
                    "model" -> spawnGlb(asset, pose, scaleVec, isEnvironment = (obj === env), title = obj.title)
                    "audio" -> spawnAudio(asset, pose, obj.title)
                    else -> Log.w(TAG, "unknown type: ${asset.type}")
                }
                Log.i(TAG, "✓ placed ${asset.type} '${obj.title}'")
            } catch (e: Exception) {
                Log.e(TAG, "✗ place failed for ${obj.title}", e)
            }
        }
    }

    override fun onDestroy() {
        audioPlayers.forEach { runCatching { it.release() } }
        audioPlayers.clear()
        // Release video ExoPlayers so the OS doesn't leak codec resources between sessions.
        // The panel-owned Surfaces are torn down by the Spatial runtime when the panel
        // entity is destroyed, so we don't release them here.
        videoPlayers.forEach { runCatching { it.release() } }
        videoPlayers.clear()
        super.onDestroy()
    }

    private fun poseFor(obj: SceneObjectRecord): Pose {
        val pos = Vector3(obj.position[0], obj.position[1], obj.position[2])
        val rot = Quaternion(obj.rotation[0], obj.rotation[1], obj.rotation[2])
        return Pose(pos, rot)
    }

    // Cache bitmaps keyed by entity so the modal can reuse without re-downloading.
    private val entityBitmaps = mutableMapOf<Entity, android.graphics.Bitmap>()
    private val entityRecords = mutableMapOf<Entity, SceneObjectRecord>()

    private fun spawnImage(asset: AssetRecord, pose: Pose, scaleVec: Vector3, title: String, record: SceneObjectRecord) {
        // baseColor stays white so the runtime albedo texture (set later via
        // SceneMaterial.setAlbedoTexture once the bitmap has downloaded) renders
        // unmodified. A dark baseColor would multiply the sampled texture toward black.
        val entity = Entity.create(
            Mesh(Uri.parse("mesh://quad")),
            Quad(Vector2(-0.8f, -0.5f), Vector2(0.8f, 0.5f)),
            Material().apply {
                unlit = true
                baseColor = Color4(1.0f, 1.0f, 1.0f, 1.0f) // white tint so the bitmap renders as-is
            },
            Transform(pose),
            Scale(scaleVec),
        )
        entityRecords[entity] = record
        val url = PocketBaseClient.fileUrl(asset, asset.file, thumb = "1280x1280")
        loadImageTexture(entity, url, title)
        attachClickListener(entity, title)
        spawnGoldFrame(pose, scaleVec, 1.6f, 1.0f)
    }

    private fun attachClickListener(entity: Entity, title: String) {
        val sos = systemManager.findSystem<SceneObjectSystem>()
        sos.getSceneObject(entity)?.thenAccept { so ->
            so?.addInputListener(
                object : InputListener {
                    override fun onClick(receiver: SceneObject, hitInfo: HitInfo, sourceOfInput: Entity) {
                        Log.i(TAG, "🖱️  click on '$title'")
                        showModalFor(entity)
                    }
                },
            )
        }
    }

    /**
     * Show the Compose info panel for [sourceEntity]. The panel entity itself is created once
     * (in [spawnInfoPanelEntity]); here we just update [infoPanelState] so the Compose tree
     * re-renders with the new title/description, then reposition + show the entity.
     *
     * The 3D pose is fixed relative to the LOCAL_FLOOR reference space — ~1.5 m forward at
     * eye height. (Following the panel to the live head pose would need a per-frame system;
     * the user can also turn toward it.)
     */
    private fun showModalFor(sourceEntity: Entity) {
        val record = entityRecords[sourceEntity]
        if (record == null) {
            Log.w(TAG, "info panel: no record for entity (was it spawned via spawnImage?)")
            return
        }

        // Update Compose state — this is what the panel actually renders.
        infoPanelState.value = InfoPanelState(
            title = record.title,
            description = record.description,
            visible = true,
        )

        // Reposition the panel in front of the player at eye level, then make it visible.
        val pose = Pose(
            Vector3(0f, INFO_PANEL_EYE_HEIGHT_M, -INFO_PANEL_DISTANCE_M),
        )
        infoPanelEntity?.let { panel ->
            panel.setComponent(Transform(pose))
            panel.setComponent(Visible(true))
        } ?: Log.w(TAG, "info panel entity not yet spawned (onSceneReady not called?)")

        Log.i(TAG, "✓ info panel shown for '${record.title}'")
    }

    private fun loadImageTexture(entity: Entity, url: String, title: String) {
        Log.i(TAG, "📥 fetch image '$title' ← $url")
        scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).build()
                    PocketBaseClient.client.newCall(req).execute().use { resp ->
                        Log.i(TAG, "   HTTP ${resp.code} for '$title' (${resp.body?.contentLength() ?: -1} bytes)")
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: error("empty body")
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ image fetch failed for '$title'", e)
                null
            }
            if (bitmap == null) {
                Log.w(TAG, "decoded bitmap was null for '$title'")
                return@launch
            }
            entityBitmaps[entity] = bitmap
            Log.i(TAG, "   decoded bitmap ${bitmap.width}×${bitmap.height} for '$title'")

            val sos = systemManager.findSystem<SceneObjectSystem>()
            val future = sos.getSceneObject(entity)
            if (future == null) {
                Log.w(TAG, "no SceneObject future for '$title'")
                return@launch
            }
            future.thenAccept { so ->
                Log.i(TAG, "   SceneObject ready for '$title' (so=$so)")
                try {
                    // The toolkit Material lives on the runtime mesh; access via mesh.materials,
                    // not SceneObject.materials. (See PremiumMediaSample HeroLightingSystem.kt,
                    // AnimationsSample ButtonController.kt, geo_voyage MainActivity.kt.)
                    val mats = so?.mesh?.materials
                    Log.i(TAG, "   materials count=${mats?.size ?: 0} for '$title'")
                    val mat = mats?.firstOrNull()
                    if (mat == null) {
                        Log.w(TAG, "no material on SceneObject for '$title'")
                        return@thenAccept
                    }
                    val tex = SceneTexture(bitmap, SamplerConfig())
                    // For an unlit toolkit Material, the shader samples the albedo texture only.
                    // Use SceneMaterial.setAlbedoTexture (NOT setTexture("emissive", …) — the
                    // unlit shader has no emissive sampler, so writing there silently no-ops).
                    runCatching { mat.setAlbedoTexture(tex) }
                        .onSuccess { Log.i(TAG, "🖼️  '$title' textured via setAlbedoTexture") }
                        .onFailure { e -> Log.e(TAG, "✗ setAlbedoTexture failed for '$title'", e) }
                } catch (e: Exception) {
                    Log.e(TAG, "✗ apply texture failed for '$title'", e)
                }
            }
        }
    }

    /**
     * Spawn a 1.6 × 0.9 m video panel streamed via ExoPlayer.
     *
     * Pipeline (mirrors Meta's MediaPlayerSample / PremiumMediaSample DIRECT_TO_SURFACE pattern):
     *   ExoPlayer ─▶ Surface ─▶ VideoSurfacePanelRegistration ─▶ Panel entity
     *
     * SDK 0.12 has no public `SceneTexture(SurfaceTexture)` constructor, so we cannot push a
     * SurfaceTexture into a quad's `Material.setAlbedoTexture(…)` directly — the runtime owns
     * the GL stream behind a `Panel`. The closest equivalent shape on a quad is
     * `MediaPanelSettings(shape = QuadShapeOptions(w, h))`, which renders the video as a flat
     * rectangle (no panel chrome, just the video).
     *
     * Auto-loops muted (volume = 0, repeatMode = ALL, playWhenReady = true).
     */
    private fun spawnVideo(asset: AssetRecord, pose: Pose, scaleVec: Vector3, title: String) {
        // Build the ExoPlayer up-front. surfaceConsumer fires asynchronously when the Spatial
        // runtime has created the underlying GL surface for the panel, at which point we wire
        // the surface into ExoPlayer and call prepare(). Setting playWhenReady = true here
        // means playback starts the moment surface + media item are both ready.
        val url = PocketBaseClient.fileUrl(asset, asset.file)
        Log.i(TAG, "🎬 spawnVideo '$title' ← $url")
        val player = ExoPlayer.Builder(applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
        }
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "✗ video player error for '$title': ${error.errorCodeName}", error)
                }
            }
        )
        videoPlayers += player

        // Register a runtime VideoSurfacePanel with a unique id (above R.id range).
        // The Spatial runtime owns the GL surface; once it's created it calls back into
        // surfaceConsumer where we route ExoPlayer output into it.
        val panelId = nextVideoPanelId++
        registerPanel(
            VideoSurfacePanelRegistration(
                panelId,
                surfaceConsumer = { _, surface ->
                    Log.i(TAG, "🎞️  surface ready for video '$title' (panelId=$panelId)")
                    player.setVideoSurface(surface)
                    player.prepare()
                },
                settingsCreator = {
                    MediaPanelSettings(
                        // 1.6 m × 0.9 m flat quad matching the old placeholder dimensions.
                        shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
                        // Logical pixel resolution of the surface. ExoPlayer will rescale into
                        // this buffer regardless of source resolution; 1080p is a good default.
                        display = PixelDisplayOptions(width = 1920, height = 1080),
                        rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
                    )
                },
            )
        )

        // The Panel component binds the entity to the registered panel id. Transform + Scale
        // place it in the world; the panel itself owns the rendered video surface.
        Entity.create(
            Panel(panelId),
            Transform(pose),
            Scale(scaleVec),
        )
        spawnGoldFrame(pose, scaleVec, 1.6f, 0.9f)
    }

    /**
     * Build a 4-piece gold "picture frame" around a flat plane of size [planeW] × [planeH].
     * Mirrors the web viewer's buildFrame in viewerScene.ts (slim T=0.04 / D=0.05 profile).
     *
     * Each side is a `mesh://box` entity with a `Box(min, max)` component giving it its
     * dimensions. The frame children share the parent's rotation so they sit flush against
     * the painting; their local offsets are rotated into world space by `parentPose.q`
     * (Spatial SDK's `Quaternion * Vector3` overload — see PremiumMediaSample TouchScalableSystem).
     *
     * baseColor is a warm desaturated gold; `unlit = true` avoids depending on scene lighting
     * (which would otherwise make the frame appear muddy under low ambient).
     */
    private fun spawnGoldFrame(parentPose: Pose, parentScale: Vector3, planeW: Float, planeH: Float) {
        val t = 0.04f          // bar thickness (cross-section, perpendicular to the plane edge)
        val d = 0.05f          // bar depth (out of the wall)
        val frontZ = 0.025f    // push frame slightly forward so it doesn't z-fight the quad
        val totalW = planeW + 2f * t

        // Build one side at [localOffset] (frame-local: +X right, +Y up, +Z out of the wall).
        // Box half-extents are centered on the entity's own origin; we then translate to
        // parentPose.t + parentPose.q.rotate(localOffset) so sides sit correctly even when
        // the parent painting is rotated. parentScale is reapplied so the frame scales with
        // any per-object scale stored in PocketBase.
        fun makeSide(localOffset: Vector3, sizeX: Float, sizeY: Float, sizeZ: Float) {
            val hx = sizeX * 0.5f
            val hy = sizeY * 0.5f
            val hz = sizeZ * 0.5f
            val worldOffset = parentPose.q * localOffset
            val sidePose = Pose(parentPose.t + worldOffset, parentPose.q)
            Entity.create(
                Mesh(Uri.parse("mesh://box")),
                Box(Vector3(-hx, -hy, -hz), Vector3(hx, hy, hz)),
                Material().apply {
                    baseColor = Color4(0.85f, 0.65f, 0.32f, 1f)
                    unlit = true
                },
                Transform(sidePose),
                Scale(parentScale),
            )
        }

        // top
        makeSide(Vector3(0f, planeH / 2f + t / 2f, frontZ), totalW, t, d)
        // bottom
        makeSide(Vector3(0f, -planeH / 2f - t / 2f, frontZ), totalW, t, d)
        // left
        makeSide(Vector3(-planeW / 2f - t / 2f, 0f, frontZ), t, planeH, d)
        // right
        makeSide(Vector3(planeW / 2f + t / 2f, 0f, frontZ), t, planeH, d)
    }

    private fun spawnVideoPlaceholder(pose: Pose, scaleVec: Vector3, title: String) {
        // 1.6 × 0.9 m plane, bright cyan so it stands out from images.
        Entity.create(
            Mesh(Uri.parse("mesh://quad")),
            Quad(Vector2(-0.8f, -0.45f), Vector2(0.8f, 0.45f)),
            Material().apply {
                unlit = true
                baseColor = Color4(0.32f, 0.78f, 1.0f, 1.0f) // cyan
            },
            Transform(pose),
            Scale(scaleVec),
        )
    }

    /**
     * Spawn an invisible point emitter that streams the asset's audio file via ExoPlayer,
     * routed through the Spatial SDK spatializer so volume falls off with distance from
     * the listener. Looping, autoplay. Entity has Transform only (no Mesh → invisible).
     *
     * Hookup (per PremiumMediaSample/ExoVideoEntity.addLinkSpatialAudioListener):
     *   1. Pick a unique session slot per emitter (the spatializer maps slot → audioSessionId).
     *   2. Once the ExoPlayer reaches STATE_READY, call
     *        spatialAudioFeature.registerAudioSessionId(slot, player.audioSessionId)
     *      and attach an AudioSessionId(slot, AudioType.{MONO,STEREO,SOUNDFIELD}) component
     *      to the emitter entity. The spatializer then attenuates/pans based on the entity's
     *      Transform vs. the headset pose.
     */
    private fun spawnAudio(asset: AssetRecord, pose: Pose, title: String) {
        val url = PocketBaseClient.fileUrl(asset, asset.file)
        val slot = nextAudioSessionSlot++
        Log.i(TAG, "🎵 spawnAudio slot=$slot '$title' ← $url")

        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            prepare()
        }
        audioPlayers.add(player)

        // Invisible emitter entity — Transform only, no Mesh. The spatial audio system
        // reads the entity's world position via Transform.
        val entity = Entity.create(Transform(pose))

        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val format = player.audioFormat
                        val audioType = when (format?.channelCount) {
                            1 -> AudioType.MONO
                            2 -> AudioType.STEREO
                            null -> AudioType.STEREO
                            else -> AudioType.SOUNDFIELD
                        }
                        try {
                            spatialAudioFeature.registerAudioSessionId(slot, player.audioSessionId)
                            entity.setComponent(AudioSessionId(slot, audioType))
                            Log.i(TAG, "🔊 spatialized '$title' slot=$slot type=$audioType sessionId=${player.audioSessionId}")
                        } catch (e: Exception) {
                            Log.e(TAG, "✗ spatial-audio register failed for '$title'", e)
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "✗ audio player error for '$title': ${error.errorCodeName}", error)
                }
            }
        )
    }

    /**
     * Spawn a glb-based model. Two distinct shapes depending on role:
     *
     * - Environment (the gallery room): walkable via [SupportsLocomotion], plus a **STATIC**
     *   Physics body using the mesh's own convex hull as the collider. This is what lets
     *   thrown hotspot objects collide with the gallery floor/walls instead of falling
     *   through them. STATIC bodies never move themselves; they're immovable surfaces.
     *
     * - Interactive hotspot model: [Grabbable] (PIVOT_Y so it stays upright when held) +
     *   a **DYNAMIC** Physics body with a box collider sized from the model's scale.
     *   With `useGrabbablePhysics = true` on [PhysicsFeature], grabbing temporarily pauses
     *   physics simulation; releasing resumes it and the SDK seeds the body's linear
     *   velocity from the controller's motion delta — so flicking the controller throws
     *   the object naturally (gravity then pulls it back to the floor).
     *
     * Why CONVEX_HULL for the env: STATIC bodies are the one case where we could use
     * TRIANGLE_MESH (exact, but slow); however CONVEX_HULL is fast and good enough for a
     * single-room gallery floor. Adjust the env [Physics.shape]/[Physics.dimensions] if
     * the convex hull occludes alcoves/doorways visibly.
     */
    private fun spawnGlb(
        asset: AssetRecord,
        pose: Pose,
        scaleVec: Vector3,
        isEnvironment: Boolean,
        title: String,
    ) {
        val url = PocketBaseClient.fileUrl(asset, asset.file)
        if (isEnvironment) {
            // Gallery room: walkable + static physics floor/walls. Pass the same glb URL as
            // the Physics.shape so the runtime builds a collider matching the visible mesh.
            Entity.create(
                Mesh(Uri.parse(url)),
                SupportsLocomotion(),
                Transform(pose),
                Scale(scaleVec),
                Physics().apply {
                    shape = url
                    state = PhysicsState.STATIC
                    restitution = 0.1f
                },
            )
            Log.i(TAG, "🏛️  env glb (static-physics) '$title'")
            return
        }
        // Interactive hotspot model: grabbable + dynamic rigid body so it falls under gravity
        // when released. Box collider sized from the entity's scale; density tuned so a typical
        // 0.5 m model has perceptible-but-not-leaden mass. PIVOT_Y keeps the model upright
        // while grabbed (matches Object3DSampleIsdk/PanelLayout.kt and PremiumMediaSample).
        Entity.create(
            Mesh(Uri.parse(url)),
            Transform(pose),
            Scale(scaleVec),
            Grabbable(true, GrabbableType.PIVOT_Y),
            Physics().apply {
                shape = "box"
                state = PhysicsState.DYNAMIC
                // Half-extents in object-local units (multiplied by Scale at the physics layer).
                // 0.25 m gives a 0.5 m cube as the default collision volume, scaled by the
                // entity's Scale component. Tweak if hotspot models read as too big/small.
                dimensions = Vector3(0.25f, 0.25f, 0.25f)
                density = 0.5f
                restitution = 0.3f
            },
        )
        Log.i(TAG, "🎯 hotspot glb (grabbable+dynamic-physics) '$title'")
    }
}
