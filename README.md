# 离线转写 OfflineTranscriber

> 一个 Android 离线应用：选择手机里的视频/音频，把声音转成文字，在**原文件同目录**生成 `.转写.txt`（对话语段排版）和 `.srt` 字幕文件。
> **转写核心全程离线、零上传、不需要任何 API Key。**（联网仅限两处：设置里下载识别模型、检查应用更新）

---

## 手机安装（在线打包）

本仓库用 GitHub Actions 自动打包签名 APK：

1. 打开仓库 → **Actions → Build Release APK → Run workflow**（手动触发）
2. CI 自动：版本号递增（v1.0.N）→ Gradle 构建 → 签名 → 发布到 **Releases**
3. 手机浏览器打开 Releases 页下载 APK 安装（OPPO 需允许「安装未知应用」）
4. 以后新版发布，在 App **设置 → 检查更新** 一键升级（覆盖安装保留模型与历史）

签名密钥（keystore）备份在本地，CI 从 GitHub Secrets 读取；**密钥丢失将无法覆盖安装**，务必保管。

对应规格书：《离线转写App-完整开发规格书.md》（本仓库同级，含 PRD / UI Token / 流水线 / 验收清单）。

---

## 快速开始

```bash
# 1. 用 Android Studio (Iguana+) 打开本目录，等待 Gradle sync
# 2. 准备模型文件（见下）
# 3. Run → 安装到手机（arm64-v8a，Android 7.0+）
```

### 模型准备（只做一次）

从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) 下载：

| 文件 | 大小 | 说明 |
|---|---|---|
| `model.int8.onnx`（来自 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`） | 228MB | SenseVoice-Small INT8 识别模型（推荐） |
| `tokens.txt` | 小 | 词表，必须与模型同包 |
| `silero_vad.onnx` | 629KB | VAD 分句（可选，缺失时自动回退能量门限 VAD） |

把这 3 个文件拷到手机上，在 App **设置 → 识别模型** 里逐个选中导入，之后永久离线可用。

---

## 依赖说明

- **sherpa-onnx**：`app/build.gradle.kts` 默认走Maven坐标 `com.k2fsa.sherpa.onnx:sherpa-onnx:1.11.0`。
  若你的版本无法从 Maven 获取，请到官方 releases 下载 AAR 放入 `app/libs/`，并把依赖改为：
  ```kotlin
  implementation(files("libs/sherpa-onnx.aar"))
  ```
- **无需 Android SDK NDK 自编译**：原生 .so 随 AAR 提供。

> ⚠️ 若官方 API 的类名/构造参数与本机版本不符，编译报错时**只需修改
> `engine/SherpaSenseVoiceEngine.kt` 与 `engine/SileroVad.kt` 两个文件**（ sherpa 相关 API 已全部收敛在这两处）。

---

## 工程结构

```
app/src/main/java/com/nightowl/transcriber/
├── MainActivity.kt            # 唯一 Activity：SAF 选文件 + 四屏导航
├── ui/
│   ├── HomeScreen.kt          # ① 首页：一个大按钮 + 最近转写
│   ├── TranscribingScreen.kt  # ② 转写页：环形进度 + 流式追加文字
│   ├── ResultScreen.kt        # ③ 结果页：可编辑 + 复制/txt/srt
│   ├── SettingsScreen.kt      # ④ 设置页：iOS 分组列表风
│   └── theme/                 # 设计 Token（双色）+ 排版
├── engine/
│   ├── AsrEngine.kt           # 引擎抽象 + Segment 数据类 + 文本清洗
│   ├── SherpaSenseVoiceEngine.kt  # SenseVoice 唯一 API 接触点
│   ├── SileroVad.kt           # Silero VAD + 能量 VAD 兜底
│   └── ModelManager.kt        # 模型导入 / 校验 / 定位
├── pipeline/
│   ├── AudioExtractor.kt      # 视频/音频 → 16kHz 单声道 PCM（含重采样）
│   ├── TranscribePipeline.kt  # 抽轨→VAD→识别→流式UI→导出 编排
│   └── TranscribeService.kt   # 前台服务 + 通知栏进度
└── data/
    ├── Exporter.kt            # txt/srt 生成 + 排版三选一
    ├── SameDirResolver.kt     # ★ 结果写到原文件同目录（含回退链）
    ├── SettingsStore.kt       # DataStore 设置项
    └── Database.kt            # Room 转写历史
```

---

## 核心实现要点

| 需求 | 实现位置 |
|---|---|
| 视频必须先抽音轨 | `AudioExtractor`：MediaExtractor + MediaCodec → PCM → `Downmixer` 混单声道 → `Resampler` 线性插值到 16kHz |
| 边抽边转（流水线并行） | `TranscribePipeline.run()`：`extract()` 的 `onChunk` 回调里直接喂 VAD + 识别，不落中间文件 |
| 长音频不 OOM | 只保留段文本；VAD 段即时释放 PCM；`EnergyVadDetector` 有 30s 缓冲上限保护 |
| 流式显示 | `TranscribeBus.segments` StateFlow → UI `LazyColumn`（`key` 避免重排抖动）+ 自动滚底 |
| 锁屏继续转 | `TranscribeService` 前台服务 + `foregroundServiceType=dataSync` |
| 同目录导出 | `SameDirResolver.resolveParentDir()`：Document Uri → 真实路径 → 同级写 File；失败逐级回退到上次目录 → MediaStore `Documents/离线转写/` |
| 隐私 | 转写核心全程离线；`INTERNET` 权限仅用于「设置→下载识别模型」与「检查更新」，Manifest 有注释说明 |

---

## 已知限制（V1.0）

1. **个别容器格式**（少量 MKV / AC3 音轨）MediaExtractor 解不出 → 界面报"未找到音轨"；V1.2 接 ffmpeg-kit 兜底
2. **硬字幕 OCR** 未实现（V2.0）
3. **中断续转**为 V1.1 项；当前取消时会把**已转部分**落盘
4. 未做 TalkBack 无障碍完整适配（仅保证字体缩放 1.0~1.3 倍不破版）

---

## 验收自测（DoD）

- [ ] 飞行模式下完成"选文件 → 转写 → 导出"全流程
- [ ] 结果 txt/srt 出现在视频同目录；不可写时能回退并 toast 提示
- [ ] 转写页文字流式追加、无整页卡顿；锁屏后台继续，通知栏有进度
- [ ] 1 小时音频不 OOM、不闪退
- [ ] `aapt dump permissions` 确认不含 INTERNET 权限
- [ ] 适配矩阵：360dp 小屏 / 主流屏 / 平板 / 折叠屏 各过一遍

---

## 参考

- 主参考工程：https://github.com/JackCaow/sherpa-sensevoice-android
- 官方文档：https://k2-fsa.github.io/sherpa/onnx/android/index.html
- 模型：https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html
- 在线效果对照：https://iic-sensevoice.ms.show
