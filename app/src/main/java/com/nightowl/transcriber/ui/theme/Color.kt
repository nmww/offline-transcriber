package com.nightowl.transcriber.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计 Token —— 严格按《离线转写App-完整开发规格书》3.2 定义。
 *
 * ⚠️ 术语澄清：这里的 "Token" 是【设计 Token】（Design Token）= 界面颜色/字号的命名常量，
 *    与大模型 API 的 token（密钥/计费单位）毫无关系。本应用完全离线，不需要任何 API Key。
 *
 * 每个 Token 均备【浅色 / 深色】两套值：切深色模式时整套自动替换，
 * 任何地方都不允许直接写死色值（写死即视为缺陷）。
 */
object Tokens {
    // 背景
    val BgLight = Color(0xFFF2F2F7)
    val BgDark = Color(0xFF000000)

    // 卡片 / 列表容器
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF1C1C1E)

    // 主操作色（iOS 蓝）
    val PrimaryLight = Color(0xFF007AFF)
    val PrimaryDark = Color(0xFF0A84FF)

    // 主文字 / 次要文字
    val TextPrimaryLight = Color(0xFF1C1C1E)
    val TextPrimaryDark = Color(0xFFF5F5F7)
    val TextSecondaryLight = Color(0xFF8E8E93)
    val TextSecondaryDark = Color(0xFF98989F)

    // 分割线
    val SeparatorLight = Color(0xFFE5E5EA)
    val SeparatorDark = Color(0xFF38383A)

    // 完成 / 危险
    val SuccessLight = Color(0xFF34C759)
    val SuccessDark = Color(0xFF30D158)
    val DangerLight = Color(0xFFFF3B30)
    val DangerDark = Color(0xFFFF453A)

    // 形状：卡片 20dp / 大按钮 28dp / 输入区 16dp
    const val RadiusCard = 20
    const val RadiusButton = 28
    const val RadiusInput = 16

    // 间距体系
    const val SpacingXs = 8
    const val SpacingSm = 12
    const val SpacingMd = 16
    const val SpacingLg = 24
    const val SpacingXl = 32
}
