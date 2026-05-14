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

/**
 * Per-frame head-follow for the welcome / scene-picker panel.
 *
 * Uses a plain Android Handler/Looper polling loop at ~20 Hz — registerSystem-based ticking
 * wasn't firing for this class on the device, so we bypass it.
 */
class WelcomePanelFollowSystem(
    private val scene: Scene,
    private val getPanelEntity: () -> Entity?,
) {
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

        val all = Query.where { has(AvatarAttachment.id) }.eval().toList()
        if (ticks <= 5) {
            val types = all.mapNotNull { it.tryGetComponent<AvatarAttachment>()?.type }
            Log.i("MuseumSpatial", "🔍 tick #$ticks avatars=${all.size} types=$types")
        }

        val head = all.firstOrNull { it.tryGetComponent<AvatarAttachment>()?.type == "head" }
        val rawHeadPose = head?.tryGetComponent<Transform>()?.transform ?: scene.getViewerPose()

        // If pose is identity (headset not on head, tracking lost, etc.), use a sane fallback
        // so the panel still renders 1.5 m in front of the LOCAL_FLOOR origin at eye level.
        val headPose = if (rawHeadPose == Pose()) {
            if (ticks <= 5) Log.i("MuseumSpatial", "🔍 tick #$ticks pose identity, using fallback (0,1.6,0)")
            Pose(Vector3(0f, 1.6f, 0f), Quaternion())
        } else {
            rawHeadPose
        }

        val fwd = headPose.q * Vector3(0f, 0f, -1f)
        val flatLenSq = fwd.x * fwd.x + fwd.z * fwd.z
        if (flatLenSq < 1e-6f) return
        val flatLen = kotlin.math.sqrt(flatLenSq)
        val fx = fwd.x / flatLen
        val fz = fwd.z / flatLen

        // Panel centred at head's actual eye Y (handles kneeling / different player heights).
        val panelPos = Vector3(
            headPose.t.x + fx * 1.5f,
            headPose.t.y,
            headPose.t.z + fz * 1.5f,
        )
        val toHead = Vector3(headPose.t.x - panelPos.x, 0f, headPose.t.z - panelPos.z)
        val rot = Quaternion.lookRotationAroundY(toHead)

        panel.setComponent(Transform(Pose(panelPos, rot)))
        panel.setComponent(Visible(true))

        if (ticks <= 5) {
            Log.i(
                "MuseumSpatial",
                "🎯 panel at (${"%.2f".format(panelPos.x)}, ${"%.2f".format(panelPos.y)}, ${"%.2f".format(panelPos.z)}) head at (${"%.2f".format(headPose.t.x)}, ${"%.2f".format(headPose.t.y)}, ${"%.2f".format(headPose.t.z)})",
            )
        }
    }
}
