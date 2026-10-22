# 밤낮 저장소 규칙 (문서 기반 개발)

이 저장소는 공개예요. 비밀값, 서명 키, 사용자 개인 정보는 이 파일과 저장소 어디에도 적지 않아요.

## 시작하기 전에

1. `docs/HANDOFF.md`(코드 구조, 빌드·시험·서명)를 읽어요.
2. 프로젝트 파일이 있으면 `계획/문서-운영-규칙.md`와 `계획/Phase2-완료-체크리스트.md`(남은 일 전체의 정본)를 읽어요.
3. 지금 판의 계획 문서(`계획/<버전>-…개발계획.md`)가 없으면 먼저 만들어요. 적지 않은 일은 하지 않고, 하게 되면 먼저 적어요.

## 개발 규칙

- **문서 먼저, 체크리스트로 끝내기.** 판의 모든 줄이 끝(`[x]`)이거나 이유를 적은 옮김(`[→ 순번]`)이어야 판을 끝내요.
- **Phase 3 진입 금지.** Phase 0~2가 모두 끝나거나 사용자가 승인한 이월일 때까지 건강·가족(M23), 리더 팀 근무표(M24), 사장님 급여(M25)는 시작하지 않아요.
- 요구사항 ID를 코드 주석과 커밋 메시지에 `[MON-01]`처럼 적어요. 새 ID는 먼저 `docs/tracker.csv`에 넣어요(`python3 tools/check_tracker.py`가 없는 ID를 오류로 봐요).
- 순번은 바꾸지 않아요. 끝나면 (완료, 버전)을 붙여요.
- 있었던 일만 실제 날짜로 적어요. 법·세금·Play 정책 같은 바깥 사실은 원문을 찾아 출처를 문서와 주석에 적고, 못 찾으면 "확인 필요"로 남겨요.
- 문서는 바뀐 부분만 고치거나 더해요.

## 판을 끝낼 때 (문서 갱신 점검표)

- 판 계획 문서의 모든 줄과 시험 결과
- 남은 일 체크리스트와 개발계획(지금 단계, 완료한 일, 사용자 할 일)
- `docs/tracker.csv` 새 ID·상태, 검사 통과
- `core/.../guide/WhatsNew.kt` 이번 판 줄, 기능 목록·설정 찾기·도움말(시험이 봄)
- 새 설정은 백업에 넣고 왕복 시험
- 서버로 가는 정보·권한·SDK가 바뀌면 처리방침 개정안, `docs/play-data-safety.md`
- `docs/HANDOFF.md` 맨 위 판과 다음 할 일
- `release/<버전>/사용자가-해야-할-일.md`와 서명한 파일(키를 가진 쪽에서)

## 시험

모두 `shift-calendar-app/`에서 실행해요.

```bash
bash ./gradlew -p core test
bash ./gradlew :app:testDebugUnitTest :app:lintDebug --continue
python3 tools/check_tracker.py
(cd server/company-worker && npm test)
(cd server/share-worker && npm test)
```

## 하지 않는 것

- 서명 키(`*.jks`), `keystore.properties`, 서버 비밀값(Cloudflare 토큰, `RATE_SALT`, `ADMIN_PASSWORD`, `MAIL_API_KEY`, `PLAY_SERVICE_ACCOUNT`)을 커밋하거나 출력하지 않아요.
- 서버 배포, Play Console, main 합치기는 사용자가 해요. PR은 사용자가 그 PR을 콕 집어 합쳐 달라고 할 때만 합쳐요.
- 광고는 정해진 띠 한 곳과 사용자가 고르는 보상형만 써요(`AdAndLicenseRulesTest`). 알람·잠금·첫 설정 화면에는 광고가 없어요.
