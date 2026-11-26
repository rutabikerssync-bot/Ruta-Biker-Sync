package com.rutabikersync.app.feature.promo

import android.content.Context
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class PromoFrequency(val apiValue: String) {
    ONCE_PER_CAMPAIGN("once_per_campaign"),
    ONCE_PER_DAY("once_per_day");

    companion object {
        fun fromApi(value: String?): PromoFrequency =
            entries.find { it.apiValue.equals(value, ignoreCase = true) } ?: ONCE_PER_DAY
    }
}

data class PromoBanner(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val badgeText: String = "",
    val imageUrl: String = "",
    val accentColorHex: String = "#E5B869",
    val ctaLabel: String = "Ver más",
    val ctaTarget: String = "",
    val frequency: PromoFrequency = PromoFrequency.ONCE_PER_DAY,
    val priority: Int = 0
)

/**
 * Repositorio de anuncios/modales promocionales (offline-first + control de frecuencia).
 */
class PromoBannerRepository(
    context: Context? = null,
    private val inMemorySeenMap: MutableMap<String, Long> = mutableMapOf()
) {
    companion object {
        private const val PREFS_NAME = "rbs_promo_banners_prefs"
        private const val KEY_PREFIX_SEEN_MS = "seen_at_"
        const val ONE_DAY_MS = 24L * 3600L * 1000L
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastSeenEpochMs(bannerId: String): Long? {
        if (prefs != null) {
            val v = prefs.getLong("$KEY_PREFIX_SEEN_MS$bannerId", -1L)
            return if (v >= 0L) v else null
        }
        return inMemorySeenMap[bannerId]
    }

    fun markSeen(bannerId: String, nowMs: Long = System.currentTimeMillis()) {
        if (prefs != null) {
            prefs.edit().putLong("$KEY_PREFIX_SEEN_MS$bannerId", nowMs).apply()
        } else {
            inMemorySeenMap[bannerId] = nowMs
        }
    }

    fun markAllSeen(banners: List<PromoBanner>, nowMs: Long = System.currentTimeMillis()) {
        banners.forEach { markSeen(it.id, nowMs) }
    }

    fun filterEligible(
        banners: List<PromoBanner>,
        nowMs: Long = System.currentTimeMillis()
    ): List<PromoBanner> = banners.filter { banner ->
        val lastSeen = lastSeenEpochMs(banner.id) ?: return@filter true
        when (banner.frequency) {
            PromoFrequency.ONCE_PER_CAMPAIGN -> false
            PromoFrequency.ONCE_PER_DAY -> (nowMs - lastSeen) >= ONE_DAY_MS
        }
    }

    suspend fun fetchEligibleBanners(
        apiClient: ClubApiClient,
        nowMs: Long = System.currentTimeMillis()
    ): List<PromoBanner> {
        val res = apiClient.fetchActivePromoBanners().getOrNull() ?: return emptyList()
        if (!res.isSuccess || res.body == null) return emptyList()
        val arr = res.body["banners"] as? JsonArray ?: return emptyList()
        val parsed = arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            parseBanner(obj)
        }
        return filterEligible(parsed, nowMs)
    }

    private fun parseBanner(obj: JsonObject): PromoBanner? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        return PromoBanner(
            id = id,
            title = title,
            subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
            badgeText = obj["badgeText"]?.jsonPrimitive?.contentOrNull ?: "",
            imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            accentColorHex = obj["accentColorHex"]?.jsonPrimitive?.contentOrNull ?: "#E5B869",
            ctaLabel = obj["ctaLabel"]?.jsonPrimitive?.contentOrNull ?: "Ver más",
            ctaTarget = obj["ctaTarget"]?.jsonPrimitive?.contentOrNull ?: "",
            frequency = PromoFrequency.fromApi(obj["frequency"]?.jsonPrimitive?.contentOrNull),
            priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}
