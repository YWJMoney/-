# 밤낮 인수인계 (다른 계정에서 이어 가기)

마지막 정리 2026-10-23, 앱 1.22.0(versionCode 33, 워치 100033) 기준이에요. 맨 위 0항이 지금 판(1.22.0), 0-0항이 1.21.0, 0-1항이 1.20.0, 0-2항이 1.19.0이고, 1항부터는 1.17.2 때 정리를 이어 고친 것이에요.

이 저장소는 공개예요(GitHub Pages로 처리방침을 올려요). 그래서 이 문서에는 비밀값, 서명 키, 사용자 개인 정보를 적지 않아요.
그런 것은 사용자가 새 프로젝트에 파일로 따로 올려요(7항). 새 계정의 Claude는 이 문서와 그 비공개 메모를 먼저 읽고 시작해요.

## 0. 지금 판: 1.22.0 Phase 2 완료판 (2026-10-23)

남은 Phase 2의 Claude 몫을 개발 스레드 일곱 묶음(A~G)으로 나눠 통합 브랜치 `claude/project-thread-kz90cm`에 합친 한 판이에요(PR #51~#73). DB는 19(연차 원장)이고 Room은 `:database` 모듈로 옮겼어요. 남은 체크리스트 줄은 모두 사용자·바깥 몫이에요(비공개 `계획/Phase2-완료-체크리스트.md`, `인계/다음-스레드-시작-1.23.md`).

- **처음 화면과 설정(묶음 G)**: `ui/onboarding/RecommendedScreen.kt`('추천대로 켜고 시작하기' 한 번에 권한과 알림, 사용 통계는 미리 체크 안 함), `data/RecommendedSettings.kt`, 설정 7묶음 `SettingsPage`(내 근무·알람·알림과 잠·급여와 연차·달력 화면·데이터·개인정보·앱 정보) + '더 보기'. `SettingsLayoutTest`는 '설정 › X'가 있는 묶음 이름인지 봐요. 기존 사용자 설정은 바꾸지 않아요(D50).
- **원격 계산 자료(묶음 A)**: 저장소 맨 위 `remote/v1/*.json`과 `.sig`를 GitHub Pages에서 하루 한 번 받아요(`gradle.properties` `bamnat.remoteBaseUrl`). core `remote/Ed25519.kt`(순수 Kotlin 검증), `RemoteFile.kt`, `PresetFile.kt`, 앱 `remote/`. 서명은 `tools/remote_sign.py sign --key <비공개 경로>` 뒤 `check`. 실수령 `pay/TakeHome.kt`는 간이세액표와 비과세(식대 등 항목마다 월 20만 원)를 써요.
- **서버·광장·통계(묶음 B)**: 회사 서버 광장 소식 하루 한 번, 사용자 차단(글마다 암호화한 글쓴이 표시), 문의 사진(D1 BLOB), 동의한 사용 통계(날짜별 합계, 400일), 가려진 글 30일 확인 경고. 공유 서버 신고·삭제. `community/` 광장 운영 규칙, `terms/` 제16조 ②.
- **직군·근무표(묶음 C)**: core `schedule/RestRules.kt`(점검 기준 하나, 기본에서 흔한 정상 근무표는 경고 0건 `NormalRostersTest`), `pay/PublicPayTable.kt`(공무원 봉급표, 현업은 57시간 상한 없음), `pay/OrdinaryGuess.kt`(이름으로 통상임금 미리 고르기), 경찰·소방 프리셋, 법정 휴게 `schedule/LegalBreak.kt`.
- **연차·급여 2단계(묶음 D)**: core `leave/`(원장, 자동 발생, 사용 촉진, 정산), 보상휴가 `LeaveKind.COMP`, 오전 반차(`Leave.fromStart`, 저장 숫자 +100), 엑셀·CSV 듀티표 `imports/`, 포괄·탄력·호봉 규칙. 달력이 연차 사용의 정본이에요(D47).
- **매일 화면·돈(묶음 E)**: 위젯 메모 줄·오늘 모든 조, 돈 화면 카드 순서, 영수증 글자 인식(기기 안), 백업에 사진(30MB), 반쯤 접힘 `androidx.window`, 시세 견주기, 월말 요약. 시간 표시는 `HoursFormat.korean`.
- **품질(묶음 F)**: `perf/StartupBudgetTest`(이름 붙은 시작 작업 17), `data/db/MigrationAllVersionsTest`(1~18→19), `a11y/ContrastTest`·`AccessibilityCheckTest`(화면 사진 510~519), Baseline Profile, 시험 격리(`TestIsolationTest`, `BamnatApp.onTerminate`), `tools/check_checklist.py`.
- **법 문서**: `privacy/index.html` 처리방침 2.8(시행 2026-11-21), `docs/play-data-safety.md` 1.22.0장.
- **시험 결과(2026-10-23)**: 아래 할 일 문서 `release/1.22.0/사용자가-해야-할-일.md`(비공개)에 적어요.

## 0-0. 앞 판: 1.21.0 사용자 제안 (2026-10-22)

사용자 제안(모든 조·달력 버튼·간호사와 직군·투자 알림·광장 고도화·직군별 광장)을 모두 만들었어요. DB는 18 그대로예요(결정 D40). 다음 판은 1.22.0이에요(비공개 `인계/다음-스레드-시작-1.22.md`).

- **모든 조와 대근 찾기**: core `schedule/TeamRoster.kt`(내 조가 몇 번째여도 내 주기, 다른 조 순서 유지), `schedule/CoverFinder.kt`(쉬고 싶은 날 들어올 수 있는 조, 휴식 기본 11시간 D31), 앱 `ui/calendar/TeamsScreen.kt`, `data/TeamStore.kt`. 달력 위 도구 줄과 '모든 조' 버튼은 `CalendarScreen`의 `CalendarHeader`.
- **간호 한 달 연달아 넣기**: core `schedule/DutyCheck.kt`(11시간, 나이트 뒤 2일, 연속 나이트 3일, 7일 연속; 알려만 줌), 앱 `ui/calendar/DutyTypingSheet.kt`, `CalendarViewModel.applyDuty`(한 번에 저장·되돌리기). 듀티표 인식은 core `imports/DutyRoster.kt`(S=미들, 병가·생휴·AL·SL·PH, '1(월)').
- **급여 엔진**: `pay/TakeHome.kt`가 그 달 날짜로 요율표 절반과 국민연금 상하한(`PENSION_BASES`)을 써요. `PayCalculator`의 수습 3개월 경고, 주휴의 공휴일 넘어가는 밤샘(`weeklyHolidayLine`의 `holiday`). 리뷰는 비공개 `research/1.21-급여엔진-리뷰.md`.
- **직군 프리셋**: `pay/PayProfile.kt`의 `Occupation.PUBLIC·POLICE·FIRE`, `pay/PublicServantPay.kt`(봉급기준액 55%/60%, 시간외 ÷209×1.5, 야간 ÷209×0.5, 휴일 ÷26×1.5를 내 규칙 3개로), `PayPresets.firstSetup`. `PayWizard`가 처음 만들 때만 적용해요.
- **투자 체결 알림**: core `invest/TradeAlertParser.kt`·`TradeAlerts.kt`(대기 30개, 10분 안 같은 체결은 한 번), 앱 `invest/TradeCapture.kt`(`AppContainer.capture`가 먼저 부름, `CaptureService` 필터), `data/InvestRepository.kt`(투자 설정 표의 `pendingAlerts`·`alertAuto`), 화면 `ui/invest/InvestAlertsCard.kt`.
- **광장**: 서버 `server/company-worker/src/index.js`(글 맞아요·달라요 `post_votes`, 답글·제안 `posts.parent/kind/change`, 자동 채택 `settle`·`payOut`(D32·D33), 판 기록, 쓰는 사람 `uses`, 광장 이름 `nicks`, 직군·지역 `companies.kind/job/region`, 임금표 `rates`·`rate_votes`(D39), 내 데이터 지우기는 채택된 것을 남김(D34)), 관리자 앱 `src/admin-app.js`(채택 기준·임금표 검토·내리기). 앱 `ui/company/PlazaScreens.kt`·`CompanyBoard.kt`·`CompanyViewModels.kt`, `company/CompanyClient.kt`, `data/CompanyShare.kt`.
- **공휴일**: `holiday/KoreanHolidays.kt`의 `ELECTIONS`(2024 선거일·국군의 날, 2028 국회의원 선거일). **복원**: `BackupManager.readSchedule`이 근무 종류 없는 추가 달력에 기본 근무 종류를 넣어요.
- **법 문서**: `privacy/index.html` 처리방침 2.7(게시 2026-10-22, 시행 2026-11-21, 2.5·2.6과 같은 날). `docs/play-data-safety.md` 1.21.0장.
- **시험**: core `DutyCheckTest`, `DutyRosterNurseTest`, `TakeHomeYearTest`, `PublicServantPayTest`, `Holidays2028Test`, `TradeAlertParserTest`(+`TradeAlertsTest`), `HourlyWorkTest`, 앱 `InvestAppTest`, `PersonalCalendarsTest`, `rules/LegalPagesTest`, 화면 사진 361~365, 서버 `test/plaza.test.mjs`.

## 0-1. 앞 판: 1.20.0 프로 가치 (2026-10-22)

프로 화면 네 기둥의 남은 줄을 모두 만들었어요. 모두 프로 기능이고, 정식 출시 스위치 전에는 1.19.0처럼 모두 열려 있어요. 다음 판은 1.21.0 출시 품질·원격 갱신·원문 대조예요(`계획/Phase2-완료-체크리스트.md` 1장).

- **기능 표**: `tier/FeatureGate.kt`에 PAY_REPORT, WORK_COMPARE, DEVICE_CALENDAR, ICS_FEED, AUTO_BACKUP, MORE_LINKS, ROSTER_PDF, SURE_ALARM이 더해졌어요. 프로 화면 `ui/pro/ProScreen.kt`의 `PRO_PILLARS`는 기둥마다 여는 `Feature`를 적고, `TierAppTest`가 모든 프로 기능이 한 기둥에만 있는지, 서버의 `freeFeatures`로 하나씩 열 수 있는지 봐요. 관리자 앱 '모두에게 무료로 연 프로 기능'에는 이 이름(영어 대문자)을 적어요.
- **급여 리포트·비교·근무표 PDF**: core `report/PayReport.kt`·`MonthCompare.kt`·`RosterReport.kt`·`ReportDoc.kt`(쪽·표 모양), 앱 `report/ReportPdf.kt`(`PdfDocument`로 A4, 글꼴은 시스템)·`ReportShare.kt`. 화면은 `ui/pay/PayReportCard.kt`, `ui/stats/CompareCards.kt`(StatsPart.COMPARE·YEAR), 공유 창의 'N월 PDF·N년 PDF'.
- **휴대폰 캘린더**: `calendar/PhoneCalendar.kt`(동기화 어댑터로 `ACCOUNT_TYPE_LOCAL` 캘린더, 날짜마다 고정 키로 바뀐 것만 다시 씀), `calendar/PhoneCalendarSync.kt`(설정 `phoneCalendars`를 따라 몇 초 뒤, 3개월 전부터 1년 뒤까지). `WRITE_CALENDAR`는 켤 때만 물어요. 캘린더 가져오기는 이 캘린더를 목록에서 빼요.
- **공유 링크**: `share/ShareLink.kt`의 `ShareLinkKey`(1번은 달력 ID, 2~5번은 `id~N`, 1번의 저장 파일 이름은 예전 그대로), 기간 7·30·90·180·365(180·365는 프로), 링크마다 `feed`(구독 주소). `ShareSync`가 모든 링크를 따라 보내고, 프로가 끝나면 2~5번은 기간까지 두되 늘리기는 `Result.Locked`(결정 D23). 공유 서버는 `GET /s/:id.ics`와 `ics`·`tz`를 받아요(서버 README '앱 1.20부터'). 만든 링크는 `share/ShareLinkBackup.kt`로 백업 파일에 열쇠와 함께 들어가고, 불러올 때 휴대폰에 있던 링크는 지우지 않아요(결정 D29).
- **매일 자동 백업**: `backup/AutoBackup.kt`(하루 한 번, 'rwt'로 쓰고 쓴 길이로 잘라 낸 뒤 다시 읽어 풀어 봄, 3일 실패 알림), `backup/AutoBackupJob.kt`(JobScheduler 6시간, 배터리 낮지 않을 때), `backup/KeyBox.kt`(암호에서 만든 열쇠를 키 저장소 AES-GCM으로 감쌈, 암호는 저장 안 함). 파일은 손으로 한 암호 백업처럼 같은 암호로 어느 휴대폰에서나 열려요. 열쇠와 파일 위치(`auto_backup` 설정 파일)는 백업에서 빠져요.
- **확실한 알람**: core `alarm/AlarmMission.kt`(계산 3문제, 흔들기 20번, `ShakeCounter`, 5분 뒤 미션 없이 끄기), 앱 `alarm/MissionScreen.kt`, `AlarmActivity`(미션 화면), `AlarmService`(미션이 있으면 알림의 '끄기'가 미션 화면을 엶, 끄면 `WakeCheck.schedule`), `alarm/WakeCheck.kt`(끈 뒤 N분에 '일어났나요?', 90초 뒤 한 번 더 울림, 알람 시계가 아닌 정확한 알람). 설정은 `ui/settings/SureAlarmCard.kt`, 잠금 풀기 전 사본은 `BootAlarms`가 함께 둬요. 프로가 아니면 `RingSettings.of(s, sure = false)`라 예전처럼 울려요.
- **스마트 알람 제안(ALRM-03)**: core `alarm/AlarmSuggest.kt`가 근무 종류별 최근 5번, 첫 알람만 보고 제안 하나를 골라요(늦게 끔 3/5 → 20분 앞, 30분 넘게 먼저 깸 3/5 → 15분 뒤, 잠이 목표보다 90분 넘게 짧음 3/5 → 잘 시간 알림). 끈 시각은 `AlarmService`가 `alarm_dismissed` 줄에 `type= due= off=`로 남기고(기기 안 이벤트 기록, 사용 통계로 안 감), 잠은 수동 기록과 이미 켠 Health Connect를 합쳐 써요.
  카드는 `CalendarScreen`의 `AlarmSuggestCard`(달력 맨 위, 내 달력만), 적용은 `CalendarViewModel.applyAlarmSuggestion`이 `AlarmShift.move`로 그 근무만 바꾸고 10초 되돌리기. 주 1장, 답한 제안은 30일 쉼, '다시 보지 않기'는 설정 › 알람 › '숨긴 제안 다시 보기'로 풀려요(`alarmSuggestMemory`, 기기에만, 백업 안 함).
  끄는 곳은 설정 › 알람 › '알람 제안'(기본 켜짐, 백업에 들어감, `alarm.suggest`). 이벤트 기록이 1,000줄로 잘려서 화면 이동이 아주 많으면 오래된 끈 기록이 빠질 수 있어요(그러면 제안이 늦게 나올 뿐 틀리지 않음).
- **증명 사진 미션 [ALRM-04] (1.23)**: core `alarm/ImageHash.kt`(32×32 흑백 → dHash 64비트, 해밍 ≤ `SAME_PLACE` 18이면 같은 곳, 기준 2장 중 가까운 쪽), `Missions.shown`이 기준 없음·3번 틀림·카메라 실패를 계산 문제로 바꿔요. 앱 `alarm/PhotoMission.kt`(`PhotoReferences` = 자기 prefs `photo_mission`, 백업 규칙과 밤낮 백업 파일 모두 밖, `PhotoPrint` = 카메라 앱 사진을 EXIF 방향 맞춰 줄이고 `finally`에서 지움, `PhotoAttempts`).
- 사진은 명세서 사진과 같은 길(카메라 권한 없이 `TakePicture` + FileProvider `scan/`), 잠금 화면에서는 `requestDismissKeyguard` 뒤에 카메라를 열어요. 설정은 `ui/settings/PhotoMissionRows.kt`(끄기 미션 아래, 기준이 없으면 증명 사진을 고를 수 없음). 시험 `ImageHashTest`·`PhotoMissionTest`, 사진 715~719.
- **법 문서**: `privacy/index.html` 처리방침 2.6(1.19.0과 1.20.0을 PR #49 하나로 합쳐 2.5와 같은 날 게시, 게시 2026-10-22·시행 2026-11-21 예정, 2.5 몫 때문에 30일 고지). `docs/play-data-safety.md` 1.20.0장.
- **시험**: core `ReportTest`·`AlarmMissionTest`·`GuideTest.whatsNewOpensRealFeatures`, 앱 `report/ReportPdfTest`, `calendar/PhoneCalendarTest`, `share/ShareLinksProTest`, `backup/AutoBackupTest`, `alarm/SureAlarmTest`, `tier/TierAppTest`, 화면 사진 385~398, 공유 서버 `worker.test.mjs`.

## 0-2. 그 앞 판: 1.19.0 수익화 기반 (2026-10-22)

일하는 규칙은 `CLAUDE.md`(문서 먼저, 판마다 체크리스트, Phase 3 진입 금지). 남은 일의 정본과 판 계획은 비공개 프로젝트 파일 `계획/Phase2-완료-체크리스트.md`에 있어요. 다음 판은 1.20.0 프로 가치(자동 암호 백업, 공유 링크 여러 개, 통계 확장, 급여 리포트 PDF·CSV, 등급 최종 시험, ICS 구독, 기기 캘린더 쓰기, 확실한 알람, 근무표 PDF)예요.

- **꺼진 채로 나가요.** 회사 서버 `GET /api/config`의 `billingLive`가 켜지기 전(관리자 앱 › 설정 › '정식 출시', 최고 관리자)에는 모든 기능이 무료이고, Play 결제에 연결하지 않고, 광고 SDK도 시작하지 않아요(매니페스트 `DELAY_APP_MEASUREMENT_INIT`). 미리 보기는 알람 점검 화면의 등급 선택(출시 뒤에는 디버그 빌드만).
- **등급 판정 한 곳**: `tier/FeatureGate.kt`(`Feature.minTier`, 무료/프로, 리더는 Phase 3) → `Gate.allows(f) = !live || f in free || tier >= f.minTier`. 프로 이유는 core `tier/ProAccess.kt`가 순서대로 정해요: Play 구독(체험·유예 포함) → 관리자 휴대폰(7일 안 확인) → 보상 이용권 → 출시 감사 보상 → 광고 보고 하루 이용.
- **원격 설정**: `tier/RemoteConfig.kt`(하루 한 번, 엄격한 해석, 인터넷 없으면 마지막 값, 처음엔 꺼짐). 서버가 프로 기능을 무료로 열 수는 있어도 거꾸로는 못 해요.
- **결제**: `billing/Billing.kt`(Play Billing 9.1.0, `bamnat_pro`의 `monthly`·`yearly`, 혜택 `trial-1m`, 승인·복원, 결제 없는 기기는 `NoBilling`). 서버 `POST /api/purchases`가 `subscriptionsv2.get`으로 확인해요. 서비스 계정 키(Cloudflare 비밀값 `PLAY_SERVICE_ACCOUNT`)가 없으면 `verified:false`로 답하고 앱은 휴대폰의 Play 상태를 써요. 실시간 개발자 알림(RTDN)은 쓰지 않아요.
- **무료 체험 전환 동의(전자상거래법 제13조 제6항)**: core `tier/TrialConsent.kt`(끝나기 7일 전부터 묻기, 마지막 날), 앱 `ui/pro/TrialConsentPrompt.kt`와 알림 `tier/TrialNotice.kt`(재부팅·시간 바꿈 뒤 다시 걸기). 답은 휴대폰과 서버 `POST /api/consents`에 남고 서버 크론이 5년 뒤 지워요. 동의가 없으면 서버 크론이 끝나기 24시간 안에 다음 결제를 멈춰요(키가 있을 때).
- **잠금과 프로 화면**: `ui/pro/ProLock.kt`(자기 숫자로 그린 뒤 12+ 흐림, 아래는 덮개), `ui/pro/ProScreen.kt`(비교표, 가격·갱신·해지·7일 청약철회·약관·처리방침·구매 복원, 보상 이용권 복구 코드, 관리자 연결). 설정 첫 화면에 '프로' 줄.
- **광고**: `ads/Ads.kt`(UMP 동의 뒤 SDK 시작, 설정 › 개인정보와 권한 › 광고 개인정보 선택), `ui/components/AdSlot.kt`(띠, AppRoot 한 곳에서만), `ads/RewardedAds.kt`(고를 때만, 하루 한 번). 규칙은 core `tier/AdRules.kt`(설치 3일 광고 없음)와 `AdAndLicenseRulesTest`. 광고 단위 ID는 `gradle.properties`의 `bamnat.admobAppId`·`bamnat.admobBanner`·`bamnat.admobRewarded`(공개값, 비면 출시 빌드는 광고 없음, 디버그는 Google 시험 ID). `play-services-ads`는 25.2.0에 묶었어요: 25.3.0부터 Kotlin 2.3 메타데이터라 지금 Kotlin 2.1.21로 빌드가 안 돼요.
- **보상 이용권·관리자 연결**: `tier/ProStore.kt`(이용권·복구 코드·관리자 상태, 백업에는 복구 코드와 감사 안내 본 기록만), `tier/TierApi.kt`. 서버 `GET /api/passes`, `POST /api/passes/code`, `POST /api/passes/claim`, 관리자 앱 › 내 정보 › '내 휴대폰 밤낮 연결'(6자리 코드). 결제·관리자 상태는 Android 자동 백업에서 빼요.
- **법 문서**: 저장소 맨 위 `privacy/index.html`(처리방침 2.5, 게시 2026-10-22·시행 2026-11-21 예정, 방침 9항의 불리한 변경 30일 고지), `terms/index.html`(이용약관 1.0). 시험 `LegalPagesTest`가 판·필수 문구를 봐요. Play 데이터 보안 답안은 `docs/play-data-safety.md` 1.19.0장.
- **시험**: core `TierTest`, 앱 `tier/TierAppTest`(가짜 `FakeTierApi`), `rules/AdAndLicenseRulesTest`, `rules/LegalPagesTest`, 화면 사진 375~384, 서버 `test/money.test.mjs`.

## 1. 지금 상태

- 안드로이드 전용 앱이에요. 갤럭시 폴드의 접은 화면과 펼친 화면을 둘 다 맞추고, 첫 화면은 달력이에요.
- Kotlin, Compose BOM 2025.05.00, Room v18 (투자 달력 표 9개, INV), minSdk 26, targetSdk 36, Robolectric 4.14.1.
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
| 1.17.0 | P3·INV·MEMO·UNDO·SWIPE·GUIDE·ADMIN-10~12 | 시장조사 P3 전부(듀티표 사진 인식, 연간 건강 지표, Wear OS 앱 `:wear`, 근태 엑셀, 위치 출퇴근 자동 기록, 두 알바 비교, 근로계약서, 퇴사 정리, 견적서, 노임단가, 일용직 세금, 간이과세, 날씨, 직원 인건비), 투자 달력과 투자일지(INV, DB 18), 메모 달력별 기본·메모 공유(MEMO), 근무 유형 변경 확인·최근 지운 기록 7일(UNDO), 가계부 밀어서 지우기 확인(SWIPE), 근무 유형별 안내·기능 켜고 끄기·기능 찾기(GUIDE), 관리자 개발사항(서버 ADMIN-10~12) |
| 1.17.1 | REV-01~06 | 1.14 이후 전체 리뷰 수정: 계산(반차 이중 차감, 수습 감액, 주휴 배지, 법정 휴게, 간이과세, 세금 마감 주말), 데이터(백업 복원 때 추가 달력 도구·계약서 사진 보존, 지운 달력 되살리기, 되돌리기 전 기록 보관), 출퇴근 자동 기록(끄면 지오펜스 해제, 재등록 줄임, 알림 따로), 투자 달력(미래 입금, 원칙 글자로 저장, 알림 겹침), 서버(문의 정리, 개발사항 권한), 워치(재부팅 뒤 자정 갱신), 버전 번호 gradle.properties 한 곳 |
| 1.17.2 | REV-07~14 | 리뷰 뒤 남은 일: 공휴일 주휴 이중 계산, 급여 이름 넓히기, 가져오기로 없어지는 달력 사진 보관, 위치 다시 켠 뒤 지오펜스 다시 걸기(12시간 점검), 워치 컴플리케이션 타임라인, 투자일지 예전 번호 형식 제거, CI 워치 AAB 확인, 되돌리기·어제 퇴근 시험 |
| 1.16.0 | LEDG-17~24·CALS·TYPE·NOTE | 가계부 자동 분류(PR #45), 달력 고치기 화면, 달력마다 따로 설정·결제 알림 쌓을 달력·카드별 달력(CALS), 근무 유형별 말·자영업 영업일·정기휴무·매출 없으면 휴무·유형별 통계(주휴수당·거래처별·요일별 매출)·유형별 분류(TYPE), 달력별 메모·공용 메모장·목록 이어쓰기·키보드 가림 방지(NOTE, DB 16). versionCode 25: 시장조사 P1·P2 전부(SHFT·DAYW·HRLY·FLD·SHOP·COMN·NOTE-05~, DB 17: 지각·조퇴·결근, 교대 바꾸기, 근무 종류 가산율, 일감 상태·공수·지급 예정일, 결제 수단별 매출·시재, 증빙·영수증 사진, 메모 반복) |
| 1.18.0 | WEEK·CAL-19~22·CREW·CUST·GUIDE-08·INQ-02~04·LOCK | 주 근무시간 월~일, 모든 조 보기, 화면 꾸미기, 업데이트 뒤 새 기능 안내, 문의 진행 단계, 앱 잠금 |
| 1.19.0 | MON-01~11 | 무료/프로 두 등급, 원격 설정과 정식 출시 스위치(꺼진 채), Play 결제, 체험 전환 동의, 흐린 잠금과 프로 화면, 띠·보상형 광고와 광고 동의, 보상 이용권, 관리자 휴대폰 연결, 이용약관 1.0, 처리방침 2.5 (0-1항) |
| 1.20.0 | PROV-01~08·FEAT-07·FEAT-15 | 급여 리포트 PDF·CSV, 지난달·작년 비교, 휴대폰 캘린더에 근무 쓰기, 캘린더 구독 주소, 링크 5개·1년, 매일 자동 암호 백업, 근무표 PDF, 끄기 미션과 일어났나요 확인, 처리방침 2.6 (0항, DB는 그대로 18) |

- 서버 두 개가 사용자의 Cloudflare 계정에 있어요.
  - 공유 서버(`server/share-worker`, KV): 켜져 있고 앱이 써요(`gradle.properties`의 `bamnat.shareBaseUrl`).
  - 회사 서버(`server/company-worker`, D1): 2026-10-18에 배포했고 개발자 앱(`/admin/`) 로그인까지 돼요.
    1.12.0까지는 앱에 연결하지 않았고(`bamnat.companyBaseUrl`이 비어 있음), ADMIN-02 작업에서 연결했어요. 연결한 빌드부터 회사 이름 검색,
    회사 설정 보내기, '회사 방식 알려 주기', 설정 > 문의하기가 이 서버로 가요. 비우고 빌드하면 예전처럼 꺼져요.
- 설정 > 문의하기 [ADMIN-02]: 서버가 있는 빌드는 앱 안 화면(`ui/settings/InquiryScreen.kt`, `company/InquiryClient.kt`)으로 보내고 답도 거기서 받아요.
  서버가 없는 빌드와 화면의 '메일로 보내고 싶어요'는 메일 앱을 열어요(`SettingsPages.kt`의 `writeToUs`, 주소는 `REPORT_EMAIL`).
  서버 쪽 API와 저장은 `server/company-worker/README.md`의 '앱 문의'. 개발자 앱의 답장 화면은 ADMIN-03 작업이 만들어요.
  **서버 코드를 다시 붙여 넣어야(README 3번) 문의 API가 살아나요.** 그 전에는 앱이 문의를 보내면 "보내지 못했어요"가 나와요.
- 개인 달력 여러 개 [MCAL-01~07], 1.14.0·1.14.2: 지금 달력은 그대로 '내 달력'(`bamnat.db`)이고, 추가한 달력은 `calendar_<id>.db`에 근무 종류·주기·날짜를 따로 둬요.
  목록·순서·보고 있는 달력은 `files/calendars.json`(`data/CalendarBook.kt`). 1.16.0부터 달력 탭·통계·설정(근무 형태·종류·주기·급여)은 보고 있는 달력을 따라요(설정 화면 '설정할 달력'). 위젯·알림은 내 달력.
  메모는 `memos.calendars`로 달력에 붙어요. MEMO-01부터 빈 값은 예전 메모로 내 달력 것, `,*,`는 모든 달력(공용 메모장)이고, 달력끼리 함께 보기는 `files/memo_sharing.json`(설정 › 달력 화면 › 메모 공유). 근무 형태 바꾸기·기록 지우기·달력 지우기는 `noBackupFilesDir/records_trash`에 7일 남아 설정 › 데이터 › 최근 지운 기록에서 되돌려요(`data/RecordsTrash.kt`, UNDO-01~03). 그리고 결제 알림은 `AppContainer.capture`가 카드 끝 4자리 → 카드사 → 앱별 → 기본 달력 순으로 넣어요(`core/money/CaptureRoute.kt`).
  자영업 달력은 `PersonalCalendar.shop`(영업시간·정기휴무)을 `ui/work/ShopDaysScreen.kt`의 `ShopKeeper`가 근무표에 반영하고, 앱을 열 때 매출 없는 지난 영업일을 휴무로 바꿔요(스스로 바꾼 날만 매출을 적으면 다시 영업).
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
(cd server/company-worker && npm test)                     # 회사 서버 (1.23 H: 178개)
(cd server/company-worker && npm ci && npm run test:ui)    # 관리자 앱 화면 시험, happy-dom (1.23 H: 10개)
(cd server/share-worker && npm test)                       # 공유 서버 (16개)
```

- 앱 테스트 결과는 `app/build/test-results/testDebugUnitTest`, 화면 사진은 `app/build/screenshots`, 린트는 `app/build/intermediates/lint_intermediate_text_report/`에 나와요.
- 저장소 맨 위의 `npm test`(GitHub Actions CI)는 같은 저장소에 있는 다른 웹앱(살림왕) 테스트예요.
- **품질 규칙 (1.23 M)**
  - 시작 작업: `AppContainer.start()`의 이름 붙은 작업은 **21개까지(새 작업 0)**, 달력이 첫 달을 그리기 전에는 **7개 이하**예요. 나머지는 `afterFirstFrame()`에서 돌아요(`perf/StartupBudgetTest`).
  - 화면 사진 시험: 묶음마다 `screenshots/<묶음>ScreenshotTest.kt`에 `class XScreenshotTest : ScreenshotBase()`를 만들고, `@RunWith(AndroidJUnit4::class) @GraphicsMode(NATIVE) @Config(sdk = [35])`를 붙여요. 번호대는 묶음끼리 겹치지 않게 정해요. 기다림은 `settle()`(조건 대기, 1초 상한)과 `reach()`(글자를 찾을 때까지 5초)에 맡기고 `Thread.sleep`을 새로 쓰지 않아요.
  - 나눠 돌리기: `-Pbamnat.testSet=unit|shots-a|shots-b|api`(CI 네 조각). 안드로이드 8·11·13·15에서 도는 `rules/ApiLevelSmokeTest`·`AlarmMatrixTest`는 `api`에서만 돌아요(여러 판 샌드박스가 한 JVM에 섞이면 다른 시험의 SQLite가 죽어요). 아무 값 없이 돌리면 빠져요, `-Pbamnat.testForks=N`(기본은 코어 절반, 1~3). 느린 시험 표는 `python3 tools/slow_tests.py app/build/test-results/testDebugUnitTest`로 봐요.
  - 크기: `python3 tools/app_size.py <apk|aab>`로 봐요. 예산은 APK 9.5MB, AAB 23.5MB이고, 넘으면 출시 빌드가 멈춰요. 글자 인식(ML Kit 한국어)은 앱에 넣지 않고 Google Play 서비스가 처음 쓸 때 내려받아요. 언어는 한국어만 넣어요(`localeFilters`).
  - 린트: 새 오류가 생기면 멈춰요. 예전 것은 `app/lint-baseline.xml`, `wear/lint-baseline.xml`에 있고, 고친 뒤 `:app:updateLintBaseline :wear:updateLintBaseline`으로 줄여요.
  - 백업 파일은 형식 3(zip: `backup.json`, `photos/…`, `sha256.txt`)이에요. 옛 `.json` 백업도 그대로 열려요(`data/BackupArchive.kt`).
- **Maven Central이 429(요청이 많다)로 막힐 때**: `~/.gradle/init.d/central-mirror.gradle.kts`에 `https://maven-central.storage-download.googleapis.com/maven2/` 저장소를 `pluginManagement`와 `dependencyResolutionManagement` 맨 앞에 넣으면 받아져요. 내려받기는 한 번에 하나씩 하고, 빌드는 `gradle --no-daemon -Pkotlin.compiler.execution.strategy=in-process`로 돌려요. Robolectric의 안드로이드 jar(API 26 등)도 막히면 `-Pbamnat.robolectricRepo=<같은 주소>`를 붙여요.
- 서명한 출시 파일

```bash
BAMNAT_KEYSTORE_PROPERTIES=<keystore.properties 위치> sh ./gradlew :app:bundleRelease :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

  인증서 SHA-256은 `8e7891b3e37e27c3911dcd83c87f647e6c83c8c631b248b6b694040910a97acb`이어야 해요(1.1.0부터 같은 업로드 키).
  결과물은 `app/build/outputs/apk/release/app-release.apk`와 `app/build/outputs/bundle/release/app-release.aab`이고, `Bamnat-<버전>-release.apk`, `Bamnat-<버전>.aab`로 이름을 바꿔 보관해요.
- 버전은 `gradle.properties`의 `bamnat.versionCode`와 `bamnat.versionName`을 올려요(워치는 versionCode + 100000으로 저절로 따라가요). DB를 바꾸면 Room 마이그레이션과 테스트를 같이 만들어요.

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

### 6-1. 밖에서 여는 파일 (1.23.0, CAL-47)

메신저나 파일 앱에서 받은 파일을 누르거나 "공유"로 보내면 밤낮이 목록에 나와요(`MainActivity` intent-filter → `IncomingFiles`).
무엇인지는 파일 안을 읽어 정하고, 바꾸기 전에 늘 먼저 물어요. 첫 설정 전이면 첫 설정의 '파일로 시작하기', 뒤면 설정 > 데이터 > 파일 불러오기와 같은 길이에요.

| 파일 | 만드는 곳 | 이름 | MIME | 여는 곳 |
|---|---|---|---|---|
| 백업 | 설정 > 데이터 > 파일로 내보내기, 자동 백업 | `밤낮-백업-날짜.json` | `application/json` | 첫 설정 전: 첫 설정에서 복원 / 뒤: 설정 > 데이터 |
| 잠근 백업(비밀번호) | 같은 곳, 비밀번호를 넣었을 때 | `밤낮-백업-날짜.json` | `application/json` | 위와 같고, 비밀번호를 먼저 물음 |
| 회사 근무 파일 | 설정 > 데이터 > 회사 설정 보내기 | `밤낮-회사설정-회사.json` | `application/json` | 첫 설정 전: 회사 설정으로 시작 / 뒤: 회사 설정 적용 창 |
| `.bamnat` 이름의 위 파일 | 사람이 이름을 바꾼 경우 | `*.bamnat` | 아무거나 | 위와 같음(이름으로도 받음) |

- 받는 동작: `VIEW`(눌러 열기)와 `SEND`(공유로 보내기). `text/plain`은 받지 않아요(아무 글 공유에나 밤낮이 뜨지 않게).
- 확장자는 `.json` 그대로 둬요. 카카오톡 등 메신저가 모르는 확장자를 막거나 이름을 바꾸는 일이 있어서예요.

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
