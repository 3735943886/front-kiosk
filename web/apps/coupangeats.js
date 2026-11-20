"use strict";
/* 쿠팡이츠. 실제 알림을 아직 못 봐서 공통 배달 문구로만 단계를 찾는다. */
KioskApps.register({
  package: "com.coupang.mobile.eats",
  label: "쿠팡이츠",
  icon: "bag",
  card: (raw) => DeliveryKit.card(raw, { id: "app:coupangeats-order" }),
});
