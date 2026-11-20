# Front Kiosk

안드로이드 태블릿을 벽걸이 대시보드로 쓰기 위한 키오스크 앱.
시계·날씨·가족 일정·알림 카드를 한 화면에 띄우고, 다른 앱의 푸시(배달 앱 등)를 읽어 카드로 바꿔 보여준다.

- **web/index.html** — 대시보드 UI 전체 (HTML/CSS/JS 한 파일). 브라우저에서 바로 열어 데모 패널로 확인할 수 있다.
- **android/** — UI를 감싸는 얇은 Android 앱 (Kotlin, minSdk 30)
  - WebView로 `web/` 을 띄움 (앱 assets 로 포함)
  - 디바이스 오너 + 잠금 작업 모드: 알림창·빠른 설정·홈/최근 앱 제스처·전원 메뉴·다른 앱 알림 팝업 차단
  - 알림 수신 서비스: 지정한 앱의 알림을 읽어 대시보드 카드로 변환하고 원본은 지움
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

## HTTP API

`http://<device-ip>:8080`, `Authorization: Bearer <token>` (health 제외)

| 요청 | 설명 |
|---|---|
| `POST /api/notify` | 알림 추가/갱신 (같은 `id` 면 갱신) → `{"id"}` |
| `DELETE /api/notify/{id}` · `DELETE /api/notify` | 알림 하나/전부 지우기 |
| `PUT /api/agenda` | 일정 목록 전체 교체 |
| `POST /api/speak` | `{"text"}` 음성만 |
| `GET /api/state` | 현재 알림·일정 |
| `GET /api/android-notifications` | 최근 받은 다른 앱 알림 원본 (파서 작성용) |
| `PUT /api/config` | `place` `lat` `lon` `pin` `watch` `consume` `url` |
| `POST /api/reload` | 화면 새로고침 |
| `GET /api/health` | 상태 (인증 없음) |

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

### 일정 형식

```json
[{ "id": "1", "title": "치과", "start": "2026-11-20T14:00:00+09:00", "end": "2026-11-20T15:00:00+09:00",
   "location": "동네 치과", "calendar": "가족", "color": "#8fb0f5", "remindBeforeMin": 30 },
 { "title": "공휴일", "start": "2026-11-21", "allDay": true }]
```

```sh
curl -X POST http://<device-ip>:8080/api/notify -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"title":"세탁 완료","speak":true}'
```
