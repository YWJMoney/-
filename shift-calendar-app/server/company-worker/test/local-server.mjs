// [COMM-04][ADMIN-01] The worker on a plain Node server with SQLite, for the app's round-trip test and
// to try the developer app at http://localhost:8788/admin/ before deploying:
//   ADMIN_PASSWORD=<10+ letters> npm run local -- 8788 [--seed] [--db local.sqlite]
// --seed fills it with made-up coworkers' reports, so every screen has something on it.
import http from "node:http";
import worker from "../src/index.js";
import { d1 } from "./d1.mjs";
import { report, setup } from "./helpers.mjs";

const args = process.argv.slice(2);
const port = Number(args.find((a) => /^\d+$/.test(a)) || 8788);
const file = args.includes("--db") ? args[args.indexOf("--db") + 1] : ":memory:";
const env = { DB: d1(file), RATE_SALT: "local", ADMIN_PASSWORD: process.env.ADMIN_PASSWORD };
if (!env.ADMIN_PASSWORD) console.log("ADMIN_PASSWORD is not set: the developer app shows what is missing.");

const send = (path, body, ip, method = "POST") => worker.fetch(new Request(`http://localhost:${port}${path}`, {
  method,
  headers: { "content-type": "application/json", "cf-connecting-ip": ip },
  body: JSON.stringify(body),
}), env).then((r) => r.json());

async function seed() {
  // Adopted by two coworkers, then one says it is out of date.
  const a = await send("/api/reports", report(1, { company: "(주)예시정밀", verified: true, note: "10월 명세서랑 맞춰 봤어요" }), "10.0.0.1");
  await send("/api/reports", report(2, { company: "예시정밀" }), "10.0.0.2");
  await send("/api/votes", { device: "seed-device-00000009", company: a.company.id, setup: a.setup, ok: false, why: "old" }, "10.0.0.9");
  // A new version of it: enough coworkers, but the rule in use is the developer's call.
  const newer = setup();
  newer.pay.rules.extra[3].percent = 0.12;
  newer.pay.rules.extra[3].adds[1].rate = 0.6;
  await send("/api/reports", report(3, { company: "예시정밀", setup: newer, verified: true, note: "11월부터 대근이 기본급 12%로 올랐어요" }), "10.0.0.3");
  await send("/api/reports", report(4, { company: "예시정밀", setup: newer }), "10.0.0.4");
  // Waiting for coworkers.
  const day = setup();
  day.shiftTypes = [
    { name: "주간", label: "주", color: 4280391411, start: "08:30", end: "17:30", break: 60, breakStart: "12:00", off: false },
    { name: "휴일", label: "휴", color: 4288585374, break: 0, off: true },
  ];
  day.rotation = { anchor: "2026-10-05", steps: ["주간", "주간", "주간", "주간", "주간", "휴일", "휴일"] };
  day.extraKindShiftSets = { 3: ["주간"] };
  day.pay.items = day.pay.items.slice(0, 2);
  day.pay.rules.publicHolidayAs = null;
  await send("/api/reports", report(5, { company: "가나다화학", dept: "품질팀", setup: day }), "10.0.0.5");
  await send("/api/reports", report(6, { company: "라마바전자", dept: "생산1팀", setup: day, verified: true }), "10.0.0.6");
  // Turned down once, sent again.
  const odd = setup();
  odd.pay.monthlyHours = 160;
  const r = await send("/api/reports", report(7, { company: "사아자물류", setup: odd }), "10.0.0.7");
  const login = await worker.fetch(new Request(`http://localhost:${port}/api/admin/login`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin": "1", "cf-connecting-ip": "10.0.0.100" },
    body: JSON.stringify({ password: env.ADMIN_PASSWORD }),
  }), env);
  if (login.ok) {
    const cookie = login.headers.get("set-cookie").split(";")[0];
    await worker.fetch(new Request(`http://localhost:${port}/api/admin/groups/${r.company.id}/${r.setup}/reject`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-admin": "1", cookie },
      body: JSON.stringify({ reason: "unclear" }),
    }), env);
  }
  await send("/api/reports", report(8, { company: "사아자물류", setup: odd, verified: true }), "10.0.0.8");
  console.log("seeded: 1 adopted rule, 1 new version, 2 waiting, 1 turned down");
}

if (args.includes("--seed")) await seed();

http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers.set(k, v);
  headers.set("cf-connecting-ip", req.socket.remoteAddress || "unknown");
  const body = ["GET", "HEAD"].includes(req.method) ? undefined : Buffer.concat(chunks);
  const out = await worker.fetch(new Request(`http://${req.headers.host}${req.url}`, { method: req.method, headers, body, redirect: "manual" }), env);
  const head = {};
  out.headers.forEach((v, k) => { head[k] = v; });
  res.writeHead(out.status, head);
  res.end(Buffer.from(await out.arrayBuffer()));
}).listen(port, "127.0.0.1", () => console.log(`company worker on http://localhost:${port} (developer app: /admin/)`));
