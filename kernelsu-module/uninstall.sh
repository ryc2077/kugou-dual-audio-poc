#!/system/bin/sh
# 卸载时恢复：删掉日志，offload 的 prop 会在重启后恢复默认值（resetprop 不写磁盘）。
rm -f /data/adb/modules/btsplit-poc/service.log
# 卸载后重启一次，/system/priv-app 里的 POC App 即消失。
