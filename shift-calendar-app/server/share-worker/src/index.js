// [MKT-09] Read-only schedule links for 밤낮. Cloudflare Worker + KV, no accounts.
//
// POST   /api/share            body: schedule JSON  -> { id, url, deleteToken, expiresAt }
// PUT    /api/share/:id        same body, header X-Delete-Token -> { id, url, expiresAt }
// DELETE /api/share/:id        header X-Delete-Token -> 204
// GET    /s/:id                the schedule as a web page (noindex, no scripts)
//
// Only what the page shows is stored: dates, shift names, colours and times. The app never sends
// memos, pay or leave reasons, and the server keeps no IP addresses (the rate limit key is hashed
// and expires within the hour). Each link deletes itself when it expires.

export const LIMITS = {
  bodyBytes: 48 * 1024,
  days: 200,
  types: 24,
  text: 20,
  title: 30,
  ttlDays: [7, 30, 90],
  createsPerHour: 20,
};

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
  const page = path.match(/^\/s\/([A-Za-z0-9]{16,32})$/);
  if (page && (request.method === "GET" || request.method === "HEAD")) return view(env, page[1]);
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
  };
  await put(env, id, record);
  return json({ id, url: `${url.origin}/s/${id}`, deleteToken, expiresAt: record.expiresAt }, 201);
}

async function update(request, env, url, id) {
  const record = await authorized(request, env, id);
  if (record instanceof Response) return record;
  const parsed = await readSchedule(request);
  if (parsed.error) return json({ error: parsed.error }, 400);
  const next = { ...record, schedule: parsed.schedule, expiresAt: expiry(parsed.ttlDays) };
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

async function view(env, id) {
  const record = await env.SHARES.get(id, "json");
  if (!record || Date.parse(record.expiresAt) < Date.now()) {
    return html(page("링크가 없어요", `<p class="empty">이 근무표 링크는 만료되었거나 공유한 사람이 끊었어요.</p>`), 404);
  }
  return html(renderSchedule(record.schedule, record.expiresAt));
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

/** Keeps only the fields the page uses, in the shapes it expects; anything else is refused. */
export function validate(body) {
  if (!body || typeof body !== "object" || body.v !== 1) return { error: "version" };
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
    types.push({ id: t.id, name, short, color: t.color, time: t.time ?? null, off: t.off === true });
  }
  const ids = new Set(types.map((t) => t.id));
  if (!Array.isArray(body.days) || body.days.length === 0 || body.days.length > LIMITS.days) return { error: "days" };
  const days = [];
  for (const d of body.days) {
    if (!isDate(d?.date) || !ids.has(d?.type)) return { error: "day" };
    if (d.second != null && !ids.has(d.second)) return { error: "day_second" };
    const leave = d.leave == null ? null : cleanText(d.leave, 6);
    if (leave === null && d.leave != null) return { error: "day_leave" };
    days.push({ date: d.date, type: d.type, second: d.second ?? null, leave });
  }
  days.sort((a, b) => a.date.localeCompare(b.date));
  return { schedule: { v: 1, title, types, days }, ttlDays };
}

function cleanText(value, max) {
  if (typeof value !== "string") return null;
  const s = value.replace(/[\u0000-\u001f\u007f]/g, "").trim();
  return s.length > max ? null : s;
}
const isColor = (c) => typeof c === "string" && /^#[0-9A-Fa-f]{6}$/.test(c);
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

/** Month grids for every month the days touch, weeks starting on Sunday. */
export function renderSchedule(schedule, expiresAt) {
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
  return page(title, `<h1>${esc(title)}</h1>${grids.join("")}<ul class="legend">${legend}</ul>` +
    `<p class="muted foot">밤낮 앱에서 공유한 읽기 전용 근무표예요. ${until}까지 열려요.</p>`);
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
.leave{font-size:11px;color:var(--muted)}.legend{list-style:none;padding:0;margin:20px 0 0;display:flex;flex-wrap:wrap;gap:8px 16px}
.legend li{display:flex;align-items:center;gap:6px}.muted{color:var(--muted)}.foot{margin-top:24px;font-size:13px}.empty{margin-top:40px}
</style></head><body><main>${body}</main></body></html>`;
}
