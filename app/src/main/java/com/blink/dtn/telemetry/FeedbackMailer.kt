package com.blink.dtn.telemetry

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Feedback / crash / trace delivery to the project mailbox.
 * Uses the system email chooser (no SMTP credentials in the app).
 */
object FeedbackMailer {
    const val FEEDBACK_EMAIL = "tuktukfb@internet.ru"
    private const val TAG = "FeedbackMailer"

    fun sendFeedback(
        context: Context,
        subject: String = "TukTuk feedback",
        body: String = "Опишите ошибку или идею:\n\n"
    ) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "Написать в TukTuk")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "No email app: ${e.message}")
            Toast.makeText(context, "Нет почтового приложения. Напишите на $FEEDBACK_EMAIL", Toast.LENGTH_LONG).show()
        }
    }

    fun sendTraceZip(context: Context, zip: File, subject: String = "TukTuk MessageTrace / error report") {
        if (!zip.exists() || zip.length() <= 0L) {
            Toast.makeText(context, "ZIP отчёта пуст или не создан", Toast.LENGTH_LONG).show()
            return
        }
        try {
            // Always share from cacheDir so FileProvider paths match (cache-path).
            val shareFile = ensureUnderCache(context, zip)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)

            // message/rfc822 prefers email clients; EXTRA_EMAIL is often ignored with application/zip.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Во вложении чёрный ящик MessageTrace / Delivery Observatory.\n" +
                        "Файл: ${shareFile.name}\n" +
                        "Адрес: $FEEDBACK_EMAIL"
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, shareFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            grantUriToResolvers(context, intent, uri)

            val chooser = Intent.createChooser(intent, "Отправить ZIP на $FEEDBACK_EMAIL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Keep read permission for the chosen app across the chooser hop.
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, emptyArray<Intent>())
            context.startActivity(chooser)
            Toast.makeText(
                context,
                "Выберите почту → $FEEDBACK_EMAIL (ZIP во вложении)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "sendTraceZip failed: ${e.message}", e)
            // Fallback: generic share so the file is not lost.
            try {
                val shareFile = ensureUnderCache(context, zip)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, "Отправьте этот ZIP на $FEEDBACK_EMAIL")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, shareFile.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                grantUriToResolvers(context, fallback, uri)
                context.startActivity(
                    Intent.createChooser(fallback, "Поделиться ZIP → $FEEDBACK_EMAIL")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.e(TAG, "fallback share failed: ${e2.message}", e2)
                Toast.makeText(context, "Не удалось открыть почту. Адрес: $FEEDBACK_EMAIL", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensureUnderCache(context: Context, zip: File): File {
        val cache = context.cacheDir
        if (zip.absolutePath.startsWith(cache.absolutePath)) return zip
        val copy = File(cache, zip.name)
        zip.copyTo(copy, overwrite = true)
        return copy
    }

    private fun grantUriToResolvers(context: Context, intent: Intent, uri: Uri) {
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in matches) {
            context.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
