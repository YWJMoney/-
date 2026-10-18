// [ADMIN-02] Questions from the app and the developer's answers: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../src/index.js";
import { server, device, from, signIn, report, ORIGIN } from "./helpers.mjs";

const sha = (s) => createHash("sha256").update(s).digest("hex");

const question = (n, change = {}) => ({
  v: 1,
  device: device(n),
  kind: "bug",
  text: "알람이 가끔 늦게 울려요. 잠금 화면에서 소리가 안 나요",
  info: { app: "1.12.0 (18)", model: "samsung SM-F956N", android: "16 (API 36)" },
  ...change,
});

const mine = (n) => ({ "x-device": device(n) });

test("a question goes in with what the user agreed to send, and only its phone sees it", async () => {
  const { call, DB } = server();
  const res = await call("POST", "/api/inquiries", question(1, { info: { app: "1.12.0 (18)", diag: "다음 알람: 10.19 06:00 · 주간" } }));
  assert.equal(res.status, 201);
  assert.equal(res.data.status, "open");
  assert.deepEqual(res.data.messages.map((m) => [m.from, m.text]), [["user", question(1).text]]);
  // The phone reads it back by its install id; another phone, or none, finds nothing.
  assert.equal((await call("GET", `/api/inquiries/${res.data.id}`, undefined, mine(1))).data.id, res.data.id);
  assert.equal((await call("GET", `/api/inquiries/${res.data.id}`, undefined, mine(2))).status, 404);
  assert.equal((await call("GET", `/api/inquiries/${res.data.id}`)).status, 400);
  assert.deepEqual((await call("GET", "/api/inquiries", undefined, mine(1))).data.inquiries.map((i) => i.id), [res.data.id]);
  assert.deepEqual((await call("GET", "/api/inquiries", undefined, mine(2))).data.inquiries, []);
  // The install id itself is never stored, and the app's view holds nothing of what was sent with it.
  assert.ok(!DB.dump().includes(device(1)));
  assert.ok(DB.dump().includes(sha(`device|${device(1)}`).slice(0, 40)));
  assert.equal(res.data.context, undefined);
});

test("contacts and amounts are refused with the field; so is anything it does not know", async () => {
  const { call } = server();
  const cases = [
    [{ text: "010-1234-5678로 연락 주세요" }, "phone", "text"],
    [{ text: "me@example.com 으로 답 주세요" }, "email", "text"],
    [{ text: "기본급 250만원인데 계산이 달라요" }, "amount", "text"],
    [{ text: "   " }, "text", "text"],
    [{ text: "가".repeat(2001) }, "text", "text"],
    [{ kind: "spam" }, "kind", "kind"],
    [{ v: 2 }, "version", "v"],
    [{ device: "x" }, "device", "device"],
    [{ info: "1.12.0" }, "info", "info"],
    [{ info: { model: "a".repeat(81) } }, "info", "model"],
    [{ info: { app: "me@example.com" } }, "info", "app"],
  ];
  for (const [change, error, field] of cases) {
    const res = await call("POST", "/api/inquiries", question(1, change));
    assert.equal(res.status, 400, JSON.stringify(change));
    assert.deepEqual(res.data, { error, field }, JSON.stringify(change));
  }
  // Ratios and times are fine; fields the app does not send are dropped.
  const ok = await call("POST", "/api/inquiries", question(1, {
    kind: "question", text: "대근 12시간이면 기본급의 15%가 맞나요? 19~07시 근무예요", info: { app: "1.12.0", name: "민지" },
  }));
  assert.equal(ok.status, 201);
});

test("the developer answers in the developer app and the phone sees the answer", async () => {
  const { call } = server();
  const q = (await call("POST", "/api/inquiries", question(1, { info: { app: "1.12.0 (18)", diag: "놓쳤을 수 있는 알람: 없음" } }), from(1))).data;
  await call("POST", "/api/inquiries", question(2, { kind: "idea", text: "주간 시작을 일요일로 바꾸고 싶어요" }), from(2));
  const admin = await signIn(call);
  assert.equal((await call("GET", "/api/admin/inquiries")).status, 401);
  assert.equal((await call("GET", "/api/admin/overview", undefined, admin)).data.inquiries, 2);

  const list = (await call("GET", "/api/admin/inquiries", undefined, admin)).data;
  assert.deepEqual(list.counts, { open: 2, answered: 0, closed: 0 });
  const item = list.inquiries.find((i) => i.id === q.id);
  assert.deepEqual([item.kind, item.status, item.waiting, item.count, item.appVersion], ["bug", "open", true, 1, "1.12.0 (18)"]);
  assert.equal(item.preview, question(1).text);
  assert.deepEqual((await call("GET", "/api/admin/inquiries?q=일요일", undefined, admin)).data.inquiries.map((i) => i.kind), ["idea"]);

  const detail = (await call("GET", `/api/admin/inquiries/${q.id}`, undefined, admin)).data;
  assert.deepEqual(detail.context, { 앱: "1.12.0 (18)", "알람 점검": "놓쳤을 수 있는 알람: 없음" });

  // Answering needs the app's own header like every change; the reply is the developer's.
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/reply`, { text: "확인했어요" }, { cookie: admin.cookie })).status, 403);
  const answered = await call("POST", `/api/admin/inquiries/${q.id}/reply`,
    { text: "배터리 최적화에서 밤낮을 빼 주세요. 다음 업데이트에서 고칠게요" }, admin);
  assert.equal(answered.status, 200);
  assert.equal(answered.data.status, "answered");
  assert.deepEqual(answered.data.messages.map((m) => [m.from, m.by ?? null]), [["user", null], ["admin", "최고 관리자"]]);

  const seen = (await call("GET", `/api/inquiries/${q.id}`, undefined, mine(1))).data;
  assert.equal(seen.status, "answered");
  assert.deepEqual(seen.messages.map((m) => m.from), ["user", "admin"]);
  assert.equal(seen.messages[1].by, undefined);

  // A follow-up opens it again for the developer.
  const more = await call("POST", `/api/inquiries/${q.id}/messages`, { text: "빼 봤는데 똑같아요" }, mine(1));
  assert.equal(more.status, 201);
  assert.equal(more.data.status, "open");
  assert.equal((await call("POST", `/api/inquiries/${q.id}/messages`, { text: "제 거예요" }, mine(2))).status, 404);
  const waiting = (await call("GET", "/api/admin/inquiries?status=open", undefined, admin)).data.inquiries.find((i) => i.id === q.id);
  assert.deepEqual([waiting.waiting, waiting.count], [true, 3]);

  // Closed with the answer, then reopened by hand.
  const closed = await call("POST", `/api/admin/inquiries/${q.id}/reply`, { text: "고친 버전을 올렸어요", close: true }, admin);
  assert.equal(closed.data.status, "closed");
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/status`, { status: "open" }, admin)).data.status, "open");
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/status`, { status: "gone" }, admin)).status, 400);
  assert.equal((await call("GET", `/api/admin/inquiries/${"A".repeat(22)}`, undefined, admin)).status, 404);
});

test("the answer names the admin who wrote it, and only admins given inquiries see them", async () => {
  const { call } = server();
  const q = (await call("POST", "/api/inquiries", question(1))).data;
  const owner = await signIn(call);
  const app = { "x-admin": "1", origin: ORIGIN };
  const account = async (email, name, inquiries) => {
    const perms = { review: "none", inquiries, companies: "none", rewards: "none", settings: "none", audit: "none" };
    const link = (await call("POST", "/api/admin/invites", { email, perms }, owner)).data.link;
    assert.equal((await call("POST", "/api/admin/join", { token: link.split("#")[1], name, password: "support-desk-2026" }, app)).status, 201);
    const id = (await call("GET", "/api/admin/admins", undefined, owner)).data.admins.find((a) => a.email === email).id;
    await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
    const res = await call("POST", "/api/admin/login", { email, password: "support-desk-2026" }, app);
    return { cookie: res.headers.get("set-cookie").split(";")[0], ...app };
  };
  const junho = await account("junho@example.com", "준호", "edit");
  const seojin = await account("seojin@example.com", "서진", "none");
  assert.equal((await call("GET", "/api/admin/inquiries", undefined, seojin)).status, 403);
  assert.equal((await call("GET", "/api/admin/overview", undefined, seojin)).data.inquiries, undefined);
  assert.equal((await call("GET", "/api/admin/overview", undefined, junho)).data.inquiries, 1);
  const res = await call("POST", `/api/admin/inquiries/${q.id}/reply`, { text: "확인했어요" }, junho);
  assert.equal(res.data.messages[1].by, "준호");
});

test("a phone sends a few questions a day, and deletes them with or without the rest", async () => {
  const { call, DB } = server();
  const ids = [];
  for (let i = 0; i < 5; i++) {
    const res = await call("POST", "/api/inquiries", question(1, { text: `질문 ${i + 1}번이에요` }));
    assert.equal(res.status, 201);
    ids.push(res.data.id);
  }
  assert.equal((await call("POST", "/api/inquiries", question(1))).status, 429);
  assert.equal((await call("DELETE", `/api/inquiries/${ids[0]}`, undefined, mine(2))).status, 404);
  assert.equal((await call("DELETE", `/api/inquiries/${ids[0]}`, undefined, mine(1))).status, 204);
  assert.equal((await call("GET", "/api/inquiries", undefined, mine(1))).data.inquiries.length, 4);
  const gone = await call("DELETE", "/api/mine", undefined, mine(1));
  assert.deepEqual(gone.data, { reports: 0, votes: 0, inquiries: 4 });
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiry_messages").get().n, 0);
});

test("a thread with nothing new for half a year is deleted", async (t) => {
  const { call, DB } = server();
  const old = (await call("POST", "/api/inquiries", question(1))).data;
  t.mock.timers.enable({ apis: ["Date"], now: Date.now() + 181 * 24 * 60 * 60 * 1000 });
  await call("POST", "/api/inquiries", question(2));
  assert.equal((await call("GET", `/api/inquiries/${old.id}`, undefined, mine(1))).status, 404);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiries").get().n, 1);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM inquiry_messages").get().n, 1);
});

test("the hourly clean-up deletes what is only kept for a while, with nobody sending", async (t) => {
  const { call, env, DB } = server();
  await call("POST", "/api/reports", report(1));
  await call("POST", "/api/inquiries", question(1));
  await signIn(call);
  const later = Date.now() + 181 * 24 * 60 * 60 * 1000;
  t.mock.timers.enable({ apis: ["Date"], now: later });
  const waits = [];
  await worker.scheduled({ cron: "17 * * * *", scheduledTime: later }, env, { waitUntil: (p) => waits.push(p) });
  await Promise.all(waits);
  const n = (sql) => DB.db.prepare(sql).get().n;
  assert.equal(n("SELECT COUNT(*) AS n FROM reports WHERE net IS NOT NULL"), 0);
  assert.equal(n("SELECT COUNT(*) AS n FROM counters"), 0);
  assert.equal(n("SELECT COUNT(*) AS n FROM admin_sessions"), 0);
  assert.equal(n("SELECT COUNT(*) AS n FROM inquiries"), 0);
  assert.equal(n("SELECT COUNT(*) AS n FROM inquiry_messages"), 0);
  // The report itself stays: only its network link went.
  assert.equal(n("SELECT COUNT(*) AS n FROM reports"), 1);
});
