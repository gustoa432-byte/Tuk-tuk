package com.blink.dtn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Mesh-friendly avatar pipeline: center-crop to square, downscale, JPEG-compress.
 * Output is tiny enough for Room + optional contact-QR embedding.
 */
object AvatarCompressor {

    /** Edge length stored in DB / shown in UI (px). */
    const val EDGE_PX = 96

    /** Prefer this JPEG quality; may drop further to fit QR budget. */
    const val JPEG_QUALITY = 58

    /** Hard cap for DB blob (still tiny). */
    const val MAX_DB_BYTES = 3_500

    /**
     * Max raw JPEG bytes allowed inside contact QR (`av` field).
     * Keep well under typical QR capacity so scans stay reliable.
     */
    const val MAX_QR_AVATAR_BYTES = 1_200

    fun compressFromUri(context: Context, uri: Uri): ByteArray? {
        val source = decodeSampled(context, uri, EDGE_PX * 2) ?: return null
        return try {
            compressBitmap(source)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    fun compressBitmap(source: Bitmap): ByteArray? {
        val square = centerCropSquare(source)
        val scaled = if (square.width != EDGE_PX || square.height != EDGE_PX) {
            Bitmap.createScaledBitmap(square, EDGE_PX, EDGE_PX, true)
        } else {
            square
        }
        try {
            var quality = JPEG_QUALITY
            var bytes = encodeJpeg(scaled, quality) ?: return null
            while (bytes.size > MAX_DB_BYTES && quality > 28) {
                quality -= 8
                bytes = encodeJpeg(scaled, quality) ?: return null
            }
            if (bytes.size > MAX_DB_BYTES) return null
            return bytes
        } finally {
            if (scaled !== square && !scaled.isRecycled) scaled.recycle()
            if (square !== source && !square.isRecycled) square.recycle()
        }
    }

    /**
     * Shrink further until the blob fits QR, or return null if impossible.
     */
    fun fitForQr(blob: ByteArray): ByteArray? {
        if (blob.size <= MAX_QR_AVATAR_BYTES) return blob
        val bmp = BitmapFactory.decodeByteArray(blob, 0, blob.size) ?: return null
        try {
            var edge = EDGE_PX
            while (edge >= 48) {
                val scaled = Bitmap.createScaledBitmap(bmp, edge, edge, true)
                try {
                    var q = 45
                    while (q >= 25) {
                        val out = encodeJpeg(scaled, q) ?: break
                        if (out.size <= MAX_QR_AVATAR_BYTES) return out
                        q -= 8
                    }
                } finally {
                    if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                }
                edge -= 16
            }
            return null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    fun decodeToBitmap(blob: ByteArray?): Bitmap? {
        if (blob == null || blob.isEmpty()) return null
        return BitmapFactory.decodeByteArray(blob, 0, blob.size)
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        if (x == 0 && y == 0 && src.width == src.height) return src
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)) {
            return null
        }
        return stream.toByteArray()
    }

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val longest = max(w, h)
        while (longest / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}
