# 게시 방법

## 1. APK·AAB 빌드

앱은 `android/` 에 있는 Android 전용 네이티브 앱입니다(최소 Android 8.0, 대상 API 35). 빌드할 때 저장소 루트의 `index.html`, `css/`, `src/`, `icons/` 등을 앱 안 `assets/www`로 복사해 넣으므로, 화면 코드는 루트에서 고치면 됩니다.

**Android Studio(권장)**
1. Android Studio에서 `android/` 폴더를 엽니다. Gradle 동기화가 끝나면 ▶ 실행으로 폰에 설치됩니다.
2. Play 업로드용은 Build → Generate Signed App Bundle → Android App Bundle(.aab)을 고릅니다.
3. 명령줄로 하려면 `android/keystore.properties` 를 만들고(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) `./gradlew bundleRelease` 를 실행합니다. 이 파일과 키스토어는 커밋하지 않습니다(.gitignore 처리됨). 키를 잃어버리면 업데이트를 못 올리니 따로 백업합니다.

**Gradle 없이 테스트 APK만 만들 때**

```bash
cd android
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar ./build-apk.sh
# → build/manual/jangbu-debug.apk (디버그 서명, Play 업로드 불가)
```

aapt2, javac, d8(또는 dx), zipalign, apksigner가 PATH에 있어야 합니다.

**폰에 직접 설치(테스트)**: APK 파일을 폰으로 옮겨 열고, "출처를 알 수 없는 앱 설치"를 허용하면 설치됩니다.

## 2. Play 스토어에 올리기

> 폰에서 바로 시험하려면 [PLAY_INTERNAL_TEST.md](PLAY_INTERNAL_TEST.md)의 내부 테스트 절차를 따르세요. 파일로 받은 APK는 알림 접근 권한 때문에 Play 프로텍트가 설치를 막습니다.

Gradle 없이 .aab를 만들 때: `ANDROID_JAR=... BUNDLETOOL=bundletool-all.jar KEYSTORE_PROPERTIES=keystore.properties ./build-aab.sh`

1. **Play 개발자 계정**: Play Console에서 계정을 만들고(등록비 25달러) 본인 인증을 마칩니다.
2. **앱 만들기**: 패키지 이름 `com.ywjmoney.jangbu`, 1단계에서 만든 .aab를 올립니다. Play 앱 서명을 사용합니다.
3. **스토어 등록 정보**: 앱 이름, 설명, 스크린샷, 아이콘(`icons/icon-512.png`), 개인정보처리방침 URL(`privacy.html`을 GitHub Pages 등에 공개한 주소)을 넣습니다.
4. **데이터 보안 양식**: v0.1은 "데이터를 수집·공유하지 않음"으로 답합니다(모두 기기 안 저장, 서버 전송 없음). 금융 기능 관련 선언 항목이 나오면 "개인 가계부, 금융 거래 실행 없음"으로 답합니다.
5. **권한 설명**: 알림(POST_NOTIFICATIONS, 결제 예정 알림), 부팅 완료 수신(RECEIVE_BOOT_COMPLETED, 알림 예약 복구), 알림 접근(NotificationListenerService, 결제 알림 자동 기록)을 씁니다. SMS·접근성 권한은 없습니다.
   - 알림 접근은 앱 안에서 무엇을 읽고 남기는지 먼저 보여 준 뒤(자동 기록 켜기 화면) 사용자가 설정에서 직접 허용합니다. 스토어 설명과 데이터 보안 양식에도 "기기 안에서만 처리, 전송 없음"을 같은 내용으로 적습니다.
   - 데이터 보안 양식의 "금융 정보 > 구매 내역"은 기기 안에서만 처리하므로 수집·공유 아님으로 답하되, 심사에서 묻는 경우를 대비해 개인정보처리방침 2-1을 링크합니다.
6. **비공개 테스트**: 신규 개인 개발자 계정은 프로덕션 출시 전에 비공개 테스트(테스터 12명 이상, 14일 연속)를 요구합니다. 최신 요건은 Play Console 안내를 다시 확인하세요.
7. 14일이 지나면 프로덕션 액세스를 신청하고 출시합니다.

iOS는 보류 중이라 App Store 절차는 다루지 않습니다.

## 3. 버전 올릴 때

- `android/app/build.gradle`의 `versionCode`(정수, 매번 +1)와 `versionName`, `src/app.js`의 `APP_VERSION`, `package.json`의 `version`을 함께 올립니다.
- 저장 형식이 바뀌면 `src/store.js`의 `SCHEMA_VERSION`을 올리고 `MIGRATIONS`에 변환 함수를 추가합니다.
