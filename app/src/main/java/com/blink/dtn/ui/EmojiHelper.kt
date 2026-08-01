package com.blink.dtn.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blink.dtn.R
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.Typography
import kotlin.math.roundToInt

/**
 * Local dino emoji pack — shortcodes travel on the wire as plain text;
 * drawables never leave the device (zero graphic overhead on BLE).
 *
 * Examples: `:)` `:D` `:(` `:sad:` `:love:` `:cool:`
 */
object EmojiHelper {

    val SHORTCODES: LinkedHashMap<String, Int> = linkedMapOf(
        ":sad:" to R.drawable.emoji_dino_sad,
        ":love:" to R.drawable.emoji_dino_love,
        ":angry:" to R.drawable.emoji_dino_angry,
        ":cool:" to R.drawable.emoji_dino_cool,
        ":sleep:" to R.drawable.emoji_dino_sleep,
        ":wow:" to R.drawable.emoji_dino_wow,
        ":laugh:" to R.drawable.emoji_dino_laugh,
        ":smile:" to R.drawable.emoji_dino_smile,
        ":D" to R.drawable.emoji_dino_laugh,
        ":)" to R.drawable.emoji_dino_smile,
        ":(" to R.drawable.emoji_dino_sad,
        "<3" to R.drawable.emoji_dino_love,
    )

    private val pattern: Regex by lazy {
        val escaped = SHORTCODES.keys.joinToString("|") { Regex.escape(it) }
        Regex(escaped)
    }

    fun containsShortcode(text: String): Boolean = pattern.containsMatchIn(text)

    /**
     * Compose-safe parse: shortcodes → inline placeholders + drawable map.
     * Prefer this over [parseEmojis] inside LazyColumn (no AndroidView / ImageSpan).
     */
    fun buildComposeEmoji(
        text: String,
        emojiSize: TextUnit = 20.sp
    ): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
        if (text.isEmpty()) {
            return androidx.compose.ui.text.AnnotatedString("") to emptyMap()
        }
        val matches = pattern.findAll(text).toList()
        if (matches.isEmpty()) {
            return androidx.compose.ui.text.AnnotatedString(text) to emptyMap()
        }
        val inline = linkedMapOf<String, InlineTextContent>()
        var nextId = 0
        val annotated = buildAnnotatedString {
            var last = 0
            for (match in matches) {
                if (match.range.first > last) {
                    append(text.substring(last, match.range.first))
                }
                val resId = SHORTCODES[match.value]
                if (resId == null) {
                    append(match.value)
                } else {
                    val id = "dino_$nextId"
                    nextId++
                    inline[id] = InlineTextContent(
                        Placeholder(
                            width = emojiSize,
                            height = emojiSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                        )
                    ) {
                        Image(
                            painter = painterResource(id = resId),
                            contentDescription = match.value,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    appendInlineContent(id, match.value)
                }
                last = match.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
        return annotated to inline
    }

    /** Spannable / ImageSpan path for classic TextView (API-safe). */
    fun parseEmojis(
        text: String,
        context: Context,
        textSizePx: Float = 40f
    ): Spannable {
        if (text.isEmpty()) return SpannableStringBuilder("")
        val builder = SpannableStringBuilder(text)
        for (match in pattern.findAll(text).toList().asReversed()) {
            val resId = SHORTCODES[match.value] ?: continue
            val drawable = scaledDrawable(context, resId, textSizePx) ?: continue
            builder.setSpan(
                imageSpan(drawable),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
    }

    fun applyTo(textView: TextView, plain: String) {
        textView.setText(
            parseEmojis(plain, textView.context, textView.textSize),
            TextView.BufferType.SPANNABLE
        )
    }

    fun reparseEditable(editable: android.text.Editable, context: Context, textSizePx: Float) {
        val plain = editable.toString()
        editable.getSpans(0, editable.length, ImageSpan::class.java).forEach {
            editable.removeSpan(it)
        }
        for (match in pattern.findAll(plain)) {
            val resId = SHORTCODES[match.value] ?: continue
            val drawable = scaledDrawable(context, resId, textSizePx) ?: continue
            editable.setSpan(
                imageSpan(drawable),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** ImageSpan(Drawable, align) is API 29+ — use single-arg ctor on older devices. */
    private fun imageSpan(drawable: Drawable): ImageSpan {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM)
        } else {
            @Suppress("DEPRECATION")
            ImageSpan(drawable)
        }
    }

    private fun scaledDrawable(context: Context, resId: Int, textSizePx: Float): Drawable? {
        val d = ContextCompat.getDrawable(context, resId)?.mutate() ?: return null
        val size = (textSizePx * 1.15f).roundToInt().coerceAtLeast(16)
        d.setBounds(0, 0, size, size)
        return d
    }
}

/**
 * Chat bubble text with local dino shortcodes — pure Compose (safe in LazyColumn).
 */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    style: TextStyle = Typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE
) {
    val fontSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 16.sp
    val emojiSize = (fontSize.value * 1.15f).sp
    val (annotated, inline) = remember(text, emojiSize) {
        EmojiHelper.buildComposeEmoji(text, emojiSize)
    }
    androidx.compose.material3.Text(
        text = annotated,
        inlineContent = inline,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines
    )
}
