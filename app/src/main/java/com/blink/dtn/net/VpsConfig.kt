package com.blink.dtn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VPS endpoint config (device-local).
 * Default: HTTPS via nip.io → FirstByte node (see scripts/setup-https.sh).
 */
object VpsConfig {
    private const val PREFS = "blink_prefs"
    private const val KEY_BASE_URL = "vps_base_url"

    /** Shipped default: TLS at Nginx, host via free nip.io DNS. */
    const val DEFAULT_BASE_URL = "https://157.228.136.239.nip.io"

    private val LEGACY_DEFAULTS = setOf(
        "http://157.228.136.239:8080",
        "https://node.tuktuk.dev",
        "http://node.tuktuk.dev",
        "https://node.tuktuk.dev:443"
    )

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BASE_URL, null)
            ?.trim()
            .orEmpty()
            .trimEnd('/')
        val effective = when {
            stored.isBlank() -> DEFAULT_BASE_URL
            stored in LEGACY_DEFAULTS -> DEFAULT_BASE_URL
            else -> stored
        }
        if (stored != effective) {
            prefs.edit().putString(KEY_BASE_URL, effective).apply()
        }
        _baseUrl.value = effective
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

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
