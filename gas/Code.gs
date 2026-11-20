/**
 * Front Kiosk ↔ Google Calendar bridge (Google Apps Script 웹 앱)
 *
 * 키오스크가 이 웹 앱으로
 *   GET  ?token=…&days=2           오늘부터 days 일 동안의 일정 (반복 일정은 Google 이 펼쳐서 줌)
 *   POST {"token","action":"add",…} 첫 번째 캘린더에 일정 추가
 * 를 호출한다. iCal 비공개 주소보다 반영이 빠르고(즉시), 쓰기도 된다.
 *
 * 설정 (캘린더 주인 계정으로):
 *   1. script.google.com → 새 프로젝트 → 이 파일 내용을 Code.gs 에 붙여넣기 → 저장
 *   2. 프로젝트 설정(톱니바퀴) → 시간대: (GMT+09:00) 서울
 *   3. 위쪽 함수 선택에서 setup → 실행 → 권한 검토 → 허용
 *      실행 로그에 나오는 TOKEN 을 복사
 *   4. 배포 → 새 배포 → 유형 선택: 웹 앱
 *        실행 사용자: 나 / 액세스 권한: 모든 사용자 → 배포 → 웹 앱 URL 복사
 *   5. URL 과 TOKEN 을 키오스크에 등록 (README 의 PUT /api/gas)
 *
 * 다른 캘린더도 읽으려면 프로젝트 설정 → 스크립트 속성 → CALENDARS 에 "가족,아이 학원" 처럼 쉼표로.
 * 이름이 같은 캘린더가 여럿이면(예: Google 가족 그룹이 자동으로 만든 "가족") 이름 대신
 * 캘린더 ID(…@group.calendar.google.com)를 적는다. ID 는 diag() 실행 로그에 나온다.
 * 일정 추가는 항상 첫 번째 캘린더에 한다.
 */

const DEFAULT_CALENDAR = "가족";

function setup() {
  const p = PropertiesService.getScriptProperties();
  let token = p.getProperty("TOKEN");
  if (!token) {
    token = (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, "").slice(0, 40);
    p.setProperty("TOKEN", token);
  }
  if (!p.getProperty("CALENDARS")) p.setProperty("CALENDARS", DEFAULT_CALENDAR);
  const cals = calendars_();
  if (!cals.length) throw new Error("캘린더를 찾지 못했습니다: " + p.getProperty("CALENDARS"));
  Logger.log("TOKEN: " + token);
  Logger.log("읽는 캘린더: " + cals.map((c) => c.getName()).join(", ") + " / 추가는: " + cals[0].getName());
}

function calendars_() {
  const names = (PropertiesService.getScriptProperties().getProperty("CALENDARS") || DEFAULT_CALENDAR)
    .split(",").map((s) => s.trim()).filter(String);
  return names.map((n) => (n.indexOf("@") > 0 ? CalendarApp.getCalendarById(n) : CalendarApp.getCalendarsByName(n)[0])).filter(Boolean);
}

/** 어떤 캘린더가 있는지, 오늘 일정이 어디에 있는지 확인 (편집기에서 실행 → 실행 로그) */
function diag() {
  Logger.log("계정: " + Session.getEffectiveUser().getEmail());
  Logger.log("CALENDARS 속성: " + (PropertiesService.getScriptProperties().getProperty("CALENDARS") || DEFAULT_CALENDAR));
  CalendarApp.getAllCalendars().forEach((c) => {
    const today = c.getEventsForDay(new Date());
    Logger.log(c.getName() + " | 내 캘린더:" + c.isOwnedByMe() + " | 숨김:" + c.isHidden() +
      " | 오늘 " + today.length + "개 " + today.map((e) => e.getTitle()).join(", ") + " | id=" + c.getId());
  });
  Logger.log("키오스크가 쓰는 캘린더: " + calendars_().map((c) => c.getName() + " (" + c.getId() + ")").join(", "));
}

function auth_(token) {
  const t = PropertiesService.getScriptProperties().getProperty("TOKEN");
  if (!t || String(token || "") !== t) throw new Error("unauthorized");
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doGet(e) {
  try {
    auth_(e.parameter.token);
    const days = Math.min(14, Math.max(1, Number(e.parameter.days || 2)));
    const from = new Date(); from.setHours(0, 0, 0, 0);
    const to = new Date(from.getTime()); to.setDate(to.getDate() + days);
    const events = [];
    calendars_().forEach((cal) => {
      cal.getEvents(from, to).forEach((ev) => {
        const allDay = ev.isAllDayEvent();
        events.push({
          uid: ev.getId(),
          title: ev.getTitle(),
          allDay: allDay,
          start: (allDay ? ev.getAllDayStartDate() : ev.getStartTime()).getTime(),
          end: (allDay ? ev.getAllDayEndDate() : ev.getEndTime()).getTime(),
          location: ev.getLocation(),
          description: ev.getDescription(),
          calendar: cal.getName(),
          color: cal.getColor(),
        });
      });
    });
    return json_({ ok: true, at: Date.now(), events: events });
  } catch (err) {
    return json_({ ok: false, error: String((err && err.message) || err) });
  }
}

function doPost(e) {
  try {
    const b = JSON.parse((e.postData && e.postData.contents) || "{}");
    auth_(b.token);
    if (b.action !== "add") throw new Error("unknown action");
    const cal = calendars_()[0];
    if (!cal) throw new Error("calendar not found");
    const title = String(b.title || "").trim().slice(0, 200);
    if (!title) throw new Error("title required");
    const opts = { description: String(b.description || ""), location: String(b.location || "") };
    const start = new Date(Number(b.start));
    const end = new Date(Number(b.end || b.start));
    if (isNaN(start.getTime()) || isNaN(end.getTime())) throw new Error("bad time");
    let ev;
    if (b.allDay) {
      ev = end.getTime() - start.getTime() > 864e5
        ? cal.createAllDayEvent(title, start, end, opts)
        : cal.createAllDayEvent(title, start, opts);
    } else {
      ev = cal.createEvent(title, start, end, opts);
    }
    return json_({ ok: true, uid: ev.getId(), calendar: cal.getName() });
  } catch (err) {
    return json_({ ok: false, error: String((err && err.message) || err) });
  }
}
