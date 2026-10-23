// [SRV-04][SRV-05][SRV-06][STAT-01][STAT-03][STAT-04][COMM-09][COMM-10][ADMIN-13][ADMIN-14] Running the
// service (1.22): notices and switches, usage counts and the gate numbers, hiding a writer, 광장 소식,
// photos with a question, reported share links and the year's chores. `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker, { cleanJpeg, EVENTS } from "../src/index.js";
import { server, report, setup, device, from, signIn, ORIGIN } from "./helpers.mjs";

const plaza = () => server({ plaza: true });
const NOW = Date.parse("2026-10-22T03:00:00Z");

/** A small JPEG as a phone makes one, with an Exif block (where it was taken) and a comment in it. */
function jpeg({ exif = true } = {}) {
  const seg = (marker, body) => [0xff, marker, ((body.length + 2) >> 8) & 0xff, (body.length + 2) & 0xff, ...body];
  const text = (s) => [...new TextEncoder().encode(s)];
  return Uint8Array.from([
    0xff, 0xd8,
    ...seg(0xe0, text("JFIF\0\x01\x01\0\0\x01\0\x01\0\0")),
    ...(exif ? seg(0xe1, text("Exif\0\0GPS 37.5665N 126.9780E")) : []),
    ...seg(0xfe, text("taken at home")),
    ...seg(0xdb, Array(65).fill(1)),
    ...seg(0xc0, [8, 0, 1, 0, 1, 1, 1, 0x11, 0]),
    ...seg(0xda, [1, 1, 0, 0, 0x3f, 0]),
    0x12, 0x34, 0x56, 0xff, 0xd9,
  ]);
}
const b64 = (bytes) => Buffer.from(bytes).toString("base64");

test("[SRV-04] a notice shows only while it runs, and a switch stops the server's own part too", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = plaza();
  const admin = await signIn(call);
  const notice = { title: "서버 점검 안내", body: "10월 25일 새벽 2시부터 30분 동안 광장이 쉬어요.", level: "important",
    from: "2026-10-22T00:00:00Z", until: "2026-10-26T00:00:00Z", minApp: 33 };
  // What the form gets wrong is refused with the field.
  assert.equal((await call("POST", "/api/admin/notices", { ...notice, until: "2026-10-21T00:00:00Z" }, admin)).data.field, "until");
  assert.equal((await call("POST", "/api/admin/notices", { ...notice, level: "loud" }, admin)).data.field, "level");
  assert.equal((await call("POST", "/api/admin/notices", { ...notice, minApp: 34, maxApp: 33 }, admin)).data.field, "maxApp");
  const made = await call("POST", "/api/admin/notices", notice, admin);
  assert.equal(made.status, 200);
  const later = await call("POST", "/api/admin/notices", { ...notice, title: "다음 주 안내", from: "2026-10-28T00:00:00Z", until: "2026-10-30T00:00:00Z" }, admin);
  assert.deepEqual(later.data.notices.map((n) => n.state), ["later", "on"]);
  let config = (await call("GET", "/api/config")).data;
  assert.deepEqual(config.notices.map((n) => [n.title, n.level, n.minApp, n.maxApp]), [["서버 점검 안내", "important", 33, null]]);
  assert.deepEqual(config.off, []);
  // Changing one keeps its id; removing it takes it off every app.
  const id = config.notices[0].id;
  await call("POST", "/api/admin/notices", { ...notice, id, title: "서버 점검 안내(시간 바뀜)" }, admin);
  assert.equal((await call("GET", "/api/config")).data.notices[0].title, "서버 점검 안내(시간 바뀜)");
  t.mock.timers.tick(4 * 24 * 60 * 60 * 1000);
  assert.deepEqual((await call("GET", "/api/config")).data.notices, []);
  await call("POST", `/api/admin/notices/${id}/remove`, {}, admin);
  assert.equal((await call("GET", "/api/admin/notices", undefined, admin)).data.notices.length, 1);

  // Switching the plaza's writing off: the app hides it, and the server refuses it from any app.
  assert.equal((await call("POST", "/api/admin/switches", { key: "NOPE", off: true }, admin)).data.field, "key");
  await call("POST", "/api/admin/switches", { key: "PLAZA_WRITE", off: true }, admin);
  await call("POST", "/api/admin/switches", { key: "COMPANY_REPORT", off: true }, admin);
  config = (await call("GET", "/api/config")).data;
  assert.deepEqual(config.off, ["PLAZA_WRITE", "COMPANY_REPORT"]);
  const sent = await call("POST", "/api/reports", report(1), from(1));
  assert.deepEqual([sent.status, sent.data.error], [503, "paused"]);
  await call("POST", "/api/admin/switches", { key: "COMPANY_REPORT", off: false }, admin);
  const r = await call("POST", "/api/reports", report(1), from(1));
  assert.equal(r.status, 201);
  const posted = await call("POST", "/api/posts", { device: device(2), company: r.data.company.id, setup: r.data.setup, body: "주간 시간이 맞아요" }, from(2));
  assert.deepEqual([posted.status, posted.data.error], [503, "paused"]);
  // Each change is in the activity log.
  const log = (await call("GET", "/api/admin/audit", undefined, admin)).data.entries.map((e) => e.action);
  for (const a of ["notice_add", "notice_edit", "notice_remove", "switch_off", "switch_on"]) assert.ok(log.includes(a), a);
});

// The 문의 담당 preset from the admin app: 운영 보기, 지표 none.
const SUPPORT = { review: "view", inquiries: "edit", dev: "edit", devStatus: "none", companies: "view", rewards: "none", settings: "none", community: "view", takedown: "none", ops: "view", metrics: "none", shares: "view", audit: "none" };

test("[SRV-04] only admins given 운영 may change notices; a 보기 admin only reads them", async () => {
  const { call, DB } = plaza();
  const owner = await signIn(call);
  // An approved admin with 운영 보기 (the 문의 담당 preset).
  const invite = await call("POST", "/api/admin/invites", { email: "help@example.com", perms: SUPPORT }, owner);
  assert.equal(invite.status, 201);
  const token = invite.data.link.split("#")[1];
  await call("POST", "/api/admin/join", { token, name: "문의 담당", password: "a-long-password-123" }, { "x-admin": "1", origin: ORIGIN });
  const id = DB.db.prepare("SELECT id FROM admins").get().id;
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
  const login = await call("POST", "/api/admin/login", { email: "help@example.com", password: "a-long-password-123" }, { "x-admin": "1", origin: ORIGIN });
  const helper = { cookie: login.headers.get("set-cookie").split(";")[0], "x-admin": "1", origin: ORIGIN };
  assert.equal((await call("GET", "/api/admin/notices", undefined, helper)).status, 200);
  assert.equal((await call("POST", "/api/admin/switches", { key: "PLAZA_WRITE", off: true }, helper)).status, 403);
  assert.equal((await call("GET", "/api/admin/metrics", undefined, helper)).status, 403);
});

test("[STAT-01] usage counts come in as names and numbers per day, never with a phone or an address", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, DB } = plaza();
  const bundle = (days, extra = {}) => ({ v: 1, app: "1.22.0", days, ...extra });
  const day = { day: "2026-10-21", events: { open: 3, pay_view: 1, someday_feature: 2 }, recent: ["share_made", "made_up"] };
  assert.equal((await call("POST", "/api/events", bundle([day]))).status, 204);
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, events: { open: 3 } }]), from(2))).status, 204);
  // [STAT-05] What cannot be a day the phone kept is left out (the rest would count); a count it never made is refused.
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, day: "2026-10-24" }]), from(3))).data.skipped, 1);
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, day: "2026-09-01" }]), from(3))).data.skipped, 1);
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, day: "nope" }]), from(3))).data.error, "day");
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, events: { open: 5000 } }]), from(3))).data.error, "count");
  assert.equal((await call("POST", "/api/events", bundle([day], { app: "latest" }), from(3))).data.error, "app");
  const days = DB.db.prepare("SELECT * FROM usage_days").all().map((r) => ({ ...r }));
  assert.deepEqual(days, [{ day: "2026-10-21", app: "1.22.0", phones: 2 }]);
  const events = DB.db.prepare("SELECT event, phones, total, recent FROM usage_events ORDER BY event").all().map((r) => ({ ...r }));
  assert.deepEqual(events, [
    { event: "open", phones: 2, total: 6, recent: 2 },
    { event: "pay_view", phones: 1, total: 1, recent: 1 },
    { event: "share_made", phones: 0, total: 0, recent: 2 },
  ]);
  // Nothing that points back to a phone: no install id, no address, no time of day.
  assert.ok(!DB.dump().includes("203.0.113"));
  assert.ok(!/test-device/.test(DB.dump()));
  // [STAT-05] The same bundle sent again (a retry) counts once; a day twice in one bundle counts once.
  assert.deepEqual((await call("POST", "/api/events", bundle([{ ...day, events: { open: 3 } }]), from(2))).data, { ok: true, again: true });
  const twice = await call("POST", "/api/events", bundle([{ ...day, events: { open: 1 } }, { ...day, events: { open: 1 } }]), from(3));
  assert.equal(twice.data.skipped, 1);
  assert.equal(DB.db.prepare("SELECT phones FROM usage_days").get().phones, 3);
  // [STAT-05] Many phones behind one carrier address: 120 bundles a day, then no more.
  for (let i = 0; i < 119; i++) await call("POST", "/api/events", bundle([{ ...day, events: { open: i + 10 } }]), from(9));
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, events: { open: 500 } }]), from(9))).status, 204);
  assert.equal((await call("POST", "/api/events", bundle([{ ...day, events: { open: 501 } }]), from(9))).status, 429);
});

test("[STAT-01] switched off, the server takes nothing; day totals go after 400 days", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, DB, env } = plaza();
  const admin = await signIn(call);
  await call("POST", "/api/admin/switches", { key: "USAGE_STATS", off: true }, admin);
  assert.equal((await call("POST", "/api/events", { v: 1, app: "1.22.0", days: [{ day: "2026-10-21", events: { open: 1 } }] })).status, 204);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM usage_days").get().n, 0);
  DB.db.prepare("INSERT INTO usage_days VALUES ('2025-09-01', '1.13.0', 5), ('2025-10-01', '1.14.0', 7)").run();
  await worker.scheduled({}, env, { waitUntil: (p) => p });
  await new Promise((r) => setTimeout(r, 50));
  assert.deepEqual(DB.db.prepare("SELECT day FROM usage_days").all().map((r) => r.day), ["2025-10-01"]);
});

test("[STAT-01] the app and the server know the same event names", () => {
  const kotlin = readFileSync(new URL("../../../core/src/main/kotlin/com/bamnat/core/stats/UsageEvents.kt", import.meta.url), "utf8");
  const app = [...kotlin.matchAll(/^\s+[A-Z_]+\("([a-z_]+)"\)/gm)].map((m) => m[1]).sort();
  assert.deepEqual(app, Object.keys(EVENTS).sort());
});

test("[ADMIN-13][STAT-03][STAT-04] the gate numbers: shares of phones, the funnel, and 데이터 부족 under 30 a day", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, DB } = plaza();
  const admin = await signIn(call);
  let m = (await call("GET", "/api/admin/metrics", undefined, admin)).data;
  assert.equal(m.enough, false);
  assert.ok(m.gates.filter((g) => g.gate !== "G0").every((g) => g.status === "few"));
  assert.ok(m.gates.filter((g) => g.gate === "G0").every((g) => g.status === "play"));
  assert.equal(m.shares, null);
  // 28 days of 100 phones: 40 saw pay within 28 days, 10 made a link, 3 subscribe.
  const ins = DB.db.prepare("INSERT INTO usage_days VALUES (?, '1.22.0', 100)");
  const ev = DB.db.prepare("INSERT INTO usage_events VALUES (?, '1.22.0', ?, ?, ?, ?)");
  for (let d = 1; d <= 28; d++) {
    const day = new Date(NOW + 9 * 3600e3 - d * 86400e3).toISOString().slice(0, 10);
    ins.run(day);
    ev.run(day, "pay_view", 20, 25, 40);
    ev.run(day, "share_made", 2, 2, 10);
    ev.run(day, "pro_sub", 3, 3, 3);
    ev.run(day, "paywall_view", 5, 6, 9);
  }
  DB.db.prepare(`INSERT INTO subs (hash, device, token, product, state, expiry, trial, auto_renew, created_at, checked_at)
    VALUES ('h1', 'd1', 't', 'bamnat_pro', 'ACTIVE', ?, 1, 1, ?, ?), ('h2', 'd2', 't', 'bamnat_pro', 'ACTIVE', ?, 0, 0, ?, ?)`)
    .run(NOW + 86400e3, NOW, NOW, NOW + 86400e3, NOW, NOW);
  m = (await call("GET", "/api/admin/metrics?days=28", undefined, admin)).data;
  assert.deepEqual([m.daily, m.enough, m.phoneDays], [100, true, 2800]);
  const g = Object.fromEntries(m.gates.map((x) => [x.name, [x.value, x.status]]));
  assert.deepEqual(g["급여 화면을 본 사용자"], [40, "pass"]);
  assert.deepEqual(g["근무표 링크를 만든 사용자"], [10, "fail"]);
  assert.deepEqual(g["유료 구독 사용자"], [3, "pass"]);
  assert.deepEqual([m.funnel.paywall, m.funnel.trials, m.funnel.paid, m.funnel.ending], [140, 1, 1, 1]);
  assert.equal(m.events.find((e) => e.event === "pay_view").label, "급여 화면");
});

test("[COMM-09] 이 사람 글 안 보기: the phone's own list hides a writer everywhere, and no two posts' marks match", async () => {
  const { call } = plaza();
  const a = await call("POST", "/api/reports", report(1), from(1));
  const b = await call("POST", "/api/reports", report(1, { company: "가나다화학" }), from(1));
  const talks = [a.data, b.data].map((r) => ({ c: r.company.id, g: r.setup }));
  const say = (n, t, body, more = {}) => call("POST", "/api/posts", { device: device(n), company: t.c, setup: t.g, body, ...more }, from(n));
  const read = (n, t, hide) => call("GET", `/api/companies/${t.c}/setups/${t.g}/posts`, undefined, { "x-device": device(n), ...(hide ? { "x-hide": hide } : {}) });
  const rude = (await say(2, talks[0], "이 회사 규칙은 엉터리예요")).data.id;
  await say(3, talks[0], "저는 맞다고 봐요", { parent: rude });
  await say(3, talks[0], "야간은 22시부터예요");
  await say(2, talks[1], "여기도 엉터리예요");
  const first = (await read(1, talks[0])).data.posts;
  const mark = first.find((p) => p.id === rude).author;
  assert.match(mark, /^[A-Za-z0-9_-]{64}$/);
  // The same writer's marks differ post by post, and between two reads.
  const again = (await read(1, talks[0])).data.posts.find((p) => p.id === rude).author;
  assert.notEqual(mark, again);
  // Hidden here, and in the other company's talk too; the reply under the hidden post goes with it.
  const hidden = (await read(1, talks[0], mark)).data;
  assert.deepEqual(hidden.posts.map((p) => p.body), ["야간은 22시부터예요"]);
  assert.equal(hidden.hiddenByYou, 1);
  assert.equal((await read(1, talks[1], mark)).data.posts.length, 0);
  // A mark this server did not make, or too many marks, changes nothing beyond the first fifty.
  assert.equal((await read(1, talks[0], "x".repeat(64))).data.posts.length, 3);
  assert.equal((await read(1, talks[0], [...Array(50).fill("y".repeat(64)), mark].join(","))).data.posts.length, 3);
  // A phone's own posts carry no mark (nobody hides themselves).
  assert.equal((await read(2, talks[0])).data.posts.find((p) => p.id === rude).author, undefined);
});

test("[COMM-10] 광장 소식: my rule changed, my rule set and my proposal adopted, only since I last asked", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = plaza();
  const news = (n, since) => call("GET", `/api/plaza/news${since ? `?since=${since}` : ""}`, undefined, { "x-device": device(n) });
  assert.equal((await call("GET", "/api/plaza/news")).status, 400);
  const a = await call("POST", "/api/reports", report(1), from(1));
  const c = a.data.company.id;
  const g = a.data.setup;
  await call("POST", "/api/reports", report(2), from(2));
  t.mock.timers.tick(60_000);
  await call("POST", "/api/reports", report(3), from(3));
  let n1 = (await news(1)).data;
  assert.deepEqual(n1.items.map((i) => [i.kind, i.company.name, i.version]), [["adopted", "예시정밀", 1]]);
  // Phone 9 uses the rule; a proposal three people agree on replaces it.
  await call("POST", "/api/uses", { device: device(9), company: c, setup: g }, from(9));
  const since = (await news(9)).data.now;
  assert.deepEqual((await news(9, since)).data.items, []);
  t.mock.timers.tick(60_000);
  const better = setup();
  better.shiftTypes[0].end = "18:00";
  const change = [{ field: "shift", now: "주간 07:00~19:00", want: "주간 07:00~18:00", why: "10월부터 한 시간 줄었어요" }];
  const p = await call("POST", "/api/posts", { device: device(4), company: c, setup: g, body: "주간이 바뀌었어요", kind: "proposal", change, proposal: better }, from(4));
  for (const v of [5, 6]) await call("POST", `/api/posts/${p.data.id}/vote`, { device: device(v), ok: true }, from(v));
  const n9 = (await news(9, since)).data.items;
  assert.deepEqual(n9.map((i) => [i.kind, i.version]), [["changed", 2]]);
  assert.deepEqual((await news(4, since)).data.items.map((i) => i.kind), ["proposal"]);
  // Another phone hears nothing of it, and once asked after it, phone 9 does not hear it again.
  assert.deepEqual((await news(7, since)).data.items, []);
  assert.deepEqual((await news(9, (await news(9, since)).data.now)).data.items, []);
});

test("[SRV-06] photos go with a question without what says where they were taken, for its phone and admins only", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, DB, env } = plaza();
  const ask = (n, photos) => call("POST", "/api/inquiries", { v: 1, device: device(n), kind: "bug", text: "화면이 이렇게 나와요", photos }, from(n));
  assert.equal((await ask(1, [b64(jpeg()), b64(jpeg()), b64(jpeg())])).data.error, "photos");
  assert.equal((await ask(1, ["bm90IGEganBlZw=="])).data.error, "photo");
  assert.equal((await ask(1, [b64(new Uint8Array(800 * 1024).fill(0xff))])).data.error, "photo");
  const res = await ask(1, [b64(jpeg()), b64(jpeg({ exif: false }))]);
  assert.equal(res.status, 201);
  assert.equal(res.data.photos, 2);
  const stored = Buffer.from(DB.db.prepare("SELECT data FROM inquiry_photos WHERE n = 1").get().data, "base64");
  assert.ok(!stored.toString("latin1").includes("GPS"));
  assert.ok(!stored.toString("latin1").includes("taken at home"));
  assert.deepEqual(cleanJpeg(jpeg({ exif: false })).length, stored.length);
  // Its phone reads the photo; another phone does not, nor anyone on 모두의 문의.
  const get = (path, headers) => worker.fetch(new Request(`${ORIGIN}${path}`, { headers }), env);
  const mine = await get(`/api/inquiries/${res.data.id}/photos/1`, { "x-device": device(1) });
  assert.equal(mine.headers.get("content-type"), "image/jpeg");
  assert.equal((await mine.arrayBuffer()).byteLength, stored.length);
  assert.equal((await get(`/api/inquiries/${res.data.id}/photos/1`, { "x-device": device(2) })).status, 404);
  const admin = await signIn(call);
  assert.equal((await get(`/api/admin/inquiries/${res.data.id}/photos/2`, { cookie: admin.cookie })).headers.get("content-type"), "image/jpeg");
  assert.equal((await call("GET", `/api/admin/inquiries/${res.data.id}`, undefined, admin)).data.photos, 2);
  // Switched off, the words still go and the app is told why the photos did not.
  await call("POST", "/api/admin/switches", { key: "INQUIRY_PHOTOS", off: true }, admin);
  const off = await ask(2, [b64(jpeg())]);
  assert.deepEqual([off.status, off.data.photos, off.data.photosSkipped], [201, 0, "paused"]);
  // Closed, the photos go 90 days later; the words stay until the question does.
  await call("POST", `/api/admin/inquiries/${res.data.id}/status`, { status: "closed" }, admin);
  DB.db.prepare("UPDATE inquiries SET updated_at = ? WHERE id = ?").run(NOW - 91 * 86400e3, res.data.id);
  await worker.scheduled({}, env, { waitUntil: (p) => p });
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiry_photos").get().n, 0);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiries").get().n, 2);
});

test("[ADMIN-14] 이달 할 일 follows docs/ops-calendar.md month by month, and ticks are kept per month", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = plaza();
  const admin = await signIn(call);
  const doc = readFileSync(new URL("../../../docs/ops-calendar.md", import.meta.url), "utf8");
  const docKeys = [...doc.matchAll(/^\| (\d{1,2})월 \| `([a-z-]+)` \|/gm)].map((m) => `${m[1]}:${m[2]}`);
  const server = [];
  for (let month = 1; month <= 12; month++) {
    const m = `2026-${String(month).padStart(2, "0")}`;
    for (const i of (await call("GET", `/api/admin/ops?month=${m}`, undefined, admin)).data.items) server.push(`${month}:${i.key}`);
  }
  assert.deepEqual(server, docKeys);
  let october = (await call("GET", "/api/admin/ops", undefined, admin)).data;
  assert.equal(october.month, "2026-10");
  assert.equal((await call("GET", "/api/admin/overview", undefined, admin)).data.opsLeft, october.items.length);
  october = (await call("POST", "/api/admin/ops", { month: "2026-10", key: october.items[0].key, done: true }, admin)).data;
  assert.equal(october.items[0].done, true);
  assert.equal((await call("GET", "/api/admin/overview", undefined, admin)).data.opsLeft, october.items.length - 1);
  assert.equal((await call("POST", "/api/admin/ops", { month: "2026-10", key: "pension-apply", done: true }, admin)).data.field, "key");
});

test("[SRV-05] reported share links come from the share server, and taking one down calls it", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls.push([init.method, String(url), init.headers.authorization]);
    if (String(url).endsWith("/api/admin/reports")) {
      return Response.json({ reports: [{ id: "AbCdEfGhJkLmNpQrStUvWx", count: 3, reasons: { abuse: 2, personal: 1 }, last: "2026-10-22T01:00:00.000Z" }] });
    }
    if (String(url).includes("/api/admin/stats")) return Response.json({ days: [], created: 4, views: 30 });
    return new Response(null, { status: 204 });
  });
  const { call } = plaza();
  const admin = await signIn(call);
  // Not connected yet: the screen says so and nothing is called.
  assert.deepEqual((await call("GET", "/api/admin/shares", undefined, admin)).data, { connected: false, reports: [] });
  assert.equal(calls.length, 0);
  const linked = server({ plaza: true, SHARE_BASE_URL: "https://bamnat-share.example.workers.dev/", SHARE_ADMIN_TOKEN: "a-shared-secret-of-thirty-two-chars" });
  const owner = await signIn(linked.call);
  const list = (await linked.call("GET", "/api/admin/shares", undefined, owner)).data;
  assert.equal(list.connected, true);
  assert.equal(list.reports[0].url, "https://bamnat-share.example.workers.dev/s/AbCdEfGhJkLmNpQrStUvWx");
  await linked.call("POST", "/api/admin/shares/AbCdEfGhJkLmNpQrStUvWx/remove", { reason: "개인정보가 보여요" }, owner);
  assert.deepEqual(calls.find(([m]) => m === "DELETE"), ["DELETE", "https://bamnat-share.example.workers.dev/api/admin/share/AbCdEfGhJkLmNpQrStUvWx",
    "Bearer a-shared-secret-of-thirty-two-chars"]);
  assert.equal((await linked.call("GET", "/api/admin/metrics", undefined, owner)).data.shares.created, 4);
  assert.ok((await linked.call("GET", "/api/admin/audit", undefined, owner)).data.entries.some((e) => e.action === "share_remove"));
});
