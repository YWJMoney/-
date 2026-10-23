// [COMM-11][SRV-08] 1.23 광장·문의 UGC: what became of a flag for its flagger and the writer, the end of
// 쓰기 막기, 확인 중 for 개인 정보 신고, how many posts are left, why an 임금표 entry waits, 이 사람 글 안
// 보기 in 모두의 문의, and the writer key of its own (AUTHOR_KEY). `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { prune } from "../src/index.js";
import { server, report, device, from, signIn } from "./helpers.mjs";

const NOW = Date.parse("2026-11-05T03:00:00Z");
const mine = (n) => ({ "x-device": device(n) });

async function talk(call) {
  const a = await call("POST", "/api/reports", report(1), from(1));
  const c = a.data.company.id;
  const g = a.data.setup;
  const say = (n, body) => call("POST", "/api/posts", { device: device(n), company: c, setup: g, body }, from(n));
  const read = (n, hide) => call("GET", `/api/companies/${c}/setups/${g}/posts`, undefined, { ...mine(n), ...(hide ? { "x-hide": hide } : {}) });
  const flag = (id, n, reason = "abuse") => call("POST", `/api/posts/${id}/flag`, { device: device(n), reason }, from(n));
  const news = async (n, since) => (await call("GET", `/api/plaza/news${since ? `?since=${since}` : ""}`, undefined, mine(n))).data.items;
  return { c, g, say, read, flag, news };
}

test("[COMM-11] flaggers hear what became of the post, and its writer hears it too", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = server({ plaza: true });
  const h = await signIn(call);
  const { say, flag, news } = await talk(call);
  const bad = (await say(2, "여기 사람들 다 바보예요")).data.id;
  for (const n of [3, 4, 5]) await flag(bad, n);
  t.mock.timers.tick(1000);
  // Hidden by three flags: each flagger hears 가려짐, the writer that it is hidden (and can appeal).
  assert.deepEqual((await news(3)).map((i) => [i.kind, i.result]), [["flag_result", "hidden"]]);
  assert.deepEqual((await news(2)).map((i) => [i.kind, i.result, i.post]), [["post_result", "hidden", bad]]);
  assert.deepEqual(await news(6), []);
  // The developer keeps it after all: the flaggers hear 그대로 둠, the writer that it shows again.
  const since = new Date(Date.now()).toISOString();
  t.mock.timers.tick(1000);
  await call("POST", `/api/admin/posts/${bad}`, { action: "show" }, h);
  assert.deepEqual((await news(4, since)).map((i) => [i.kind, i.result]), [["flag_result", "kept"]]);
  assert.deepEqual((await news(2, since)).map((i) => [i.kind, i.result]), [["post_result", "shown"]]);
  // Flagged again and deleted.
  const since2 = new Date(Date.now()).toISOString();
  t.mock.timers.tick(1000);
  await flag(bad, 7, "spam");
  await call("POST", `/api/admin/posts/${bad}`, { action: "delete" }, h);
  assert.deepEqual((await news(7, since2)).map((i) => [i.kind, i.result]), [["flag_result", "deleted"]]);
  assert.deepEqual((await news(2, since2)).map((i) => [i.kind, i.result]), [["post_result", "deleted"]]);
});

test("[COMM-11] the end of 쓰기 막기 is news once, and results go after 30 days", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, env, DB } = server({ plaza: true });
  const h = await signIn(call);
  const { say, news } = await talk(call);
  const id = (await say(2, "광고 글이에요")).data.id;
  await call("POST", `/api/admin/posts/${id}`, { action: "mute", reason: "spam" }, h);
  const since = new Date(Date.now()).toISOString();
  t.mock.timers.tick(31 * 24 * 3600e3);
  await prune(env, Date.now());
  const items = await news(2, since);
  assert.ok(items.some((i) => i.kind === "unmuted"), JSON.stringify(items));
  await prune(env, Date.now() + 31 * 24 * 3600e3);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM plaza_results").get().n, 0);
});

test("[COMM-11] a 개인 정보 flag shows the post as 확인 중, and a writer sees how many posts are left today", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = server({ plaza: true });
  const { say, read, flag } = await talk(call);
  const p = (await say(2, "김민지 반장님 근무가 이상해요")).data.id;
  await say(3, "야간은 22시부터예요");
  await flag(p, 4, "personal");
  const posts = (await read(5)).data.posts;
  assert.equal(posts.find((x) => x.id === p).review, true);
  assert.equal(posts.find((x) => x.id !== p).review, undefined);
  const left = (await read(2)).data.left;
  assert.equal(left.posts, 19);
  // Without a phone nothing is said about limits.
  assert.equal((await call("GET", `/api/companies/${(await read(2)).data.company.id}/setups/${(await read(2)).data.setup}/posts`)).data.left, undefined);
});

test("[COMM-11] the sender's own 임금표 entry says why it waits", async () => {
  const { call, DB } = server({ plaza: true });
  const sent = await call("POST", "/api/rates", { device: device(1), job: "construction", region: "11", trade: "철근공", unit: "day", amount: 250000 }, from(1));
  assert.equal(sent.status, 201);
  DB.db.prepare("UPDATE rates SET status = 'review'").run();
  const view = (n) => call("GET", "/api/rates?job=construction", undefined, mine(n));
  assert.equal((await view(1)).data.entries[0].waitingWhy, "far");
  assert.equal((await view(2)).data.entries[0].waitingWhy, undefined);
});

test("[COMM-11] 이 사람 글 안 보기 in 모두의 문의: the phone's own marks hide a writer's public questions", async () => {
  const { call } = server();
  const ask = async (n, text) => (await call("POST", "/api/inquiries", {
    v: 1, device: device(n), kind: "bug", text, info: { app: "1.23.0 (34)" }, public: true,
  }, from(n))).data.id;
  const rude = await ask(1, "이 앱 만든 사람은 바보예요 알람이 안 울려요");
  await ask(2, "공유 링크가 안 열려요");
  const list = (await call("GET", "/api/inquiries/public", undefined, mine(3))).data.inquiries;
  const mark = list.find((i) => i.id === rude).author;
  assert.match(mark, /^[A-Za-z0-9_-]{64}$/);
  const hidden = (await call("GET", "/api/inquiries/public", undefined, { ...mine(3), "x-hide": mark })).data;
  assert.deepEqual(hidden.inquiries.map((i) => i.id).includes(rude), false);
  assert.equal(hidden.hiddenByYou, 1);
  assert.equal((await call("GET", `/api/inquiries/public/${rude}`, undefined, { ...mine(3), "x-hide": mark })).status, 404);
  // The thread's mark hides the same writer, and one's own question carries none.
  const threadMark = (await call("GET", `/api/inquiries/public/${rude}`, undefined, mine(3))).data.author;
  assert.equal((await call("GET", "/api/inquiries/public", undefined, { ...mine(3), "x-hide": threadMark })).data.hiddenByYou, 1);
  assert.equal((await call("GET", `/api/inquiries/public/${rude}`, undefined, mine(1))).data.author, undefined);
  assert.equal((await call("GET", "/api/inquiries/public", undefined, { ...mine(1), "x-hide": mark })).data.inquiries.length, 2);
});

test("[SRV-08] with AUTHOR_KEY the marks phones keep still work, and changing RATE_SALT no longer empties them", async () => {
  // Before: marks sealed with the salt.
  const before = server({ plaza: true });
  const t1 = await talk(before.call);
  await t1.say(2, "이 회사 규칙은 엉터리예요");
  await t1.say(3, "야간은 22시부터예요");
  const mark = (await t1.read(1)).data.posts.find((p) => p.body.includes("엉터리")).author;
  // AUTHOR_KEY is put in on the same database: the old mark still hides the writer.
  const after = server({ plaza: true, DB: before.DB, AUTHOR_KEY: "a-separate-author-key-0123456789" });
  const read = (hide, call = after.call) => call("GET", `/api/companies/${t1.c}/setups/${t1.g}/posts`, undefined, { ...mine(1), "x-hide": hide });
  assert.equal((await read(mark)).data.hiddenByYou, 1);
  // A mark made now survives a new RATE_SALT.
  const fresh = (await read("")).data.posts.find((p) => p.body.includes("엉터리")).author;
  const newSalt = server({ plaza: true, DB: before.DB, AUTHOR_KEY: "a-separate-author-key-0123456789", RATE_SALT: "a-brand-new-salt-after-a-leak-xx" });
  assert.equal((await read(fresh, newSalt.call)).data.hiddenByYou, 1);
});

test("[SRV-08] the same X-Request-Id sent again gets the first answer, not a second post or question", async () => {
  const { call, DB } = server({ plaza: true });
  const a = await call("POST", "/api/reports", report(1), from(1));
  const body = { device: device(2), company: a.data.company.id, setup: a.data.setup, body: "야간은 22시부터예요" };
  const id = { ...from(2), "x-request-id": "req-0123456789abcdef" };
  const first = await call("POST", "/api/posts", body, id);
  const again = await call("POST", "/api/posts", body, id);
  assert.equal(first.status, 201);
  assert.deepEqual(again.data, first.data);
  assert.equal(again.headers.get("x-replayed"), "1");
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM posts").get().n, 1);
  // Another id is another post; no id at all is as before.
  await call("POST", "/api/posts", body, { ...from(2), "x-request-id": "req-fedcba9876543210" });
  await call("POST", "/api/posts", body, from(2));
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM posts").get().n, 3);
  const q = { v: 1, device: device(3), kind: "bug", text: "알람이 늦게 울려요", info: { app: "1.23.0 (34)" } };
  const qid = { ...from(3), "x-request-id": "ask-0123456789abcdef" };
  assert.equal((await call("POST", "/api/inquiries", q, qid)).data.id, (await call("POST", "/api/inquiries", q, qid)).data.id);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiries").get().n, 1);
  // Nothing in the kept answers ties them to an install.
  assert.ok(!JSON.stringify(DB.db.prepare("SELECT * FROM requests").all()).includes(device(2)));
});
