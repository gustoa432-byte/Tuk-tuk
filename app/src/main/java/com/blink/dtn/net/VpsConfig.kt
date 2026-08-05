package com.blink.dtn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VPS endpoint config (device-local).
 * Default production bridge: FirstByte TukTuk node (override in Settings).
 */
object VpsConfig {
    private const val PREFS = "blink_prefs"
    private const val KEY_BASE_URL = "vps_base_url"

    /** Shipped default: TLS via Nginx (see scripts/setup-https.sh). */
    const val DEFAULT_BASE_URL = "https://node.tuktuk.dev"

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null)
            ?.trim()
            .orEmpty()
            .trimEnd('/')
        val effective = stored.ifBlank { DEFAULT_BASE_URL }
        if (stored.isBlank()) {
            // Persist default once so Settings / VpsBridge see the same URL.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BASE_URL, effective).apply()
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
