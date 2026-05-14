package art.galerra.museum.spatial

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/**
 * Per-frame head-follow for the welcome / scene-picker panel.
 *
 * Problem this solves: the welcome panel originally used a fixed pose `(0, 1.6, -2)` relative to
 * the LOCAL_FLOOR origin. But the headset's actual facing direction at boot depends on how the
 * Quest was oriented when the OS handed the session to us — so the player can spawn looking any
 * direction and the panel ends up behind them.
 *
 * Solution: every frame, query [Scene.getViewerPose] (the live head pose written by the runtime),
 * project the head's forward onto the XZ plane to discard pitch/roll, and place the panel
 * [FOLLOW_DISTANCE_M] in front of the head at a fixed eye-level Y. The panel's quaternion is
 * computed via [Quaternion.lookRotationAroundY] so its +Z normal points BACK toward the head —
 * matching how the SDK's panel system treats face normals (the same trick MrukSample uses in
 * MenuPlacementSystem.kt).
 *
 * Lifecycle: registered in [ImmersiveActivity.onSceneReady] alongside the other systems. We
 * no-op when [welcomeState.value.visible] is false, so once the user picks a scene the panel is
 * frozen wherever it was (and the activity flips Visible(false) anyway, so it's invisible). We
 * don't unregisterSystem because re-showing the panel on user request would need a re-register
 * and SystemBase doesn't expose a stable handle for that — early-out is cheaper than dance.
 *
 * Note: We deliberately keep Y fixed at [PANEL_Y_M] rather than reading head.t.y. The runtime
 * eye level for [com.meta.spatial.runtime.ReferenceSpace.LOCAL_FLOOR] is the actual head height,
 * which fluctuates as the player squats/stands; pinning the panel at a constant 1.6 m gives a
 * calmer floating-in-front-of-me feeling than having it bob with the head.
 */
class WelcomePanelFollowSystem(
    private val scene: Scene,
    private val getPanelEntity: () -> Entity?,
) : SystemBase() {

    override fun execute() {
        // No-op while the panel is hidden — no point updating an invisible entity, and once the
        // user has picked a scene we want the panel to STOP following them (it's parked behind
        // them anyway because Visible(false) makes the position irrelevant).
        if (!welcomeState.value.visible) return

        val panel = getPanelEntity() ?: return

        // Live head pose written by the OpenXR runtime each frame. May briefly be the identity
        // pose during the first few frames before the session warms up; skip those frames so we
        // don't anchor the panel at the world origin. getViewerPose returns a non-nullable
        // [Pose] in SDK 0.12, so the null-check is a `== Pose()` identity test only.
        val headPose = scene.getViewerPose()
        if (headPose == Pose()) return

        // Project the head's forward direction onto the XZ plane (ground-locked). headPose.q
        // bakes in pitch/roll/yaw of the headset; multiplying it by the local-forward unit
        // vector (0, 0, -1 in the SDK's convention — see toolkit/Pose's forward() impl) gives
        // the world-space gaze vector. Zeroing Y removes pitch, normalize re-stretches it back
        // to unit length so [FOLLOW_DISTANCE_M] is the actual horizontal distance to the panel
        // (not foreshortened by the pitch component).
        val headForward = headPose.q * Vector3(0f, 0f, -1f)
        val groundForward = Vector3(headForward.x, 0f, headForward.z)
        val flatLen = groundForward.length()
        // If the player is looking straight up/down the projected vector collapses to zero —
        // skip this frame rather than divide-by-zero and snap the panel to the head position.
        if (flatLen < 1e-4f) return
        val forwardN = Vector3(groundForward.x / flatLen, 0f, groundForward.z / flatLen)

        // Panel position: [FOLLOW_DISTANCE_M] in front of the head's horizontal projection, at
        // fixed eye-level Y. Using head.t.x/z (not the LOCAL_FLOOR origin) keeps the panel in
        // front of the player even after they've stick-walked or teleported elsewhere — the
        // welcome panel is only ever visible at boot, but if a future menu re-shows it the
        // tracking still works.
        val panelPos = Vector3(
            headPose.t.x + forwardN.x * FOLLOW_DISTANCE_M,
            PANEL_Y_M,
            headPose.t.z + forwardN.z * FOLLOW_DISTANCE_M,
        )

        // The panel's +Z face is its rendered surface (default panel normal in the SDK). We
        // want that face pointing TOWARD the player → rotation that maps +Z to (head − panel).
        // lookRotationAroundY does exactly this with the Y axis locked, so the panel never
        // tilts up/down even if the player looks up — keeps the panel vertical/readable.
        val panelToHead = Vector3(
            headPose.t.x - panelPos.x,
            0f,
            headPose.t.z - panelPos.z,
        )
        // Same length-guard as above — the panelPos is by construction `FOLLOW_DISTANCE_M` away,
        // so this is non-zero except in pathological zero-flatLen cases we already handled.
        val rot = Quaternion.lookRotationAroundY(panelToHead)

        panel.setComponent(Transform(Pose(panelPos, rot)))
        // Defensive Visible(true) — the activity sets this on spawn, but if anything else has
        // flipped it we don't want to mysteriously stop following. The visibility flip we DO
        // care about (after scene pick) is gated by the welcomeState.visible early-return above.
        panel.setComponent(Visible(true))
    }

    companion object {
        // 1.5 m in front of the player — comfortable read distance for a 1.6 × 1.4 m panel.
        // Closer feels claustrophobic in a Quest headset; further makes the text small.
        private const val FOLLOW_DISTANCE_M: Float = 1.5f

        // Panel center height. Half-way between standing eye level (~1.65 m) and the panel's
        // height (1.4 m) so the title sits at gaze level and the scene list extends downward.
        private const val PANEL_Y_M: Float = 1.6f
    }
}
