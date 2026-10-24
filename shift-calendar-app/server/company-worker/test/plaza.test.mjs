// [PLZ-01]~[PLZ-15] The plaza that grows by itself (1.21): 맞아요·달라요 on posts, adoption by three
// people, rewards given by themselves, amendments and their history, nicknames, uses, job plazas and
// 임금표. `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { postTrust } from "../src/index.js";
import { server, report, setup, device, from, signIn } from "./helpers.mjs";

/** A plaza server: the defaults a deployed one has (three people, rewards by themselves). */
const plaza = () => server({ plaza: true });

/** One rule set from phone 1, and how phones post and vote in its talk. */
async function talkOn(call, extra = {}) {
  const a = await call("POST", "/api/reports", report(1, extra), from(1));
  const c = a.data.company.id;
  const g = a.data.setup;
  const say = (n, body, more = {}) => call("POST", "/api/posts", { device: device(n), company: c, setup: g, body, ...more }, from(n));
  const vote = (n, id, ok) => call("POST", `/api/posts/${id}/vote`, { device: device(n), ok }, from(n));
  const read = (n) => call("GET", `/api/companies/${c}/setups/${g}/posts`, undefined, n ? { "x-device": device(n) } : {});
  return { a: a.data, c, g, say, vote, read };
}

test("trust of a post waits for three votes before it can read 100", () => {
  assert.deepEqual(postTrust({ agree: 0, disagree: 0 }), { trust: 0 });
  assert.deepEqual(postTrust({ agree: 1, disagree: 0 }), { trust: 33 });
  assert.deepEqual(postTrust({ agree: 3, disagree: 0 }), { trust: 100 });
  assert.deepEqual(postTrust({ agree: 3, disagree: 1 }), { trust: 75 });
});

test("people say 맞아요 or 달라요 to each other's posts, once each, never their own", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  const p = (await t.say(2, "야간 수당은 통상임금의 50%예요")).data.id;
  assert.equal((await t.vote(2, p, true)).data.error, "own");
  let r = (await t.vote(3, p, true)).data;
  assert.deepEqual([r.agree, r.disagree, r.myVote], [1, 0, true]);
  r = (await t.vote(4, p, false)).data;
  assert.deepEqual([r.agree, r.disagree], [1, 1]);
  // Changing one's mind moves the vote, it does not add one.
  r = (await t.vote(4, p, true)).data;
  assert.deepEqual([r.agree, r.disagree, r.trust], [2, 0, 67]);
  r = (await t.vote(3, p, null)).data;
  assert.deepEqual([r.agree, r.disagree, r.myVote], [1, 0, null]);
  assert.equal((await t.vote(5, p, "yes")).data.error, "ok");
  const posts = (await t.read(4)).data.posts;
  assert.deepEqual([posts[0].agree, posts[0].myVote, posts[0].kind], [1, true, "talk"]);
  // A reply goes under the first post, and a reply to the reply too.
  const reply = (await t.say(3, "우리 공장도 그래요", { parent: p })).data.id;
  await t.say(5, "저도요", { parent: reply });
  const shown = (await t.read()).data.posts;
  assert.deepEqual(shown.map((x) => x.parent), [null, p, p]);
  assert.equal((await t.say(3, "엉뚱한 글", { parent: "pAAAAAAAAAAAAAAA" })).data.error, "parent");
});

test("a rule set is adopted by three people on their own, and the first three get their reward as a pass", async () => {
  const { call, DB } = plaza();
  const t = await talkOn(call);
  assert.equal(t.a.status, "pending");
  // Two people are no longer enough (결정 D32).
  const second = await call("POST", "/api/reports", report(2), from(2));
  assert.equal(second.data.status, "pending");
  const third = await call("POST", "/api/reports", report(3), from(3));
  assert.equal(third.data.status, "adopted");
  const rewards = DB.db.prepare("SELECT status, memo FROM rewards ORDER BY created_at").all();
  assert.equal(rewards.length, 3);
  assert.ok(rewards.every((w) => w.status === "paid" && w.memo === "자동 지급"));
  const passes = (await call("GET", "/api/passes", undefined, { "x-device": device(2) })).data;
  assert.equal(passes.items.length, 1);
  assert.equal(passes.items[0].why, "rule");
  // A fourth sender joins the rule in use; rewards are for those who brought it in.
  assert.equal((await call("POST", "/api/reports", report(4), from(4))).data.status, "adopted");
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM rewards").get().n, 3);
  // Who stands behind it.
  const co = (await call("GET", `/api/companies/${t.c}`)).data;
  assert.deepEqual([co.setups[0].senders, co.setups[0].persons], [4, 4]);
});

test("rewards stop at the cap a phone can get in the days set", async () => {
  const { call, DB } = plaza();
  for (const [i, company] of ["가나다정밀", "라마바정밀"].entries()) {
    for (const n of [1, 2, 3]) await call("POST", "/api/reports", report(n === 1 ? 1 : n + 10 * (i + 1), { company }), from(n + 10 * (i + 1)));
  }
  // Phone 1 sent both: 3 months for the first, the second stays owed (cap 3 months in 90 days).
  const mine = DB.db.prepare("SELECT status FROM rewards WHERE report_id IN (SELECT id FROM reports WHERE device = (SELECT device FROM reports ORDER BY created_at LIMIT 1)) ORDER BY created_at").all();
  assert.deepEqual(mine.map((w) => w.status), ["paid", "owed"]);
});

test("a proposal in the talk changes the rule in use once three people agree, and the history keeps both", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  await call("POST", "/api/reports", report(2), from(2));
  await call("POST", "/api/reports", report(3), from(3));
  const rule = (await call("GET", `/api/companies/${t.c}`)).data.setups[0];
  assert.equal(rule.version, 1);
  const better = setup();
  better.shiftTypes[0].end = "18:00";
  // The change says what, how it is, how it should be and why, without amounts.
  const change = [{ field: "shift", now: "주간 07:00~19:00", want: "주간 07:00~18:00", why: "10월부터 한 시간 줄었어요" }];
  assert.equal((await t.say(4, "주간이 바뀌었어요", { kind: "proposal", change: [{ ...change[0], why: "수당 300,000원" }], proposal: better })).data.error, "amount");
  assert.equal((await t.say(4, "그대로", { kind: "proposal", change, proposal: setup() })).data.error, "same");
  const sent = await t.say(4, "주간이 바뀌었어요", { kind: "proposal", change, proposal: better });
  assert.equal(sent.status, 201);
  assert.match(sent.data.proposal, /^[0-9a-f]{16}$/);
  let posts = (await t.read()).data.posts;
  const p = posts.find((x) => x.kind === "proposal");
  assert.deepEqual(p.change, change);
  assert.deepEqual([p.proposal.status, p.proposal.persons], ["pending", 1]);
  // 맞아요 on the proposal is a vote for its setup: three people adopt it as version 2.
  await t.vote(5, p.id, true);
  const last = await t.vote(6, p.id, true);
  assert.equal(last.data.agree, 2);
  const now = (await call("GET", `/api/companies/${t.c}`)).data.setups;
  assert.deepEqual(now.map((s) => [s.id, s.version]), [[sent.data.proposal, 2]]);
  const h = (await call("GET", `/api/companies/${t.c}/history?setup=${t.g}`)).data;
  assert.deepEqual(h.versions.map((v) => [v.version, v.source, v.state]), [[2, "amend", "active"], [1, "auto", "superseded"]]);
  assert.equal(h.versions[0].post, p.id);
  assert.ok(h.versions[0].changes.removed.length + h.versions[0].changes.added.length > 0);
  // The talk it came from stays readable, as the older version's.
  const old = (await t.read()).data;
  assert.equal(old.past, true);
  assert.equal(old.posts.find((x) => x.kind === "proposal").proposal.status, "adopted");
  assert.equal((await t.say(7, "늦었네요")).status, 404);
});

test("an adopted rule cannot be taken away by its sender, only taken down by an admin who may", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  await call("POST", "/api/reports", report(2), from(2));
  await call("POST", "/api/reports", report(3), from(3));
  const gone = await call("DELETE", `/api/reports/${t.a.id}`, undefined, { "x-device": device(1) });
  assert.equal(gone.data.kept, true);
  assert.equal((await call("GET", `/api/companies/${t.c}`)).data.setups[0].version, 1);
  // Everything phone 1 sent deleted: the rule stays all the same.
  await call("DELETE", "/api/mine", undefined, { "x-device": device(2) });
  assert.equal((await call("GET", `/api/companies/${t.c}`)).data.setups.length, 1);
  const h = await signIn(call);
  const info = (await call("GET", `/api/admin/companies/${t.c}`, undefined, h)).data;
  const ruleId = info.rules[0].id;
  assert.equal((await call("POST", `/api/admin/rules/${ruleId}`, { withdrawn: true }, h)).status, 200);
  assert.equal((await call("GET", `/api/companies/${t.c}`)).data.setups.length, 0);
});

test("a nickname is the phone's own, two to twelve letters, never an admin's", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  const nick = (n, name) => call("POST", "/api/nick", { device: device(n), nick: name }, from(n));
  assert.equal((await nick(1, "야간요정")).data.nick, "야간요정");
  assert.equal((await nick(2, "야간 요정")).status, 409);
  assert.equal((await nick(2, "관리자")).data.error, "nick");
  assert.equal((await nick(2, "가")).data.error, "nick");
  assert.equal((await nick(2, "01012345678")).data.error, "phone");
  await t.say(1, "제가 올렸어요");
  assert.equal((await t.read()).data.posts[0].nick, "야간요정");
  assert.deepEqual((await call("GET", `/api/companies/${t.c}`)).data.setups[0].by, ["야간요정"]);
  assert.equal((await nick(1, "")).data.nick, null);
  assert.equal((await t.read()).data.posts[0].nick, null);
});

test("phones that use a rule set are counted once each", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  const use = (n, on) => call(on ? "POST" : "DELETE", "/api/uses", { device: device(n), company: t.c, setup: t.g }, from(n));
  assert.deepEqual((await use(2, true)).data, { uses: 1, using: true });
  assert.equal((await use(2, true)).data.uses, 1);
  assert.equal((await use(3, true)).data.uses, 2);
  assert.equal((await use(2, false)).data.uses, 1);
  assert.equal((await call("GET", `/api/companies/${t.c}`)).data.setups[0].uses, 1);
});

test("job plazas: a hospital and a whole-job rule sit in their plaza and region, and the board shows one plaza", async () => {
  const { call } = plaza();
  const h = await call("POST", "/api/reports", report(1, { company: "가나다병원", job: "nurse", region: "11" }), from(1));
  const topic = await call("POST", "/api/reports", report(2, { topic: true, job: "construction", region: "31" }), from(2));
  assert.equal(topic.data.company.name, "건설 일용직·기능공 공통 (경기)");
  assert.equal((await call("POST", "/api/reports", report(3, { topic: true }), from(3))).data.field, "job");
  assert.equal((await call("POST", "/api/reports", report(3, { job: "chef" }), from(3))).data.field, "job");
  const c = (await call("GET", `/api/companies/${h.data.company.id}`)).data;
  assert.deepEqual([c.kind, c.job, c.region], ["hospital", "nurse", "11"]);
  const nurses = (await call("GET", "/api/board?job=nurse")).data;
  assert.deepEqual(nurses.items.map((i) => i.company.name), ["가나다병원"]);
  assert.equal((await call("GET", "/api/board?region=31")).data.items[0].company.kind, "topic");
  const p = (await call("GET", "/api/plazas")).data;
  assert.equal(p.jobs.find((j) => j.key === "nurse").voting, 1);
  assert.equal(p.regions.length, 17);
  assert.ok(p.rateUnits.some((u) => u.key === "day"));
});

test("임금표: a market rate is adopted by three people, gives its sender a pass, and adds to the 광장 시세", async () => {
  const { call } = plaza();
  const add = (n, extra = {}) => call("POST", "/api/rates", { device: device(n), job: "construction", region: "31", trade: "형틀목수", unit: "day", amount: 250000, ...extra }, from(n));
  const vote = (n, id, ok) => call("POST", `/api/rates/${id}/vote`, { device: device(n), ok }, from(n));
  assert.equal((await add(1, { amount: 2500 })).data.error, "setup");
  assert.equal((await add(1, { note: "반장 010-1234-5678" })).data.error, "phone");
  assert.equal((await add(1, { trade: "가" })).data.error, "trade");
  const a = await add(1, { note: "식대 별도" });
  assert.equal(a.status, 201);
  // Sending again changes it while it waits.
  const again = await add(1, { amount: 260000, note: "식대 별도" });
  assert.equal(again.data.id, a.data.id);
  assert.equal((await vote(1, a.data.id, true)).data.error, "own");
  await vote(2, a.data.id, true);
  const done = await vote(3, a.data.id, true);
  assert.equal(done.data.status, "adopted");
  const passes = (await call("GET", "/api/passes", undefined, { "x-device": device(1) })).data;
  assert.equal(passes.items[0].why, "rate");
  const list = (await call("GET", "/api/rates?job=construction&region=41", undefined, { "x-device": device(2) })).data;
  assert.deepEqual(list.groups.map((g) => [g.trade, g.unit, g.n, g.mid]), [["형틀목수", "day", 1, 260000]]);
  assert.deepEqual([list.entries[0].mine, list.entries[0].myVote, list.entries[0].note], [false, true, "식대 별도"]);
  assert.equal((await add(1, { amount: 270000 })).status, 409);
  // Taken back, it stays as everyone's.
  assert.equal((await call("DELETE", `/api/rates/${a.data.id}`, undefined, { "x-device": device(1) })).data.kept, true);
  assert.equal((await call("GET", "/api/rates?job=construction")).data.groups[0].n, 1);
});

test("임금표: a rate far from the adopted ones waits for an admin", async () => {
  const { call } = plaza();
  const add = (n, amount) => call("POST", "/api/rates", { device: device(n), job: "construction", region: "11", trade: "철근공", unit: "day", amount }, from(n));
  const vote = (n, id) => call("POST", `/api/rates/${id}/vote`, { device: device(n), ok: true }, from(n));
  for (const [n, amount] of [[1, 230000], [4, 240000], [7, 250000]]) {
    const r = await add(n, amount);
    await vote(n + 1, r.data.id);
    assert.equal((await vote(n + 2, r.data.id)).data.status, "adopted");
  }
  const far = await add(20, 900000);
  await vote(21, far.data.id);
  assert.equal((await vote(22, far.data.id)).data.status, "review");
  const h = await signIn(call);
  const waiting = (await call("GET", "/api/admin/rates?status=review", undefined, h)).data;
  assert.deepEqual(waiting.rates.map((r) => r.amount), [900000]);
  assert.equal((await call("POST", `/api/admin/rates/${far.data.id}`, { status: "hidden" }, h)).data.status, "hidden");
  assert.equal((await call("GET", "/api/rates?job=construction")).data.entries.length, 3);
});

test("내 활동 shows what this phone sent and where it stands, to that phone only", async () => {
  const { call } = plaza();
  const t = await talkOn(call);
  await t.say(1, "제안이에요", { kind: "proposal", change: [{ field: "break", want: "휴게 90분" }] });
  await call("POST", "/api/rates", { device: device(1), job: "hourly", trade: "편의점", unit: "hour", amount: 10500 }, from(1));
  const mine = (await call("GET", "/api/activity", undefined, { "x-device": device(1) })).data;
  assert.equal(mine.reports.length, 1);
  assert.equal(mine.reports[0].status, "pending");
  assert.equal(mine.proposals.length, 1);
  assert.equal(mine.rates[0].mine, true);
  assert.equal((await call("GET", "/api/activity")).status, 400);
});

test("deleting everything a phone sent takes its votes off others' posts", async () => {
  const { call, DB } = plaza();
  const t = await talkOn(call);
  const p = (await t.say(2, "맞는 말")).data.id;
  await t.vote(3, p, true);
  await t.vote(4, p, false);
  await call("DELETE", "/api/mine", undefined, { "x-device": device(3) });
  const row = DB.db.prepare("SELECT agree, disagree FROM posts WHERE id = ?").get(p);
  assert.deepEqual([row.agree, row.disagree], [0, 1]);
});
