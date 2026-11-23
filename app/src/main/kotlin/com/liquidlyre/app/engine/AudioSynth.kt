package com.liquidlyre.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.concurrent.thread

/**
 * 演奏台模拟音效：单渲染线程 + 复音混音器。
 *
 * 结构（v1.4 起）：
 *   1. noteOn 只把 Voice 放进列表（O(1)，不阻塞调度线程）
 *   2. 一个常驻渲染线程按 CHUNK 帧渲染，把所有活跃 Voice 混成一个 buffer 再 write
 *   3. 混音后 tanh 软限幅，避免和弦叠加削波爆音
 *
 * v1.5 修复「演示没声音」：
 *  - 同一键再次按下时先移除旧 voice（否则同键叠加、音量翻倍后 tanh 压平，听起来像没声）
 *  - 建轨/播放失败写入诊断日志，不再静默 return
 *  - 申请 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，避免被系统/游戏静音策略吞掉
 *  - 音色加 4 次谐波 + 更慢的衰减，拨弦感更清楚
 *
 * 音阶严格按原神「风物之诗琴」21 键真实布局（三行各一个八度，从左到右 Do→Si）：
 *   上排 Q W E R T Y U = C5 D5 E5 F5 G5 A5 B5（高八度）
 *   中排 A S D F G H J = C4 D4 E4 F4 G4 A4 B4（中八度）
 *   下排 Z X C V B N M = C3 D3 E3 F3 G3 A3 B3（低八度）
 */
object AudioSynth {
    private const val SAMPLE_RATE = 44100
    /** 实际采样率：44100 不受支持时回退 48000。所有帧换算统一用它，避免音高偏移 */
    @Volatile private var effectiveRate = SAMPLE_RATE
    private const val CHUNK = 256          // 5.8ms，兼顾延迟与 CPU
    private const val MASTER = 0.62f
    private const val MAX_VOICES = 16

    /** 半音偏移，以 C4 = 0 为基准 */
    private val KEY_SEMITONE = mapOf(
        // 上排 = 高八度 C5..B5
        "Q" to 12, "W" to 14, "E" to 16, "R" to 17, "T" to 19, "Y" to 21, "U" to 23,
        // 中排 = 中八度 C4..B4
        "A" to 0, "S" to 2, "D" to 4, "F" to 5, "G" to 7, "H" to 9, "J" to 11,
        // 下排 = 低八度 C3..B3
        "Z" to -12, "X" to -10, "C" to -8, "V" to -7, "B" to -5, "N" to -3, "M" to -1
    )

    private class Voice(val key: String, val freq: Double, val totalFrames: Int) {
        var pos = 0
    }

    private val voices = ArrayList<Voice>()
    private val lock = Any()

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var renderThread: Thread? = null
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHeld = false
    @Volatile private var lastError: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // ============ 音频焦点 ============

    private fun requestFocus() {
        if (focusHeld) return
        val am = audioManager ?: return
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            val r = am.requestAudioFocus(req)
            focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            PlayerState.diag("音频焦点：${if (focusHeld) "已获取" else "被拒绝($r)，仍尝试发声"}")
        } catch (t: Throwable) {
            PlayerState.diag("音频焦点异常：${t.javaClass.simpleName}")
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        val am = audioManager ?: return
        try { focusRequest?.let { am.abandonAudioFocusRequest(it) } } catch (_: Throwable) {}
        focusRequest = null
    }

    // ============ 引擎生命周期 ============

    /**
     * 建轨。v1.6.2 修复 "Invalid audio buffer size"：
     *  旧代码 coerceAtLeast(44100/4=11025) 得到奇数 11025 = 5512.5 帧，
     *  AudioTrack.Builder 要求 bufferSizeInBytes 必须是帧长(2 字节)整数倍 → 直接抛异常。
     *  现在按各采样率实际 getMinBufferSize 计算并向上对齐到偶数；44100 不支持则退 48000。
     */
    private fun buildTrack(): AudioTrack? {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        for (rate in intArrayOf(SAMPLE_RATE, 48000)) {
            val min = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) {
                PlayerState.diag("采样率 ${rate}Hz 不受支持（getMinBufferSize=$min），尝试下一个")
                continue
            }
            val need = maxOf(min, rate / 4)        // 至少 250ms，抗 underrun
            val aligned = ((need + 1) / 2) * 2     // 向上对齐到偶数（整数帧）
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(aligned)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (t.state != AudioTrack.STATE_INITIALIZED) {
                    PlayerState.diag("采样率 ${rate}Hz 建轨未初始化（state=${t.state}），尝试下一个")
                    try { t.release() } catch (_: Throwable) {}
                    continue
                }
                effectiveRate = rate
                PlayerState.diag("音频引擎就绪：${rate}Hz 单声道，buffer=$aligned 字节（原始 min=$min）")
                return t
            } catch (t: Throwable) {
                PlayerState.diag("采样率 ${rate}Hz 建轨失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }
        lastError = "所有采样率建轨失败（buffer 已按帧对齐）"
        PlayerState.diag(lastError ?: "建轨失败")
        return null
    }

    private fun ensureEngine(): Boolean {
        if (running) return true
        synchronized(this) {
            if (running) return true
            requestFocus()
            val t = buildTrack()
            if (t == null) {
                PlayerState.diag(lastError ?: "音频引擎初始化失败")
                return false
            }
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                PlayerState.diag("AudioTrack 未初始化（state=${t.state}）")
                try { t.release() } catch (_: Throwable) {}
                return false
            }
            track = t
            running = true
            try {
                t.play()
            } catch (e: Throwable) {
                running = false
                PlayerState.diag("AudioTrack.play 失败：${e.message ?: e.javaClass.simpleName}")
                return false
            }
            lastError = null
            renderThread = thread(name = "lyre-mixer", isDaemon = true) { renderLoop(t) }
            
            return true
        }
    }

    // ============ 渲染 ============

    private fun renderLoop(t: AudioTrack) {
        val out = ShortArray(CHUNK)
        val mix = FloatArray(CHUNK)
        var idleBlocks = 0
        while (running) {
            java.util.Arrays.fill(mix, 0f)
            val active = synchronized(lock) {
                var i = 0
                while (i < voices.size) {
                    val v = voices[i]
                    if (v.pos >= v.totalFrames) {
                        voices.removeAt(i)
                        continue
                    }
                    val n = min(CHUNK, v.totalFrames - v.pos)
                    for (k in 0 until n) {
                        val idx = v.pos + k
                        val time = idx.toDouble() / effectiveRate
                        val ph = 2.0 * PI * v.freq * time
                        // 拨弦音色：基频 + 2/3/4 次谐波，谐波衰减更快
                        val decay = 1.0 - idx.toDouble() / v.totalFrames
                        val wave = sin(ph) +
                            0.34 * decay * sin(2.0 * ph) +
                            0.14 * decay * sin(3.0 * ph) +
                            0.05 * decay * sin(4.0 * ph)
                        mix[k] += (wave * envelope(idx, v.totalFrames)).toFloat()
                    }
                    v.pos += n
                    i++
                }
                voices.isNotEmpty()
            }
            for (k in 0 until CHUNK) {
                // tanh 软限幅：和弦多音叠加也不削波爆音
                val s = tanh(mix[k].toDouble() * 0.85) * MASTER
                out[k] = (s * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            val written = try {
                t.write(out, 0, CHUNK)
            } catch (e: Throwable) {
                lastError = "write 异常：${e.message ?: e.javaClass.simpleName}"
                -1
            }
            if (written < 0) {
                PlayerState.diag(lastError ?: "AudioTrack.write 返回 $written（设备可能已被系统回收）")
                synchronized(this) {
                    if (track === t) { running = false; track = null }
                }
                break
            }
            // 空闲约 1.2s 后主动退出并释放。
            // 关键：退出判定放进 synchronized(this)，且与 notesOn 入队互斥检查一次，
            // 避免"刚判定空闲 → 用户点播放 → 旧线程退出把新引擎一起关掉"的竞态（表现为无声）。
            if (active) {
                idleBlocks = 0
            } else if (++idleBlocks > 200) {
                var exit = false
                var dropFocus = false
                synchronized(this) {
                    // 引擎已被新一次播放替换：旧线程只管自己退出，
                    // 绝不能碰 running / track / 焦点，否则会把刚启动的新引擎一起关掉（无声）。
                    if (track !== t) {
                        exit = true
                    } else {
                        val hasVoice = synchronized(lock) { voices.isNotEmpty() }
                        if (hasVoice) {
                            idleBlocks = 0
                        } else {
                            running = false
                            try { t.pause(); t.flush(); t.release() } catch (_: Throwable) {}
                            track = null
                            exit = true
                            dropFocus = true
                        }
                    }
                }
                if (dropFocus) abandonFocus()
                if (exit) break
            }
        }
    }

    /** 快速起音 + 指数衰减 + 末尾线性收尾（杜绝爆音） */
    private fun envelope(i: Int, total: Int): Float {
        val atk = (effectiveRate * 0.003).toInt().coerceAtLeast(1)   // 3ms
        val rel = (total * 0.08).toInt().coerceAtLeast(1)          // 尾部 8%
        val atkPart = if (i < atk) i.toFloat() / atk else 1f
        val body = exp(-2.2 * i.toDouble() / total).toFloat()
        val relPart = if (i > total - rel) ((total - i).toFloat() / rel).coerceIn(0f, 1f) else 1f
        return (atkPart * body * relPart).coerceIn(0f, 1f)
    }

    // ============ 对外 API ============

    /** 弹一个音。durationMs 建议传真实按键时值（按下到抬起） */
    fun noteOn(key: String, durationMs: Int = 420) {
        val k = key.trim().uppercase()
        val semi = KEY_SEMITONE[k] ?: return
        if (!ensureEngine()) return
        val freq = 261.63 * Math.pow(2.0, semi / 12.0)
        val frames = (effectiveRate * durationMs / 1000.0).toInt().coerceIn(64, effectiveRate * 4)
        synchronized(lock) {
            // 同键重按：移除旧 voice，避免叠加导致 tanh 压平（听起来"没声音"）
            voices.removeAll { it.key == k }
            voices.add(Voice(k, freq, frames))
            while (voices.size > MAX_VOICES) voices.removeAt(0)
        }
    }

    /** 同时弹奏多个键（和弦）—— 一把抓下去，同帧进混音 */
    fun notesOn(keys: List<String>, durationMs: Int = 420) {
        if (keys.isEmpty()) return
        if (!ensureEngine()) return
        val frames = (effectiveRate * durationMs / 1000.0).toInt().coerceIn(64, effectiveRate * 4)
        synchronized(lock) {
            keys.forEach { raw ->
                val k = raw.trim().uppercase()
                val semi = KEY_SEMITONE[k] ?: return@forEach
                voices.removeAll { it.key == k }
                voices.add(Voice(k, 261.63 * Math.pow(2.0, semi / 12.0), frames))
            }
            while (voices.size > MAX_VOICES) voices.removeAt(0)
        }
    }

    /** 停止发声但不释放引擎（暂停/急停用，O(1) 不阻塞主线程） */
    fun silence() {
        synchronized(lock) { voices.clear() }
    }

    /** 一键测试音：绕过播放引擎直接发声，并把 AudioTrack 运行时状态写进诊断日志 */
    fun selfTest(): String {
        val ready = ensureEngine()
        if (!ready) {
            val msg = "音频引擎启动失败：${lastError ?: "未知原因"}"
            PlayerState.diag(msg)
            return msg
        }
        noteOn("A", 1200)
        val st = track?.state ?: -1
        val playing = track?.playState ?: -1
        val v = synchronized(lock) { voices.size }
        val msg = "测试音 A 已送入：${effectiveRate}Hz track state=$st playState=$playing voices=$v focus=$focusHeld"
        PlayerState.diag(msg)
        return msg
    }

    /** 供播放引擎首音诊断：一句话说明引擎是否活着 */
    fun selfTestShort(): String =
        if (running && track != null) "AudioTrack 已就绪" else "AudioTrack 未就绪：${lastError ?: "未知"}"

    fun lastErrorText(): String? = lastError

    @Synchronized
    fun release() {
        running = false
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.flush() } catch (_: Throwable) {}
        try { renderThread?.join(200) } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        renderThread = null
        synchronized(lock) { voices.clear() }
        abandonFocus()
    }
}
