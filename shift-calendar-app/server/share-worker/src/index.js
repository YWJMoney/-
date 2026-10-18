// [MKT-09] Read-only schedule links for 밤낮. Cloudflare Worker + KV, no accounts.
//
// POST   /api/share            body: schedule JSON  -> { id, url, deleteToken, expiresAt }
// PUT    /api/share/:id        same body, header X-Delete-Token -> { id, url, expiresAt }
// DELETE /api/share/:id        header X-Delete-Token -> 204
// GET    /api/share/:id        [SHR-02] the schedule for the app's 받은 달력 -> { schedule, expiresAt, updatedAt }
// GET    /s/:id                the schedule as a web page (noindex, no scripts)
// GET    /.well-known/assetlinks.json   lets Android open /s/ links in the app (ANDROID_CERT_SHA256)
//
// Only what the page shows is stored: dates, shift names, colours and times, and since v2 [SHR-01]
// only what the sharer ticked of 휴가 종류, 연장·휴무일 근무, 메모 and 급여. The server keeps no IP
// addresses (the rate limit key is hashed and expires within the hour). Each link deletes itself
// when it expires.

export const LIMITS = {
  bodyBytes: 64 * 1024,
  days: 200,
  types: 24,
  text: 20,
  title: 30,
  // 연차, 반차 … up to "0.75일 무급 휴가".
  leave: 12,
  ttlDays: [7, 30, 90],
  createsPerHour: 20,
  // [SHR-01] What a sharer may add to v2 links.
  memo: 80,
  extras: 4,
  extraName: 10,
  payMonths: 6,
  payLabel: 10,
  payAmount: 10_000_000_000,
};

/** [SHR-01] The parts a sharer can tick on top of the shifts. */
export const SHOWS = ["leaveKinds", "extras", "memos", "pay"];

/** [SHR-01] Leave as it shows without 휴가 종류 ticked: 연차 as it is, any other kind only by how much of the day is off. */
const PLAIN_LEAVE = ["연차", "반차", "반반차", "0.75일 연차", "휴가", "0.75일 휴가"];

export function plainLeave(label) {
  if (PLAIN_LEAVE.includes(label)) return label;
  if (label.includes("반반")) return "반반차";
  if (label.includes("반")) return "반차";
  if (label.startsWith("0.75")) return "0.75일 휴가";
  return "휴가";
}

const ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      return json({ error: "server" }, 500);
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (path === "/api/share" && request.method === "POST") return create(request, env, url);
  const api = path.match(/^\/api\/share\/([A-Za-z0-9]{16,32})$/);
  if (api && request.method === "PUT") return update(request, env, url, api[1]);
  if (api && request.method === "DELETE") return remove(request, env, api[1]);
  if (api && request.method === "GET") return read(env, api[1]);
  if (path === "/.well-known/assetlinks.json" && request.method === "GET") return assetLinks(env);
  const page = path.match(/^\/s\/([A-Za-z0-9]{16,32})$/);
  if (page && (request.method === "GET" || request.method === "HEAD")) return view(request, env, page[1]);
  if (path === "/" || path === "/robots.txt") {
    return new Response("User-agent: *\nDisallow: /\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
  }
  return new Response("Not found", { status: 404 });
}

async function create(request, env, url) {
  if (!(await allowCreate(request, env))) return json({ error: "rate_limited" }, 429);
  const parsed = await readSchedule(request);
  if (parsed.error) return json({ error: parsed.error }, 400);
  const id = randomId(22);
  const deleteToken = randomId(32);
  const record = {
    schedule: parsed.schedule,
    tokenHash: await sha256(deleteToken),
    expiresAt: expiry(parsed.ttlDays),
    updatedAt: new Date().toISOString(),
  };
  await put(env, id, record);
  return json({ id, url: `${url.origin}/s/${id}`, deleteToken, expiresAt: record.expiresAt }, 201);
}

async function update(request, env, url, id) {
  const record = await authorized(request, env, id);
  if (record instanceof Response) return record;
  const parsed = await readSchedule(request);
  if (parsed.error) return json({ error: parsed.error }, 400);
  const next = { ...record, schedule: parsed.schedule, expiresAt: expiry(parsed.ttlDays), updatedAt: new Date().toISOString() };
  await put(env, id, next);
  return json({ id, url: `${url.origin}/s/${id}`, expiresAt: next.expiresAt });
}

async function remove(request, env, id) {
  const record = await authorized(request, env, id);
  if (record instanceof Response) return record;
  await env.SHARES.delete(id);
  return new Response(null, { status: 204 });
}

async function authorized(request, env, id) {
  const token = request.headers.get("x-delete-token") || "";
  const record = await env.SHARES.get(id, "json");
  if (!record) return json({ error: "not_found" }, 404);
  if (!token || (await sha256(token)) !== record.tokenHash) return json({ error: "forbidden" }, 403);
  return record;
}

async function view(request, env, id) {
  const record = await env.SHARES.get(id, "json");
  if (!record || Date.parse(record.expiresAt) < Date.now()) {
    return html(page("링크가 없어요", `<p class="empty">이 근무표 링크는 만료되었거나 공유한 사람이 끊었어요.</p>`), 404);
  }
  // [SHR-02] On Android the page offers the app, which keeps it as a 받은 달력 next to the user's own.
  const android = /Android/i.test(request.headers.get("user-agent") || "");
  return html(renderSchedule(record.schedule, record.expiresAt, android ? id : null));
}

/** [SHR-02] What the app's 받은 달력 shows: the same schedule as the page, never the token hash. */
async function read(env, id) {
  const record = await env.SHARES.get(id, "json");
  if (!record || Date.parse(record.expiresAt) < Date.now()) return json({ error: "not_found" }, 404);
  return json({ schedule: record.schedule, expiresAt: record.expiresAt, updatedAt: record.updatedAt ?? null });
}

/**
 * [SHR-02] Digital Asset Links, so a tapped /s/ link opens 밤낮 when it is installed. The SHA-256
 * fingerprints of the app signing certificate (Play Console > 앱 무결성) go in ANDROID_CERT_SHA256,
 * separated by commas; without them Android keeps opening the page in the browser.
 */
function assetLinks(env) {
  const prints = String(env.ANDROID_CERT_SHA256 || "").split(",").map((s) => s.trim().toUpperCase())
    .filter((s) => /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(s));
  const body = prints.length === 0 ? [] : [{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: env.ANDROID_PACKAGE || "com.bamnat.app", sha256_cert_fingerprints: prints },
  }];
  return new Response(JSON.stringify(body), { headers: { "content-type": "application/json", "cache-control": "max-age=3600" } });
}

// ---- input ----

async function readSchedule(request) {
  const type = request.headers.get("content-type") || "";
  if (!type.startsWith("application/json")) return { error: "content_type" };
  const text = await request.text();
  if (new TextEncoder().encode(text).length > LIMITS.bodyBytes) return { error: "too_large" };
  let body;
  try { body = JSON.parse(text); } catch { return { error: "bad_json" }; }
  return validate(body);
}

/**
 * Keeps only the fields the page uses, in the shapes it expects; anything else is refused.
 * v1 is shifts and leave; v2 [SHR-01] may add what the sharer ticked: leave kinds, extra work,
 * memos and pay. A v2 field the sharer did not tick in `shows` is dropped, not stored, and
 * without 휴가 종류 a leave keeps only how much of the day it takes.
 */
export function validate(body) {
  if (!body || typeof body !== "object" || (body.v !== 1 && body.v !== 2)) return { error: "version" };
  const v2 = body.v === 2;
  let shows = [];
  if (v2) {
    if (!Array.isArray(body.shows) || body.shows.some((s) => !SHOWS.includes(s))) return { error: "shows" };
    shows = SHOWS.filter((s) => body.shows.includes(s));
  }
  const ttlDays = LIMITS.ttlDays.includes(body.ttlDays) ? body.ttlDays : 30;
  const title = cleanText(body.title ?? "", LIMITS.title);
  if (title === null) return { error: "title" };
  if (!Array.isArray(body.types) || body.types.length > LIMITS.types) return { error: "types" };
  const types = [];
  for (const t of body.types) {
    const name = cleanText(t?.name, LIMITS.text);
    const short = cleanText(t?.short, 4);
    if (!Number.isInteger(t?.id) || !name || !short || !isColor(t?.color)) return { error: "type" };
    if (t.time != null && !isTimeRange(t.time)) return { error: "type_time" };
    // [SHR-02] The shift's icon key (sun, moon …), so the app draws the badge as the sharer sees it.
    if (t.icon != null && !(typeof t.icon === "string" && /^[a-z0-9]{1,16}$/.test(t.icon))) return { error: "type_icon" };
    types.push({ id: t.id, name, short, color: t.color, time: t.time ?? null, off: t.off === true, icon: t.icon ?? null });
  }
  const ids = new Set(types.map((t) => t.id));
  if (!Array.isArray(body.days) || body.days.length === 0 || body.days.length > LIMITS.days) return { error: "days" };
  const days = [];
  const seen = new Set();
  for (const d of body.days) {
    // A day may have no shift of its own: a 대근 on a day off, or in v2 only a memo or extra work.
    if (!isDate(d?.date) || seen.has(d.date) || (d?.type != null && !ids.has(d.type))) return { error: "day" };
    seen.add(d.date);
    if (d.second != null && !ids.has(d.second)) return { error: "day_second" };
    let leave = d.leave == null ? null : cleanText(d.leave, LIMITS.leave);
    if (leave === null && d.leave != null) return { error: "day_leave" };
    if (leave && !shows.includes("leaveKinds")) leave = plainLeave(leave);
    const day = { date: d.date, type: d.type ?? null, second: d.second ?? null, leave };
    // [SHR-04] The sharer's app says whether the day has no work at all as far as the link shows, for 같이 쉬는 날.
    if (typeof d.rest === "boolean") day.rest = d.rest;
    if (shows.includes("memos") && d.memo != null) {
      const memo = cleanText(d.memo, LIMITS.memo);
      if (memo === null) return { error: "day_memo" };
      if (memo) day.memo = memo;
    }
    if (shows.includes("extras") && d.extras != null) {
      if (!Array.isArray(d.extras) || d.extras.length > LIMITS.extras) return { error: "day_extras" };
      const extras = [];
      for (const e of d.extras) {
        const name = cleanText(e?.name, LIMITS.extraName);
        if (!name) return { error: "day_extra" };
        if (e.time != null && !isTimeRange(e.time)) return { error: "day_extra" };
        if (e.minutes != null && !(Number.isInteger(e.minutes) && e.minutes > 0 && e.minutes <= 1440)) return { error: "day_extra" };
        extras.push({ name, time: e.time ?? null, minutes: e.minutes ?? null });
      }
      if (extras.length) day.extras = extras;
    }
    if (day.type == null && day.second == null && !day.leave && !day.memo && !day.extras) return { error: "day" };
    days.push(day);
  }
  days.sort((a, b) => a.date.localeCompare(b.date));
  const schedule = { v: v2 ? 2 : 1, title, types, days };
  if (v2) {
    schedule.shows = shows;
    if (shows.includes("pay") && body.pay != null) {
      if (!Array.isArray(body.pay) || body.pay.length > LIMITS.payMonths) return { error: "pay" };
      const pay = [];
      for (const p of body.pay) {
        const label = cleanText(p?.label ?? "", LIMITS.payLabel);
        if (!isMonth(p?.month) || !Number.isInteger(p?.amount) || p.amount < 0 || p.amount > LIMITS.payAmount || label === null) {
          return { error: "pay" };
        }
        pay.push({ month: p.month, amount: p.amount, label });
      }
      pay.sort((a, b) => a.month.localeCompare(b.month));
      schedule.pay = pay;
    }
  }
  return { schedule, ttlDays };
}

function cleanText(value, max) {
  if (typeof value !== "string") return null;
  const s = value.replace(/[\u0000-\u001f\u007f]/g, "").trim();
  return s.length > max ? null : s;
}
const isColor = (c) => typeof c === "string" && /^#[0-9A-Fa-f]{6}$/.test(c);
const isMonth = (m) => typeof m === "string" && /^\d{4}-(0[1-9]|1[0-2])$/.test(m);
const isDate = (d) => typeof d === "string" && /^\d{4}-\d{2}-\d{2}$/.test(d) && !Number.isNaN(Date.parse(d + "T00:00:00Z"));
const isTimeRange = (t) => typeof t === "string" && /^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(t);

// ---- storage ----

async function put(env, id, record) {
  const ttl = Math.max(60, Math.ceil((Date.parse(record.expiresAt) - Date.now()) / 1000));
  await env.SHARES.put(id, JSON.stringify(record), { expirationTtl: ttl });
}

function expiry(days) {
  return new Date(Date.now() + days * 86400 * 1000).toISOString();
}

/** A per-hour count of new links per caller. The key is a salted hash, so no address is stored. */
async function allowCreate(request, env) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const hour = Math.floor(Date.now() / 3600000);
  const key = `rl:${(await sha256(`${env.RATE_SALT || "bamnat"}:${ip}:${hour}`)).slice(0, 24)}`;
  const count = parseInt((await env.SHARES.get(key)) || "0", 10);
  if (count >= LIMITS.createsPerHour) return false;
  await env.SHARES.put(key, String(count + 1), { expirationTtl: 3600 });
  return true;
}

function randomId(length) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  let out = "";
  // 256 % 57 leaves a small bias; rejecting the top values keeps every character equally likely.
  for (const b of bytes) out += b < 228 ? ID_CHARS[b % 57] : "";
  return out.length >= length ? out.slice(0, length) : out + randomId(length - out.length);
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ---- output ----

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-robots-tag": "noindex, nofollow",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** [SHR-01] The words for each part a sharer can tick, in the order the page lists them. */
const SHOW_NAMES = { leaveKinds: "휴가 종류", extras: "연장·휴무일 근무", memos: "메모", pay: "급여" };

const won = (n) => `${String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",")}원`;

/** [SHR-02] Opens the app on Android, or its store page when it is not installed. */
function appLink(id) {
  const store = encodeURIComponent("https://play.google.com/store/apps/details?id=com.bamnat.app");
  return `intent://s/${id}#Intent;scheme=bamnat;package=com.bamnat.app;S.browser_fallback_url=${store};end`;
}

/** Month grids for every month the days touch, weeks starting on Sunday. */
export function renderSchedule(schedule, expiresAt, appId = null) {
  const types = new Map(schedule.types.map((t) => [t.id, t]));
  const byDate = new Map(schedule.days.map((d) => [d.date, d]));
  const months = [...new Set(schedule.days.map((d) => d.date.slice(0, 7)))];
  const today = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10); // KST
  const grids = months.map((ym) => {
    const [y, m] = ym.split("-").map(Number);
    const first = new Date(Date.UTC(y, m - 1, 1));
    const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
    const cells = [];
    for (let i = 0; i < first.getUTCDay(); i++) cells.push(`<div class="cell blank"></div>`);
    for (let d = 1; d <= days; d++) {
      const date = `${ym}-${String(d).padStart(2, "0")}`;
      const entry = byDate.get(date);
      const t = entry && types.get(entry.type);
      const s = entry?.second != null ? types.get(entry.second) : null;
      const dow = (first.getUTCDay() + d - 1) % 7;
      cells.push(
        `<div class="cell${date === today ? " today" : ""}"><span class="num${dow === 0 ? " sun" : dow === 6 ? " sat" : ""}">${d}</span>` +
          (t ? `<span class="chip" style="background:${t.color}">${esc(t.short)}</span>` : "") +
          (s ? `<span class="chip" style="background:${s.color}">${esc(s.short)}</span>` : "") +
          (entry?.leave ? `<span class="leave">${esc(entry.leave)}</span>` : "") +
          (entry?.extras ?? []).map((e) => `<span class="extra">${esc(e.name)}${e.minutes ? ` ${esc(hours(e.minutes))}` : ""}</span>`).join("") +
          (entry?.memo ? `<span class="memo">${esc(entry.memo)}</span>` : "") +
          `</div>`,
      );
    }
    return `<section><h2>${y}년 ${m}월</h2><div class="grid">${WEEKDAYS.map((w, i) => `<div class="wd${i === 0 ? " sun" : i === 6 ? " sat" : ""}">${w}</div>`).join("")}${cells.join("")}</div></section>`;
  });
  const used = new Set(schedule.days.flatMap((d) => [d.type, d.second]).filter((x) => x != null));
  const legend = schedule.types.filter((t) => used.has(t.id)).map((t) =>
    `<li><span class="chip" style="background:${t.color}">${esc(t.short)}</span>${esc(t.name)}${t.time && !t.off ? ` <span class="muted">${esc(t.time.replace("-", "~"))}</span>` : ""}</li>`,
  ).join("");
  const until = new Date(Date.parse(expiresAt) + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const title = schedule.title ? `${schedule.title}의 근무표` : "근무표";
  const shows = (schedule.shows ?? []).map((k) => SHOW_NAMES[k]).filter(Boolean);
  const has = shows.length ? `<p class="muted">근무와 함께 보낸 것: ${esc(shows.join(", "))}</p>` : "";
  const pay = schedule.pay?.length
    ? `<section class="pay"><h2>함께 보낸 급여</h2><ul>${schedule.pay.map((p) => {
        const [y, m] = p.month.split("-").map(Number);
        return `<li><span>${y}년 ${m}월${p.label ? ` <span class="muted">${esc(p.label)}</span>` : ""}</span><b>${won(p.amount)}</b></li>`;
      }).join("")}</ul><p class="muted">보낸 사람의 앱이 계산한 예상 금액이라 실제 명세서와 다를 수 있어요.</p></section>`
    : "";
  const app = appId
    ? `<p class="app"><a href="${esc(appLink(appId))}">밤낮 앱에서 보기</a><span class="muted">앱에서는 내 달력과 바꿔 가며 볼 수 있어요</span></p>`
    : "";
  return page(title, `<h1>${esc(title)}</h1>${has}${app}${grids.join("")}<ul class="legend">${legend}</ul>${pay}` +
    `<p class="muted foot">밤낮 앱에서 공유한 읽기 전용 근무표예요. ${until}까지 열려요.</p>`);
}

/** 90 → "1시간 30분", 120 → "2시간". */
function hours(minutes) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h && m ? `${h}시간 ${m}분` : h ? `${h}시간` : `${m}분`;
}

function page(title, body) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>${esc(title)}</title><style>
:root{--fg:#15192B;--muted:#5b5f73;--line:#e6e2d9;--bg:#F7F4EE;--card:#fff;--sun:#d14343;--sat:#3b5bdb;--today:#C2A36B}
@media (prefers-color-scheme:dark){:root{--fg:#F2ECE1;--muted:#a7a9b8;--line:#2a2f45;--bg:#15192B;--card:#1d2236;--sun:#ff8787;--sat:#91a7ff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,"Noto Sans KR","Apple SD Gothic Neo",sans-serif}
main{max-width:720px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px;margin:0 0 16px}h2{font-size:17px;margin:24px 0 8px}
.grid{display:grid;grid-template-columns:repeat(7,1fr);gap:4px}.wd{text-align:center;font-size:12px;color:var(--muted);padding:4px 0}
.cell{background:var(--card);border:1px solid var(--line);border-radius:10px;min-height:64px;padding:4px;display:flex;flex-direction:column;align-items:center;gap:3px}
.cell.blank{background:transparent;border:none}.cell.today{border:2px solid var(--today)}
.num{font-size:12px;color:var(--muted)}.sun{color:var(--sun)}.sat{color:var(--sat)}
.chip{display:inline-block;min-width:24px;padding:1px 6px;border-radius:8px;color:#fff;font-weight:700;font-size:13px;text-align:center;text-shadow:0 0 2px rgba(0,0,0,.35)}
.leave{font-size:11px;color:var(--muted)}.extra{font-size:10px;line-height:1.2;color:var(--fg);background:var(--line);border-radius:6px;padding:0 4px;text-align:center}
.memo{font-size:10px;line-height:1.25;color:var(--muted);text-align:center;word-break:break-all;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.app{display:flex;flex-wrap:wrap;align-items:center;gap:6px 12px;margin:0 0 8px}.app a{display:inline-block;padding:10px 16px;border-radius:999px;background:var(--today);color:#15192B;font-weight:700;text-decoration:none}
.pay ul{list-style:none;padding:0;margin:0;background:var(--card);border:1px solid var(--line);border-radius:12px}.pay li{display:flex;justify-content:space-between;gap:12px;padding:12px 14px;border-top:1px solid var(--line)}.pay li:first-child{border-top:none}
.legend{list-style:none;padding:0;margin:20px 0 0;display:flex;flex-wrap:wrap;gap:8px 16px}
.legend li{display:flex;align-items:center;gap:6px}.muted{color:var(--muted)}.foot{margin-top:24px;font-size:13px}.empty{margin-top:40px}
</style></head><body><main>${body}</main></body></html>`;
}
