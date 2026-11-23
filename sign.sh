#!/usr/bin/env bash
# 交付签名：先 gradle :app:assembleRelease，再运行本脚本产出正式签名 Release APK。
#
# 安全说明：签名密码不写入仓库，运行时从环境变量读取：
#   export LYRE_KS_PASS=你的密码
#   bash sign.sh
# 若未设置，脚本会报错退出，绝不使用硬编码密码。
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.1.0"
KS="$ROOT/signing/lyre.jks"
UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="$ROOT/../LiquidLyre-璃音-1.6.5.apk"

if [ -z "${LYRE_KS_PASS:-}" ]; then
  echo "错误：请先设置签名密码环境变量：export LYRE_KS_PASS=你的密码"
  exit 1
fi
test -f "$KS" || { echo "未找到 keystore：$KS"; exit 1; }
test -f "$UNSIGNED" || { echo "未找到 unsigned APK，请先执行 gradle :app:assembleRelease"; exit 1; }
"$BT/zipalign" -p -f 4 "$UNSIGNED" /tmp/lyre-aligned.apk
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias lyre \
  --ks-pass "pass:$LYRE_KS_PASS" --key-pass "pass:$LYRE_KS_PASS" \
  --min-sdk-version 31 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" /tmp/lyre-aligned.apk
"$BT/apksigner" verify --min-sdk-version 31 --verbose "$OUT" | head -20
echo "已生成：$OUT"
