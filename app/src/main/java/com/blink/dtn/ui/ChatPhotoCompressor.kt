package com.blink.dtn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Chat photo compressor for internet-only PRIVATE_IMAGE (never mesh).
 * Target ~200–300 KiB JPEG.
 */
object ChatPhotoCompressor {
    const val MAX_EDGE_PX = 1280
    const val MAX_BYTES = 280_000
    private const val JPEG_QUALITY_START = 82

    fun compressToFile(context: Context, uri: Uri, messageId: String): File? {
        val bytes = compressToBytes(context, uri) ?: return null
        val dir = File(context.filesDir, "chat_media").also { it.mkdirs() }
        val out = File(dir, "$messageId.jpg")
        return try {
            out.writeBytes(bytes)
            out
        } catch (e: Exception) {
            android.util.Log.w("ChatPhoto", "write failed: ${e.message}")
            null
        }
    }

    fun writeBytes(context: Context, messageId: String, bytes: ByteArray): File? {
        val dir = File(context.filesDir, "chat_media").also { it.mkdirs() }
        val out = File(dir, "$messageId.jpg")
        return try {
            out.writeBytes(bytes)
            out
        } catch (e: Exception) {
            android.util.Log.w("ChatPhoto", "writeBytes failed: ${e.message}")
            null
        }
    }

    fun compressToBytes(context: Context, uri: Uri): ByteArray? {
        val source = decodeSampled(context, uri, MAX_EDGE_PX) ?: return null
        return try {
            val scaled = scaleDown(source, MAX_EDGE_PX)
            try {
                var q = JPEG_QUALITY_START
                var out = encodeJpeg(scaled, q) ?: return null
                while (out.size > MAX_BYTES && q > 35) {
                    q -= 8
                    out = encodeJpeg(scaled, q) ?: return null
                }
                if (out.size > MAX_BYTES) null else out
            } finally {
                if (scaled !== source && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(source.width, source.height)
        if (edge <= maxEdge) return source
        val scale = maxEdge.toFloat() / edge
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun encodeJpeg(bmp: Bitmap, quality: Int): ByteArray? {
        return try {
            val baos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)) return null
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val edge = max(bounds.outWidth, bounds.outHeight)
            while (edge / sample > maxEdge * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            android.util.Log.w("ChatPhoto", "decode failed: ${e.message}")
            null
        }
    }
}
