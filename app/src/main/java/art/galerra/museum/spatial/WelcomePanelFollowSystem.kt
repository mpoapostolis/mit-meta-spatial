package art.galerra.museum.spatial

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import kotlin.math.PI

/**
 * Per-frame head-follow for the welcome / scene-picker panel.
 *
 * Uses a plain Android Handler/Looper polling loop at ~20 Hz — registerSystem-based ticking
 * wasn't firing for this class on the device, so we bypass it.
 *
 * Three-tier fallback for placing the panel so it's ALWAYS in front of the player:
 *  1. Query for an entity with AvatarAttachment(typeData="head") — canonical SDK pattern.
 *  2. [Scene.getViewerPose] — OpenXR pose if head entity hasn't materialised yet.
 *  3. The activity-supplied view origin lambda — used when (1)+(2) both return identity
 *     (Quest hasn't established tracking yet, or LOCAL_FLOOR hasn't synced). Without this
 *     fallback we'd place the panel at world (0, 1.6, -1.5) even if the player has been
 *     teleported elsewhere — which is exactly why the panel ended up "behind" them before.
 */
class WelcomePanelFollowSystem(
    private val scene: Scene,
    private val getPanelEntity: () -> Entity?,
    private val getViewOrigin: () -> ViewOriginSnapshot = { ViewOriginSnapshot(0f, 0f, 0f, 0f) },
) {
    /** Cached view origin from the activity (set by every [scene.setViewOrigin] mirror). */
    data class ViewOriginSnapshot(val x: Float, val y: Float, val z: Float, val yawDeg: Float)

    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 50L)
        }
    }

    fun start() {
        Log.i("MuseumSpatial", "⚡ welcome follow started")
        handler.post(tickRunnable)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun tick() {
        ticks++
        if (!welcomeState.value.visible) return
        val panel = getPanelEntity() ?: run {
            if (ticks <= 5) Log.i("MuseumSpatial", "🔍 tick #$ticks no panel yet")
            return
        }

        // Tier 1: AvatarAttachment(typeData="head") — canonical Spatial SDK 0.12 pattern. Same
        // query used by SpatialAudioSystem and our SmoothLocomotionSystem (both ship with the
        // SDK / are battle-tested on this device).
        val headEntity = Query.where { has(AvatarAttachment.id) }
            .filter { by(AvatarAttachment.typeData).isEqualTo("head") }
            .eval()
            .firstOrNull()
        val headPose = headEntity?.tryGetComponent<Transform>()?.transform
            ?: scene.getViewerPose()

        // Detect "effectively identity" head pose: tracking lost / playspace not yet established
        // shows up as Pose(Vector3(0,0,0), Quaternion(0,0,0,±1)). The simple `headPose == Pose()`
        // check misses the w=-1 case (semantically identical rotation, different object equality)
        // AND misses the case where the SDK reports head at y≈0 because the LOCAL_FLOOR origin
        // hasn't synced yet — both produce a panel at floor level the player can't see. Threshold
        // 0.5 m is conservative: even a kneeling player sits well above that.
        val headIsIdentity = headPose.t.y < 0.5f
        val (anchorPos, fwdX, fwdZ) = if (headIsIdentity) {
            val origin = getViewOrigin()
            val yawRad = origin.yawDeg * (PI / 180.0).toFloat()
            // yaw=0 means facing -Z (Quest default). sin(yaw) → x, -cos(yaw) → z.
            val fx = kotlin.math.sin(yawRad)
            val fz = -kotlin.math.cos(yawRad)
            if (ticks <= 5) {
                Log.i(
                    "MuseumSpatial",
                    "🔍 tick #$ticks pose identity → fallback origin=($origin) fwd=($fx,$fz)",
                )
            }
            // Use eye-height-ish anchor at view origin so the panel sits at face level.
            Triple(Vector3(origin.x, origin.y + 1.55f, origin.z), fx, fz)
        } else {
            // Use the SDK's canonical Pose.forward() — matches what SmoothLocomotionSystem.kt
            // uses (line 204). Returns world-space forward (-Z in head's local frame).
            val fwd = headPose.forward()
            val flatLenSq = fwd.x * fwd.x + fwd.z * fwd.z
            if (flatLenSq < 1e-6f) return
            val flatLen = kotlin.math.sqrt(flatLenSq)
            Triple(headPose.t, fwd.x / flatLen, fwd.z / flatLen)
        }

        // Panel centered ~1.2 m ahead of the player at their eye level. Closer than 1.5 m makes
        // the panel feel like a HUD; further makes the text harder to read on Quest 2 LCDs.
        val placeDistance = 1.2f
        val panelPos = Vector3(
            anchorPos.x + fwdX * placeDistance,
            anchorPos.y,
            anchorPos.z + fwdZ * placeDistance,
        )
        // Compose-backed panels: default surface normal is -Z (panel front faces -Z in local
        // frame). The mute-button used Quaternion(0,180,0) to flip its -Z to point +Z toward
        // the player at origin — same trick here. We want panel's -Z (front) to point AT the
        // player, which lives in the OPPOSITE direction from where the head is looking.
        //
        // Empirically: passing `toPlayer` to lookRotationAroundY rotates the panel so its BACK
        // faces the player (Compose content doesn't render from behind → black/invisible, which
        // matched the user's "dn vlepw tipota" report). Passing `fwd` (the head's forward
        // direction) makes the panel's -Z align with -fwd = direction back toward the head, so
        // the Compose face is what the player sees.
        val rot = Quaternion.lookRotationAroundY(Vector3(fwdX, 0f, fwdZ))

        panel.setComponent(Transform(Pose(panelPos, rot)))
        panel.setComponent(Visible(true))

        // Continuous logging (not just first 5 ticks) so we can verify the panel keeps tracking
        // through Quest recenter events. Throttled to every 20th tick (~1 s) to avoid spam.
        if (ticks <= 5 || ticks % 20 == 0) {
            Log.i(
                "MuseumSpatial",
                "🎯 tick #$ticks panel=(${"%.2f".format(panelPos.x)}, ${"%.2f".format(panelPos.y)}, ${"%.2f".format(panelPos.z)}) head=(${"%.2f".format(anchorPos.x)}, ${"%.2f".format(anchorPos.y)}, ${"%.2f".format(anchorPos.z)}) fwd=($fwdX, $fwdZ)",
            )
        }
    }
}
