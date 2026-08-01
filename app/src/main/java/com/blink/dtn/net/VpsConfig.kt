package com.blink.dtn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VPS endpoint config (device-local). Empty base URL = Internet path disabled.
 */
object VpsConfig {
    private const val PREFS = "blink_prefs"
    private const val KEY_BASE_URL = "vps_base_url"

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(context: Context) {
        _baseUrl.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "")
            ?.trim()
            .orEmpty()
            .trimEnd('/')
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
