# 게시 방법

## 1. 웹앱으로 바로 공개 (GitHub Pages)

1. 저장소 Settings → Pages → Source를 "Deploy from a branch", Branch를 `main` / `(root)`로 저장합니다.
2. 몇 분 뒤 `https://ywjmoney.github.io/-/` 에서 열립니다.
3. 폰 Chrome·Safari에서 열고 "홈 화면에 추가"를 누르면 앱처럼 설치됩니다. Android에서 설치한 뒤에는 문자 앱의 공유하기 목록에 "자동 장부"가 나타납니다.

## 2. Play 스토어에 올리기 (TWA)

v0.1은 웹 코드 그대로 Trusted Web Activity로 감싸 올립니다. v0.2부터는 알림 읽기 기능 때문에 Capacitor 네이티브 앱으로 바꿉니다.

1. **도메인 준비**: TWA는 도메인 루트의 `/.well-known/assetlinks.json`을 확인합니다. `ywjmoney.github.io/-/` 처럼 하위 경로에서는 루트에 파일을 둘 수 없으므로 아래 중 하나를 고릅니다.
   - `YWJMoney/ywjmoney.github.io` 저장소를 만들어 `.well-known/assetlinks.json`을 둔다.
   - 커스텀 도메인을 연결하고 이 저장소 루트에 `.well-known/assetlinks.json`을 둔다(이 경우 `.nojekyll` 파일도 추가).
2. **Play 개발자 계정**: Play Console에서 계정을 만들고(등록비 25달러) 본인 인증을 마칩니다.
3. **패키지 만들기**: [PWABuilder](https://www.pwabuilder.com)에 사이트 주소를 넣고 Android → Google Play 패키지를 받거나, Bubblewrap CLI를 씁니다.
   ```bash
   npm i -g @bubblewrap/cli
   bubblewrap init --manifest https://<도메인>/manifest.webmanifest
   bubblewrap build
   ```
   만든 서명 키(.keystore)와 비밀번호는 잃어버리면 업데이트를 못 올리니 안전한 곳에 백업합니다.
4. **assetlinks.json**: 패키지 이름과 서명 키 SHA-256 지문을 넣은 파일을 1단계 위치에 올립니다(PWABuilder가 내용을 만들어 줍니다).
5. **스토어 등록 정보**: 앱 이름, 설명, 스크린샷, 아이콘(`icons/icon-512.png`), 개인정보처리방침 URL(`privacy.html`)을 넣습니다.
6. **데이터 보안 양식**: v0.1은 "데이터를 수집·공유하지 않음"으로 답합니다(모두 기기 안 저장, 서버 전송 없음). 금융 기능 관련 선언 항목이 나오면 "개인 가계부, 금융 거래 실행 없음"으로 답합니다.
7. **비공개 테스트**: 신규 개인 개발자 계정은 프로덕션 출시 전에 비공개 테스트(테스터 12명 이상, 14일 연속)를 요구합니다. 최신 요건은 Play Console 안내를 다시 확인하세요.
8. 14일이 지나면 프로덕션 액세스를 신청하고 출시합니다.

## 3. 버전 올릴 때

- `src/app.js`의 `APP_VERSION`, `sw.js`의 `VERSION`, `package.json`의 `version`을 함께 올립니다. 서비스 워커 버전을 올려야 사용자 폰의 캐시가 새 파일로 바뀝니다.
- 저장 형식이 바뀌면 `src/store.js`의 `SCHEMA_VERSION`을 올리고 `MIGRATIONS`에 변환 함수를 추가합니다.
