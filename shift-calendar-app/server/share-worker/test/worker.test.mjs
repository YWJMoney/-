// [MKT-09] Runs the Worker's fetch handler in Node with an in-memory KV: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { validate, renderSchedule } from "../src/index.js";

function memoryKv() {
  const map = new Map();
  return {
    map,
    async get(key, type) {
      const v = map.get(key);
      if (v === undefined) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(key, value, opts) { map.set(key, value); this.lastTtl = opts?.expirationTtl; },
    async delete(key) { map.delete(key); },
  };
}

const sample = {
  v: 1,
  title: "민지",
  ttlDays: 30,
  types: [
    { id: 1, name: "주간", short: "주", color: "#1E88E5", time: "07:00-19:00" },
    { id: 2, name: "야간", short: "야", color: "#5E35B1", time: "19:00-07:00" },
    { id: 3, name: "휴무", short: "휴", color: "#9E9E9E", off: true },
  ],
  days: [
    { date: "2026-11-01", type: 1 },
    { date: "2026-11-02", type: 1, second: 2 },
    { date: "2026-11-03", type: 3, leave: "연차" },
  ],
};

const call = (env, method, path, body, headers = {}) =>
  worker.fetch(new Request(`https://share.example${path}`, {
    method,
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.5", ...headers },
    body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  }), env);

test("create, view, update and delete a link", async () => {
  const env = { SHARES: memoryKv() };
  const res = await call(env, "POST", "/api/share", sample);
  assert.equal(res.status, 201);
  const made = await res.json();
  assert.match(made.id, /^[A-Za-z0-9]{22}$/);
  assert.equal(made.url, `https://share.example/s/${made.id}`);
  assert.ok(env.SHARES.lastTtl > 29 * 86400 && env.SHARES.lastTtl <= 30 * 86400);

  const page = await call(env, "GET", `/s/${made.id}`);
  assert.equal(page.status, 200);
  assert.equal(page.headers.get("x-robots-tag"), "noindex, nofollow");
  assert.match(page.headers.get("content-security-policy"), /default-src 'none'/);
  const html = await page.text();
  assert.match(html, /민지의 근무표/);
  assert.match(html, /2026년 11월/);
  assert.match(html, /07:00~19:00/);
  assert.match(html, /연차/);

  // The server never stores the delete token itself.
  const stored = env.SHARES.map.get(made.id);
  assert.ok(!stored.includes(made.deleteToken));

  assert.equal((await call(env, "PUT", `/api/share/${made.id}`, sample, { "x-delete-token": "wrong" })).status, 403);
  const updated = await call(env, "PUT", `/api/share/${made.id}`, { ...sample, title: "민지2" }, { "x-delete-token": made.deleteToken });
  assert.equal(updated.status, 200);
  assert.match(await (await call(env, "GET", `/s/${made.id}`)).text(), /민지2의 근무표/);

  assert.equal((await call(env, "DELETE", `/api/share/${made.id}`, undefined, { "x-delete-token": made.deleteToken })).status, 204);
  assert.equal((await call(env, "GET", `/s/${made.id}`)).status, 404);
});

test("text is escaped on the page", async () => {
  const html = renderSchedule({ ...validate({ ...sample, title: "<b>x</b>" }).schedule }, new Date(Date.now() + 86400000).toISOString());
  assert.ok(!html.includes("<b>x</b>"));
  assert.match(html, /&lt;b&gt;x&lt;\/b&gt;/);
});

test("anything outside the schedule shape is refused", () => {
  assert.equal(validate({ ...sample, v: 2 }).error, "version");
  assert.equal(validate({ ...sample, types: [{ ...sample.types[0], color: "red" }] }).error, "type");
  assert.equal(validate({ ...sample, days: [{ date: "2026-11-01", type: 99 }] }).error, "day");
  assert.equal(validate({ ...sample, days: [{ date: "nope", type: 1 }] }).error, "day");
  assert.equal(validate({ ...sample, title: "x".repeat(31) }).error, "title");
  // Unknown fields such as a memo are dropped, not stored.
  const kept = validate({ ...sample, memo: "비밀", days: [{ date: "2026-11-01", type: 1, memo: "비밀" }] });
  assert.ok(!JSON.stringify(kept.schedule).includes("비밀"));
  assert.equal(validate({ ...sample, ttlDays: 365 }).ttlDays, 30);
});

test("bodies over the size limit and non-JSON are refused", async () => {
  const env = { SHARES: memoryKv() };
  assert.equal((await call(env, "POST", "/api/share", "x".repeat(50 * 1024))).status, 400);
  assert.equal((await call(env, "POST", "/api/share", sample, { "content-type": "text/plain" })).status, 400);
});

test("one caller can make at most 20 links an hour", async () => {
  const env = { SHARES: memoryKv() };
  for (let i = 0; i < 20; i++) assert.equal((await call(env, "POST", "/api/share", sample)).status, 201);
  assert.equal((await call(env, "POST", "/api/share", sample)).status, 429);
  // No raw address is kept.
  assert.ok(![...env.SHARES.map.keys()].some((k) => k.includes("203.0.113.5")));
});

test("unknown paths and the root are not indexed", async () => {
  const env = { SHARES: memoryKv() };
  assert.equal((await call(env, "GET", "/s/short")).status, 404);
  assert.match(await (await call(env, "GET", "/robots.txt")).text(), /Disallow: \//);
});
