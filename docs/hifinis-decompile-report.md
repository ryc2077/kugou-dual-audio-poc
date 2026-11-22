# Hifinis 2.5.16 多输出 / 立体声对（Stereo Pair）实现方式分析报告

> 样本：仅使用 Barbara 上传的 `apk/Hifinis-2.5.16.apks`（SHA256 `a23d1d763c6bf07fb3e5b37cba560b5ae173d37be7837891d01facef216db46e`，23 MB）。
> 只做静态分析事实记录：未修改原文件、未安装、未运行、未把 Hifinis 的任何代码复制进我们的 POC。
> 日期：2026-11-22（UTC）。分析机：Ubuntu 24.04，Temurin OpenJDK 17.0.21.1（注：本机原先 apt 装的 Java 17 已不在，`java` 命令不存在，改从 Adoptium 下载 JRE 17 完成本次分析），jadx 1.5.2，apktool 2.11.1。
> 注意版本差异：多输出功能首见于 1.1.0，本次样本是更新的 2.5.16；以下结论针对 2.5.16，1.1.0 的实现细节可能不同。

## 0. 结论先行

- **多输出不是 Java 的 AudioTrack，而是 native 的 `auralis::MultiOut`**：一个 C++ 类，在 `libauralis_audio.so` 里，每个额外输出对应一个 **Slot**，每个 Slot 独立开一条 **Oboe（AAudio）流**，用 `AAudioStreamBuilder_setDeviceId(deviceId)` 指到具体设备。
- **主输出不走 MultiOut**：主路是 Media3（ExoPlayer）正常播放；MultiOut 只负责“额外输出”。主路的 PCM 通过一个 Media3 的 `BaseAudioProcessor` 探针（`W2/R2.java`，日志 tag `HifinisTap`）被复制一份，喂给 MultiOut 的各个 Slot——所以额外输出拿到的永远是同一份已解码 PCM。
- **声道拆分在 native 层做**：每个 Slot 有 gain（音量）、channel（0=立体声 / 1=左 / 2=右 / 3=单声道 / 4=左右互换）、delayMs 三个参数，由 Kotlin 侧通过 `multiSetSlot(slot, gain, channel, delayMs)` 写入 Slot 结构体，Oboe 回调 `Slot::onAudioReady` 读 PCM 时实时应用。我们的 POC 目前在 Java 层拆声道，思路等价，但 Hifinis 的做法更底层、延迟更小。
- **同步 = 全部向最慢的那个对齐**：Kotlin 侧每轮 `reconcile` 用 `multiStatus(slot, float[6])` 读回每个 Slot 实测的延迟 + 缓冲毫秒数，取所有输出里的最大值，每路的延迟 = 最大值 − 本路值（±8 ms 迟滞，防抖动），再加用户手动 trim（±400 ms）。不是测一次就完事，是持续 reconcile。
- **“只有主设备保持 bit-perfect” 的含义**：主路可以走 bit-perfect 专用流（硬件采样率一致才开，否则回落普通路径）；额外输出走“BIT-PERFECT mixer”——同一份 PCM 在 native 层按各 Slot 的采样率做重采样（`ResamplerSpec` / FFmpeg `swr_convert`），并按各 Slot 的增益/声道处理。所谓“微调”就是重采样 + 增益 + 声道变换，不是改主路。
- **不需要 root / 特权**：普通应用权限即可。Manifest 里没有 `MODIFY_AUDIO_ROUTING`，没有系统签名权限；只用了 `BLUETOOTH_CONNECT`、`READ_MEDIA_AUDIO`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`RECORD_AUDIO`（后者应为其他功能）、USB host 特性。关键在于它**自己拥有播放管线**（解码 + Oboe 输出），而不是去劫持系统音频。

---

## 1. 样本与工具链

| 项目 | 值 |
|---|---|
| 文件 | `apk/Hifinis-2.5.16.apks`（APK bundle，4 个分包） |
| 大小 | 23 MB（`.apks`）；`base.apk` 15,405,707 字节，507 个文件 |
| SHA256 | `a23d1d76…16db46e`（已核对一致） |
| dex | 2 个：`classes.dex` 12,656,480 字节、`classes2.dex` 1,632,108 字节；jadx 共处理 8,072 个类，输出 11,681 个 `.java` 文件，52 个反编译错误 |
| 包名 / 版本 | `com.curvio.hifinis` / versionName `2.5.16` / versionCode `45` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| native 库 | 15 个 `.so`，全部仅 `lib/arm64-v8a`（见 §2） |
| 应用类 | `com.pairip.application.Application`（注意：不是 `com.auralis…`，见 §1.1） |
| 主 Activity / 服务 | `com.auralis.player.MainActivity`；`PlaybackService`（Media3 `MediaSessionService`，`mediaPlayback` 前台服务）、`NativePlaybackService` |

### 执行的命令与结果

```bash
java -jar tools/apktool.jar d extracted/base.apk -o apktool-out
# 退出码 0。apktool-out/ 136 MB，13,594 个 .smali。日志：analysis/apktool.log

java -Xmx3g -cp tools/jadx/lib/jadx-1.5.2-all.jar jadx.cli.JadxCLI -j 2 --no-res -d jadx-out extracted/base.apk
# 全量成功：8,072 类，11,681 个 .java，73 MB，52 个错误（ERROR - finished with errors, count: 52）。
# 注：jadx 命令行不接受 -Xmx 参数（Unknown option），改用 java -cp 直接调 JadxCLI。
```

### 1.1 混淆情况

- **Kotlin 业务代码基本未混淆类名**：`com.auralis.player.audio.NativeAudioEngine`、`com.auralis.player.playback.PlaybackService` 等完整保留；但 UI/业务逻辑层大量使用单字母包名（`A2/C0075o1.java`、`W2/R2.java`、`R2/EnumC0755d.java`），是 R8 的包名扁平化 + 字段名混淆（`f858a`、`f864g` 等），方法名多为单字母。关键枚举的常量名在 static 初始化块里是明文，可还原（见 §3）。
- 应用类 `com.pairip.application.Application`：`pairip` 应为开发者自研的应用框架/启动器（PairIP），与音频无关；未深挖。

---

## 2. Native 库一览（`split_config.arm64_v8a.apk`）

| 库 | 大小 | 作用（实测证据） |
|---|---|---|
| `libauralis_audio.so` | 694 KB | **音频引擎本体**：JNI 类 `com.auralis.player.audio.NativeAudioEngine`（143 个 native 方法）、`auralis::MultiOut` / `MultiOut::Slot`、Oboe 流、FFmpeg 解码（`AMediaCodec`/`AMediaExtractor`）、`ResamplerSpec`、USB UAC sink。见 §4–§6 |
| `libhifinis_core.so` | 348 KB | **Rust 库**，仅 6 个导出：`hc_api_version`、`hc_open`、`hc_info`、`hc_read`、`hc_seek`、`hc_close`；字符串含 rustc 1.8x 路径与 `miniz_oxide`。**作用未确认**：Java 层没有任何 `loadLibrary("hifinis_core")` 调用，也没有它的 JNI 符号；可能是被 `libauralis_audio` 内部 dlopen，或为其他模块预留。未做进一步推断 |
| `liboboe.so` | 290 KB | 标准 **Oboe** 库（`oboe::AudioStream`、`AAudioStreamBuilder_setDeviceId`、`getTimestamp`、`calculateLatencyMillis` 等）。`libauralis_audio` 只动态导入了 `oboe::AudioStreamBuilder::openStream` + `convertToText`，其余 Oboe 代码静态链接在 `libauralis_audio` 内部 |
| `libavcodec.so` / `libavformat.so` / `libavfilter.so` / `libavutil.so` / `libswresample.so` / `libswscale.so` | 8.0 / 2.0 / 2.8 / 0.5 / 0.08 / 0.3 MB | FFmpeg：解码 + 重采样（`swr_convert`）。`libswresample` 就是额外输出“微调”里重采样的来源之一 |
| `libmp3lame.so` | 225 KB | MP3 编码（应为 Audio Studio / 导出功能） |
| `libtranslate_jni.so` | 16 MB | ML Kit 翻译（歌词翻译），与音频无关 |
| `liblanguage_id_l2c_jni.so` | 1.0 MB | ML Kit 语言识别，与音频无关 |
| `libc++_shared.so`、`libandroidx.graphics.path.so`、`libdatastore_shared_counter.so` | — | 运行时/Compose/数据存储支持库 |

---

## 3. 多输出模型（Kotlin 侧）

### 3.1 中心类：`A2/C0075o1.java`（PlaybackService 里叫 `multiOutputHub`）

`PlaybackService.g()` 返回的就是它（字段名 `multiOutputHub`）。它持有：

- `AudioManager`（`getDevices(2)` 枚举输出设备，`2` = `GET_DEVICES_OUTPUTS`）
- `SharedPreferences("multi_output", 0)`：每设备存 `gain:<key>`（音量 0–1）和 `trim:<key>`（手动同步修正，−400…+400 ms）
- 3 个 Slot 的状态数组：`f760E: String[3]`（设备 key）、`f761F: int[3]`（设备 id）、`f765J: float[3]`（打开时的基准毫秒数）、`K: int[3]`
- 拒绝集合 `f762G`：打不开的设备记在这里，UI 显示 `"Android won't play here at the same time"`，不再重试
- 延迟缓存 `f763H`（上次算出的每设备延迟，用于 ±8 ms 迟滞）

**最多 3 个额外输出**：`listK0 = …k0(list, 3)`，且 native 的 `MultiOut::open/close/setSlot` 里 slot 索引都以 `cmp slot, #2` 校验（0–2）。

### 3.2 输出的表示：`A2/C0092u1.java`

每个输出是一条不可变记录（字段名已按构造器参数名还原）：

| 字段 | 含义 |
|---|---|
| `key`（`f858a`） | 唯一键，格式 `KIND:名称`，如 `BLUETOOTH:xxx`（`C0075o1.e()` 生成） |
| `deviceId`（`f859b`，方法 `c()`） | `AudioDeviceInfo.getId()` 的值，直接传给 native / Oboe `setDeviceId` |
| `name`（`f860c`，方法 `f()`） | 显示名：产品名；为空则用种类名；`h2w` 特判为 `"Wired headphones"` |
| `kind`（`f861d`，方法 `e()`） | `EnumC0755d`（见下） |
| `isMain`（`f862e`，方法 `h()`） | 是否主输出 |
| `selected`（`f863f`，方法 `g()`） | 是否勾选播放 |
| `gain`（`f864g`） | 音量 0–1（SharedPreferences `gain:`） |
| `channel`（`f865h`） | `EnumC0078p1`（见下） |
| `trim`（`i`） | 手动同步修正 ms（SharedPreferences `trim:`，±400） |
| `balance`（`f866j`，方法 `b()`） | 整数，传给 `multiSetMain` 的第 3 个参数（含义未确认，疑似声像/平衡） |
| `status`（`f867k`） | UI 状态文案，如 `"Playing · in sync (+12 ms)"`、`"Tap to play here too"`、`"Main output"`、`"Playing here"`、`"Not playing"` |
| `refused`（`f868l`）、`share`（`f869m`） | 是否被系统拒绝 / 是否参与“与其他应用共享” |

### 3.3 两个关键枚举（static 初始化块明文还原）

**`R2/EnumC0755d.java` —— 输出种类**：`AUTO(0, "Automatic")`、`BLUETOOTH(1, "Bluetooth")`、`USB(2, "USB DAC")`、`WIRED(3, "Wired / AUX")`、`SPEAKER(4, "Phone speaker")`。

类型映射（`C0075o1.g(int type)` + `PlaybackService.f()`）：

| `AudioDeviceInfo` type | 种类 |
|---|---|
| 2（`TYPE_BUILTIN_SPEAKER`） | SPEAKER |
| 3 / 4（有线耳机/耳麦） | WIRED |
| 8（`TYPE_BLUETOOTH_A2DP`）、26 / 27（BLE 耳机/广播） | BLUETOOTH |
| 11 / 22 / 12（USB 设备/配件/耳机） | USB |

**`A2/EnumC0078p1.java` —— 声道模式**：`STEREO(0, "Stereo")`、`LEFT(1, "Left")`、`RIGHT(2, "Right")`、`MONO(3, "Mono")`、`SWAP(4, "Swap L/R")`。`f797e` 就是传给 native 的 int。

**`R2/EnumC0754c.java` —— 引擎模式**：`ANDROID(0)`、`NATIVE(1)`、`USB_EXCLUSIVE(2)`。同步基准延迟按引擎模式不同而不同（见 §5）。

### 3.4 设备枚举与“主输出”选择

`C0075o1.k()`（`reconcile`，`synchronized`）每轮：

1. `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`，按类型映射成 kind，去重后 key 为 `KIND:名称`；
2. USB 独占模式时，把当前 USB DAC 拼成一个 `USB:<名>` 的虚拟设备；
3. 主输出 = 当前引擎模式 + 路由监听（`C0104y1`）决定的 kind 对应的设备；用户点 “Play only here” 会产生一个 `c0092u15`（pin），强制主路只走它；
4. 额外输出 = 勾选的非主设备，最多 3 个。

**会话级**：与 1.1.0 发行说明一致——选择只在当前会话有效；设备插拔时回到仅主输出（代码里 `b()` 会清空 `M/N/f762G/f763H` 缓存；`k()` 在设备变化时重算）。

### 3.5 一个容易忽略的机制：静音“presence track”（`A2/d2.java`）

这就是 1.1.0 发行说明里 **“Share with other apps”** 的实现：

```java
// A2/d2.java：a(Context)
AudioTrack 48kHz 单声道、PCM 16-bit、MODE_STATIC、写 24000 个 short（0.5 秒静音）、setLoopPoints(0, 24000, -1) 循环
→ 找到 type==2（内置扬声器）的 AudioDeviceInfo → track.setPreferredDevice(扬声器)
Log.i("HifinisPresence", "presence track ready (silent, looped, speaker)");
```

即用一条**静音循环的 AudioTrack 占住扬声器**，让 Android 把其他应用的音频继续路由到扬声器，而 Hifinis 自己的音乐走别的设备。用的就是公开 API `AudioTrack.setPreferredDevice()`，普通应用权限，无 root、无系统 API。我们的 POC 不需要这个，但它解释了 Hifinis 如何做到“自己播自己的设备、别的 App 播别处”。

---

## 4. 播放管线：同一份 PCM 如何到 N 个输出

### 4.1 主路：Media3 + native 引擎

- 播放服务是 Media3（ExoPlayer）的 `MediaSessionService`；解码在 native 引擎（FFmpeg / `AMediaCodec` / `AMediaExtractor`，见 `libauralis_audio.so` 的未定义符号）。
- 主路输出分两条（按 `EnumC0754c`）：
  - **NATIVE / ANDROID**：native 引擎经 Oboe（AAudio）出声；可开 bit-perfect 流（见 §6）。
  - **USB_EXCLUSIVE**：`nativePlayUsbExclusive(…22 个参数…)` + `UsbUacSink`，直接写 USB 音频类端点，绕过 Android mixer。

### 4.2 探针：`W2/R2.java`（`HifinisTap`）

```java
// W2/R2 extends BaseAudioProcessor（Media3 音频处理链里的一个 tap）
onConfigure: 记下 channelCount / sampleRate / encoding（16-bit?）
// queueInput：每块 PCM 到达时
copy buffer → replaceOutputBuffer（原样透传，不改主路）
if (toneActive())  nativeTonePcm(...)
nativeSignalFeedPcm(...)          // 信号分析/示波器
if (multiMainActive()) nativeMultiMainPcm(buffer, position, remaining, is16bit, channels, sampleRate)
```

**结论：额外输出的 PCM 来源就是主路同一份解码后 PCM**，在 Media3 处理链里被“tap”出来。主路本身不受 MultiOut 影响；`multiMainActive()` 为真（即至少开了一个额外输出）时才分流。

### 4.3 Native 侧：`auralis::MultiOut`（`libauralis_audio.so`）

动态符号（`nm -D`，`analysis/nm-auralis.txt`）实测：

```
_ZN7auralis8MultiOut4openEiibf            MultiOut::open(int slot, int deviceId, bool exclusive, float baseMs)
_ZN7auralis8MultiOut5closeEi              MultiOut::close(int slot)
_ZN7auralis8MultiOut10setPlayingEb        MultiOut::setPlaying(bool)
_ZN7auralis8MultiOut4Slot12onAudioReadyEPN4oboe11AudioStreamEPvi
_ZN7auralis8MultiOut4Slot17onErrorAfterCloseEPN4oboe11AudioStreamENS2_6ResultE
_ZN7auralis8MultiOut8feedWithIZNS0_4feedEPKfiiifEUliE_EEvT_iii      // float PCM
_ZN7auralis8MultiOut8feedWithIZNS0_7feedS16EPKsiiiEUliE_EEvT_iii   // 16-bit PCM
_ZN7auralis8MultiOut8mainWithIZNS0_11processMainEPfiiiEUliE_...   // 主路 PCM 分发（float）
_ZN7auralis8MultiOut8mainWithIZNS0_14processMainS16EPsiiiEUliE_...// 主路 PCM 分发（16-bit）
_ZN7auralis8MultiOut4SlotE  + _ZTVN7auralis8MultiOut4SlotE / _ZTIN7auralis8MultiOut4SlotE
_ZN7auralis8MultiOutC2Ev / D2Ev
_ZZN7auralis8multiOutEvE1m  （单例）
```

JNI 入口（`com.auralis.player.audio.NativeAudioEngine`，`analysis/nm-auralis.txt`）：

```
nativeMultiOpen(int slot, int deviceId, boolean exclusive, float baseMs) -> int
nativeMultiSetSlot(int slot, float gain, int channel, float delayMs)
nativeMultiSetMain(float gain, int channel, float balance, boolean muted?)
nativeMultiMainPcm(ByteBuffer, int pos, int len, boolean is16bit, int channels, int sampleRate)
nativeMultiMainActive() -> boolean
nativeMultiStatus(int slot, float[] out6)
nativeMultiFlush()
nativeMultiClose(int slot)
nativeMultiSetPlaying(boolean)
nativeSetOutputDeviceId(int deviceId)   // 注意：本版 Kotlin 里没有任何调用方（死代码/遗留）
nativeSetRawOutput(boolean) / nativeSetWirelessOutput(boolean)
```

反汇编核实（Capstone AArch64，`/tmp/a64dis.py`，`nm -D` 定址）：

- **Slot 结构体**：步长 `0xb0`（176 字节），3 个 Slot 连续存放。`nativeMultiSetSlot` 直接写 Slot 字段：`+0x78 = gain（float）`、`+0x7c = delayMs（float，fmaxnm(…, 0) 钳 ≥0）`、`+0x90 = channel（int）`，均为原子存储（`stlr`）——即**音量/声道/延迟是热更新的**，改 UI 不用重开流，Oboe 回调下次就生效。
- **`MultiOut::open`**：slot ≥ 3 直接返回 0；内部用互斥锁；日志格式 `"slot %d open dev %d -> %d deep=%d excl=%d buf=%d/%d burst=%d base=%.0fms"`——即按 deviceId、exclusive 标志、baseMs 建 Oboe 流，记录深度/缓冲/每 burst 帧数。Oboe 流的创建走 `oboe::AudioStreamBuilder::openStream`（`libauralis_audio` 唯一动态导入的 Oboe 符号），设备选择就是 Oboe 的 `setDeviceId(deviceId)`（`liboboe.so` 导出 `AAudioStreamBuilder_setDeviceId`）。
- **`Slot::onAudioReady(oboe::AudioStream*, void*, int)`**：Oboe 回调；从共享环形缓冲按本 Slot 的读偏移取 PCM，乘 gain、按 channel 取 L/R/混合/互换、做 ±1.0 钳制（反汇编见 `0.5 / -0.5` 常数与钳制分支，对应 `(L+R)/2` 的单声道混合）。延迟就是读偏移：本 Slot 的 PCM 比主路晚 `delayMs` 才读。
- **`feedWith` / `feedS16` / `mainWith` / `processMain`**：模板化的 PCM 喂入/主路分发（float 与 S16 两套），即主路 PCM 到各 Slot 环形缓冲的复制点。

### 4.4 与我们 POC 的对照

| 点 | Hifinis | 我们的 POC（已实现 Hifinis 式多输出） |
|---|---|---|
| 输出数 | 主路 + 最多 3 个额外 Slot | 2 路（可扩到 N 路，结构已是列表） |
| 每路流 | 每 Slot 一条 Oboe（AAudio）流，`setDeviceId` | 每路一条 `AudioTrack`，`setPreferredDevice()` |
| 声道拆分 | native 回调里按 channel 参数实时做 | Java 层按 `ChannelMode` 拆（等价，延迟略大） |
| 同步 | 实测延迟 + 缓冲，持续 reconcile（§5） | 每路先写静音帧（固定延迟），无实测反馈 |
| 主路 | Media3 + native 引擎，bit-perfect 可选 | 无主路概念，两路都是“额外” |
| 权限 | 普通应用，无 root | 需 priv-app 才能调 `setPreferredDevicesForStrategy`；单路 `setPreferredDevice` 本身是公开 API |

**可直接借鉴的**：① 用 Oboe/AAudio 代替 AudioTrack 做额外输出（更低延迟、可取 `getTimestamp` 实测延迟）；② 同步算法（§5）；③ 热更新 gain/channel/delay 而不重开流；④ `multiOpen` 失败要记“拒绝”并给用户明确提示，而不是静默失败。

---

## 5. 同步：实测延迟 + 向最慢者对齐

核心在 `C0075o1.k()`（reconcile）与 `h()` / `c()` / `l()` 三个辅助函数。

### 5.1 基准延迟估计 `h(engine, kind)` 与 `c(kind)`

```java
// h(EnumC0754c engine, EnumC0755d kind)：引擎 + 种类 的基础延迟
engine==ANDROID → 260ms；NATIVE → 60ms；USB_EXCLUSIVE → 25ms
再加：USB_EXCLUSIVE 引擎时 +0；否则 kind==BLUETOOTH +200ms，kind==SPEAKER +25ms，其他 +12ms
// 例：NATIVE + 蓝牙 = 60+200 = 260ms；ANDROID + 蓝牙 = 260+200 = 460ms

// c(kind)：multiOpen 时的 baseMs（缓冲基准）
// SharedPreferences? 不，是按 kind 的字符串档位：low=30 / max=150 / min=20 / high=80；
// 默认：BLUETOOTH=60ms，其他=40ms
```

### 5.2 实测：`multiStatus(slot, float[6])`

Kotlin 只用其中两个下标（其余下标含义未确认，见下）：

- **`out[1]` = 实测延迟 ms**：native 里调 Oboe 流的 `getTimestamp` 类接口取回（反汇编：`Slot+0x40` 的流对象虚表 `+0xb8`，返回 double 转 float）；若 ≤ 0 则按 kind 回落：蓝牙 220ms（200+20）、扬声器 45ms（25+20）、其他 32ms（12+20）。
- **`out[4]` = 缓冲毫秒数**：native 里由缓冲帧数换算（反汇编见帧数→毫秒的乘加）；若 ≤ 0 则回落到 `c(kind)`。

其余下标（`out[0]/[2]/[3]/[5]`）Kotlin 未使用；反汇编显示 `out[5]` 像是缓冲帧数（虚表 `+0x98`，失败时 −1），其余未确认——报告里不编造。

### 5.3 对齐算法（`k()` 后半段）

```java
// 1. 每路总延迟 = out[4]（缓冲） + out[1]（实测延迟） − trim（用户手动修正，±400ms）
//    主路（若存在且被勾选）也一样算：h(engine, kind) − trim
// 2. 目标 = 所有路总延迟的最大值 maxTotal
// 3. 每路延迟 delayMs = maxTotal − 本路总延迟（<0 则钳 0），
//    且与上次值相比变化 <8ms 时保持旧值（迟滞，防抖动）——函数 l()
// 4. multiSetSlot(slot, gain, channel, delayMs) 热写入 Slot；
//    UI 文案："Playing · in sync (+12 ms)"
```

**“auto” 同步就是这个**：不依赖用户手调，持续用实测延迟重算；用户 trim 只是叠加修正。另有 `multiSetMain(gain, channel, balance, muted?)`：主路在“仅在此播放”时 gain/channel 直接改主路；多输出开着时主路 gain 取 `f10`（即 `h()` 算出的主路基准？此处为 `f6=1.0f` 的变量，含义未完全确认——见 §7）。

### 5.4 打开失败的处理

`multiOpen` 返回 0 或返回的 deviceId 与请求不一致 → `multiClose(slot)`，把该设备 key 加入拒绝集合 `f762G`，UI 显示 `"Android won't play here at the same time"`。即 **Android 拒绝第二路 A2DP 时，Hifinis 的做法是明确告诉用户、并记住不再重试**，而不是反复尝试。这与我们 POC 里要验证的点完全一致：双 A2DP 能否同时开，取决于系统/厂商 HAL，不是应用能强求的。

---

## 6. Bit-perfect 与“额外输出的微调”

- **主路 bit-perfect**：native 字符串实测：
  - `"bit-perfect stream: hardware rate %d Hz (0 = not reported), format %d"`
  - `"bit-perfect stream: hardware runs at %d Hz, not %d Hz - Android resamples, using the normal path"`
  - `"bit-perfect stream refused (fmt %d @ %d Hz) - using the normal path"`
  - `"bit-perfect stream came back %d Hz fmt %d - not exact, using the normal path"`
  - `"not exact: source is not the file (bits=%d: lossy/float/resampled/crossfade)"`
  - `"BIT-PERFECT mixer"` / `"Android mixer"`
  - `"reopening at the device rate (Hifinis converts)"` / `"sending NO audio at this rate; the engine replays at one it really runs"`
  - 另有 `nativeBitPerfectActive()`、`nativeSetBitPerfectEncoding(int)`、`nativeBitExactRate()` / `nativeBitExactFrames()`（bit-exact 自检：速率 >0 且帧数 ≥ 速率/2 才算通过）。
  
  含义：主路先尝试按文件原采样率/位深开一条“精确”流；硬件回报的速率/格式不一致、或源不是无损文件本身（有损/浮点/已重采样/交叉淡化）时，**回落到普通路径**（Android mixer / 重采样）。
- **额外输出的“微调”**：额外输出不走 bit-perfect 流。同一份 PCM 在 `MultiOut` 里按各 Slot 的目标采样率重采样（`auralis::IDecoder::setResampler(ResamplerSpec)`、`FfmpegDecoder::setResampler`、FFmpeg `swr_convert`，`libswresample.so`），再乘 gain、按 channel 取声道。所谓“adjusted very slightly to stay in sync”在代码层面就是：**重采样到 Slot 的速率 + 增益 + 声道变换 + 延迟对齐**，主路的 bit-perfect 不受影响。
- **USB 独占**：`nativePlayUsbExclusive` + `UsbUacSink`（字符串：`"UAC sink started: ep=0x%02x %dHz %dch %dB/sample hs=%d pkt/s=%d uac%d sync=%s fb=0x%02x"`），直接写 USB 音频端点并用反馈端点（feedback）做时钟同步；`nativeSetUsbVolume`、`nativeRebindUsbInterface` 配套。

---

## 7. 未确认 / 存疑的点

- `multiSetMain` 的第 3 个参数（Kotlin 传 `c0092u112.b()`，即 `C0092u1.balance`，int）和第 4 个 bool：疑似“声像/平衡”与“静音”，但未在 native 侧核实——**未确认**。
- `multiStatus` 的 `out[0]/[2]/[3]/[5]`：Kotlin 未使用；反汇编提示 `[5]` 可能是缓冲帧数，`[0]` 可能是 active 标志——**未确认**。
- `multiOpen` 第 3 个 bool（Kotlin：`kind != BLUETOOTH && baseMs < 80 ? false : true`）：native 日志写作 `excl=%d`，即应为 Oboe 的 exclusive 共享模式标志——**高度可能，但未在反汇编里逐条核实 builder 调用**（Oboe 静态链接在 `libauralis_audio` 内，builder 调用是内部直接跳转，未逐一解析）。
- `libhifinis_core.so`（Rust，`hc_*`）的作用：**未确认**（见 §2）。
- 主路在 NATIVE 引擎下是否也经 Oboe：native 有 `AudioPipeline::openBitPerfect` / `TunedPipeline::openBitPerfect` 与 Oboe 流管理，但主路 Oboe 流的创建点未逐一追踪——**未确认细节**，不影响多输出结论。
- `nativeSetOutputDeviceId` 在 2.5.16 的 Kotlin 里无调用方：单输出时代遗留接口的可能性大——**未确认**。

---

## 8. 对我们 POC 的具体建议（只写做法，不抄代码）

1. **架构**：保持“主路 + 额外 Slot”的分离。POC 现在两路都是额外路，可以接受；若要更像 Hifinis，应有一条主路（正常播放）+ N 条额外路，主路 PCM 经 tap 复制给额外路。
2. **输出层**：把额外路从 `AudioTrack` 换成 **Oboe / AAudio**（`setDeviceId(deviceId)`，`AudioDeviceInfo.getId()` 直接可用），可拿到 `getTimestamp`/`calculateLatencyMillis` 做实测延迟；Java 层 `AudioTrack.getTimestamp` 也能近似，但 Oboe 更直接。
3. **同步**：照抄 §5.3 的算法——每路总延迟 = 实测延迟 + 缓冲毫秒 − trim；目标 = 最大值；每路延迟 = 目标 − 本路；变化 <8 ms 时保持旧值；用户 trim ±400 ms。不要只靠固定静音帧。
4. **热更新**：gain/channel/delay 改 UI 时不要重开流，直接写 Slot 参数（POC 目前每次改设置都重建 Track，可改为热更新）。
5. **失败处理**：`multiOpen`/`setPreferredDevice` 失败时明确记“拒绝”、UI 提示“Android 不允许同时播这里”，不要静默重试——这正是 Hifinis 的做法，也是我们 POC 要验证的核心不确定点。
6. **不要做的事**：不要尝试把 Hifinis 的 native 库或反编译代码搬进 POC；不要碰它的 `libhifinis_core.so`（作用未确认）；不要以为换了 Oboe 就能绕过系统对双 A2DP 的限制——Hifinis 自己也处理了“被拒绝”的情况。

---

## 附录：产物与原始输出

| 产物 | 路径 |
|---|---|
| 本报告 | `~/workspace/bt-stereo-split-poc/hifinis-decompile/HIFINIS-REPORT.md` |
| apktool 输出（136 MB，13,594 smali） | `~/workspace/bt-stereo-split-poc/hifinis-decompile/apktool-out/` |
| jadx 输出（73 MB，11,681 java，52 错误） | `~/workspace/bt-stereo-split-poc/hifinis-decompile/jadx-out/` |
| grep / 符号 / 日志字符串原始输出 | `~/workspace/bt-stereo-split-poc/hifinis-decompile/analysis/notes.txt`（172 行）、`analysis/nm-auralis.txt`、`analysis/nm-hifinis-core.txt`、`analysis/auralis-strings-filtered.txt` |
| 原文件（未修改） | `~/workspace/bt-stereo-split-poc/hifinis-decompile/apk/Hifinis-2.5.16.apks` |
| 解出的分包 | `~/workspace/bt-stereo-split-poc/hifinis-decompile/extracted/`（`base.apk`、`split_config.arm64_v8a.apk` 等） |
| 解出的 native 库 | `~/workspace/bt-stereo-split-poc/hifinis-decompile/extracted_libs/lib/arm64-v8a/` |

关键文件路径（jadx-out 内）：
- `sources/com/auralis/player/audio/NativeAudioEngine.java`（JNI 桥，143 个 native 方法）
- `sources/A2/C0075o1.java`（multiOutputHub，reconcile/sync 算法）
- `sources/A2/C0092u1.java`（输出记录）、`sources/A2/EnumC0078p1.java`（声道枚举）、`sources/R2/EnumC0755d.java`（种类枚举）、`sources/R2/EnumC0754c.java`（引擎枚举）
- `sources/W2/R2.java`（Media3 PCM 探针 `HifinisTap`）、`sources/A2/d2.java`（静音 presence track）
- `sources/com/auralis/player/playback/PlaybackService.java`（设备类型映射 `f()`、multiOutputHub 持有）

未做事项：未修改原 `.apks`、未安装、未运行、未把 Hifinis 代码复制进 POC；未从任何其他来源下载 Hifinis。
