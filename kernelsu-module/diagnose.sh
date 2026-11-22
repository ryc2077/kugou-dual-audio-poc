#!/system/bin/sh
# BT Split POC — 开机后诊断脚本（KernelSU root shell 里跑）
# 用法（任选其一）：
#   KernelSU App -> 模块 -> 本模块 -> 右上角终端，或
#   Termux / adb shell 里：
#     su -c sh /data/adb/modules/btsplit-poc/diagnose.sh
# 输出会同时打到屏幕和 /sdcard/btsplit-diagnose.txt
#
# 它不改任何东西，只读：先跑这个，再决定要不要继续测 App。
# 如果这里已经看到“只暴露 1 个 A2DP 设备”或“offload 关不掉”，
# App 大概率也会在同一步失败——先把诊断结果发回来再继续。

OUT=/sdcard/btsplit-diagnose.txt
{
  echo "=== BT Split POC diagnose $(date) ==="
  echo "Android: $(getprop ro.build.version.sdk) / $(getprop ro.build.version.release)"
  echo "Device: $(getprop ro.product.manufacturer) $(getprop ro.product.model)"
  echo "KernelSU: $(ls -d /data/adb/ksu 2>/dev/null || echo no-ksu-dir)  su=$(which su)"
  echo ""
  echo "--- A2DP offload props ---"
  getprop | grep -i -E "a2dp|bluetooth.*offload" || echo "(none)"
  echo ""
  echo "--- Bluetooth state ---"
  dumpsys bluetooth_manager 2>/dev/null | grep -i -E "state|enabled|address" | head -20
  echo ""
  echo "--- Audio devices (dumpsys audio) ---"
  dumpsys audio 2>/dev/null | grep -i -A2 -B2 "BT_A2DP\|bluetooth_a2dp\|A2DP" | head -60
  echo ""
  echo "--- Audio policy: is there a second A2DP output? ---"
  dumpsys media.audio_policy 2>/dev/null | grep -i -E "a2dp|bluetooth" | head -40
  echo ""
  echo "--- POC app installed as priv-app? ---"
  ls -l /system/priv-app/BTSplitPOC/ 2>&1
  pm list packages | grep btsplit || echo "(com.btsplit.poc not installed)"
  dumpsys package com.btsplit.poc 2>/dev/null | grep -i -E "privileged|MODIFY_AUDIO_ROUTING|versionName" | head -20
  echo ""
  echo "--- What to look for ---"
  echo "PASS signs: 2x A2DP devices in dumpsys audio, offload.disabled=true, POC is privileged."
  echo "FAIL signs: only 1 A2DP output, or dumpsys shows a single 'bt_a2dp' stream."
  echo "If FAIL: try Developer Options > Disable Bluetooth A2DP hardware offload, reboot, re-run."
} | tee "$OUT"
echo "Saved to $OUT"
