# 调研结论：先去“上面”看了什么

日期：2026-11-22。目标：root 后两个蓝牙设备分左右声道，有没有现成东西能用。

## 1. 现成的、直接可用的 App：Hifinis（闭源，只有 APK）

- 仓库：`Parivallalin/hifinis`，GitHub 上只有网站、隐私政策和 APK 发布，没有源码，仓库说明明确写了 “The app itself is closed-source”。
- 但它已经实现了你要的功能，而且**不需要 root**：
- 1.1.0：“play on several devices at once”，Outputs 里能看到每个已连接设备，勾几个就一起播
- “Per device: volume, channel (Stereo / Left / Right / Mono) and a sync control”，以及 “Stereo pair. Put the left channel on one device and the right on another.”（见上一轮搜索结果）
- 官方指南也写了：每个输出有自己的音量、左右平衡和同步延迟
- 1.1.1 还修了蓝牙多输出的卡顿和同步问题：同步延迟现在包含真实 buffer 大小
- **结论：如果你只是想播本地音乐文件分左右声道，先直接装 Hifinis 试，十有八九不用折腾 root。** 它的存在本身也证明了这条路在应用层是走得通的：一个 App 开多个 AudioTrack，各自路由到不同设备。
- 局限：只能播它自己的本地曲库，不能劫持 Spotify / YouTube / 系统全局音频。想让所有 App 都分左右，才需要系统层方案。

## 2. 现成的 Magisk / KernelSU 模块：没有

- XDA 上有人在 2024 年发帖求一个“像三星 Dual Audio 那样的 Magisk 模块”，想同时连两个蓝牙音箱，帖子只有顶贴、没有答案。
- 其他 Magisk 音频模块（Moto Waves、LG 双扬声器等）都是改 EQ / 混音器 / 编解码器，没有一个是做双 A2DP 分声道的。
- **结论：没有可直接复用的模块，只能借鉴思路。**

## 3. 能借鉴的官方机制：AOSP Combined Audio Routing（Android 12+）

- Android 11 及以下对“同类型多个设备”（比如 2 个蓝牙 A2DP 耳机）支持有限；Android 12 起这些限制被移除，支持向多个设备同时推流。
- 系统 API 是 `setPreferredDevicesForStrategy` / `getPreferredDevicesForStrategy` 等，Audio Policy Manager 用它们设置一个策略的多个首选设备。
- 但注意两点：(a) 这些是 `@SystemApi`，普通 App 调不到，需要特权 App（priv-app）或系统签名；(b) 它们本身只解决“同时推给多个设备”，不解决“分左右声道”，分声道要在 App 里自己拆 PCM。
- `AudioTrack.setPreferredDevice(AudioDeviceInfo)` 是公开 API，每个 AudioTrack 可以单独指定路由设备，这是本 POC 的核心。
- `AudioTrack` 还有 `DUAL_MONO_MODE_LL`（左声道复制到双声道）和 `DUAL_MONO_MODE_RR`（右声道复制到双声道），可作备用方案。

## 4. 本 POC 的设计（基于以上结论）

不写一个去改蓝牙协议栈的 Magisk 模块——那条路最难、机型差异最大。
写一个 **KernelSU 模块 + 一个特权播放器 App**：

1. App 自己解码 / 生成立体声 PCM，拆成左、右两路单声道；
2. 开两个 `AudioTrack`，各 `setPreferredDevice()` 到一个蓝牙 A2DP 设备；
3. 两路用同一个启动屏障同时起播，另加可调延迟（ms）对齐两个设备的编解码延迟；
4. KernelSU 模块做三件事：把 App 装进 `/system/priv-app`（拿到 `MODIFY_AUDIO_ROUTING` 等特权权限）、关 A2DP 硬件 offload（offload 只允许一路硬件流，软件编码才能开两路）、提供一键诊断脚本。

如果 Hifinis 在你手机上已经能分左右，说明你手机的音频 HAL 支持多路 A2DP，本 POC 成功的概率就很高；如果 Hifinis 也只能响一个，说明 HAL / 厂商实现不支持，POC 很可能在 `setPreferredDevice` 那一步就失败——这本身就是概念验证要测出来的结论。
