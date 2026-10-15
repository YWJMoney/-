#!/usr/bin/env bash
# Gradle 없이 Android SDK 기본 도구(aapt2·javac·d8/dx·zipalign·apksigner)로 디버그 APK를 만든다.
# 평소에는 Android Studio에서 이 폴더(android/)를 열어 빌드하면 된다. 이 스크립트는 그럴 수 없는 환경용이다.
#
# 필요한 것: ANDROID_JAR(android-35 android.jar), aapt2, javac(JDK 17+), d8 또는 dx, zipalign, apksigner, zip
# 사용: ANDROID_JAR=/path/android.jar ./build-apk.sh  →  build/manual/jangbu-debug.apk
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
OUT=build/manual
ANDROID_JAR=${ANDROID_JAR:-${ANDROID_HOME:-/opt/android-sdk}/platforms/android-35/android.jar}
VERSION_NAME=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
VERSION_CODE=$(sed -n "s/.*versionCode \([0-9]*\).*/\1/p" app/build.gradle)
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}

rm -rf "$OUT" && mkdir -p "$OUT"/{assets/www,res,gen,classes}

# 1) 웹 화면 파일을 assets/www 로
(cd "$ROOT" && cp -r index.html privacy.html manifest.webmanifest css src icons "$OLDPWD/$OUT/assets/www/")

# 2) 리소스 컴파일·링크 (manifest에 package 이름을 넣는다)
sed 's#<manifest #<manifest package="com.ywjmoney.jangbu" #' app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
aapt2 compile --dir app/src/main/res -o "$OUT/res/compiled.zip"
aapt2 link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$OUT/assets" --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res/compiled.zip"

# 3) Java → class → dex
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')
if command -v d8 >/dev/null; then
  d8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
else
  dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
fi
(cd "$OUT" && zip -q -j base.apk classes.dex)

# 4) 정렬·서명 (기본은 디버그 키. 없으면 만든다)
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
zipalign -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:${KS_PASS:-android} --ks-key-alias ${KS_ALIAS:-androiddebugkey} \
  --out "$OUT/jangbu-debug.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/jangbu-debug.apk"
echo "완료: $OUT/jangbu-debug.apk (v$VERSION_NAME)"
