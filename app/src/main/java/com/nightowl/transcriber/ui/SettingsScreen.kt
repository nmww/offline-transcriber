package com.nightowl.transcriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.nightowl.transcriber.engine.ModelManager
import com.nightowl.transcriber.data.ExportFormat

/** 设置页（规格书 3.3 ④）：iOS 分组列表风 */
@Composable
fun SettingsScreen(
    modelReady: Boolean,
    themeMode: String,
    styleCode: String,
    showTimestamps: Boolean,
    autoSave: Boolean,
    downloadState: com.nightowl.transcriber.pipeline.ModelDownloader.State,
    onDownloadModels: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickModel: () -> Unit,
    onThemeChange: (String) -> Unit,
    onStyleChange: (String) -> Unit,
    onTimestampsChange: (Boolean) -> Unit,
    onAutoSaveChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Spacer(modifier = Modifier.size(12.dp))

        Group {
            RowTile(
                title = "识别模型",
                subtitle = if (modelReady) "SenseVoice INT8 · 已载入" else "未就绪：下载或手动导入",
                onClick = onPickModel
            )
            when (val st = downloadState) {
                is com.nightowl.transcriber.pipeline.ModelDownloader.State.Downloading -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "下载中 ${st.downloadedMB}MB / ${st.totalMB}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { st.fileProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            "点击取消（已下载部分支持断点续传）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { onCancelDownload() }
                        )
                    }
                }
                else -> {
                    RowTile(
                        title = if (modelReady) "重新下载模型" else "⬇ 一键下载模型（228MB）",
                        subtitle = when (st) {
                            is com.nightowl.transcriber.pipeline.ModelDownloader.State.Failed -> "上次失败：${st.reason}，点击重试"
                            is com.nightowl.transcriber.pipeline.ModelDownloader.State.Done -> "已完成，点击可重新下载"
                            else -> "WiFi 下约 2~5 分钟，支持断点续传"
                        },
                        onClick = onDownloadModels
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(24.dp))

        Group {
            RowTile(
                title = "排版",
                subtitle = ExportFormat.from(styleCode).label,
                onClick = {
                    val next = when (styleCode) {
                        "dialog" -> "plain"
                        "plain" -> "line"
                        else -> "dialog"
                    }
                    onStyleChange(next)
                }
            )
            SwitchTile("显示时间段头", showTimestamps, onTimestampsChange)
            SwitchTile("转写完成自动保存", autoSave, onAutoSaveChange)
        }

        Spacer(modifier = Modifier.size(24.dp))

        // ── 软件更新（应用内热更新）──
        val updateState by com.nightowl.transcriber.pipeline.UpdateManager.state.collectAsState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Group {
            RowTile(
                title = "当前版本",
                subtitle = "v" + com.nightowl.transcriber.BuildConfig.VERSION_NAME,
                onClick = {}
            )
            when (val us = updateState) {
                is com.nightowl.transcriber.pipeline.UpdateManager.State.Downloading -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "下载更新 ${us.downloadedMb}MB / ${us.totalMb}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { us.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is com.nightowl.transcriber.pipeline.UpdateManager.State.ReadyToInstall -> {
                    RowTile(
                        title = "✅ 新版本已就绪",
                        subtitle = "点击安装 ${us.info.tag}（覆盖安装，模型与记录全部保留）",
                        onClick = { com.nightowl.transcriber.pipeline.UpdateManager.install(ctx, us.apkFile) }
                    )
                }
                is com.nightowl.transcriber.pipeline.UpdateManager.State.Available -> {
                    RowTile(
                        title = "发现新版本 ${us.info.tag}",
                        subtitle = "APK ${us.info.sizeMb}MB，点击下载安装",
                        onClick = { scope.launch { com.nightowl.transcriber.pipeline.UpdateManager.download(ctx, us.info) } }
                    )
                }
                is com.nightowl.transcriber.pipeline.UpdateManager.State.Failed -> {
                    RowTile(
                        title = "检查更新",
                        subtitle = "上次失败：${us.reason}，点击重试",
                        onClick = { scope.launch { com.nightowl.transcriber.pipeline.UpdateManager.check(ctx) } }
                    )
                }
                else -> {
                    RowTile(
                        title = if (updateState is com.nightowl.transcriber.pipeline.UpdateManager.State.Checking) "正在检查…" else "检查更新",
                        subtitle = "自动从 GitHub 拉取最新版本，模型不用重下",
                        onClick = { scope.launch { com.nightowl.transcriber.pipeline.UpdateManager.check(ctx) } }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(24.dp))

        Group {
            RowTile(
                title = "深色模式",
                subtitle = when (themeMode) {
                    "light" -> "关闭"
                    "dark" -> "开启"
                    else -> "跟随系统"
                },
                onClick = {
                    val next = when (themeMode) {
                        "system" -> "light"
                        "light" -> "dark"
                        else -> "system"
                    }
                    onThemeChange(next)
                }
            )
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun RowTile(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SwitchTile(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
