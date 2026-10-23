// [ADMIN-15][ADMIN-16][ADMIN-17][ADMIN-19] The developer app's server in 1.23: sign-in, sessions, lists a
// page at a time, many questions at once, saved answers, the activity log, the home screen and web push.
// `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { prune } from "../src/index.js";
import { adminBuild } from "../src/admin-app.js";
import { server, device, from, signIn, report, ORIGIN, PASSWORD } from "./helpers.mjs";

const APP = { "x-admin": "1", origin: ORIGIN };
const DAY = 24 * 60 * 60 * 1000;
const SUPPORT = { review: "none", inquiries: "edit", dev: "edit", devStatus: "none", companies: "none", rewards: "none", settings: "none",
  community: "none", takedown: "none", ops: "none", metrics: "none", shares: "none", audit: "none" };

/** The owner invites [email] with [perms]; returns the approved admin's id and headers. */
async function member(call, owner, email = "minji@example.com", perms = SUPPORT, name = "민지", ip = 40) {
  const inv = await call("POST", "/api/admin/invites", { email, perms }, owner);
  assert.equal(inv.status, 201, inv.text);
  const token = inv.data.link.split("#")[1];
  assert.equal((await call("POST", "/api/admin/join", { token, name, password: "minji-answers-2026" }, { ...APP, ...from(ip) })).status, 201);
  const id = (await call("GET", "/api/admin/admins", undefined, owner)).data.admins.find((a) => a.email === email).id;
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
  const res = await call("POST", "/api/admin/login", { email, password: "minji-answers-2026" }, { ...APP, ...from(ip) });
  assert.equal(res.status, 200, res.text);
  return { id, headers: { cookie: res.headers.get("set-cookie").split(";")[0], ...APP } };
}

const question = (n, change = {}) => ({
  v: 1, device: device(n), kind: "bug", text: `알람이 늦게 울려요 ${n}번째`, info: { app: "1.22.0 (33)", model: "samsung SM-F956N" }, ...change,
});

/** [n] questions written straight into the database (the app's own limits allow five a day per phone). */
function seedInquiries(DB, n, { kind = "bug", start = Date.now() - n * 60000, app = "1.22.0 (33)", text = (i) => `알람이 늦게 울려요 ${i}번째` } = {}) {
  const ids = [];
  for (let i = 0; i < n; i++) {
    const id = `q${String(i).padStart(21, "0")}`;
    const at = start + i * 60000;
    DB.db.prepare("INSERT INTO inquiries (id, device, kind, info, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'open', ?, ?)")
      .run(id, `h${i}`, typeof kind === "function" ? kind(i) : kind, JSON.stringify({ app }), at, at);
    DB.db.prepare("INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'user', ?, NULL, ?)").run(id, text(i), at);
    ids.push(id);
  }
  return ids;
}

// ---- 290: sign-in, health, sessions, the log ----

test("[ADMIN-15] fifty wrong guesses from other networks do not lock the owner out of a clean one", async () => {
  const { call } = server();
  for (let n = 1; n <= 50; n++) {
    const res = await call("POST", "/api/admin/login", { password: `guess-${n}-wrong` }, { ...APP, ...from(n) });
    assert.equal(res.status, 401);
  }
  // A network that guessed wrong this hour is shut out now, even on its first miss.
  const guesser = await call("POST", "/api/admin/login", { password: PASSWORD }, { ...APP, ...from(7) });
  assert.equal(guesser.status, 429);
  // The owner on a network with no misses gets in.
  const owner = await call("POST", "/api/admin/login", { password: PASSWORD }, { ...APP, ...from(200) });
  assert.equal(owner.status, 200);
});

test("[ADMIN-15] the tried email is kept only as a short hash, and only for 30 days", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call, DB } = server();
  await call("POST", "/api/admin/login", { email: "someone@example.com", password: "not-the-password" }, APP);
  const row = DB.db.prepare("SELECT target FROM audit WHERE action = 'login_failed'").get();
  assert.match(row.target, /^mail:[0-9a-f]{10}$/);
  assert.ok(!DB.dump().includes("someone@example.com"));
  t.mock.timers.tick(31 * DAY);
  await call("POST", "/api/admin/login", { password: "not-the-password" }, { ...APP, ...from(3) });
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM audit WHERE action = 'login_failed'").get().n, 1, "the old one is gone");
});

test("[ADMIN-15] the login screen learns only what it needs; the owner sees the services", async () => {
  // (With mail set up, test/accounts.test.mjs reads mail: true here.)
  const { call } = server({ SHARE_BASE_URL: "https://share.example", SHARE_ADMIN_TOKEN: "t".repeat(32) });
  assert.deepEqual(Object.keys((await call("GET", "/api/admin/health")).data).sort(), ["database", "password", "salt", "why"]);
  const owner = await signIn(call);
  const full = (await call("GET", "/api/admin/server", undefined, owner)).data;
  assert.deepEqual([full.mail, full.play, full.share, full.push], [false, false, true, false]);
  const { headers } = await member(call, owner);
  assert.equal((await call("GET", "/api/admin/server", undefined, headers)).status, 403);
});

test("[ADMIN-15] me says which areas are yes or no and which are look only", async () => {
  const { call } = server();
  const me = (await call("GET", "/api/admin/me", undefined, await signIn(call))).data;
  assert.deepEqual(me.toggles, ["devStatus", "takedown"]);
  assert.deepEqual(me.viewOnly, ["audit"]);
});

test("[ADMIN-15] an admin change writes one line; the hourly clean-up deletes the old ones", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call, DB, env } = server();
  const owner = await signIn(call);
  const statements = [];
  const { prepare } = DB;
  DB.prepare = (sql) => (statements.push(sql), prepare(sql));
  await call("POST", "/api/admin/templates", { label: "인사", text: "안녕하세요" }, owner);
  DB.prepare = prepare;
  assert.equal(statements.filter((s) => /audit/.test(s)).length, 1, "one statement for the log");
  DB.db.prepare("INSERT INTO audit (at, action, target, detail, actor, actor_id) VALUES (?, 'settings', '', '', 'x', '')").run(Date.now() - 400 * DAY);
  await prune(env, Date.now());
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM audit WHERE at < ?").get(Date.now() - 365 * DAY).n, 0);
});

test("[ADMIN-15] the invite link is read with the token in the body", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const inv = await call("POST", "/api/admin/invites", { email: "junho@example.com", perms: SUPPORT }, owner);
  const token = inv.data.link.split("#")[1];
  const info = await call("POST", "/api/admin/join/info", { token }, APP);
  assert.equal(info.status, 200);
  assert.equal(info.data.email, "junho@example.com");
  assert.equal((await call("POST", "/api/admin/join/info", { token: "x".repeat(32) }, APP)).status, 404);
  // The old address still answers, for an app cached from before.
  assert.equal((await call("GET", `/api/admin/join?t=${token}`)).status, 200);
});

test("[ADMIN-15] the same answer sent twice within a minute reaches the asker once", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const q = await call("POST", "/api/inquiries", question(1));
  const send = () => call("POST", `/api/admin/inquiries/${q.data.id}/reply`, { text: "확인해 볼게요" }, owner);
  await send();
  await send();
  let view = (await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data;
  assert.equal(view.messages.filter((m) => m.from === "admin").length, 1);
  t.mock.timers.tick(61 * 1000);
  await send();
  view = (await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data;
  assert.equal(view.messages.filter((m) => m.from === "admin").length, 2, "a minute later it is a new answer");
});

test("[ADMIN-15] a 거절 can be taken back for ten minutes", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const a = await call("POST", "/api/reports", report(1), from(1));
  const group = `/api/admin/groups/${a.data.company.id}/${a.data.setup}`;
  await call("POST", `${group}/reject`, { reason: "wrong" }, owner);
  assert.equal((await call("GET", "/api/admin/queue", undefined, owner)).data.groups.length, 0);
  t.mock.timers.tick(9 * 60 * 1000);
  const back = await call("POST", `${group}/reopen`, {}, owner);
  assert.deepEqual(back.data, { ok: true, reopened: 1 });
  assert.equal((await call("GET", "/api/admin/queue", undefined, owner)).data.groups.length, 1);
  assert.equal((await call("GET", `/api/reports/${a.data.id}`, undefined, { "x-device": device(1) })).data.status, "pending");
  await call("POST", `${group}/reject`, { reason: "wrong" }, owner);
  t.mock.timers.tick(11 * 60 * 1000);
  assert.deepEqual((await call("POST", `${group}/reopen`, {}, owner)).data, { error: "too_late" });
  const log = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries;
  assert.ok(log.some((e) => e.action === "reject_undo"));
});

test("[ADMUX-08] the home screen carries the app's build and the latest activity without sign-ins", async () => {
  const { call } = server();
  const owner = await signIn(call);
  await call("POST", "/api/admin/templates", { label: "인사", text: "안녕하세요" }, owner);
  const o = (await call("GET", "/api/admin/overview", undefined, owner)).data;
  assert.equal(o.build, adminBuild());
  assert.deepEqual(o.recent.map((r) => r.action), ["template_add"]);
  assert.equal(o.doneToday, 1);
});

test("[ADMIN-16] an unchanged screen is a 304 with no body", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const first = await call("GET", "/api/admin/companies?q=", undefined, owner);
  const tag = first.headers.get("etag");
  assert.match(tag, /^"[0-9a-f]{27}"$/);
  const again = await call("GET", "/api/admin/companies?q=", undefined, { ...owner, "if-none-match": tag });
  assert.equal(again.status, 304);
  assert.equal(again.text, "");
  await call("POST", "/api/admin/companies", { name: "새회사정밀" }, owner);
  assert.equal((await call("GET", "/api/admin/companies?q=", undefined, { ...owner, "if-none-match": tag })).status, 200);
});

// ---- 291: lists, many at once, saved answers, the log ----

test("[ADMIN-16] 120 questions come 50, 50 and 20, none twice; topics count all of them", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  seedInquiries(DB, 120, { kind: (i) => (i % 3 === 0 ? "question" : "bug"), text: (i) => (i % 2 ? `알람이 늦어요 ${i}` : `급여 계산이 달라요 ${i}`) });
  const seen = [];
  let next = 0;
  const sizes = [];
  while (next !== null) {
    const page = (await call("GET", `/api/admin/inquiries?status=open${next ? `&after=${next}` : ""}`, undefined, owner)).data;
    sizes.push(page.inquiries.length);
    seen.push(...page.inquiries.map((i) => i.id));
    assert.equal(page.total, 120);
    next = page.next;
    const sum = Object.values(page.topics).reduce((a, b) => a + b, 0);
    assert.equal(sum, 120, "topics are counted before the page and the topic filter");
  }
  assert.deepEqual(sizes, [50, 50, 20]);
  assert.equal(new Set(seen).size, 120);
  // 놓친 것 fixes the status to waiting, whatever was asked.
  const late = (await call("GET", "/api/admin/inquiries?status=closed&late=1", undefined, owner)).data;
  assert.equal(late.status, "open");
  assert.equal(late.late, true);
});

test("[ADMIN-16] the questions list sorts by the answer target, oldest, newest or 나도 그래요", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  const now = Date.now();
  // A question asked 20 hours ago (target 24) and a problem report 6 hours ago (target 8): the report is closer.
  seedInquiries(DB, 1, { kind: "question", start: now - 20 * 3600000 });
  DB.db.prepare("UPDATE inquiries SET id = 'qquestion0000000000000' WHERE id = ?").run("q000000000000000000000");
  DB.db.prepare("UPDATE inquiry_messages SET inquiry_id = 'qquestion0000000000000'").run();
  seedInquiries(DB, 1, { kind: "bug", start: now - 6 * 3600000 });
  const list = (await call("GET", "/api/admin/inquiries?sort=sla", undefined, owner)).data;
  assert.deepEqual(list.inquiries.map((i) => i.kind), ["bug", "question"]);
  assert.ok(Math.abs(list.inquiries[0].slaLeft - 120) <= 1, "two hours left of eight");
  assert.deepEqual(list.sla, { slaBug: 8, slaQuestion: 24, slaIdea: 72, slaOther: 72 });
  assert.deepEqual((await call("GET", "/api/admin/inquiries?sort=oldest", undefined, owner)).data.inquiries.map((i) => i.kind), ["question", "bug"]);
  assert.deepEqual((await call("GET", "/api/admin/inquiries?sort=newest", undefined, owner)).data.inquiries.map((i) => i.kind), ["bug", "question"]);
  // 답 목표 changes in 설정; out of range is refused with the key.
  assert.deepEqual((await call("POST", "/api/admin/settings/sla", { slaBug: 4 }, owner)).data.sla, { slaBug: 4, slaQuestion: 24, slaIdea: 72, slaOther: 72 });
  assert.deepEqual((await call("POST", "/api/admin/settings/sla", { slaIdea: 200 }, owner)).data, { error: "sla", field: "slaIdea" });
  const late = (await call("GET", "/api/admin/inquiries?sort=sla", undefined, owner)).data.inquiries[0];
  assert.ok(late.slaLeft < 0, "past the new four hours");
});

test("[ADMIN-16] 개발사항, companies, posts, rewards and rates come a page at a time", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  const now = Date.now();
  for (let i = 0; i < 60; i++) {
    DB.db.prepare(`INSERT INTO dev_items (id, title, body, status, created_by, created_by_name, created_at, updated_at)
      VALUES (?, ?, '', 'todo', 'owner', '최고 관리자', ?, ?)`).run(`d${String(i).padStart(15, "0")}`, `항목 ${i}`, now + i, now + i);
    DB.db.prepare("INSERT INTO companies (id, name, norm, created_at) VALUES (?, ?, ?, ?)").run(`c${String(i).padStart(11, "0")}`, `회사${i}`, `회사${i}`, now + i);
  }
  const dev1 = (await call("GET", "/api/admin/dev?status=open", undefined, owner)).data;
  const dev2 = (await call("GET", `/api/admin/dev?status=open&after=${dev1.next}`, undefined, owner)).data;
  assert.deepEqual([dev1.items.length, dev1.next, dev2.items.length, dev2.next, dev1.total], [50, 50, 10, null, 60]);
  const c1 = (await call("GET", "/api/admin/companies?q=&limit=40", undefined, owner)).data;
  const c2 = (await call("GET", `/api/admin/companies?q=&limit=40&after=${c1.next}`, undefined, owner)).data;
  assert.deepEqual([c1.companies.length, c1.next, c2.companies.length, c2.next], [40, 40, 20, null]);
  assert.equal(new Set([...c1.companies, ...c2.companies].map((c) => c.id)).size, 60);
  for (const path of ["/api/admin/posts?status=all", "/api/admin/rewards?status=owed", "/api/admin/rates?status=review"]) {
    const res = (await call("GET", path, undefined, owner)).data;
    assert.equal(res.next, null, path);
  }
});

test("[ADMIN-16] a company's name finds its posts and rewards; a trade finds its rates", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const a = await call("POST", "/api/reports", report(1, { company: "가나정밀" }), from(1));
  await call("POST", "/api/reports", report(2, { company: "가나정밀" }), from(2));
  await call("POST", "/api/reports", report(3, { company: "다라전자" }), from(3));
  await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/adopt`, {}, owner);
  const all = (await call("GET", "/api/admin/rewards?status=owed", undefined, owner)).data.rewards;
  assert.ok(all.length >= 1);
  assert.equal((await call("GET", "/api/admin/rewards?status=owed&q=가나", undefined, owner)).data.rewards.length, all.length);
  assert.equal((await call("GET", "/api/admin/rewards?status=owed&q=다라", undefined, owner)).data.rewards.length, 0);
  assert.equal((await call("GET", "/api/admin/posts?status=all&q=없는회사", undefined, owner)).data.posts.length, 0);
  assert.equal((await call("GET", "/api/admin/rates?status=review&q=용접", undefined, owner)).status, 200);
});

test("[ADMIN-16] many questions at once: take, answer each, close, link; one line in the log", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  const { id: minji, headers } = await member(call, owner);
  const ids = seedInquiries(DB, 5);
  // Minji takes two; the owner cannot let them go for her in bulk… but may, being the owner.
  assert.deepEqual((await call("POST", "/api/admin/inquiries/bulk", { action: "assign", ids: ids.slice(0, 2) }, headers)).data, { done: 2, skipped: 0 });
  const { headers: junho } = await member(call, owner, "junho@example.com", SUPPORT, "준호", 41);
  assert.deepEqual((await call("POST", "/api/admin/inquiries/bulk", { action: "unassign", ids }, junho)).data, { done: 3, skipped: 2 },
    "someone else's are skipped");
  const reply = await call("POST", "/api/admin/inquiries/bulk", { action: "reply", ids, text: "고쳤어요. 업데이트해 주세요", close: true }, headers);
  assert.deepEqual(reply.data, { done: 5, skipped: 0 });
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiry_messages WHERE sender = 'admin'").get().n, 5, "an answer in each");
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiries WHERE status = 'closed'").get().n, 5);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiries WHERE assignee = ?").get(minji).n, 5, "answering takes the free ones");
  const item = await call("POST", "/api/admin/dev", { title: "알람 늦음" }, headers);
  assert.deepEqual((await call("POST", "/api/admin/inquiries/bulk", { action: "link", ids, item: item.data.id }, headers)).data, { done: 5, skipped: 0 });
  assert.equal((await call("GET", `/api/admin/dev/${item.data.id}`, undefined, headers)).data.inquiries.length, 5);
  const log = DB.db.prepare("SELECT action, detail FROM audit WHERE action = 'inquiry_bulk' ORDER BY id").all();
  assert.deepEqual(log.map((r) => r.detail), ["assign 2", "unassign 3 (2 건너뜀)", "reply 5", "link 5"]);
  // Limits: at most 100, known actions, and 문의 고치기.
  assert.equal((await call("POST", "/api/admin/inquiries/bulk", { action: "close", ids: Array.from({ length: 101 }, (_, i) => `x${String(i).padStart(21, "0")}`) }, headers)).data.error, "ids");
  assert.equal((await call("POST", "/api/admin/inquiries/bulk", { action: "delete", ids }, headers)).data.error, "action");
  const viewer = await member(call, owner, "viewer@example.com", { ...SUPPORT, inquiries: "view" }, "보기", 42);
  assert.equal((await call("POST", "/api/admin/inquiries/bulk", { action: "close", ids }, viewer.headers)).status, 403);
});

test("[ADMIN-16] saved answers: changed in place, a topic, uses counted, the question's topic first", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const q = await call("POST", "/api/inquiries", question(1, { text: "알람이 안 울려요" }));
  let view = (await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data;
  assert.ok(view.templates.length >= 4, "the saved answers come with the question");
  const topic = view.topic;
  const added = await call("POST", "/api/admin/templates", { label: "알람 안내", text: "{앱버전}에서 고쳤어요", topic }, owner);
  const mine = added.data.templates.find((x) => x.label === "알람 안내");
  assert.equal(mine.topic, topic);
  view = (await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data;
  assert.equal(view.templates[0].id, mine.id, "its topic first");
  const edited = await call("POST", `/api/admin/templates/${mine.id}`, { label: "알람 고침", text: "{앱버전}에서 고쳤어요. 업데이트해 주세요", topic: "" }, owner);
  assert.equal(edited.data.templates.find((x) => x.id === mine.id).label, "알람 고침");
  assert.equal((await call("POST", `/api/admin/templates/${mine.id}`, { label: "x", text: "y", topic: "nope" }, owner)).data.error, "topic");
  await call("POST", `/api/admin/inquiries/${q.data.id}/reply`, { text: "1.22.0에서 고쳤어요", template: mine.id }, owner);
  const after = (await call("GET", "/api/admin/templates", undefined, owner)).data.templates.find((x) => x.id === mine.id);
  assert.equal(after.uses, 1);
});

test("[ADMIN-17] the activity log filters by who, kind and day, and names what each line is about", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const { id: minji, headers } = await member(call, owner, "minji@example.com", { ...SUPPORT, companies: "edit" });
  const q = await call("POST", "/api/inquiries", question(1, { text: "알람이 안 울려요 잠금 화면에서" }));
  await call("POST", `/api/admin/inquiries/${q.data.id}/assign`, { to: "me" }, headers);
  const c = await call("POST", "/api/admin/companies", { name: "예시정밀" }, headers);
  await call("POST", `/api/admin/companies/${c.data.id}`, { name: "예시정밀 주식회사" }, headers);
  t.mock.timers.tick(DAY);
  await call("POST", "/api/admin/templates", { label: "인사", text: "안녕하세요" }, owner);
  const byMinji = (await call("GET", `/api/admin/audit?actor=${minji}`, undefined, owner)).data;
  // Her own sign-up has no id yet; everything after does.
  assert.deepEqual(byMinji.entries.map((e) => e.action).sort(), ["company_create", "company_update", "inquiry_assign", "login"]);
  const assign = byMinji.entries.find((e) => e.action === "inquiry_assign");
  assert.equal(assign.label, '문의 "알람이 안 울려요 잠금 화면에서"');
  assert.equal(assign.link, `#/inquiries/${q.data.id}`);
  const update = byMinji.entries.find((e) => e.action === "company_update");
  assert.equal(update.label, "예시정밀 주식회사");
  assert.match(update.detail, /^이름 → 예시정밀 주식회사/);
  assert.deepEqual((await call("GET", "/api/admin/audit?kind=inquiries", undefined, owner)).data.entries.map((e) => e.action), ["template_add", "inquiry_assign"]);
  assert.deepEqual((await call("GET", "/api/admin/audit?from=2026-10-18&to=2026-10-18", undefined, owner)).data.entries.map((e) => e.action), ["template_add"]);
  assert.deepEqual((await call("GET", "/api/admin/audit?actor=me", undefined, owner)).data.entries.map((e) => e.action), ["template_add", "admin_update", "invite", "login"]);
  const actors = (await call("GET", "/api/admin/audit", undefined, owner)).data.actors;
  assert.deepEqual(actors.map((a) => a.name), ["최고 관리자", "민지"]);
});

test("[ADMIN-17] the activity log shows admins by name; only the owner sees their email", async () => {
  const { call } = server();
  const owner = await signIn(call);
  await member(call, owner, "minji@example.com", { ...SUPPORT, audit: "view" });
  const { headers: viewer } = await member(call, owner, "junho@example.com", { ...SUPPORT, audit: "view" }, "준호", 41);
  const entries = (await call("GET", "/api/admin/audit", undefined, viewer)).data.entries;
  const invite = entries.find((e) => e.action === "invite" && e.label === "민지");
  assert.ok(invite, "the invite names who it was for");
  assert.equal(invite.target, "m•••@example.com");
  assert.ok(!JSON.stringify(entries).includes("minji@example.com"));
  const own = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries;
  assert.ok(own.some((e) => e.target === "minji@example.com"));
});

test("[ADMUX-07] the notices screen knows the newest app version questions came from", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  seedInquiries(DB, 1, { app: "1.21.0 (32)" });
  DB.db.prepare("UPDATE inquiries SET id = 'qold00000000000000000a'").run();
  seedInquiries(DB, 1, { app: "1.22.0 (33)" });
  assert.deepEqual((await call("GET", "/api/admin/notices", undefined, owner)).data.latestApp, { name: "1.22.0", code: 33 });
});

// ---- 293: the home screen, typing, the timeline ----

test("[ADMUX-06] the home screen's 할 일: closest to its target first, setups and flagged posts, who holds what", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  const { headers } = await member(call, owner);
  const now = Date.now();
  seedInquiries(DB, 1, { kind: "idea", start: now - 3600000 });
  DB.db.prepare("UPDATE inquiries SET id = 'qidea00000000000000000'").run();
  DB.db.prepare("UPDATE inquiry_messages SET inquiry_id = 'qidea00000000000000000'").run();
  seedInquiries(DB, 1, { kind: "bug", start: now - 7 * 3600000 });
  await call("POST", "/api/admin/inquiries/q000000000000000000000/assign", { to: "me" }, headers);
  await call("POST", "/api/reports", report(1), from(1));
  const o = (await call("GET", "/api/admin/overview", undefined, owner)).data;
  assert.deepEqual(o.queue.map((x) => x.type), ["inquiry", "inquiry", "group"]);
  assert.equal(o.queue[0].kind, "bug", "an hour left of eight comes before 71 of 72");
  assert.equal(o.queue[0].assignee.name, "민지");
  assert.deepEqual(o.assigned, [{ id: o.queue[0].assignee.id, name: "민지", n: 1 }]);
  assert.equal(o.slaLateToday >= 1, true);
  assert.equal(o.inquiriesToday + o.inquiriesYesterday >= 2, true);
});

test("[ADMIN-16] 답 쓰는 중 shows to the other admins for 30 seconds, and stops when the answer goes", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const { headers } = await member(call, owner);
  const q = await call("POST", "/api/inquiries", question(1));
  await call("POST", `/api/admin/inquiries/${q.data.id}/typing`, {}, headers);
  assert.deepEqual((await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data.typing, ["민지"]);
  assert.deepEqual((await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, headers)).data.typing, [], "not to herself");
  assert.deepEqual((await call("GET", "/api/admin/inquiries", undefined, owner)).data.inquiries[0].typing, ["민지"]);
  t.mock.timers.tick(31 * 1000);
  assert.deepEqual((await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data.typing, []);
  await call("POST", `/api/admin/inquiries/${q.data.id}/typing`, {}, headers);
  await call("POST", `/api/admin/inquiries/${q.data.id}/reply`, { text: "확인할게요" }, headers);
  assert.deepEqual((await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data.typing, []);
});

test("[ADMUX-07] a question's timeline: who took it, answered, closed and linked it, in order", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const q = await call("POST", "/api/inquiries", question(1));
  await call("POST", `/api/admin/inquiries/${q.data.id}/assign`, { to: "me" }, owner);
  await call("POST", `/api/admin/inquiries/${q.data.id}/reply`, { text: "확인할게요" }, owner);
  await call("POST", "/api/admin/dev", { title: "알람 늦음", inquiry: q.data.id }, owner);
  await call("POST", `/api/admin/inquiries/${q.data.id}/status`, { status: "closed" }, owner);
  const view = (await call("GET", `/api/admin/inquiries/${q.data.id}`, undefined, owner)).data;
  assert.deepEqual(view.events.map((e) => e.action), ["inquiry_assign", "inquiry_reply", "dev_link", "inquiry_status"]);
  assert.equal(view.events[2].detail, "알람 늦음");
  assert.ok(view.events.every((e) => e.by === "최고 관리자"));
});

test("[ADMIN-16] the new screens take a few trips to the database too", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  seedInquiries(DB, 30);
  let trips = 0;
  let inBatch = false;
  const { prepare, batch } = DB;
  const counted = (s) => ({ ...s, bind: (...v) => counted(s.bind(...v)), first: (...a) => (inBatch || trips++, s.first(...a)),
    all: () => (inBatch || trips++, s.all()), run: () => (inBatch || trips++, s.run()) });
  DB.prepare = (sql) => counted(prepare(sql));
  DB.batch = async (list) => {
    trips++;
    inBatch = true;
    try {
      return await batch(list);
    } finally {
      inBatch = false;
    }
  };
  for (const path of ["/api/admin/audit", "/api/admin/audit?kind=inquiries&actor=me", "/api/admin/dev", "/api/admin/rewards", "/api/admin/rates", "/api/admin/inquiries?sort=sla"]) {
    trips = 0;
    const res = await call("GET", path, undefined, owner);
    assert.equal(res.status, 200, path);
    assert.ok(trips <= 3, `${path}: ${trips} trips`);
  }
});

// ---- 294: web push ----

const b64u = (bytes) => Buffer.from(bytes).toString("base64url");

async function vapidKeys() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const pub = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
  return { env: { VAPID_PUBLIC_KEY: b64u(pub), VAPID_PRIVATE_KEY: jwk.d, VAPID_SUBJECT: "mailto:ops@bamnat.example" }, publicKey: pair.publicKey };
}

/** A browser's side: its keys, its subscription JSON, and how it reads what arrives (RFC 8291). */
async function browser(n = 1) {
  const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const ua = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const auth = crypto.getRandomValues(new Uint8Array(16));
  const hmac = async (key, data) => new Uint8Array(await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]), data));
  const cat = (...p) => Uint8Array.from(p.flatMap((x) => [...x]));
  const te = new TextEncoder();
  return {
    subscription: { endpoint: `https://fcm.googleapis.com/fcm/send/browser-${n}`, keys: { p256dh: b64u(ua), auth: b64u(auth) } },
    async read(body) {
      const bytes = new Uint8Array(body);
      const salt = bytes.slice(0, 16);
      const idlen = bytes[20];
      const as = bytes.slice(21, 21 + idlen);
      const sealed = bytes.slice(21 + idlen);
      const theirs = await crypto.subtle.importKey("raw", as, { name: "ECDH", namedCurve: "P-256" }, false, []);
      const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: theirs }, pair.privateKey, 256));
      const ikm = await hmac(await hmac(auth, shared), cat(te.encode("WebPush: info\0"), ua, as, [1]));
      const prk = await hmac(salt, ikm);
      const cek = (await hmac(prk, cat(te.encode("Content-Encoding: aes128gcm\0"), [1]))).slice(0, 16);
      const nonce = (await hmac(prk, cat(te.encode("Content-Encoding: nonce\0"), [1]))).slice(0, 12);
      const key = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["decrypt"]);
      const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, key, sealed));
      assert.equal(plain[plain.length - 1], 2, "one last record");
      return JSON.parse(new TextDecoder().decode(plain.slice(0, -1)));
    },
  };
}

test("[ADMIN-19] without VAPID secrets notifyAdmins returns at once and the screen says how to turn it on", async (t) => {
  const { notifyAdmins } = await import("../src/index.js");
  const { call, env } = server();
  const fetched = t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 201 }));
  assert.deepEqual(await notifyAdmins(env, "inquiry", { title: "x" }), { sent: 0, gone: 0 });
  assert.deepEqual(await notifyAdmins({}, "inquiry", { title: "x" }), { sent: 0, gone: 0 }, "not even a database");
  assert.equal(fetched.mock.callCount(), 0);
  const owner = await signIn(call);
  const view = (await call("GET", "/api/admin/me/push", undefined, owner)).data;
  assert.deepEqual([view.ready, view.publicKey], [false, null]);
  assert.equal((await call("POST", "/api/admin/me/push", { subscription: (await browser()).subscription }, owner)).data.error, "push");
});

test("[ADMIN-19] a subscribed browser gets the news encrypted for it, signed by the server; a gone one is forgotten", async (t) => {
  const keys = await vapidKeys();
  const { call, env, DB } = server(keys.env);
  const owner = await signIn(call);
  const b = await browser(1);
  const view = (await call("POST", "/api/admin/me/push", { subscription: b.subscription, kinds: ["inquiry", "join"] }, owner)).data;
  assert.equal(view.ready, true);
  assert.equal(view.publicKey, keys.env.VAPID_PUBLIC_KEY);
  assert.deepEqual(view.subscriptions.map((s) => s.kinds), [["inquiry", "join"]]);
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    sent.push({ url, headers: init.headers, body: init.body });
    return new Response(null, { status: sent.length > 1 ? 410 : 201 });
  });
  const { notifyAdmins } = await import("../src/index.js");
  assert.deepEqual(await notifyAdmins(env, "inquiry", { title: "새 문의", body: "문제 신고 1건", url: "/admin/#/inquiries" }), { sent: 1, gone: 0 });
  assert.equal(sent[0].url, b.subscription.endpoint);
  assert.equal(sent[0].headers["content-encoding"], "aes128gcm");
  assert.deepEqual(await b.read(sent[0].body), { tag: "inquiry", title: "새 문의", body: "문제 신고 1건", url: "/admin/#/inquiries" });
  // RFC 8292: a JWT for the push service's origin, signed with the server's key, and that key.
  const [, jwt, k] = sent[0].headers.authorization.match(/^vapid t=([^,]+), k=(.+)$/);
  assert.equal(k, keys.env.VAPID_PUBLIC_KEY);
  const [head, claims, sig] = jwt.split(".");
  assert.equal(JSON.parse(Buffer.from(claims, "base64url")).aud, "https://fcm.googleapis.com");
  assert.ok(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, keys.publicKey, Buffer.from(sig, "base64url"), new TextEncoder().encode(`${head}.${claims}`)));
  // A kind it did not ask for is not sent.
  assert.deepEqual(await notifyAdmins(env, "flagged", { title: "x" }), { sent: 0, gone: 0 });
  // The push service says the browser is gone: the row goes.
  assert.deepEqual(await notifyAdmins(env, "join", { title: "승인 대기" }), { sent: 0, gone: 1 });
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM push_subscriptions").get().n, 0);
});

test("[ADMIN-19] nothing in the quiet hours, nothing an admin may not see, and a test on demand", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T14:00:00Z") }); // 23:00 KST
  const keys = await vapidKeys();
  const { call, env } = server(keys.env);
  const owner = await signIn(call);
  const { headers } = await member(call, owner, "minji@example.com", { ...SUPPORT, inquiries: "none" });
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url) => (sent.push(url), new Response(null, { status: 201 })));
  await call("POST", "/api/admin/me/push", { subscription: (await browser(1)).subscription, quiet: { from: 22, to: 7 } }, owner);
  // Minji may not read questions: she cannot ask for them, and is not sent them.
  const hers = (await call("POST", "/api/admin/me/push", { subscription: (await browser(2)).subscription, kinds: ["inquiry"] }, headers)).data;
  assert.deepEqual(hers.subscriptions[0].kinds, []);
  assert.ok(!hers.kinds.some((k) => k.key === "inquiry"));
  const { notifyAdmins } = await import("../src/index.js");
  assert.deepEqual(await notifyAdmins(env, "inquiry", { title: "새 문의" }), { sent: 0, gone: 0 }, "23:00 is quiet for the owner");
  t.mock.timers.tick(9 * 3600000); // 08:00 KST
  assert.deepEqual(await notifyAdmins(env, "inquiry", { title: "새 문의" }), { sent: 1, gone: 0 });
  assert.deepEqual((await call("POST", "/api/admin/me/push/test", {}, headers)).data, { sent: 1, gone: 0, ready: true });
  // Only known push services, so the server never posts to an address someone typed.
  const bad = { ...(await browser(3)).subscription, endpoint: "https://example.com/push" };
  assert.equal((await call("POST", "/api/admin/me/push", { subscription: bad }, owner)).data.error, "subscription");
  const off = await call("POST", "/api/admin/me/push/remove", { endpoint: (await browser(1)).subscription.endpoint }, owner);
  assert.equal(off.data.subscriptions.length, 0);
});

test("[ADMIN-19] a new admin waiting for approval reaches the owner's browser", async (t) => {
  const keys = await vapidKeys();
  const { call } = server(keys.env);
  const owner = await signIn(call);
  const b = await browser(1);
  await call("POST", "/api/admin/me/push", { subscription: b.subscription, kinds: ["join"] }, owner);
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url, init) => (sent.push(init.body), new Response(null, { status: 201 })));
  const inv = await call("POST", "/api/admin/invites", { email: "junho@example.com", perms: SUPPORT }, owner);
  await call("POST", "/api/admin/join", { token: inv.data.link.split("#")[1], name: "준호", password: "junho-answers-2026" }, { ...APP, ...from(44) });
  assert.equal(sent.length, 1);
  const message = await b.read(sent[0]);
  assert.equal(message.tag, "join");
  assert.ok(!JSON.stringify(message).includes("junho@example.com"), "no email in a push");
});

test("[ADMIN-19] an urgent question reaches those who asked for urgent ones and those who asked for all questions, once each", async (t) => {
  const keys = await vapidKeys();
  const { call, env } = server(keys.env);
  const owner = await signIn(call);
  const urgentOnly = await browser(1);
  const every = await browser(2);
  await call("POST", "/api/admin/me/push", { subscription: urgentOnly.subscription, kinds: ["urgent"] }, owner);
  await call("POST", "/api/admin/me/push", { subscription: every.subscription, kinds: ["inquiry", "urgent"] }, owner);
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    sent.push(url);
    return new Response(null, { status: 201 });
  });
  const { notifyAdmins } = await import("../src/index.js");
  assert.deepEqual(await notifyAdmins(env, "urgent", { title: "새 문의", body: "문제 신고 1건" }), { sent: 2, gone: 0 });
  assert.deepEqual(new Set(sent), new Set([urgentOnly.subscription.endpoint, every.subscription.endpoint]));
  assert.deepEqual(await notifyAdmins(env, "inquiry", { title: "새 문의", body: "질문 1건" }), { sent: 1, gone: 0 });
  assert.equal(sent.at(-1), every.subscription.endpoint);
});
