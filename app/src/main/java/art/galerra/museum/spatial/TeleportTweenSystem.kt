package art.galerra.museum.spatial

import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene

/**
 * Click-to-animated-move tween for the player view origin. Mirrors the Babylon web viewer's
 * [teleportTo] (viewerScene.ts, CubicEase EASEINOUT) so the Quest VR build feels identical when
 * the user trigger-clicks on the gallery floor.
 *
 * Coexists with [SmoothLocomotionSystem]:
 *  - Thumbstick walking → continuous, head-relative locomotion (still active).
 *  - Trigger-click on env floor → spawns a [startTween] from current → target over [DURATION_MS]
 *    using cubic ease-in-out (smoothstep variant). Yaw is preserved (read from the locomotion
 *    system so a previously-snap-turned heading isn't lost).
 *
 * Conflict avoidance: every tween frame we call [SmoothLocomotionSystem.syncFromScene] so the
 * locomotion system's cached origin stays in lockstep. Without that sync, the next thumbstick
 * frame would increment from stale state and snap the player back. If the user holds the stick
 * mid-tween, their stick-driven setViewOrigin write happens on the same frame as ours; the last
 * write wins for that frame. Since both systems write coherent state (we sync them), the visual
 * effect is acceptable — the user's stick input takes over naturally.
 *
 * Registered in [ImmersiveActivity.onSceneReady] alongside [SmoothLocomotionSystem].
 */
class TeleportTweenSystem(
    private val scene: Scene,
    private val locomotion: SmoothLocomotionSystem,
) : SystemBase() {

    private var active: Boolean = false
    private var startMs: Long = 0L
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var startZ: Float = 0f
    private var targetX: Float = 0f
    private var targetY: Float = 0f
    private var targetZ: Float = 0f
    private var yawDeg: Float = 0f

    /**
     * Kick off a tween from the player's current view origin to [targetWorld] (the world-space
     * floor hit point from [com.meta.spatial.runtime.HitInfo.point]). Adds [EYE_HEIGHT_M] to the
     * target Y so the player's HEAD ends at eye level over the click point — matches the web
     * viewer's `new Vector3(point.x, point.y + this.eyeHeight, point.z)` math.
     *
     * No-op if a tween is already running (mirrors the web viewer's `if (isAnimating) return`).
     */
    fun startTween(targetWorld: Vector3) {
        if (active) return

        // Read the live origin so the tween starts from wherever the player actually is (which
        // may have been moved by stick walking since the last tween). Scene.getViewOrigin()
        // returns the cached view-origin position written by the most recent setViewOrigin call.
        val origin = scene.getViewOrigin() ?: Vector3(0f, 0f, 0f)

        val tx = targetWorld.x
        val ty = targetWorld.y + EYE_HEIGHT_M
        val tz = targetWorld.z

        // Skip if too close — avoids a flicker for clicks right under the player. Matches the
        // web viewer's `if (dist < 0.05) return` early-out.
        val dx = tx - origin.x
        val dy = ty - origin.y
        val dz = tz - origin.z
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq < 0.05f * 0.05f) return

        startX = origin.x
        startY = origin.y
        startZ = origin.z
        targetX = tx
        targetY = ty
        targetZ = tz
        yawDeg = locomotion.getCurrentYawDeg()
        startMs = System.currentTimeMillis()
        active = true
    }

    override fun execute() {
        if (!active) return

        val elapsed = System.currentTimeMillis() - startMs
        val rawT = (elapsed.toFloat() / DURATION_MS).coerceIn(0f, 1f)

        // Cubic ease-in-out (smoothstep) — symmetric S-curve. Equivalent to Babylon's
        // CubicEase + EASINGMODE_EASEINOUT for the position channel: derivative is 0 at both
        // endpoints, max slope at t=0.5. Cheap closed form: 3t² − 2t³.
        val eased = rawT * rawT * (3f - 2f * rawT)

        val x = startX + (targetX - startX) * eased
        val y = startY + (targetY - startY) * eased
        val z = startZ + (targetZ - startZ) * eased

        scene.setViewOrigin(x, y, z, yawDeg)
        // Keep the locomotion system's cached origin matched so the next stick frame doesn't
        // snap us back. Yaw stays at whatever locomotion already had (we read it at tween start
        // and never animate yaw — same as the web viewer's teleportTo).
        locomotion.syncFromScene(x, y, z, yawDeg)

        if (rawT >= 1f) {
            active = false
        }
    }

    /** True while a tween is in progress. The activity uses this to suppress duplicate clicks. */
    fun isActive(): Boolean = active

    companion object {
        // 600 ms matches the brief and is roughly the perceptual sweet-spot between "instant"
        // (≤200 ms feels like a snap, can disorient in VR) and "slow" (≥1 s feels sluggish).
        private const val DURATION_MS: Float = 600f

        // Standing-adult eye height. Matches the web viewer's `private eyeHeight = 1.65` so the
        // animated head height over a click point is identical across platforms.
        private const val EYE_HEIGHT_M: Float = 1.65f
    }
}
