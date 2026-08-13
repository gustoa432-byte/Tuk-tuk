package com.blink.dtn.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hidden on-device error journal (not shown in UI).
 * Survives process death; included in "Отчет об ошибках" ZIP.
 */
object ErrorJournal {
    private const val TAG = "ErrorJournal"
    private const val DIR = "error_journal"
    private const val FILE = "errors.log"
    private const val MAX_BYTES = 512 * 1024 // 512 KiB ring-ish truncate

    private val installed = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val dir = File(ctx.filesDir, DIR).also { it.mkdirs() }
        logFile = File(dir, FILE)
    }

    /** Install once — captures uncaught crashes into the journal. */
    fun install(context: Context) {
        init(context)
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(
                    tag = "FATAL",
                    throwable = throwable,
                    detail = "thread=${thread.name}"
                )
            } catch (_: Throwable) {
                // never block the real crash path
            }
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: Throwable) {
                    }
                }
        }
        record("BOOT", null, "ErrorJournal installed · sdk=${Build.VERSION.SDK_INT}")
    }

    fun record(tag: String, throwable: Throwable? = null, detail: String = "", context: Context? = null) {
        if (logFile == null && context != null) init(context)
        val file = logFile ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val block = buildString {
            appendLine("--- $stamp [$tag] ---")
            if (detail.isNotBlank()) appendLine(detail)
            if (throwable != null) {
                appendLine("${throwable.javaClass.name}: ${throwable.message}")
                appendLine(throwable.stackTraceToString().take(8000))
            }
            appendLine()
        }
        synchronized(this) {
            try {
                if (file.exists() && file.length() > MAX_BYTES) {
                    val keep = file.readText(Charsets.UTF_8).takeLast(MAX_BYTES / 2)
                    file.writeText("…truncated…\n$keep", Charsets.UTF_8)
                }
                file.appendText(block, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "record failed: ${e.message}")
            }
        }
        Log.e(TAG, "[$tag] $detail ${throwable?.message ?: ""}")
    }

    fun readAll(): String {
        val file = logFile ?: return "(journal not initialized)"
        return try {
            if (!file.exists()) "(empty)" else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "(read failed: ${e.message})"
        }
    }

    fun entryCountHint(): Int {
        val text = readAll()
        if (text.startsWith("(")) return 0
        return text.split("\n--- ").size - 1
    }

    /**
     * Build ZIP: error journal + MessageTrace telemetry export.
     */
    fun buildReportZip(
        context: Context,
        peerCount: Int = 0,
        peers: List<String> = emptyList()
    ): File? {
        init(context)
        return try {
            val stamp = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.US).format(Date())
            val out = File(context.cacheDir, "qq_error_report_$stamp.zip")
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            val versionCode = runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                }
            }.getOrDefault(0L)

            ZipOutputStream(FileOutputStream(out)).use { zos ->
                fun put(name: String, text: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                put(
                    "README.txt",
                    """
                    Qq error report
                    appVersion=$appVersion ($versionCode)
                    exportedAt=$stamp
                    Please forward this ZIP to @b6dmachine in Telegram.
                    """.trimIndent()
                )
                put("error_journal.txt", readAll())
                put(
                    "device.txt",
                    buildString {
                        appendLine("manufacturer=${Build.MANUFACTURER}")
                        appendLine("model=${Build.MODEL}")
                        appendLine("device=${Build.DEVICE}")
                        appendLine("sdk=${Build.VERSION.SDK_INT}")
                        appendLine("release=${Build.VERSION.RELEASE}")
                        appendLine("appVersion=$appVersion")
                        appendLine("versionCode=$versionCode")
                    }
                )

                // Embed full telemetry export if available.
                val telemetry = TraceStore.exportZip(context, peerCount, peers)
                if (telemetry != null && telemetry.exists()) {
                    zos.putNextEntry(ZipEntry("telemetry/${telemetry.name}"))
                    telemetry.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } else {
                    put("telemetry/MISSING.txt", "TraceStore.exportZip returned null")
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "buildReportZip failed: ${e.message}", e)
            record("REPORT_BUILD", e)
            null
        }
    }
}
