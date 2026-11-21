package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage

internal actual object IptvStorage {
    private val store = DesktopStorage.store("nuvio_iptv")
    actual fun load(): String? = store.getString("account")
    actual fun save(value: String?) = store.putString("account", value)
}
