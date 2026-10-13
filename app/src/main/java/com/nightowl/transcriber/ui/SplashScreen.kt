package com.nightowl.transcriber.ui

import android.graphics.Color as GColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 启动动画总时长（ms） */
private const val SPLASH_MS = 2100

/** 配色 */
private val FACE = GColor.WHITE
private val DARK = GColor.parseColor("#0B3B36") // 深青黑，比纯黑柔和、呼应品牌

/**
 * 启动页：品牌青渐变底 + 熊猫「手绘描边」出场（轮廓按 头→耳→眼圈→眼珠→鼻→嘴 逐笔画出），
 * 描边收尾后五官柔和填充淡入，最后名称淡入。熊猫为精致填充造型（白脸 + 深青黑五官）。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(SPLASH_MS, easing = LinearEasing))
        onFinish()
    }
    val g = anim.value                       // 总进度 0..1（2100ms）
    val drawT = (g / 0.72f).coerceIn(0f, 1f) // 前 72% 时间用于描边（≈1512ms）
    val fillAlpha = ((g - 0.72f) / 0.28f).coerceIn(0f, 1f)
    val textAlpha = ((g - 0.60f) / 0.30f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E9C8F), Color(0xFF16BFA8)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(240.dp)) {
                drawPanda(drawT, fillAlpha)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "盘语",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(textAlpha)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "语音转文字 · 全程离线",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 13.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }
    }
}

/** 单位空间（中心 0、半径 1）下的椭圆路径，可旋转 */
private fun unitEllipse(cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Float): Path {
    val p = Path()
    p.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry))
    if (rotDeg != 0f) {
        val m = Matrix()
        m.postRotate(rotDeg, cx, cy)
        p.transform(m)
    }
    return p
}

/** 单位空间下的二次贝塞尔（嘴） */
private fun unitQuad(p0: Pair<Float, Float>, ctrl: Pair<Float, Float>, p1: Pair<Float, Float>): Path {
    val p = Path()
    p.moveTo(p0.first, p0.second)
    p.quadTo(ctrl.first, ctrl.second, p1.first, p1.second)
    return p
}

/** 单部位：轮廓路径 + 填充色（null = 仅描边）+ 描边时间窗 [tStart,tEnd] */
private data class PandaPart(
    val path: Path,
    val fill: GColor?,
    val tStart: Float,
    val tEnd: Float
)

/** 熊猫几何（与图标一致的居中矢量），按出场顺序排布 */
private val PANDA_PARTS = listOf(
    PandaPart(unitEllipse(0f, 0.04f, 0.60f, 0.55f), FACE, 0.00f, 0.30f),       // 脸
    PandaPart(unitEllipse(-0.47f, -0.45f, 0.17f, 0.17f), DARK, 0.30f, 0.42f),  // 左耳
    PandaPart(unitEllipse(0.47f, -0.45f, 0.17f, 0.17f), DARK, 0.42f, 0.54f),   // 右耳
    PandaPart(unitEllipse(-0.26f, -0.04f, 0.155f, 0.19f, -12f), DARK, 0.54f, 0.66f), // 左眼圈
    PandaPart(unitEllipse(0.26f, -0.04f, 0.155f, 0.19f, 12f), DARK, 0.66f, 0.78f),  // 右眼圈
    PandaPart(unitEllipse(-0.25f, -0.03f, 0.058f, 0.070f), DARK, 0.78f, 0.84f), // 左眼珠
    PandaPart(unitEllipse(0.25f, -0.03f, 0.058f, 0.070f), DARK, 0.84f, 0.90f),  // 右眼珠
    PandaPart(unitEllipse(0f, 0.25f, 0.070f, 0.050f), DARK, 0.90f, 0.95f),      // 鼻
    PandaPart(unitQuad(Pair(-0.13f, 0.32f), Pair(0f, 0.48f), Pair(0.13f, 0.32f)), null, 0.95f, 1.00f), // 嘴
)

private fun DrawScope.drawPanda(progress: Float, fillAlpha: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    val cx = size.width / 2f
    val cy = size.height / 2f
    val R = minOf(size.width, size.height) / 2f * 0.92f

    // 单位空间 → 画布：先缩放再平移
    val m = Matrix().apply { postScale(R, R); postTranslate(cx, cy) }

    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = R * 0.022f
        color = GColor.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val fillPaintAlpha = (fillAlpha * 255f).toInt().coerceIn(0, 255)

    val pm = PathMeasure()
    val seg = Path()
    val fillPath = Path()

    for (part in PANDA_PARTS) {
        val lp = ((progress - part.tStart) / (part.tEnd - part.tStart)).coerceIn(0f, 1f)
        if (lp <= 0f) continue

        // 描边：按进度沿路径截取线段（手绘出场效果）
        pm.setPath(part.path, false)
        val total = pm.length
        seg.reset()
        if (total > 0f) pm.getSegment(0f, total * lp, seg, true)
        seg.transform(m)
        canvas.drawPath(seg, stroke)

        // 填充：描边主体完成后，五官柔和淡入
        if (progress > 0.72f && part.fill != null) {
            fill.color = part.fill
            fill.alpha = fillPaintAlpha
            fillPath.reset()
            fillPath.addPath(part.path)
            fillPath.transform(m)
            canvas.drawPath(fillPath, fill)
        }
    }
}
