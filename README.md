# Front Kiosk

안드로이드 태블릿을 벽걸이 대시보드로 쓰기 위한 키오스크 앱.
시계·날씨·가족 일정·알림 카드를 한 화면에 띄우고, 다른 앱의 푸시(배달 앱 등)를 읽어 카드로 바꿔 보여준다.

- **web/index.html** — 대시보드 UI 전체. 브라우저에서 바로 열어 데모 패널로 확인할 수 있다.
- **web/apps/** — 다른 앱 알림 어댑터 (앱별 문구 해석은 여기에만 둔다)
- **gas/** — 가족 캘린더 읽기·쓰기용 Google Apps Script 웹 앱
- **android/** — UI를 감싸는 얇은 Android 앱 (Kotlin, minSdk 30)
  - WebView로 `web/` 을 띄움 (앱 assets 로 포함)
  - 디바이스 오너 + 잠금 작업 모드: 알림창·빠른 설정·홈/최근 앱 제스처·전원 메뉴·다른 앱 알림 팝업 차단
  - 알림 수신 서비스: 지정한 앱의 알림을 그대로 대시보드에 넘기고 원본은 지움 (해석은 web/apps)
  - LAN HTTP API, TTS(안드로이드 음성 엔진)
  - 관리자 탈출구: 왼쪽 위 모서리 5번 탭 → PIN

## 화면 구성

- 위: 시계, 날짜, 오늘 다음 일정 한 줄, 현재 날씨와 시간별 예보 (Open-Meteo)
  - 번인 방지: 블록이 중심에서 ±20px 안에서 천천히 이동, 1분마다 화면 전체 ±2px 이동, 시간대별 배경, 야간 감광
  - 공간이 모자라면 시간별 예보 → 날씨 설명 → 일정 줄 순서로 숨기고, 그래도 모자라면 축소 (알림 영역은 침범하지 않음)
  - 비/눈이 오면 배경에 은은한 효과
- 아래: 알림. 우선순위 1개는 큰 카드, 나머지 3개는 한 줄 카드, 그 이상은 "N건 더 보기"
- 30초간 조작이 없으면 열린 화면을 닫고 초기 화면으로

## 빌드

GitHub Actions(`.github/workflows/android.yml`)가 `main` 푸시마다 APK를 만든다.
기기에 업데이트 설치를 하려면 항상 같은 키로 서명해야 하므로 저장소 Secret 두 개를 쓴다.

| Secret | 내용 |
|---|---|
| `KIOSK_KEYSTORE_B64` | `keytool -genkeypair -keystore kiosk.keystore -alias kiosk -keyalg RSA -keysize 2048 -validity 10000` 로 만든 키를 base64 인코딩 |
| `KIOSK_KEYSTORE_PASS` | 키 비밀번호 (키스토어/키 동일) |

```sh
gh run download --name kiosk-apk --dir out
```

## 기기 설치 (처음 한 번)

디바이스 오너는 **기기에 계정(Google 등)이 하나도 없을 때만** 지정할 수 있다. 계정 로그인은 그 뒤에 한다.

```sh
adb connect <device-ip>:5555
adb install -t -r out/app-debug.apk                       # testOnly 빌드라 -t 필요
adb shell dpm set-device-owner kiosk.front/.AdminReceiver
adb shell cmd notification allow_listener kiosk.front/kiosk.front.NotifListener
adb shell am start -n kiosk.front/.MainActivity \
  --es pin <관리자PIN> --es place <동네이름> --es lat <위도> --es lon <경도>
adb logcat -d -s Kiosk | grep token                       # API 토큰 확인
```

업데이트: `adb install -t -r new.apk`
해제: 관리자 메뉴 → 디바이스 오너 해제, 또는 `adb shell dpm remove-active-admin kiosk.front/.AdminReceiver` (testOnly 빌드)

## 다른 앱 알림

1. 받을 앱 지정: `PUT /api/config {"watch": "패키지1,패키지2"}`. 알림 읽기 권한은
   `adb shell cmd notification allow_listener kiosk.front/kiosk.front.NotifListener`
2. 지정한 앱의 알림은 어댑터가 없어도 기본 카드(제목·본문)로 뜬다. 같은 알림이 갱신되면 같은 카드가 바뀌고,
   앱이 알림을 거두면 카드도 닫힌다.
3. 앱별로 다듬으려면 `web/apps/<앱>.js` 에 어댑터를 두고 `index.html` 에 `<script>` 한 줄을 더한다.
   형식은 `web/apps/registry.js` 주석 참고. 광고 무시(`null`), 여러 알림을 한 카드로 묶기(`id`),
   진행 단계·음성 등. 배달 앱 공통 단계 찾기는 `web/apps/delivery.js`.

## HTTP API

`http://<device-ip>:8080`, `Authorization: Bearer <token>` (health 제외)

| 요청 | 설명 |
|---|---|
| `POST /api/notify` | 알림 추가/갱신 (같은 `id` 면 갱신) → `{"id"}` |
| `DELETE /api/notify/{id}` · `DELETE /api/notify` | 알림 하나/전부 지우기 |
| `PUT /api/agenda` | 일정 목록 전체 교체 |
| `POST /api/speak` | `{"text"}` 음성만. `voice` `rate` `pitch` 를 같이 주면 이번 한 번만 그 음성으로 |
| `GET /api/tts` | 한국어 음성 목록(`network: true` 는 클라우드 합성)과 현재 설정 |
| `PUT /api/tts` | `{"voice", "rate", "pitch"}` 기본 음성 저장. `voice: ""` = 엔진 기본, 속도·높낮이 0.5~2.0 |
| `GET /api/state` | 현재 알림·일정 |
| `GET /api/android-notifications?package=&limit=` | 받은 알림 원본, 최근 것부터 (기기 파일에 500개 보관, 어댑터 작성용) |
| `PUT /api/config` | `place` `lat` `lon` `pin` `watch` `consume` `url` |
| `POST /api/reload` | 화면 새로고침 |
| `PUT /api/calendars` · `GET` · `POST …/sync` | iCal 구독 캘린더 |
| `PUT /api/gas` · `GET` · `POST …/sync` | Apps Script 웹 앱 (일정 읽기·쓰기) |
| `POST /api/admin/unlock` · `lock` | 키오스크 잠금 잠시 해제 / 다시 잠금 |
| `POST /api/admin/open` | `{"uri"}` 또는 `{"package"}` 잠금 해제 후 열기 (Play 스토어 페이지 등) |
| `GET /api/health` | 상태, TTS 엔진·한국어 음성 준비 여부 (인증 없음) |

### 알림 형식

```jsonc
{
  "id": "fridge-door",                 // 같은 id 로 다시 보내면 카드 갱신
  "source": "fridge",                  // 아이콘·이름 (delivery, fridge, door, trash, system, calendar)
  "level": "warn",                     // info | warn | urgent | ok
  "title": "냉장고 문이 열려 있어요",
  "body": "주방 냉장고 · 3분째 열림",
  "html": "<b>...</b>",                // 텍스트 대신 HTML 카드 (스크립트·이벤트 속성 제거)
  "progress": { "step": 2, "steps": ["접수", "조리", "출발", "완료"] },
  "detail": { "text": "...", "fields": [["기기", "주방 냉장고"]] },
  "tap": { "type": "detail" },         // 카드를 눌렀을 때
  "actions": [                         // 세부 화면 버튼
    { "label": "확인했어요", "style": "primary", "action": { "type": "dismiss" } },
    { "label": "5분 뒤 다시 알림", "action": { "type": "snooze", "minutes": 5 } }
  ],
  "sound": "chime",                    // chime | alert | none | 오디오 URL
  "speak": "냉장고 문이 열려 있습니다", // true 면 제목+본문을 읽음 (조용한 시간엔 긴급만)
  "remind": { "everySec": 60, "max": 5 },  // 확인할 때까지 반복
  "ttlSec": 3600
}
```

동작(`action.type`): `detail` `dismiss` `speak` `snooze` `url`(iframe) `http`(webhook 호출) `native`

### 가족 캘린더 (Google 캘린더 등 iCal 구독)

Google 캘린더 → 캘린더 설정 → "비공개 주소(iCal 형식)"를 기기에 등록한다. 이 주소는 비밀이라 기기에만 저장되고 API 응답·로그에는 호스트 이름만 나온다.

```sh
curl -X PUT http://<device-ip>:8080/api/calendars -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '[{"name":"가족","url":"<비공개 iCal 주소>","color":"#8fb0f5"},
       {"name":"음성 알림","url":"<다른 캘린더 주소>","color":"#e9b072","remind":[5]}]'
```

15분마다 다시 받고, 오늘·내일 일정을 펼쳐 보여준다 (반복·예외·수정된 회차·종일 일정 지원).
`remind` 를 준 캘린더의 일정은 모두 그 시간 전에 음성으로 알린다.

일정 하나만 음성 알림을 받고 싶으면 **제목이나 설명(메모)에 태그**를 적는다. 태그는 화면에서는 지워진다.

| 태그 | 의미 |
|---|---|
| `#알림` | 5분 전에 카드 + 음성 |
| `#알림 30,5` | 30분 전, 5분 전 두 번 |
| `#멘트 가방 챙기세요` | 알림 음성 뒤에 이 문장을 덧붙임 |

종료 시간이 없거나 15분 이하인 일정(등원·하원 등)은 "그 시각" 일정으로 취급해 시작 10분 뒤 다음 일정으로 넘어간다.

종일 일정은 날짜 아래 줄에 따로 보인다 (공휴일은 날짜가 빨갛게, 여러 날 일정은 "2/3일"). 종일 일정에 `#알림` 을 달면 그날 아침 8~12시 사이에 한 번 알린다.

### 일정 추가·빠른 반영 (Google Apps Script)

iCal 비공개 주소는 읽기 전용이고 Google 쪽 반영이 몇 분~몇 시간 늦다. 캘린더 주인 계정에 작은 웹 앱(`gas/Code.gs`)을 배포하면
키오스크가 2분마다 일정을 바로 받아오고, 화면에서 일정을 추가할 수 있다 (일정 줄을 눌러 여는 목록 → ＋ 일정 추가. 일정이 하나도 없을 땐 요일 옆 ＋).

1. script.google.com → 새 프로젝트 → `gas/Code.gs` 내용을 붙여넣고 저장
2. 프로젝트 설정 → 시간대 `(GMT+09:00) 서울`
3. 함수 `setup` 실행 → 권한 허용 → 실행 로그의 `TOKEN` 복사 (기본으로 "가족" 캘린더를 읽고 씀)
   - 이름이 같은 캘린더가 여럿이면(Google 가족 그룹의 "가족" 등) `diag` 를 실행해 원하는 캘린더 ID 를 찾고,
     프로젝트 설정 → 스크립트 속성 `CALENDARS` 에 그 ID 를 넣는다
4. 배포 → 새 배포 → 웹 앱, 실행 사용자 "나", 액세스 "모든 사용자" → 웹 앱 URL 복사
5. 키오스크에 등록 (URL·토큰은 기기에만 저장됨):

```sh
curl -X PUT http://<device-ip>:8080/api/gas -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"url":"<웹 앱 URL>","token":"<setup 이 만든 TOKEN>"}'
curl http://<device-ip>:8080/api/gas -H "Authorization: Bearer $TOKEN"   # status.ok 확인
```

iCal 과 웹 앱 양쪽에 같은 일정이 있으면 하나만 보인다. 화면에서 고른 음성 알림·멘트는 일정 설명에 `#알림` `#멘트` 태그로 저장되어 폰에서도 그대로 보인다.

### 일정 형식 (API로 직접 넣을 때)

```json
[{ "id": "1", "title": "치과", "start": "2026-11-20T14:00:00+09:00", "end": "2026-11-20T15:00:00+09:00",
   "location": "동네 치과", "calendar": "가족", "color": "#8fb0f5", "remindBeforeMin": 30 },
 { "title": "공휴일", "start": "2026-11-21", "allDay": true }]
```

```sh
curl -X POST http://<device-ip>:8080/api/notify -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"title":"세탁 완료","speak":true}'
```
