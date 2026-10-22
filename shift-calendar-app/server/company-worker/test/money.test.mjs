// [MON-02][MON-03][MON-06][MON-08][MON-09][MON-10] 정식 출시 switch, 프로 passes, admins' phones, purchases
// checked with Play and the consent asked before a free trial turns paid: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { server, report, device, from, signIn, ORIGIN } from "./helpers.mjs";
import worker, { addMonths, stack, readSub, stopUnconsentedTrials } from "../src/index.js";

const APP = { "x-admin": "1", origin: ORIGIN };
const phone = (n) => ({ "x-device": device(n) });
const DAY = 24 * 60 * 60 * 1000;

/** Two coworkers who agree: adopted, and each is owed the reward. */
async function adopted(call) {
  await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  const b = await call("POST", "/api/reports", report(2), from(2));
  assert.equal(b.data.status, "adopted");
}

async function owed(call, owner) {
  return (await call("GET", "/api/admin/rewards?status=owed", undefined, owner)).data.rewards;
}

/** An admin account the owner invited and approved, signed in. */
async function member(call, owner, email = "minji@example.com", ip = 40) {
  const perms = { review: "edit", inquiries: "none", dev: "edit", devStatus: "none", companies: "view", rewards: "none", settings: "edit", community: "none", audit: "none" };
  const inv = await call("POST", "/api/admin/invites", { email, perms }, owner);
  await call("POST", "/api/admin/join", { token: inv.data.link.split("#")[1], name: "민지", password: "minji-admin-2026" }, { ...APP, ...from(ip) });
  const id = (await call("GET", "/api/admin/admins", undefined, owner)).data.admins.find((a) => a.email === email).id;
  await call("POST", `/api/admin/admins/${id}`, { status: "active" }, owner);
  const res = await call("POST", "/api/admin/login", { email, password: "minji-admin-2026" }, { ...APP, ...from(ip) });
  return { id, headers: { cookie: res.headers.get("set-cookie").split(";")[0], ...APP } };
}

test("months are added by the calendar, on a shorter month's last day", () => {
  assert.equal(new Date(addMonths(Date.parse("2026-01-31T00:00:00Z"), 1)).toISOString(), "2026-02-28T00:00:00.000Z");
  assert.equal(new Date(addMonths(Date.parse("2026-11-20T09:00:00Z"), 3)).toISOString(), "2027-02-20T09:00:00.000Z");
});

test("passes stack from the launch, and wait before it", () => {
  const launch = Date.parse("2026-12-01T00:00:00Z");
  const rows = [
    { status: "paid", months: 3, paid_at: Date.parse("2026-11-01T00:00:00Z"), deferred: 0 },
    { status: "paid", months: 1, paid_at: Date.parse("2026-12-15T00:00:00Z"), deferred: 0 },
    { status: "owed", months: 6, paid_at: null, deferred: 0 },
    { status: "paid", months: 2, paid_at: Date.parse("2026-12-20T00:00:00Z"), deferred: 1 },
  ];
  assert.deepEqual({ ...stack(rows, null), items: undefined }, { until: null, waiting: 4, items: undefined });
  // 3 months from the launch, then 1 more after those: given on 12-15, it starts when the first ends.
  assert.equal(new Date(stack(rows, launch).until).toISOString(), "2027-04-01T00:00:00.000Z");
  // A pass given long after the last one ended starts when it is given.
  const late = [{ status: "paid", months: 1, paid_at: Date.parse("2027-06-10T00:00:00Z"), deferred: 0 }];
  assert.equal(new Date(stack(late, launch).until).toISOString(), "2027-07-10T00:00:00.000Z");
});

test("every app reads the switch; only the owner turns it", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-11-20T01:00:00Z") });
  const { call } = server();
  const before = (await call("GET", "/api/config")).data;
  assert.deepEqual(before, { v: 1, billingLive: false, launchedAt: null, thanksMonths: 1, freeFeatures: [], adQuietDays: 3, trialAskDays: 7 });

  const owner = await signIn(call);
  const { headers: m } = await member(call, owner);
  // An admin who may change 채택 설정 still cannot launch.
  assert.equal((await call("POST", "/api/admin/launch", { billingLive: true }, m)).status, 403);
  assert.equal((await call("POST", "/api/admin/launch", { freeFeatures: ["not ok"] }, owner)).data.error, "freeFeatures");
  assert.equal((await call("POST", "/api/admin/launch", { thanksMonths: 13 }, owner)).data.error, "thanksMonths");

  const on = await call("POST", "/api/admin/launch", { billingLive: true, freeFeatures: ["MONEY_STATS"] }, owner);
  assert.equal(on.status, 200);
  assert.equal(on.data.billingLive, true);
  assert.equal(on.data.launchedAt, "2026-11-20T01:00:00.000Z");
  assert.equal(on.data.play, false);
  assert.equal((await call("GET", "/api/admin/overview", undefined, owner)).data.billingLive, true);

  // Off and on again later: the launch day stays the first one.
  t.mock.timers.tick(3 * DAY);
  await call("POST", "/api/admin/launch", { billingLive: false }, owner);
  assert.equal((await call("GET", "/api/config")).data.billingLive, false);
  await call("POST", "/api/admin/launch", { billingLive: true }, owner);
  const cfg = (await call("GET", "/api/config")).data;
  assert.equal(cfg.launchedAt, "2026-11-20T01:00:00.000Z");
  assert.deepEqual(cfg.freeFeatures, ["MONEY_STATS"]);
  const log = (await call("GET", "/api/admin/audit", undefined, owner)).data.entries.map((e) => e.action);
  assert.deepEqual(log.filter((a) => a.startsWith("launch")), ["launch_on", "launch_off", "launch_settings", "launch_on"]);
});

test("a reward given becomes a pass on that phone, counted from the launch", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-11-01T00:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  await adopted(call);
  const [w] = (await owed(call, owner)).filter((x) => x);
  assert.equal((await call("POST", `/api/admin/rewards/${w.id}`, { status: "paid", months: 13 }, owner)).data.error, "months");
  const paid = await call("POST", `/api/admin/rewards/${w.id}`, { status: "paid", months: 6, memo: "큰 개선" }, owner);
  assert.deepEqual(paid.data, { ok: true, months: 6, deferred: false });
  const all = [...(await owed(call, owner)), ...(await call("GET", "/api/admin/rewards?status=paid", undefined, owner)).data.rewards];
  assert.equal(all.find((x) => x.id === w.id).months, 6);

  // Which phone got it: one of the two that sent the rule.
  const p1 = (await call("GET", "/api/passes", undefined, phone(1))).data;
  const p2 = (await call("GET", "/api/passes", undefined, phone(2))).data;
  const mine = p1.items.length ? p1 : p2;
  assert.equal(mine.until, null);
  assert.equal(mine.waitingMonths, 6);
  assert.equal(mine.admin, null);
  assert.equal((await call("GET", "/api/passes")).data.error, "device");

  t.mock.timers.tick(19 * DAY);
  // A sign-in lasts a week; the owner signs in again on launch day.
  assert.equal((await call("POST", "/api/admin/launch", { billingLive: true }, await signIn(call))).status, 200);
  const after = (await call("GET", "/api/passes", undefined, p1.items.length ? phone(1) : phone(2))).data;
  assert.equal(after.until, "2027-05-20T00:00:00.000Z");
  assert.equal(after.waitingMonths, 0);
});

test("a recovery code moves the passes to a new phone once", async () => {
  const { call } = server();
  const owner = await signIn(call);
  await adopted(call);
  for (const w of await owed(call, owner)) await call("POST", `/api/admin/rewards/${w.id}`, { status: "paid" }, owner);
  const made = await call("POST", "/api/passes/code", {}, phone(1));
  assert.equal(made.status, 201);
  assert.match(made.data.code, /^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  assert.equal((await call("GET", "/api/passes", undefined, phone(1))).data.code, true);

  // Typed in lower case with spaces on the new phone.
  const typed = made.data.code.toLowerCase().replace(/-/g, " ");
  const moved = await call("POST", "/api/passes/claim", { code: typed }, phone(9));
  assert.equal(moved.status, 200);
  assert.equal(moved.data.waitingMonths, 3);
  assert.equal((await call("GET", "/api/passes", undefined, phone(1))).data.waitingMonths, 0);
  // Spent: the same code does nothing for a third phone.
  assert.equal((await call("POST", "/api/passes/claim", { code: made.data.code }, phone(8))).status, 404);
  // A new code replaces the old one.
  const a = (await call("POST", "/api/passes/code", {}, phone(9))).data.code;
  const b = (await call("POST", "/api/passes/code", {}, phone(9))).data.code;
  assert.notEqual(a, b);
  assert.equal((await call("POST", "/api/passes/claim", { code: a }, phone(7))).status, 404);
});

test("an admin's phone: a ten-minute code, the owner's approval, two phones each", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-11-01T00:00:00Z") });
  const { call } = server();
  const owner = await signIn(call);
  const { id, headers: m } = await member(call, owner);

  // The owner's own phone needs nobody's approval.
  const oc = (await call("POST", "/api/admin/me/link-code", {}, owner)).data;
  assert.match(oc.code, /^\d{6}$/);
  assert.deepEqual((await call("POST", "/api/admin-link", { code: oc.code }, phone(1))).data, { status: "approved", name: "최고 관리자" });

  const code = (await call("POST", "/api/admin/me/link-code", {}, m)).data.code;
  const linked = await call("POST", "/api/admin-link", { code }, { ...phone(2), ...from(2) });
  assert.deepEqual(linked.data, { status: "pending", name: "민지" });
  // Once only.
  assert.equal((await call("POST", "/api/admin-link", { code }, { ...phone(3), ...from(3) })).status, 404);
  assert.equal((await call("GET", "/api/passes", undefined, phone(2))).data.admin.status, "pending");
  assert.equal((await call("GET", "/api/admin/overview", undefined, owner)).data.admins.phones, 1);
  // Only the owner sees and approves phones.
  assert.equal((await call("GET", "/api/admin/devices", undefined, m)).status, 403);
  const list = (await call("GET", "/api/admin/devices", undefined, owner)).data.devices;
  const d = list.find((x) => x.admin === id);
  await call("POST", `/api/admin/devices/${d.id}/approve`, {}, owner);
  assert.equal((await call("GET", "/api/passes", undefined, phone(2))).data.admin.status, "approved");

  // A code lasts ten minutes.
  const late = (await call("POST", "/api/admin/me/link-code", {}, m)).data.code;
  t.mock.timers.tick(10 * 60 * 1000 + 1);
  assert.equal((await call("POST", "/api/admin-link", { code: late }, { ...phone(3), ...from(3) })).data.error, "code");
  // Two phones each.
  await call("POST", "/api/admin-link", { code: (await call("POST", "/api/admin/me/link-code", {}, m)).data.code }, { ...phone(3), ...from(3) });
  const third = await call("POST", "/api/admin-link", { code: (await call("POST", "/api/admin/me/link-code", {}, m)).data.code }, { ...phone(4), ...from(4) });
  assert.equal(third.data.error, "too_many");

  // Removing the admin ends their phones.
  await call("POST", `/api/admin/admins/${id}/remove`, {}, owner);
  assert.equal((await call("GET", "/api/passes", undefined, phone(2))).data.admin, null);
  // And a phone can let go itself; 내 정보 지우기 lets go too.
  assert.equal((await call("DELETE", "/api/admin-link", undefined, phone(1))).status, 204);
  assert.equal((await call("GET", "/api/passes", undefined, phone(1))).data.admin, null);
});

test("forgetting an install takes its recovery code and admin link", async () => {
  const { call, DB } = server();
  const owner = await signIn(call);
  await call("POST", "/api/passes/code", {}, phone(5));
  const code = (await call("POST", "/api/admin/me/link-code", {}, owner)).data.code;
  await call("POST", "/api/admin-link", { code }, phone(5));
  await call("DELETE", "/api/mine", undefined, phone(5));
  const rows = (sql) => DB.db.prepare(sql).all().length;
  assert.equal(rows("SELECT * FROM pass_codes"), 0);
  assert.equal(rows("SELECT * FROM admin_devices"), 0);
});

// ---- Play, with a made-up service account and Play answering from the test ----

async function serviceAccount() {
  const key = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true, ["sign", "verify"]);
  const der = Buffer.from(await crypto.subtle.exportKey("pkcs8", key.privateKey)).toString("base64");
  const pem = `-----BEGIN PRIVATE KEY-----\n${der.match(/.{1,64}/g).join("\n")}\n-----END PRIVATE KEY-----\n`;
  return { account: JSON.stringify({ client_email: "play@example.iam.gserviceaccount.com", private_key: pem }), publicKey: key.publicKey };
}

/** Play as the test says, recording what the server asked. */
function fakePlay(t, publicKey, subs) {
  const calls = [];
  const real = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method ?? "GET", body: init.body });
    if (String(url) === "https://oauth2.googleapis.com/token") {
      const assertion = new URLSearchParams(init.body).get("assertion");
      const [h, c, s] = assertion.split(".");
      const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, Buffer.from(s, "base64url"), new TextEncoder().encode(`${h}.${c}`));
      const claims = JSON.parse(Buffer.from(c, "base64url").toString());
      assert.ok(ok, "signed with the account's key");
      assert.equal(claims.scope, "https://www.googleapis.com/auth/androidpublisher");
      return Response.json({ access_token: "play-token", expires_in: 3600 });
    }
    assert.equal(init.headers.authorization, "Bearer play-token");
    const m = String(url).match(/\/applications\/com\.bamnat\.app\/purchases\/subscriptionsv2\/tokens\/([^/:]+)(:(cancel|defer))?$/);
    if (!m) return new Response("", { status: 404 });
    const sub = subs[decodeURIComponent(m[1])];
    if (!sub) return new Response("", { status: 410 });
    if (m[3] === "cancel") {
      sub.lineItems[0].autoRenewingPlan.autoRenewEnabled = false;
      return new Response("{}");
    }
    if (m[3] === "defer") {
      const body = JSON.parse(init.body).deferralContext;
      assert.equal(body.etag, sub.etag);
      const next = new Date(Date.parse(sub.lineItems[0].expiryTime) + Number(body.deferDuration.replace("s", "")) * 1000).toISOString();
      sub.lineItems[0].expiryTime = next;
      return Response.json({ itemExpiryTimeDetails: [{ productId: "bamnat_pro", expiryTime: next }] });
    }
    return Response.json(sub);
  };
  t.after(() => { globalThis.fetch = real; });
  return calls;
}

const playSub = (expiry, { trial = false, state = "SUBSCRIPTION_STATE_ACTIVE", renew = true } = {}) => ({
  kind: "androidpublisher#subscriptionPurchaseV2", subscriptionState: state, etag: `e-${expiry}`,
  acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
  lineItems: [{ productId: "bamnat_pro", expiryTime: expiry, autoRenewingPlan: { autoRenewEnabled: renew },
    offerDetails: { basePlanId: "monthly", offerId: trial ? "trial-1m" : undefined }, offerPhase: trial ? { freeTrial: {} } : { basePrice: {} } }],
});

test("Play's answer is read the way the app needs it", () => {
  assert.deepEqual(readSub(playSub("2026-12-31T15:30:00Z", { trial: true }), "bamnat_pro"), {
    state: "ACTIVE", expiry: Date.parse("2026-12-31T15:30:00Z"), trial: true, autoRenew: true, basePlan: "monthly", acknowledged: true,
    etag: "e-2026-12-31T15:30:00Z",
  });
  assert.equal(readSub({ subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", lineItems: [] }, "bamnat_pro").state, "GRACE");
  assert.equal(readSub({ subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD" }, "bamnat_pro").state, "ON_HOLD");
});

test("without the service account a purchase is not checked and nothing is kept", async () => {
  const { call, DB } = server();
  const res = await call("POST", "/api/purchases", { product: "bamnat_pro", token: "purchase-token-123" }, phone(1));
  assert.deepEqual(res.data, { verified: false });
  assert.equal(DB.db.prepare("SELECT * FROM subs").all().length, 0);
  assert.equal(await stopUnconsentedTrials({ DB }, Date.now()), 0);
});

test("a trial nobody said 'keep' to is stopped a day before it turns paid", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-12-30T16:00:00Z") });
  const { account, publicKey } = await serviceAccount();
  const subs = { "token-trial-a": playSub("2026-12-31T15:30:00Z", { trial: true }), "token-trial-b": playSub("2026-12-31T15:30:00Z", { trial: true }) };
  const calls = fakePlay(t, publicKey, subs);
  const { call, env } = server({ PLAY_SERVICE_ACCOUNT: account });

  const a = await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-trial-a" }, phone(1));
  assert.deepEqual(a.data, { verified: true, state: "ACTIVE", expiry: "2026-12-31T15:30:00.000Z", trial: true, autoRenew: true,
    acknowledged: true, consentAt: null, stoppedAt: null });
  await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-trial-b" }, phone(2));
  // Phone 2 says keep, with the price and date it was shown.
  assert.equal((await call("POST", "/api/consents", { answer: "maybe", product: "bamnat_pro", price: "₩6,900", trialEnd: "2026-12-31T15:30:00Z" }, phone(2))).data.error, "answer");
  const kept = await call("POST", "/api/consents", { answer: "keep", product: "bamnat_pro", price: "₩6,900/월", trialEnd: "2026-12-31T15:30:00Z",
    shown: "체험이 2027-01-01에 끝나고 그날부터 월 ₩6,900이 결제돼요" }, phone(2));
  assert.equal(kept.status, 201);

  assert.equal(await stopUnconsentedTrials(env, Date.now()), 1);
  const cancels = calls.filter((c) => c.url.endsWith(":cancel"));
  assert.equal(cancels.length, 1);
  assert.match(cancels[0].url, /token-trial-a:cancel$/);
  assert.deepEqual(JSON.parse(cancels[0].body), { cancellationContext: { cancellationType: "USER_REQUESTED_STOP_RENEWALS" } });
  // Once: the next hour does not ask Play again.
  assert.equal(await stopUnconsentedTrials(env, Date.now()), 0);
  const again = await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-trial-a" }, phone(1));
  assert.equal(again.data.autoRenew, false);
  assert.ok(again.data.stoppedAt);
  const b = await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-trial-b" }, phone(2));
  assert.equal(b.data.consentAt, "2026-12-30T16:00:00.000Z");
  assert.equal((await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-gone" }, phone(3))).status, 404);
});

test("a reward for someone already paying moves their next payment instead", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-12-10T00:00:00Z") });
  const { account, publicKey } = await serviceAccount();
  const subs = { "token-paying-1": playSub("2027-01-05T00:00:00Z"), "token-paying-2": playSub("2027-01-05T00:00:00Z") };
  const calls = fakePlay(t, publicKey, subs);
  const { call } = server({ PLAY_SERVICE_ACCOUNT: account });
  const owner = await signIn(call);
  await adopted(call);
  await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-paying-1" }, phone(1));
  await call("POST", "/api/purchases", { product: "bamnat_pro", token: "token-paying-2" }, phone(2));
  const [w] = await owed(call, owner);
  const res = await call("POST", `/api/admin/rewards/${w.id}`, { status: "paid", months: 3 }, owner);
  assert.equal(res.data.deferred, true);
  const defer = calls.find((c) => c.url.endsWith(":defer"));
  // 2027-01-05 + 3 months = 2027-04-05: 90 days.
  assert.equal(JSON.parse(defer.body).deferralContext.deferDuration, `${90 * 24 * 3600}s`);
  const paid = (await call("GET", "/api/admin/rewards?status=paid", undefined, owner)).data.rewards[0];
  assert.equal(paid.deferred, true);
  assert.match(paid.memo, /^Play 결제 미룸\(다음 결제 2027-04-05\)/);
});

test("consent answers are kept five years, then the hourly clean-up deletes them", async (t) => {
  const { env, DB } = server();
  const now = Date.parse("2032-01-10T00:00:00Z");
  const year = 365 * 24 * 60 * 60 * 1000;
  // The table is made on the first request.
  await worker.fetch(new Request("https://x/api/config"), env, { waitUntil() {} });
  DB.db.prepare("INSERT INTO consents (device, kind, detail, at) VALUES (?, 'trial_paid', '{}', ?)").run("old", now - 5 * year - 1000);
  DB.db.prepare("INSERT INTO consents (device, kind, detail, at) VALUES (?, 'trial_paid', '{}', ?)").run("kept", now - 5 * year + 60000);
  t.mock.timers.enable({ apis: ["Date"], now });
  const waits = [];
  await worker.scheduled({ cron: "17 * * * *", scheduledTime: now }, env, { waitUntil: (p) => waits.push(p) });
  await Promise.all(waits);
  assert.deepEqual(DB.db.prepare("SELECT device FROM consents").all().map((r) => r.device), ["kept"]);
});
