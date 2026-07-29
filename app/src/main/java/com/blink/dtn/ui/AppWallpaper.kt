package com.blink.dtn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Local app wallpaper over a black base.
 * [opacity] = how strongly the photo shows (0 = black only).
 * Draft photo/opacity preview live until [commitDraft].
 */
object AppWallpaper {

    private const val PREFS_NAME = "blink_prefs"
    private const val KEY_CUSTOM = "wallpaper_custom"
    private const val KEY_OPACITY = "wallpaper_opacity"
    private const val FILE_NAME = "wallpaper.jpg"
    private const val MAX_EDGE_PX = 1920
    private const val JPEG_QUALITY = 82
    const val DEFAULT_OPACITY = 0.45f

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private val _opacity = MutableStateFlow(DEFAULT_OPACITY)
    val opacity: StateFlow<Float> = _opacity

    private val _draftBitmap = MutableStateFlow<Bitmap?>(null)
    val draftBitmap: StateFlow<Bitmap?> = _draftBitmap

    private val _draftOpacity = MutableStateFlow<Float?>(null)
    val draftOpacity: StateFlow<Float?> = _draftOpacity

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_CUSTOM, false) && file(context).exists()
        if (!enabled && prefs.getBoolean(KEY_CUSTOM, false)) {
            prefs.edit().putBoolean(KEY_CUSTOM, false).apply()
        }
        _opacity.value = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0f, 1f)
        if (_revision.value == 0L && enabled) {
            _revision.value = file(context).lastModified().coerceAtLeast(1L)
        }
    }

    fun hasCustom(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM, false) && file(context).exists()
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun effectiveOpacity(): Float = (_draftOpacity.value ?: _opacity.value).coerceIn(0f, 1f)

    fun setDraftOpacity(value: Float) {
        _draftOpacity.value = value.coerceIn(0f, 1f)
    }

    /** Load gallery photo into draft only — not written until [commitDraft]. */
    fun loadDraftFromUri(context: Context, uri: Uri): Boolean {
        val bitmap = decodeSampled(context, uri, MAX_EDGE_PX) ?: return false
        val scaled = scaleDown(bitmap, MAX_EDGE_PX)
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        val old = _draftBitmap.value
        _draftBitmap.value = scaled
        if (old != null && old !== scaled && !old.isRecycled) old.recycle()
        if (_draftOpacity.value == null) {
            _draftOpacity.value = _opacity.value
        }
        return true
    }

    fun commitDraft(context: Context): Boolean {
        val draft = _draftBitmap.value
        val draftOp = _draftOpacity.value ?: _opacity.value
        try {
            if (draft != null) {
                FileOutputStream(file(context)).use { out ->
                    if (!draft.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CUSTOM, true)
                    .putFloat(KEY_OPACITY, draftOp.coerceIn(0f, 1f))
                    .apply()
                _opacity.value = draftOp.coerceIn(0f, 1f)
                _revision.value = System.currentTimeMillis()
            } else if (_draftOpacity.value != null) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putFloat(KEY_OPACITY, draftOp.coerceIn(0f, 1f)).apply()
                _opacity.value = draftOp.coerceIn(0f, 1f)
            } else {
                return false
            }
            discardDraft(keepOpacityPreview = false)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun discardDraft(keepOpacityPreview: Boolean = false) {
        val old = _draftBitmap.value
        _draftBitmap.value = null
        if (old != null && !old.isRecycled) old.recycle()
        if (!keepOpacityPreview) {
            _draftOpacity.value = null
        }
    }

    fun clear(context: Context) {
        discardDraft()
        file(context).delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CUSTOM, false).apply()
        _revision.value = System.currentTimeMillis()
    }

    fun loadBitmap(context: Context): Bitmap? {
        if (!hasCustom(context)) return null
        return try {
            BitmapFactory.decodeFile(file(context).absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (longest / sample > maxEdge * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}
