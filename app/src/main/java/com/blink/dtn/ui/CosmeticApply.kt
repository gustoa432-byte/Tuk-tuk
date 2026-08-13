package com.blink.dtn.ui

import androidx.compose.ui.graphics.Color
import com.blink.dtn.BuildConfig
import com.blink.dtn.ui.theme.AccentLime
import com.blink.dtn.ui.theme.BackgroundDark
import com.blink.dtn.ui.theme.TextPrimary

/**
 * Maps legacy expedition cosmetics to visible UI tokens.
 *
 * Under [BuildConfig.QQ_CORE_ONLY] every mapping collapses to the neutral default, so
 * cosmetics unlocked by an older build cannot tint Qq (prefs may still exist on disk).
 */
object CosmeticApply {
    fun nickColor(id: String): Color {
        if (BuildConfig.QQ_CORE_ONLY) return TextPrimary
        return when (id) {
            "nick_lime" -> AccentLime
            else -> TextPrimary
        }
    }

    fun frameColor(id: String): Color? {
        if (BuildConfig.QQ_CORE_ONLY) return null
        return when (id) {
            "frame_lime" -> AccentLime
            "frame_gold" -> Color(0xFFE0B84A)
            else -> null
        }
    }

    fun backdropTint(themeId: String): Color {
        if (BuildConfig.QQ_CORE_ONLY) return BackgroundDark
        return when (themeId) {
            "theme_night_road" -> Color(0xFF0B1220)
            else -> BackgroundDark
        }
    }

    fun dinoBadge(dinoId: String): String {
        if (BuildConfig.QQ_CORE_ONLY) return ""
        return when (dinoId) {
            "dino_rare" -> "🦕✨"
            else -> "🦕"
        }
    }
}
