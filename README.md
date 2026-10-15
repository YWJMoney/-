# 자동 장부

카드·은행 문자를 붙여넣으면 장부가 되고, 구독·고정비·공과금·카드 결제일을 **"곧 나갈 돈"** 한곳에서 미리 알려주는 무광고 가계부입니다. 서버가 없고 모든 데이터는 기기 안에만 저장됩니다.

**Android 전용 앱**입니다(iOS는 보류). `android/` 의 네이티브 앱이 같은 화면 코드를 앱 안에 넣어 오프라인으로 실행하고, 결제 예정 알림·파일 저장·공유하기 받기를 Android 기능으로 처리합니다.

- 개발계획서: [자동 장부 앱 개발계획서](https://claude.ai/code/artifact/d3766d1b-f012-4ec6-b2cd-31995eb99066)
- 버전별 범위: [docs/ROADMAP.md](docs/ROADMAP.md)
- 개인정보처리방침: [공개 페이지](https://claude.ai/artifact/CcL5RymbMBWLEajJm2qBZQ) (원본 [docs/PRIVACY.md](docs/PRIVACY.md))
- 빌드와 Play 스토어 게시: [docs/PUBLISHING.md](docs/PUBLISHING.md)

## 들어 있는 것 (v0.2)

| 화면 | 기능 |
| --- | --- |
| 홈 | 다음 급여일까지 하루에 쓸 수 있는 돈, 다음 큰 지출 D-day, 다음 7일 나갈 돈, 확인 필요 항목 |
| 자동 기록 | 카드·은행·페이 앱과 문자 알림에서 결제·입출금만 골라 바로 기록(알림 접근), 문자+앱 알림 중복 제거, 못 읽은 알림 모아 고치기·숫자 가린 형식 제보, 수집 끊김 경고 |
| 입력 | 직접 입력, 문자 여러 건 붙여넣기(미리보기·수정 후 저장), Android 공유하기로 바로 받기 |
| 곧 나갈 돈 | 고정 지출·구독(매월·매주·매년), 카드 결제 예상액(이용기간·할부·취소 반영), 휴일이면 다음 영업일, 실제 결제와 자동 매칭, 달력(.ics) 내보내기 |
| 내역 | 월별 지출·수입, 분류별 합계, 날짜별 목록, 분류를 고치면 같은 가맹점에 계속 적용 |
| 설정 | 급여일·생활비, 테마·큰 글자, 앱 알림, 자동 기록 상태, CSV·비밀번호 암호화 백업·복원·전체 삭제 |

## 구조

```
index.html            앱 화면 껍데기
css/app.css           스타일(밝게/어둡게/큰 글자)
src/app.js            화면과 이벤트
src/parser.js         카드·은행 문자 파서(기기 안에서만 동작)
src/engine.js         곧 나갈 돈 엔진: 카드 주기, 고정 지출, 매칭, 급여 주기 요약
src/store.js          이벤트 로그 저장소(localStorage, 스키마 버전·마이그레이션)
src/dates.js          날짜·영업일 계산
src/holidays.js       2026~2027 공휴일표
src/io.js             CSV·JSON 백업(AES-GCM 암호화 선택)·.ics 내보내기
src/capture.js        알림 문구 → 장부 기록(중복 제거·취소 연결·못 읽은 목록)
sw.js                 오프라인 실행(서비스 워커)
manifest.webmanifest  설치·공유하기 수신 설정
privacy.html          개인정보처리방침 초안
tests/                Node 내장 테스트
legacy/calculator/    이전에 있던 계산기 페이지
android/              Android 앱(Gradle 프로젝트 + build-apk.sh)
```

웹 코드에는 빌드 단계가 없습니다. Android 앱은 빌드할 때 이 파일들을 앱 안(assets/www)에 복사해 넣고, `window.JangbuAndroid` 브리지로 알림 예약·파일 저장·클립보드를 네이티브로 처리합니다.

## 실행과 테스트

```bash
npm test     # 단위 테스트 (Node 20+)
npm start    # http://localhost:8080 에서 화면만 확인
```

APK 빌드는 [docs/PUBLISHING.md](docs/PUBLISHING.md)를 보세요.

서비스 워커와 설치 기능은 `http://localhost` 또는 HTTPS에서만 동작합니다. 파일을 직접 열면(file://) 일부 기능이 꺼집니다.
