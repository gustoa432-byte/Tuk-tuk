package com.blink.dtn

/**
 * Quarantine map for Qq Great Cleanup (P7).
 *
 * Legacy code stays in-tree (no physical delete until P8 field tests).
 * Runtime isolation is [BuildConfig.QQ_CORE_ONLY] (+ [BuildConfig.QQ_ALLOW_TELEMETRY_UPLOAD]).
 *
 * KEEP active in Core:
 * - BLE/DTN PRIVATE text, ACK, ContactQr, Room 1:1
 * - VPS push/pull messaging ([com.blink.dtn.net.VpsBridge.performSync] / register)
 *
 * GATED / no-op in Core (files remain):
 * - Hub / Crowd / PUBLIC channels UI
 * - VK relay loop
 * - Oracle sync/hints + social_orbit meets
 * - Global directory sync + moderation blacklist sync
 * - Gamification counters / cosmetics / invite sheet
 * - Telemetry ZIP upload to VPS→Telegram (local share only)
 */
object QqLegacyQuarantine
