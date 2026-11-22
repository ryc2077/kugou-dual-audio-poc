package com.btsplit.poc

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.Context
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 概念验证核心 v2：把一路立体声 PCM 拆成多路输出，
 * 每路一个 AudioTrack，setPreferredDevice() 到自己的蓝牙 A2DP 设备。
 *
 * 行为复现自 Hifinis 2.5.16 的公开行为（见 hifinis-decompile/HIFINIS-REPORT.md §5/§8），
 * 只复现“行为”，没有 Hifinis 的任何代码：
 * - Hifinis 用 native auralis::MultiOut + Oboe 流；本 POC 没有 NDK 构建，
 *   用 AudioTrack 代替，同步算法按 §5.3 改编（见 reconcileLoop）。
 * - Hifinis 每 Slot 有 gain / channel / delay 三个热更新参数；
 *   本 POC 的 OutputConfig 同样是可变的，写线程每 chunk 重新读取。
 * - Hifinis 打开失败时记“拒绝”并明确提示，不静默重试；本 POC 同样处理。
 *
 * v1 与 v2 的关键区别：
 * - v1：每路延迟是开播前一次性写静音帧；改声道/音量/延迟要重建 Track。
 * - v2：延迟是写线程里按“已应用延迟帧数”动态维持的读偏移；
 *   声道/音量/trim 改动时只写 volatile 字段，下个 chunk 就生效，不重建 Track。
 * - v2 所有 Track 统一建成 STEREO（见 buildTrack 注释），这样声道模式
 *   LEFT/RIGHT/MONO/STEREO 之间才能热切换——否则 mono Track 无法变成立体声。
 */
class SplitEngine(private val context: Context) {

    companion object {
        const val TAG = "BTSplitPOC"
        const val SAMPLE_RATE = 48000
        /** reconcile 周期（Hifinis 是持续 reconcile，本 POC 每 500ms 一轮）。 */
        const val RECONCILE_INTERVAL_MS = 500L
        /** 迟滞阈值（Hifinis §5.3：变化 <8ms 时保持旧值，防抖动）。 */
        const val RECONCILE_HYSTERESIS_MS = 8
        /** 用户 trim 范围（Hifinis §5：±400ms）。 */
        const val TRIM_MIN_MS = -400
        const val TRIM_MAX_MS = 400
        /** getTimestamp() 失败时的实测延迟回落值（Hifinis §5.2：蓝牙 220ms）。 */
        const val FALLBACK_LATENCY_BT_MS = 220f
    }

    data class BtDevice(val info: AudioDeviceInfo) {
        val id: Int get() = info.id
        val name: String get() = info.productName?.toString() ?: "BT-${info.id}"
        val address: String get() = info.address ?: ""
        override fun toString(): String = "$name (${info.type}, id=$id)"
    }

    /** 每路输出的声道模式 —— 与 Hifinis 公开的 Outputs 设置同名同义。 */
    enum class ChannelMode(val label: String) {
        STEREO("立体声 (Stereo)"),
        LEFT("仅左声道 (Left)"),
        RIGHT("仅右声道 (Right)"),
        MONO("单声道混音 (Mono)");

        override fun toString(): String = label
    }

    /**
     * 一路输出的完整配置。v2 中 mode / volume / trimMs 都是 @Volatile var，
     * 写线程每 chunk 重新读取，所以 UI 改动可以热更新，不重建 Track。
     *
     * @param volume 0.0f..1.0f，超出范围会被钳制。
     * @param trimMs 用户手动同步修正，-400..+400ms（Hifinis §5：trim 叠加在
     *   reconcile 之上）。语义：trim 越大，该路总延迟越小，reconcile 给它的
     *   补偿延迟就越大，即该路出声越晚。界面上的“延迟”滑杆就是这个 trim，
     *   中间位置（400/800）= 0ms。
     * @param effectiveDelayMs reconcile 算出的补偿延迟（引擎内部写，UI 不直接写）。
     */
    data class OutputConfig(
        val device: BtDevice,
        @Volatile var mode: ChannelMode = ChannelMode.STEREO,
        @Volatile var volume: Float = 1.0f,
        @Volatile var trimMs: Int = 0
    ) {
        @Volatile var effectiveDelayMs: Int = 0
        val safeVolume: Float get() = volume.coerceIn(0.0f, 1.0f)
        val safeTrimMs: Int get() = trimMs.coerceIn(TRIM_MIN_MS, TRIM_MAX_MS)
    }

    /** 一路输出的运行时状态（写线程 + reconcile 线程共享）。 */
    private class OutputState(val config: OutputConfig, val track: AudioTrack) {
        @Volatile var active = true
        /** 下一个要写的 PCM 帧下标（立体声帧数）。 */
        @Volatile var pcmPos = 0
        /** 已插入的静音帧总数 = 当前已应用的延迟（帧）。 */
        @Volatile var appliedDelayFrames = 0
        /** 已写入 Track 的总帧数（含静音帧），用于实测延迟计算。 */
        @Volatile var framesWritten = 0L
        @Volatile var lastVolume = config.safeVolume
        @Volatile var lastEffectiveDelayMs = -1
    }

    /** 列出当前已连接的蓝牙 A2DP 输出设备。 */
    fun listA2dpDevices(): List<BtDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .map { BtDevice(it) }
    }

    /** 反射调用隐藏的系统 API：把 MEDIA 策略的首选设备设为两个蓝牙设备。
     *  成功不代表分声道，只代表系统允许同时向两者推流；分声道仍靠两个 Track。
     *  普通 App 调这个会抛 SecurityException，priv-app + 权限后才可能成功。 */
    fun trySetPreferredDevicesForStrategy(devices: List<AudioDeviceInfo>): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val m = AudioManager::class.java.getMethod(
                "setPreferredDevicesForStrategy",
                Int::class.javaPrimitiveType, List::class.java
            )
            val ok = m.invoke(am, 0, devices) as Boolean
            "setPreferredDevicesForStrategy(MEDIA, ${devices.size} devices) -> $ok"
        } catch (e: Exception) {
            "setPreferredDevicesForStrategy failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private val tracks = mutableListOf<AudioTrack>()
    private val activeStates = mutableListOf<OutputState>()
    /** 本次会话被系统拒绝的设备 id（Hifinis §5.4：拒绝后记下来，不再重试）。 */
    private val refusedDeviceIds = mutableSetOf<Int>()
    private var logFn: ((String) -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var sessionId = 0

    fun isPlaying(): Boolean = running

    fun isRefused(device: BtDevice): Boolean = synchronized(refusedDeviceIds) {
        device.id in refusedDeviceIds
    }

    /** 用户在界面点 Refresh（重新插拔/重连设备）时调用，清空拒绝记录，允许再试一次。 */
    fun clearRefusedDevices() {
        synchronized(refusedDeviceIds) { refusedDeviceIds.clear() }
    }

    fun stop() {
        running = false
        sessionId++
        synchronized(activeStates) { activeStates.forEach { it.active = false } }
        synchronized(tracks) {
            tracks.forEach { t ->
                try { t.stop(); t.release() } catch (_: Exception) {}
            }
            tracks.clear()
        }
        synchronized(activeStates) { activeStates.clear() }
    }

    /**
     * 热更新一路输出的声道模式 / 音量 / trim，不重建 AudioTrack。
     * 写线程每 chunk 重新读取 config 的 volatile 字段，下个 chunk 即生效；
     * 音量还会由写线程在下个 chunk 调用 track.setVolume() 应用。
     * 未在播放时调用返回 false，不做任何事。
     */
    fun updateOutput(index: Int, mode: ChannelMode, volume: Float, trimMs: Int): Boolean {
        val state = synchronized(activeStates) { activeStates.getOrNull(index) } ?: return false
        if (!running || !state.active) return false
        state.config.mode = mode
        state.config.volume = volume.coerceIn(0.0f, 1.0f)
        state.config.trimMs = trimMs.coerceIn(TRIM_MIN_MS, TRIM_MAX_MS)
        logFn?.invoke(
            "热更新 输出 ${index + 1}（${state.config.device.name}）: " +
                "mode=$mode volume=${state.config.safeVolume} trim=${state.config.safeTrimMs}ms" +
                "（不重建 Track，下个 chunk 生效）"
        )
        return true
    }

    /**
     * 建一条 Track。v2 统一建成 STEREO：声道模式只是写 PCM 时的每帧变换
     * （LEFT 写 (L,0)、RIGHT 写 (0,R)、MONO 写 (M,M)），所以模式可以在
     * STEREO/LEFT/RIGHT/MONO 之间热切换；若建成 MONO Track，之后切到
     * STEREO 就必须重建 Track，热更新就做不到了。
     * A2DP 本身是立体声链路，单声道模式只是一侧写静音，效果与 mono Track 等价。
     */
    private fun buildTrack(
        config: OutputConfig, log: (String) -> Unit
    ): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setVolume(config.safeVolume)
            val preferred = track.setPreferredDevice(config.device.info)
            val routed = track.routedDevice
            log(
                "Track [${config.mode}] -> ${config.device.name}: " +
                    "volume=${config.safeVolume}, trim=${config.safeTrimMs}ms, " +
                    "setPreferredDevice=$preferred, " +
                    "routedDevice=${routed?.productName} (type=${routed?.type})"
            )
            if (!preferred) {
                log("⚠️ ${config.device.name}：setPreferredDevice 返回 false，起播后会再核对 routedDevice")
            }
            track
        } catch (e: Exception) {
            // 建 Track 本身就失败：按 Hifinis §5.4 记拒绝，不抛异常、不影响其他路。
            refuse(config.device, "创建 AudioTrack 失败：${e.javaClass.simpleName}: ${e.message}", log)
            null
        }
    }

    /** 记拒绝并写日志（Hifinis §5.4：明确告诉用户，不静默重试）。 */
    private fun refuse(device: BtDevice, reason: String, log: (String) -> Unit) {
        synchronized(refusedDeviceIds) { refusedDeviceIds.add(device.id) }
        log("❌ Android 不允许同时播这里：${device.name} —— $reason（本次会话不再重试，Refresh 后可再试）")
        Log.w(TAG, "refused ${device.name}: $reason")
    }

    /** 停止一路被拒绝的输出（不影响其他路），记拒绝。 */
    private fun refuseOutput(state: OutputState, reason: String, log: (String) -> Unit) {
        if (!state.active) return
        state.active = false
        try { state.track.stop(); state.track.release() } catch (_: Exception) {}
        synchronized(tracks) { tracks.remove(state.track) }
        refuse(state.config.device, reason, log)
    }

    /** 生成立体声测试音 PCM（交错 L/R）：左路 440Hz，右路 660Hz。 */
    private fun generateTestToneStereoPcm(seconds: Int): ShortArray {
        val frames = SAMPLE_RATE * seconds
        val pcm = ShortArray(frames * 2)
        for (n in 0 until frames) {
            val t = n.toDouble() / SAMPLE_RATE
            pcm[n * 2] = (sin(2 * PI * 440.0 * t) * 12000).toInt().toShort()
            pcm[n * 2 + 1] = (sin(2 * PI * 660.0 * t) * 12000).toInt().toShort()
        }
        return pcm
    }

    /**
     * 实测延迟（Hifinis §5.2 的 AudioTrack 版近似）：
     * AudioTrack.getTimestamp() 给出“某一帧实际出声的时间”；
     * 已写入但还没出声的帧数 = framesWritten - timestamp.framePosition，
     * 换算成毫秒，再加上时间戳本身的年龄，就是当前输出延迟的估计。
     * 取不到时间戳时按蓝牙回落值 220ms（Hifinis §5.2）。
     * @return Pair(实测延迟 ms, 缓冲 ms)，缓冲 = Track 缓冲区帧数换算。
     */
    private fun measureLatency(state: OutputState): Pair<Float, Float> {
        val track = state.track
        val bufferMs = track.bufferSizeInFrames * 1000f / SAMPLE_RATE
        return try {
            val ts = AudioTimestamp()
            if (track.getTimestamp(ts)) {
                val pending = (state.framesWritten - ts.framePosition).coerceAtLeast(0L)
                val backlogMs = pending * 1000f / SAMPLE_RATE
                val ageMs = (System.nanoTime() - ts.nanoTime) / 1_000_000f
                Pair((backlogMs + ageMs).coerceAtLeast(0f), bufferMs)
            } else {
                Pair(FALLBACK_LATENCY_BT_MS, bufferMs)
            }
        } catch (_: Exception) {
            Pair(FALLBACK_LATENCY_BT_MS, bufferMs)
        }
    }

    /**
     * Reconcile 同步循环（Hifinis §5.3，改编到 AudioTrack）：
     * 每 500ms 一轮，对每路：总延迟 = 实测延迟 + 缓冲毫秒 − 用户 trim；
     * 目标 = 所有路总延迟的最大值（向最慢者对齐）；
     * 每路补偿延迟 = 目标 − 本路总延迟（<0 钳 0），
     * 与上次值相比变化 <8ms 时保持旧值（±8ms 迟滞，防抖动）。
     * 补偿延迟只写进 config.effectiveDelayMs（volatile），写线程在下个
     * chunk 边界通过“插静音 / 跳帧”应用，不重建 Track。
     */
    private fun startReconcile(states: List<OutputState>, session: Int, log: (String) -> Unit) {
        Thread {
            var firstRound = true
            while (running && sessionId == session) {
                try { Thread.sleep(RECONCILE_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (!running || sessionId != session) break
                val live = states.filter { it.active }
                if (live.isEmpty()) break
                val totals = live.map { state ->
                    val (measured, buffer) = measureLatency(state)
                    val total = measured + buffer - state.config.safeTrimMs
                    Triple(state, total, measured to buffer)
                }
                val target = totals.maxOf { it.second }
                var changed = false
                val parts = mutableListOf<String>()
                for ((state, total, mb) in totals) {
                    val desired = (target - total).toInt().coerceAtLeast(0)
                    val old = state.config.effectiveDelayMs
                    if (abs(desired - old) >= RECONCILE_HYSTERESIS_MS || firstRound) {
                        state.config.effectiveDelayMs = desired
                        changed = true
                    }
                    parts.add(
                        "${state.config.device.name}: 总=${total.toInt()}ms" +
                            "（实测=${mb.first.toInt()} 缓冲=${mb.second.toInt()} " +
                            "trim=${state.config.safeTrimMs}）→ 补偿=${state.config.effectiveDelayMs}ms"
                    )
                }
                firstRound = false
                if (changed) {
                    log("Reconcile（目标 ${target.toInt()}ms，向最慢者对齐）: " + parts.joinToString("；"))
                }
            }
        }.also { it.name = "bt-split-reconcile"; it.isDaemon = true }.start()
    }

    /**
     * 起播后核对路由（Hifinis §5.4 的 AudioTrack 版）：
     * 等 500ms 让路由稳定，再查每路 routedDevice；若与目标不一致，
     * 说明 Android 把这路又路由到了别的设备，停掉这一路并记拒绝，
     * 其他路继续播。
     */
    private fun startRouteCheck(states: List<OutputState>, session: Int, log: (String) -> Unit) {
        Thread {
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            if (!running || sessionId != session) return
            for (state in states) {
                if (!state.active) continue
                val routed = try { state.track.routedDevice } catch (_: Exception) { null }
                if (routed != null && routed.id != state.config.device.id) {
                    refuseOutput(
                        state,
                        "routedDevice 是 ${routed.productName}，不是目标设备",
                        log
                    )
                }
            }
        }.also { it.name = "bt-split-route-check"; it.isDaemon = true }.start()
    }

    /**
     * 通用多输出播放 v2（Hifinis 式行为的核心）：
     * 一份立体声 PCM，喂给 N 路输出，每路独立的声道模式 / 音量 / trim，
     * 每路一个 AudioTrack，setPreferredDevice() 到自己的蓝牙设备，同时起播。
     *
     * 延迟的实现（v2，不再是开播前一次性静音帧）：
     * 写线程维护 pcmPos（下一个 PCM 帧）和 appliedDelayFrames（已应用延迟帧数）；
     * 每 chunk 先把 effectiveDelayMs 换算成目标延迟帧数：
     * - 目标 > 已应用：先写（目标−已应用）帧静音，已应用随之增加；
     * - 目标 < 已应用：跳过（已应用−目标）帧 PCM（追赶），已应用随之减少；
     * - 目标 == 已应用：直接写 PCM。
     * 这样 reconcile 每 500ms 改 effectiveDelayMs 时，输出延迟平滑跟随，
     * 不用重建 Track，也不会把已经写出去的 PCM 倒回去重放。
     *
     * 声道变换（每帧，输入为交错 L/R；Track 统一是立体声，见 buildTrack）：
     * - STEREO：原样 (L,R)
     * - LEFT：(L, 0)
     * - RIGHT：(0, R)
     * - MONO：(M,M)，M=(L+R)/2（int 累加再除，避免 16-bit 溢出削波）
     * 模式在每 chunk 重新读取 config.mode，所以播放中改模式立即生效。
     */
    fun playOutputs(
        configs: List<OutputConfig>, stereoPcm: ShortArray,
        log: (String) -> Unit
    ) {
        stop()
        logFn = log
        if (configs.isEmpty()) { log("playOutputs: 没有输出配置"); return }
        if (stereoPcm.isEmpty()) { log("playOutputs: PCM 为空"); return }
        val distinct = configs.map { it.device.id }.toSet()
        if (distinct.size < configs.size) {
            log("⚠️ 警告：多个输出选了同一个设备，声音会叠加在同一个音箱上")
        }
        // 被拒绝过的设备本次会话不再重试（Hifinis §5.4），直接跳过并提示。
        val usable = configs.filter { config ->
            if (isRefused(config.device)) {
                log("跳过被拒绝的设备：${config.device.name}（本次会话不再重试，点 Refresh 可清除拒绝记录再试）")
                false
            } else true
        }
        if (usable.isEmpty()) { log("没有可用的输出（全部被拒绝或未选设备）"); return }
        log("=== Multi-output v2: ${usable.size} 路（reconcile 同步 + 热更新） ===")
        usable.forEachIndexed { i, c ->
            c.effectiveDelayMs = 0
            log("输出 ${i + 1}: ${c.device.name} mode=${c.mode} volume=${c.safeVolume} trim=${c.safeTrimMs}ms")
        }
        log(trySetPreferredDevicesForStrategy(usable.map { it.device.info }))

        val states = usable.mapNotNull { config ->
            buildTrack(config, log)?.let { OutputState(config, it) }
        }
        if (states.isEmpty()) { log("没有成功创建任何 AudioTrack"); return }
        synchronized(tracks) { tracks.addAll(states.map { it.track }) }
        synchronized(activeStates) { activeStates.addAll(states) }
        running = true
        val session = ++sessionId

        // 所有路用同一个 CountDownLatch 同时起播，避免 App 层启动时差。
        val gate = CountDownLatch(1)
        val frames = stereoPcm.size / 2
        val chunkFrames = 4096

        val threads = states.map { state ->
            Thread {
                val track = state.track
                val config = state.config
                try { track.play() } catch (e: Exception) {
                    refuseOutput(state, "track.play() 失败：${e.javaClass.simpleName}: ${e.message}", log)
                    return@Thread
                }
                gate.await(5, TimeUnit.SECONDS)
                val buf = ShortArray(chunkFrames * 2)
                while (running && state.active && state.pcmPos < frames) {
                    // --- 热更新：每 chunk 重新读模式 / 音量（volatile）---
                    val mode = config.mode
                    val volume = config.safeVolume
                    if (volume != state.lastVolume) {
                        try { track.setVolume(volume) } catch (_: Exception) {}
                        state.lastVolume = volume
                    }
                    // --- 延迟：按 effectiveDelayMs 调整已应用延迟帧数 ---
                    val desiredDelayFrames = config.effectiveDelayMs * SAMPLE_RATE / 1000
                    if (desiredDelayFrames > state.appliedDelayFrames) {
                        val silenceFrames = minOf(
                            desiredDelayFrames - state.appliedDelayFrames, chunkFrames
                        )
                        val written = try {
                            track.write(ShortArray(silenceFrames * 2), 0, silenceFrames * 2)
                        } catch (_: Exception) { -1 }
                        if (written < 0) break
                        state.appliedDelayFrames += silenceFrames
                        state.framesWritten += silenceFrames
                        continue
                    }
                    if (desiredDelayFrames < state.appliedDelayFrames) {
                        // 追赶：跳过 PCM 帧（不重放已写出的内容）。
                        val skip = minOf(
                            state.appliedDelayFrames - desiredDelayFrames,
                            frames - state.pcmPos
                        )
                        state.pcmPos += skip
                        state.appliedDelayFrames = desiredDelayFrames
                        if (state.pcmPos >= frames) break
                    }
                    // --- 写一 chunk PCM，按当前模式变换声道 ---
                    val len = minOf(chunkFrames, frames - state.pcmPos)
                    for (i in 0 until len) {
                        val l = stereoPcm[(state.pcmPos + i) * 2].toInt()
                        val r = stereoPcm[(state.pcmPos + i) * 2 + 1].toInt()
                        when (mode) {
                            ChannelMode.STEREO -> { buf[i * 2] = l.toShort(); buf[i * 2 + 1] = r.toShort() }
                            ChannelMode.LEFT -> { buf[i * 2] = l.toShort(); buf[i * 2 + 1] = 0 }
                            ChannelMode.RIGHT -> { buf[i * 2] = 0; buf[i * 2 + 1] = r.toShort() }
                            ChannelMode.MONO -> {
                                val m = ((l + r) / 2).toShort()
                                buf[i * 2] = m; buf[i * 2 + 1] = m
                            }
                        }
                    }
                    val written = try { track.write(buf, 0, len * 2) } catch (_: Exception) { -1 }
                    if (written < 0) break
                    state.framesWritten += len
                    state.pcmPos += len
                }
            }.also { it.name = "bt-split-out-${state.config.device.id}" }
        }

        threads.forEach { it.start() }
        gate.countDown()
        startReconcile(states, session, log)
        startRouteCheck(states, session, log)
        Thread {
            threads.forEach { it.join() }
            val stillActive = states.filter { it.active }
            log(
                "Multi-output finished. 存活输出: " +
                    if (stillActive.isEmpty()) "无（全部结束或被拒绝）"
                    else stillActive.joinToString {
                        "${it.config.device.name}=${it.track.routedDevice?.productName}"
                    }
            )
            running = false
        }.also { it.isDaemon = true }.start()
    }

    /**
     * 测试音模式（不需要任何音频文件，最适合做概念验证）：
     * 左路 = 440Hz 正弦，右路 = 660Hz 正弦，持续 [seconds] 秒。
     * 如果分声道成功：左音箱只听到 440Hz，右音箱只听到 660Hz。
     * 如果失败：两个音箱都听到两个频率，或只有一个音箱响。
     *
     * 新版：按每路的 OutputConfig（声道模式 / 音量 / trim）播放。
     */
    fun playTestTone(
        configs: List<OutputConfig>, seconds: Int = 10,
        log: (String) -> Unit
    ) {
        log("=== TestTone: L=440Hz / R=660Hz, ${seconds}s ===")
        playOutputs(configs, generateTestToneStereoPcm(seconds), log)
    }

    /** 旧入口保留：左设备 = 仅左声道，右设备 = 仅右声道，右路 trim = delayRightMs。 */
    fun playTestTone(
        left: BtDevice, right: BtDevice, seconds: Int = 10, delayRightMs: Int = 0,
        log: (String) -> Unit
    ) {
        playTestTone(
            listOf(
                OutputConfig(left, ChannelMode.LEFT, 1.0f, 0),
                OutputConfig(right, ChannelMode.RIGHT, 1.0f, delayRightMs)
            ),
            seconds, log
        )
    }

    /**
     * 播放一个立体声 WAV/MP3 文件并按每路配置分流。
     * 用 MediaExtractor 解码成 PCM（立体声交错），解码一次，喂给所有输出。
     * 只做概念验证：不支持 DRM / 流媒体，只支持本地文件。
     */
    fun playFile(
        uri: Uri, configs: List<OutputConfig>,
        log: (String) -> Unit
    ) {
        log("=== PlayFile: $uri ===")
        val pcm = decodeToStereoPcm(uri, log) ?: return
        log("Decoded ${pcm.size / 2} stereo frames @ $SAMPLE_RATE Hz (resampled by decoder if needed)")
        playOutputs(configs, pcm, log)
    }

    /** 旧入口保留：左设备 = 仅左声道，右设备 = 仅右声道。 */
    fun playFile(
        uri: Uri, left: BtDevice, right: BtDevice, delayRightMs: Int = 0,
        log: (String) -> Unit
    ) {
        playFile(
            uri,
            listOf(
                OutputConfig(left, ChannelMode.LEFT, 1.0f, 0),
                OutputConfig(right, ChannelMode.RIGHT, 1.0f, delayRightMs)
            ),
            log
        )
    }

    /** 尽量简单地解码：只取第一个音频轨，输出 16-bit 立体声 PCM。采样率以解码器为准，
     *  本 POC 固定按 48kHz 建 Track，若文件是 44.1kHz 会有音调偏差——测试音模式不受影响，
     *  正式验证请用 48kHz WAV。 */
    private fun decodeToStereoPcm(uri: Uri, log: (String) -> Unit): ShortArray? {
        return try {
            val ex = MediaExtractor()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: run { log("openFileDescriptor failed"); return null }
            ex.setDataSource(pfd.fileDescriptor)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIdx = i; format = f; break
                }
            }
            if (trackIdx < 0) { log("no audio track"); return null }
            ex.selectTrack(trackIdx)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = mutableListOf<Short>()
            val info = android.media.MediaCodec.BufferInfo()
            var done = false
            while (!done) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val shorts = ShortArray(info.size / 2)
                    buf.asShortBuffer().get(shorts)
                    // 只保留前两个声道（立体声）；单声道文件则复制一份。
                    val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (ch >= 2) out.addAll(shorts.toList())
                    else shorts.forEach { out.add(it); out.add(it) }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            codec.stop(); codec.release(); ex.release()
            out.toShortArray()
        } catch (e: Exception) {
            log("decode failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "decode", e)
            null
        }
    }
}
