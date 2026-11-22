#!/system/bin/sh
# KernelSU service.sh：每次开机后执行（late_start service 阶段）。
# 目标：关掉 A2DP 硬件 offload。
# 为什么：硬件 offload 时蓝牙编码在 DSP / 蓝牙芯片里做，AudioFlinger 只开一路硬件流；
# 关掉后走软件编码（SBC/AAC 由 CPU 编），才有可能同时开两路 A2DP 流。
# 注意：不同厂商的 prop 名字不一样，下面是按优先级逐个尝试，失败不报错。
# 改完需要重启蓝牙（或重启手机）才生效。

MODDIR=${0%/*}
LOG=/data/adb/modules/btsplit-poc/service.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5

{
  echo "=== BT Split POC service.sh $(date) ==="
  # 通用开关（AOSP / Pixel）
  resetprop persist.bluetooth.a2dp_offload.disabled true 2>/dev/null
  resetprop persist.bluetooth.a2dp_offload.cap sbc-aac 2>/dev/null
  # 高通系
  resetprop persist.vendor.bt.a2dp_offload_cap sbc-aac 2>/dev/null
  resetprop persist.vendor.bt.a2dp_offload.disabled true 2>/dev/null
  # 有些 ROM 用这个
  resetprop persist.bluetooth.a2dp_offload.enable false 2>/dev/null
  # 开发者选项里也有“停用蓝牙 A2DP 硬件卸载”——如果上面没生效，手动去开它。
  echo "a2dp_offload.disabled=$(getprop persist.bluetooth.a2dp_offload.disabled)"
  echo "vendor a2dp_offload_cap=$(getprop persist.vendor.bt.a2dp_offload_cap)"
  echo "If dual routing still fails, toggle Developer Options > Disable Bluetooth A2DP hardware offload, then reboot."
} >> "$LOG" 2>&1
