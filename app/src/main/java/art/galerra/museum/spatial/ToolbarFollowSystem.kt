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

/**
 * Pins a set of floating toolbar buttons (mute, back-to-scenes, …) to a constant offset from
 * the player's head pose so they're always reachable, regardless of where the player walks or
 * looks (within the same yaw — the toolbar follows yaw but not pitch/roll, so it acts as a
 * gentle HUD that doesn't pitch up/down as the player tilts their head).
 *
 * Why a separate system from [WelcomePanelFollowSystem]:
 *  - Supports MULTIPLE buttons with their own head-local XY offsets
 *  - Never stops following (toolbar is always present); welcome stops after scene pick
 *  - Yaw-only follow (HUD style); welcome uses full head forward
 *
 * Each entry is a (entity-supplier, local-offset) pair. local-offset is in **head-local** space
 * with +X right, +Y up, -Z forward (OpenXR/Spatial SDK convention). Buttons are placed at:
 *   button.pos = head.pos + yawQuat * offset
 *   button.rot = lookRotationAroundY(headForwardXZ)   ← so content side (-Z local) faces head
 */
class ToolbarFollowSystem(
    private val scene: Scene,
    private val buttons: List<Entry>,
) {
    data class Entry(val getEntity: () -> Entity?, val offset: Vector3)

    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 50L)
        }
    }

    fun start() {
        Log.i("MuseumSpatial", "⚡ toolbar follow started (${buttons.size} buttons)")
        handler.post(tickRunnable)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun tick() {
        ticks++
        val head = Query.where { has(AvatarAttachment.id) }
            .filter { by(AvatarAttachment.typeData).isEqualTo("head") }
            .eval()
            .firstOrNull()
        val rawHeadPose = head?.tryGetComponent<Transform>()?.transform
            ?: scene.getViewerPose()
        // Identity-pose detection: y < 0.5 m means tracking not yet warmed up.
        val headPose = if (rawHeadPose.t.y < 0.5f) {
            Pose(Vector3(0f, 1.55f, 0f), Quaternion())
        } else {
            rawHeadPose
        }

        // Extract head's forward and flatten to XZ (drop pitch/roll for HUD stability).
        val rawFwd = headPose.forward()
        val flatLenSq = rawFwd.x * rawFwd.x + rawFwd.z * rawFwd.z
        if (flatLenSq < 1e-6f) return  // looking straight up/down — skip this tick
        val flatLen = kotlin.math.sqrt(flatLenSq)
        val fx = rawFwd.x / flatLen
        val fz = rawFwd.z / flatLen
        // Compute yaw angle (radians) from forward; we'll use it to rotate the head-local
        // offset into world. atan2(x, -z) matches SmoothLocomotionSystem's convention.
        val yawRad = kotlin.math.atan2(fx, -fz)
        // Yaw-only quaternion built via the SDK's 3-arg Euler-degrees constructor.
        val yawDeg = yawRad * 180.0f / kotlin.math.PI.toFloat()
        val yawQuat = Quaternion(0f, yawDeg, 0f)

        for ((getEntity, offset) in buttons) {
            val entity = getEntity() ?: continue
            // Rotate the head-local offset by yaw only (drops pitch/roll) → world offset.
            val worldOffset = yawQuat * offset
            val buttonPos = Vector3(
                headPose.t.x + worldOffset.x,
                headPose.t.y + worldOffset.y,
                headPose.t.z + worldOffset.z,
            )
            // Orient so the content face (-Z local of a Compose panel) points back toward
            // the head: pass the head's flat-forward to lookRotationAroundY which aligns the
            // panel's +Z with the input vector → -Z (content) ends up pointing -forward,
            // i.e. back toward the head. (Same trick used by WelcomePanelFollowSystem.)
            val buttonRot = Quaternion.lookRotationAroundY(Vector3(fx, 0f, fz))
            entity.setComponent(Transform(Pose(buttonPos, buttonRot)))
        }

        if (ticks <= 3) {
            Log.i(
                "MuseumSpatial",
                "🧰 toolbar tick #$ticks head=(${"%.2f".format(headPose.t.x)}, ${"%.2f".format(headPose.t.y)}, ${"%.2f".format(headPose.t.z)}) yaw=${"%.1f".format(yawDeg)}°",
            )
        }
    }
}
