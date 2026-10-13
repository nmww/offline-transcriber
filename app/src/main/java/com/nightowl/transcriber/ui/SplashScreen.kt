package com.nightowl.transcriber.ui

import com.nightowl.transcriber.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.graphics.vector.rememberAnimatedVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 启动动画总时长（ms） */
private const val SPLASH_MS = 2100

/**
 * 启动页：品牌青渐变底 + 矢量熊猫「逐笔描边」出场（panda_splash_anim 动画资源），
 * 描边画完后名称淡入。矢量绘制，任意分辨率都清晰无锯齿。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val anim = remember { Animatable(0f) }
    val avd = AnimatedImageVector.animatedVectorResource(R.drawable.panda_splash_anim)
    var atEnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        atEnd = true
        anim.animateTo(1f, tween(SPLASH_MS, easing = LinearEasing))
        onFinish()
    }
    val g = anim.value
    val textAlpha = ((g - 0.6f) / 0.35f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF00B3A4), Color(0xFF16BFA8)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = avd.rememberAnimatedVectorPainter(atEnd),
                contentDescription = "盘语",
                modifier = Modifier.size(220.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "盘语",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.alpha(textAlpha)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "语音转文字 · 全程离线",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 13.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }
    }
}
