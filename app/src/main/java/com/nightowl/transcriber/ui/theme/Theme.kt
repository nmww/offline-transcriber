package com.nightowl.transcriber.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 排版 Token（规格书 3.2）：大标题 28/34 Bold，标题 20/26 Semibold，正文 16/26，脚注 13/18 */
val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
)

private val LightScheme = lightColorScheme(
    background = Tokens.BgLight,
    surface = Tokens.SurfaceLight,
    surfaceVariant = Tokens.SurfaceLight,
    primary = Tokens.PrimaryLight,
    onPrimary = Tokens.SurfaceLight,
    onBackground = Tokens.TextPrimaryLight,
    onSurface = Tokens.TextPrimaryLight,
    outline = Tokens.SeparatorLight
)

private val DarkScheme = darkColorScheme(
    background = Tokens.BgDark,
    surface = Tokens.SurfaceDark,
    surfaceVariant = Tokens.SurfaceDark,
    primary = Tokens.PrimaryDark,
    onPrimary = Tokens.BgDark,
    onBackground = Tokens.TextPrimaryDark,
    onSurface = Tokens.TextPrimaryDark,
    outline = Tokens.SeparatorDark
)

/**
 * 全 Token 双色主题：传入 darkTheme（跟随系统或设置页指定）即可整站换装。
 * 颜色只允许从这两个 scheme 取，禁止在页面里写 Color(0xFFxxxxxx)。
 */
@Composable
fun OfflineTranscriberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}

/** 语义扩展：次要文字色（Material3 无对应槽位，统一从此处取，保证双色一致） */
@Composable
fun secondaryTextColor(): androidx.compose.ui.graphics.Color =
    if (isSystemInDarkTheme()) Tokens.TextSecondaryDark else Tokens.TextSecondaryLight
