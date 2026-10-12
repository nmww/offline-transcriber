package com.nightowl.transcriber.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 音轨抽取器：视频/音频 → 16kHz 单声道 Float PCM 流。
 *
 * 路线说明（规格书 4.3）：视频不能直接喂给识别模型，必须先 MediaExtractor 解出音轨 →
 * MediaCodec 解码 → 重采样到 16kHz 单声道 → 交给 VAD + ASR。
 *
 * 采用 Flow 式回调：解码一块就回调一块，与上层 VAD/识别并行，不会先等整条音轨抽完。
 */
class AudioExtractor(private val context: Context) {

    companion object {
        const val TARGET_SR = 16000
        private const val TIMEOUT_US = 10_000L
    }

    /** 容器总时长（毫秒），用于计算进度 */
    fun durationMs(uri: Uri): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            val track = selectAudioTrack(ex)
            if (track < 0) return 0L
            val fmt = ex.getTrackFormat(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } catch (t: Throwable) {
            0L
        } finally {
            ex.release()
        }
    }

    /**
     * 抽取并回调 PCM。
     * @param onProgress (已解码毫秒, 总毫秒)
     * @param onChunk 每次回调一段重采样后的 16kHz 单声道样本
     */
    suspend fun extract(
        uri: Uri,
        onProgress: (Long, Long) -> Unit,
        onChunk: suspend (FloatArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = selectAudioTrack(extractor)
            if (track < 0) throw UnsupportedMediaException("未找到音轨：可能不是媒体文件或使用了不支持的音轨格式")
            val format = extractor.getTrackFormat(track)
            val totalMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw UnsupportedMediaException("未知音轨格式")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            while (!sawOutputEos && coroutineContext.isActive) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sz, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex) ?: continue
                        // 输出格式可能在解码中变更（推荐以输出格式为准）
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                        // PCM16LE → Float
                        val pcm16 = ShortArray(info.size / 2)
                        outBuf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm16)

                        val mono = Downmixer.toMono(pcm16, channels)
                        val resampled = Resampler.resample(mono, srcSampleRate, TARGET_SR)
                        if (resampled.isNotEmpty()) onChunk(resampled)

                        val progressedMs = info.presentationTimeUs / 1000
                        onProgress(progressedMs, totalMs)

                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun selectAudioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    class UnsupportedMediaException(msg: String) : Throwable(msg)
}

/** 多声道 → 单声道 混合 */
object Downmixer {
    fun toMono(pcm: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) {
            val out = FloatArray(pcm.size)
            for (i in pcm.indices) out[i] = pcm[i] / 32768f
            return out
        }
        val frames = pcm.size / channels
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += pcm.getOrElse(idx++) { 0.toShort() } / 32768f
            out[f] = acc / channels
        }
        return out
    }
}

/** 线性插值重采样：纯 Kotlin 实现，无第三方依赖 */
object Resampler {
    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty() || srcRate <= 0) return FloatArray(0)
        val ratio = dstRate.toDouble() / srcRate
        val outLen = (input.size * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = kotlin.math.floor(srcPos).toInt()
            val i1 = i0 + 1
            val frac = (srcPos - i0).toFloat()
            val v0 = if (i0 in input.indices) input[i0] else 0f
            val v1 = if (i1 in input.indices) input[i1] else v0
            out[i] = v0 + (v1 - v0) * frac
        }
        return out
    }
}
