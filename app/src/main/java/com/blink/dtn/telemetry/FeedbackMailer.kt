package com.blink.dtn.telemetry

import android.content.Context
import android.content.Intent
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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(
                Intent.EXTRA_TEXT,
                "Во вложении чёрный ящик MessageTrace / Delivery Observatory.\nУстройство готово к разбору без Android Studio."
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "Отправить отчёт на $FEEDBACK_EMAIL")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendTraceZip failed: ${e.message}")
            Toast.makeText(context, "Не удалось открыть почту. Адрес: $FEEDBACK_EMAIL", Toast.LENGTH_LONG).show()
        }
    }
}
