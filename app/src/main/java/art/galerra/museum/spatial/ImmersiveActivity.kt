package art.galerra.museum.spatial

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
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
import com.meta.spatial.core.Query
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
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
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

    // Disposable IDs for runtime-registered per-source audio icon panels. Starts above the
    // video panel id range (2_000_000) so we don't collide with [nextVideoPanelId] (1_500_000+)
    // or any compile-time resource id.
    private var nextAudioIconPanelId: Int = 2_000_000

    // Global mute toggled by the floating mute-button panel. Affects both audio emitters
    // (spatial audio via the underlying ExoPlayer.volume) and video panel players. Flipped
    // by [toggleAudio]; the button glyph is driven by [muteButtonState].
    private var audioMuted = false

    // Custom locomotion replacing the SDK's default teleport-on-thumbstick-release. Built in
    // [onSceneReady] after disabling the default [LocomotionSystem]. The reference is kept so
    // every external `scene.setViewOrigin` call can be mirrored into the system's cached
    // (currentX, currentY, currentZ, currentYawDeg) via [SmoothLocomotionSystem.syncFromScene]
    // — otherwise the next frame of stick input would jump the player back to stale state.
    private var smoothLocomotion: SmoothLocomotionSystem? = null

    // Click-to-animated-move tween for the player view origin. Coexists with [smoothLocomotion]:
    // sticks still drive continuous walking, while trigger-clicks on the gallery floor animate
    // the player smoothly to the hit point (~600 ms, cubic ease-in-out). Mirrors the Babylon
    // web viewer's [teleportTo] (viewerScene.ts). Registered in [onSceneReady]; floor clicks
    // are routed via [attachFloorClickListener] which runs at the end of [placeObjects] once
    // the env glb's SceneObject exists.
    private var teleportTween: TeleportTweenSystem? = null

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

        // Per-source in-scene audio icon (Quest meters). 0.25 × 0.25 m disc pinned to each
        // audio emitter's pose. Single tap mutes only that ExoPlayer (not the global bus).
        private const val AUDIO_ICON_SIZE_M = 0.25f

        // Welcome / scene-picker panel (Quest meters). Larger than the info modal because it
        // hosts a scrollable list of exhibitions.
        private const val WELCOME_PANEL_WIDTH_M = 1.6f
        private const val WELCOME_PANEL_HEIGHT_M = 1.4f
    }

    override fun registerFeatures(): List<SpatialFeature> =
        // Order matches StarterSample: VRFeature first (initialises the OpenXR session +
        // panel render targets that ComposeFeature attaches to), ComposeFeature second
        // (registers the Compose-backed panel renderer used by ComposeViewPanelRegistration),
        // then optional features.
        //
        // PhysicsFeature drives gravity + rigid-body collision for entities with a Physics
        // component, and (because we leave useGrabbablePhysics at its default of true) also
        // integrates with toolkit Grabbable: physics pauses while an object is grabbed and
        // resumes with the controller's release velocity, so grab→throw "just works".
        listOf(VRFeature(this), ComposeFeature(), spatialAudioFeature, PhysicsFeature(spatial))

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

        // Richer warm "museum at evening" lighting — warm ambient floor + a strong, slightly
        // warm top-front sun + IBL from environment.env for glb material reflections. Matches
        // StarterSample's setLightingEnvironment + updateIBLEnvironment + skydome pattern.
        scene.setLightingEnvironment(
            ambientColor = Vector3(0.18f, 0.16f, 0.14f),
            sunColor = Vector3(3.5f, 3.2f, 2.7f),
            sunDirection = -Vector3(0.6f, 1.0f, -0.4f).normalize(),
            environmentIntensity = 0.85f,
        )
        // IBL cube map for PBR reflections on the gallery glb. Asset is copied from
        // StarterSample's app/src/main/assets/environment.env. Wrapped in runCatching so a
        // missing asset doesn't crash the scene — lighting still works without IBL.
        runCatching { scene.updateIBLEnvironment("environment.env") }
            .onFailure { Log.w(TAG, "IBL environment.env not loaded; PBR reflections disabled", it) }

        // Subtle dark-night skybox so the void outside the gallery doesn't read as pure black.
        // mesh://skybox is a built-in inward-facing cube; unlit so scene lighting can't darken it.
        Entity.create(
            Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
            Material().apply {
                unlit = true
                baseColor = Color4(0.05f, 0.05f, 0.08f, 1f)
            },
            Transform(Pose()),
        )

        // Disable the SDK's built-in teleport locomotion BEFORE we register our smooth
        // replacement. The default [LocomotionSystem] writes the view origin every time the
        // thumbstick is released (teleport-on-release), which would fight our per-frame
        // continuous-walk writes. Calling enableLocomotion(false) leaves the system installed
        // but no-ops its execute() body — safer than unregisterSystem, which other samples
        // (PremiumMediaSample) do but which removes the entity-tracking machinery the SDK
        // uses for the controller laser cursor.
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)

        // Register our custom smooth-walking + snap-turn replacement. The system holds its
        // own (x, y, z, yaw) state and writes the view origin every frame the thumbsticks are
        // held. See [SmoothLocomotionSystem] kdoc for the input model and movement math.
        smoothLocomotion = SmoothLocomotionSystem(scene).also { systemManager.registerSystem(it) }

        // Click-to-animated-move tween (~600 ms cubic ease-in-out). Floor clicks on the env
        // glb (attached in [placeObjects] once the env entity exists) call into
        // [TeleportTweenSystem.startTween]; the system then interpolates view origin every
        // frame in execute(). It also calls back into [smoothLocomotion.syncFromScene] each
        // tween frame so the locomotion system's cached origin stays aligned and the player
        // can resume stick walking immediately after the animation ends.
        teleportTween = TeleportTweenSystem(scene, smoothLocomotion!!).also {
            systemManager.registerSystem(it)
        }

        // Spawn the user a couple meters in front of the gallery centroid, looking forward.
        scene.setViewOrigin(0.0f, 0.0f, 0.0f, 0.0f)
        // Keep the locomotion system's cached origin in lockstep with the SDK after every
        // external setViewOrigin so the next stick frame increments from the correct anchor.
        smoothLocomotion?.syncFromScene(0.0f, 0.0f, 0.0f, 0.0f)

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
        // on every click so the player always sees it dead-ahead. Quaternion(0,180,0) rotates
        // 180° around Y so the panel's front face points back toward the player (Spatial SDK
        // panels' default normal is +Z, which faces AWAY from a player standing at origin).
        val initialPose = Pose(
            Vector3(0f, INFO_PANEL_EYE_HEIGHT_M, -INFO_PANEL_DISTANCE_M),
            Quaternion(0f, 180f, 0f),
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
        val pose = Pose(Vector3(0.5f, 1.4f, -1.3f), Quaternion(0f, 180f, 0f))
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
        val pose = Pose(Vector3(0f, 1.6f, -2f), Quaternion(0f, 180f, 0f))
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

        // Hotspots = every non-env object with a visible asset type (image/video/non-env model).
        // The centroid of these positions is "where the paintings are" in XZ and is what the
        // player should face on spawn. See [recenterOnEnvironment].
        val hotspots = objects.filter { obj ->
            obj !== env && when (obj.expand?.asset?.type) {
                "image", "video" -> true
                "model" -> true
                else -> false
            }
        }
        val hotspotCentroid = if (hotspots.isEmpty()) null else {
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (h in hotspots) {
                sx += h.position[0]
                sy += h.position[1]
                sz += h.position[2]
            }
            Vector3(sx / hotspots.size, sy / hotspots.size, sz / hotspots.size)
        }

        if (env != null) {
            recenterOnEnvironment(env, hotspots, hotspotCentroid)
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

        // Attach the click-to-animated-move listener to the env glb. Must run AFTER spawnGlb has
        // created the env entity; we locate it by Query on SupportsLocomotion (only the env has
        // it) rather than threading the entity reference through spawnGlb's signature, which
        // keeps spawnGlb's body unchanged. See [attachFloorClickToEnv].
        attachFloorClickToEnv()
    }

    /**
     * Spawn the player at the center of the gallery's hotspot cluster, on the floor, facing the
     * densest cluster of paintings. Mirrors the web viewer's `spawnAtEnvironmentCenter` but
     * works without reading the streamed glb's bounding box (SDK 0.12's toolkit [Mesh] doesn't
     * surface per-mesh extents synchronously, and a future-based AABB read from the runtime
     * [SceneObject] isn't reliably exposed in this version — the public API only guarantees
     * mesh.materials, which is what we already use elsewhere).
     *
     * Strategy (mirrors viewerScene.ts:1375-1383 spawnAtEnvironmentCenter):
     *  - center XZ: midpoint of the **hotspot** AABB on the XZ plane. Paintings hang on the
     *    room's walls, so the XZ midpoint of their positions is reliably *inside* the room
     *    near the floor center rather than at the glb's authoring origin (which is often a
     *    corner of the bbox or far below the actual floor for sketchfab models).
     *  - floor Y: env.position.y is the gallery's authored ground plane; we use that unless the
     *    paintings hang surprisingly low, in which case we fall back to `minHotspotY - 1.6`
     *    (head height) so the player still stands below them. With [ReferenceSpace.LOCAL_FLOOR]
     *    set in [onSceneReady] the runtime offsets the live head pose for us, so we pass the
     *    bare floor Y here, not floor + eye height.
     *  - yaw: atan2(dx, dz) toward [hotspotCentroid] (in radians, converted to degrees for the
     *    SDK overload). 0° looks down +Z; positive yaw rotates around +Y (right-handed Y-up).
     *    This is the same yaw convention used by the toolkit's [Quaternion(0, yaw, 0)] elsewhere
     *    in this file (e.g. info-panel/welcome-panel facing the player).
     *
     * If [hotspots] is empty we fall back to env's authored position (the legacy behaviour) so
     * empty-room exhibitions don't regress. After writing the new origin we mirror it into
     * [smoothLocomotion]'s cached anchor so the next stick frame increments from the right
     * place — without this the player would snap back to the previous (currentX, …, yawDeg)
     * the first time they touched the thumbstick.
     */
    private fun recenterOnEnvironment(
        env: SceneObjectRecord,
        hotspots: List<SceneObjectRecord>,
        hotspotCentroid: Vector3?,
    ) {
        runCatching {
            if (hotspots.isEmpty() || hotspotCentroid == null) {
                val ex = env.position[0]
                val ey = env.position[1]
                val ez = env.position[2]
                Log.i(TAG, "→ no hotspots; centering player on env '${env.title}' at ($ex, $ey, $ez)")
                scene.setViewOrigin(ex, ey, ez, 0f)
                smoothLocomotion?.syncFromScene(ex, ey, ez, 0f)
                return@runCatching
            }

            // Hotspot AABB → midpoint = room center on XZ (paintings line the walls).
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (h in hotspots) {
                minX = min(minX, h.position[0]); maxX = max(maxX, h.position[0])
                minY = min(minY, h.position[1])
                minZ = min(minZ, h.position[2]); maxZ = max(maxZ, h.position[2])
            }
            val cx = (minX + maxX) * 0.5f
            val cz = (minZ + maxZ) * 0.5f

            // Floor Y: env's authored Y is the reference floor. Use min(envY, minHotspotY-1.6)
            // as a safety net for galleries where paintings hang well above an unusually-high
            // env origin — 1.6 m is roughly head height so paintings at eye level (1.5 m) still
            // leave the player on the floor below them.
            val envY = env.position[1]
            val floorY = min(envY, minY - 1.6f)

            // Yaw to face hotspot centroid from (cx, cz). atan2(dx, dz) returns radians where
            // 0 = look toward +Z; positive rotates toward +X (standard right-handed Y-up yaw).
            val dx = hotspotCentroid.x - cx
            val dz = hotspotCentroid.z - cz
            val yawRad = atan2(dx, dz)
            val yawDeg = (yawRad * 180.0 / PI).toFloat()

            Log.i(
                TAG,
                "→ recenter on env '${env.title}': hotspot bbox xz=($minX..$maxX, $minZ..$maxZ) " +
                    "→ origin=($cx, $floorY, $cz) yaw=${yawDeg}° " +
                    "facing centroid=(${hotspotCentroid.x}, ${hotspotCentroid.y}, ${hotspotCentroid.z}) " +
                    "(${hotspots.size} hotspots)",
            )
            scene.setViewOrigin(cx, floorY, cz, yawDeg)
            smoothLocomotion?.syncFromScene(cx, floorY, cz, yawDeg)
        }.onFailure { Log.e(TAG, "✗ recenterOnEnvironment failed; falling back to origin", it) }
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
     * Find the env glb entity (the only one carrying [SupportsLocomotion]) and attach an
     * [InputListener] that animates the player to the floor-hit point. Mirrors the Babylon web
     * viewer's `processPickedHit` → `teleportTo` flow (viewerScene.ts:983-1130).
     *
     * Routing rules:
     *  - The SDK's ray-pick chooses the nearest hittable mesh. So clicks on paintings/hotspots
     *    fire those entities' [attachClickListener] (showModalFor / grab) and never reach the
     *    env. Only ray-misses against hotspots — i.e. clicks on the gallery floor/walls — land
     *    here, which matches what we want.
     *  - We filter by hit-normal: walls and ceiling have horizontal-or-downward normals, so we
     *    only tween if `hitInfo.normal.y > 0.5` (mostly upward → floor). This prevents the
     *    "click a wall and faceplant" failure mode the web viewer guards against via
     *    `isWalkableHit`.
     *  - Tween is no-op-while-active (see [TeleportTweenSystem.startTween]) so rapid clicks
     *    don't stack.
     *
     * Idempotent: if called multiple times (e.g. user picks a new exhibition), the most recent
     * env's SceneObject just receives an additional listener. Each [TeleportTweenSystem.startTween]
     * call early-outs while a tween is running, so duplicate listeners don't cause double-jumps.
     */
    private fun attachFloorClickToEnv() {
        val sos = systemManager.findSystem<SceneObjectSystem>()
        val envEntity = Query.where { has(SupportsLocomotion.id, Mesh.id) }.eval().firstOrNull()
        if (envEntity == null) {
            Log.w(TAG, "floor-click: no env entity (SupportsLocomotion+Mesh) found in scene")
            return
        }
        sos.getSceneObject(envEntity)?.thenAccept { so ->
            if (so == null) {
                Log.w(TAG, "floor-click: env SceneObject is null")
                return@thenAccept
            }
            so.addInputListener(
                object : InputListener {
                    override fun onClick(
                        receiver: SceneObject,
                        hitInfo: HitInfo,
                        sourceOfInput: Entity,
                    ) {
                        // Reject clicks on walls/ceiling — only tween on floor-like surfaces.
                        // 0.5 ≈ 60° from horizontal, generous enough to cover slight floor
                        // tilts in artist-authored glbs while still rejecting vertical walls.
                        val normalY = hitInfo.normal.y
                        if (normalY <= 0.5f) {
                            Log.i(TAG, "🖱️  env click rejected (normal.y=$normalY, treating as wall/ceiling)")
                            return
                        }
                        val p = hitInfo.point
                        Log.i(TAG, "🖱️  floor click → tween to (${p.x}, ${p.y}, ${p.z})")
                        teleportTween?.startTween(p)
                    }
                },
            )
            Log.i(TAG, "✓ floor-click listener attached to env entity")
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

        // Reposition the panel in front of the player at eye level, facing them.
        val pose = Pose(
            Vector3(0f, INFO_PANEL_EYE_HEIGHT_M, -INFO_PANEL_DISTANCE_M),
            Quaternion(0f, 180f, 0f),
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
     * Build a multi-layer gold "picture frame" around a flat plane of size [planeW] × [planeH].
     * Mirrors the web viewer's buildFrame in viewerScene.ts:
     *   1. dark "backing" panel behind the painting (planeW × planeH × 0.01, recessed),
     *   2. four thick outer frame bars (T=0.06 thickness, D=0.08 depth) sitting forward,
     *   3. four thin inner trim bars (T=0.015 thickness) recessed slightly behind the outer.
     *
     * Each piece is a `mesh://box` entity with a `Box(min, max)` component giving it its
     * dimensions; local offsets are rotated into world space by `parentPose.q` (Spatial SDK's
     * `Quaternion * Vector3` overload — see PremiumMediaSample TouchScalableSystem) so frame
     * pieces sit flush against rotated paintings.
     *
     * Gold colour is a bright warm tone (0.95, 0.78, 0.42) tuned to match admin's PBR look
     * even though we're stuck with `unlit = true` (no per-pixel lighting available on toolkit
     * Material for the box mesh in SDK 0.12 — unlit avoids depending on scene lighting which
     * would otherwise make the frame appear muddy under low ambient).
     */
    private fun spawnGoldFrame(parentPose: Pose, parentScale: Vector3, planeW: Float, planeH: Float) {
        // Outer frame profile — chunkier than the previous slim version so it reads as a real
        // museum picture frame, matching admin's T=0.06 / D=0.08 dimensions.
        val tOuter = 0.06f      // outer bar thickness (cross-section perpendicular to plane edge)
        val dOuter = 0.08f      // outer bar depth (out of the wall)
        val frontZ = 0.03f      // push outer frame slightly forward to avoid z-fighting

        // Inner gold trim — a thin recessed band around the painting just inside the outer frame.
        val tInner = 0.015f
        val dInner = 0.025f
        // Place inner trim recessed behind the front face of the outer frame so the outer
        // visually steps over the inner (admin's `innerZ = FRONT_Z - D/2 + innerDepth/2 + 0.001`).
        val innerZ = frontZ - dOuter / 2f + dInner / 2f + 0.001f

        // Dark backing panel sits behind the painting (admin uses inner PBR material; for the
        // toolkit Material we draw a near-black unlit box at z=-0.012). Slightly oversized so
        // the painting doesn't show transparent bleed at the border.
        val backingDepth = 0.015f
        val backingZ = -0.012f

        // Total width/height across the outer frame is just used to size the top/bottom bars
        // (which span the painting plus the corner caps of the left/right bars).
        val totalW = planeW + 2f * tOuter
        // Inner trim total width — same idea, for the recessed inner bars.
        val innerW = planeW + 2f * tInner

        // Build one frame piece at [localOffset] (frame-local: +X right, +Y up, +Z out of the
        // wall). Box half-extents are centered on the entity's own origin; we then translate
        // to parentPose.t + parentPose.q.rotate(localOffset) so sides sit correctly even when
        // the parent painting is rotated. parentScale is reapplied so the frame scales with
        // any per-object scale stored in PocketBase.
        fun makeBox(localOffset: Vector3, sizeX: Float, sizeY: Float, sizeZ: Float, color: Color4) {
            val hx = sizeX * 0.5f
            val hy = sizeY * 0.5f
            val hz = sizeZ * 0.5f
            val worldOffset = parentPose.q * localOffset
            val sidePose = Pose(parentPose.t + worldOffset, parentPose.q)
            Entity.create(
                Mesh(Uri.parse("mesh://box")),
                Box(Vector3(-hx, -hy, -hz), Vector3(hx, hy, hz)),
                Material().apply {
                    baseColor = color
                    unlit = true
                },
                Transform(sidePose),
                Scale(parentScale),
            )
        }

        // Warm bright gold — admin's PBR (0.42, 0.32, 0.16) reads as too dull when sampled
        // through unlit toolkit material; this tone is closer to what the admin actually paints
        // on screen after PBR lighting brightens the gold.
        val gold = Color4(0.95f, 0.78f, 0.42f, 1f)
        // Near-black backing — admin uses a dark PBR (0.06, 0.05, 0.06); same here via unlit.
        val backing = Color4(0.06f, 0.05f, 0.06f, 1f)

        // (1) Backing panel — slightly larger than the painting on X/Y, thin slab on Z.
        makeBox(Vector3(0f, 0f, backingZ), planeW + 0.005f, planeH + 0.005f, backingDepth, backing)

        // (2) Outer frame — 4 thick gold bars around the painting.
        makeBox(Vector3(0f, planeH / 2f + tOuter / 2f, frontZ), totalW, tOuter, dOuter, gold) // top
        makeBox(Vector3(0f, -planeH / 2f - tOuter / 2f, frontZ), totalW, tOuter, dOuter, gold) // bottom
        makeBox(Vector3(-planeW / 2f - tOuter / 2f, 0f, frontZ), tOuter, planeH, dOuter, gold) // left
        makeBox(Vector3(planeW / 2f + tOuter / 2f, 0f, frontZ), tOuter, planeH, dOuter, gold) // right

        // (3) Inner trim — 4 thin gold bars sitting just inside the outer frame and recessed.
        makeBox(Vector3(0f, planeH / 2f + tInner / 2f, innerZ), innerW, tInner, dInner, gold) // top
        makeBox(Vector3(0f, -planeH / 2f - tInner / 2f, innerZ), innerW, tInner, dInner, gold) // bottom
        makeBox(Vector3(-planeW / 2f - tInner / 2f, 0f, innerZ), tInner, planeH, dInner, gold) // left
        makeBox(Vector3(planeW / 2f + tInner / 2f, 0f, innerZ), tInner, planeH, dInner, gold) // right
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

        // Spawn a small Compose mute-icon panel pinned to the audio source's world pose so the
        // user can SEE where each sound is coming from and silence only that emitter. The icon
        // uses a runtime-registered panel (one per audio source) wired to per-source mute state.
        spawnAudioIconPanel(pose, player, title)

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
     * Spawn a small per-source mute icon at [pose] (the audio emitter's world position).
     *
     * Each audio asset needs its own panel because the panel content (the speaker glyph) is
     * driven by per-source state — clicking the icon toggles ONLY [player]'s volume, not the
     * global audio bus (that's what [MuteButtonPanel] is for).
     *
     * Each call registers a fresh runtime [ComposeViewPanelRegistration] with a unique id
     * (mirroring [nextVideoPanelId]'s dynamic-id pattern). The panel's Compose content reads
     * from [audioIconMuteStates] keyed by `audioKey`, and clicks fire through
     * [audioIconOnClick] which flips the per-source mute flag and applies it to the player.
     */
    private fun spawnAudioIconPanel(pose: Pose, player: ExoPlayer, title: String) {
        val panelId = nextAudioIconPanelId++
        val audioKey = "audio_$panelId"

        // Per-source observable state + click handler. The Compose tree reads these via the
        // top-level lookup maps in [AudioIconPanel].
        val muteState = mutableStateOf(false).also { audioIconMuteStates[audioKey] = it }
        audioIconOnClick[audioKey] = {
            val nowMuted = !muteState.value
            muteState.value = nowMuted
            runCatching { player.volume = if (nowMuted) 0f else 1f }
            Log.i(TAG, "🔇 per-source mute '$title' → muted=$nowMuted")
        }

        registerPanel(
            ComposeViewPanelRegistration(
                panelId,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply { setContent { AudioIconPanel(audioKey) } }
                },
                settingsCreator = {
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = AUDIO_ICON_SIZE_M, height = AUDIO_ICON_SIZE_M),
                        // Transparent platform theme so only the disc/glyph drawn by Compose
                        // is visible — without it the panel Activity window would render an
                        // opaque white square around the icon.
                        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                        display = DpPerMeterDisplayOptions(),
                    )
                },
            )
        )

        // Place the icon at the audio source's translation, rotated 180° around Y so the
        // panel's front face (its default +Z normal) points back toward a player roughly at
        // world origin. We deliberately ignore the audio source's own rotation here — audio
        // emitters are points (not oriented surfaces), so what matters is that the icon faces
        // the room rather than the wall behind it. Matches how [spawnInfoPanelEntity] orients
        // the info modal.
        val iconPose = Pose(pose.t, Quaternion(0f, 180f, 0f))
        Entity.create(
            Panel(panelId),
            PanelDimensions(Vector2(AUDIO_ICON_SIZE_M, AUDIO_ICON_SIZE_M)),
            Transform(iconPose),
            Visible(true),
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
        // when released. Following the canonical pattern in Object3DSampleIsdk/PanelLayout.kt
        // (lines 198–229) we pass Physics dimensions via the constructor — `Physics(...)`'s
        // dimensions argument is in WORLD half-extents and the physics layer also multiplies
        // by the entity's Scale. PIVOT_Y keeps the model upright while grabbed.
        //
        // We can't read the streamed glb's bbox here (the mesh loads async and SDK 0.12
        // doesn't surface a per-mesh extent on the toolkit Mesh), so we use a fixed 0.4 m
        // world half-extent (0.8 m cube). That's big enough to ray-hit easily even for tiny
        // sculptures, small enough to feel like an object rather than a wall. Density 0.5
        // keeps the mass perceptible-but-throwable. Restitution 0.3 = light bounce on landing.
        //
        // The visual Mesh is given `hittable = MeshCollision.LineTest` so the controller's
        // selection ray actually registers Grab pointer events on the model — without this
        // hint, streamed glb meshes can default to NoCollision until the mesh finishes
        // loading, which would make the entity un-grabbable even though the component is set.
        val hotspotDim = Vector3(0.4f, 0.4f, 0.4f)
        val hotspotEntity = Entity.create(
            Mesh(Uri.parse(url), hittable = MeshCollision.LineTest),
            Transform(pose),
            Scale(scaleVec),
            Grabbable(true, GrabbableType.PIVOT_Y),
            Physics(
                shape = "box",
                state = PhysicsState.DYNAMIC,
                dimensions = hotspotDim,
                density = 0.5f,
                restitution = 0.3f,
            ),
        )
        // Visual locator dot so the user can find the hotspot in the room — small floating
        // gold beacon 0.6 m above the anchor pose (see spawnHotspotMarker).
        spawnHotspotMarker(pose, scaleVec)
        Log.i(
            TAG,
            "🎯 hotspot glb '$title' id=${hotspotEntity.id} " +
                "pos=(${pose.t.x}, ${pose.t.y}, ${pose.t.z}) " +
                "scale=(${scaleVec.x}, ${scaleVec.y}, ${scaleVec.z}) " +
                "physicsDim=$hotspotDim",
        )
    }

    /**
     * Spawn a small floating gold-coloured beacon ~0.6 m above [parentPose] so the user can
     * find grabbable hotspot models across the gallery. Uses an unlit gold box (same warm
     * tone as the picture frames) — `unlit = true` means it reads as a self-luminous marker
     * even in dim ambient. Sized 0.06 m (half-extent 0.03 m) so it's visible across the room
     * without dominating the scene.
     *
     * Intentionally NOT grabbable and NOT physics-enabled — this is a pure locator. We also
     * don't parent it to the hotspot entity: if the user throws the hotspot, the marker stays
     * at the original spawn anchor as a "this is where the object came from" indicator.
     * `MeshCollision.NoCollision` ensures the ray-cast passes through the marker so the
     * hotspot underneath remains grabbable.
     */
    private fun spawnHotspotMarker(parentPose: Pose, parentScale: Vector3) {
        val h = 0.03f
        // Local offset 0.6 m up; rotate by parentPose.q so a rotated parent still gets a
        // marker directly above its top (same idiom as makeBox in spawnGoldFrame).
        val worldOffset = parentPose.q * Vector3(0f, 0.6f, 0f)
        val markerPose = Pose(parentPose.t + worldOffset, parentPose.q)
        Entity.create(
            Mesh(Uri.parse("mesh://box"), hittable = MeshCollision.NoCollision),
            Box(Vector3(-h, -h, -h), Vector3(h, h, h)),
            Material().apply {
                // Warm gold matching the picture frames; unlit so it glows in low ambient.
                baseColor = Color4(1.0f, 0.86f, 0.45f, 1f)
                unlit = true
            },
            Transform(markerPose),
            Scale(parentScale),
        )
    }
}
