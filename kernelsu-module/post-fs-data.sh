#!/system/bin/sh
# post-fs-data.sh：在 system 挂载后、开机早期执行。
# 这里只做一件事：尽早关 offload，避免蓝牙服务启动时就锁死硬件流。
# 真正的设置在 service.sh 里再做一次（开机完成后再确认）。
resetprop persist.bluetooth.a2dp_offload.disabled true 2>/dev/null
resetprop persist.vendor.bt.a2dp_offload.disabled true 2>/dev/null
