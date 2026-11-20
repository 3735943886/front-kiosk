"use strict";
/* 다른 앱 알림 어댑터 등록소.
   기기 앱은 감시 대상 앱의 알림을 raw 로 넘기고, 대시보드는 누구 알림이든 기본 카드로 띄운다.
   앱마다 다듬고 싶으면 web/apps/<앱>.js 에 어댑터를 하나 두고 index.html 에 <script> 한 줄을 더한다.

   raw = { package, appLabel, key, id, tag, postTime, channel, category, ongoing,
           title, text, bigText, subText, lines[], progress, progressMax }

   KioskApps.register({
     package: "com.example",          // 필수
     label: "앱 이름",                 // 카드 위 출처 (없으면 기기가 알려준 앱 이름)
     icon: "bag",                      // 카드 아이콘 (index.html 의 아이콘 이름)
     card(raw) {                       // 선택. 기본 카드에 덮어쓸 필드를 돌려준다
       return { ... }                  //   객체 → 기본 카드 + 이 필드 (id 를 주면 그 카드로 묶인다)
                                       //   null → 이 알림은 무시 (광고 등)
                                       //   undefined → 기본 카드 그대로
     },
   });
*/
window.KioskApps = (() => {
  const byPkg = new Map();
  return {
    register(def) {
      if (!def || !def.package) throw new Error("KioskApps.register: package required");
      byPkg.set(def.package, def);
    },
    get: (pkg) => byPkg.get(pkg),
    list: () => [...byPkg.keys()],
  };
})();
