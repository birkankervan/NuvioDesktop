package com.nuvio.app.features.iptv

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
internal data class IptvAccount(val server: String, val username: String, val password: String)

internal data class IptvCategory(val id: String, val name: String)

internal data class IptvItem(
    val id: String,
    val name: String,
    val icon: String?,
    val url: String,
    val num: Int? = null,
    val categoryId: String? = null,
)

/** One now/next programme; times are epoch seconds. */
internal data class IptvEpgEntry(val title: String, val description: String, val startSec: Long, val endSec: Long)

internal class IptvException(message: String) : Exception(message)

/** Never put credentials in exception messages: they end up in toasts/logs. */
internal class XtreamClient(private val account: IptvAccount) {
    private val http = createApiHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun enc(s: String) = s.encodeURLPathPart()
    private fun base() = "${account.server}/player_api.php"

    private suspend fun call(action: String?, categoryId: String? = null, extra: Map<String, String> = emptyMap()): JsonElement {
        val response = try {
            http.get(base()) {
                parameter("username", account.username)
                parameter("password", account.password)
                action?.let { parameter("action", it) }
                categoryId?.let { parameter("category_id", it) }
                extra.forEach { (k, v) -> parameter(k, v) }
            }
        } catch (e: Exception) {
            throw IptvException("Could not reach server")
        }
        if (!response.status.isSuccess()) throw IptvException("Server returned HTTP ${response.status.value}")
        return runCatching { json.parseToJsonElement(response.bodyAsText()) }
            .getOrElse { throw IptvException("Unexpected server response") }
    }

    suspend fun authenticate() {
        val info = (call(null) as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw IptvException("Login failed")
        val auth = info.str("auth")
        if (auth != "1" && auth != "true") throw IptvException("Login failed: wrong username or password")
    }

    suspend fun categories(vod: Boolean): List<IptvCategory> =
        (call(if (vod) "get_vod_categories" else "get_live_categories") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject) }
            .mapNotNull { o ->
                val id = o.str("category_id") ?: return@mapNotNull null
                IptvCategory(id, o.str("category_name") ?: id)
            }
            .distinctBy { it.id }

    suspend fun items(vod: Boolean, categoryId: String): List<IptvItem> =
        (call(if (vod) "get_vod_streams" else "get_live_streams", categoryId) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = o.str("stream_id") ?: return@mapNotNull null
                val name = o.str("name") ?: id
                val url = if (vod) {
                    val ext = o.str("container_extension") ?: "mp4"
                    "${account.server}/movie/${enc(account.username)}/${enc(account.password)}/$id.$ext"
                } else {
                    "${account.server}/live/${enc(account.username)}/${enc(account.password)}/$id.m3u8"
                }
                IptvItem(id, name, (o.str("stream_icon") ?: o.str("cover"))?.takeIf { it.startsWith("http", ignoreCase = true) }, url,
                    o.str("num")?.toIntOrNull(), categoryId)
            }
            .distinctBy { it.id }

    /** Now/next for a live stream. Titles and descriptions come base64 encoded. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun shortEpg(streamId: String): List<IptvEpgEntry> {
        fun dec(s: String?) = s?.let { runCatching { Base64.Mime.decode(it).decodeToString() }.getOrNull() }.orEmpty().trim()
        val listings = (call("get_short_epg", extra = mapOf("stream_id" to streamId, "limit" to "2")) as? JsonObject)
            ?.get("epg_listings") as? JsonArray
        return listings.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val start = o.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = o.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val title = dec(o.str("title")).ifEmpty { return@mapNotNull null }
            IptvEpgEntry(title, dec(o.str("description")), start, end)
        }
    }

    fun close() = http.close()
}

private fun JsonObject.str(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun normalizeServer(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.isNotEmpty() && !s.contains("://")) s = "http://$s"
    return s.removeSuffix("/player_api.php")
}

// Port of iptvAdultFilter.js. Token based (no lookbehind: Kotlin/Native regex lacks it).
// ponytail: keyword heuristic; providers can still mislabel.
private val adultTokens = setOf(
    "adult", "adults", "porn", "porno", "erotic", "erotico", "erotica", "erotik", "sex", "sexy",
    "yetiskin", "yetiskinler", "erwachsene", "erwachsenen", "adulti", "adulte", "adultes", "adultos",
    "взрослые", "للكبار", "للبالغين", "بالغين",
)

private fun fold(s: String): String = buildString {
    for (c in s.lowercase()) append(
        when (c) { 'ı' -> 'i'; 'ş' -> 's'; 'ç' -> 'c'; 'ğ' -> 'g'; 'ö' -> 'o'; 'ü' -> 'u'; 'é', 'è' -> 'e'; 'ã', 'à', 'á' -> 'a'; else -> c },
    )
}

private fun tokens(s: String): List<String> =
    s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

internal fun isAdultCategoryName(name: String): Boolean {
    val text = fold(name)
    if (Regex("\\+\\s?18|18\\s?\\+|\\(18\\)|18\\s?plus|для\\s+взрослых").containsMatchIn(text)) return true
    val t = tokens(text)
    t.forEachIndexed { i, w ->
        if (w.length >= 3 && w.all { it == 'x' }) return true
        if (w in adultTokens) {
            // "adult swim" / "adult animation" are not adult content
            if ((w == "adult" || w == "adults") && t.getOrNull(i + 1).let { it == "swim" || it == "animation" }) return@forEachIndexed
            return true
        }
    }
    return false
}

private val itemTag = Regex("^[\\s|\\[(]*(?:X{3,}|18\\+|\\+18)(?![\\p{L}\\p{N}])")

// Case-sensitive tag so the film "xXx: Return of Xander Cage" is kept.
internal fun isAdultItemName(name: String): Boolean =
    itemTag.containsMatchIn(name) || tokens(name.lowercase()).any { it == "porn" }
