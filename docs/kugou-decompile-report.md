# 酷狗概念版 5.3.21 反编译静态分析报告

> 样本：仅使用 Barbara 提供的飞盘链接下载的 APK，**未从任何其他来源下载**。
> 本报告只做静态分析事实记录：未修改原 APK、未安装、未签名、未重打包。
> 日期：2026-11-22（UTC）。分析机：Ubuntu 24.04.5 LTS，Java OpenJDK 17.0.21.1，jadx 1.5.2，apktool 2.11.1。

## 0. 结论先行

- **这不是官方原版 APK**：包内已内置一套完整的第三方注入框架（HKP + LSPlant + Xposed 模块 `KuGouLitePlus`），以及 2015 年的 Nuwa 热修复残留。每日签到领 VIP 的功能就来自内置模块的 `VipClaim` hook，不是官方功能。
- **主播放路径在 native 层**：音乐播放由 `libkugouplayer.so` / `libfxplayer.so` 完成，PCM 不经过 Java 层 `AudioTrack.write()`。Java 层只有一个“扩展音轨”开关可以摸到：`FxMusicPlayerJNI.JNI_enableExtendAudioTrack(boolean)`。
- **存在签名校验**：`com.kugou.framework.detect.DetectUtils`（`d()` 取自身签名证书 MD5 指纹）+ `libyoung_native.so` 的 native `checkLegal`，在登录流程（`ThirdLoginView` / `CloudLoginFragment` / `ShortMessageLoginFragment`）中被调用。重签名后登录/VIP 很可能失败。
- ** verdict：静态补丁加双声道理论上可能、实际上极难（维持 9/10）**。最难的一点不是混淆，而是 **PCM 在 native 层、AudioTrack 由 native 创建并驱动**——要在 native 层把一路立体声拆成两路单声道、分别 `setPreferredDevice()` 到两个蓝牙 A2DP 设备，等价于重写一半播放器。更现实的路线是**运行时 hook（沿用 APK 内已有的 HKP/LSPlant 注入框架，或 LSPosed 模块）**，而不是改 smali 重打包。

---

## 1. 样本与工具链

| 项目 | 值 |
|---|---|
| 文件 | `apk/kugou-5.3.21.apk` |
| 大小 | 123,304,911 字节（118 MiB 磁盘占用；zip 压缩后约 116.0 MB，未压缩约 279.6 MB） |
| SHA256 | `044810da8bc74cfd3268d7db2cd50ed23e0ccf600fe5b664d966b483a938dedb`（已核对一致） |
| zip 条目 | 10,506 个 |
| dex 文件 | 16 个（`classes.dex` … `classes15.dex`，其中 `classes15.dex` 仅 9 个类） |
| native 库 | 107 个 `.so`，全部仅 `lib/arm64-v8a`（无 armeabi-v7a / x86） |
| 包名 / 版本 | `com.kugou.android.lite` / versionName `5.3.21` / versionCode `11621` |
| 签名 | 无 v1 签名（zip 内无 `META-INF/` 条目）；APK Signing Block v2/v3 存在（文件尾 magic `APK Sig Block 42`，size 字段 4088） |

### 执行的命令与结果

```bash
java -jar tools/apktool.jar d apk/kugou-5.3.21.apk -o apktool-out
# 退出码 0，约 127 秒。apktool-out/ 1.4 GB，110,447 个 .smali 文件。日志：analysis/apktool.log

tools/jadx/bin/jadx -d jadx-out apk/kugou-5.3.21.apk 2>&1 | tee analysis/jadx.log
# 失败：跑到 25,169 / 43,124（58%）时被系统 OOM killer 杀掉
# （jadx 默认 MaxRAMPercentage=70%，8 GB 内存 + swap 耗尽；failureKind=subprocess.oom_killed）
# 但已输出的部分保留：jadx-out/ 321 MB，24,471 个 .java 文件
```

**Fallback（按任务要求执行，全部成功）**：改用小堆内存、逐个 dex 反编译，只补播放器和应用入口相关的 dex：

```bash
java -Xmx3g -cp tools/jadx/lib/jadx-1.5.2-all.jar jadx.cli.JadxCLI -j 2 --no-res -d jadx-out-dex6  /tmp/dexsel/classes6.dex   # 2,421 类，4,529 个 .java，6 个错误
java -Xmx3g -cp tools/jadx/lib/jadx-1.5.2-all.jar jadx.cli.JadxCLI -j 2 --no-res -d jadx-out-dex8  /tmp/dexsel/classes8.dex   # 1,950 类，3,239 个 .java，6 个错误
java -Xmx3g -cp tools/jadx/lib/jadx-1.5.2-all.jar jadx.cli.JadxCLI -j 2 --no-res -d jadx-out-dex13 /tmp/dexsel/classes13.dex  # 1,798 类，3,201 个 .java，10 个错误
```

- `classes6.dex`：`com.kugou.framework.player`（`KGMediaPlaybackService`、`KGPlayer` 接口）、`com.kugou.framework.detect.DetectUtils`、`com.kugou.framework.hack.*`
- `classes8.dex`：`com.kugou.android.app.KGApplication`
- `classes13.dex`：`com.kugou.common.player.*`（fxplayer / kugouplayer / kgplayer）

### DEX 总量（dex header 解析，16 个 dex 求和）

| 总计 | 数量 |
|---|---|
| 类 | 110,447 |
| 方法 | 802,031 |
| 字段 | 432,928 |
| 字符串 | 729,010 |

单 dex 类数 9（classes15.dex）～ 10,381（classes2.dex）；多数 dex 的方法数接近 65,5xx 上限。

---

## 2. AndroidManifest（`apktool-out/AndroidManifest.xml`，`apktool.yml`）

| 项目 | 值 |
|---|---|
| package | `com.kugou.android.lite` |
| versionName / versionCode | `5.3.21` / `11621` |
| minSdk / targetSdk / compileSdk | 22 / 30 / 34 |
| application | `com.kugou.android.app.KGApplication`（继承 `com.kugou.common.app.KGCommonApplication`） |
| 启动 Activity | `com.kugou.android.app.splash.SplashActivity` |
| appComponentFactory | `androidx.core.app.CoreComponentFactory` |
| networkSecurityConfig | `@xml/q`（见下） |
| allowBackup | `false` |
| usesCleartextTraffic | `true` |
| uses-library | `org.apache.http.legacy`（required=false） |
| android:debuggable | 未找到该属性（即非 debuggable） |

组件数量（`grep -c` 实测 `apktool-out/AndroidManifest.xml`）：

| 组件 | 数量 |
|---|---|
| `<uses-permission>` | 105 |
| `<activity>` | 347 |
| `<service>` | 53 |
| `<receiver>` | 24 |
| `<provider>` | 33 |

关键 service：`com.kugou.framework.player.KGMediaPlaybackService`（继承 `MediaBrowserServiceCompat`，见 `jadx-out-dex6/sources/com/kugou/framework/player/KGMediaPlaybackService.java:31`）。

网络安全配置 `apktool-out/res/xml/q.xml`：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

即明文流量允许；仅在 debuggable 时才信任用户证书（正式包 debuggable=false，该分支不生效）。

Manifest 中**没有**声明任何签名/完整性校验组件——校验在代码和 native 层（见第 6 节）。

---

## 3. DEX / 混淆

- **部分混淆**：包名完整保留（`com/kugou/...`、`com/tencent/...`），但大量类名、方法名是单字母或短名，例如 `com.kugou.common.player.fxplayer.a/b/c/d/e/f/g.java`、`com.kugou.framework.player.a/e/g/h/i/j/k/m/n.java`、`La/a/a;`。同时也有大量可读类名存活（如 `AbsTopicDetailFragment`、`KGMediaPlaybackService`、`FxMusicPlayerJNI`、`DetectUtils`）。
- **无 mapping 文件**：APK 内未发现 ProGuard/R8 mapping 或 DexGuard 标记文件。
- **腾讯 Sword AOP 框架遍布全包**：`com.tencent.qqmusic.sword.SwordProxy` / `SwordSwitches` 在 jadx-out 的 `com/kugou/` 下 **1,523 个 Java 文件**中有引用。反编译出的每个方法开头基本都是：
  ```java
  if (SwordSwitches.switches6 != null) {
      SwordProxyResult r = SwordProxy.proxy0Arg(this, 593976798338103L);
      if (r.isSupported) return (Long) r.result;
  }
  ```
  即方法体先被 Sword 的运行时开关代理。这意味着：静态反编译的可读性被严重削弱，很多真实逻辑在运行时由 Sword 开关决定，静态看不到。
- smali 总量 110,447 个文件，与 dex 类总数一致。

---

## 4. APK 内已有的 Mod 产物（全部只做了静态检查，未运行、未安装）

### 4.1 `assets/hkp/config.json`（229 字节）

```json
{"appComponentFactory":"androidx.core.app.CoreComponentFactory","hkpConfig":{"API_CODE":102,"CORE_VERSION_CODE":7912,"CORE_VERSION_NAME":"2.2.1","VERSION_CODE":281,"VERSION_NAME":"2.0-281"},"packageName":"com.kugou.android.lite"}
```

HKP = 注入框架的配置：API_CODE 102（对应 LSPosed API），core 2.2.1。

### 4.2 `assets/hkp/core.so`（3,464,724 字节）

- `file` 结果：`data`（不是 ELF，不是 dex，不是 zip）。
- 高熵加密 blob，无 ELF/dex/zip magic；字符串中提到 “with HKP ClassLoader” 和 Xposed API 文本。
- 推断：HKP 的加密核心载荷，运行时解密加载。

### 4.3 `assets/hkp/so/arm64-v8a/libhkp.so`（452,352 字节）

- `file`：`ELF 64-bit LSB shared object, ARM aarch64, stripped`，BuildID `a35eb02423c04ea40809c3c3a0ca1092c125781a`。
- 字符串证据（`strings` 实测）：
  - `org.hkp.loader.Application`
  - `dalvik/system/InMemoryDexClassLoader`
  - `PathClassLoader`
  - `Failed to get java dex file`
  - `LSPlant Hook` / `LSPlant` / `LSPHooker_`
  - `Failed to init lsplant`
  - `Failed to hook EncodeGenericId, attaching debugger may crash the process`（反调试）
  - `Failed to generate hooker`、`HookBridge`、`ResourcesHook`、`Skip duplicate hook`
  - `de.robv.android.xposed.`、`hkp.debug`、`MarkVisiblyInitialized not hooked`、`InMemoryDexClassLoader creation failed!!!`
- 结论：HKP 加载器，基于 **LSPlant**（LSPosed 的 native hook 框架）+ `InMemoryDexClassLoader`，在内存中加载 dex、hook 方法。

### 4.4 `assets/hkp/modules/com.kugoulite.plus.apk`（67,049 字节）

一个 Xposed/LSPosed 模块。实测 `unzip -l`：`META-INF/xposed/module.prop`、`META-INF/xposed/scope.list`、`META-INF/xposed/java_init.list`、`classes.dex`（69,332 字节）。

`module.prop` 内容：

```ini
name=酷狗概念版液态玻璃
description=酷狗概念版 5.3.21 液态玻璃底栏、自动领取 VIP、去广告
author=Jursin
source=https://github.com/Jursin/KuGouLitePlus
minApi=102
targetApi=102
staticScope=true
scope=com.kugou.android.lite
entry=com.kugoulite.plus.MainHook
```

dex 字符串实测：hook 类 `VipClaim`（接口 `/youth/v1/recharge/receive_vip_listen_song`，日志 tag `[KuGouLiteVip]`）、`NetHook`、`UiHook`、`GlassHook`、`AdsCenterMgr`。**dex 中不含 `AudioTrack`，无任何 audio 相关 hook**（`grep AudioTrack` = False，audio 关键词 = 空）。

**这就是每日签到领 VIP 的来源**：`VipClaim` 自动调用听歌领 VIP 接口。不是官方功能。

### 4.5 `assets/hack.apk`（2,214 字节）与 `assets/apks/hack.jar`（707 字节）

- 两者都只含一个 448 字节的 `classes.dex`，类为 `cn/jiajixin/nuwa/Hack`。
- Nuwa 是 2015 年的 Android 热修复框架。属于历史残留/占位文件，无实际音频或 hook 功能。

### 4.6 `KGApplication` 与 HKP 的关系

- `KGApplication.java`（`jadx-out-dex8/sources/com/kugou/android/app/KGApplication.java`）中**没有** `hkp` / `loadLibrary` 引用；`attachBaseContext()` 只做日志和 `KGCommonApplication.o.j()` 等初始化。
- 因此 HKP 的加载入口不在 Application Java 代码里，而在 `appComponentFactory`（manifest 与 `config.json` 均指向 `androidx.core.app.CoreComponentFactory`）或更早的启动路径。静态分析到此为止，未深挖加载链。

---

## 5. 播放路径（核心章节）

### 5.1 结论

**主音乐播放器是 native 的**：`libkugouplayer.so` + `libfxplayer.so`，Java 层只做控制（播放/暂停/音量/音效参数），PCM 数据不经过 Java 层。**Java 层没有可达的 `AudioTrack.write()` 调用点**。

证据：

| 搜索 | 结果 |
|---|---|
| `grep -rl "new AudioTrack\|AudioTrack(" jadx-out/sources/com/kugou/` | 仅 9 个文件，且都在非主播放路径（见 5.3） |
| `grep -rl "AudioTrack" jadx-out/sources/com/kugou/` | 18 个文件 |
| `grep -rl "MediaPlayer" jadx-out/sources/com/kugou/` | 31 个文件（广告/短视频/提示音等外围） |
| `grep -rli "exoplayer" jadx-out/sources/com/` | 仅 `com/tencent/thumbplayer/.../TPReportCommonEnum.java` 提到（上报枚举），**主播放不用 ExoPlayer** |
| 107 个 `.so` 中含 `AudioTrack` 字符串 | 8 个；含 `libOpenSLES` 12 个；含 `AAudio` 3 个（`libkugouplayer.so`、`libZegoLiveRoom.so`、`libliteavsdk.so`）；含 `oboe` 1 个（`libliteavsdk.so`） |

### 5.2 native 播放器符号（`nm -D` 实测）

**`lib/arm64-v8a/libkugouplayer.so`（2.86 MB，stripped，但导出了 JNI 符号）**：

```
_Z26createAudioTrackJavaObjectiilPN11KugouPlayer16NativeAudioTrackE
_Z32register_kugou_player_audiotrackP7_JNIEnv
_Z19callAudioTrackStartP8_jobject
_Z19callAudioTrackStopP8_jobject
_Z19callAudioTrackFlushP8_jobject
_Z19callAudioTrackPauseP8_jobjectb
_Z20callAudioTrackResumeP8_jobject
_Z21callAudioTrackPrepareP8_jobject
_Z21callAudioTrackReleaseP8_jobject
_Z22callAudioTrackPositionP8_jobject
_Z23callAudioTrackSessionIdP8_jobject
_Z23callAudioTrackSetVolumeP8_jobjectf
_Z19newAudioTrackFormatv
_Z21jobj2AudioTrackFormatP8_jobject
_ZN11KugouPlayer11AudioOutput16enableAudioTrackEii
_ZN11KugouPlayer11AudioOutput19isAudioTrackEnabledEi
_ZN11KugouPlayer11AudioOutput22enableExtendAudioTrackEi
_ZN11KugouPlayer11AudioOutput25isExtendAudioTrackEnabledEv
_ZN11KugouPlayer11AudioOutput22setAudioTrackIntervalsER9Intervalsi
_ZN11KugouPlayer13AudioRecorder16isOpenSLRecorderEv
_ZN11KugouPlayer10AudioTools19checkAudioTrackMuteEPNS_12PlayDataInfoEPNS_11MediaSourceE
_ZN11KugouPlayer13MVMediaSource13SetAudioTrackEiiiii
```

关键点：**native 代码自己创建 Java AudioTrack 对象**（`createAudioTrackJavaObject` + `register_kugou_player_audiotrack`），并通过 `callAudioTrack*` 驱动它。PCM 的写入发生在 native 侧。

**`lib/arm64-v8a/libfxplayer.so`（1.48 MB）**：

```
_Z33JAVA_NativeAudioTrack_constructoriiPN8FxPlayer16NativeAudioTrackE
_Z27JAVA_NativeAudioTrack_startP8_jobject
_Z26JAVA_NativeAudioTrack_stopP8_jobject
_Z27JAVA_NativeAudioTrack_pauseP8_jobjectb
_Z28JAVA_NativeAudioTrack_resumeP8_jobject
_Z29JAVA_NativeAudioTrack_prepareP8_jobject
_Z29JAVA_NativeAudioTrack_releaseP8_jobject
_Z28JAVA_NativeAudioTrack_setMuteP8_jobjectb
_Z28register_NativeAudioTrackJNIP7_JNIEnv
_ZN8FxPlayer16NativeAudioTrack6fillupEPhi
_ZN8FxPlayer16NativeAudioTrack5startEi
_ZN8FxPlayer16NativeAudioTrack14getDeviceDelayEv
_ZN8FxPlayer11AudioOutput15audioTrackCountEv
_ZN8FxPlayer11AudioOutput22enableExtendAudioTrackEi
_ZN8FxPlayer11AudioOutput25isExtendAudioTrackEnabledEv
_ZN8FxPlayer13FxMusicPlayer15audioTrackCountEv
_ZN8FxPlayer13FxMusicPlayer22enableExtendAudioTrackEi
_ZN8FxPlayer13FxMusicPlayer23_enableExtendAudioTrackEi
```

`NativeAudioTrack::fillup` 是 PCM 回填回调——native 向 AudioTrack 供数。

### 5.3 Java 层能摸到的点

**`com.kugou.common.player.fxplayer.player.music.FxMusicPlayerJNI`**（`jadx-out/sources/.../FxMusicPlayerJNI.java`）：

```java
public native void JNI_enableExtendAudioTrack(boolean z11);
public native boolean JNI_isExtendAudioTrackEnabled();
public native int JNI_getAudioTrackCount();
public native void JNI_setPlayVolume(float f11);
public native void JNI_startPlay();
public native void JNI_pausePlay();
public native void JNI_stopPlay();
public native void JNI_release();
```

**这是全包里最接近“双声道”的现成开关**：native 的 `AudioOutput` 本来就支持“扩展音轨”（`enableExtendAudioTrack` / `audioTrackCount`，两个 .so 都有），用途推测是伴奏/合唱等多路输出。但它是不是“左声道一路、右声道一路”尚无法静态确认，需要运行时验证。

其他 Java 层 AudioTrack 引用（**都不在主播放路径**）：

- `com/kugou/common/player/fxplayer/g.java`、`mixedChorus/AudioUtil.java`、`util/AudioUtil.java`（混音/合唱工具）
- `com/kugou/common/player/fxplayerAdapters/IFAStreamPusherManager.java`（推流）
- `com/kugou/shortvideo/media/.../AudioTrackWrapper.java`、`AudioPlayback.java`（短视频）
- `com/kugou/svplayer/media/player/PlayInfo.java`

### 5.4 其他播放器

- `com.kugou.common.player.kugouplayer.NativeOboe`（`jadx-out/sources/.../NativeOboe.java`）：Oboe 播放器封装，`createOboePlayer()` / `createOboeRecorder()`，但**全部方法都被 SwordProxy 代理**，静态看不到真实调用链。
- `com.kugou.common.player.kugouplayer.AudioPipe`：`LibraryManager.loadLibrary()` + `native_setup()`，native 管道。
- `com.kugou.common.player.kugouplayer.HwAudioDecoder`：`init(...)` 方法**反编译失败**（jadx 报错 `Method not decompiled`），内含 `AudioTrackFormat` 内部类。
- 视频/直播用腾讯 **ThumbPlayer**（`com/tencent/thumbplayer/...`）和 `libTPCore-master.so`（10.88 MB，含 `TPAudioTrack*` 类），与音乐主路径无关。
- `libSAudio.so`（0.18 MB）：无 AudioTrack 命中。
- 播放服务：`com.kugou.framework.player.KGMediaPlaybackService`（`MediaBrowserServiceCompat`），`jadx-out-dex6/.../KGMediaPlaybackService.java:31`；`KGPlayer` 主类本身在反编译输出中只有接口文件（`KGPlayer$IKGPlayerListener.java` 等），实现类被混淆为单字母类（`a/e/g/h/i/j/k/m/n.java`），未逐一还原。

---

## 6. 防护（只列实测到的，不猜测）

| 防护项 | 实测结果 |
|---|---|
| **签名校验** | **有**。`com.kugou.framework.detect.DetectUtils`（`jadx-out-dex6/.../DetectUtils.java`）：`d(Context)` 用 `getPackageInfo(pkg, 64)` 取 `packageInfo.signatures[0]`，经 X509 解析后算 **MD5 指纹**；另有 native 方法 `public static native boolean checkLegal(String)`，实现在 `lib/arm64-v8a/libyoung_native.so`（`Java_com_kugou_framework_detect_DetectUtils_checkLegal`）。调用点在登录流程：`ThirdLoginView.java:780/794`、`CloudLoginFragment.java:1245`、`ShortMessageLoginFragment.java:144/1157` 调用 `DetectUtils.a(context)`。注意：反编译出的 `DetectUtils.a()` / `c()` 方法体显示为空（jadx 标注 `Unreachable blocks removed`），真实校验逻辑在 Sword 代理/native 侧，静态无法完整还原。 |
| **Root 检测** | Java 层 `grep -rli "isRooted\|RootBeer\|/system/bin/su\|/system/xbin/su\|test-keys\|Magisk"` 在 `com/kugou/` 下**零命中**。`libyoung_native.so` 的 `strings` 也未见 `magisk` / `su` / `root` 明文（该 .so 字符串疑似加密/混淆）。**结论：未发现，不等于没有**——可能在 FireEye/TME 的 native 层。 |
| **腾讯安全组件（native）** | APK 内确实存在：`lib/arm64-v8a/libFireEye-rqd.so`、`libfireeye-memory.so`、`libtmesec.so`、`libtmeshield.so`、`libTMEMars.so`（均为腾讯 MTP / FireEye 终端安全组件）。只确认了文件存在，未分析其检测逻辑。 |
| **Pangle（穿山甲广告）加固** | 存在 `lib/arm64-v8a/libPglbizssdk_ml.so`、`libpanglearmor.so`。只确认文件存在。 |
| **腾讯 Legu（乐固）加壳** | **未发现**。dex 未加壳（apktool/jadx 可直接解析全部 16 个 dex）；`legu` / `libshell` / `libtprt` 的 grep 命中均为误报（如 `LittleGuardCountEntity` 含 `legu` 子串）。 |
| **SSL pinning** | 未发现 `CertificatePinner`。唯一相关的是 `com.kugou.common.network.p`（`jadx-out/sources/com/kugou/common/network/p.java`）：一个自定义 `X509TrustManager`，内部用 `TrustManagerFactory.getDefaultAlgorithm()` 取**系统默认** trust manager 并委托——即没有 pinning，只是包装系统信任链。另 `com/kugou/svapm/http/k.java`、`VideoEncoderCore.java` 提到 `X509TrustManager`，同属外围。 |
| **反调试** | `libhkp.so` 字符串：`Failed to hook EncodeGenericId, attaching debugger may crash the process`（HKP 自带的反调试提示）。 |
| **应用自带 hook 框架** | `com.kugou.framework.hack.*`（`jadx-out-dex6`）：`PackageInfoHookImpl`、`PackageManagerHacker`、`InstrumentationHacker`、`HackHub`、`JniHacker`、`OsHacker`、`SystemHacker` 等——这是酷狗**自己** hook 系统 API 的层（用于兼容/后台保活等），与本任务无关，但说明该 App 对 hook 行为有容忍设计。 |
| **Sword 运行时代理** | 见第 3 节：1,523 个 kugou 文件引用 SwordProxy，方法行为可在运行时被 Sword 开关改写，静态分析结果与运行时行为可能不一致。 |

---

## 7. 结论：能否通过补丁加双声道？

### 7.1 精确的 hook 点（按可行性排序）

1. **最优候选——`enableExtendAudioTrack`（native 已有功能）**
   - Java 入口：`com.kugou.common.player.fxplayer.player.music.FxMusicPlayerJNI.JNI_enableExtendAudioTrack(boolean)` / `JNI_getAudioTrackCount()`
   - Native 实现：`KugouPlayer::AudioOutput::enableExtendAudioTrack(int)`（`libkugouplayer.so`）、`FxPlayer::AudioOutput::enableExtendAudioTrack(int)`（`libfxplayer.so`）
   - 思路：启用扩展音轨后，看能否让第二路 AudioTrack 输出另一声道，并对其 `setPreferredDevice()`。**需要运行时验证**：扩展音轨的声道语义（是伴奏/合唱还是真正的 L/R）静态无法确认。

2. **次选——native AudioTrack 创建点**
   - `libkugouplayer.so` 的 `createAudioTrackJavaObject(int, int, NativeAudioTrack*)` + `register_kugou_player_audiotrack`：native 创建 Java AudioTrack 的唯一收口。在此处 hook 可拦截/复制 AudioTrack 实例，对第二路调用 `setPreferredDevice()` 并做声道分离。但 PCM 分离仍需在 native 回调（`NativeAudioTrack::fillup`）里做，工作量大。

3. **不推荐——Java 层 `AudioTrack.write()`**
   - 主路径**不存在** Java 层 write 调用点，无从 hook。

4. **不推荐——`KGMediaPlaybackService` / `KGPlayer`**
   - 只是控制层（播放/暂停/切歌），不接触 PCM。

### 7.2 单个最难的 blocker

**PCM 在 native 层、AudioTrack 由 native 创建并驱动，且方法体被 Sword 运行时代理、登录时有签名校验。**

展开说就是三件事叠加，缺一不可：

1. **声道分离必须在 native 层做**——Java 层拿不到 PCM；要在 `fillup` 回调或 `AudioOutput` 里把立体声拆成两路单声道，再分别喂给两个 AudioTrack，还要处理两个蓝牙设备的延迟同步（Hifinis 的做法是每设备可调延迟）。
2. **重打包必然改签名**——`DetectUtils` + `libyoung_native.so` 的 `checkLegal` 在登录时校验自身签名；重签名后登录/VIP（恰恰是这个分享版的核心卖点）很可能失败。绕过校验本身又是一项逆向工作。
3. **静态分析不可靠**——1,523 个文件被 Sword 代理，native 库 stripped，`HwAudioDecoder.init` 等关键方法反编译失败；很多结论必须上真机动态验证才能确认。

### 7.3 建议路线（按推荐度排序）

1. **运行时 hook，沿用 APK 已有的 HKP/LSPlant 框架**：写一个 LSPosed/HKP 模块，hook `FxMusicPlayerJNI.JNI_enableExtendAudioTrack` 或 native 的 `AudioOutput`，**不改 APK、不改签名**，VIP 功能不受影响。这是唯一不破坏现有签名的路线。
2. **外部伴生 App（AudioPlaybackCapture）**：不碰酷狗，用系统录音权限抓它的输出再分流到两个蓝牙设备。音质/延迟有损失，但零逆向。
3. **静态补丁重打包**：最后选择。需要同时解决 native 声道分离 + 签名校验绕过 + 重签名后 VIP 接口风控，工作量最大。

---

## 附录：产物与原始输出

| 产物 | 路径 |
|---|---|
| 本报告 | `~/workspace/bt-stereo-split-poc/kugou-decompile/DECOMPILE-REPORT.md` |
| apktool 输出（1.4 GB，110,447 smali） | `~/workspace/bt-stereo-split-poc/kugou-decompile/apktool-out/` |
| jadx 输出（部分，24,471 java） | `~/workspace/bt-stereo-split-poc/kugou-decompile/jadx-out/` |
| jadx 补跑（dex6 / dex8 / dex13） | `~/workspace/bt-stereo-split-poc/kugou-decompile/jadx-out-dex6/`、`jadx-out-dex8/`、`jadx-out-dex13/` |
| jadx 完整日志 | `~/workspace/bt-stereo-split-poc/kugou-decompile/analysis/jadx.log` |
| apktool 日志 | `~/workspace/bt-stereo-split-poc/kugou-decompile/analysis/apktool.log` |
| grep / native / 防护原始输出 | `~/workspace/bt-stereo-split-poc/kugou-decompile/analysis/notes.txt`（218 行，另有 `protection-grep.txt`、`native-player.txt`、`protection2.txt`、`final-checks.txt`） |
| 原 APK（未修改） | `~/workspace/bt-stereo-split-poc/kugou-decompile/apk/kugou-5.3.21.apk` |

未做事项：未修改原 APK、未安装、未签名、未重打包、未运行 APK 内任何模块；未从任何其他来源下载 APK。
