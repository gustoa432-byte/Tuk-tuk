package com.blink.dtn.ui

import androidx.compose.ui.graphics.Color
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.BackgroundDark
import com.blink.dtn.ui.theme.TextPrimary

/** Maps expedition cosmetics to visible UI tokens. */
object CosmeticApply {
    fun nickColor(id: String): Color = when (id) {
        "nick_lime" -> AccentLime
        else -> TextPrimary
    }

    fun frameColor(id: String): Color? = when (id) {
        "frame_lime" -> AccentLime
        "frame_gold" -> Color(0xFFE0B84A)
        else -> null
    }

    fun backdropTint(themeId: String): Color = when (themeId) {
        "theme_night_road" -> Color(0xFF0B1220)
        else -> BackgroundDark
    }

    fun dinoBadge(dinoId: String): String = when (dinoId) {
        "dino_rare" -> "🦕✨"
        else -> "🦕"
    }
}
