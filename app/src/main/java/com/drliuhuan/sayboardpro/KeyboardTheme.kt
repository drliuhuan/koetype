package com.drliuhuan.sayboardpro

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 按键盘主题构建配色（纯函数）：设置页键盘预览与真实键盘共用，保证预览所见即所得。
 *
 * - 浅色（默认）：保持现有外观（绿 #2E7D32 / 橙 / 浅灰底 / 白按键）；
 * - 深色：深底 + 浅绿主色；
 * - 自定义：用户设置的前景（文字/图标）与背景色，按键色按背景明暗自动微调；
 * - 跟随系统：按系统深色模式二选一。
 *
 * @param keyboardTheme [AppPrefs.THEME_SYSTEM]/[AppPrefs.THEME_LIGHT]/[AppPrefs.THEME_DARK]/[AppPrefs.THEME_CUSTOM]
 * @param foregroundArgb 自定义前景色（文字/图标，ARGB int）
 * @param backgroundArgb 自定义背景色（底色，ARGB int）
 * @param systemDark 系统是否处于深色模式（THEME_SYSTEM 时使用）
 */
fun keyboardThemeColors(
    keyboardTheme: String,
    foregroundArgb: Int,
    backgroundArgb: Int,
    systemDark: Boolean
): Colors {
    return when (keyboardTheme) {
        AppPrefs.THEME_DARK -> darkColors(
            primary = Color(0xFF81C784),
            secondary = Color(0xFFFFB74D),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        )
        AppPrefs.THEME_LIGHT -> lightColors(
            primary = Color(0xFF2E7D32),
            secondary = Color(0xFFFF9800),
            background = Color(0xFFF5F5F5),
            surface = Color.White
        )
        AppPrefs.THEME_CUSTOM -> {
            val bg = Color(backgroundArgb)
            val fg = Color(foregroundArgb)
            val isLightBg = bg.luminance() > 0.5f
            val surface = if (isLightBg) lerp(bg, Color.Black, 0.05f) else lerp(bg, Color.White, 0.08f)
            lightColors(
                primary = if (isLightBg) Color(0xFF2E7D32) else Color(0xFF81C784),
                secondary = Color(0xFFFF9800),
                background = bg,
                surface = surface,
                onSurface = fg,
                onBackground = fg
            )
        }
        else -> if (systemDark) {
            darkColors(
                primary = Color(0xFF81C784),
                secondary = Color(0xFFFFB74D),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E)
            )
        } else {
            lightColors(
                primary = Color(0xFF2E7D32),
                secondary = Color(0xFFFF9800),
                background = Color(0xFFF5F5F5),
                surface = Color.White
            )
        }
    }
}
