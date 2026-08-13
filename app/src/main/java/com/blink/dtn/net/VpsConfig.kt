package com.blink.dtn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VPS endpoint config (device-local).
 *
 * There is **no shipped default gateway**: a fresh install talks to nobody until
 * the user enters a URL. The app is fully usable in that state — BLE mesh is the
 * primary transport and the gateway is an optional extra. A previously stored
 * URL is kept as-is, so existing installs do not silently lose their gateway.
 */
object VpsConfig {
    private const val PREFS = "blink_prefs"
    private const val KEY_BASE_URL = "vps_base_url"

    /** No gateway configured. Kept as a named constant for readability. */
    const val DEFAULT_BASE_URL = ""

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BASE_URL, null)
            ?.trim()
            .orEmpty()
            .trimEnd('/')
        _baseUrl.value = stored
    }

    fun setBaseUrl(context: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, cleaned).apply()
        _baseUrl.value = cleaned
    }

    fun isConfigured(context: Context): Boolean {
        if (_baseUrl.value.isBlank()) init(context)
        return _baseUrl.value.isNotBlank()
    }

    /** True when the user removed / never set a gateway — app stays fully usable. */
    fun isGatewayDisabled(context: Context): Boolean = !isConfigured(context)

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
