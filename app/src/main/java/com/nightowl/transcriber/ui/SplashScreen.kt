package com.nightowl.transcriber.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightowl.transcriber.R
import kotlin.math.min

/** 启动动画总时长（ms） */
private const val SPLASH_MS = 1600

/**
 * 启动动画页：品牌青渐变底 + 透明底熊猫 logo + 三圈声波扩散 + 名称淡入。
 * 时间线跑完回调 [onFinish] 进入首页。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(SPLASH_MS, easing = LinearEasing))
        onFinish()
    }
    val t = anim.value
    // 归一化取时间段进度
    fun at(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    val logoA = at(0.06f, 0.34f)         // 熊猫淡入
    val logoScale = 0.82f + 0.18f * at(0.06f, 0.48f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E9C8F), Color(0xFF16BFA8)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(260.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val base = min(size.width, size.height) / 2f
                    // 三圈声波：熊猫外侧向外扩散，依次错开（无白底圆盘）
                    for (i in 0..2) {
                        val p = at(0.10f + i * 0.15f, 0.62f + i * 0.15f)
                        if (p <= 0f || p >= 1f) continue
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - p) * 0.42f),
                            radius = base * (0.50f + 0.44f * p),
                            center = c,
                            style = Stroke(width = (3f * (1f - p) + 1f).dp.toPx())
                        )
                    }
                }
                Image(
                    painter = painterResource(R.drawable.panda_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(126.dp)
                        .alpha(logoA)
                        .scale(logoScale)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "盘语",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .alpha(at(0.40f, 0.66f))
                    .offset(y = (18f * (1f - at(0.40f, 0.72f))).dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "语音转文字 · 全程离线",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 13.sp,
                modifier = Modifier.alpha(at(0.54f, 0.82f))
            )
        }
    }
}
