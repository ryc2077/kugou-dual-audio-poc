#!/system/bin/sh
# KernelSU module install script (runs in recovery / KernelSU install context).
# KernelSU 模块格式与 Magisk 模块基本兼容：module.prop + customize.sh + service.sh，
# 安装目录是 /data/adb/modules/<id>。不要在这里写 Magisk 专用的 magisk --hide 这类命令。
# 本模块真正做的事：
#  1. 把 App 装进 /system/priv-app（KernelSU 的 system 目录 overlay 会自动处理）；
#  2. 配套的 privapp-permissions XML 也放在 system/etc/permissions/ 下；
#  3. 安装完成后提示用户重启，再跑 diagnose.sh。

ui_print "- BT Split POC v0.1.0 (KernelSU)"
ui_print "- Installing POC player as priv-app..."

# APK 由构建步骤放进 system/priv-app/BTSplitPOC/BTSplitPOC.apk
# 如果这里没有 APK，说明你只装了“诊断版”模块，先按 README 把 APK 编出来再打包。
if [ ! -f "$MODPATH/system/priv-app/BTSplitPOC/BTSplitPOC.apk" ]; then
  ui_print "! WARNING: BTSplitPOC.apk not found in module."
  ui_print "! This install only sets props + diagnose script."
  ui_print "! Build the app in Android Studio, copy the APK to"
  ui_print "!   kernelsu-module/system/priv-app/BTSplitPOC/BTSplitPOC.apk"
  ui_print "! then re-zip and reinstall."
else
  set_perm "$MODPATH/system/priv-app/BTSplitPOC/BTSplitPOC.apk" 0 0 0644
  ui_print "- APK found, will be overlaid to /system/priv-app/BTSplitPOC/"
fi

if [ -f "$MODPATH/system/etc/permissions/privapp-permissions-com.btsplit.poc.xml" ]; then
  set_perm "$MODPATH/system/etc/permissions/privapp-permissions-com.btsplit.poc.xml" 0 0 0644
fi

# 诊断脚本放到模块目录，装完后在 KernelSU 里用 root shell 跑：
#   su -c sh /data/adb/modules/btsplit-poc/diagnose.sh
set_perm "$MODPATH/diagnose.sh" 0 0 0755

ui_print "- Done. Reboot, then run diagnose.sh as root."
ui_print "- Then open 'BT Split POC' and tap Refresh."
