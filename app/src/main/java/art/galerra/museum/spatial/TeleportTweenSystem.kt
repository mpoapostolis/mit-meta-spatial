package art.galerra.museum.spatial

import android.util.Log
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import kotlin.math.abs

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
     * floor hit point from [com.meta.spatial.runtime.HitInfo.point]).
     *
     * Vertical handling: we **ground-lock** the tween — targetY is forced to the player's
     * current Y rather than `targetWorld.y + EYE_HEIGHT_M`. Two reasons:
     *
     *   1. The env glb's mesh-hit point .y depends on the artist-authored geometry. Even with
     *      the normal.y > 0.5 filter in [ImmersiveActivity.attachFloorClickToEnv] a click on
     *      a stairstep / slightly slanted platform / decorative pedestal will return a high Y
     *      that the old `+ EYE_HEIGHT_M` math would lift the player up to.
     *   2. The web viewer's behaviour in the museum exhibitions is ground-locked walking —
     *      paintings are at fixed eye level, the floor is one continuous plane.
     *
     * Defensive Y guard: if the user clicked an oblique surface the SDK happens to classify as
     * floor-ish (e.g. a steep ramp), the hit Y can be wildly different from current Y. We also
     * reject any click whose ABSOLUTE Y delta to current origin exceeds [MAX_Y_DELTA_M] — that
     * prevents the click from teleporting the player onto a roof or into a basement on stale
     * meshes where the normal heuristic over-accepts.
     *
     * No-op if a tween is already running (mirrors the web viewer's `if (isAnimating) return`).
     */
    fun startTween(targetWorld: Vector3) {
        if (active) return

        // Read the live origin so the tween starts from wherever the player actually is (which
        // may have been moved by stick walking since the last tween). Scene.getViewOrigin()
        // returns the cached view-origin position written by the most recent setViewOrigin call.
        val origin = scene.getViewOrigin() ?: Vector3(0f, 0f, 0f)

        // Defensive Y-delta guard: the hit point's "natural eye-height" target would be
        // `targetWorld.y + EYE_HEIGHT_M`. If that's drastically different from the player's
        // current Y, the surface is almost certainly not a real floor (probably a wall, ceiling,
        // or weird collision pocket the normal-y filter let through). Cancel rather than fly
        // the player up. 0.5 m is permissive enough for a single step / small ramp.
        val naturalTargetY = targetWorld.y + EYE_HEIGHT_M
        if (abs(naturalTargetY - origin.y) > MAX_Y_DELTA_M) {
            Log.w(
                TAG,
                "teleport: rejecting click (vertical delta ${naturalTargetY - origin.y} m too large; " +
                    "hit y=${targetWorld.y}, origin y=${origin.y}) — probably hit a non-floor surface",
            )
            return
        }

        val tx = targetWorld.x
        // GROUND-LOCK: keep player Y exactly where it was. Walking and teleport never change Y;
        // the spawn-floor Y is set by [ImmersiveActivity.recenterOnEnvironment] and persists.
        val ty = origin.y
        val tz = targetWorld.z

        // Skip if too close — avoids a flicker for clicks right under the player. Matches the
        // web viewer's `if (dist < 0.05) return` early-out. Compares XZ distance only because
        // we ground-locked Y above; the dy term would always be 0.
        val dx = tx - origin.x
        val dz = tz - origin.z
        val distSq = dx * dx + dz * dz
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
        private const val TAG = "TeleportTween"

        // 600 ms matches the brief and is roughly the perceptual sweet-spot between "instant"
        // (≤200 ms feels like a snap, can disorient in VR) and "slow" (≥1 s feels sluggish).
        private const val DURATION_MS: Float = 600f

        // Standing-adult eye height. Matches the web viewer's `private eyeHeight = 1.65`. Only
        // used as a reference for the defensive Y-delta guard below — the actual tween is
        // ground-locked (target Y = current origin Y) so the player never ends up above floor.
        private const val EYE_HEIGHT_M: Float = 1.65f

        // Reject any click whose natural eye-height target Y differs from the current view
        // origin Y by more than this much. 0.5 m permits a single step/small ramp while
        // rejecting walls, ceilings, and high pedestals that the normal.y > 0.5 floor filter
        // upstream lets through (a 60°-tilted floor is "floor-like" by normal but its hit
        // point can still be high).
        private const val MAX_Y_DELTA_M: Float = 0.5f
    }
}
