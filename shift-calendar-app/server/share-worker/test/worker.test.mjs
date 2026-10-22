// [MKT-09] Runs the Worker's fetch handler in Node with an in-memory KV: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import worker, { validate, renderSchedule, renderIcs } from "../src/index.js";

function memoryKv() {
  const map = new Map();
  return {
    map,
    async get(key, type) {
      const v = map.get(key);
      if (v === undefined) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(key, value, opts) { map.set(key, value); this.lastTtl = opts?.expirationTtl; },
    async delete(key) { map.delete(key); },
  };
}

const sample = {
  v: 1,
  title: "민지",
  ttlDays: 30,
  types: [
    { id: 1, name: "주간", short: "주", color: "#1E88E5", time: "07:00-19:00" },
    { id: 2, name: "야간", short: "야", color: "#5E35B1", time: "19:00-07:00" },
    { id: 3, name: "휴무", short: "휴", color: "#9E9E9E", off: true },
  ],
  days: [
    { date: "2026-11-01", type: 1 },
    { date: "2026-11-02", type: 1, second: 2 },
    { date: "2026-11-03", type: 3, leave: "연차" },
  ],
};

const call = (env, method, path, body, headers = {}) =>
  worker.fetch(new Request(`https://share.example${path}`, {
    method,
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.5", ...headers },
    body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  }), env);

test("create, view, update and delete a link", async () => {
  const env = { SHARES: memoryKv() };
  const res = await call(env, "POST", "/api/share", sample);
  assert.equal(res.status, 201);
  const made = await res.json();
  assert.match(made.id, /^[A-Za-z0-9]{22}$/);
  assert.equal(made.url, `https://share.example/s/${made.id}`);
  assert.ok(env.SHARES.lastTtl > 29 * 86400 && env.SHARES.lastTtl <= 30 * 86400);

  const page = await call(env, "GET", `/s/${made.id}`);
  assert.equal(page.status, 200);
  assert.equal(page.headers.get("x-robots-tag"), "noindex, nofollow");
  assert.match(page.headers.get("content-security-policy"), /default-src 'none'/);
  const html = await page.text();
  assert.match(html, /민지의 근무표/);
  assert.match(html, /2026년 11월/);
  assert.match(html, /07:00~19:00/);
  assert.match(html, /연차/);

  // The server never stores the delete token itself.
  const stored = env.SHARES.map.get(made.id);
  assert.ok(!stored.includes(made.deleteToken));

  assert.equal((await call(env, "PUT", `/api/share/${made.id}`, sample, { "x-delete-token": "wrong" })).status, 403);
  const updated = await call(env, "PUT", `/api/share/${made.id}`, { ...sample, title: "민지2" }, { "x-delete-token": made.deleteToken });
  assert.equal(updated.status, 200);
  assert.match(await (await call(env, "GET", `/s/${made.id}`)).text(), /민지2의 근무표/);

  assert.equal((await call(env, "DELETE", `/api/share/${made.id}`, undefined, { "x-delete-token": made.deleteToken })).status, 204);
  assert.equal((await call(env, "GET", `/s/${made.id}`)).status, 404);
});

test("text is escaped on the page", async () => {
  const html = renderSchedule({ ...validate({ ...sample, title: "<b>x</b>" }).schedule }, new Date(Date.now() + 86400000).toISOString());
  assert.ok(!html.includes("<b>x</b>"));
  assert.match(html, /&lt;b&gt;x&lt;\/b&gt;/);
});

test("anything outside the schedule shape is refused", () => {
  assert.equal(validate({ ...sample, v: 3 }).error, "version");
  assert.equal(validate({ ...sample, v: 2 }).error, "shows");
  assert.equal(validate({ ...sample, v: 2, shows: ["phone"] }).error, "shows");
  assert.equal(validate({ ...sample, types: [{ ...sample.types[0], color: "red" }] }).error, "type");
  assert.equal(validate({ ...sample, days: [{ date: "2026-11-01", type: 99 }] }).error, "day");
  assert.equal(validate({ ...sample, days: [{ date: "nope", type: 1 }] }).error, "day");
  assert.equal(validate({ ...sample, title: "x".repeat(31) }).error, "title");
  // Unknown fields such as a memo are dropped, not stored.
  const kept = validate({ ...sample, memo: "비밀", days: [{ date: "2026-11-01", type: 1, memo: "비밀" }] });
  assert.ok(!JSON.stringify(kept.schedule).includes("비밀"));
  // [PROV-04] 180 and 365 are allowed since 1.20; anything else is 30.
  assert.equal(validate({ ...sample, ttlDays: 366 }).ttlDays, 30);
});

test("bodies over the size limit and non-JSON are refused", async () => {
  const env = { SHARES: memoryKv() };
  assert.equal((await call(env, "POST", "/api/share", "x".repeat(50 * 1024))).status, 400);
  assert.equal((await call(env, "POST", "/api/share", sample, { "content-type": "text/plain" })).status, 400);
});

test("one caller can make at most 20 links an hour", async () => {
  const env = { SHARES: memoryKv() };
  for (let i = 0; i < 20; i++) assert.equal((await call(env, "POST", "/api/share", sample)).status, 201);
  assert.equal((await call(env, "POST", "/api/share", sample)).status, 429);
  // No raw address is kept.
  assert.ok(![...env.SHARES.map.keys()].some((k) => k.includes("203.0.113.5")));
});

test("unknown paths and the root are not indexed", async () => {
  const env = { SHARES: memoryKv() };
  assert.equal((await call(env, "GET", "/s/short")).status, 404);
  assert.match(await (await call(env, "GET", "/robots.txt")).text(), /Disallow: \//);
});

// ---- [SHR-01] what the sharer ticks, [SHR-02] the app's 받은 달력 ----

const full = {
  ...sample,
  v: 2,
  shows: ["pay", "memos", "extras", "leaveKinds"],
  days: [
    { date: "2026-11-01", type: 1, memo: "엄마 생신 · 저녁 7시", extras: [{ name: "잔업", time: "19:00-21:00", minutes: 120 }] },
    { date: "2026-11-02", type: 1, second: 2 },
    { date: "2026-11-03", type: 3, leave: "병가" },
    { date: "2026-11-04", memo: "근무 없는 날 메모" },
  ],
  pay: [{ month: "2026-11", amount: 3120000, label: "세전 예상" }],
};

test("v2 keeps only the parts the sharer ticked", () => {
  const onlyMemos = validate({ ...full, shows: ["memos"] });
  assert.equal(onlyMemos.error, undefined);
  const text = JSON.stringify(onlyMemos.schedule);
  assert.match(text, /엄마 생신/);
  assert.ok(!text.includes("잔업"), "extra work was not ticked");
  assert.ok(!text.includes("3120000"), "pay was not ticked");
  assert.deepEqual(onlyMemos.schedule.shows, ["memos"]);

  const nothing = validate({ ...full, shows: [], days: full.days.slice(0, 3) });
  assert.ok(!JSON.stringify(nothing.schedule).includes("엄마"));
  assert.equal(nothing.schedule.pay, undefined);

  const all = validate({ ...full, types: [{ ...full.types[0], icon: "sun" }, ...full.types.slice(1)],
    days: [...full.days.slice(0, 2), { ...full.days[2], rest: true }, full.days[3]] }).schedule;
  assert.equal(all.types[0].icon, "sun");
  assert.equal(all.types[1].icon, null);
  assert.equal(all.days[2].rest, true);
  assert.equal(all.days[1].rest, undefined);
  assert.equal(validate({ ...full, types: [{ ...full.types[0], icon: "<svg>" }] }).error, "type_icon");
  assert.deepEqual(all.shows, ["leaveKinds", "extras", "memos", "pay"]);
  assert.equal(all.payKind, "pay");
  assert.deepEqual(all.days[0].extras, [{ name: "잔업", time: "19:00-21:00", minutes: 120 }]);
  assert.equal(all.days[3].type, null);
  assert.deepEqual(all.pay, [{ month: "2026-11", amount: 3120000, label: "세전 예상" }]);
});

test("v2 parts are checked like the rest", () => {
  const day = (d) => validate({ ...full, days: [d] }).error;
  assert.equal(day({ date: "2026-11-01", type: 1, memo: "x".repeat(81) }), "day_memo");
  assert.equal(day({ date: "2026-11-01", type: 1, extras: [{ name: "잔업", time: "25:00-26:00" }] }), "day_extra");
  assert.equal(day({ date: "2026-11-01", type: 1, extras: [{ name: "잔업", minutes: 0 }] }), "day_extra");
  assert.equal(day({ date: "2026-11-01", type: 1, extras: Array(5).fill({ name: "잔업" }) }), "day_extras");
  assert.equal(day({ date: "2026-11-01" }), "day", "a day with nothing on it");
  assert.equal(validate({ ...sample, days: [{ date: "2026-11-01" }] }).error, "day", "v1 days need a shift or a 대근");
  assert.equal(validate({ ...full, days: [full.days[0], full.days[0]] }).error, "day", "one entry a date");
  assert.equal(validate({ ...full, pay: [{ month: "2026-13", amount: 1 }] }).error, "pay");
  assert.equal(validate({ ...full, pay: [{ month: "2026-11", amount: -1 }] }).error, "pay");
  assert.equal(validate({ ...full, pay: [{ month: "2026-11", amount: 1.5 }] }).error, "pay");
});

// [SHR-11] A shop or a freelancer: money in their own kind, a freelancer's jobs, and links with no shifts at all.
test("money keeps its kind, and jobs show by title and time only", () => {
  const shop = validate({ v: 2, title: "가나다식당", ttlDays: 30, shows: ["pay"], payKind: "sales", types: [], days: [],
    pay: [{ month: "2026-11", amount: 18450000, label: "매출" }] });
  assert.equal(shop.error, undefined);
  assert.equal(shop.schedule.payKind, "sales");
  assert.deepEqual(shop.schedule.days, []);
  assert.equal(validate({ ...full, payKind: "profit" }).error, "pay_kind");
  assert.equal(validate({ ...full }).schedule.payKind, "pay", "links from before 1.13 are pay");
  assert.equal(validate({ v: 2, title: "", ttlDays: 30, shows: ["pay"], types: [], days: [], pay: [] }).error, "days", "nothing to show");
  assert.equal(validate({ ...sample, days: [] }).error, "days");

  const field = { v: 2, title: "준호", ttlDays: 30, shows: ["jobs", "pay"], payKind: "jobs", types: [],
    days: [{ date: "2026-11-03", rest: false, jobs: [{ name: "욕실 시공", time: "09:00-17:00", client: "김", amount: 900000 }] }],
    pay: [{ month: "2026-11", amount: 870300, label: "일감 1건" }] };
  const out = validate(field).schedule;
  assert.deepEqual(out.days[0].jobs, [{ name: "욕실 시공", time: "09:00-17:00" }]);
  assert.ok(!JSON.stringify(out).includes("900000"), "a job's price never goes in");
  assert.equal(validate({ ...field, shows: ["pay"] }).error, "day", "jobs not ticked are dropped");
  assert.equal(validate({ ...field, days: [{ date: "2026-11-03", jobs: Array(5).fill({ name: "a" }) }] }).error, "day_jobs");
  assert.equal(validate({ ...field, days: [{ date: "2026-11-03", jobs: [{ name: "x".repeat(13) }] }] }).error, "day_job");

  const shopPage = renderSchedule(shop.schedule, new Date(Date.now() + 86400000).toISOString());
  assert.match(shopPage, /함께 보낸 매출/);
  assert.match(shopPage, /18,450,000원/);
  assert.match(shopPage, /근무와 함께 보낸 것: 매출/);
  assert.ok(!shopPage.includes("명세서"));
  const fieldPage = renderSchedule(out, new Date(Date.now() + 86400000).toISOString());
  assert.match(fieldPage, /근무와 함께 보낸 것: 일정, 일감 수입/);
  assert.match(fieldPage, /욕실 시공 09:00/);
});

test("rest is kept either way, and a 대근 on a day with no shift goes in v1 too", () => {
  const out = validate({ ...sample, days: [
    { date: "2026-11-01", type: 1, rest: false },
    { date: "2026-11-02", type: 3, rest: true },
    { date: "2026-11-07", second: 2, rest: false },
    { date: "2026-11-08", type: 3, rest: "yes" },
  ] });
  assert.equal(out.error, undefined);
  assert.deepEqual(out.schedule.days.map((d) => d.rest), [false, true, false, undefined]);
  assert.equal(out.schedule.days[2].type, null);
  assert.equal(out.schedule.days[2].second, 2);
});

test("without 휴가 종류 ticked a leave keeps only how much of the day it takes", () => {
  const leaves = ["연차", "0.75일 연차", "유급 휴가", "유급 휴가 반차", "무급 휴가 반반차", "0.75일 무급 휴가", "병가"];
  const days = leaves.map((leave, i) => ({ date: `2026-11-0${i + 1}`, type: 1, leave }));
  assert.deepEqual(validate({ ...sample, days }).schedule.days.map((d) => d.leave),
    ["연차", "0.75일 연차", "휴가", "반차", "반반차", "0.75일 휴가", "휴가"]);
  assert.deepEqual(validate({ ...full, shows: ["memos"], days }).schedule.days.map((d) => d.leave)[3], "반차");
  assert.deepEqual(validate({ ...full, days }).schedule.days.map((d) => d.leave), leaves);
  assert.equal(validate({ ...sample, days: [{ date: "2026-11-01", type: 1, leave: "x".repeat(13) }] }).error, "day_leave");
});

test("the page shows ticked parts, escaped", async () => {
  const html = renderSchedule(validate({ ...full, days: [{ date: "2026-11-01", type: 1, memo: "<img src=x>" }, ...full.days.slice(1)] }).schedule,
    new Date(Date.now() + 86400000).toISOString());
  assert.match(html, /근무와 함께 보낸 것: 휴가 종류, 연장·휴무일 근무, 메모, 급여/);
  assert.match(html, /병가/);
  assert.match(html, /3,120,000원/);
  assert.match(html, /세전 예상/);
  assert.ok(!html.includes("<img"));
  assert.match(html, /&lt;img src=x&gt;/);
  assert.ok(!html.includes("intent://"), "no app button unless asked");
});

test("Android visitors get a button into the app", async () => {
  const env = { SHARES: memoryKv() };
  const made = await (await call(env, "POST", "/api/share", full)).json();
  const phone = await (await call(env, "GET", `/s/${made.id}`, undefined, { "user-agent": "Mozilla/5.0 (Linux; Android 14; SM-F956N)" })).text();
  assert.match(phone, new RegExp(`intent://s/${made.id}#Intent;scheme=bamnat;package=com\\.bamnat\\.app;`));
  assert.match(phone, /밤낮 앱에서 보기/);
  const desktop = await (await call(env, "GET", `/s/${made.id}`, undefined, { "user-agent": "Mozilla/5.0 (Windows NT 10.0)" })).text();
  assert.ok(!desktop.includes("intent://"));
});

test("the app reads a link as JSON until it is cut or expires", async () => {
  const env = { SHARES: memoryKv() };
  const made = await (await call(env, "POST", "/api/share", full)).json();
  const res = await call(env, "GET", `/api/share/${made.id}`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("cache-control"), "no-store");
  const got = await res.json();
  assert.equal(got.schedule.title, "민지");
  assert.equal(got.schedule.days.length, 4);
  assert.equal(got.expiresAt, made.expiresAt);
  assert.ok(Date.parse(got.updatedAt) <= Date.now());
  assert.ok(!JSON.stringify(got).includes("tokenHash"));

  // A refreshed link moves its updatedAt; the app shows when it last changed.
  const before = got.updatedAt;
  await new Promise((r) => setTimeout(r, 5));
  await call(env, "PUT", `/api/share/${made.id}`, { ...full, title: "민지2" }, { "x-delete-token": made.deleteToken });
  const after = await (await call(env, "GET", `/api/share/${made.id}`)).json();
  assert.equal(after.schedule.title, "민지2");
  assert.ok(after.updatedAt > before);

  // Expired: the same answer as cut, so the app clears its copy.
  const record = JSON.parse(env.SHARES.map.get(made.id));
  env.SHARES.map.set(made.id, JSON.stringify({ ...record, expiresAt: new Date(Date.now() - 1000).toISOString() }));
  assert.equal((await call(env, "GET", `/api/share/${made.id}`)).status, 404);
  env.SHARES.map.set(made.id, JSON.stringify(record));
  await call(env, "DELETE", `/api/share/${made.id}`, undefined, { "x-delete-token": made.deleteToken });
  const gone = await call(env, "GET", `/api/share/${made.id}`);
  assert.equal(gone.status, 404);
  assert.deepEqual(await gone.json(), { error: "not_found" });
});

test("[SHR-12] an update keeps the end date unless the sharer extends it", async () => {
  const env = { SHARES: memoryKv() };
  const made = await (await call(env, "POST", "/api/share", { ...sample, ttlDays: 7 })).json();
  const token = { "x-delete-token": made.deleteToken };
  const { ttlDays, ...auto } = sample;
  const kept = await (await call(env, "PUT", `/api/share/${made.id}`, { ...auto, title: "민지2" }, token)).json();
  assert.equal(kept.expiresAt, made.expiresAt, "a change the app sends by itself moves nothing");
  assert.ok(env.SHARES.lastTtl <= 7 * 86400);
  const longer = await (await call(env, "PUT", `/api/share/${made.id}`, { ...auto, ttlDays: 90 }, token)).json();
  assert.ok(Date.parse(longer.expiresAt) > Date.now() + 89 * 86400000, "연장 opens it 90 days from now");
  assert.ok(env.SHARES.lastTtl > 89 * 86400);

  // A link past its end date cannot be brought back by an update.
  const record = JSON.parse(env.SHARES.map.get(made.id));
  env.SHARES.map.set(made.id, JSON.stringify({ ...record, expiresAt: new Date(Date.now() - 1000).toISOString() }));
  assert.equal((await call(env, "PUT", `/api/share/${made.id}`, auto, token)).status, 404);
});

test("[SHR-12] receivers download only what changed", async () => {
  const env = { SHARES: memoryKv() };
  const made = await (await call(env, "POST", "/api/share", sample)).json();
  const token = { "x-delete-token": made.deleteToken };
  const first = await call(env, "GET", `/api/share/${made.id}`);
  const tag = first.headers.get("etag");
  assert.match(tag, /^W\/"[0-9a-f]{20}"$/);
  const same = await call(env, "GET", `/api/share/${made.id}`, undefined, { "if-none-match": tag });
  assert.equal(same.status, 304);
  assert.equal(await same.text(), "");
  // Cloudflare may hand the tag back without W/.
  assert.equal((await call(env, "GET", `/api/share/${made.id}`, undefined, { "if-none-match": tag.slice(2) })).status, 304);

  // Sending the same schedule again changes nothing for them.
  const { ttlDays, ...auto } = sample;
  await new Promise((r) => setTimeout(r, 5));
  await call(env, "PUT", `/api/share/${made.id}`, auto, token);
  assert.equal((await call(env, "GET", `/api/share/${made.id}`, undefined, { "if-none-match": tag })).status, 304);

  // A changed day, or a longer link, is downloaded again.
  await call(env, "PUT", `/api/share/${made.id}`, { ...auto, days: [...sample.days, { date: "2026-11-04", type: 2 }] }, token);
  const changed = await call(env, "GET", `/api/share/${made.id}`, undefined, { "if-none-match": tag });
  assert.equal(changed.status, 200);
  assert.equal((await changed.json()).schedule.days.length, 4);
  const tag2 = changed.headers.get("etag");
  await call(env, "PUT", `/api/share/${made.id}`, { ...auto, days: [...sample.days, { date: "2026-11-04", type: 2 }], ttlDays: 90 }, token);
  assert.equal((await call(env, "GET", `/api/share/${made.id}`, undefined, { "if-none-match": tag2 })).status, 200);
});

test("asset links name the app only once a certificate is set", async () => {
  const none = await call({ SHARES: memoryKv() }, "GET", "/.well-known/assetlinks.json");
  assert.deepEqual(await none.json(), []);
  const print = Array(32).fill("AB").join(":");
  const set = await call({ SHARES: memoryKv(), ANDROID_CERT_SHA256: ` ${print.toLowerCase()} , nonsense` }, "GET", "/.well-known/assetlinks.json");
  const links = await set.json();
  assert.equal(links[0].target.package_name, "com.bamnat.app");
  assert.deepEqual(links[0].target.sha256_cert_fingerprints, [print]);
  assert.deepEqual(links[0].relation, ["delegate_permission/common.handle_all_urls"]);
});

// [SHR-01] What the app's ShareLinkTest wrote (app/build), when it ran first: the server takes the
// app's own payloads whole and keeps what the sharer ticked.
for (const name of ["share-payload.json", "share-payload-v2.json", "share-payload-field.json"]) {
  const file = new URL(`../../../app/build/${name}`, import.meta.url);
  test(`the app's ${name} passes`, { skip: !existsSync(file) && "run the app's ShareLinkTest first" }, () => {
    const body = JSON.parse(readFileSync(file, "utf8"));
    const out = validate(body);
    assert.equal(out.error, undefined, `error ${out.error}`);
    assert.equal(out.schedule.days.length, body.days.length);
    for (const key of ["memo", "extras", "rest", "jobs"]) {
      assert.equal(out.schedule.days.filter((d) => d[key] != null).length, body.days.filter((d) => d[key] != null).length, key);
    }
    assert.deepEqual(out.schedule.pay ?? [], body.pay ?? []);
  });
}

test("[FEAT-15] a link's calendar feed exists only when the sharer turned it on", async () => {
  const env = { SHARES: memoryKv() };
  const plain = await (await call(env, "POST", "/api/share", sample)).json();
  assert.equal((await call(env, "GET", `/s/${plain.id}.ics`)).status, 404);

  const made = await (await call(env, "POST", "/api/share", { ...sample, ics: true, tz: "Asia/Seoul" })).json();
  const res = await call(env, "GET", `/s/${made.id}.ics`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type"), /^text\/calendar/);
  const ics = await res.text();
  assert.match(ics, /^BEGIN:VCALENDAR\r\n/);
  assert.match(ics, /X-WR-CALNAME:민지의 근무/);
  // The night shift ends the next morning; the day off is left out; the leave is an all-day event.
  assert.match(ics, /UID:2026-11-02-t@[A-Za-z0-9]+\.bamnat\r\nDTSTAMP:\d{8}T\d{6}Z\r\nDTSTART;TZID=Asia\/Seoul:20261102T190000\r\nDTEND;TZID=Asia\/Seoul:20261103T070000/);
  assert.doesNotMatch(ics, /SUMMARY:휴무/);
  assert.match(ics, /DTSTART;VALUE=DATE:20261103\r\nDTEND;VALUE=DATE:20261104\r\nSUMMARY:연차/);
  assert.equal((ics.match(/BEGIN:VEVENT/g) ?? []).length, 4);
  // The page does not change, and the token never shows.
  assert.doesNotMatch(ics, new RegExp(made.deleteToken));

  // Turned off again by an update, the address goes away.
  await call(env, "PUT", `/api/share/${made.id}`, sample, { "x-delete-token": made.deleteToken });
  assert.equal((await call(env, "GET", `/s/${made.id}.ics`)).status, 404);
});

test("[FEAT-15] the feed refuses a time zone that is not one", () => {
  assert.equal(validate({ ...sample, ics: true, tz: "Asia/Seoul\r\nX-EVIL:1" }).error, "tz");
  assert.equal(validate({ ...sample, ics: "yes" }).error, "ics");
  assert.equal(validate({ ...sample, ics: true }).schedule.tz, "Asia/Seoul");
  assert.equal(validate({ ...sample, ics: true, tz: "America/Los_Angeles" }).schedule.tz, "America/Los_Angeles");
});

test("[PROV-04] links can stay open 180 days or a year", async () => {
  const env = { SHARES: memoryKv() };
  await call(env, "POST", "/api/share", { ...sample, ttlDays: 365 });
  assert.ok(env.SHARES.lastTtl > 364 * 86400 && env.SHARES.lastTtl <= 365 * 86400);
  assert.equal(validate({ ...sample, ttlDays: 180 }).ttlDays, 180);
  assert.equal(validate({ ...sample, ttlDays: 200 }).ttlDays, 30);
});

test("[FEAT-15] long names fold without splitting a character", () => {
  const ics = renderIcs({ ...sample, title: "가".repeat(30), ics: true, tz: "Asia/Seoul" }, "abcdefghijklmnop", null);
  for (const line of ics.split("\r\n")) assert.ok(new TextEncoder().encode(line).length <= 75, line);
  assert.match(ics, /X-WR-CALNAME:가+\r\n 가+/);
});
