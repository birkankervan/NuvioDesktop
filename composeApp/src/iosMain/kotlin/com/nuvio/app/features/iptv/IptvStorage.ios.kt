package com.nuvio.app.features.iptv

// ponytail: desktop-only feature; non-desktop targets keep the account in memory only.
internal actual object IptvStorage {
    private var value: String? = null
    actual fun load(): String? = value
    actual fun save(value: String?) { this.value = value }
    private var progress: String? = null
    actual fun loadProgress(): String? = progress
    actual fun saveProgress(json: String) { progress = json }
}
