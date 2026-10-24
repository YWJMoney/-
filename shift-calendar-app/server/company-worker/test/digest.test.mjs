// [SRV-10][COMM-11] 1.23 혁신: 밤낮 소식 in one ask (N3), 두 달 이상 확인됨 (N7) and features resting
// themselves before the free limit (N8). `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { autoRest, syncShareSwitch } from "../src/index.js";
import { server, report, device, from, signIn } from "./helpers.mjs";

const NOW = Date.parse("2026-11-05T03:00:00Z");
const HOUR = 3600e3;
const mine = (n) => ({ "x-device": device(n) });

test("[SRV-10] 밤낮 소식: plaza news, new answers and new notices in one answer, only this phone's", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call } = server();
  const h = await signIn(call);
  const q = (await call("POST", "/api/inquiries", { v: 1, device: device(1), kind: "bug", text: "알람이 늦게 울려요", info: { app: "1.23.0 (34)" } }, from(1))).data;
  const since = new Date(Date.now()).toISOString();
  t.mock.timers.tick(1000);
  await call("POST", `/api/admin/inquiries/${q.id}/reply`, { text: "배터리 설정을 봐 주세요" }, h);
  await call("POST", "/api/admin/notices", { title: "새 판 안내", body: "1.23이 나왔어요.", level: "info",
    from: new Date(Date.now()).toISOString(), until: new Date(Date.now() + 3 * 24 * HOUR).toISOString(), action: { kind: "WHATS_NEW" } }, h);
  t.mock.timers.tick(1000);
  const d = (await call("GET", `/api/digest?since=${since}`, undefined, mine(1))).data;
  assert.deepEqual(d.answers.map((a) => [a.inquiry, a.count]), [[q.id, 1]]);
  assert.deepEqual(d.notices.map((n) => [n.title, n.action.kind]), [["새 판 안내", "WHATS_NEW"]]);
  assert.deepEqual(d.plaza, []);
  // Another phone hears of the notice but not of someone else's answer; nothing new after now.
  assert.deepEqual((await call("GET", `/api/digest?since=${since}`, undefined, mine(2))).data.answers, []);
  const after = (await call("GET", `/api/digest?since=${d.now}`, undefined, mine(1))).data;
  assert.deepEqual([after.answers, after.notices], [[], []]);
  assert.equal((await call("GET", "/api/digest")).status, 400);
});

test("[COMM-11] 두 달 이상 확인됨 needs pay-slip checks from two networks in two months", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, DB } = server();
  const a = await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  const look = async () => (await call("GET", `/api/companies/${a.data.company.id}`)).data.setups[0];
  assert.equal((await look()).checkedLong, undefined);
  // A second check from another network, but in the same month: not yet.
  DB.db.prepare("INSERT INTO votes (company_id, grp, device, cluster, ok, verified, why, net, at) VALUES (?, ?, 'v2', 'other-net', 1, 1, NULL, NULL, ?)")
    .run(a.data.company.id, a.data.setup, NOW);
  assert.equal((await look()).checkedLong, undefined);
  // The same network a month later: still not.
  DB.db.prepare("UPDATE votes SET cluster = (SELECT cluster FROM reports LIMIT 1), at = ? WHERE device = 'v2'").run(NOW + 40 * 24 * HOUR);
  assert.equal((await look()).checkedLong, undefined);
  // Another network a month later: yes.
  DB.db.prepare("UPDATE votes SET cluster = 'other-net' WHERE device = 'v2'").run();
  assert.equal((await look()).checkedLong, true);
});

test("[SRV-10] over 90% of the free D1 day, 사용 통계 then 문의 사진 rest with a notice, and come back the next day", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const { call, env, DB } = server();
  const h = await signIn(call);
  // The developer switched 광장 소식 off by hand: the job leaves it alone.
  await call("POST", "/api/admin/switches", { key: "PLAZA_NEWS", off: true }, h);
  const day = new Date(NOW).toISOString().slice(0, 10);
  DB.db.prepare("INSERT OR REPLACE INTO counters (key, count, expires) VALUES (?, 95000, ?)").run(`meter|${day}`, NOW + 3 * 24 * HOUR);
  const off = async () => (await call("GET", "/api/config")).data.off;
  assert.deepEqual((await autoRest(env, NOW)).rested, ["USAGE_STATS"]);
  assert.deepEqual((await off()).sort(), ["PLAZA_NEWS", "USAGE_STATS"]);
  assert.match((await call("GET", "/api/config")).data.notices[0].body, /사용 통계 받기/);
  // The same hour again changes nothing; an hour on, still over, the next one rests.
  assert.deepEqual((await autoRest(env, NOW + 1000)).rested, ["USAGE_STATS"]);
  assert.deepEqual((await autoRest(env, NOW + HOUR)).rested, ["USAGE_STATS", "INQUIRY_PHOTOS"]);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM audit WHERE action = 'auto_rest'").get().n, 2);
  // Next day, a new meter: both come back and their notices go; the hand-made switch stays off.
  const next = NOW + 24 * HOUR;
  t.mock.timers.setTime(next);
  assert.deepEqual((await autoRest(env, next)).rested, []);
  assert.deepEqual(await off(), ["PLAZA_NEWS"]);
  assert.deepEqual((await call("GET", "/api/config")).data.notices, []);
});

test("[SRV-10] the share server's KV writes over 90% rest 근무표 링크 새로 만들기, and the share server hears it", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: NOW });
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url, init = {}) => {
    sent.push([init.method ?? "GET", String(url), init.body ?? null]);
    if (String(url).includes("/api/admin/stats")) return Response.json({ days: [{ day: "2026-11-05", writes: 950 }] });
    return new Response(null, { status: 204 });
  });
  const { call, env } = server({ SHARE_BASE_URL: "https://share.example", SHARE_ADMIN_TOKEN: "share-admin-token-0123456789" });
  await call("GET", "/api/config");
  assert.deepEqual((await autoRest(env, NOW)).rested, ["SHARE_CREATE"]);
  assert.equal(await syncShareSwitch(env), true);
  assert.deepEqual(sent.at(-1), ["PUT", "https://share.example/api/admin/flags", JSON.stringify({ createOff: true })]);
});
