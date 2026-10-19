# 밤낮 인수인계 (다른 계정에서 이어 가기)

마지막 정리 2026-10-19, 앱 1.15.0(versionCode 23) 기준이에요.

이 저장소는 공개예요(GitHub Pages로 처리방침을 올려요). 그래서 이 문서에는 비밀값, 서명 키, 사용자 개인 정보를 적지 않아요.
그런 것은 사용자가 새 프로젝트에 파일로 따로 올려요(7항). 새 계정의 Claude는 이 문서와 그 비공개 메모를 먼저 읽고 시작해요.

## 1. 지금 상태

- 안드로이드 전용 앱이에요. 갤럭시 폴드의 접은 화면과 펼친 화면을 둘 다 맞추고, 첫 화면은 달력이에요.
- Kotlin, Compose BOM 2025.05.00, Room v15, minSdk 26, targetSdk 36, Robolectric 4.14.1.
- 버전별로 들어간 큰 줄기

| 버전 | 단계 | 들어간 것 |
| --- | --- | --- |
| 1.8.0 | M8 | 근무표 링크 공유(공유 서버) 등 |
| 1.9.0 | M9 | 잔업·특근·대근(연장·휴무일 근무) 종류와 시간대, 급여 계산 |
| 1.10.0 | M12 일부, 공유 MVP, M10 일부 | 링크에 보여 줄 것 고르기, 받은 근무표 넣기, 내 달력과 받은 달력 바꿔 보기, 달력 맨 위 오늘 카드, 회사 찾기·제보 앱 코드(서버 주소가 비어 꺼짐) |
| 1.11.0 | M10 | 수면 알림 정리, 설정 9묶음, 달력·빠른 입력, 같은 달 보기, 움직임·접근성 |
| 1.12.0 | M11 | 질문형 첫 설정, 질문형 급여 설정, 확인 필요와 금액 범위, 명세서로 맞춰 보기, 회사 방식(감시·단속, 5인 미만, 휴게, 일당·연봉, 가산 대신 받는 수당), 회사 찾기 먼저 |
| 1.13.0 | M12·M13 | 알람 눌러 고치기, 일요일 시작 주와 날짜 보기 방식, 하루 화면 순서, 공휴일 이름, 명세서 사진으로 급여 채우기와 말로 적는 회사 규칙, 첫 실행 동의·워치 질문·메모 일정 알림(DB 13), 회사 서버 연결과 앱 안 문의, 개발자 앱 개편과 관리자 권한, 회사 규칙 광장과 투표 채택, 근무 형태를 따르는 링크 공유, 멈춤·큰 글자 대응, 수면 기록·캘린더 가져오기·받을 돈(DB 14), 처리방침 2.0 |
| 1.14.0 | M13·M14 일부 | 개발자 앱 로딩 속도, 계산이 이상해요를 앱 안 문의로, 문의 자동 분류, 모두의 문의(공개·나도 그래요·신고), 회사 규칙 광장 검색과 입구, 위아래로 돌리는 시간·길이 선택, 공유 링크 자동 갱신과 기간 알림·늘리기, 받을 돈 계산 바로잡기와 화면 개선, 개인 달력 여러 개, 처리방침 2.1 |
| 1.14.1 | INQ-01 | 건의·제안·그 밖의 이야기와 회사 방식 알려 주기를 모두 앱 안 문의로(회사 방식은 개발자만 봄), 개발자 앱 문의함에서 처리, 처리방침 2.2 |
| 1.14.2 | MCAL-06·07 | 추가한 달력도 급여·일감·받을 돈과 급여를 담은 링크를 내 달력처럼, 내 달력+추가 1개 무료·두 번째 추가부터 프로(결제 전엔 표시만), 처리방침 2.3 |
| 1.15.0 | M14·사용자 요청 | 알람 자가 점검·1분 시험 알람·점검 고치기 버튼, 잠금 화면 오늘 근무, 광고 규칙 시험, 오픈소스 라이선스, 출시 점검표(`docs/release-checklist.md`), 시간 휠 터치 범위, 가계부 고도화(DB 15), 우리 회사 항목 직접 만들기와 동료와 나누기, 급여·근무 설정 쉽게, 근무 형태별 달력(달 요약·목록·되돌리기·위젯), 설정 찾기·도움말과 문의·공유 버튼 |

- 서버 두 개가 사용자의 Cloudflare 계정에 있어요.
  - 공유 서버(`server/share-worker`, KV): 켜져 있고 앱이 써요(`gradle.properties`의 `bamnat.shareBaseUrl`).
  - 회사 서버(`server/company-worker`, D1): 2026-10-18에 배포했고 개발자 앱(`/admin/`) 로그인까지 돼요.
    1.12.0까지는 앱에 연결하지 않았고(`bamnat.companyBaseUrl`이 비어 있음), ADMIN-02 작업에서 연결했어요. 연결한 빌드부터 회사 이름 검색,
    회사 설정 보내기, '회사 방식 알려 주기', 설정 > 문의하기가 이 서버로 가요. 비우고 빌드하면 예전처럼 꺼져요.
- 설정 > 문의하기 [ADMIN-02]: 서버가 있는 빌드는 앱 안 화면(`ui/settings/InquiryScreen.kt`, `company/InquiryClient.kt`)으로 보내고 답도 거기서 받아요.
  서버가 없는 빌드와 화면의 '메일로 보내고 싶어요'는 메일 앱을 열어요(`SettingsPages.kt`의 `writeToUs`, 주소는 `REPORT_EMAIL`).
  서버 쪽 API와 저장은 `server/company-worker/README.md`의 '앱 문의'. 개발자 앱의 답장 화면은 ADMIN-03 작업이 만들어요.
  **서버 코드를 다시 붙여 넣어야(README 3번) 문의 API가 살아나요.** 그 전에는 앱이 문의를 보내면 "보내지 못했어요"가 나와요.
- 개인 달력 여러 개 [MCAL-01~05], 다음 버전: 지금 달력은 그대로 '내 달력'(`bamnat.db`)이고, 추가한 달력은 `calendar_<id>.db`에 근무 종류·주기·날짜를 따로 둬요.
  목록·순서·보고 있는 달력은 `files/calendars.json`(`data/CalendarBook.kt`). 달력 탭과 근무 종류·주기 화면은 보고 있는 달력을, 위젯·알림·급여·통계는 내 달력을 따라요.
  알람은 내 달력과 '근무 알람 울리기'가 켜진 추가 달력을 합쳐 예약하고, 추가 달력 알람은 이름이 붙어요("편의점 알바 오전 알바 근무 알람").
  달력마다 공유 링크가 따로예요(`ShareLinkStore(context, calendarId)`, 내 달력은 예전 `share_link` 그대로). 서버는 바뀌지 않았어요.
- 사용자가 1.11.0에서 링크 공유와 달력 바꿔 보기를 못 찾았어요. 위치는 달력 위 공유 버튼(좁은 화면은 ⋮ 더 보기 → 근무표 공유)이고,
  바꿔 보기 막대는 받은 근무표가 하나라도 있을 때만 보여요(`CalendarScreen.kt`의 `receivedList.isNotEmpty() && received.enabled`).

## 2. 다음에 할 일 (1.15.0 뒤)

남은 일 전체는 개발계획 문서의 '남은 일'에 순번으로 있어요. 지금은 M14(정식 출시와 측정 기반)예요.

1. **1.15.0 출시**: 서버는 바뀌지 않았어요. 출시 점검표(`docs/release-checklist.md`)를 `docs/releases/1.15.0-checklist.md`로 복사해 채우고 테스트 트랙에 올려요.
2. **M14의 사용자 몫**: Play 계정 종류(순번 16), Firebase 프로젝트와 리전(17, 그 뒤 Crashlytics·사용 통계 18·19·20), 테스터 12명과 인터뷰(33·34), 상표(30).
3. **M14의 코드 몫 남은 것**: 성능 예산(25, Baseline Profile은 에뮬레이터가 필요해요), 실제 옛 DB 이전 시험(27), 접근성 수동 점검(28), 알람 기기 매트릭스(23, 실기기).
4. 급여 엔진은 사용자가 실제 명세서와 대 보고 다르면 고치기로 했어요. 엔진이 "확인 필요"로 남긴 곳은 세 군데예요.
   - 감시·단속 승인자의 근로자의 날: 가산 없이 유급 하루로 둠
   - 22~06시 안의 유급 휴게: 야간 가산 없음
   - 연봉 ÷ 13: 퇴직금이 들어 있을 수 있어 노무사나 1350 상담을 안내
5. 직접 만든 회사 항목을 금액 없이 서버에 담을지(계획 순번 60)는 사용자가 정해요. 지금은 휴대폰 안에만 있어요.

## 3. 일하는 방식 (사용자가 정한 것)

- 사용자에게는 한국어로, 쉬운 말로 답해요. 결론부터 말하고, 확인한 근거(파일, 테스트, 화면)를 함께 적어요.
- 요구사항 ID를 코드 주석과 커밋 메시지에 `[PAYS-02]`처럼 적어요. 새 ID는 먼저 추적표에 넣어요(검사 스크립트가 없는 ID를 오류로 봐요).
- 문서는 바뀐 부분만 고치거나 더해요. 처음부터 다시 쓰지 않아요.
- 회사 정보는 근거가 있는 것만 써요. 예시에는 가상의 이름만 써요(예시정밀, 가나다화학, 라마바전자, 사아자물류, 사람은 민지·준호).
- 한 단계 개발이 끝나면 main으로 PR을 열어 merge commit으로 합치고, 작업 브랜치를 main에서 다시 시작해요(force-with-lease).
- 서버 연결은 단계를 마칠 때 사용자에게 묻고, workers.dev 주소만 받아요.
- 화면은 디자인과 성능을 최고로, 버그 없이. 고친 화면은 스크린샷 테스트로 접은 화면과 펼친 화면을 둘 다 확인해요.

## 4. 지켜야 할 보안·정책 규칙

- 토큰, 비밀번호, `RATE_SALT`, 관리자 비밀번호를 채팅에 붙여 달라고 하지 않아요. Cloudflare는 workers.dev 주소만 받아요.
- 회사 서버의 `ADMIN_PASSWORD`와 `RATE_SALT`는 사용자가 Cloudflare 대시보드에서 직접 넣어요. 채팅으로 받지 않아요.
- 서명 키(`*.jks`)와 `keystore.properties`는 git에 넣지 않아요(`.gitignore`에 있음). 빌드할 때 `BAMNAT_KEYSTORE_PROPERTIES`로 파일 위치만 알려 줘요.
- 비밀값이 보이는 화면 사진은 보내지 않아요. `.claude/` 폴더는 커밋하지 않아요.
- Google Play는 리뷰 대가 보상을 금지해요. 정보 나누기 보상을 스토어 리뷰와 묶지 않아요.
- 회사 설정 제보에는 금액이 든 정보(기본급, 수당 금액, 1회 정액, 입사일, 이름, 연락처)를 넣지 않아요.
- 명세서 금액, 연봉(`annual`), 명세서 확인 기록(`verified`)은 휴대폰 안에만 둬요. 회사 설정 파일과 서버에는 넣지 않고, 제보에는 맞춰 봤는지(참·거짓)만 보내요.
- 링크 공유: 메모와 급여는 사용자가 고를 때만 서버로 가요. 받은 근무표는 `noBackupFilesDir`에 두고 링크가 끊기거나 만료되면 지워요.
  링크의 '쉬는 날' 표시는 링크에 보이는 것만으로 계산하고, 고르지 않은 연장 근무는 드러내지 않아요(대근만 `second`로 근무로 셈).
- 공유 서버의 `ANDROID_CERT_SHA256`은 공개값이고 사용자가 대시보드에서 넣어요.
- 처리방침(`privacy/index.html`)은 서버로 가는 정보가 바뀔 때마다 판을 올리고, 테스트(`ShareServerTest`)가 문구를 확인해요.

## 5. 빌드·테스트·서명

모두 `shift-calendar-app/`에서 실행해요. JDK 17 이상, Android SDK가 필요해요.

```bash
sh ./gradlew -p core test                                  # 급여·근무 계산 (1.12.0: 289개)
sh ./gradlew :app:testDebugUnitTest :app:lintDebug --continue   # 앱 테스트·스크린샷·린트 (280개, 서버 연결 1개 건너뜀)
python3 tools/check_tracker.py                             # 추적표 검사
(cd server/company-worker && npm test)                     # 회사 서버 (45개)
(cd server/share-worker && npm test)                       # 공유 서버 (16개)
```

- 앱 테스트 결과는 `app/build/test-results/testDebugUnitTest`, 화면 사진은 `app/build/screenshots`, 린트는 `app/build/intermediates/lint_intermediate_text_report/`에 나와요.
- 저장소 맨 위의 `npm test`(GitHub Actions CI)는 같은 저장소에 있는 다른 웹앱(살림왕) 테스트예요.
- 서명한 출시 파일

```bash
BAMNAT_KEYSTORE_PROPERTIES=<keystore.properties 위치> sh ./gradlew :app:bundleRelease :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

  인증서 SHA-256은 `8e7891b3e37e27c3911dcd83c87f647e6c83c8c631b248b6b694040910a97acb`이어야 해요(1.1.0부터 같은 업로드 키).
  결과물은 `app/build/outputs/apk/release/app-release.apk`와 `app/build/outputs/bundle/release/app-release.aab`이고, `Bamnat-<버전>-release.apk`, `Bamnat-<버전>.aab`로 이름을 바꿔 보관해요.
- 버전은 `app/build.gradle.kts`의 `versionCode`와 `versionName`을 올려요. DB를 바꾸면 Room 마이그레이션과 테스트를 같이 만들어요.

## 6. 코드 구조

```
shift-calendar-app/
  core/    안드로이드 없는 순수 Kotlin (JVM 테스트)
    pay/       급여 엔진 (PayCalculator, PayProfile, Unknowns, PayRange, PayslipMatch)
    setup/     첫 설정 (SetupDraft, TodayQuestion, CompanyTemplates)
    schedule/  근무 주기와 근무 묶음 (ShiftBundles)
    hours/ alarm/ sleep/ notify/ work/ money/ holiday/ memo/ backup/ diag/ model/
  app/     안드로이드 앱
    ui/setup/      질문형 첫 설정 (SetupHost)
    ui/pay/        급여 화면, 질문형 급여 설정 (PayWizard), 명세서로 맞춰 보기 (PayslipMatchDialog), 회사 규칙 편집기
    ui/calendar/   달력, 공유 (ShareDialog), 받은 근무표 (ReceivedCalendarUi)
    ui/company/    회사 찾기·제보 (CompanyNudges의 AskDeveloperDialog 등)
    company/       회사 서버 클라이언트 (CompanyClient, 주소가 비면 꺼짐)
    share/ data/ alarm/ widget/ ...
    src/online*/   서버 주소를 넣은 빌드에만 붙는 매니페스트(인터넷 권한)
  server/
    share-worker/    공유 서버 (Cloudflare Worker + KV)
    company-worker/  회사 서버와 개발자 앱 /admin/ (Cloudflare Worker + D1), 설명은 그 폴더 README
  docs/    추적표, 급여 골든 사례, 스크린샷 일부, 이 문서
```

- 서버 코드를 고친 뒤에는 사용자가 Cloudflare 대시보드에서 Worker 코드를 다시 붙여 넣고 배포해요(각 서버 README의 3단계).
  Claude는 Cloudflare 토큰이 없고, 토큰을 받지도 않아요. 출시용 `worker.js`는 그 서버의 `src/index.js`와 같은 내용이에요.

## 7. git 밖에 있는 것 (사용자가 새 프로젝트에 파일로 올림)

이전 프로젝트의 파일을 묶은 압축 파일 두 개로 옮겨요. 새 프로젝트에서는 올라온 파일을 풀어 프로젝트 파일 폴더에 두고 써요.

- 인수인계 묶음: 비공개 메모(사용자 근무·급여 조건, 서버 주소, 결정 기록), 개발 계획서 내보낸 파일, 법·Play 준비 메모, UX 리뷰,
  서버 배포 안내서와 출시용 `worker.js`, Play Console 답안, 처리방침 사본, 스토어 이미지 안내
- 서명 키 묶음: `bamnat-upload.jks`, `keystore.properties`. 비밀이에요. git과 채팅에 넣지 않고, 내용을 화면에 출력하지 않아요.
- 출시 파일(APK, AAB)은 키가 있으면 다시 만들 수 있어서 옮기지 않아도 돼요.
- 개발 계획서는 Claude Docs 문서였어요. 새 계정에서는 내보낸 파일을 보고, 필요하면 새 문서로 다시 만들어요.
- Cloudflare 계정, Google Play Console, GitHub 저장소는 그대로예요. 옮길 것이 없어요.

## 8. 새 계정의 Claude가 처음 할 일

1. 이 문서와 비공개 메모를 읽어요. 압축 파일이 올라와 있으면 풀어서 위치를 확인해요(서명 키는 내용을 열어 보지 않아요).
2. `git log --oneline -5`로 main이 1.12.0 병합 상태인지 확인해요.
3. core 테스트를 한 번 돌려 기준을 확인해요.
4. 2항의 순서대로 할지 사용자에게 한 번 확인하고 시작해요.
