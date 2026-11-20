"use strict";
/* 배달 앱 공통 도우미. 알림 문구에서 주문 단계를 찾아 진행 카드로 만든다.
   앱마다 문구가 다르면 어댑터에서 patterns 를 넘겨 덮어쓴다. */
window.DeliveryKit = (() => {
  const STEPS = ["주문 접수", "조리 중", "배달 출발", "배달 완료"];
  const PATTERNS = [            // 뒤 단계부터 검사
    [3, /완료|도착했/],
    [2, /출발|배달을 시작|픽업|가고 있|곧 도착/],
    [1, /조리|준비 중|준비를 시작/],
    [0, /접수|수락|주문을 확인/],
  ];
  function step(raw, patterns = PATTERNS) {
    const all = [raw.title, raw.bigText || raw.text, raw.subText].join(" ");
    for (const [s, re] of patterns) if (re.test(all)) return s;
    return null;
  }
  /** 주문 단계를 찾으면 진행 카드 필드, 못 찾으면 undefined (기본 카드) */
  function card(raw, { id, patterns } = {}) {
    const s = step(raw, patterns);
    if (s === null) return undefined;
    return {
      id, level: s === 3 ? "ok" : "info",
      title: raw.title || STEPS[s],
      progress: { step: s, steps: STEPS },
      speak: s === 1 ? undefined : `${STEPS[s]}. ${raw.title || ""}`,
      ttlSec: s === 3 ? 1800 : 4 * 3600,
    };
  }
  return { STEPS, step, card };
})();
