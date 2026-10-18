// [MKT-09] Runs the Worker's fetch handler in Node with an in-memory KV: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import worker, { validate, renderSchedule } from "../src/index.js";

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
  assert.equal(validate({ ...sample, ttlDays: 365 }).ttlDays, 30);
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
for (const name of ["share-payload.json", "share-payload-v2.json"]) {
  const file = new URL(`../../../app/build/${name}`, import.meta.url);
  test(`the app's ${name} passes`, { skip: !existsSync(file) && "run the app's ShareLinkTest first" }, () => {
    const body = JSON.parse(readFileSync(file, "utf8"));
    const out = validate(body);
    assert.equal(out.error, undefined, `error ${out.error}`);
    assert.equal(out.schedule.days.length, body.days.length);
    for (const key of ["memo", "extras", "rest"]) {
      assert.equal(out.schedule.days.filter((d) => d[key] != null).length, body.days.filter((d) => d[key] != null).length, key);
    }
    assert.deepEqual(out.schedule.pay ?? [], body.pay ?? []);
  });
}
