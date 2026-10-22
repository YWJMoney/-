// [ADMIN-10][ADMIN-11][ADMIN-12] 개발사항 gathered from questions, their history, 처리 and what makes
// admins' work quicker (내가 맡기, notes, shared answers): `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { server, device, from, signIn, ORIGIN } from "./helpers.mjs";

const APP = { "x-admin": "1", origin: ORIGIN };

const question = (n, text = "주간 시작을 일요일로 바꾸고 싶어요") => ({
  v: 1, device: device(n), kind: "idea", text, info: { app: "1.14.2 (40)" },
});

/** An invited, approved admin with [perms]; returns their headers and id. */
async function account(call, owner, email, name, perms, ip) {
  const link = (await call("POST", "/api/admin/invites", { email, perms }, owner)).data.link;
  assert.equal((await call("POST", "/api/admin/join", { token: link.split("#")[1], name, password: "dev-items-2026" }, { ...APP, ...from(ip) })).status, 201);
  const id = (await call("GET", "/api/admin/admins", undefined, owner)).data.admins.find((a) => a.email === email).id;
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
  const res = await call("POST", "/api/admin/login", { email, password: "dev-items-2026" }, { ...APP, ...from(ip) });
  return { id, h: { cookie: res.headers.get("set-cookie").split(";")[0], ...APP } };
}

const SUPPORT = { review: "none", inquiries: "edit", companies: "none", rewards: "none", settings: "none", community: "none", audit: "none" };

test("an admin adds a 개발사항 from a question in one step, and both show the link and who made it", async () => {
  const { call } = server();
  const q = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  const owner = await signIn(call);
  // Invited before 개발사항 existed (no dev in perms): every admin may work on them; 처리 is not given.
  const { h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  assert.deepEqual([(await call("GET", "/api/admin/me", undefined, junho)).data.perms.dev, (await call("GET", "/api/admin/me", undefined, junho)).data.perms.devStatus],
    ["edit", "none"]);

  const made = await call("POST", "/api/admin/dev", { title: "주 시작 요일 고르기", body: "일요일 시작 요청", inquiry: q.id }, junho);
  assert.equal(made.status, 201, made.text);
  const item = made.data;
  assert.match(item.id, /^d[A-Za-z0-9]{15}$/);
  assert.deepEqual([item.status, item.createdBy.name, item.links, item.inquiries.map((i) => i.id)], ["todo", "준호", 1, [q.id]]);
  assert.deepEqual(item.history.map((x) => [x.action, x.actor]).reverse(), [["create", "준호"], ["link", "준호"]]);

  // The question shows the item; the list says it is in one.
  const thread = (await call("GET", `/api/admin/inquiries/${q.id}`, undefined, junho)).data;
  assert.deepEqual(thread.dev.map((d) => [d.id, d.status]), [[item.id, "todo"]]);
  assert.equal((await call("GET", "/api/admin/inquiries", undefined, junho)).data.inquiries[0].dev, 1);
  // A second question asking the same thing joins it: demand counts up, once per question.
  const q2 = (await call("POST", "/api/inquiries", question(2, "일요일부터 시작하는 달력이면 좋겠어요"), from(2))).data;
  await call("POST", `/api/admin/dev/${item.id}/links`, { inquiry: q2.id }, owner);
  const again = (await call("POST", `/api/admin/dev/${item.id}/links`, { inquiry: q2.id }, owner)).data;
  assert.equal(again.links, 2);
  assert.equal(again.history.filter((x) => x.action === "link").length, 2);
  const list = (await call("GET", "/api/admin/dev", undefined, junho)).data;
  assert.deepEqual([list.counts.todo, list.counts.all, list.items[0].links, list.items[0].waiting], [1, 1, 2, 2]);
  assert.equal((await call("GET", "/api/admin/overview", undefined, junho)).data.devTodo, 1);
  // The app's view of the question has none of it.
  const app = (await call("GET", `/api/inquiries/${q.id}`, undefined, { "x-device": device(1) })).data;
  assert.equal(app.dev, undefined);
  assert.equal(app.notes, undefined);
});

test("every change is in the item's history with who made it and what it was; deleting can be undone by the owner only", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-20T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const { h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  const item = (await call("POST", "/api/admin/dev", { title: "위젯 글자 크기", body: "" }, junho)).data;
  t.mock.timers.tick(60 * 1000);
  const edited = await call("POST", `/api/admin/dev/${item.id}`, { title: "위젯 글자 크기 키우기", body: "큰 글자 설정을 따라가게", since: item.updatedAt }, owner);
  assert.equal(edited.status, 200, edited.text);
  const edit = edited.data.history.find((x) => x.action === "edit");
  assert.deepEqual([edit.actor, edit.detail.title, edit.detail.body], ["최고 관리자", ["위젯 글자 크기", "위젯 글자 크기 키우기"], ["", "큰 글자 설정을 따라가게"]]);
  // Someone working from what they saw before that change does not write over it.
  const stale = await call("POST", `/api/admin/dev/${item.id}`, { title: "다른 제목", since: item.updatedAt }, junho);
  assert.equal(stale.status, 409);
  assert.equal(stale.data.error, "changed");
  assert.equal(stale.data.item.title, "위젯 글자 크기 키우기");
  // 내가 맡기 and a note.
  let it = (await call("POST", `/api/admin/dev/${item.id}`, { assignee: "me" }, junho)).data;
  assert.equal(it.assignee.name, "준호");
  it = (await call("POST", `/api/admin/dev/${item.id}/notes`, { text: "다음 버전에 넣으면 좋겠어요" }, junho)).data;
  assert.deepEqual(it.history[0].detail, { text: "다음 버전에 넣으면 좋겠어요" });
  assert.equal((await call("GET", "/api/admin/overview", undefined, junho)).data.devMine, 1);

  const gone = (await call("POST", `/api/admin/dev/${item.id}/delete`, {}, junho)).data;
  assert.ok(gone.deletedAt);
  assert.equal((await call("GET", "/api/admin/dev", undefined, junho)).data.items.length, 0);
  assert.deepEqual((await call("GET", "/api/admin/dev?status=deleted", undefined, junho)).data.items.map((x) => x.id), [item.id]);
  assert.equal((await call("POST", `/api/admin/dev/${item.id}`, { title: "고칠 수 없어요" }, junho)).status, 409);
  assert.equal((await call("POST", `/api/admin/dev/${item.id}/restore`, {}, junho)).status, 403);
  const back = (await call("POST", `/api/admin/dev/${item.id}/restore`, {}, owner)).data;
  assert.equal(back.deletedAt, null);
  assert.deepEqual(back.history.map((x) => `${x.action}:${x.actor}`).reverse(),
    ["create:준호", "edit:최고 관리자", "assign:준호", "note:준호", "delete:준호", "restore:최고 관리자"]);
  // The activity screen has them too.
  const log = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries.map((e) => e.action);
  for (const a of ["dev_create", "dev_edit", "dev_delete", "dev_restore"]) assert.ok(log.includes(a), a);
});

test("개발중·완료·반려 need 처리: the owner has it and gives it per admin", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { id, h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  const item = (await call("POST", "/api/admin/dev", { title: "알람 소리 미리 듣기" }, junho)).data;
  const no = await call("POST", `/api/admin/dev/${item.id}`, { status: "doing" }, junho);
  assert.equal(no.status, 403);
  assert.equal(no.data.error, "no_permission");
  assert.equal((await call("POST", "/api/admin/dev/bulk", { ids: [item.id], status: "done" }, junho)).status, 403);
  // 반려 says why.
  assert.equal((await call("POST", `/api/admin/dev/${item.id}`, { status: "rejected" }, owner)).data.error, "reason");
  assert.equal((await call("POST", `/api/admin/dev/${item.id}`, { status: "later" }, owner)).data.error, "status");
  const r = (await call("POST", `/api/admin/dev/${item.id}`, { status: "rejected", reason: "기기마다 소리가 달라 넣기 어려워요" }, owner)).data;
  assert.deepEqual([r.status, r.reason, r.history[0].detail], ["rejected", "기기마다 소리가 달라 넣기 어려워요",
    { status: ["todo", "rejected"], reason: "기기마다 소리가 달라 넣기 어려워요" }]);

  // The owner gives 처리; "view" means nothing for a switch, so it is stored as none.
  const perms = (await call("GET", "/api/admin/me", undefined, junho)).data.perms;
  const viewed = (await call("POST", `/api/admin/admins/${id}`, { perms: { ...perms, devStatus: "view" } }, owner)).data;
  assert.equal(viewed.admins[0].perms.devStatus, "none");
  assert.deepEqual(viewed.toggles, ["devStatus", "takedown"]);
  await call("POST", `/api/admin/admins/${id}`, { perms: { ...perms, devStatus: "edit" } }, owner);
  assert.equal((await call("POST", `/api/admin/dev/${item.id}`, { status: "doing" }, junho)).data.status, "doing");
  // Without "dev" at all, nothing of it.
  const { h: seojin } = await account(call, owner, "seojin@example.com", "서진", { ...SUPPORT, inquiries: "none", dev: "none" }, 42);
  assert.equal((await call("GET", "/api/admin/dev", undefined, seojin)).status, 403);
  assert.equal((await call("GET", "/api/admin/overview", undefined, seojin)).data.devTodo, undefined);
  // A look only: reads, changes nothing.
  const { h: viewer } = await account(call, owner, "viewer@example.com", "보기", { ...SUPPORT, dev: "view" }, 43);
  assert.equal((await call("GET", `/api/admin/dev/${item.id}`, undefined, viewer)).status, 200);
  assert.equal((await call("POST", "/api/admin/dev", { title: "새 항목" }, viewer)).status, 403);
});

test("the owner sets one status on many items at once; each item's history says so", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const ids = [];
  for (const title of ["백업 자동으로", "위젯 다크 모드", "근무표 인쇄"]) ids.push((await call("POST", "/api/admin/dev", { title }, owner)).data.id);
  await call("POST", `/api/admin/dev/${ids[2]}/delete`, {}, owner);
  const res = await call("POST", "/api/admin/dev/bulk", { ids, status: "done" }, owner);
  assert.equal(res.status, 200, res.text);
  // A deleted item is left as it is.
  assert.deepEqual(res.data, { changed: 2, ids: ids.slice(0, 2) });
  const list = (await call("GET", "/api/admin/dev?status=done", undefined, owner)).data;
  assert.deepEqual([list.counts.done, list.counts.todo, list.counts.deleted], [2, 0, 1]);
  assert.deepEqual(list.items.map((x) => x.status), ["done", "done"]);
  assert.equal((await call("GET", "/api/admin/dev?status=open", undefined, owner)).data.items.length, 0);
  const one = (await call("GET", `/api/admin/dev/${ids[0]}`, undefined, owner)).data;
  assert.deepEqual(one.history[0].detail, { status: ["todo", "done"], bulk: 2 });
  // Setting what is already set changes nothing; 반려 in bulk needs its reason too.
  assert.equal((await call("POST", "/api/admin/dev/bulk", { ids, status: "done" }, owner)).data.changed, 0);
  assert.equal((await call("POST", "/api/admin/dev/bulk", { ids, status: "rejected" }, owner)).data.error, "reason");
  assert.equal((await call("POST", "/api/admin/dev/bulk", { ids: ["nope"], status: "done" }, owner)).data.error, "ids");
  assert.equal((await call("POST", "/api/admin/dev/bulk", { ids: [], status: "done" }, owner)).data.error, "ids");
});

test("a finished item answers its questions at once, and the phones see the answer", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const q1 = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  const q2 = (await call("POST", "/api/inquiries", question(2, "일요일 시작이 필요해요"), from(2))).data;
  const item = (await call("POST", "/api/admin/dev", { title: "주 시작 요일", inquiry: q1.id }, owner)).data;
  await call("POST", `/api/admin/dev/${item.id}/links`, { inquiry: q2.id }, owner);
  await call("POST", `/api/admin/dev/${item.id}`, { status: "done" }, owner);
  assert.equal((await call("POST", `/api/admin/dev/${item.id}/reply`, { text: " " }, owner)).data.error, "text");
  const res = await call("POST", `/api/admin/dev/${item.id}/reply`, { text: "반영했어요. 업데이트해 주세요", close: true }, owner);
  assert.equal(res.status, 200, res.text);
  assert.equal(res.data.answered, 2);
  for (const [q, n] of [[q1, 1], [q2, 2]]) {
    const seen = (await call("GET", `/api/inquiries/${q.id}`, undefined, { "x-device": device(n) })).data;
    assert.deepEqual([seen.status, seen.messages.at(-1).text], ["closed", "반영했어요. 업데이트해 주세요"]);
  }
  assert.deepEqual(res.data.item.history[0].detail, { count: 2, text: "반영했어요. 업데이트해 주세요", close: true });
  // Only some of them.
  const only = await call("POST", `/api/admin/dev/${item.id}/reply`, { text: "한 번 더 알려 드려요", ids: [q2.id] }, owner);
  assert.equal(only.data.answered, 1);

  // A phone deleting its question takes it off the item.
  await call("DELETE", `/api/inquiries/${q1.id}`, undefined, { "x-device": device(1) });
  assert.equal((await call("GET", `/api/admin/dev/${item.id}`, undefined, owner)).data.links, 1);
});

test("내가 맡기, notes between admins and shared answers", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  const q = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  await call("POST", "/api/inquiries", question(2, "알람이 안 울려요"), from(2));
  let o = (await call("GET", "/api/admin/overview", undefined, junho)).data;
  assert.deepEqual([o.inquiries, o.inquiriesFree, o.inquiriesMine], [2, 2, 0]);

  const took = (await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: "me" }, junho)).data;
  assert.equal(took.assignee.name, "준호");
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: "someone" }, junho)).status, 400);
  o = (await call("GET", "/api/admin/overview", undefined, junho)).data;
  assert.deepEqual([o.inquiriesFree, o.inquiriesMine], [1, 1]);
  assert.deepEqual((await call("GET", "/api/admin/inquiries?who=mine", undefined, junho)).data.inquiries.map((i) => i.id), [q.id]);
  assert.equal((await call("GET", "/api/admin/inquiries?who=free", undefined, junho)).data.inquiries.length, 1);
  // The owner's "mine" is their own; answering a question nobody took takes it.
  assert.equal((await call("GET", "/api/admin/inquiries?who=mine", undefined, owner)).data.inquiries.length, 0);
  const other = (await call("GET", "/api/admin/inquiries?who=free", undefined, owner)).data.inquiries[0];
  assert.equal((await call("POST", `/api/admin/inquiries/${other.id}/reply`, { text: "확인할게요" }, owner)).data.assignee.name, "최고 관리자");
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/reply`, { text: "확인할게요" }, owner)).data.assignee.name, "준호");

  const noted = (await call("POST", `/api/admin/inquiries/${q.id}/notes`, { text: "개발사항에 올렸어요" }, junho)).data;
  assert.deepEqual(noted.notes.map((n) => [n.by, n.text]), [["준호", "개발사항에 올렸어요"]]);
  assert.equal((await call("GET", "/api/admin/inquiries", undefined, owner)).data.inquiries.find((i) => i.id === q.id).notes, 1);
  // Notes never reach the phone.
  const seen = await call("GET", `/api/inquiries/${q.id}`, undefined, { "x-device": device(1) });
  assert.ok(!seen.text.includes("개발사항에 올렸어요"));

  let t = (await call("GET", "/api/admin/templates", undefined, junho)).data.templates;
  assert.ok(t.length >= 4);
  t = (await call("POST", "/api/admin/templates", { label: "업데이트 안내", text: "스토어에서 업데이트해 주세요." }, junho)).data.templates;
  const mine = t.find((x) => x.label === "업데이트 안내");
  assert.equal(mine.by, "준호");
  const first = t[0];
  t = (await call("POST", `/api/admin/templates/${first.id}/remove`, {}, junho)).data.templates;
  assert.ok(!t.some((x) => x.id === first.id));
  // A deleted starting answer stays deleted.
  assert.ok(!(await call("GET", "/api/admin/templates", undefined, owner)).data.templates.some((x) => x.id === first.id));
  assert.equal((await call("POST", "/api/admin/templates", { label: "", text: "x" }, junho)).data.error, "label");
});

test("letting a question go is for whoever holds it or the owner", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  const { h: mina } = await account(call, owner, "mina@example.com", "미나", SUPPORT, 42);
  const q = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: "me" }, junho);
  const refused = await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: null }, mina);
  assert.equal(refused.status, 403);
  assert.equal((await call("GET", `/api/admin/inquiries/${q.id}`, undefined, owner)).data.assignee.name, "준호");
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: null }, junho)).data.assignee, null);
  await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: "me" }, mina);
  assert.equal((await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: null }, owner)).data.assignee, null);
});

test("starting answers are added once even when two admins open the list at once", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const [a, b] = await Promise.all([call("GET", "/api/admin/templates", undefined, owner), call("GET", "/api/admin/templates", undefined, owner)]);
  const after = (await call("GET", "/api/admin/templates", undefined, owner)).data.templates;
  const labels = after.map((x) => x.label);
  assert.equal(new Set(labels).size, labels.length);
  assert.ok(a.data.templates.length <= after.length && b.data.templates.length <= after.length);
});

test("two changes from the same screen: only the first is written, the other is told it changed", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const item = (await call("POST", "/api/admin/dev", { title: "위젯" }, owner)).data;
  const [a, b] = await Promise.all([
    call("POST", `/api/admin/dev/${item.id}`, { title: "위젯 A", since: item.updatedAt }, owner),
    call("POST", `/api/admin/dev/${item.id}`, { title: "위젯 B", since: item.updatedAt }, owner),
  ]);
  assert.deepEqual([a.status, b.status].sort(), [200, 409]);
  const now = (await call("GET", `/api/admin/dev/${item.id}`, undefined, owner)).data;
  assert.equal(now.history.filter((x) => x.action === "edit").length, 1);
  assert.equal(now.title, (a.status === 200 ? a : b).data.title);
});

test("an admin who may not read questions does not see the words sent to them in an item's history", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { h: dev } = await account(call, owner, "dev@example.com", "개발", { ...SUPPORT, inquiries: "none", dev: "edit" }, 43);
  const q = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  const item = (await call("POST", "/api/admin/dev", { title: "주 시작 요일", inquiry: q.id }, owner)).data;
  await call("POST", `/api/admin/dev/${item.id}/reply`, { text: "비밀 답장 내용" }, owner);
  const seen = await call("GET", `/api/admin/dev/${item.id}`, undefined, dev);
  assert.equal(seen.status, 200, seen.text);
  assert.ok(!seen.text.includes("비밀 답장 내용"));
  assert.equal(seen.data.history[0].action, "reply");
  assert.ok((await call("GET", `/api/admin/dev/${item.id}`, undefined, owner)).text.includes("비밀 답장 내용"));
});

test("a question dropped after 180 days takes its notes and 개발사항 links with it; the item stays and counts only kept questions", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-04-01T09:00:00Z") });
  const { call, DB } = server();
  let owner = await signIn(call);
  const old = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  const item = (await call("POST", "/api/admin/dev", { title: "주 시작 요일", inquiry: old.id }, owner)).data;
  await call("POST", `/api/admin/inquiries/${old.id}/notes`, { text: "오래된 메모 내용" }, owner);
  t.mock.timers.tick(181 * 24 * 60 * 60 * 1000);
  // A new question sweeps out the old one on its way in.
  const fresh = (await call("POST", "/api/inquiries", question(2, "일요일 시작이면 좋겠어요"), from(2))).data;
  const n = async (table) => (await DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first()).n;
  assert.deepEqual([await n("inquiries"), await n("inquiry_notes"), await n("dev_links"), await n("dev_items")], [1, 0, 0, 1]);
  assert.ok(!DB.dump().includes("오래된 메모 내용"));
  owner = await signIn(call);
  // A link left from before never counts once its question is gone.
  await DB.prepare("INSERT INTO dev_links (item_id, inquiry_id, by, at) VALUES (?, ?, 'x', 0)").bind(item.id, "gone".padEnd(22, "x")).run();
  await call("POST", `/api/admin/dev/${item.id}/links`, { inquiry: fresh.id }, owner);
  assert.equal((await call("GET", "/api/admin/dev", undefined, owner)).data.items[0].links, 1);
  assert.equal((await call("GET", `/api/admin/dev/${item.id}`, undefined, owner)).data.links, 1);
  assert.deepEqual((await call("GET", `/api/admin/inquiries/${fresh.id}`, undefined, owner)).data.dev.map((d) => d.links), [1]);
});

test("removing an admin frees the questions and items they had taken on", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { id, h: junho } = await account(call, owner, "junho@example.com", "준호", SUPPORT, 41);
  const q = (await call("POST", "/api/inquiries", question(1), from(1))).data;
  await call("POST", `/api/admin/inquiries/${q.id}/assign`, { to: "me" }, junho);
  const item = (await call("POST", "/api/admin/dev", { title: "위젯" }, junho)).data;
  await call("POST", `/api/admin/dev/${item.id}`, { assignee: "me" }, junho);
  assert.equal((await call("POST", `/api/admin/admins/${id}/remove`, {}, owner)).status, 200);
  assert.equal((await call("GET", `/api/admin/inquiries/${q.id}`, undefined, owner)).data.assignee, null);
  const it = (await call("GET", `/api/admin/dev/${item.id}`, undefined, owner)).data;
  assert.equal(it.assignee, null);
  assert.deepEqual([it.history[0].action, it.history[0].detail], ["assign", { assignee: ["준호", ""] }]);
  assert.equal((await call("GET", "/api/admin/inquiries?who=free", undefined, owner)).data.inquiries.length, 1);
});

test("an admin invited before 개발사항 existed may add, change and delete them, but 처리 waits for the owner", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { h: rev } = await account(call, owner, "rev@example.com", "검토", { ...SUPPORT, inquiries: "none", review: "edit" }, 44);
  const me = (await call("GET", "/api/admin/me", undefined, rev)).data;
  assert.deepEqual([me.perms.dev, me.perms.devStatus], ["edit", "none"]);
  const item = await call("POST", "/api/admin/dev", { title: "위젯" }, rev);
  assert.equal(item.status, 201, item.text);
  assert.equal((await call("POST", `/api/admin/dev/${item.data.id}`, { title: "위젯 크기" }, rev)).status, 200);
  assert.equal((await call("POST", `/api/admin/dev/${item.data.id}`, { status: "doing" }, rev)).status, 403);
  assert.equal((await call("POST", `/api/admin/dev/${item.data.id}/delete`, {}, rev)).status, 200);
});
