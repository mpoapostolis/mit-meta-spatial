package art.galerra.museum.spatial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal PocketBase REST client for the Museum scenes.
 * Configured against https://yms.galerra.art (the same backend the web viewer uses).
 */
object PocketBaseClient {
    private const val BASE_URL = "https://yms.galerra.art"
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun loadExhibition(sceneId: String): ExhibitionData = withContext(Dispatchers.IO) {
        val scene = getScene(sceneId)
        val objects = getSceneObjects(sceneId)
        ExhibitionData(scene = scene, objects = objects)
    }

    /**
     * Fetch the full list of scenes (latest first) for the welcome picker.
     * Mirrors `PB.collection("scenes").getFullList({ sort: "-created" })` from the web admin.
     */
    suspend fun loadScenes(): List<SceneRecord> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/collections/scenes/records?perPage=50&sort=-created")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Scenes list fetch failed (${resp.code})")
            val body = resp.body?.string() ?: error("Empty scenes body")
            val page = json.decodeFromString(SceneListPage.serializer(), body)
            page.items
        }
    }

    private fun getScene(id: String): SceneRecord {
        val req = Request.Builder()
            .url("$BASE_URL/api/collections/scenes/records/$id")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Scene fetch failed (${resp.code})")
            val body = resp.body?.string() ?: error("Empty scene body")
            return json.decodeFromString(SceneRecord.serializer(), body)
        }
    }

    private fun getSceneObjects(sceneId: String): List<SceneObjectRecord> {
        val url = "$BASE_URL/api/collections/scene_objects/records" +
            "?filter=" + java.net.URLEncoder.encode("scene = \"$sceneId\"", "UTF-8") +
            "&expand=asset&perPage=200&sort=created"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Objects fetch failed (${resp.code})")
            val body = resp.body?.string() ?: error("Empty objects body")
            val page = json.decodeFromString(SceneObjectPage.serializer(), body)
            return page.items
        }
    }

    /** PocketBase file URL helper (mirror of the web viewer's `fileUrl()`). */
    fun fileUrl(record: AssetRecord, filename: String, thumb: String? = null): String {
        val collection = record.collectionId
        val q = if (thumb != null) "?thumb=$thumb" else ""
        return "$BASE_URL/api/files/$collection/${record.id}/$filename$q"
    }
}

// ─── data models ────────────────────────────────────────────────────────────

@Serializable
data class SceneRecord(
    val id: String,
    val collectionId: String,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class AssetRecord(
    val id: String,
    val collectionId: String,
    val name: String = "",
    val type: String = "image",
    val file: String = "",
    val mime: String = "",
)

@Serializable
data class SceneObjectRecord(
    val id: String,
    val collectionId: String,
    val scene: String,
    val asset: String,
    val title: String = "",
    val description: String = "",
    val position: List<Float> = listOf(0f, 0f, 0f),
    val rotation: List<Float> = listOf(0f, 0f, 0f),
    val scale: List<Float> = listOf(1f, 1f, 1f),
    val interaction: String = "none",
    val walkable: Boolean = false,
    val expand: ExpandedAsset? = null,
)

@Serializable
data class ExpandedAsset(val asset: AssetRecord? = null)

@Serializable
data class SceneObjectPage(
    val items: List<SceneObjectRecord>,
    @SerialName("totalItems") val totalItems: Int = 0,
)

@Serializable
data class SceneListPage(
    val items: List<SceneRecord>,
    @SerialName("totalItems") val totalItems: Int = 0,
)

data class ExhibitionData(
    val scene: SceneRecord,
    val objects: List<SceneObjectRecord>,
)
