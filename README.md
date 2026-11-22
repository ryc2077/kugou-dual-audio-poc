# BT Split POC — 双蓝牙分左右声道概念验证（KernelSU 版）

> 先说结论：我先去 GitHub / XDA 上看了一圈（详见 `RESEARCH.md`），**没有现成的 Magisk / KernelSU 模块能直接用**，但有一个闭源 App（Hifinis）已经在应用层实现了你要的功能。所以本 POC 不重复造轮子去改蓝牙协议栈，而是做一个最小可验证的东西：一个特权播放器 App + 一个 KernelSU 模块，测清楚“你这台手机到底支不支持双 A2DP 分声道”。

## 先试最省事的（5 分钟，不用 root）

装 [Hifinis](https://github.com/Parivallalin/hifinis/releases)（GitHub Releases 里有 APK），放两首本地歌进去 → Now Playing → Outputs → 勾上两个蓝牙设备 → 每个设备把 Channel 设成 Left / Right。
- 如果这样就分开了：**这条路走得通，你手机支持**，POC 只是把它复现成开源可测的版本。
- 如果 Hifinis 里也只能响一个：先别折腾模块，大概率是你手机的音频 HAL 不支持双 A2DP，POC 也会在同一步失败。

## 本 POC 是什么

```
bt-stereo-split-poc/
├── RESEARCH.md                  # 调研结论：现成东西、为什么这样设计
├── app/                         # Android App 源码（Kotlin，minSdk 31 = Android 12）
│   └── src/main/java/com/btsplit/poc/
│       ├── MainActivity.kt      # 界面：选左右设备、播测试音、看日志
│       └── SplitEngine.kt       # 核心：拆 PCM + 双 AudioTrack + setPreferredDevice
├── kernelsu-module/             # KernelSU 模块（兼容 Magisk 模块格式，但按 KernelSU 写的）
│   ├── module.prop
│   ├── customize.sh             # 安装时把 APK 装进 /system/priv-app
│   ├── service.sh               # 开机后关 A2DP 硬件 offload
│   ├── post-fs-data.sh
│   ├── uninstall.sh
│   ├── diagnose.sh              # ★ 先跑这个诊断，再测 App
│   └── system/etc/permissions/privapp-permissions-com.btsplit.poc.xml
└── scripts/build-module-zip.sh  # 打包模块 zip
```

**工作原理**：App 生成测试音（左 440Hz / 右 660Hz），或解码你选的本地文件，拆成多路 PCM，开多个 `AudioTrack`，各 `setPreferredDevice()` 到一个蓝牙 A2DP 设备，同时起播。v2 增加持续 reconcile 同步：每 500ms 读每路实测延迟，向最慢的一路对齐（补偿延迟 ±8ms 迟滞），用户 trim ±400ms 叠加；播放中改声道/音量/trim 热更新，不重建 Track。

## Hifinis 式多输出（v2：reconcile 同步 + 热更新 + 拒绝处理）

只复现 Hifinis 2.5.16 公开描述的**行为**（反编译分析见
`hifinis-decompile/HIFINIS-REPORT.md` §5/§8），没有用 Hifinis 的任何代码
（它是闭源的），也没有碰酷狗。v2 的改动对照 Hifinis 的做法：

- **每路输出独立配置**（`SplitEngine.OutputConfig`）：目标蓝牙设备 + 声道模式 + 音量 + 同步修正 trim。
- **声道模式**（`SplitEngine.ChannelMode`，与 Hifinis 的 Outputs 设置同名同义）：
  - `STEREO` 立体声：原样输出 L+R；
  - `LEFT` 仅左声道：只输出 L（另一声道写静音）；
  - `RIGHT` 仅右声道：只输出 R；
  - `MONO` 单声道混音：输出 (L+R)/2（int 累加再除，避免 16-bit 溢出削波）。
  - v2 的 Track 统一建成 STEREO（见 `SplitEngine.buildTrack` 注释），所以四种模式之间可以热切换；若建成 MONO Track，切到 STEREO 就必须重建。
- **每路独立音量**：0–100%，通过 `AudioTrack.setVolume()` 实现，写线程每 chunk 检查，改动下个 chunk 生效。
- **同步修正 trim（复用 v1 的“延迟”滑杆）**：每路一个滑杆，范围 0..800，中间 400 = 0ms，即 trim −400..+400ms；trim 越大，该路总延迟越小，reconcile 给它的补偿延迟就越大（出声越晚）。这是 Hifinis §5 的 trim 语义，不是 v1 的“开播前一次性静音帧”。
- **Reconcile 同步（Hifinis §5.3，改编到 AudioTrack）**：播放中每 500ms 一轮，`SplitEngine.startReconcile()` 对每路读 `AudioTrack.getTimestamp()` 实测延迟 + Track 缓冲毫秒，减去用户 trim 得总延迟，取所有路的最大值作目标，每路补偿延迟 = 目标 − 本路总延迟（<0 钳 0），变化 <8ms 时保持旧值（±8ms 迟滞防抖动）。补偿延迟只写进 `OutputConfig.effectiveDelayMs`（volatile），写线程在下个 chunk 边界通过“插静音帧 / 跳 PCM 帧”应用，**不重建 Track、不把已写出的 PCM 倒回去重放**。
- **热更新**：`SplitEngine.updateOutput(index, mode, volume, trimMs)` 只写 volatile 字段，界面上的声道 Spinner、音量滑杆、trim 滑杆在播放中改动时会直接调它（`MainActivity.applyLiveUpdate()`），下个 chunk（4096 帧，约 85ms）即生效。未播放时调用无任何作用。
- **拒绝处理（Hifinis §5.4）**：建 Track 失败、起播失败、或起播 500ms 后 `routedDevice` 与目标不一致，都会停掉这一路并记进本次会话的拒绝集合，日志和界面都会显示“❌ Android 不允许同时播这里：<设备名>”，**本次会话不再重试这一路**，另一路继续播。点 Refresh 会清空拒绝记录（`SplitEngine.clearRefusedDevices()`），重连设备后可再试。
- **通用播放路径**：`SplitEngine.playOutputs(configs, stereoPcm)` —— 一份立体声 PCM（测试音先合成、本地文件先解码一次），按每路配置变换后喂给 N 个 AudioTrack，`CountDownLatch` 同时起播。旧的 `playTestTone(left, right)` / `playFile(uri, left, right)` 保留，内部改走新路径（左=仅左、右=仅右，右路 trim = 旧的 delayRightMs）。
- **界面**：每个输出各有一组控件——设备 Spinner、声道模式 Spinner、音量 SeekBar、trim SeekBar；Play Test Tone 和 Play File 都按界面上的设置播。

**它不是什么**：不是 Hifinis 的代码、不是酷狗的改版、不能劫持其他 App（Spotify / YouTube / 酷狗）的音频。它只播自己生成的测试音和你选的本地文件。

**测试方法（v2）**：
1. 默认设置（输出 1 = 仅左，输出 2 = 仅右）播测试音：左音箱只听到 440Hz 低音、右音箱只听到 660Hz 高音 = 分声道成功。
2. 看日志里的 `Reconcile（目标 …ms，向最慢者对齐）`：两路的补偿延迟应在一两轮后稳定下来，不再每轮大幅跳动；若某路补偿一直是 0、另一路很大，说明两路实测延迟确实差这么多，是正常的。
3. **播放中改声道**：把输出 1 从“仅左”切到“仅右”，应立刻听到左音箱变成 660Hz，且没有卡顿/重建的爆音；再切回“仅左”应恢复。
4. **播放中改音量/trim**：拖音量滑杆，对应音箱应单独变响/变轻；拖 trim 滑杆（+为更晚），reconcile 下一轮会重新算补偿，日志里能看到数值变化。
5. 把两路都改成 `STEREO` 再播：两个音箱都应听到两个频率，确认 Stereo 模式没把声道弄丢。
6. 把两路都改成 `MONO` 再播：两个音箱都应听到混合音，确认混音不削波、不爆音。
7. **拒绝测试**：只连一个蓝牙设备就播两路，或播到一半断开一路蓝牙，应看到“❌ Android 不允许同时播这里：<设备名>”，另一路继续播；点 Refresh 后再播，拒绝记录已清除，可再试一次。
8. Swap L/R 对调设备再测，确认左右跟着设置走。

**Oboe 迁移**：见 `OBOE-MIGRATION.md`。v2 仍用 AudioTrack（本地无 NDK，无法构建/验证 native 代码）；迁移到 Oboe/AAudio 能换来更低延迟和更准的实测延迟，但不改变“双 A2DP 取决于系统/HAL”的结论，待 v2 在真机上测通后再做。

**GitHub Actions**：`.github/workflows/android.yml` 在每次 push 到 main 时构建 debug APK（JDK 17 + Android SDK + Gradle 8.7，无 wrapper），产物 `app-debug.apk` 作为 artifact 上传。本地没有 Android SDK，所以**本次未在本地编译验证**，以 Actions 的构建结果为准。

**KernelSU 模块做的三件事**：
1. 把 App 装进 `/system/priv-app`，拿到 `MODIFY_AUDIO_ROUTING`（对应 `setPreferredDevicesForStrategy()`，普通 App 调不到）；
2. 开机后关 A2DP 硬件 offload——硬件 offload 只允许一路硬件流，关掉走软件编码才可能开两路；
3. 提供 `diagnose.sh`，不改系统、只读诊断。

> 注意：这是 **KernelSU** 版。KernelSU 的模块格式和 Magisk 基本兼容（`/data/adb/modules/<id>`、module.prop、customize.sh、service.sh），但不要用 Magisk 的 `magisk --hide` 之类命令，也不要指望 Zygisk。本模块没用到 Zygisk。

## 构建与安装（需要一台电脑 + Android Studio）

我这台机器上没有 Java / Android SDK，**没法在这里直接给你编译出 APK**，源码是完整的，你按下面步骤 10 分钟能编出来：

1. 在 Android Studio 里打开 `bt-stereo-split-poc/`（Open an existing project，选这个文件夹）；
2. 等 Gradle 同步完，`Build > Build App Bundle(s) / APK(s) > Build APK(s)`；
3. 找到 `app/build/outputs/apk/debug/app-debug.apk`；
4. 打包模块：
   ```bash
   ./scripts/build-module-zip.sh app/build/outputs/apk/debug/app-debug.apk
   ```
   得到 `btsplit-poc-kernelsu-v0.1.0.zip`；
5. 传到手机，KernelSU App → 模块 → 从本地安装 → 选这个 zip → 重启。

**只想先诊断、不装 App 也行**：把 `kernelsu-module/diagnose.sh` 单独拷到手机，用 root 跑 `su -c sh diagnose.sh`，结果在 `/sdcard/btsplit-diagnose.txt`。

## 测试步骤（在手机上）

1. 两个蓝牙音箱/耳机都配对好，都连上（系统蓝牙里显示 Connected for audio）。
2. 跑诊断（上一步），确认能看到两个 A2DP 设备、offload 已关。
   - 如果诊断里只有一个 A2DP：去开发者选项打开“停用蓝牙 A2DP 硬件卸载”，重启，再跑一次诊断。
3. 打开 “BT Split POC” → 点 Refresh，确认列表里有两个设备。
4. 左选 A、右选 B，点 **Play Test Tone**，听 10 秒：
   - ✅ **成功**：左音箱只有低音（440Hz），右音箱只有高音（660Hz）。
   - ❌ **只响一个**：看日志里 `routedDevice` 是不是跟目标不一致——说明系统把两路又路由到同一个设备了。
   - ❌ **两个都响、两个频率都有**：分声道没生效，可能是 `setPreferredDevice` 返回 false，或 HAL 不支持。
   - ❌ **两个都响但有回声/错位**：分声道成功了，只是延迟没对齐，拖动“右路延迟”滑块（每次加 50ms 试）再测。
5. 点 **Swap L/R** 再测一次，确认左右真的跟着设置走，不是碰巧。
6. （可选）Pick File 选一个 48kHz WAV，再播一次，确认不是测试音的特例。

## 怎么判断“这条路走不走得通”

| 现象 | 结论 |
|---|---|
| Refresh 只能看到 1 个 A2DP 设备 | 你手机的 HAL / 蓝牙栈不支持同时暴露两个 A2DP 输出，此路不通（除非换 ROM） |
| 能看到 2 个，但 `setPreferredDevice` 返回 false / routedDevice 不对 | AudioFlinger 拒绝双路由，offload 可能没关掉，或厂商阉割了 Combined Routing |
| 测试音左右分开了，但延迟对不齐 | 路走得通，剩下的是同步工程问题（Hifinis 就是靠每设备延迟补偿解决的） |
| 测试音 + 本地文件都分开了 | ✅ 概念验证通过，可以再往下做全局音频劫持（那是另一个大工程） |

## 风险提示

- 改 offload 可能让蓝牙音质/延迟变差，卸载模块并重启即可恢复。
- priv-app 安装需要重启，装错权限 XML 可能导致 App 闪退，日志在 App 界面里直接能看到。
- 别在主力机上测第一版，先确认诊断结果再装模块。

## 文件清单

- `RESEARCH.md` — 调研：Hifinis、XDA、AOSP Combined Routing
- `app/src/main/java/com/btsplit/poc/SplitEngine.kt` — 拆声道 + 多 Track 核心（v2：reconcile / 热更新 / 拒绝处理）
- `app/src/main/java/com/btsplit/poc/MainActivity.kt` — 界面 + 日志
- `OBOE-MIGRATION.md` — 迁到 Oboe/AAudio 的路径（未实施，无 native 代码）
- `hifinis-decompile/HIFINIS-REPORT.md` — Hifinis 2.5.16 多输出实现分析
- `kernelsu-module/diagnose.sh` — 先跑的诊断脚本
