# Oboe / AAudio 迁移路径（v2 之后，尚未实施）

> 来源：`hifinis-decompile/HIFINIS-REPORT.md` §4.3、§5、§8。
> 本文档只写“要改什么、为什么”，**不含任何 native 代码**——本地没有
> Android SDK / NDK，native 代码在这里编不出来，也没法验证，所以刻意
> 不往 app 模块里加编不过的 C++ 文件。真正的迁移在 GitHub Actions
> （有 Android SDK + NDK 的环境）里做。

## 为什么要迁

Hifinis 的多输出是 native 的 `auralis::MultiOut`：每个额外输出是一条
独立的 Oboe（AAudio）流，`setDeviceId(deviceId)` 指到具体设备，声道/
增益/延迟是 Slot 结构体里的原子字段，Oboe 回调里实时应用。

本 POC v2 仍用 Java 的 `AudioTrack`：

| 点 | AudioTrack（v2 现状） | Oboe / AAudio（目标） |
|---|---|---|
| 每路流 | 一条 AudioTrack，`setPreferredDevice(AudioDeviceInfo)` | 一条 Oboe 流，`AAudioStreamBuilder_setDeviceId(AudioDeviceInfo.getId())` |
| 实测延迟 | `AudioTrack.getTimestamp()` 近似（已写入未出声帧数 + 时间戳年龄），见 `SplitEngine.measureLatency()` | `AAudioStream_getTimestamp()` / `oboe::AudioStream::calculateLatencyMillis()`，是 Oboe 官方的延迟接口，更准 |
| 声道/增益/延迟 | Java 写线程每 chunk 读 volatile 字段，chunk 粒度 4096 帧（约 85ms @48kHz） | native 回调每 burst 应用，粒度更细，延迟更小 |
| 采样率 | 全路固定 48kHz，44.1kHz 文件会音调偏差 | 每 Slot 可按设备速率重采样（Hifinis 用 FFmpeg `swr_convert`，见报告 §6） |
| 权限 | 不变，普通应用权限 + `setPreferredDevice` 公开 API | 不变，同样不需要 root / `MODIFY_AUDIO_ROUTING` |

**迁移不改变结论**：Oboe 绕不过系统对双 A2DP 的限制。Hifinis 自己也
处理了“被拒绝”的情况（`multiOpen` 失败 → 记拒绝、UI 明确提示），
v2 的拒绝处理（`SplitEngine.refuse()`）就是按这个做的，迁移后保持。

## 要改什么

### 1. 新增 native 模块（`app/src/main/cpp/`）

- `CMakeLists.txt`：一个 `multiout` 共享库，链接 `oboe`（可用 prefab：
  `find_package(oboe REQUIRED CONFIG)`，或直接用 NDK 自带的 AAudio
  C API `aaudio`，不依赖 Oboe 库——Hifinis 用 Oboe，但 AAudio 已足够，
  且少一个依赖）。
- `multiout.cpp`：一个 `MultiOut` 类，对应 Hifinis 的 `auralis::MultiOut`
  （行为复现，不抄代码）：
  - `open(slot, deviceId, baseMs)`：按 `deviceId` 建一条 AAudio 输出流
    （`AAudioStreamBuilder_setDeviceId`），slot 只允许 0..2（最多 3 个
    额外输出，与 Hifinis 一致）；失败返回 0。
  - `Slot` 结构：`gain`、`channel`（0=立体声 / 1=左 / 2=右 / 3=单声道 /
    4=左右互换）、`delayMs` 三个原子字段；`setSlot(slot, gain, channel,
    delayMs)` 直接写字段，回调下次即生效（热更新，机制与 v2 的 volatile
    字段相同，只是移到 native）。
  - `feed(pcm, frames, sampleRate)`：主路 PCM 复制进共享环形缓冲。
  - AAudio 数据回调：按本 Slot 的读偏移取 PCM，乘 gain、按 channel 取
    声道、钳制到 ±1.0；延迟 = 读偏移（本 Slot 比主路晚 `delayMs` 才读）。
  - `status(slot, out[6])`：`out[1]` = `getTimestamp` 实测延迟 ms，
    `out[4]` = 缓冲毫秒数（其余下标保留不用，与 Hifinis 的接口形状一致，
    避免以后对不上）。
- `multiout_jni.cpp`：JNI 桥，对应 `NativeAudioEngine` 的 multi* 方法：
  `nativeMultiOpen / nativeMultiSetSlot / nativeMultiMainPcm /
  nativeMultiStatus / nativeMultiClose / nativeMultiSetPlaying`。

### 2. Kotlin 侧（`SplitEngine.kt`）

- 新增 `NativeMultiOut`（`object`，`System.loadLibrary("multiout")`，
  `external fun` 声明上述 JNI 方法）。
- `playOutputs()` 的写线程改成：主路 PCM 不再直接写 AudioTrack，而是
  一次 `nativeMultiMainPcm()` 喂给 native；每路不再建 AudioTrack，
  改为 `nativeMultiOpen(slot, device.id, baseMs)`。
- `reconcileLoop()` 的数据源从 `AudioTrack.getTimestamp()` 换成
  `nativeMultiStatus(slot, floatArray(6))`，算法本身不变：
  总延迟 = 实测 + 缓冲 − trim，目标 = 最大值，补偿 = 目标 − 本路，
  变化 <8ms 保持旧值，trim ±400ms。
- `updateOutput()` 改为调 `nativeMultiSetSlot()`，仍不重建流。
- `refuse()` 的触发点从“routedDevice 不符”换成“`nativeMultiOpen`
  返回 0 或返回的 deviceId 与请求不符”，其余不变。
- `buildTrack()`、`measureLatency()`（AudioTrack 版）删除；
  `trySetPreferredDevicesForStrategy()` 保留（priv-app 路径仍可用，
  与 Oboe 互不冲突）。

### 3. 构建（`app/build.gradle.kts`、`.github/workflows/android.yml`）

- `app/build.gradle.kts` 增加 `externalNativeBuild { cmake { ... } }`
  和 NDK 版本（例如 `ndkVersion = "26.1.10909125"`），`cmake` 块指向
  `app/src/main/cpp/CMakeLists.txt`。
- Actions 工作流增加一步 `setup-ndk`（`android-actions/setup-android`
  或直接用 `sdkmanager "ndk;<版本>"`），其余不变（JDK 17、Gradle 8.7、
  `assembleDebug`、上传 `app-debug.apk`）。
- 本地（本机）仍无法构建：没有 Android SDK / NDK，见 README。

## 为什么现在不做

1. **没有验证环境**：native 代码的对错只能在真机 + 双蓝牙设备上
   听出来，本地编过也证明不了同步是对的；先把 v2 的 AudioTrack 版
   在真机上测通（reconcile 日志、热更新、拒绝提示），确认“这台手机
   支持双 A2DP”这个前提，再投 native。
2. **v2 已覆盖行为**：声道模式、每路音量、trim、持续 reconcile、
   热更新、拒绝处理都已在 AudioTrack 上实现；Oboe 换来的是更低延迟
   和更准的实测延迟，不是新功能。
3. **风险**：AAudio 在部分厂商 ROM 上对第二路 A2DP 的行为与 AudioTrack
   不同（有的 ROM 只允许一条 AAudio 流走蓝牙），迁移后要重新做一遍
   “双路能不能同时开”的验证，不能假设一定更好。

## 验收标准（迁移完成后）

- 同一份测试音，两路同时出声，reconcile 日志里两路补偿延迟稳定在
  ±8ms 抖动带内，不再漂移。
- 播放中改声道/音量/trim，下一秒内生效，无卡顿、无重建流的爆音。
- 断开一路蓝牙，UI 出现“Android 不允许同时播这里”，另一路继续播。
