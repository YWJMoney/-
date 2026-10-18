# 밤낮 근무표 공유 서버 (bamnat-share)

‘링크로 보내기’로 만든 근무표를 브라우저와 밤낮 앱에 보여 주는 서버예요 [MKT-09][SHR-01][SHR-02].
Cloudflare Worker 하나와 KV 저장소 하나로 돌아가고, 무료 요금제로 충분해요.

## 앱 1.10을 내기 전에: 코드 다시 올리기

앱 1.10의 ‘받은 근무표’는 이 서버의 새 주소(`GET /api/share/:id`)로 근무표를 읽어요.
**스토어에 1.10을 올리기 전에** 서버를 먼저 새 코드로 바꿔 주세요. 예전 서버에서는 받은 링크를 넣으면
"근무표를 받지 못했어요"가 나와요. 새 서버는 예전 앱이 만든 링크도 그대로 받아요.

1. Cloudflare 대시보드 → 컴퓨팅(Workers) → Workers 및 Pages → `bamnat-share`를 열어요.
2. 코드 편집(Edit code)을 눌러 `worker.js` 내용을 모두 지우고, 이 폴더의 `src/index.js` 전체를 붙여 넣은 뒤 배포(Deploy)를 눌러요.
3. KV 바인딩(`SHARES`)과 비밀값(`RATE_SALT`)은 그대로 남아요. 다시 넣지 않아도 돼요.
4. 확인: `https://bamnat-share.<계정 이름>.workers.dev/.well-known/assetlinks.json`을 열어 `[]`가 보이면 새 코드예요.

## 선택: 링크를 누르면 바로 앱으로 열기 (ANDROID_CERT_SHA256)

넣지 않아도 공유 페이지의 "밤낮 앱에서 보기" 버튼으로 앱이 열려요. 넣으면 메신저에서 링크를 누를 때 브라우저를 거치지 않고 바로 앱이 열려요.

1. Play Console → 앱 → 테스트 및 출시(Test and release) → 앱 무결성(App integrity) → 앱 서명(App signing)에서
   **앱 서명 키 인증서**의 SHA-256 인증서 지문을 복사해요. `AB:CD:…` 모양의 64자리예요.
2. Worker → 설정(Settings) → 변수 및 비밀(Variables and Secrets) → 추가(Add). 유형은 **텍스트(Text)**,
   이름은 `ANDROID_CERT_SHA256`, 값은 복사한 지문이에요. 직접 빌드한 앱도 열리게 하려면 업로드 키 지문을 쉼표로 이어 붙여요.
3. 배포(Deploy) 후 `/.well-known/assetlinks.json`에 지문이 보이면 돼요. 휴대폰이 확인하는 데 하루쯤 걸릴 수 있어요.

이 지문은 공개 정보라 비밀은 아니에요. 그래도 Claude에게 따로 보낼 필요는 없어요.
`RATE_SALT`는 비밀값이니 채팅이나 메신저로 보내지 마세요.

## 앱이 쓰는 API

| 요청 | 하는 일 |
| --- | --- |
| `POST /api/share` | 링크 만들기. 답: `{id, url, deleteToken, expiresAt}` |
| `PUT /api/share/:id` | 지금 근무표로 고치기 (헤더 `X-Delete-Token`) |
| `DELETE /api/share/:id` | 링크 끊기 (헤더 `X-Delete-Token`) |
| `GET /api/share/:id` | 받은 근무표 읽기. 답: `{schedule, expiresAt, updatedAt}`. 끊겼거나 기간이 지나면 404 `not_found` |
| `GET /s/:id` | 브라우저로 보는 근무표 페이지 (검색엔진 차단, 스크립트 없음) |
| `GET /.well-known/assetlinks.json` | 링크를 앱으로 여는 Android 확인 파일 |

근무표 형식은 `v: 1`(근무와 휴가만)과 `v: 2`(보낸 분이 고른 것을 `shows`에 함께)예요.
`shows`에는 `leaveKinds`, `extras`, `memos`, `pay`가 들어갈 수 있고, 서버는 고르지 않은 항목이 오면 버려요.
휴가 종류(`leaveKinds`)를 고르지 않으면 휴가는 연차가 아닌 한 '휴가'·'반차'처럼 쉬는 만큼만 남겨요.
날마다 `rest`(링크에 담긴 것으로 본 쉬는 날인지)가 함께 와서 앱이 같이 쉬는 날을 보여 줘요.
근무가 없는 날도 대근이 있으면 `second`만으로 들어와요.

## 개인정보

- 근무 이름·색·시간과 날짜, 쉬는 날인지, 보낸 분이 고른 것(휴가 종류, 연장·휴무일 근무, 메모 첫 줄 80자, 달마다 세전 급여 하나)만 저장해요.
- 링크는 고른 기간(7·30·90일)이 지나거나 ‘링크 끊기’를 누르면 지워져요. 받은 사람 앱의 근무표도 그때 지워져요.
- IP 주소는 저장하지 않아요. 링크 만들기 제한에는 `RATE_SALT`를 섞은 해시만 쓰고 한 시간 안에 지워요.
- 받은 근무표를 읽을 때는 아무것도 저장하지 않아요.

## 로컬에서 해 보기

Node 22 이상이 있으면 Cloudflare 없이 돌려 볼 수 있어요.

```bash
npm test                                  # 서버 테스트
node test/local-server.mjs 8787 &         # 메모리 KV로 띄우기
SHARE_TEST_URL=http://127.0.0.1:8787 ../../gradlew -p ../.. :app:testDebugUnitTest --tests '*ShareLinkTest*'
```

앱의 `ShareLinkTest`를 먼저 돌리면 앱이 만든 실제 근무표(`app/build/share-payload*.json`)를 `npm test`가 함께 검사해요.
