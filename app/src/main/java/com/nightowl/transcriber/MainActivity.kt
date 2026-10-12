package com.nightowl.transcriber

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.nightowl.transcriber.data.AppDatabase
import com.nightowl.transcriber.data.ExportFormat
import com.nightowl.transcriber.data.SettingsStore
import com.nightowl.transcriber.engine.ModelManager
import com.nightowl.transcriber.pipeline.ModelDownloadService
import com.nightowl.transcriber.pipeline.ModelDownloader
import com.nightowl.transcriber.pipeline.Status
import com.nightowl.transcriber.pipeline.TranscribeBus
import com.nightowl.transcriber.pipeline.TranscribeService
import com.nightowl.transcriber.ui.HomeScreen
import com.nightowl.transcriber.ui.ResultScreen
import com.nightowl.transcriber.ui.SettingsScreen
import com.nightowl.transcriber.ui.TranscribingScreen
import com.nightowl.transcriber.ui.theme.OfflineTranscriberTheme
import kotlinx.coroutines.launch

private enum class Screen { HOME, WORKING, RESULT, SETTINGS }

/**
 * 唯一 Activity：负责文件选择（SAF）、拖起前台服务、根据 Bus 状态驱动四屏切换。
 * 界面适配（3.5）通过 systemBarsPadding + 全部 dp/sp + Compose 自适应单双栏实现。
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private var screen by mutableStateOf(Screen.HOME)
    private var modelReady by mutableStateOf(false)

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        if (!modelReady) {
            Toast.makeText(this, "请先在设置中导入识别模型", Toast.LENGTH_LONG).show()
            screen = Screen.SETTINGS
            return@registerForActivityResult
        }
        val name = displayName(uri)
        try {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) { }
        screen = Screen.WORKING
        TranscribeService.start(this, uri.toString(), name)
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = ModelManager.importFile(this@MainActivity, uri, displayName(uri))
            if (ok) {
                modelReady = ModelManager.isReady(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (modelReady) "模型已就绪，可以开始转写" else "已导入，请继续导入其余文件（model / tokens / vad）",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this@MainActivity, "导入失败：请选择 model.int8.onnx、tokens.txt 或 silero_vad.onnx", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        ModelManager.ensureBaseFiles(this)          // 解包内置 tokens/vad
        modelReady = ModelManager.isReady(this)
        askNotifications()

        // 模型下载完成 → 刷新就绪状态
        lifecycleScope.launch {
            ModelDownloader.state.collect { st ->
                if (st is ModelDownloader.State.Done) {
                    modelReady = ModelManager.isReady(this@MainActivity)
                }
            }
        }

        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = "system")
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            OfflineTranscriberTheme(darkTheme = dark) {
                AppSurface()
            }
        }
    }

    @Composable
    private fun AppSurface() {
        val db = AppDatabase.get(this)
        val recent by db.transcriptDao().recent().collectAsState(initial = emptyList())
        val status by TranscribeBus.status.collectAsState()
        val resultText by TranscribeBus.resultText.collectAsState()
        val segments by TranscribeBus.segments.collectAsState()
        val fileName by TranscribeBus.fileName.collectAsState()
        val styleCode by settings.transcriptStyle.collectAsState(initial = ExportFormat.DIALOG.code)
        val showTs by settings.showTimestamps.collectAsState(initial = true)
        val autoSave by settings.autoSave.collectAsState(initial = true)
        val themeMode by settings.themeMode.collectAsState(initial = "system")

        // 状态驱动：转写完成自动进入结果页
        androidx.compose.runtime.LaunchedEffect(status) {
            if (status is Status.Done && screen == Screen.WORKING) screen = Screen.RESULT
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = screen, label = "screen") { target ->
                when (target) {
                    Screen.HOME -> HomeScreen(
                        recent = recent,
                        onPickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenResult = { item ->
                            TranscribeBus.fileName.value = item.fileName
                            TranscribeBus.resultText.value = item.text
                            TranscribeBus.segments.value =
                                listOf(com.nightowl.transcriber.engine.Segment(0, item.text, 0, item.durationMs))
                            TranscribeBus.status.value = Status.Done
                            screen = Screen.RESULT
                        }
                    )
                    Screen.WORKING -> TranscribingScreen(
                        onCancel = {
                            TranscribeService.stop(this)
                            screen = Screen.HOME
                        },
                        onBackground = { moveTaskToBack(true) }
                    )
                    Screen.RESULT -> {
                        val latest = recent.firstOrNull { it.fileName == fileName }
                        ResultScreen(
                            title = fileName.ifBlank { "转写结果" },
                            initialText = resultText,
                            segments = segments,
                            elapsedText = TranscribeBus.elapsedText.collectAsState(initial = "").value,
                            savePath = latest?.savePath ?: "未记录",
                            format = ExportFormat.from(styleCode),
                            onBack = { screen = Screen.HOME }
                        )
                    }
                    Screen.SETTINGS -> {
                        val dlState by ModelDownloader.state.collectAsState()
                        SettingsScreen(
                            modelReady = modelReady,
                            themeMode = themeMode,
                            styleCode = styleCode,
                            showTimestamps = showTs,
                            autoSave = autoSave,
                            downloadState = dlState,
                            onDownloadModels = {
                                if (modelReady) {
                                    // 已就绪时点击 → 仅提示已就绪（避免误重下）
                                    Toast.makeText(this, "模型已就绪，无需下载", Toast.LENGTH_SHORT).show()
                                } else {
                                    ModelDownloadService.start(this)
                                }
                            },
                            onCancelDownload = { ModelDownloader.requestCancel() },
                            onPickModel = { pickModel.launch(arrayOf("*/*")) },
                            onThemeChange = { lifecycleScope.launch { settings.setTheme(it) } },
                            onStyleChange = { lifecycleScope.launch { settings.setStyle(it) } },
                            onTimestampsChange = { lifecycleScope.launch { settings.setTimestamps(it) } },
                            onAutoSaveChange = { lifecycleScope.launch { settings.setAutoSave(it) } },
                            onBack = { screen = Screen.HOME }
                        )
                    }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment
        } catch (_: Throwable) { uri.lastPathSegment } ?: "未知文件"
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
