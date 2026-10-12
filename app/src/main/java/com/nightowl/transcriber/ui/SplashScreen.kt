package com.nightowl.transcriber.ui

import android.graphics.drawable.Animatable as AndroidAnimatable
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nightowl.transcriber.R

/** 启动动画总时长（ms） */
private const val SPLASH_MS = 2100

/**
 * 启动动画页：品牌青渐变底 + 矢量熊猫「手绘描边」动画（AnimatedVectorDrawable，
 * 轮廓按 头→耳→眼圈→眼珠→鼻→嘴 逐笔画出）+ 名称淡入。
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E9C8F), Color(0xFF16BFA8)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 矢量描边熊猫（View 层承载 AnimatedVectorDrawable，播放一次）
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(ctx.getDrawable(R.drawable.panda_splash_anim))
                    }
                },
                update = { iv ->
                    (iv.drawable as? AndroidAnimatable)?.let { if (!it.isRunning) it.start() }
                },
                modifier = Modifier.size(232.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "盘语",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .alpha(at(0.70f, 0.86f))
                    .offset(y = (18f * (1f - at(0.70f, 0.92f))).dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "语音转文字 · 全程离线",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 13.sp,
                modifier = Modifier.alpha(at(0.80f, 0.96f))
            )
        }
    }
}
