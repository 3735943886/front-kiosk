/* Minimal iCalendar (ICS) reader for the dashboard.
   - VEVENT: SUMMARY, DESCRIPTION, LOCATION, UID, DTSTART, DTEND, DURATION, RRULE, EXDATE, RECURRENCE-ID, STATUS
   - RRULE: FREQ=DAILY|WEEKLY|MONTHLY|YEARLY, INTERVAL, COUNT, UNTIL, BYDAY (incl. 2MO / -1FR), BYMONTHDAY, BYMONTH, WKST
   - Times with TZID are treated as device-local wall time (the kiosk lives in one time zone); "Z" times are UTC.
   - Only a small window (today + tomorrow) is expanded, which is all the dashboard shows.
   Tags in the title or description:
     #알림 30,5   → remind 30 and 5 minutes before (bare "#알림" = 5)
     #멘트 문장    → extra sentence spoken with the reminder
*/
(function (g) {
  "use strict";
  const DAY = 86400e3;
  const WD = { SU: 0, MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6 };

  const unfold = (text) => String(text || "").replace(/\r\n?/g, "\n").replace(/\n[ \t]/g, "");
  const unescapeText = (v) => v.replace(/\\n/gi, "\n").replace(/\\([,;\\])/g, "$1");

  function parseLine(line) {
    let i = 0, inQ = false;
    for (; i < line.length; i++) {
      const c = line[i];
      if (c === '"') inQ = !inQ;
      else if (c === ":" && !inQ) break;
    }
    const parts = line.slice(0, i).split(";");
    const name = parts.shift().toUpperCase();
    const params = {};
    for (const p of parts) {
      const k = p.indexOf("=");
      if (k > 0) params[p.slice(0, k).toUpperCase()] = p.slice(k + 1).replace(/^"|"$/g, "");
    }
    return { name, params, value: line.slice(i + 1) };
  }

  /** "20261120" | "20261120T100000" | "20261120T010000Z" → local wall-clock parts + epoch ms */
  function parseDate(value) {
    const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$/.exec(String(value).trim());
    if (!m) return null;
    const [y, mo, d] = [+m[1], +m[2] - 1, +m[3]];
    if (m[4] === undefined) return { allDay: true, y, m: mo, d, h: 0, mi: 0, s: 0, t: new Date(y, mo, d).getTime() };
    if (m[7]) {
      const t = Date.UTC(y, mo, d, +m[4], +m[5], +m[6]), L = new Date(t);
      return { allDay: false, y: L.getFullYear(), m: L.getMonth(), d: L.getDate(), h: L.getHours(), mi: L.getMinutes(), s: L.getSeconds(), t };
    }
    return { allDay: false, y, m: mo, d, h: +m[4], mi: +m[5], s: +m[6], t: new Date(y, mo, d, +m[4], +m[5], +m[6]).getTime() };
  }

  function parseDuration(v) {
    const m = /^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(String(v).trim());
    if (!m) return null;
    const ms = (+(m[2] || 0) * 7 + +(m[3] || 0)) * DAY + (+(m[4] || 0) * 3600 + +(m[5] || 0) * 60 + +(m[6] || 0)) * 1000;
    return m[1] === "-" ? -ms : ms;
  }

  function parseRule(v) {
    const r = {};
    for (const kv of v.split(";")) {
      const k = kv.indexOf("=");
      if (k > 0) r[kv.slice(0, k).toUpperCase()] = kv.slice(k + 1);
    }
    return r;
  }

  /** ICS text → list of raw VEVENTs */
  function parse(text) {
    const out = [];
    let cur = null, depth = 0;
    for (const line of unfold(text).split("\n")) {
      if (!line) continue;
      const { name, params, value } = parseLine(line);
      if (name === "BEGIN") {
        if (value.toUpperCase() === "VEVENT" && !cur) { cur = { exdates: [] }; depth = 0; }
        else if (cur) depth++;           // VALARM etc. inside an event: skip its properties
        continue;
      }
      if (name === "END") {
        if (!cur) continue;
        if (depth > 0) { depth--; continue; }
        if (value.toUpperCase() === "VEVENT") { out.push(cur); cur = null; }
        continue;
      }
      if (!cur || depth > 0) continue;
      switch (name) {
        case "UID": cur.uid = value; break;
        case "SUMMARY": cur.summary = unescapeText(value); break;
        case "DESCRIPTION": cur.description = unescapeText(value); break;
        case "LOCATION": cur.location = unescapeText(value); break;
        case "STATUS": cur.status = value.toUpperCase(); break;
        case "DTSTART": cur.start = parseDate(value); if (cur.start && params.VALUE === "DATE") cur.start.allDay = true; break;
        case "DTEND": cur.end = parseDate(value); break;
        case "DURATION": cur.duration = parseDuration(value); break;
        case "RRULE": cur.rrule = parseRule(value); break;
        case "EXDATE": for (const v of value.split(",")) { const d = parseDate(v); if (d) cur.exdates.push(d); } break;
        case "RECURRENCE-ID": cur.recurrenceId = parseDate(value); break;
      }
    }
    return out;
  }

  const dayNum = (y, m, d) => Math.round(Date.UTC(y, m, d) / DAY);
  const weekday = (y, m, d) => new Date(Date.UTC(y, m, d)).getUTCDay();
  const daysIn = (y, m) => new Date(Date.UTC(y, m + 1, 0)).getUTCDate();

  /** does day (y,m,d) carry an occurrence of rule r anchored at S? (ignores COUNT/UNTIL) */
  function matches(r, S, y, m, d) {
    const dn = dayNum(y, m, d), sn = dayNum(S.y, S.m, S.d);
    if (dn < sn) return false;
    const iv = Math.max(1, +(r.INTERVAL || 1));
    const wd = weekday(y, m, d), dim = daysIn(y, m);
    const byday = r.BYDAY ? r.BYDAY.split(",") : null;
    const bymd = r.BYMONTHDAY ? r.BYMONTHDAY.split(",").map(Number) : null;
    const bymon = r.BYMONTH ? r.BYMONTH.split(",").map(Number) : null;
    if (bymon && !bymon.includes(m + 1)) return false;
    const mdOk = (list) => list.some((x) => (x > 0 ? x === d : dim + x + 1 === d));
    const dayOk = (list, ordinal) => list.some((tok) => {
      const mm = /^([+-]?\d+)?(SU|MO|TU|WE|TH|FR|SA)$/.exec(tok.trim());
      if (!mm || WD[mm[2]] !== wd) return false;
      if (!mm[1] || !ordinal) return true;
      const n = +mm[1];
      return n > 0 ? Math.ceil(d / 7) === n : Math.ceil((dim - d + 1) / 7) === -n;
    });
    switch ((r.FREQ || "").toUpperCase()) {
      case "DAILY":
        return (dn - sn) % iv === 0 && (!byday || dayOk(byday, false)) && (!bymd || mdOk(bymd));
      case "WEEKLY": {
        const wkst = WD[r.WKST] ?? 1, sw = weekday(S.y, S.m, S.d);
        const weekStart = (n, w) => n - ((w - wkst + 7) % 7);
        if (((weekStart(dn, wd) - weekStart(sn, sw)) / 7) % iv !== 0) return false;
        return byday ? dayOk(byday, false) : wd === sw;
      }
      case "MONTHLY": {
        if (((y - S.y) * 12 + (m - S.m)) % iv !== 0) return false;
        if (bymd) return mdOk(bymd);
        if (byday) return dayOk(byday, true);
        return d === S.d;
      }
      case "YEARLY": {
        if ((y - S.y) % iv !== 0) return false;
        if (!bymon && m !== S.m) return false;
        if (bymd) return mdOk(bymd);
        if (byday) return dayOk(byday, true);
        return d === S.d;
      }
    }
    return false;
  }

  /** occurrences from DTSTART up to and including day (y,m,d) — for COUNT */
  function countUpTo(r, S, y, m, d) {
    let n = 0;
    const end = dayNum(y, m, d);
    for (let i = 0, k = dayNum(S.y, S.m, S.d); k <= end && i < 4000; i++, k++) {
      const D = new Date(k * DAY);
      if (matches(r, S, D.getUTCFullYear(), D.getUTCMonth(), D.getUTCDate())) n++;
    }
    return n;
  }

  /** raw VEVENTs → occurrences overlapping [from, to) */
  function expand(evs, from, to) {
    const out = [], overrides = new Map(), used = new Set();
    for (const e of evs) if (e.recurrenceId && e.uid) overrides.set(`${e.uid}|${e.recurrenceId.t}`, e);
    const durOf = (e) => (e.end ? e.end.t - e.start.t : e.duration != null ? e.duration : e.start.allDay ? DAY : 0);
    const overlaps = (st, dur) => st < to && st + Math.max(dur, 1) > from;
    const occ = (e, st, dur) => ({ uid: e.uid || "", start: st, end: st + dur, allDay: !!e.start.allDay, title: e.summary || "", location: e.location || "", description: e.description || "" });

    for (const e of evs) {
      if (e.recurrenceId || !e.start || e.status === "CANCELLED") continue;
      const dur = durOf(e);
      if (!e.rrule) { if (overlaps(e.start.t, dur)) out.push(occ(e, e.start.t, dur)); continue; }
      const r = e.rrule, S = e.start;
      const until = r.UNTIL ? parseDate(r.UNTIL) : null;
      const untilT = until ? (until.allDay ? until.t + DAY - 1 : until.t) : Infinity;
      const ex = new Set(e.exdates.map((x) => x.t));
      const F = new Date(from);
      const spanDays = Math.ceil((to - from) / DAY) + Math.ceil(Math.max(dur, 0) / DAY) + 1;
      for (let i = -Math.ceil(Math.max(dur, 0) / DAY) - 1; i < spanDays; i++) {
        const D = new Date(F.getFullYear(), F.getMonth(), F.getDate() + i);
        const y = D.getFullYear(), m = D.getMonth(), d = D.getDate();
        if (!matches(r, S, y, m, d)) continue;
        const st = new Date(y, m, d, S.h, S.mi, S.s).getTime();
        if (st > untilT) continue;
        if (r.COUNT && countUpTo(r, S, y, m, d) > +r.COUNT) continue;
        const ov = overrides.get(`${e.uid}|${st}`);
        if (ov) {
          used.add(ov);
          if (ov.status !== "CANCELLED" && ov.start && overlaps(ov.start.t, durOf(ov))) out.push(occ(ov, ov.start.t, durOf(ov)));
          continue;
        }
        if (ex.has(st) || !overlaps(st, dur)) continue;
        out.push(occ(e, st, dur));
      }
    }
    // a single occurrence moved into the window from outside it
    for (const ov of overrides.values()) {
      if (used.has(ov) || ov.status === "CANCELLED" || !ov.start) continue;
      if (overlaps(ov.start.t, durOf(ov))) out.push(occ(ov, ov.start.t, durOf(ov)));
    }
    return out.sort((a, b) => a.start - b.start);
  }

  /** pull #알림 / #멘트 tags out of a title or description */
  function parseTags(title, description) {
    const clean = (s) => String(s || "")
      .replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div|li)>/gi, "\n").replace(/<[^>]+>/g, "")
      .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
    let remind = null, speak = null;
    const take = (s) => s
      .replace(/#멘트[ \t]+([^\n#]+)/g, (_, t) => { speak = t.trim(); return ""; })
      .replace(/#알림[ \t]*([\d,\s]*)/g, (_, nums) => {
        const list = nums.split(/[,\s]+/).map(Number).filter((n) => n > 0);
        remind = (remind || []).concat(list.length ? list : [5]);
        return "";
      });
    const t = take(clean(title)).replace(/\s+/g, " ").trim();
    const note = take(clean(description)).split("\n").map((l) => l.trim()).filter(Boolean).join("\n");
    if (remind) remind = [...new Set(remind)].sort((a, b) => b - a);
    return { title: t, note, remind, speak };
  }

  /** calendar config {name, color, remind} + ICS text → dashboard agenda items */
  function toAgenda(evs, cal, from, to) {
    return expand(evs, from, to).map((o) => {
      const tags = parseTags(o.title, o.description);
      return {
        id: `${cal.name || "cal"}:${o.uid}:${o.start}`, uid: o.uid,
        title: tags.title || "(제목 없음)",
        start: o.start, end: o.end, allDay: o.allDay,
        location: o.location, note: tags.note,
        calendar: cal.name || "", color: cal.color || undefined,
        holiday: !!cal.holiday || /휴일|공휴일|holiday/i.test(cal.name || ""),
        remind: tags.remind || (Array.isArray(cal.remind) ? cal.remind : cal.remind ? [cal.remind] : null),
        speakText: tags.speak || undefined,
      };
    });
  }

  g.ICS = { parse, expand, parseTags, toAgenda, parseDate };
})(typeof window !== "undefined" ? window : globalThis);
