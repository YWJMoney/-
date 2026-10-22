# 밤낮 인수인계 (다른 계정에서 이어 가기)

마지막 정리 2026-10-22, 앱 1.20.0(versionCode 31, 워치 100031) 기준이에요. 맨 위 0항이 지금 판(1.20.0)과 바로 앞 판(1.19.0)이고, 1항부터는 1.17.2 때 정리를 이어 고친 것이에요.

이 저장소는 공개예요(GitHub Pages로 처리방침을 올려요). 그래서 이 문서에는 비밀값, 서명 키, 사용자 개인 정보를 적지 않아요.
그런 것은 사용자가 새 프로젝트에 파일로 따로 올려요(7항). 새 계정의 Claude는 이 문서와 그 비공개 메모를 먼저 읽고 시작해요.

## 0. 지금 판: 1.20.0 프로 가치 (2026-10-22)

프로 화면 네 기둥의 남은 줄을 모두 만들었어요. 모두 프로 기능이고, 정식 출시 스위치 전에는 1.19.0처럼 모두 열려 있어요. 다음 판은 1.21.0 출시 품질·원격 갱신·원문 대조예요(`계획/Phase2-완료-체크리스트.md` 1장).

- **기능 표**: `tier/FeatureGate.kt`에 PAY_REPORT, WORK_COMPARE, DEVICE_CALENDAR, ICS_FEED, AUTO_BACKUP, MORE_LINKS, ROSTER_PDF, SURE_ALARM이 더해졌어요. 프로 화면 `ui/pro/ProScreen.kt`의 `PRO_PILLARS`는 기둥마다 여는 `Feature`를 적고, `TierAppTest`가 모든 프로 기능이 한 기둥에만 있는지, 서버의 `freeFeatures`로 하나씩 열 수 있는지 봐요. 관리자 앱 '모두에게 무료로 연 프로 기능'에는 이 이름(영어 대문자)을 적어요.
- **급여 리포트·비교·근무표 PDF**: core `report/PayReport.kt`·`MonthCompare.kt`·`RosterReport.kt`·`ReportDoc.kt`(쪽·표 모양), 앱 `report/ReportPdf.kt`(`PdfDocument`로 A4, 글꼴은 시스템)·`ReportShare.kt`. 화면은 `ui/pay/PayReportCard.kt`, `ui/stats/CompareCards.kt`(StatsPart.COMPARE·YEAR), 공유 창의 'N월 PDF·N년 PDF'.
- **휴대폰 캘린더**: `calendar/PhoneCalendar.kt`(동기화 어댑터로 `ACCOUNT_TYPE_LOCAL` 캘린더, 날짜마다 고정 키로 바뀐 것만 다시 씀), `calendar/PhoneCalendarSync.kt`(설정 `phoneCalendars`를 따라 몇 초 뒤, 3개월 전부터 1년 뒤까지). `WRITE_CALENDAR`는 켤 때만 물어요. 캘린더 가져오기는 이 캘린더를 목록에서 빼요.
- **공유 링크**: `share/ShareLink.kt`의 `ShareLinkKey`(1번은 달력 ID, 2~5번은 `id~N`, 1번의 저장 파일 이름은 예전 그대로), 기간 7·30·90·180·365(180·365는 프로), 링크마다 `feed`(구독 주소). `ShareSync`가 모든 링크를 따라 보내고, 프로가 끝나면 2~5번은 기간까지 두되 늘리기는 `Result.Locked`(결정 D23). 공유 서버는 `GET /s/:id.ics`와 `ics`·`tz`를 받아요(서버 README '앱 1.20부터').
- **매일 자동 백업**: `backup/AutoBackup.kt`(하루 한 번, 'rwt'로 쓰고 쓴 길이로 잘라 낸 뒤 다시 읽어 풀어 봄, 3일 실패 알림), `backup/AutoBackupJob.kt`(JobScheduler 6시간, 배터리 낮지 않을 때), `backup/KeyBox.kt`(암호에서 만든 열쇠를 키 저장소 AES-GCM으로 감쌈, 암호는 저장 안 함). 파일은 손으로 한 암호 백업처럼 같은 암호로 어느 휴대폰에서나 열려요. 열쇠와 파일 위치(`auto_backup` 설정 파일)는 백업에서 빠져요.
- **확실한 알람**: core `alarm/AlarmMission.kt`(계산 3문제, 흔들기 20번, `ShakeCounter`, 5분 뒤 미션 없이 끄기), 앱 `alarm/MissionScreen.kt`, `AlarmActivity`(미션 화면), `AlarmService`(미션이 있으면 알림의 '끄기'가 미션 화면을 엶, 끄면 `WakeCheck.schedule`), `alarm/WakeCheck.kt`(끈 뒤 N분에 '일어났나요?', 90초 뒤 한 번 더 울림, 알람 시계가 아닌 정확한 알람). 설정은 `ui/settings/SureAlarmCard.kt`, 잠금 풀기 전 사본은 `BootAlarms`가 함께 둬요. 프로가 아니면 `RingSettings.of(s, sure = false)`라 예전처럼 울려요.
- **법 문서**: `privacy/index.html` 처리방침 2.6(게시 2026-10-24·시행 2026-11-22 예정, 7일 고지, 2.5보다 먼저 시행 못 함). `docs/play-data-safety.md` 1.20.0장.
- **시험**: core `ReportTest`·`AlarmMissionTest`·`GuideTest.whatsNewOpensRealFeatures`, 앱 `report/ReportPdfTest`, `calendar/PhoneCalendarTest`, `share/ShareLinksProTest`, `backup/AutoBackupTest`, `alarm/SureAlarmTest`, `tier/TierAppTest`, 화면 사진 385~398, 공유 서버 `worker.test.mjs`.

## 0-1. 바로 앞 판: 1.19.0 수익화 기반 (2026-10-22)

일하는 규칙은 `CLAUDE.md`(문서 먼저, 판마다 체크리스트, Phase 3 진입 금지). 남은 일의 정본과 판 계획은 비공개 프로젝트 파일 `계획/Phase2-완료-체크리스트.md`에 있어요. 다음 판은 1.20.0 프로 가치(자동 암호 백업, 공유 링크 여러 개, 통계 확장, 급여 리포트 PDF·CSV, 등급 최종 시험, ICS 구독, 기기 캘린더 쓰기, 확실한 알람, 근무표 PDF)예요.

- **꺼진 채로 나가요.** 회사 서버 `GET /api/config`의 `billingLive`가 켜지기 전(관리자 앱 › 설정 › '정식 출시', 최고 관리자)에는 모든 기능이 무료이고, Play 결제에 연결하지 않고, 광고 SDK도 시작하지 않아요(매니페스트 `DELAY_APP_MEASUREMENT_INIT`). 미리 보기는 알람 점검 화면의 등급 선택(출시 뒤에는 디버그 빌드만).
- **등급 판정 한 곳**: `tier/FeatureGate.kt`(`Feature.minTier`, 무료/프로, 리더는 Phase 3) → `Gate.allows(f) = !live || f in free || tier >= f.minTier`. 프로 이유는 core `tier/ProAccess.kt`가 순서대로 정해요: Play 구독(체험·유예 포함) → 관리자 휴대폰(7일 안 확인) → 보상 이용권 → 출시 감사 보상 → 광고 보고 하루 이용.
- **원격 설정**: `tier/RemoteConfig.kt`(하루 한 번, 엄격한 해석, 인터넷 없으면 마지막 값, 처음엔 꺼짐). 서버가 프로 기능을 무료로 열 수는 있어도 거꾸로는 못 해요.
- **결제**: `billing/Billing.kt`(Play Billing 9.1.0, `bamnat_pro`의 `monthly`·`yearly`, 혜택 `trial-1m`, 승인·복원, 결제 없는 기기는 `NoBilling`). 서버 `POST /api/purchases`가 `subscriptionsv2.get`으로 확인해요. 서비스 계정 키(Cloudflare 비밀값 `PLAY_SERVICE_ACCOUNT`)가 없으면 `verified:false`로 답하고 앱은 휴대폰의 Play 상태를 써요. 실시간 개발자 알림(RTDN)은 쓰지 않아요.
- **무료 체험 전환 동의(전자상거래법 제13조 제6항)**: core `tier/TrialConsent.kt`(끝나기 7일 전부터 묻기, 마지막 날), 앱 `ui/pro/TrialConsentPrompt.kt`와 알림 `tier/TrialNotice.kt`(재부팅·시간 바꿈 뒤 다시 걸기). 답은 휴대폰과 서버 `POST /api/consents`에 남고 서버 크론이 5년 뒤 지워요. 동의가 없으면 서버 크론이 끝나기 24시간 안에 다음 결제를 멈춰요(키가 있을 때).
- **잠금과 프로 화면**: `ui/pro/ProLock.kt`(자기 숫자로 그린 뒤 12+ 흐림, 아래는 덮개), `ui/pro/ProScreen.kt`(비교표, 가격·갱신·해지·7일 청약철회·약관·처리방침·구매 복원, 보상 이용권 복구 코드, 관리자 연결). 설정 첫 화면에 '프로' 줄.
- **광고**: `ads/Ads.kt`(UMP 동의 뒤 SDK 시작, 설정 › 개인정보와 권한 › 광고 개인정보 선택), `ui/components/AdSlot.kt`(띠, AppRoot 한 곳에서만), `ads/RewardedAds.kt`(고를 때만, 하루 한 번). 규칙은 core `tier/AdRules.kt`(설치 3일 광고 없음)와 `AdAndLicenseRulesTest`. 광고 단위 ID는 `gradle.properties`의 `bamnat.admobAppId`·`bamnat.admobBanner`·`bamnat.admobRewarded`(공개값, 비면 출시 빌드는 광고 없음, 디버그는 Google 시험 ID). `play-services-ads`는 25.2.0에 묶었어요: 25.3.0부터 Kotlin 2.3 메타데이터라 지금 Kotlin 2.1.21로 빌드가 안 돼요.
- **보상 이용권·관리자 연결**: `tier/ProStore.kt`(이용권·복구 코드·관리자 상태, 백업에는 복구 코드와 감사 안내 본 기록만), `tier/TierApi.kt`. 서버 `GET /api/passes`, `POST /api/passes/code`, `POST /api/passes/claim`, 관리자 앱 › 내 정보 › '내 휴대폰 밤낮 연결'(6자리 코드). 결제·관리자 상태는 Android 자동 백업에서 빼요.
- **법 문서**: 저장소 맨 위 `privacy/index.html`(처리방침 2.5, 게시 2026-10-23·시행 2026-11-22 예정, 방침 9항의 불리한 변경 30일 고지), `terms/index.html`(이용약관 1.0). 시험 `LegalPagesTest`가 판·필수 문구를 봐요. Play 데이터 보안 답안은 `docs/play-data-safety.md` 1.19.0장.
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
