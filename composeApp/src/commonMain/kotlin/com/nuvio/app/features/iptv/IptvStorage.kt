package com.nuvio.app.features.iptv

/** Holds the single Xtream account as a JSON string. */
internal expect object IptvStorage {
    fun load(): String?
    fun save(value: String?)
}
