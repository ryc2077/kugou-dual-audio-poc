#!/bin/bash
# 打包 KernelSU 模块 zip（在电脑上跑，不是在手机上）。
# 前置：先在 Android Studio 里打开本目录，Build > Build APK(s)，
# 得到 app/build/outputs/apk/release/app-release.apk（或 debug 版）。
set -e
cd "$(dirname "$0")/.."
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
if [ ! -f "$APK" ]; then
  echo "APK not found: $APK"
  echo "Build it first in Android Studio, or pass the APK path:"
  echo "  ./scripts/build-module-zip.sh /path/to/app-debug.apk"
  exit 1
fi
mkdir -p kernelsu-module/system/priv-app/BTSplitPOC
cp "$APK" kernelsu-module/system/priv-app/BTSplitPOC/BTSplitPOC.apk
# KernelSU 模块 zip：根目录就是 module.prop / customize.sh 等，不要多套一层文件夹。
cd kernelsu-module
zip -r ../btsplit-poc-kernelsu-v0.1.0.zip . -x "*.DS_Store"
cd ..
echo "Built: btsplit-poc-kernelsu-v0.1.0.zip"
echo "Install: KernelSU App -> Modules -> Install from storage -> select the zip -> Reboot"
ls -lh btsplit-poc-kernelsu-v0.1.0.zip
