#!/usr/bin/env bash
# 手机闹钟 App 构建脚本：aapt2 → javac → d8 → 打包 → 对齐 → 签名（零 Gradle）。
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
if [ -x "$HOME/Android/jdk17/bin/javac" ]; then
    export JAVA_HOME="$HOME/Android/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
BT="$(ls -d "$SDK"/build-tools*/34.0.0 2>/dev/null | head -1 || ls -d "$SDK"/android-* 2>/dev/null | grep -i build | head -1)"
PLATFORM="$(find "$SDK" -maxdepth 2 -name android.jar | grep -E 'android-3[45]' | head -1)"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"

[ -x "$BT/aapt2" ] || { echo "未找到 build-tools（aapt2）：检查 $SDK"; exit 1; }
[ -f "$PLATFORM" ] || { echo "未找到 android.jar：检查 $SDK"; exit 1; }
echo "BT=$BT"
echo "PLATFORM=$PLATFORM"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] aapt2 compile res"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --java "$OUT/gen" --auto-add-overlay "$OUT/res.zip"

echo "[3/6] javac"
javac -source 8 -target 8 -Xlint:none -nowarn \
    -classpath "$PLATFORM" -d "$OUT/classes" \
    $(find "$OUT/gen" "$ROOT/app/java" -name '*.java')

echo "[4/6] d8 dex"
"$BT/d8" --release --lib "$PLATFORM" --min-api 26 \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] 注入 classes.dex"
python3 - "$OUT" <<'PYEOF'
import sys, zipfile
out = sys.argv[1]
src, dst = out + "/base.apk", out + "/base2.apk"
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(out + "/dex/classes.dex", "classes.dex")
print("injected classes.dex")
PYEOF

echo "[6/6] 对齐 + 签名"
"$BT/zipalign" -f 4 "$OUT/base2.apk" "$OUT/aligned.apk"

KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass phonealarm -keypass phonealarm \
        -alias alarm -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=phone-alarm" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:phonealarm --key-pass pass:phonealarm \
    --out "$ROOT/phone-alarm.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$ROOT/phone-alarm.apk" && echo "签名校验通过"

echo "产物: $ROOT/phone-alarm.apk ($(du -h "$ROOT/phone-alarm.apk" | cut -f1))"
