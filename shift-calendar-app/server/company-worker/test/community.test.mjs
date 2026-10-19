// [COMM-06][COMM-07] Every company's rule sets in one list, the talk on each, 신고 and the developer's moderation: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { trust, prune } from "../src/index.js";
import { server, report, device, from, signIn, ORIGIN } from "./helpers.mjs";

const sha = (s) => createHash("sha256").update(s).digest("hex");
const APP = { "x-admin": "1", origin: ORIGIN };

/** One coworker's rule set, still being voted on, and how to post in its talk. */
async function oneRuleSet(call, company = "예시정밀") {
  const a = await call("POST", "/api/reports", report(1, { company }), from(1));
  const c = a.data.company.id;
  const g = a.data.setup;
  const say = (n, body, net = n) => call("POST", "/api/posts", { device: device(n), company: c, setup: g, body }, from(net));
  const read = (n) => call("GET", `/api/companies/${c}/setups/${g}/posts`, undefined, n ? { "x-device": device(n) } : {});
  return { a: a.data, c, g, say, read };
}

test("trust is the share who agree, times how full the points are", () => {
  assert.deepEqual(trust({ persons: 0, bad: 0, points: 0 }, 3), { trust: 0, agree: 0 });
  assert.deepEqual(trust({ persons: 1, bad: 0, points: 1 }, 3), { trust: 33, agree: 100 });
  assert.deepEqual(trust({ persons: 3, bad: 1, points: 5 }, 3), { trust: 75, agree: 75 });
  assert.deepEqual(trust({ persons: 2, bad: 2, points: 4 }, 4), { trust: 50, agree: 50 });
});

test("the board lists rule sets being voted on and adopted ones, but not hidden or turned-down ones", async () => {
  const { call } = server();
  const h = await signIn(call);
  // 예시정밀: one sender, still being voted on.
  const one = await oneRuleSet(call);
  // 가나다화학: two coworkers on two networks, adopted.
  await call("POST", "/api/reports", report(2, { company: "가나다화학", verified: true }), from(2));
  const adopted = await call("POST", "/api/reports", report(3, { company: "가나다화학" }), from(3));
  assert.equal(adopted.data.status, "adopted");
  // 라마바전자: turned down by the developer.
  const down = await call("POST", "/api/reports", report(4, { company: "라마바전자" }), from(4));
  assert.equal((await call("POST", `/api/admin/groups/${down.data.company.id}/${down.data.setup}/reject`, { reason: "wrong" }, h)).status, 200);
  // 사아자물류: the company is hidden.
  const hid = await call("POST", "/api/reports", report(5, { company: "사아자물류" }), from(5));
  await call("POST", `/api/admin/companies/${hid.data.company.id}`, { hidden: true }, h);

  const voting = (await call("GET", "/api/board")).data;
  assert.equal(voting.tab, "voting");
  assert.deepEqual(voting.items.map((i) => i.company.name), ["예시정밀"]);
  const v = voting.items[0];
  assert.deepEqual([v.id, v.status, v.points, v.persons, v.bad, v.threshold, v.trust, v.agree, v.posts], [one.g, "pending", 1, 1, 0, 3, 33, 100, 0]);
  assert.match(v.gist, /^주간 07:00~19:00/);
  assert.equal(v.cycle, "순서 8일");
  assert.equal(v.pay, true);
  assert.equal(voting.next, null);

  const done = (await call("GET", "/api/board?tab=adopted")).data;
  assert.deepEqual(done.items.map((i) => [i.company.name, i.status, i.version]), [["가나다화학", "adopted", 1]]);
  // Nothing here says who sent it.
  const text = JSON.stringify([voting, done]);
  assert.ok(!text.includes(sha(`device|${device(1)}`).slice(0, 40)));
  assert.ok(!text.includes("device"));
});

test("the board finds a company by name or another name for it, in either tab", async () => {
  const { call } = server();
  const h = await signIn(call);
  await oneRuleSet(call);
  await call("POST", "/api/reports", report(2, { company: "가나다화학", verified: true }), from(2));
  const adopted = await call("POST", "/api/reports", report(3, { company: "가나다화학" }), from(3));
  await call("POST", `/api/admin/companies/${adopted.data.company.id}/aliases`, { name: "GND케미칼" }, h);
  const names = async (q, tab = "adopted") => (await call("GET", `/api/board?tab=${tab}&q=${encodeURIComponent(q)}`)).data.items.map((i) => i.company.name);
  assert.deepEqual(await names("(주)가나다"), ["가나다화학"]);
  assert.deepEqual(await names("gnd"), ["가나다화학"]);
  assert.deepEqual(await names("예시"), []);
  assert.deepEqual(await names("예시", "voting"), ["예시정밀"]);
  assert.deepEqual(await names("가나다", "voting"), []);
  // One letter, or only symbols, narrows nothing.
  assert.deepEqual(await names("가"), ["가나다화학"]);
  assert.deepEqual(await names("%"), ["가나다화학"]);
  assert.equal((await call("GET", "/api/board?tab=adopted&q=가나다")).data.q, "가나다");
});

test("anyone can talk about a rule set; each writer is 참여자 N of that talk with what they said about it", async () => {
  const { call } = server();
  const { c, g, say, read } = await oneRuleSet(call);
  // The sender, someone who agrees, someone who does not.
  // 3 points but only 67% agree: it stays up for a vote instead of being adopted on its own.
  await call("POST", "/api/votes", { device: device(3), company: c, setup: g, ok: false, why: "old" }, from(3));
  const v = await call("POST", "/api/votes", { device: device(2), company: c, setup: g, ok: true, verified: true }, from(2));
  assert.deepEqual([v.data.status, v.data.points, v.data.agree], ["pending", 3, 67]);
  assert.equal((await say(1, "저희 2팀은 이렇게 돌아요")).status, 201);
  assert.equal((await say(2, "명세서랑 맞춰 봤는데 맞아요")).status, 201);
  assert.equal((await say(3, "올해부터 야간 시작이 바뀌었어요\n18:30부터예요")).status, 201);
  assert.equal((await say(1, "아 그건 몰랐네요")).status, 201);

  const t = (await read(4)).data;
  assert.deepEqual(t.posts.map((p) => [p.who, p.stance, p.verified, p.mine]), [
    [1, "sender", false, false], [2, "ok", true, false], [3, "bad", false, false], [1, "sender", false, false],
  ]);
  assert.equal(t.posts[2].body, "올해부터 야간 시작이 바뀌었어요\n18:30부터예요");
  assert.deepEqual((await read(2)).data.posts.map((p) => p.mine), [false, true, false, false]);
  // The board counts the talk, and its trust follows the votes.
  const b = (await call("GET", "/api/board")).data.items[0];
  assert.deepEqual([b.posts, b.persons, b.bad, b.agree], [4, 2, 1, 67]);
  // No install id, hash or address is in what everyone reads.
  const text = JSON.stringify(t);
  for (const n of [1, 2, 3]) assert.ok(!text.includes(sha(`device|${device(n)}`).slice(0, 40)));
  assert.ok(!text.includes("198.51.100"));
});

test("a post carries no email, phone number or amount, and spam is limited", async () => {
  const { call } = server();
  const { say, c, g } = await oneRuleSet(call);
  assert.equal((await say(2, "기본급 250만원이에요")).data.error, "amount");
  assert.equal((await say(2, "010-1234-5678로 연락 주세요")).data.error, "phone");
  assert.equal((await say(2, "me@example.com")).data.error, "email");
  assert.equal((await say(2, " ")).data.error, "body");
  assert.equal((await say(2, "가".repeat(501))).data.error, "body");
  assert.equal((await say(2, "야간 가산 50%예요")).status, 201);
  assert.equal((await call("POST", "/api/posts", { device: device(2), company: c, setup: "0123456789abcdef", body: "있나요" }, from(2))).status, 404);
  for (let i = 1; i < 20; i++) assert.equal((await say(2, `글 ${i}번`)).status, 201);
  assert.equal((await say(2, "스물한 번째")).status, 429);
});

test("a writer takes their post back; others cannot", async () => {
  const { call } = server();
  const { say, read } = await oneRuleSet(call);
  const p = (await say(2, "맞아요")).data;
  assert.match(p.id, /^p[A-Za-z0-9]{15}$/);
  assert.equal((await call("DELETE", `/api/posts/${p.id}`, undefined, { "x-device": device(3) })).status, 403);
  assert.equal((await call("DELETE", `/api/posts/${p.id}`, undefined, { "x-device": device(2) })).status, 204);
  assert.deepEqual((await read()).data.posts, []);
});

test("three 신고 hide a post until the developer looks; the developer keeps, hides, deletes or mutes", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-18T09:00:00Z") });
  const { call, DB } = server();
  const h = await signIn(call);
  const { say, read } = await oneRuleSet(call);
  const bad = (await say(2, "여기 사람들 다 바보예요")).data.id;
  const flagIt = (n, reason = "abuse") => call("POST", `/api/posts/${bad}/flag`, { device: device(n), reason }, from(n));
  assert.equal((await call("POST", `/api/posts/${bad}/flag`, { device: device(2) }, from(2))).data.error, "own");
  await flagIt(3);
  await flagIt(3);
  await flagIt(4, "spam");
  assert.equal((await read()).data.posts.length, 1);
  let list = (await call("GET", "/api/admin/posts", undefined, h)).data;
  assert.deepEqual(list.posts.map((p) => [p.flags, p.status]), [[2, "shown"]]);
  assert.deepEqual(list.posts[0].reasons.map((r) => [r.code, r.n]), [["abuse", 1], ["spam", 1]]);
  await flagIt(5);
  // Hidden from everyone but its writer, who sees why.
  assert.deepEqual((await read(6)).data.posts, []);
  const own = (await read(2)).data.posts[0];
  assert.equal(own.hidden.code, "flags");
  assert.equal(own.hidden.text, "신고가 모여 개발자가 확인하고 있어요");
  let o = (await call("GET", "/api/admin/overview", undefined, h)).data;
  assert.equal(o.flaggedPosts, 1);
  assert.equal(o.hideReasons.offtopic, "이 규칙과 상관없는 글이에요");

  // The developer looks and keeps it: shown again, and flags no longer hide it.
  assert.equal((await call("POST", `/api/admin/posts/${bad}`, { action: "show" }, h)).data.status, "shown");
  for (const n of [6, 7, 8]) await flagIt(n);
  assert.equal((await read()).data.posts.length, 1);
  // Then hides it after all, with a reason the writer sees.
  assert.equal((await call("POST", `/api/admin/posts/${bad}`, { action: "hide", reason: "abuse" }, h)).data.status, "hidden");
  assert.equal((await read(2)).data.posts[0].hidden.text, "욕설이나 비방이 있어요");
  list = (await call("GET", "/api/admin/posts?status=hidden", undefined, h)).data;
  assert.deepEqual(list.posts.map((p) => p.id), [bad]);
  assert.equal(list.posts[0].writer.length, 6);

  // Muting: the writer cannot post for 30 days.
  const again = (await say(2, "또 왔어요")).data.id;
  assert.equal((await call("POST", `/api/admin/posts/${again}`, { action: "mute", reason: "spam" }, h)).data.status, "hidden");
  const refused = await say(2, "다시 써요");
  assert.equal(refused.status, 403);
  assert.equal(refused.data.until, "2026-11-17T09:00:00.000Z");
  assert.equal((await read(2)).data.mutedUntil, "2026-11-17T09:00:00.000Z");
  assert.equal((await say(3, "다른 분은 써져요")).status, 201);
  t.mock.timers.tick(31 * 24 * 60 * 60 * 1000);
  assert.equal((await say(2, "한 달 뒤")).status, 201);
  // A week-long sign-in has ended by now.
  const h2 = await signIn(call);

  // Deleting is for good.
  assert.deepEqual((await call("POST", `/api/admin/posts/${bad}`, { action: "delete" }, h2)).data, { deleted: true });
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM post_flags WHERE post_id = ?").get(bad).n, 0);
  assert.equal((await call("POST", `/api/admin/posts/${bad}`, { action: "show" }, h2)).status, 404);
  assert.equal((await call("POST", `/api/admin/posts/${again}`, { action: "pin" }, h2)).data.error, "action");

  const log = (await call("GET", "/api/admin/audit", undefined, h2)).data.entries.map((e) => e.action);
  for (const a of ["post_autohide", "post_show", "post_hide", "post_mute", "post_delete"]) assert.ok(log.includes(a), a);
});

test("the hourly clean-up deletes hidden posts after 90 days and ends 쓰기 막기", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-18T09:00:00Z") });
  const { call, DB, env } = server();
  const h = await signIn(call);
  const { say } = await oneRuleSet(call);
  const hidden = (await say(2, "광고 글이에요")).data.id;
  const kept = (await say(3, "보이는 글이에요")).data.id;
  await call("POST", `/api/admin/posts/${hidden}`, { action: "mute", reason: "spam" }, h);
  await call("POST", `/api/posts/${kept}/flag`, { device: device(4), reason: "spam" }, from(4));
  const n = (sql) => DB.db.prepare(sql).get().n;
  await prune(env, Date.now() + 89 * 24 * 60 * 60 * 1000);
  assert.equal(n("SELECT COUNT(*) AS n FROM posts"), 2);
  assert.equal(n("SELECT COUNT(*) AS n FROM muted"), 0);
  await prune(env, Date.now() + 91 * 24 * 60 * 60 * 1000);
  assert.deepEqual(DB.db.prepare("SELECT id FROM posts").all().map((r) => r.id), [kept]);
  assert.equal(n("SELECT COUNT(*) AS n FROM post_flags"), 1);
});

test("one talk whole, for the developer reviewing a rule set", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { c, g, say } = await oneRuleSet(call);
  await say(1, "첫 글");
  await say(2, "두 번째");
  const other = await oneRuleSet(call, "가나다화학");
  await other.say(3, "다른 회사");
  const talk = (await call("GET", `/api/admin/posts?company=${c}&setup=${g}`, undefined, h)).data;
  assert.equal(talk.status, "talk");
  assert.deepEqual(talk.posts.map((p) => p.body), ["첫 글", "두 번째"]);
  // The review queue says how much is being said about each.
  const q = (await call("GET", "/api/admin/queue", undefined, h)).data;
  assert.deepEqual(q.groups.map((x) => [x.company.name, x.posts]).sort(), [["가나다화학", 1], ["예시정밀", 2]]);
});

test("only admins given 토론 can moderate", async () => {
  const { call } = server();
  const h = await signIn(call);
  const { say } = await oneRuleSet(call);
  const p = (await say(2, "글")).data.id;
  // Invite a 문의 담당: 토론 is view only.
  const inv = await call("POST", "/api/admin/invites", { email: "support@example.com", perms: { inquiries: "edit", community: "view" } }, h);
  const token = inv.data.link.split("#")[1];
  await call("POST", "/api/admin/join", { token, name: "지원", password: "a-long-password-9" }, APP);
  const id = (await call("GET", "/api/admin/admins", undefined, h)).data.admins[0].id;
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, h);
  const login = await call("POST", "/api/admin/login", { email: "support@example.com", password: "a-long-password-9" }, APP);
  const s = { cookie: login.headers.get("set-cookie").split(";")[0], ...APP };
  assert.equal((await call("GET", "/api/admin/posts", undefined, s)).status, 200);
  assert.equal((await call("POST", `/api/admin/posts/${p}`, { action: "hide" }, s)).status, 403);
});

test("deleting everything an install sent takes its posts and 신고 too", async () => {
  const { call, DB } = server();
  const { say } = await oneRuleSet(call);
  const mine = (await say(2, "내 글")).data.id;
  const theirs = (await say(3, "남의 글")).data.id;
  await call("POST", `/api/posts/${theirs}/flag`, { device: device(2), reason: "spam" }, from(2));
  const gone = await call("DELETE", "/api/mine", undefined, { "x-device": device(2) });
  assert.equal(gone.data.posts, 1);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM posts WHERE id = ?").get(mine).n, 0);
  assert.equal(DB.db.prepare("SELECT flags FROM posts WHERE id = ?").get(theirs).flags, 0);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM post_flags").get().n, 0);
  assert.ok(!DB.dump().includes(sha(`device|${device(2)}`).slice(0, 40)));
});
