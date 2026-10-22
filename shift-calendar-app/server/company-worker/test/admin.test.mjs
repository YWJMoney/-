// [ADMIN-01] The developer app: sign-in, review, companies, rules, rewards and its files: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { server, setup, report, from, signIn, ORIGIN, PASSWORD } from "./helpers.mjs";

const APP = { "x-admin": "1", origin: ORIGIN };

/** Two coworkers who agree: adopted on its own. */
async function adopted(call, extra = {}) {
  const a = await call("POST", "/api/reports", report(1, { verified: true, ...extra }), from(1));
  const b = await call("POST", "/api/reports", report(2, extra), from(2));
  assert.equal(b.data.status, "adopted");
  return { a: a.data, b: b.data, company: a.data.company.id };
}

test("only a signed-in developer app gets in", async () => {
  const { call } = server();
  assert.deepEqual((await call("GET", "/api/admin/overview")).data, { error: "login" });
  // Changes need the app's own header, from its own address.
  assert.equal((await call("POST", "/api/admin/login", { password: PASSWORD })).status, 403);
  assert.equal((await call("POST", "/api/admin/login", { password: PASSWORD }, { ...APP, origin: "https://elsewhere.example" })).status, 403);

  const res = await call("POST", "/api/admin/login", { password: PASSWORD }, APP);
  assert.equal(res.status, 200);
  const set = res.headers.get("set-cookie");
  assert.match(set, /^__Host-bamnat_admin=[A-Za-z0-9]{40}; Path=\/; Secure; HttpOnly; SameSite=Strict; Max-Age=604800$/);
  const cookie = { cookie: set.split(";")[0] };
  assert.equal((await call("GET", "/api/admin/overview", undefined, cookie)).status, 200);
  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 3, rewardMonths: 3 }, cookie)).status, 403);
  assert.equal((await call("GET", "/api/admin/overview", undefined, { cookie: "__Host-bamnat_admin=" + "A".repeat(40) })).status, 401);

  const out = await call("POST", "/api/admin/logout", {}, { ...cookie, ...APP });
  assert.match(out.headers.get("set-cookie"), /^__Host-bamnat_admin=; .*Max-Age=0$/);
  assert.equal((await call("GET", "/api/admin/overview", undefined, cookie)).status, 401);
});

test("five wrong passwords lock that network out for an hour", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call, DB } = server();
  for (let left = 4; left >= 1; left--) {
    const res = await call("POST", "/api/admin/login", { password: "not-the-password" }, APP);
    assert.equal(res.status, 401);
    assert.deepEqual(res.data, { error: "wrong", left });
  }
  assert.deepEqual((await call("POST", "/api/admin/login", { password: "not-the-password" }, APP)).data, { error: "locked", left: 0 });
  const locked = await call("POST", "/api/admin/login", { password: PASSWORD }, APP);
  assert.equal(locked.status, 429);
  assert.equal(locked.data.until, "2026-10-17T10:00:00.000Z");
  // Another network is not locked out.
  assert.equal((await call("POST", "/api/admin/login", { password: PASSWORD }, { ...APP, ...from(9) })).status, 200);
  t.mock.timers.tick(59 * 60 * 1000);
  assert.equal((await call("POST", "/api/admin/login", { password: PASSWORD }, APP)).status, 429);
  t.mock.timers.tick(2 * 60 * 1000);
  assert.equal((await call("POST", "/api/admin/login", { password: PASSWORD }, APP)).status, 200);
  assert.ok(!DB.dump().includes("203.0.113"), "no address kept");
  assert.ok(!DB.dump().includes(PASSWORD), "no password kept");
});

test("a session lasts a week", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T09:00:00Z") });
  const { call } = server();
  const h = await signIn(call);
  t.mock.timers.tick(7 * 24 * 60 * 60 * 1000 - 60 * 1000);
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).status, 200);
  t.mock.timers.tick(2 * 60 * 1000);
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).status, 401);
});

test("without a password set, sign-in says what is missing", async () => {
  const { call } = server({ ADMIN_PASSWORD: "short", RATE_SALT: undefined });
  assert.deepEqual((await call("POST", "/api/admin/login", { password: "short" }, APP)).data, { error: "not_set" });
  assert.deepEqual((await call("GET", "/api/admin/health")).data,
    { database: true, password: false, salt: false, mail: false, play: false, why: { password: "short", salt: "missing" } });
});

test("the setup screen says why a secret is not read, never what it is", async () => {
  // Typed under a slightly different name, and bound from the Secrets Store instead of set on the Worker.
  const { call } = server({ ADMIN_PASSWORD: undefined, " admin_password": "a-long-password", RATE_SALT: { get: async () => "x" } });
  const h = (await call("GET", "/api/admin/health")).data;
  assert.deepEqual(h, { database: true, password: false, salt: false, mail: false, play: false, why: { password: "name", salt: "store" } });
  assert.equal(JSON.stringify(h).includes("a-long-password"), false);
});

test("the developer adopts or turns down what coworkers sent", async () => {
  const { call } = server();
  const h = await signIn(call);
  const a = await call("POST", "/api/reports", report(1, { verified: true, note: "명세서랑 맞춰 봤어요" }), from(1));
  const other = setup();
  other.shiftTypes[0].end = "18:00";
  const b = await call("POST", "/api/reports", report(2, { company: "다른정밀", setup: other }), from(2));

  let q = (await call("GET", "/api/admin/queue", undefined, h)).data;
  assert.equal(q.groups.length, 2);
  const g = q.groups.find((x) => x.setup === a.data.setup);
  assert.deepEqual([g.company.name, g.dept, g.points, g.persons, g.threshold, g.blocked, g.turnedDown, g.replaces, g.changes],
    ["예시정밀", "", 2, 1, 3, false, null, null, null]);
  assert.deepEqual(g.reports.map((r) => [r.verified, r.note]), [[true, "명세서랑 맞춰 봤어요"]]);
  assert.deepEqual(g.lines[0], ["근무", "주간 07:00~19:00 휴게 60분 · 야간 19:00~07:00 휴게 60분 · 휴무(쉬는 날)"]);

  const res = await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/adopt`, {}, h);
  assert.equal(res.status, 200);
  assert.match(res.data.rule, /^[A-Za-z0-9]{16}$/);
  const mine = (await call("GET", `/api/reports/${a.data.id}`)).data;
  assert.equal(mine.status, "adopted");
  assert.deepEqual(mine.reward, { months: 3, status: "owed" });
  assert.equal((await call("GET", "/api/companies?q=예시")).data.companies.length, 1);

  await call("POST", `/api/admin/groups/${b.data.company.id}/${b.data.setup}/reject`, { reason: "unclear" }, h);
  const theirs = (await call("GET", `/api/reports/${b.data.id}`)).data;
  assert.equal(theirs.status, "rejected");
  assert.deepEqual(theirs.reason, { code: "unclear", text: "정보가 부족해서 확인하기 어려워요" });
  assert.equal(theirs.reward, null);

  q = (await call("GET", "/api/admin/queue", undefined, h)).data;
  assert.deepEqual(q.groups, []);
  assert.equal((await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/adopt`, {}, h)).status, 404);
  const o = (await call("GET", "/api/admin/overview", undefined, h)).data;
  assert.deepEqual([o.pending, o.flagged, o.owed, o.companies, o.rules, o.reports], [0, 0, 1, 2, 1, 2]);
  assert.deepEqual(o.settings, { adoptPoints: 3, rewardMonths: 3, adoptAgree: 75, adoptPersons: 2, amendPersons: 2, autoReward: 0, rewardCapMonths: 3, rewardCapDays: 90 });
  assert.equal(o.reasons.wrong, "실제와 다른 정보로 보여요");
});

test("what the developer turned down is not adopted on its own again", async () => {
  const { call } = server();
  const h = await signIn(call);
  const a = await call("POST", "/api/reports", report(1), from(1));
  await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/reject`, { reason: "wrong" }, h);
  await call("POST", "/api/reports", report(2, { verified: true }), from(2));
  const c = await call("POST", "/api/reports", report(3, { verified: true }), from(3));
  assert.deepEqual([c.data.status, c.data.persons, c.data.points, c.data.review], ["pending", 2, 4, true]);
  // Coworkers are not offered it; the developer sees it again, marked.
  assert.deepEqual((await call("GET", `/api/companies/${a.data.company.id}`)).data.setups, []);
  const [g] = (await call("GET", "/api/admin/queue", undefined, h)).data.groups;
  assert.deepEqual([g.blocked, g.turnedDown, g.persons], [true, "rejected", 2]);
});

test("a setup that only one network agrees on waits for the developer", async () => {
  const { call } = server();
  const h = await signIn(call);
  await call("POST", "/api/reports", report(1, { verified: true }), from(7));
  await call("POST", "/api/reports", report(2), from(7));
  const [g] = (await call("GET", "/api/admin/queue", undefined, h)).data.groups;
  assert.deepEqual([g.persons, g.networks, g.points, g.blocked, g.turnedDown, g.replaces], [2, 1, 3, true, null, null]);
});

test("a withdrawn rule leaves search at once and can come back", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { a, company } = await adopted(call);
  let info = (await call("GET", `/api/admin/companies/${company}`, undefined, h)).data;
  const rule = info.rules[0];
  assert.deepEqual([rule.version, rule.source, rule.withdrawn, rule.superseded, rule.ok, rule.bad], [1, "auto", false, false, 2, 0]);

  info = (await call("POST", `/api/admin/rules/${rule.id}`, { withdrawn: true }, h)).data;
  assert.equal(info.rules[0].withdrawn, true);
  assert.deepEqual((await call("GET", "/api/companies?q=예시")).data.companies, []);
  assert.deepEqual((await call("GET", `/api/companies/${company}`)).data.setups, []);
  assert.equal((await call("GET", `/api/reports/${a.id}`)).data.status, "withdrawn");

  // The same setup sent meanwhile waits, then joins the rule when it comes back.
  const c = await call("POST", "/api/reports", report(3, { verified: true }), from(3));
  assert.equal(c.data.status, "pending");
  assert.equal((await call("GET", "/api/admin/queue", undefined, h)).data.groups[0].turnedDown, "withdrawn");
  info = (await call("POST", `/api/admin/rules/${rule.id}`, { withdrawn: false }, h)).data;
  assert.equal(info.rules[0].withdrawn, false);
  assert.equal((await call("GET", `/api/reports/${c.data.id}`)).data.status, "adopted");
  assert.equal((await call("GET", `/api/reports/${a.id}`)).data.status, "adopted");
  assert.equal((await call("GET", "/api/companies?q=예시")).data.companies.length, 1);

  assert.equal((await call("POST", `/api/admin/rules/${rule.id}`, {}, h)).data.error, "withdrawn");
  assert.equal((await call("POST", "/api/admin/rules/AAAAAAAAAAAAAAAA", { withdrawn: true }, h)).status, 404);
});

test("a rule cannot come back over the one that replaced it", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { company } = await adopted(call);
  const first = (await call("GET", `/api/admin/companies/${company}`, undefined, h)).data.rules[0];
  await call("POST", `/api/admin/rules/${first.id}`, { withdrawn: true }, h);
  const other = setup();
  other.pay.monthlyHours = 209;
  await call("POST", "/api/reports", report(3, { setup: other, verified: true }), from(3));
  const d = await call("POST", "/api/reports", report(4, { setup: other }), from(4));
  assert.equal(d.data.status, "adopted");
  const res = await call("POST", `/api/admin/rules/${first.id}`, { withdrawn: false }, h);
  // The new rule superseded the withdrawn one, so it comes back as an earlier version only.
  assert.equal(res.status, 200);
  const rules = res.data.rules.map((r) => [r.version, r.withdrawn, r.superseded]);
  assert.deepEqual(rules, [[2, false, false], [1, false, true]]);
});

test("a new version of a rule in use waits for the developer, with what changed", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { company } = await adopted(call);
  const other = setup();
  other.pay.rules.extra[3].percent = 0.12;
  await call("POST", "/api/reports", report(3, { setup: other, verified: true }), from(3));
  const d = await call("POST", "/api/reports", report(4, { setup: other }), from(4));
  assert.deepEqual([d.data.status, d.data.review], ["pending", true]);

  const [g] = (await call("GET", "/api/admin/queue", undefined, h)).data.groups;
  assert.equal(g.blocked, true);
  assert.equal(g.turnedDown, null);
  assert.equal(g.replaces.version, 1);
  assert.deepEqual(g.changes, {
    added: [["대근", "8시간에 기본급 12% + 연장 가산 50% (8시간 넘게) + 야간 가산 50%"]],
    removed: [["대근", "8시간에 기본급 10% + 연장 가산 50% (8시간 넘게) + 야간 가산 50%"]],
  });

  await call("POST", `/api/admin/groups/${company}/${d.data.setup}/adopt`, {}, h);
  const info = (await call("GET", `/api/admin/companies/${company}`, undefined, h)).data;
  assert.deepEqual(info.rules.map((r) => [r.version, r.source, r.superseded]), [[2, "admin", false], [1, "auto", true]]);
  const pub = (await call("GET", `/api/companies/${company}`)).data;
  assert.deepEqual(pub.setups.map((s) => [s.status, s.version]), [["adopted", 2]]);
  assert.equal(pub.setups[0].setup.pay.rules.extra[3].percent, 0.12);
});

test("a lower bar adopts what already meets it", async () => {
  const { call, DB } = server();
  const h = await signIn(call);
  const a = await call("POST", "/api/reports", report(1), from(1));
  await call("POST", "/api/reports", report(2), from(2));
  assert.equal((await call("GET", `/api/reports/${a.data.id}`)).data.status, "pending");

  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 1, rewardMonths: 3 }, h)).data.error, "adoptPoints");
  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 3, rewardMonths: 25 }, h)).data.error, "rewardMonths");
  const saved = await call("POST", "/api/admin/settings", { adoptPoints: 2, rewardMonths: 6 }, h);
  assert.deepEqual(saved.data, { adoptPoints: 2, rewardMonths: 6, adoptAgree: 75, adoptPersons: 2, amendPersons: 2, autoReward: 0, rewardCapMonths: 3, rewardCapDays: 90 });
  const mine = (await call("GET", `/api/reports/${a.data.id}`)).data;
  assert.deepEqual([mine.status, mine.threshold, mine.rewardMonths], ["adopted", 2, 6]);
  assert.deepEqual(mine.reward, { months: 6, status: "owed" });
  assert.deepEqual((await call("GET", "/api/terms")).data, { threshold: 2, rewardMonths: 6, agree: 75 });
  // [COMM-07] The agreement share it takes on its own, from half to everyone.
  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 2, rewardMonths: 6, adoptAgree: 40 }, h)).data.error, "adoptAgree");
  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 2, rewardMonths: 6, adoptAgree: 90 }, h)).data.adoptAgree, 90);

  // With no reward set, adoption records none.
  await call("POST", "/api/admin/settings", { adoptPoints: 2, rewardMonths: 0 }, h);
  await call("POST", "/api/reports", report(3, { company: "다른정밀" }), from(3));
  await call("POST", "/api/reports", report(4, { company: "다른정밀" }), from(4));
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM rewards").get().n, 2);
});

test("the developer answers a way the app cannot set yet, with a reward when the app will learn it", async () => {
  const { call } = server();
  const h = await signIn(call);
  const note = "대근은 8시간에 기본급의 10%예요";
  const a = await call("POST", "/api/reports", report(1, { ask: true, note, dept: "생산팀" }), from(1));
  const b = await call("POST", "/api/reports", report(2, { ask: true, note: "야간 대근은 0.6 가산이에요", verified: true }), from(2));
  let q = (await call("GET", "/api/admin/queue", undefined, h)).data;
  assert.deepEqual(q.groups, []);
  assert.deepEqual(q.asks.map((x) => [x.company.name, x.dept, x.note, x.verified]),
    [["예시정밀", "생산팀", note, false], ["예시정밀", "", "야간 대근은 0.6 가산이에요", true]]);
  assert.deepEqual(q.asks[0].lines[0], ["근무", "주간 07:00~19:00 휴게 60분 · 야간 19:00~07:00 휴게 60분 · 휴무(쉬는 날)"]);
  let o = (await call("GET", "/api/admin/overview", undefined, h)).data;
  assert.deepEqual([o.pending, o.asks], [0, 2]);
  assert.equal(o.answers.planned, "알려 주신 방식을 앱에 넣을게요. 업데이트되면 바로 설정할 수 있어요");

  assert.deepEqual((await call("POST", `/api/admin/asks/${a.data.id}`, { answer: "maybe" }, h)).data, { error: "answer", field: "answer" });
  assert.deepEqual((await call("POST", `/api/admin/asks/${a.data.id}`, { answer: "planned", reward: true }, h)).data, { ok: true, reward: true });
  // Only a way the app will learn earns the reward.
  assert.deepEqual((await call("POST", `/api/admin/asks/${b.data.id}`, { answer: "possible", reward: true }, h)).data, { ok: true, reward: false });
  assert.equal((await call("POST", `/api/admin/asks/${a.data.id}`, { answer: "later" }, h)).status, 404);

  const mine = (await call("GET", `/api/reports/${a.data.id}`)).data;
  assert.deepEqual([mine.status, mine.ask, mine.reason.code, mine.reward], ["answered", true, "planned", { months: 3, status: "owed" }]);
  assert.equal(mine.reason.text, "알려 주신 방식을 앱에 넣을게요. 업데이트되면 바로 설정할 수 있어요");
  const theirs = (await call("GET", `/api/reports/${b.data.id}`)).data;
  assert.deepEqual([theirs.status, theirs.reason.code, theirs.reward], ["answered", "possible", null]);
  const list = (await call("GET", "/api/admin/rewards", undefined, h)).data;
  assert.deepEqual(list.rewards.map((w) => [w.id, w.ask, w.version, w.dept]), [[`w${a.data.id}`, true, null, "생산팀"]]);
  o = (await call("GET", "/api/admin/overview", undefined, h)).data;
  assert.deepEqual([o.asks, o.owed], [0, 1]);
  assert.deepEqual((await call("GET", "/api/admin/queue", undefined, h)).data.asks, []);
});

test("rewards are marked given with a note, or cancelled", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { a } = await adopted(call);
  let list = (await call("GET", "/api/admin/rewards", undefined, h)).data;
  assert.equal(list.status, "owed");
  assert.deepEqual(list.counts, { owed: 2 });
  assert.deepEqual(list.rewards.map((w) => [w.company.name, w.months, w.version, w.paidAt]), [["예시정밀", 3, 1, null], ["예시정밀", 3, 1, null]]);
  const id = `w${a.id}`;
  assert.equal((await call("POST", `/api/admin/rewards/${id}`, { status: "paid", memo: "프로 3개월 쿠폰" }, h)).status, 200);
  list = (await call("GET", "/api/admin/rewards?status=paid", undefined, h)).data;
  assert.deepEqual(list.rewards.map((w) => [w.id, w.memo, Boolean(w.paidAt)]), [[id, "프로 3개월 쿠폰", true]]);
  assert.deepEqual((await call("GET", `/api/reports/${a.id}`)).data.reward, { months: 3, status: "paid" });

  await call("POST", `/api/admin/rewards/${id}`, { status: "owed" }, h);
  list = (await call("GET", "/api/admin/rewards", undefined, h)).data;
  assert.deepEqual(list.rewards.find((w) => w.id === id).memo, "프로 3개월 쿠폰");
  await call("POST", `/api/admin/rewards/${id}`, { status: "void" }, h);
  assert.deepEqual((await call("GET", "/api/admin/rewards?status=void", undefined, h)).data.counts, { owed: 1, void: 1 });
  assert.equal((await call("POST", `/api/admin/rewards/${id}`, { status: "sent" }, h)).data.error, "status");
  assert.equal((await call("POST", `/api/admin/rewards/w${"A".repeat(22)}`, { status: "paid" }, h)).status, 404);
});

test("companies are made, renamed, given other names and hidden", async () => {
  const { call } = server();
  const h = await signIn(call);
  const made = await call("POST", "/api/admin/companies", { name: "예시정밀" }, h);
  assert.equal(made.status, 201);
  const id = made.data.id;
  assert.equal((await call("POST", "/api/admin/companies", { name: "(주)예시 정밀" }, h)).status, 409);
  assert.equal((await call("POST", "/api/admin/companies", { name: "주" }, h)).data.error, "company");

  const a = await call("POST", "/api/reports", report(1, { company: "주식회사 예시정밀", verified: true }), from(1));
  assert.equal(a.data.company.id, id);
  let info = (await call("POST", `/api/admin/companies/${id}/aliases`, { name: "Yesi Precision" }, h)).data;
  assert.deepEqual(info.aliases, ["Yesi Precision"]);
  const b = await call("POST", "/api/reports", report(2, { company: "YESI PRECISION CO., LTD." }), from(2));
  assert.equal(b.data.company.id, id);
  assert.equal(b.data.status, "adopted");
  assert.deepEqual((await call("GET", "/api/companies?q=yesi")).data.companies.map((c) => [c.name, c.alias]), [["예시정밀", "Yesi Precision"]]);

  info = (await call("POST", `/api/admin/companies/${id}`, { name: "예시정밀공업" }, h)).data;
  assert.equal(info.name, "예시정밀공업");
  assert.deepEqual(info.aliases, ["Yesi Precision", "예시정밀"]);
  const found = (await call("GET", "/api/companies?q=예시정밀")).data.companies;
  assert.deepEqual(found.map((c) => [c.id, c.name]), [[id, "예시정밀공업"]]);

  const other = await call("POST", "/api/admin/companies", { name: "다른회사" }, h);
  assert.equal((await call("POST", `/api/admin/companies/${other.data.id}/aliases`, { name: "예시 정밀" }, h)).status, 409);
  assert.equal((await call("POST", `/api/admin/companies/${other.data.id}`, { name: "예시정밀공업" }, h)).status, 409);
  info = (await call("POST", `/api/admin/companies/${id}/aliases/remove`, { name: "yesi precision" }, h)).data;
  assert.deepEqual(info.aliases, ["예시정밀"]);

  info = (await call("POST", `/api/admin/companies/${id}`, { hidden: true }, h)).data;
  assert.equal(info.hidden, true);
  assert.equal((await call("GET", `/api/companies/${id}`)).status, 404);
  assert.deepEqual((await call("GET", "/api/companies?q=예시정밀")).data.companies, []);
  assert.equal((await call("POST", `/api/admin/companies/${id}`, { hidden: "yes" }, h)).data.error, "hidden");
  await call("POST", `/api/admin/companies/${id}`, { hidden: false }, h);
  assert.equal((await call("GET", `/api/companies/${id}`)).status, 200);

  const list = (await call("GET", "/api/admin/companies?q=", undefined, h)).data.companies;
  assert.deepEqual(list.map((c) => [c.name, c.rules, c.reports, c.aliases]).sort(), [["다른회사", 0, 0, 0], ["예시정밀공업", 1, 2, 1]]);
  assert.deepEqual((await call("GET", "/api/admin/companies?q=yesi", undefined, h)).data.companies, []);
});

test("one company found under two names becomes one", async () => {
  const { call } = server();
  const h = await signIn(call);
  const a = await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  const b = await call("POST", "/api/reports", report(2, { company: "예시정밀공업" }), from(2));
  assert.notEqual(a.data.company.id, b.data.company.id);
  assert.equal(a.data.setup, b.data.setup);

  const res = await call("POST", `/api/admin/companies/${b.data.company.id}/merge`, { into: a.data.company.id }, h);
  assert.equal(res.data.id, a.data.company.id);
  assert.deepEqual(res.data.aliases, ["예시정밀공업"]);
  assert.equal(res.data.rules.length, 1);
  assert.equal((await call("GET", `/api/reports/${b.data.id}`)).data.status, "adopted");
  assert.deepEqual((await call("GET", `/api/companies/${b.data.company.id}`)).data, { mergedInto: a.data.company.id });
  const old = (await call("GET", `/api/admin/companies/${b.data.company.id}`, undefined, h)).data;
  assert.deepEqual(old.mergedInto, { id: a.data.company.id, name: "예시정밀" });

  // The old name keeps leading to the company.
  const c = await call("POST", "/api/reports", report(3, { company: "예시정밀공업" }), from(3));
  assert.deepEqual([c.data.company.id, c.data.status], [a.data.company.id, "adopted"]);
  const vote = await call("POST", "/api/votes", { device: "test-device-00000009", company: b.data.company.id, setup: a.data.setup, ok: true }, from(9));
  assert.equal(vote.data.status, "adopted");

  assert.equal((await call("POST", `/api/admin/companies/${b.data.company.id}/merge`, { into: a.data.company.id }, h)).data.error, "merge");
  assert.equal((await call("POST", `/api/admin/companies/${a.data.company.id}/merge`, { into: a.data.company.id }, h)).data.error, "merge");
  assert.equal((await call("POST", `/api/admin/companies/${a.data.company.id}/merge`, { into: "AAAAAAAAAAAA" }, h)).status, 404);
});

test("where both had a rule for a department, the one in use stays", async () => {
  const { call } = server();
  const h = await signIn(call);
  const first = await adopted(call);
  const other = setup();
  other.pay.monthlyHours = 209;
  await call("POST", "/api/reports", report(3, { company: "예시정밀공업", setup: other, verified: true }), from(3));
  const d = await call("POST", "/api/reports", report(4, { company: "예시정밀공업", setup: other }), from(4));
  assert.equal(d.data.status, "adopted");
  const res = await call("POST", `/api/admin/companies/${d.data.company.id}/merge`, { into: first.company }, h);
  assert.deepEqual(res.data.rules.map((r) => [r.version, r.superseded, r.ok]).sort(), [[1, false, 2], [1, true, 2]]);
  const pub = (await call("GET", `/api/companies/${first.company}`)).data;
  assert.deepEqual(pub.setups.map((s) => [s.status, s.id]), [["adopted", first.a.setup]]);
});

test("doubts about a rule reach the developer", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { a } = await adopted(call);
  for (const [n, why] of [[3, "old"], [4, "name"], [5, "wrong"]]) {
    await call("POST", "/api/votes", { device: `test-device-0000000${n}`, company: a.company.id, setup: a.setup, ok: false, why }, from(n));
  }
  const { flagged } = (await call("GET", "/api/admin/queue", undefined, h)).data;
  assert.deepEqual(flagged.map((f) => [f.company.name, f.version, f.good, f.bad, f.names, f.old]), [["예시정밀", 1, 2, 3, 1, 1]]);
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).data.flagged, 1);
});

test("the developer app's files are served safely", async () => {
  const { call } = server({ DB: undefined });
  const redirect = await call("GET", "/admin");
  assert.equal(redirect.status, 301);
  assert.equal(redirect.headers.get("location"), `${ORIGIN}/admin/`);

  const page = await call("GET", "/admin/");
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type"), /^text\/html/);
  const csp = page.headers.get("content-security-policy");
  for (const part of ["default-src 'none'", "script-src 'self'", "frame-ancestors 'none'", "form-action 'none'"]) assert.ok(csp.includes(part), part);
  assert.equal(page.headers.get("x-robots-tag"), "noindex, nofollow");
  assert.equal(page.headers.get("referrer-policy"), "no-referrer");
  assert.match(page.text, /<script src="\/admin\/app\.js" defer><\/script>/);
  assert.ok(!/<script>|on(click|load|error)=/i.test(page.text), "no inline script");

  for (const path of ["/admin/app.js", "/admin/sw.js"]) {
    const js = await call("GET", path);
    assert.match(js.headers.get("content-type"), /^text\/javascript/);
    assert.doesNotThrow(() => new Function(js.text), path);
    assert.ok(!/\beval\(|new Function/.test(js.text), `${path} runs under the CSP`);
  }
  const css = await call("GET", "/admin/app.css");
  assert.match(css.headers.get("content-type"), /^text\/css/);
  // [MON-08][MON-09][MON-10] The 정식 출시 switch, the phone link code, phone approval and reward months are on the page.
  const app = (await call("GET", "/admin/app.js")).text;
  for (const part of ['data-form="launch"', 'data-act="launch-switch"', 'data-act="link-code"', 'data-act="phone"', "내 휴대폰 밤낮 연결", "프로 이용권"]) {
    assert.ok(app.includes(part), part);
  }

  const manifest = JSON.parse((await call("GET", "/admin/manifest.webmanifest")).text);
  assert.equal(manifest.start_url, "/admin/");
  assert.equal(manifest.display, "standalone");
  for (const icon of manifest.icons) {
    const res = await worker(icon.src);
    assert.equal(res.status, 200, icon.src);
    if (icon.type === "image/png") {
      const bytes = new Uint8Array(await res.arrayBuffer());
      assert.deepEqual([...bytes.slice(1, 4)], [0x50, 0x4e, 0x47], icon.src);
      const view = new DataView(bytes.buffer);
      assert.equal(`${view.getUint32(16)}x${view.getUint32(20)}`, icon.sizes, icon.src);
    }
  }
  assert.equal((await call("GET", "/admin/secret.txt")).status, 404);
});

/** A request straight to the worker, for binary answers. */
async function worker(path) {
  const { default: w } = await import("../src/index.js");
  return w.fetch(new Request(ORIGIN + path), {});
}

test("the developer app's screens take a few trips to the database however much waits", async () => {
  const { call, DB } = server();
  for (let g = 0; g < 12; g++) {
    const s = setup();
    s.pay.monthlyHours = 160 + g;
    await call("POST", "/api/reports", report(g + 1, { company: `회사${g}정밀`, setup: s }), from(g + 1));
  }
  const admin = await signIn(call);
  let trips = 0;
  let inBatch = false;
  const { prepare, batch } = DB;
  const trip = () => { if (!inBatch) trips++; };
  const counted = (s) => ({ ...s, bind: (...v) => counted(s.bind(...v)),
    first: (...a) => (trip(), s.first(...a)), all: () => (trip(), s.all()), run: () => (trip(), s.run()) });
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
  for (const path of ["/api/admin/overview", "/api/admin/queue", "/api/admin/companies?q=", "/api/admin/posts?status=all", "/api/admin/inquiries"]) {
    trips = 0;
    const res = await call("GET", path, undefined, admin);
    assert.equal(res.status, 200, path);
    // The sign-in check, then one batch.
    assert.ok(trips <= 2, `${path}: ${trips} trips`);
  }
  const q = (await call("GET", "/api/admin/queue", undefined, admin)).data;
  assert.equal(q.groups.length, 12);
  const one = (await call("GET", `/api/admin/companies/${q.groups[0].company.id}`, undefined, admin)).data;
  assert.equal(one.pending, 1);
});

test("the app's files are not sent again when the phone already has them", async () => {
  const { call } = server();
  const first = await call("GET", "/admin/app.js");
  const etag = first.headers.get("etag");
  assert.match(etag, /^"[0-9a-z]+"$/);
  const again = await call("GET", "/admin/app.js", undefined, { "if-none-match": etag });
  assert.equal(again.status, 304);
  assert.equal(again.text, "");
  assert.equal((await call("GET", "/admin/app.js", undefined, { "if-none-match": '"other"' })).status, 200);
  // The phone keeps the files under a name that changes with the app.
  assert.match((await call("GET", "/admin/sw.js")).text, /\)\("[0-9a-z]+"\);\n$/);
});
