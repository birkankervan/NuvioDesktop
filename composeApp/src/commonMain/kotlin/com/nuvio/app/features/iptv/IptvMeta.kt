package com.nuvio.app.features.iptv

internal const val IPTV_META_PREFIX = "iptv:"

/** True for ids minted by IPTV playback; these must never hit addons, trackers or watch progress. */
internal fun isIptvMetaId(id: String?): Boolean = id?.startsWith(IPTV_META_PREFIX) == true
