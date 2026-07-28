package com.blink.dtn.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Local APK / signing trust helpers for update gossip and Profile status.
 * Does not claim extraction-proofing — only same-cert install checks.
 */
object BuildIntegrity {
    private const val TAG = "BuildIntegrity"

    data class Status(
        /** Short Russian line for Profile / About. */
        val labelRu: String,
        val isDebugBuild: Boolean,
        val certSha256Hex: String?,
        val matchesExpectedRelease: Boolean?
    )

    fun describe(context: Context): Status {
        val debug = try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            true
        }
        val sha = ownSigningCertSha256(context)
        // Prefer BuildConfig (injected from release keystore at assemble time); SecurityConfig
        // property already falls back from BuildConfig → empty const.
        val expected = SecurityConfig.EXPECTED_RELEASE_CERT_SHA256.trim().lowercase()
        val match = when {
            expected.isEmpty() || sha == null -> null
            else -> sha.equals(expected, ignoreCase = true)
        }
        val label = when {
            debug -> "Сборка: debug (не Play)"
            match == true -> "Сборка: официальная подпись"
            match == false -> "Сборка: неофициальная подпись"
            else -> "Сборка: подпись неизвестна / локальная"
        }
        return Status(label, debug, sha, match)
    }

    fun ownSigningCertSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val chars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return null
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            } ?: return null
            sha256Hex(chars)
        } catch (e: Exception) {
            Log.w(TAG, "ownSigningCertSha256: ${e.message}")
            null
        }
    }

    /**
     * True if [apkFile] is signed with the same certificate(s) as the currently installed app.
     * Critical for peer update transfer — reject forged APKs.
     */
    fun apkMatchesInstalledSignature(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() <= 0L) return false
        return try {
            val pm = context.packageManager
            val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return false

            val local = ownSignerBytes(context) ?: return false
            val remote = archiveSignerBytes(archive) ?: return false
            if (local.size != remote.size) return false
            local.indices.all { i ->
                MessageDigest.isEqual(local[i], remote[i])
            }
        } catch (e: Exception) {
            Log.w(TAG, "apkMatchesInstalledSignature: ${e.message}")
            false
        }
    }

    fun myVersionCode(context: Context): Long {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun myVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun ownSignerBytes(context: Context): Array<ByteArray>? {
        val pm = context.packageManager
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return null
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs?.map { it.toByteArray() }?.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }?.toTypedArray()
        }
    }

    private fun archiveSignerBytes(info: android.content.pm.PackageInfo): Array<ByteArray>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return null
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs?.map { it.toByteArray() }?.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }?.toTypedArray()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { "%02x".format(it) }
    }
}
