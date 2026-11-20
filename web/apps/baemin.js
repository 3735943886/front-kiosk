"use strict";
/* 배달의민족. 문구는 실제 알림(GET /api/android-notifications)을 보고 다듬는다. */
KioskApps.register({
  package: "com.sampleapp",
  label: "배달의민족",
  icon: "bag",
  card(raw) {
    if (raw.channel === "com_appboy_default_notification_channel") return null;   // 마케팅
    return DeliveryKit.card(raw, { id: "app:baemin-order" });
  },
});
