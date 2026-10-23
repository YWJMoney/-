// [ADMUX-01]~[ADMUX-08] The developer app itself, run in a pretend browser (happy-dom) against the worker on
// an in-memory D1: it signs in, opens every screen, and the things that live only in the page work —
// filters in the address, drafts that outlive a reload, the bulk bar, undo, the palette, signing in again.
// `npm run test:ui` (needs `npm ci` once for happy-dom).
import { test } from "node:test";
import assert from "node:assert/strict";
import { Window } from "happy-dom";
import worker from "../../src/index.js";
import { adminFile } from "../../src/admin-app.js";
import { server, signIn, report, from, ORIGIN, PASSWORD } from "../helpers.mjs";

const APP_JS = await adminFile("/admin/app.js").text();
const HTML = await adminFile("/admin/").text();
const BODY = HTML.slice(HTML.indexOf("<body>") + 6, HTML.indexOf("</body>"));

const open = [];
test.after(async () => {
  for (const w of open) await w.happyDOM.close();
});

/** A browser tab on the developer app: its fetch goes to the worker, with the sign-in cookie kept like a browser does. */
async function tab(env, { hash = "", storage = null, cookie = null } = {}) {
  const window = new Window({ url: `${ORIGIN}/admin/${hash}`, width: 1280, height: 900,
    settings: { disableJavaScriptFileLoading: true, disableCSSFileLoading: true, disableIframePageLoading: true } });
  open.push(window);
  const jar = { cookie };
  const errors = [];
  window.addEventListener("error", (e) => errors.push(e.error ?? e.message));
  window.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url, ORIGIN);
    const headers = new Headers(init.headers ?? {});
    if (jar.cookie) headers.set("cookie", jar.cookie);
    headers.set("cf-connecting-ip", "203.0.113.9");
    if (init.method && init.method !== "GET") headers.set("origin", ORIGIN);
    const res = await worker.fetch(new Request(url, { method: init.method ?? "GET", headers, body: init.body }), env);
    const set = res.headers.get("set-cookie");
    if (set) jar.cookie = /Max-Age=0/.test(set) ? null : set.split(";")[0];
    const body = res.status === 304 ? null : await res.text();
    return new window.Response(body, { status: res.status, headers: Object.fromEntries(res.headers) });
  };
  if (storage) for (const [k, v] of Object.entries(storage)) window.localStorage.setItem(k, v);
  window.structuredClone ??= structuredClone;
  window.document.body.innerHTML = BODY;
  window.eval(APP_JS);
  const t = {
    window, document: window.document, jar, errors,
    $: (s) => window.document.querySelector(s),
    $$: (s) => [...window.document.querySelectorAll(s)],
    text: (s) => window.document.querySelector(s)?.textContent.replace(/\s+/g, " ").trim() ?? "",
    /** Lets the app's requests and repaints finish. */
    settle: async (n = 12) => {
      // Not waitUntilComplete: the app's polling timer never completes.
      for (let i = 0; i < n; i++) await new Promise((r) => setTimeout(r, 5));
    },
    click: async (s) => {
      const el = typeof s === "string" ? window.document.querySelector(s) : s;
      assert.ok(el, `nothing to click at ${s}`);
      el.click();
      await t.settle();
    },
    go: async (h) => {
      window.location.hash = h;
      await t.settle();
    },
    type: async (s, value) => {
      const el = window.document.querySelector(s);
      el.value = value;
      el.dispatchEvent(new window.Event("input", { bubbles: true }));
      await t.settle();
    },
    key: async (key, extra = {}) => {
      window.document.activeElement?.blur?.();
      window.document.dispatchEvent(new window.KeyboardEvent("keydown", { key, bubbles: true, ...extra }));
      await t.settle();
    },
  };
  await t.settle();
  return t;
}

async function world() {
  const s = server();
  const { call, env } = s;
  await call("POST", "/api/reports", report(1, { company: "가나다화학" }), from(1));
  for (const [n, kind, text] of [[11, "bug", "알람 소리가 안 나요"], [12, "idea", "달력이 일요일부터면 좋겠어요"], [13, "question", "급여는 어디서 봐요"]]) {
    await call("POST", "/api/inquiries", { v: 1, device: `test-device-000000${n}`, kind, text, info: { app: "1.22.0 (33)" } }, from(n));
  }
  return { ...s, env };
}

async function signedIn(env) {
  const t = await tab(env);
  assert.ok(t.$('form[data-form="login"]'), "the sign-in screen first");
  await t.click('[data-act="login-mode"][data-key="owner"]');
  t.$("#password").value = PASSWORD;
  t.$('form[data-form="login"]').requestSubmit();
  await t.settle(30);
  assert.ok(t.$("#main"), "signed in");
  return t;
}

test("[ADMUX-01] every screen opens without an error and without a style attribute", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  for (const s of ["home", "review", "inquiries", "dev", "companies", "community", "rewards", "shares", "ops", "metrics", "admins", "audit", "settings", "account", "more"]) {
    await t.go(`#/${s}`);
    const main = t.$("#main");
    assert.ok(main.textContent.trim().length > 10, `${s} shows something`);
    assert.ok(!t.$("#main .failed"), `${s} loaded`);
    assert.equal(t.$$("#main [style]").length, 0, `${s}: no style attribute (CSP)`);
  }
  assert.deepEqual(t.errors, []);
});

test("[ADMUX-02] filters live in the address and come back from it", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/inquiries");
  await t.click('[data-act="inquiry-who"][data-key="free"]');
  assert.equal(t.window.location.hash, "#/inquiries?who=free");
  await t.type("#search", "알람");
  await new Promise((r) => setTimeout(r, 300));
  await t.settle();
  assert.equal(t.window.location.hash, `#/inquiries?who=free&q=${encodeURIComponent("알람")}`);
  assert.equal(t.$$("#main .rows .row").length, 1);
  // A link copied with its filters opens the same list.
  const u = await tab(env, { hash: "#/inquiries?status=open&sort=oldest", cookie: t.jar.cookie });
  assert.equal(u.$("#inq-sort option[selected]").value, "oldest");
  // 놓친 것 is only ever a waiting question.
  await u.click('[data-act="inquiry-status"][data-key="closed"]');
  await u.click('[data-act="inquiry-who"][data-key="late"]');
  assert.match(u.window.location.hash, /who=late/);
  assert.doesNotMatch(u.window.location.hash, /status=closed/);
});

test("[ADMUX-01] a typed answer survives a repaint, a reload and a lost sign-in", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/inquiries");
  await t.click("#main .rows .row");
  await t.type("#reply", "확인하고 있어요");
  await t.click('[data-act="refresh"]');
  assert.equal(t.$("#reply").value, "확인하고 있어요", "kept across a repaint");
  const store = Object.fromEntries(Object.entries(t.window.localStorage).filter(([k]) => k.startsWith("bamnat-admin-draft:")));
  const again = await tab(env, { hash: t.window.location.hash, storage: store, cookie: t.jar.cookie });
  assert.equal(again.$("#reply").value, "확인하고 있어요", "kept across a reload");
  // Signed out under way: a box over the screen, and the answer is still there after.
  again.jar.cookie = null;
  await again.click('[data-act="refresh"]');
  assert.ok(again.$('dialog form[data-form="relogin"]'));
  again.$("#re-pw").value = PASSWORD;
  again.$('form[data-form="relogin"]').requestSubmit();
  await again.settle(30);
  assert.equal(again.$("dialog").open, false);
  assert.equal(again.$("#reply").value, "확인하고 있어요");
  // Sent: the box and the kept draft are empty.
  again.$('form[data-form="reply"]').requestSubmit(again.$('form[data-form="reply"] .btn.primary'));
  await again.settle(30);
  assert.equal(again.$("#reply")?.value ?? "", "");
  assert.ok(!Object.keys(again.window.localStorage).some((k) => k.startsWith("bamnat-admin-draft:")));
});

test("[ADMIN-16] picked questions are taken at once, and a 닫기 can be taken back", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/inquiries");
  await t.click('[data-act="inquiry-pick"]');
  // Each pick repaints the list, so the next box is looked up again.
  for (let i = 0; i < 3; i++) {
    const box = t.$$("[data-pick-inquiry]")[i];
    box.checked = true;
    box.dispatchEvent(new t.window.Event("change", { bubbles: true }));
    await t.settle();
  }
  assert.match(t.text(".bulkbar b"), /3개/);
  await t.click('[data-act="inquiry-bulk"][data-key="assign"]');
  assert.match(t.text("#toast"), /3개를 처리했어요/);
  assert.equal(t.$$("#main .pill.info").filter((p) => p.textContent === "내가 맡음").length, 3);
  // Closed, then 되돌리기.
  await t.click("#main .rows .row");
  await t.click('[data-act="inquiry-state"][data-status="closed"]');
  assert.ok(t.$("#toast [data-undo]"));
  await t.click("#toast [data-undo]");
  await t.settle(20);
  const id = t.window.location.hash.split("/")[2] ?? "";
  const { call } = { call: (m, p) => worker.fetch(new Request(ORIGIN + p, { method: m, headers: { cookie: t.jar.cookie } }), env).then((r) => r.json()) };
  const list = await call("GET", "/api/admin/inquiries?status=open");
  assert.equal(list.inquiries.length, 3, `the closed one (${id}) is open again`);
});

test("[ADMUX-04] keys and the palette go places", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.key("g");
  await t.key("i");
  assert.match(t.window.location.hash, /^#\/inquiries/);
  await t.key("j");
  assert.equal(t.$$("#main .rows .row.selected").length, 1);
  await t.key("Enter");
  assert.match(t.window.location.hash, /^#\/inquiries\/[A-Za-z0-9]{22}$/);
  await t.key("k", { ctrlKey: true });
  assert.ok(t.$("dialog.palette[open]"));
  await t.type("#pal", "활동");
  assert.match(t.text("#pal-list"), /활동 기록/);
  t.$("#pal").dispatchEvent(new t.window.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await t.settle();
  assert.equal(t.window.location.hash, "#/audit");
});

test("[ADMUX-03] this device's choices: colours, text size, bottom tabs", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/settings");
  const pick = async (id, value) => {
    const el = t.$(id);
    el.value = value;
    el.dispatchEvent(new t.window.Event("change", { bubbles: true }));
    await t.settle();
  };
  await pick("#pref-theme", "dark");
  await pick("#pref-size", "large");
  assert.equal(t.document.documentElement.dataset.theme, "dark");
  assert.equal(t.document.documentElement.dataset.size, "large");
  const prefs = JSON.parse(t.window.localStorage.getItem("bamnat-admin-prefs"));
  assert.equal(prefs.theme, "dark");
  const flip = async (key, on) => {
    const box = t.$(`[data-change="pref-tab"][value="${key}"]`);
    box.checked = on;
    box.dispatchEvent(new t.window.Event("change", { bubbles: true }));
    await t.settle();
  };
  // Home and three: a fourth takes the place of the one there longest.
  await flip("audit", true);
  assert.match(t.text("#toast"), /3개까지라 검토 대신 활동 기록/);
  assert.ok(!t.$('.tabs [data-nav="review"]'));
  assert.ok(t.$('.tabs [data-nav="audit"]'), "활동 기록 is a bottom tab now");
});

test("[ADMIN-15] the join page asks for its invite with the token in the body", async () => {
  const s = await world();
  const owner = await signIn(s.call);
  const inv = await s.call("POST", "/api/admin/invites", { email: "new@example.com", perms: { review: "view" } }, owner);
  const token = inv.data.link.split("#")[1];
  const window = new Window({ url: `${ORIGIN}/admin/join#${token}` });
  open.push(window);
  const asked = [];
  window.fetch = async (input, init = {}) => {
    asked.push(`${init.method ?? "GET"} ${new URL(input, ORIGIN).pathname}${new URL(input, ORIGIN).search}`);
    const res = await worker.fetch(new Request(new URL(input, ORIGIN), { method: init.method ?? "GET", headers: { ...init.headers, origin: ORIGIN }, body: init.body }), s.env);
    return new window.Response(await res.text(), { status: res.status, headers: Object.fromEntries(res.headers) });
  };
  window.structuredClone ??= structuredClone;
  window.document.body.innerHTML = BODY;
  window.eval(APP_JS);
  for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 5));
  assert.ok(asked.includes("POST /api/admin/join/info"), asked.join(", "));
  assert.ok(!asked.some((a) => a.includes(token)), "the token is in no address");
  assert.match(window.document.querySelector("main").textContent, /new@example\.com/);
});

test("[ADMUX-07] a notice shows as the phone will show it, and lists go out as CSV", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/ops");
  await t.click('[data-act="notice-new"]');
  assert.match(t.text("#nt-preview"), /제목/);
  await t.type("#nt-title", "점검 안내");
  await t.type("#nt-body", "새벽 2시에 <잠시> 멈춰요");
  assert.equal(t.text("#nt-preview b"), "점검 안내");
  assert.equal(t.$("#nt-preview span").innerHTML, "새벽 2시에 &lt;잠시&gt; 멈춰요", "escaped");
  assert.match(t.text('form[data-form="notice"] .help'), /1\.22\.0\(번호 33\)/, "the newest app a question came from");
  t.$("dialog").close();
  await t.settle();
  await t.go("#/inquiries");
  const csv = t.$('[data-act="csv"][data-kind="inquiries"]');
  assert.ok(csv && !csv.disabled, "three questions to take out");
  let saved = null;
  t.window.URL.createObjectURL = (blob) => { saved = blob; return "blob:x"; };
  t.window.URL.revokeObjectURL = () => {};
  await t.click(csv);
  const text = await saved.text();
  assert.match(text, /^﻿받은 때,종류,주제,상태,맡은 사람/);
  assert.equal(text.trim().split("\r\n").length, 4);
  assert.ok(!/알람 소리가 안 나요/.test(text), "no question text in the file");
  await t.go("#/rewards");
  assert.ok(!t.$("#main").textContent.includes("드림"), "지급함, not 드림");
});

test("[ADMUX-04] on 검토, a asks before 채택 and x opens 거절", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/review");
  assert.ok(t.$('#main [data-act="adopt"]'), "a report waits");
  await t.key("a");
  assert.match(t.text("dialog"), /채택할까요/);
  t.$("dialog").close();
  await t.settle();
  await t.key("x");
  assert.ok(t.$('dialog form[data-form="reject"]'), "the 거절 form");
});

test("[ADMUX-04] the palette offers what was opened last, and home the saved views", async () => {
  const { env } = await world();
  const t = await signedIn(env);
  await t.go("#/inquiries");
  await t.click("#main .rows a.row");
  await t.go("#/settings");
  await t.key("k", { ctrlKey: true });
  const first = t.$("#pal-list li");
  assert.match(first.textContent, /급여는 어디서 봐요\s*최근/);
  t.$("dialog").close();
  await t.settle();
  await t.go("#/inquiries?status=answered");
  await t.key("k", { ctrlKey: true });
  await t.type("#pal", "지금 보기 저장");
  t.$("#pal").dispatchEvent(new t.window.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await t.settle();
  await t.go("#/home");
  const chip = t.$('nav[aria-label="저장한 보기"] a');
  assert.ok(chip, "the saved view is on home");
  assert.equal(chip.getAttribute("href"), "#/inquiries?status=answered");
});
