package art.galerra.museum.spatial

import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Transform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom smooth locomotion replacing the SDK's default teleport-on-thumbstick-release.
 *
 * The SDK exposes thumbstick state via discrete directional bits on the [Controller] component
 * ([ButtonBits.ButtonThumbLU/LD/LL/LR] for the left stick, [ButtonBits.ButtonThumbRU/RD/RL/RR]
 * for the right). There is no analog axis API in 0.12 — the bits fire whenever the stick crosses
 * the hardware deadzone in the corresponding direction. We treat them as a virtual D-pad and
 * advance/rotate the view origin every frame they're held, yielding continuous (smooth) motion
 * rather than discrete teleports.
 *
 * Movement model:
 *   - Left stick → walking, head-facing relative. Forward = the player's facing direction
 *     (projected onto the floor plane). Sideways = perpendicular. Y is preserved so the player
 *     can't "walk up" by holding both forward + an off-axis direction.
 *   - Right stick → snap-turn (left/right only). Continuous yaw spin causes nausea for most
 *     riders in seated VR, so we apply a discrete 30° step on the first frame the bit fires and
 *     ignore it again until released. Right-stick up/down is unused.
 *
 * The system keeps its own [currentX/Y/Z/Yaw] state because the SDK doesn't expose a typed
 * getter for the view origin we can rely on across builds; [syncFromScene] is the hook the
 * activity calls after any external [Scene.setViewOrigin] (e.g. on scene swap) to keep us
 * aligned.
 *
 * The default [com.meta.spatial.vr.LocomotionSystem] should be disabled before this system runs
 * (via `systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)`) — otherwise it
 * will fight us by writing the view origin every time the thumbstick is released.
 */
class SmoothLocomotionSystem(private val scene: Scene) : SystemBase() {

    // Tracked view-origin state. Mirrors the SDK's internal `lastViewOriginX/Y/Z/DegRotation`.
    // Initialised to the values [ImmersiveActivity.onSceneReady] writes (0,0,0,0); kept in
    // sync via [syncFromScene] when the activity re-centers on scene load.
    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var currentZ: Float = 0f
    private var currentYawDeg: Float = 0f

    private var lastTimeMs: Long = System.currentTimeMillis()

    // Right-stick snap-turn debounce: the bit stays set as long as the stick is held past its
    // deadzone, but we want one 30° step per press. Track the last frame's bit state and only
    // fire on the rising edge (false → true).
    private var rightSnapLeftLatched = false
    private var rightSnapRightLatched = false

    /**
     * Called by [ImmersiveActivity] after any external [Scene.setViewOrigin] so our cached
     * origin stays in lockstep with the SDK's. Without this, when the activity re-centers the
     * player on the gallery centroid, the next frame of locomotion would jump the player back
     * to (0,0,0) + accumulated delta because we'd be incrementing from stale state.
     */
    fun syncFromScene(x: Float, y: Float, z: Float, yawDeg: Float) {
        currentX = x
        currentY = y
        currentZ = z
        currentYawDeg = yawDeg
    }

    /**
     * Read-only accessor for the system's currently-tracked yaw (degrees). Used by the
     * click-to-teleport tween in [ImmersiveActivity] so the animated move preserves whatever
     * heading the player has snap-turned to (via the right stick) instead of resetting to 0.
     */
    fun getCurrentYawDeg(): Float = currentYawDeg

    override fun execute() {
        val nowMs = System.currentTimeMillis()
        val deltaSec = ((nowMs - lastTimeMs).coerceAtLeast(0L) * 0.001f).coerceAtMost(0.1f)
        lastTimeMs = nowMs

        val leftController = findLocalController(isRight = false) ?: return
        // The right controller is optional (player may have only one stick paired); guard it.
        val rightController = findLocalController(isRight = true)

        var moved = false
        var yawChanged = false

        // --- Left stick: smooth walking, head-facing relative. ---
        if (leftController.isActive) {
            // Build a unit-length input vector in player-local space:
            //   +X = strafe right, -X = strafe left
            //   +Z = walk backward, -Z = walk forward (matches OpenGL-style forward = -Z)
            var localX = 0f
            var localZ = 0f
            if (leftController.isDown(ButtonBits.ButtonThumbLU)) localZ -= 1f
            if (leftController.isDown(ButtonBits.ButtonThumbLD)) localZ += 1f
            if (leftController.isDown(ButtonBits.ButtonThumbLL)) localX -= 1f
            if (leftController.isDown(ButtonBits.ButtonThumbLR)) localX += 1f

            if (localX != 0f || localZ != 0f) {
                // Resolve "facing direction" from the head pose (the player's actual gaze yaw).
                // The head entity's Transform.q is already in WORLD space — it bakes in the
                // view-origin yaw PLUS the headset's tracked rotation — so we use it directly
                // and do NOT add currentYawDeg again, which would double-count and send walking
                // off-axis whenever the snap-turn yaw is non-zero.
                val facingYawRad = getHeadYawRad()
                val cosY = cos(facingYawRad)
                val sinY = sin(facingYawRad)

                // Rotate (localX, localZ) by the facing yaw to produce a world-space delta.
                // Standard 2D yaw rotation: worldX = localX*cos - localZ*sin,
                //                          worldZ = localX*sin + localZ*cos.
                // Normalize by Euclidean length so diagonal (e.g. forward + strafe) doesn't move
                // the player faster than cardinal — keep walking speed constant in any direction.
                val len = sqrt(localX * localX + localZ * localZ)
                val nx = localX / len
                val nz = localZ / len

                val worldDX = nx * cosY - nz * sinY
                val worldDZ = nx * sinY + nz * cosY

                val step = WALK_SPEED_M_PER_S * deltaSec
                currentX += worldDX * step
                currentZ += worldDZ * step
                moved = true
            }
        }

        // --- Right stick: snap-turn left/right (rising-edge only to prevent continuous spin). ---
        if (rightController != null && rightController.isActive) {
            val leftDown = rightController.isDown(ButtonBits.ButtonThumbRL)
            val rightDown = rightController.isDown(ButtonBits.ButtonThumbRR)

            if (leftDown && !rightSnapLeftLatched) {
                currentYawDeg = wrapYawDeg(currentYawDeg + SNAP_TURN_DEG)
                yawChanged = true
            }
            if (rightDown && !rightSnapRightLatched) {
                currentYawDeg = wrapYawDeg(currentYawDeg - SNAP_TURN_DEG)
                yawChanged = true
            }
            rightSnapLeftLatched = leftDown
            rightSnapRightLatched = rightDown
        }

        if (moved || yawChanged) {
            scene.setViewOrigin(currentX, currentY, currentZ, currentYawDeg)
        }
    }

    /**
     * Look up the player's left or right [Controller] via the [AvatarBody] component. Returns
     * null if no local avatar exists yet (early frames before the SDK has spawned the rig) or
     * if the controller entity exists but lacks a Controller component (hand-only mode).
     */
    private fun findLocalController(isRight: Boolean): Controller? {
        val avatarEntity =
            Query.where { has(AvatarBody.id) }
                .eval()
                .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                ?: return null
        val body = avatarEntity.getComponent<AvatarBody>()
        val hand = if (isRight) body.rightHand else body.leftHand
        return hand.tryGetComponent<Controller>()
    }

    /**
     * Extract the head's yaw (rotation around world Y) in radians. The head's Transform.q is a
     * quaternion; we project the forward direction onto the XZ plane and atan2 it. Returning 0
     * if the head entity isn't found yet (very-early-frame race) is safe — locomotion just
     * defaults to world-axis-aligned walking until the head comes online.
     */
    private fun getHeadYawRad(): Float {
        val head =
            Query.where { has(AvatarAttachment.id) }
                .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }
                .eval()
                .firstOrNull()
                ?: return 0f
        val transform = head.tryGetComponent<Transform>() ?: return 0f
        // Pose.forward() returns the world-space forward vector implied by the pose's
        // quaternion. Projecting onto XZ and taking atan2 gives us yaw in radians.
        val fwd = transform.transform.forward()
        // atan2(x, -z) yields yaw such that 0° = forward along -Z, +90° = forward along +X,
        // matching the convention used by Scene.setViewOrigin's `degRotation` parameter
        // (rotation about Y, 0° = facing -Z).
        return atan2(fwd.x, -fwd.z)
    }

    /** Wrap a yaw value into the canonical [-180°, 180°] range so it doesn't drift unbounded. */
    private fun wrapYawDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    companion object {
        // Gentle walking pace — ~1.5 m/s is normal human walk speed and avoids the lurchy feel
        // of higher values in a small gallery. Tune up to ~2.0 for "brisk" if needed.
        private const val WALK_SPEED_M_PER_S: Float = 1.5f

        // 30° per stick press is the de-facto standard in Quest titles (Beat Saber, Pavlov, …)
        // — large enough to feel responsive, small enough to limit nausea spikes from each step.
        private const val SNAP_TURN_DEG: Float = 30f
    }
}
