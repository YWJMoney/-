#!/usr/bin/env bash
# Gradle 없이 Play 업로드용 앱 번들(.aab)을 만든다. 평소에는 Android Studio에서
# Build → Generate Signed App Bundle 을 쓰면 된다. 이 스크립트는 그럴 수 없는 환경용이다.
#
# 필요한 것: ANDROID_JAR, aapt2, javac, d8 또는 dx, BUNDLETOOL(bundletool-all.jar), jarsigner, zip, unzip
# 서명: keystore.properties(storeFile, storePassword, keyAlias, keyPassword)의 업로드 키로 서명한다.
# 사용: ANDROID_JAR=... BUNDLETOOL=... ./build-aab.sh  →  build/aab/jangbu-<버전>.aab
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
OUT=build/aab
ANDROID_JAR=${ANDROID_JAR:-${ANDROID_HOME:-/opt/android-sdk}/platforms/android-35/android.jar}
BUNDLETOOL=${BUNDLETOOL:-bundletool.jar}
PROPS=${KEYSTORE_PROPERTIES:-keystore.properties}
VERSION_NAME=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
VERSION_CODE=$(sed -n "s/.*versionCode \([0-9]*\).*/\1/p" app/build.gradle)

[ -f "$PROPS" ] || { echo "$PROPS 가 없습니다(업로드 키 정보)." >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS" | tr -d '\r'; }
PROPS_DIR=$(cd "$(dirname "$PROPS")" && pwd)
STORE_FILE=$(prop storeFile); case "$STORE_FILE" in /*) ;; *) STORE_FILE="$PROPS_DIR/$STORE_FILE";; esac

rm -rf "$OUT" && mkdir -p "$OUT"/{assets/www,res,gen,classes,module}

# 1) 웹 화면 파일
(cd "$ROOT" && cp -r index.html privacy.html manifest.webmanifest css src icons "$OLDPWD/$OUT/assets/www/")

# 2) 리소스를 번들용(proto) 형식으로 링크
sed 's#<manifest #<manifest package="com.ywjmoney.jangbu" #' app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
aapt2 compile --dir app/src/main/res -o "$OUT/res/compiled.zip"
aapt2 link --proto-format -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" -o "$OUT/proto.apk" "$OUT/res/compiled.zip"

# 3) Java → dex
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')
if command -v d8 >/dev/null; then
  d8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
else
  dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
fi

# 4) base 모듈 구성: manifest/, dex/, res/, resources.pb, assets/
M="$OUT/module"
(cd "$M" && unzip -q ../proto.apk)
mkdir -p "$M/manifest" "$M/dex"
mv "$M/AndroidManifest.xml" "$M/manifest/"
cp "$OUT/classes.dex" "$M/dex/"
cp -r "$OUT/assets" "$M/assets"
(cd "$M" && zip -q -r ../base.zip .)

# 5) 번들 만들고 업로드 키로 서명
AAB="$OUT/jangbu-$VERSION_NAME-$VERSION_CODE.aab"
java -jar "$BUNDLETOOL" build-bundle --modules="$OUT/base.zip" --output="$AAB"
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore "$STORE_FILE" \
  -storepass "$(prop storePassword)" -keypass "$(prop keyPassword)" "$AAB" "$(prop keyAlias)" >/dev/null
jarsigner -verify "$AAB" >/dev/null
java -jar "$BUNDLETOOL" validate --bundle="$AAB" >/dev/null
echo "완료: $AAB (v$VERSION_NAME, code $VERSION_CODE)"
