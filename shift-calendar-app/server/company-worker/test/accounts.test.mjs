// [ADMIN-04] Admin accounts: the owner invites an email with permissions, the invited person makes an
// account from that link, the owner approves it, and the account sees and changes only what it was given.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { server, report, from, signIn, ORIGIN } from "./helpers.mjs";

const APP = { "x-admin": "1", origin: ORIGIN };
const REVIEWER = { review: "edit", inquiries: "none", companies: "view", rewards: "none", settings: "none", community: "edit", audit: "none" };
const PASSWORD = "minji-review-2026";

const token = (link) => link.split("#")[1];

/** The owner invites [email]; the person makes an account and the owner approves it. Returns their headers. */
async function member(call, owner, email = "minji@example.com", perms = REVIEWER, ip = 40) {
  const inv = await call("POST", "/api/admin/invites", { email, perms }, owner);
  assert.equal(inv.status, 201, inv.text);
  const joined = await call("POST", "/api/admin/join", { token: token(inv.data.link), name: "민지", password: PASSWORD }, { ...APP, ...from(ip) });
  assert.equal(joined.status, 201, joined.text);
  const list = (await call("GET", "/api/admin/admins", undefined, owner)).data;
  const id = list.admins.find((a) => a.email === email).id;
  assert.equal((await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner)).status, 200);
  return { id, headers: await login(call, email, PASSWORD, ip) };
}

async function login(call, email, password, ip = 40) {
  const res = await call("POST", "/api/admin/login", { email, password }, { ...APP, ...from(ip) });
  assert.equal(res.status, 200, res.text);
  return { cookie: res.headers.get("set-cookie").split(";")[0], ...APP };
}

test("only an invited email can make an account, and it waits for the owner", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  const inv = await call("POST", "/api/admin/invites", { email: " MinJi@Example.com ", perms: REVIEWER, note: "검토 도와줄 분" }, owner);
  assert.equal(inv.status, 201);
  assert.equal(inv.data.email, "minji@example.com");
  assert.match(inv.data.link, /^https:\/\/company\.example\/admin\/join#[A-Za-z0-9]{32}$/);
  // The link is shown once and kept only as a hash.
  assert.ok(!DB.dump().includes(token(inv.data.link)));

  const info = await call("GET", `/api/admin/join?t=${token(inv.data.link)}`);
  assert.equal(info.status, 200);
  assert.equal(info.data.email, "minji@example.com");
  assert.deepEqual(info.data.perms, REVIEWER);
  assert.equal((await call("GET", "/api/admin/join?t=" + "A".repeat(32))).status, 404);
  assert.equal((await call("POST", "/api/admin/join", { token: "B".repeat(32), name: "누구", password: "long-enough-pw" }, APP)).status, 404);

  // Weak passwords and empty names are refused before anything is made.
  assert.equal((await call("POST", "/api/admin/join", { token: token(inv.data.link), name: "민지", password: "short" }, APP)).data.error, "password");
  assert.equal((await call("POST", "/api/admin/join", { token: token(inv.data.link), name: "민지", password: "aaaaaaaaaaaa" }, APP)).data.error, "weak_password");
  assert.equal((await call("POST", "/api/admin/join", { token: token(inv.data.link), name: " ", password: PASSWORD }, APP)).data.error, "name");

  const joined = await call("POST", "/api/admin/join", { token: token(inv.data.link), name: "민지", password: PASSWORD }, APP);
  assert.deepEqual(joined.data, { ok: true, status: "pending" });
  assert.ok(!DB.dump().includes(PASSWORD), "only a hash of the password");
  // The link works once.
  assert.equal((await call("POST", "/api/admin/join", { token: token(inv.data.link), name: "다른 분", password: PASSWORD }, APP)).status, 404);

  // The right password does not get in before the owner approves.
  assert.deepEqual((await call("POST", "/api/admin/login", { email: "minji@example.com", password: PASSWORD }, APP)).data, { error: "pending" });
  const list = (await call("GET", "/api/admin/admins", undefined, owner)).data;
  const [a] = list.admins;
  assert.equal(a.status, "pending");
  assert.equal(list.invites[0].state, "joined");
  assert.equal((await call("GET", "/api/admin/overview", undefined, owner)).data.admins.waiting, 1);

  await call("POST", `/api/admin/admins/${a.id}`, { status: "active" }, owner);
  const h = await login(call, "MINJI@example.com", PASSWORD);
  const me = (await call("GET", "/api/admin/me", undefined, h)).data;
  assert.equal(me.name, "민지");
  assert.equal(me.owner, false);
  assert.deepEqual(me.perms, REVIEWER);
});

test("an account sees and changes only what it was given", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { headers: h } = await member(call, owner);
  const a = await call("POST", "/api/reports", report(1), from(1));

  // Review: changes allowed.
  assert.equal((await call("GET", "/api/admin/queue", undefined, h)).status, 200);
  assert.equal((await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/reject`, { reason: "unclear" }, h)).status, 200);
  // Companies: a look only.
  assert.equal((await call("GET", "/api/admin/companies", undefined, h)).status, 200);
  const no = await call("POST", "/api/admin/companies", { name: "가나다화학" }, h);
  assert.equal(no.status, 403);
  assert.deepEqual(no.data, { error: "no_permission", area: "companies" });
  // Nothing at all for rewards, settings, activity and admins.
  for (const path of ["/rewards", "/audit", "/admins"]) assert.equal((await call("GET", `/api/admin${path}`, undefined, h)).status, 403, path);
  assert.equal((await call("POST", "/api/admin/settings", { adoptPoints: 2, rewardMonths: 0 }, h)).status, 403);
  assert.equal((await call("POST", "/api/admin/invites", { email: "x@example.com", perms: REVIEWER }, h)).status, 403);
  // A route nobody mapped yet stays the owner's.
  assert.equal((await call("GET", "/api/admin/notices", undefined, h)).status, 403);

  // The home numbers leave out what the account cannot open.
  const o = (await call("GET", "/api/admin/overview", undefined, h)).data;
  assert.equal(typeof o.pending, "number");
  assert.equal(o.owed, undefined);
  assert.equal(o.admins, undefined);
  assert.equal(o.me.email, "minji@example.com");
});

test("changes to an account apply at once; pausing or deleting signs it out", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { id, headers: h } = await member(call, owner);
  assert.equal((await call("GET", "/api/admin/rewards", undefined, h)).status, 403);
  await call("POST", `/api/admin/admins/${id}`, { perms: { ...REVIEWER, rewards: "view" } }, owner);
  assert.equal((await call("GET", "/api/admin/rewards", undefined, h)).status, 200);
  // Activity is only ever looked at.
  const edited = (await call("POST", `/api/admin/admins/${id}`, { perms: { ...REVIEWER, audit: "edit" } }, owner)).data;
  assert.equal(edited.admins[0].perms.audit, "view");
  assert.equal((await call("POST", `/api/admin/admins/${id}`, { perms: { review: "all" } }, owner)).status, 400);

  await call("POST", `/api/admin/admins/${id}`, { status: "suspended" }, owner);
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).status, 401);
  assert.deepEqual((await call("POST", "/api/admin/login", { email: "minji@example.com", password: PASSWORD }, { ...APP, ...from(40) })).data,
    { error: "suspended" });
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
  const again = await login(call, "minji@example.com", PASSWORD);
  assert.equal((await call("GET", "/api/admin/overview", undefined, again)).status, 200);

  await call("POST", `/api/admin/admins/${id}/remove`, {}, owner);
  assert.equal((await call("GET", "/api/admin/overview", undefined, again)).status, 401);
  const list = (await call("GET", "/api/admin/admins", undefined, owner)).data;
  assert.equal(list.admins.length, 0);
  assert.equal(list.invites.length, 0, "the email can be invited again");
});

test("activity names who did it", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { headers: h } = await member(call, owner);
  const a = await call("POST", "/api/reports", report(1), from(1));
  await call("POST", `/api/admin/groups/${a.data.company.id}/${a.data.setup}/reject`, { reason: "wrong" }, h);
  const log = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries;
  const reject = log.find((e) => e.action === "reject");
  assert.equal(reject.actor, "민지 <minji@example.com>");
  assert.equal(log.find((e) => e.action === "invite").actor, "최고 관리자");
  assert.equal(log.find((e) => e.action === "join").actor, "민지 <minji@example.com>");
});

test("five wrong passwords lock the account from any network", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-18T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  await member(call, owner);
  for (let i = 0; i < 5; i++) {
    const res = await call("POST", "/api/admin/login", { email: "minji@example.com", password: "not-the-password" }, { ...APP, ...from(50 + i) });
    assert.equal(res.status, 401);
  }
  assert.equal((await call("POST", "/api/admin/login", { email: "minji@example.com", password: PASSWORD }, { ...APP, ...from(60) })).status, 429);
  // An unknown email answers like a wrong password.
  assert.deepEqual((await call("POST", "/api/admin/login", { email: "nobody@example.com", password: PASSWORD }, { ...APP, ...from(61) })).data,
    { error: "wrong", left: 4 });
  t.mock.timers.tick(61 * 60 * 1000);
  assert.equal((await call("POST", "/api/admin/login", { email: "minji@example.com", password: PASSWORD }, { ...APP, ...from(60) })).status, 200);
});

test("an account changes its password and can sign out everywhere", async () => {
  const { call } = server();
  const owner = await signIn(call);
  const { headers: h } = await member(call, owner);
  const other = await login(call, "minji@example.com", PASSWORD, 41);
  assert.equal((await call("POST", "/api/admin/me/password", { current: "wrong-one-xx", next: "new-password-123" }, h)).data.error, "wrong");
  assert.equal((await call("POST", "/api/admin/me/password", { current: PASSWORD, next: "new-password-123" }, h)).status, 200);
  // This device stays signed in; the other one is signed out.
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).status, 200);
  assert.equal((await call("GET", "/api/admin/overview", undefined, other)).status, 401);
  const fresh = await login(call, "minji@example.com", "new-password-123", 42);
  await call("POST", "/api/admin/me/logout-all", {}, fresh);
  assert.equal((await call("GET", "/api/admin/overview", undefined, h)).status, 401);
  // The owner's password lives in Cloudflare.
  assert.equal((await call("POST", "/api/admin/me/password", { current: "x", next: "y" }, owner)).data.error, "owner_password");
});

test("invites expire, can be renewed and taken back", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-18T09:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const first = await call("POST", "/api/admin/invites", { email: "junho@example.com", perms: REVIEWER }, owner);
  t.mock.timers.tick(7 * 24 * 60 * 60 * 1000 + 1000);
  assert.equal((await call("GET", `/api/admin/join?t=${token(first.data.link)}`)).status, 404);
  assert.equal((await call("GET", "/api/admin/admins", undefined, await signIn(call))).data.invites[0].state, "expired");
  const owner2 = await signIn(call);
  const second = await call("POST", "/api/admin/invites", { email: "junho@example.com", perms: REVIEWER }, owner2);
  assert.equal((await call("GET", `/api/admin/join?t=${token(second.data.link)}`)).status, 200);
  assert.equal((await call("POST", "/api/admin/invites/remove", { email: "junho@example.com" }, owner2)).status, 200);
  assert.equal((await call("GET", `/api/admin/join?t=${token(second.data.link)}`)).status, 404);
  assert.equal((await call("POST", "/api/admin/invites", { email: "not an email", perms: REVIEWER }, owner2)).data.error, "email");
});

test("with mail set up, the account needs the code sent to the invited email", async (t) => {
  const sent = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    sent.push({ url, body: JSON.parse(init.body), auth: init.headers.authorization });
    return new Response("{}", { status: 200 });
  });
  const { call, DB } = server({ MAIL_API_KEY: "re_test", MAIL_FROM: "밤낮 <no-reply@bamnat.example>", OWNER_EMAIL: "owner@example.com" });
  assert.equal((await call("GET", "/api/admin/health")).data.mail, true);
  const owner = await signIn(call);
  const inv = await call("POST", "/api/admin/invites", { email: "minji@example.com", perms: REVIEWER }, owner);
  const t1 = token(inv.data.link);
  assert.equal((await call("GET", `/api/admin/join?t=${t1}`)).data.mail, true);
  assert.equal((await call("POST", "/api/admin/join", { token: t1, name: "민지", password: PASSWORD }, APP)).data.error, "code_expired");
  assert.equal((await call("POST", "/api/admin/join/code", { token: t1 }, APP)).status, 200);
  assert.equal(sent[0].url, "https://api.resend.com/emails");
  assert.deepEqual(sent[0].body.to, ["minji@example.com"]);
  const code = sent[0].body.text.match(/\b(\d{6})\b/)[1];
  assert.ok(!DB.dump().includes(code), "only a hash of the code");
  const wrong = String((Number(code) + 1) % 1000000).padStart(6, "0");
  assert.deepEqual((await call("POST", "/api/admin/join", { token: t1, name: "민지", password: PASSWORD, code: wrong }, APP)).data, { error: "code", left: 4 });
  assert.equal((await call("POST", "/api/admin/join", { token: t1, name: "민지", password: PASSWORD, code }, APP)).status, 201);
  // The owner hears that someone is waiting.
  assert.deepEqual(sent[1].body.to, ["owner@example.com"]);
});

test("a server deployed before accounts keeps its activity and adds who did it", async () => {
  const { call, DB } = server();
  DB.db.exec("CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')");
  DB.db.exec(`INSERT INTO audit (at, action, target, detail) VALUES (${Date.now() - 1000}, 'adopt', 'x', '')`);
  DB.db.exec("CREATE TABLE sessions (token TEXT PRIMARY KEY, expires INTEGER NOT NULL)");
  const owner = await signIn(call);
  const log = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries;
  assert.equal(log.at(-1).action, "adopt");
  assert.equal(log.at(-1).actor, "");
  assert.equal(log[0].actor, "최고 관리자");
});

test("the developer app's pages: the app, the invite link and installing", async () => {
  const { call } = server();
  for (const path of ["/admin/", "/admin/join", "/admin/install"]) {
    const res = await call("GET", path);
    assert.equal(res.status, 200, path);
    assert.match(res.headers.get("content-security-policy"), /script-src 'self'/);
    assert.match(res.text, /<script src="\/admin\/app.js" defer><\/script>/);
  }
  const js = await call("GET", "/admin/app.js");
  assert.doesNotThrow(() => new Function(js.text));
  const manifest = JSON.parse((await call("GET", "/admin/manifest.webmanifest")).text);
  assert.equal(manifest.start_url, "/admin/");
  assert.equal(manifest.display, "standalone");
});

test("the bundled worker.js for the dashboard is one file that works the same", async () => {
  const { bundle } = await import("../tools/bundle.mjs");
  const { mkdtempSync, writeFileSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const code = bundle();
  assert.ok(!/^\s*import\s/m.test(code), "nothing left to import");
  const file = join(mkdtempSync(join(tmpdir(), "bamnat-")), "worker.mjs");
  writeFileSync(file, code);
  const worker = (await import(file)).default;
  const res = await worker.fetch(new Request(`${ORIGIN}/admin/join`), {});
  assert.equal(res.status, 200);
  assert.match(await res.text(), /밤낮 관리자/);
});

test("the hourly clean-up deletes spent sign-ins, unused invites and codes, and keeps used invites", async (t) => {
  const { call, env, DB } = server();
  const owner = await signIn(call);
  await member(call, owner);
  await call("POST", "/api/admin/invites", { email: "junho@example.com", perms: REVIEWER }, owner);
  DB.db.prepare("INSERT INTO codes (email, code, expires, tries) VALUES (?, ?, ?, 0)").run("junho@example.com", "x", Date.now() + 60000);
  const later = Date.now() + 120 * 24 * 60 * 60 * 1000;
  t.mock.timers.enable({ apis: ["Date"], now: later });
  const waits = [];
  await worker.scheduled({ cron: "17 * * * *", scheduledTime: later }, env, { waitUntil: (p) => waits.push(p) });
  await Promise.all(waits);
  const n = (sql) => DB.db.prepare(sql).get().n;
  assert.equal(n("SELECT COUNT(*) AS n FROM admin_sessions"), 0);
  assert.equal(n("SELECT COUNT(*) AS n FROM codes"), 0);
  assert.deepEqual(DB.db.prepare("SELECT email FROM invites").all().map((r) => r.email), ["minji@example.com"]);
  assert.equal(n("SELECT COUNT(*) AS n FROM admins"), 1);
});
