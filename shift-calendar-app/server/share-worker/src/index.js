// [MKT-09] Read-only schedule links for 밤낮. Cloudflare Worker + KV, no accounts.
//
// POST   /api/share            body: schedule JSON  -> { id, url, deleteToken, expiresAt }
// PUT    /api/share/:id        same body, header X-Delete-Token -> { id, url, expiresAt }
//                              [SHR-12] without ttlDays the link keeps its end date; with it the link stays open that long from now
// DELETE /api/share/:id        header X-Delete-Token -> 204
// GET    /api/share/:id        [SHR-02] the schedule for the app's 받은 달력 -> { schedule, expiresAt, updatedAt }
//                              [SHR-12] with an ETag; If-None-Match with it answers 304 while nothing changed
// GET    /s/:id                the schedule as a web page (noindex, no scripts)
// GET    /s/:id.ics            [FEAT-15] the same days as a calendar to subscribe to, only when the sharer turned it on
// GET    /.well-known/assetlinks.json   lets Android open /s/ links in the app (ANDROID_CERT_SHA256)
// POST   /api/share/:id/report body {reason} -> 204   [SRV-05] the app's 신고
// POST   /s/:id/report         the page's own form (no script) -> a thank-you page
// GET    /api/admin/reports    [SRV-05] reported links, most reported first (Bearer SHARE_ADMIN_TOKEN)
// DELETE /api/admin/share/:id  takes a link down: the page then says it was removed (410)
// GET    /api/admin/stats?days=  links made, pages seen, reports and take-downs per day [STAT-04]
// PUT    /api/admin/flags      [SRV-08] {createOff}: the company server's 근무표 링크 새로 만들기 switch
// POST   /api/groups           [SHR-14] {link, token} -> { code, expiresAt }: a 6-letter 모임 코드 holding that link
// POST   /api/groups/:code     {link, token} -> { code, expiresAt, links }: adds one's own link to a group
// DELETE /api/groups/:code/:id header X-Delete-Token -> 204: takes one's own link out
// GET    /api/groups/:code     -> { code, expiresAt, links: [id…] }: the links still open, nothing else
//                              The admin paths answer 404 until SHARE_ADMIN_TOKEN (20+ characters) is set;
//                              the company server's 개발자 앱 calls them with the same token.
//
// Only what the page shows is stored: dates, shift names, colours and times, and since v2 [SHR-01]
// only what the sharer ticked of 휴가 종류, 연장·휴무일 근무, 메모 and 급여, and since 1.13 [SHR-11] 일정 (a
// freelancer's job titles and times) and the money in the sharer's own kind (급여, 일감 수입 or 매출). The server keeps no IP
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
  // [PROV-04] 180 days and a year since 1.20 (프로 in the app).
  ttlDays: [7, 30, 90, 180, 365],
  createsPerHour: 20,
  // [SHR-01] What a sharer may add to v2 links.
  memo: 80,
  extras: 4,
  extraName: 10,
  payMonths: 6,
  payLabel: 10,
  payAmount: 10_000_000_000,
  // [SHR-11] A freelancer's jobs on a day: title and time only.
  jobs: 4,
  jobName: 12,
  // [SRV-05] Reports: a few an hour from one caller, one per link and caller a day.
  reportsPerHour: 10,
  // A taken-down link answers "removed" this long, then is gone like any other.
  removedDays: 90,
  // [STAT-04] Day counters are kept this long, and written at most every few minutes per Worker.
  statsDays: 400,
  statsFlushMs: 5 * 60 * 1000,
  // [SHR-13] The words an operator gave when taking a link down.
  removedReason: 200,
  // [SHR-14] A 모임 코드: how many links, how many groups one caller may start or join in an hour.
  groupLinks: 12,
  groupsPerHour: 10,
  // [SRV-08] .ics: calendar apps may keep it this long.
  icsMaxAge: 300,
};

/** [SRV-05] Why someone reports a link (the page and the app offer the same list). */
export const REPORT_REASONS = {
  personal: "다른 사람의 개인정보",
  abuse: "욕설·괴롭힘",
  spam: "광고·스팸",
  illegal: "불법 정보",
  other: "그 밖의 문제",
};

/** [SHR-01] The parts a sharer can tick on top of the shifts. */
export const SHOWS = ["leaveKinds", "extras", "memos", "pay", "jobs"];

/** [SHR-11] What the money in `pay` is: an employee's pay, a freelancer's job income, a shop's sales. */
export const PAY_KINDS = { pay: "급여", jobs: "일감 수입", sales: "매출" };

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
  async fetch(request, env, ctx) {
    try {
      return await route(request, env);
    } catch (e) {
      return json({ error: "server" }, 500);
    } finally {
      // [STAT-04] Counts reach KV in batches, so a busy link does not spend the day's writes.
      const due = flushStats(env, false);
      if (ctx?.waitUntil) ctx.waitUntil(due); else await due;
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (path === "/api/share" && request.method === "POST") return create(request, env, url);
  if (path === "/api/groups" || path.startsWith("/api/groups/")) return groups(request, env, path);
  if (path.startsWith("/api/admin/")) return admin(request, env, url);
  const reported = path.match(/^\/(api\/share|s)\/([A-Za-z0-9]{16,32})\/report$/);
  if (reported && request.method === "POST") return report(request, env, reported[2], reported[1] === "s");
  const api = path.match(/^\/api\/share\/([A-Za-z0-9]{16,32})$/);
  if (api && request.method === "PUT") return update(request, env, url, api[1]);
  if (api && request.method === "DELETE") return remove(request, env, api[1]);
  if (api && request.method === "GET") return read(request, env, api[1]);
  if (path === "/.well-known/assetlinks.json" && request.method === "GET") return assetLinks(env);
  const page = path.match(/^\/s\/([A-Za-z0-9]{16,32})$/);
  if (page && (request.method === "GET" || request.method === "HEAD")) return view(request, env, page[1]);
  const feed = path.match(/^\/s\/([A-Za-z0-9]{16,32})\.ics$/);
  if (feed && (request.method === "GET" || request.method === "HEAD")) return calendar(request, env, feed[1]);
  if (path === "/" || path === "/robots.txt") {
    return new Response("User-agent: *\nDisallow: /\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
  }
  return new Response("Not found", { status: 404 });
}

async function create(request, env, url) {
  // [SRV-08] Resting (the company server's switch): nothing new, whatever app asks.
  if (await env.SHARES.get(CREATE_OFF)) return json({ error: "paused" }, 503);
  if (!(await allowCreate(request, env))) return json({ error: "rate_limited" }, 429);
  const parsed = await readSchedule(request);
  if (parsed.error) return json({ error: parsed.error }, 400);
  const id = randomId(22);
  const deleteToken = randomId(32);
  const record = {
    schedule: parsed.schedule,
    tokenHash: await sha256(deleteToken),
    expiresAt: expiry(parsed.ttlDays ?? 30),
    updatedAt: new Date().toISOString(),
  };
  await put(env, id, record);
  count(env, "created");
  return json({ id, url: `${url.origin}/s/${id}`, deleteToken, expiresAt: record.expiresAt }, 201);
}

async function update(request, env, url, id) {
  const record = await authorized(request, env, id);
  if (record instanceof Response) return record;
  const parsed = await readSchedule(request);
  if (parsed.error) return json({ error: parsed.error }, 400);
  // [SHR-12] The app sends every change by itself; only the sharer's 연장 (a ttlDays) moves the end date.
  const expiresAt = parsed.ttlDays == null ? record.expiresAt : expiry(parsed.ttlDays);
  if (Date.parse(expiresAt) < Date.now()) return json({ error: "not_found" }, 404);
  const same = JSON.stringify(parsed.schedule) === JSON.stringify(record.schedule);
  const next = { ...record, schedule: parsed.schedule, expiresAt, updatedAt: same ? record.updatedAt : new Date().toISOString() };
  await put(env, id, next);
  return json({ id, url: `${url.origin}/s/${id}`, expiresAt: next.expiresAt, updatedAt: next.updatedAt });
}

async function remove(request, env, id) {
  const record = await authorized(request, env, id);
  if (record instanceof Response) return record;
  await kvDel(env, id);
  return new Response(null, { status: 204 });
}

async function authorized(request, env, id) {
  const token = request.headers.get("x-delete-token") || "";
  const record = await env.SHARES.get(id, "json");
  if (!record) return json({ error: "not_found" }, 404);
  // [SRV-05] A link taken down for breaking the rules cannot be edited back.
  if (record.removed) return json({ error: "removed" }, 410);
  if (!token || (await sha256(token)) !== record.tokenHash) return json({ error: "forbidden" }, 403);
  return record;
}

async function view(request, env, id) {
  const record = await env.SHARES.get(id, "json");
  if (record?.removed) return html(page("내려진 링크예요", `<p class="empty">${REMOVED_TEXT}</p>`), 410);
  if (!record || Date.parse(record.expiresAt) < Date.now()) {
    return html(page("링크가 없어요", `<p class="empty">이 근무표 링크는 만료되었거나 공유한 사람이 끊었어요.</p>`), 404);
  }
  if (request.method === "GET") count(env, "views");
  // [SHR-02] On Android the page offers the app, which keeps it as a 받은 달력 next to the user's own.
  const android = /Android/i.test(request.headers.get("user-agent") || "");
  return html(renderSchedule(record.schedule, record.expiresAt, android ? id : null, id));
}

/** [SRV-08] The key the company server's switch writes while new links rest. */
const CREATE_OFF = "flag:create_off";

const REMOVED_TEXT = "이 근무표 링크는 신고를 받아 운영 규칙에 따라 내려졌어요. 공유한 분이 앱에서 새 링크를 만들 수 있어요.";

/**
 * [FEAT-15] The link's days as an iCalendar feed that Google, Apple and Outlook calendars subscribe to.
 * Only for a link whose sharer turned the address on; it says no more than the page does.
 */
async function calendar(request, env, id) {
  const record = await env.SHARES.get(id, "json");
  if (!record || record.removed || Date.parse(record.expiresAt) < Date.now() || record.schedule.ics !== true) {
    return new Response("Not found", { status: 404, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
  }
  // [SRV-08] A calendar app that has the same days gets 304, and caches for five minutes only, so a
  // link taken down leaves subscribed calendars soon after.
  const tag = `W/"${(await sha256(`ics|${record.updatedAt ?? ""}|${record.expiresAt}`)).slice(0, 20)}"`;
  const headers = {
    "content-type": "text/calendar; charset=utf-8",
    "content-disposition": `inline; filename="bamnat-${id.slice(0, 6)}.ics"`,
    "cache-control": `public, max-age=${LIMITS.icsMaxAge}`,
    "x-robots-tag": "noindex, nofollow",
    etag: tag,
  };
  if (matches(request, tag)) return new Response(null, { status: 304, headers });
  return new Response(renderIcs(record.schedule, id, record.updatedAt), { headers });
}

/** Whether If-None-Match names [tag] (weak or strong). */
function matches(request, tag) {
  const asked = (request.headers.get("if-none-match") || "").split(",").map((t) => t.trim().replace(/^W\//, ""));
  return asked.includes(tag.replace(/^W\//, ""));
}

/** [SHR-02] What the app's 받은 달력 shows: the same schedule as the page, never the token hash. */
async function read(request, env, id) {
  const record = await env.SHARES.get(id, "json");
  // [SHR-13] The sharer's app (and a receiver) is told why, in the operator's words.
  if (record?.removed) return json({ error: "removed", ...(record.reason ? { reason: record.reason } : {}), at: record.at ?? null }, 410);
  if (!record || Date.parse(record.expiresAt) < Date.now()) return json({ error: "not_found" }, 404);
  // [SHR-12] Receivers ask often and download only when the schedule or its end date changed.
  const tag = `W/"${(await sha256(`${record.updatedAt ?? ""}|${record.expiresAt}`)).slice(0, 20)}"`;
  const asked = (request.headers.get("if-none-match") || "").split(",").map((t) => t.trim().replace(/^W\//, ""));
  if (asked.includes(tag.replace(/^W\//, ""))) {
    return new Response(null, { status: 304, headers: { etag: tag, "cache-control": "no-store" } });
  }
  const res = json({ schedule: record.schedule, expiresAt: record.expiresAt, updatedAt: record.updatedAt ?? null });
  res.headers.set("etag", tag);
  return res;
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

// ---- [SRV-05] reports and take-downs ----

/**
 * Someone who got the link reports it. Kept per link as a count by reason (in the key's metadata, so the
 * list needs no extra reads), never who reported: the caller is only a salted hash that expires in a day.
 */
async function report(request, env, id, fromPage) {
  const done = (status, text) => fromPage
    ? html(page("신고", `<p class="empty">${text}</p>`), status)
    : status === 204 ? new Response(null, { status }) : json({ error: text }, status);
  let reason;
  const type = request.headers.get("content-type") || "";
  const text = (await request.text()).slice(0, 1024);
  if (type.startsWith("application/x-www-form-urlencoded")) reason = new URLSearchParams(text).get("reason");
  else if (type.startsWith("application/json")) {
    try { reason = JSON.parse(text)?.reason; } catch { reason = null; }
  }
  if (!Object.hasOwn(REPORT_REASONS, reason ?? "")) return done(400, fromPage ? "신고 이유를 골라 주세요." : "reason");
  const record = await env.SHARES.get(id, "json");
  if (!record || record.removed || Date.parse(record.expiresAt) < Date.now()) return done(404, fromPage ? "이미 닫힌 링크예요." : "not_found");
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const day = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const once = `rp:${(await sha256(`${env.RATE_SALT || "bamnat"}:${ip}:${id}:${day}`)).slice(0, 24)}`;
  const thanks = "신고를 받았어요. 운영자가 확인하고 운영 규칙에 어긋나면 링크를 내려요. 고마워요.";
  // The same caller reporting the same link again the same day is thanked and not counted twice.
  if (await env.SHARES.get(once)) return done(fromPage ? 200 : 204, thanks);
  if (!(await allow(env, request, "rr", LIMITS.reportsPerHour))) return done(429, fromPage ? "잠시 뒤에 다시 신고해 주세요." : "rate_limited");
  await kvPut(env, once, "1", { expirationTtl: 86400 });
  const key = `report:${id}`;
  const got = await env.SHARES.getWithMetadata?.(key);
  const was = got?.metadata ?? { count: 0, reasons: {} };
  const next = { count: was.count + 1, reasons: { ...was.reasons, [reason]: (was.reasons?.[reason] ?? 0) + 1 }, last: new Date().toISOString() };
  const ttl = Math.max(60, Math.ceil((Date.parse(record.expiresAt) - Date.now()) / 1000));
  await kvPut(env, key, "1", { expirationTtl: ttl, metadata: next });
  count(env, "reports");
  return done(fromPage ? 200 : 204, thanks);
}

/** The company server's 개발자 앱 only: a shared token in Authorization, compared as digests. */
async function admin(request, env, url) {
  const secret = typeof env.SHARE_ADMIN_TOKEN === "string" ? env.SHARE_ADMIN_TOKEN.trim() : "";
  if (secret.length < 20) return new Response("Not found", { status: 404 });
  const given = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if ((await sha256(`admin|${given}`)) !== (await sha256(`admin|${secret}`))) return json({ error: "forbidden" }, 403);
  const path = url.pathname.slice("/api/admin".length);
  if (path === "/reports" && request.method === "GET") return json({ reports: await reports(env) });
  const m = path.match(/^\/share\/([A-Za-z0-9]{16,32})$/);
  if (m && request.method === "DELETE") return takeDown(request, env, m[1]);
  if (path === "/stats" && request.method === "GET") return json(await stats(env, url.searchParams.get("days")));
  if (path === "/flags" && request.method === "PUT") {
    let body = {};
    try { body = JSON.parse((await request.text()).slice(0, 256)); } catch { /* nothing */ }
    if (body.createOff === true) await kvPut(env, CREATE_OFF, "1");
    else await kvDel(env, CREATE_OFF);
    return json({ createOff: body.createOff === true });
  }
  return json({ error: "not_found" }, 404);
}

async function reports(env) {
  const out = [];
  let cursor;
  do {
    const page = await env.SHARES.list({ prefix: "report:", cursor });
    for (const k of page.keys) if (k.metadata) out.push({ id: k.name.slice("report:".length), ...k.metadata });
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor && out.length < 1000);
  return out.sort((a, b) => b.count - a.count || b.last.localeCompare(a.last)).slice(0, 200);
}

async function takeDown(request, env, id) {
  const record = await env.SHARES.get(id, "json");
  if (!record) return json({ error: "not_found" }, 404);
  let reason = "";
  try {
    reason = String(JSON.parse((await request.text()).slice(0, 2048))?.reason ?? "");
  } catch { /* no reason given */ }
  reason = reason.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, LIMITS.removedReason);
  if (!record.removed) {
    // Only the mark stays: the schedule itself is gone at once. [SHR-13] With the operator's reason, as long as the mark.
    await kvPut(env, id, JSON.stringify({ removed: true, at: new Date().toISOString(), ...(reason ? { reason } : {}) }),
      { expirationTtl: LIMITS.removedDays * 86400 });
    count(env, "removed");
  }
  await kvDel(env, `report:${id}`);
  return new Response(null, { status: 204 });
}

// ---- [SHR-14] 우리 조 모아 보기: links put together under a 6-letter code ----
//
// Each coworker shares their own link and adds it to a group with its code. The group is a list of link
// ids and nothing else: no names, no company, not who made it. Only a link's own delete token adds or
// takes it out, so nobody can add someone else's link, and a link cut or expired drops out by itself.
// The group goes when its longest link ends.

const GROUP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const GROUP_CODE = /^[A-HJ-NP-Z2-9]{6}$/;

async function groups(request, env, path) {
  const m = path.match(/^\/api\/groups(?:\/([A-Za-z0-9]{6}))?(?:\/([A-Za-z0-9]{16,32}))?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const code = m[1]?.toUpperCase();
  if (code && !GROUP_CODE.test(code)) return json({ error: "not_found" }, 404);
  if (!code && request.method === "POST") return groupStart(request, env);
  if (code && !m[2] && request.method === "POST") return groupJoin(request, env, code);
  if (code && !m[2] && request.method === "GET") return groupRead(env, code);
  if (code && m[2] && request.method === "DELETE") return groupLeave(request, env, code, m[2]);
  return json({ error: "not_found" }, 404);
}

/** The caller's own link, proved by its delete token, or the answer that refuses it. */
async function ownLink(request, env) {
  let body = {};
  try { body = JSON.parse((await request.text()).slice(0, 1024)); } catch { return { error: json({ error: "bad_json" }, 400) }; }
  const id = typeof body.link === "string" && /^[A-Za-z0-9]{16,32}$/.test(body.link) ? body.link : null;
  if (!id || typeof body.token !== "string") return { error: json({ error: "link" }, 400) };
  const record = await env.SHARES.get(id, "json");
  if (!record || record.removed || Date.parse(record.expiresAt) < Date.now()) return { error: json({ error: "not_found" }, 404) };
  if ((await sha256(body.token)) !== record.tokenHash) return { error: json({ error: "forbidden" }, 403) };
  return { id, record };
}

async function liveLinks(env, ids) {
  const out = [];
  for (const id of ids) {
    const r = await env.SHARES.get(id, "json");
    if (r && !r.removed && Date.parse(r.expiresAt) >= Date.now()) out.push({ id, expiresAt: r.expiresAt });
  }
  return out;
}

async function saveGroup(env, code, links) {
  const ends = links.reduce((a, l) => (l.expiresAt > a ? l.expiresAt : a), new Date().toISOString());
  const ttl = Math.max(60, Math.ceil((Date.parse(ends) - Date.now()) / 1000));
  await kvPut(env, `group:${code}`, JSON.stringify({ ids: links.map((l) => l.id) }), { expirationTtl: ttl });
  return ends;
}

async function groupStart(request, env) {
  if (!(await allow(env, request, "rg", LIMITS.groupsPerHour))) return json({ error: "rate_limited" }, 429);
  const own = await ownLink(request, env);
  if (own.error) return own.error;
  let code;
  for (let i = 0; i < 5 && !code; i++) {
    const c = groupCode();
    if (!(await env.SHARES.get(`group:${c}`))) code = c;
  }
  if (!code) return json({ error: "server" }, 503);
  const expiresAt = await saveGroup(env, code, [{ id: own.id, expiresAt: own.record.expiresAt }]);
  return json({ code, expiresAt, links: [own.id] }, 201);
}

async function groupJoin(request, env, code) {
  if (!(await allow(env, request, "rg", LIMITS.groupsPerHour))) return json({ error: "rate_limited" }, 429);
  const group = await env.SHARES.get(`group:${code}`, "json");
  if (!group) return json({ error: "not_found" }, 404);
  const own = await ownLink(request, env);
  if (own.error) return own.error;
  const links = (await liveLinks(env, group.ids)).filter((l) => l.id !== own.id);
  if (links.length >= LIMITS.groupLinks) return json({ error: "full" }, 409);
  links.push({ id: own.id, expiresAt: own.record.expiresAt });
  const expiresAt = await saveGroup(env, code, links);
  return json({ code, expiresAt, links: links.map((l) => l.id) });
}

async function groupRead(env, code) {
  const group = await env.SHARES.get(`group:${code}`, "json");
  if (!group) return json({ error: "not_found" }, 404);
  const links = await liveLinks(env, group.ids);
  if (links.length === 0) return json({ error: "not_found" }, 404);
  return json({ code, expiresAt: links.reduce((a, l) => (l.expiresAt > a ? l.expiresAt : a), ""), links: links.map((l) => l.id) });
}

async function groupLeave(request, env, code, id) {
  const group = await env.SHARES.get(`group:${code}`, "json");
  if (!group || !group.ids.includes(id)) return new Response(null, { status: 204 });
  const record = await env.SHARES.get(id, "json");
  // A link already cut or taken down has left by itself; otherwise only its own token takes it out.
  if (record && !record.removed && (await sha256(request.headers.get("x-delete-token") || "")) !== record.tokenHash) {
    return json({ error: "forbidden" }, 403);
  }
  const links = (await liveLinks(env, group.ids)).filter((l) => l.id !== id);
  if (links.length === 0) await kvDel(env, `group:${code}`);
  else await saveGroup(env, code, links);
  return new Response(null, { status: 204 });
}

function groupCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  // 32 letters divide 256, so every letter is equally likely.
  return [...bytes].map((b) => GROUP_CHARS[b % 32]).join("");
}

// ---- [STAT-04] day counters ----

// [SRV-10] Every KV write is counted too (KV's free day is 1,000 writes), so the company server can rest
// 근무표 링크 새로 만들기 before the limit (N8). Like the rest, a rough number.
async function kvPut(env, key, value, options) {
  count(env, "writes");
  return env.SHARES.put(key, value, options);
}

async function kvDel(env, key) {
  count(env, "writes");
  return env.SHARES.delete(key);
}

const pending = new WeakMap();

function count(env, field) {
  if (!env.SHARES) return;
  const day = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  let p = pending.get(env.SHARES);
  if (!p) pending.set(env.SHARES, (p = { since: Date.now(), days: {} }));
  const d = (p.days[day] ??= {});
  d[field] = (d[field] ?? 0) + 1;
}

/**
 * Adds what this Worker counted to the day keys, at most every [LIMITS.statsFlushMs] unless [now] is
 * asked. Two Workers flushing at the same moment can lose a few counts: these are rough numbers.
 */
export async function flushStats(env, now = true) {
  const p = env.SHARES && pending.get(env.SHARES);
  if (!p || Object.keys(p.days).length === 0) return;
  if (!now && Date.now() - p.since < LIMITS.statsFlushMs) return;
  pending.delete(env.SHARES);
  for (const [day, add] of Object.entries(p.days)) {
    const key = `stats:${day}`;
    const was = (await env.SHARES.get(key, "json")) ?? {};
    for (const [k, v] of Object.entries(add)) was[k] = (was[k] ?? 0) + v;
    was.writes = (was.writes ?? 0) + 1;
    await env.SHARES.put(key, JSON.stringify(was), { expirationTtl: LIMITS.statsDays * 86400 });
  }
}

async function stats(env, daysParam) {
  const n = Math.min(90, Math.max(1, parseInt(daysParam ?? "28", 10) || 28));
  await flushStats(env);
  const today = Date.now() + 9 * 3600 * 1000;
  const days = [];
  for (let i = n - 1; i >= 0; i--) {
    const day = new Date(today - i * 86400 * 1000).toISOString().slice(0, 10);
    const got = (await env.SHARES.get(`stats:${day}`, "json")) ?? {};
    days.push({ day, created: got.created ?? 0, views: got.views ?? 0, reports: got.reports ?? 0, removed: got.removed ?? 0, writes: got.writes ?? 0 });
  }
  const sum = (k) => days.reduce((a, d) => a + d[k], 0);
  // [SRV-08] Counted in each Worker and added up every few minutes: two at once can lose a few, so 대략.
  return { days, created: sum("created"), views: sum("views"), reports: sum("reports"), removed: sum("removed"), approximate: true };
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
  // [SHR-12] No ttlDays: a new link gets 30 days, an update keeps its end date.
  const ttlDays = body.ttlDays == null ? null : LIMITS.ttlDays.includes(body.ttlDays) ? body.ttlDays : 30;
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
  if (!Array.isArray(body.days) || body.days.length > LIMITS.days) return { error: "days" };
  // [SHR-11] A shop or a freelancer may have no shifts at all and send only the months' money.
  const moneyOnly = v2 && shows.includes("pay") && Array.isArray(body.pay) && body.pay.length > 0;
  if (body.days.length === 0 && !moneyOnly) return { error: "days" };
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
    if (shows.includes("jobs") && d.jobs != null) {
      if (!Array.isArray(d.jobs) || d.jobs.length > LIMITS.jobs) return { error: "day_jobs" };
      const jobs = [];
      for (const j of d.jobs) {
        const name = cleanText(j?.name, LIMITS.jobName);
        if (!name) return { error: "day_job" };
        if (j.time != null && !isTimeRange(j.time)) return { error: "day_job" };
        jobs.push({ name, time: j.time ?? null });
      }
      if (jobs.length) day.jobs = jobs;
    }
    if (day.type == null && day.second == null && !day.leave && !day.memo && !day.extras && !day.jobs) return { error: "day" };
    days.push(day);
  }
  days.sort((a, b) => a.date.localeCompare(b.date));
  const schedule = { v: v2 ? 2 : 1, title, types, days };
  // [FEAT-15] The sharer turned on the address calendar apps subscribe to, in the time zone of their phone.
  if (body.ics != null && typeof body.ics !== "boolean") return { error: "ics" };
  if (body.ics === true) {
    schedule.ics = true;
    const tz = body.tz ?? "Asia/Seoul";
    if (typeof tz !== "string" || !/^[A-Za-z]+(?:\/[A-Za-z0-9_+-]+){1,2}$/.test(tz) || tz.length > 40) return { error: "tz" };
    schedule.tz = tz;
  }
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
      const payKind = body.payKind ?? "pay";
      if (!Object.hasOwn(PAY_KINDS, payKind)) return { error: "pay_kind" };
      schedule.payKind = payKind;
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
  await kvPut(env, id, JSON.stringify(record), { expirationTtl: ttl });
}

function expiry(days) {
  return new Date(Date.now() + days * 86400 * 1000).toISOString();
}

/** A per-hour count of new links per caller. The key is a salted hash, so no address is stored. */
const allowCreate = (request, env) => allow(env, request, "rl", LIMITS.createsPerHour);

/**
 * [SRV-08] KV has no atomic add, so twenty requests at the same moment could all read the same count.
 * Each Worker also counts in memory (synchronously, before any await), and the larger of the two counts
 * decides: a burst from one caller reaches one Worker and is held to [max]. Across Workers the limit is
 * still approximate (KV is eventually consistent); Durable Objects would make it exact but are not on
 * the free plan this server is meant for (README).
 */
const counted = new WeakMap();

async function allow(env, request, prefix, max) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const hour = Math.floor(Date.now() / 3600000);
  const local = `${prefix}|${ip}|${hour}`;
  let counts = counted.get(env.SHARES);
  if (!counts) counted.set(env.SHARES, (counts = new Map()));
  const mine = (counts.get(local) ?? 0) + 1;
  counts.set(local, mine);
  if (counts.size > 5000) for (const k of counts.keys()) if (!k.endsWith(`|${hour}`)) counts.delete(k);
  if (mine > max) return false;
  // "rl" keeps the hash it always had, so a deploy does not reset the hour's count.
  const salt = prefix === "rl" ? "" : `${prefix}:`;
  const key = `${prefix}:${(await sha256(`${env.RATE_SALT || "bamnat"}:${salt}${ip}:${hour}`)).slice(0, 24)}`;
  // The first use here adds to what other Workers counted; after that this Worker's own count leads.
  const stored = parseInt((await env.SHARES.get(key)) || "0", 10);
  const n = Math.max(mine === 1 ? stored + 1 : stored, mine);
  if (n > max) return false;
  await kvPut(env, key, String(n), { expirationTtl: 3600 });
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
      // [SRV-05] The only form is the page's own 신고, sent back to this address.
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    },
  });
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** [SHR-01] The words for each part a sharer can tick, in the order the page lists them. */
const SHOW_NAMES = { jobs: "일정", leaveKinds: "휴가 종류", extras: "연장·휴무일 근무", memos: "메모", pay: "급여" };

/** [SHR-11] How the page explains the money, by what it is. */
const PAY_NOTES = {
  pay: "보낸 사람의 앱이 계산한 예상 금액이라 실제 명세서와 다를 수 있어요.",
  jobs: "보낸 사람이 앱에 적은 일감 금액을 달마다 더했어요. 3.3%를 뗀 금액이에요.",
  sales: "보낸 사람이 앱에 적은 매출을 달마다 더했어요. 경비는 빼지 않은 금액이에요.",
};

const won = (n) => `${String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",")}원`;

/** [SHR-02] Opens the app on Android, or its store page when it is not installed. */
function appLink(id) {
  const store = encodeURIComponent("https://play.google.com/store/apps/details?id=com.bamnat.app");
  return `intent://s/${id}#Intent;scheme=bamnat;package=com.bamnat.app;S.browser_fallback_url=${store};end`;
}

/** Month grids for every month the days touch, weeks starting on Sunday. */
export function renderSchedule(schedule, expiresAt, appId = null, reportId = null) {
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
          (entry?.jobs ?? []).map((j) => `<span class="extra job">${esc(j.name)}${j.time ? ` ${esc(j.time.slice(0, 5))}` : ""}</span>`).join("") +
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
  const payKind = Object.hasOwn(PAY_KINDS, schedule.payKind ?? "") ? schedule.payKind : "pay";
  const shows = Object.keys(SHOW_NAMES).filter((k) => (schedule.shows ?? []).includes(k))
    .map((k) => (k === "pay" ? PAY_KINDS[payKind] : SHOW_NAMES[k]));
  const has = shows.length ? `<p class="muted">근무와 함께 보낸 것: ${esc(shows.join(", "))}</p>` : "";
  const pay = schedule.pay?.length
    ? `<section class="pay"><h2>함께 보낸 ${PAY_KINDS[payKind]}</h2><ul>${schedule.pay.map((p) => {
        const [y, m] = p.month.split("-").map(Number);
        return `<li><span>${y}년 ${m}월${p.label ? ` <span class="muted">${esc(p.label)}</span>` : ""}</span><b>${won(p.amount)}</b></li>`;
      }).join("")}</ul><p class="muted">${PAY_NOTES[payKind]}</p></section>`
    : "";
  const app = appId
    ? `<p class="app"><a href="${esc(appLink(appId))}">밤낮 앱에서 보기</a><span class="muted">앱에서는 내 달력과 바꿔 가며 볼 수 있어요</span></p>`
    : "";
  return page(title, `<h1>${esc(title)}</h1>${has}${app}${grids.join("")}<ul class="legend">${legend}</ul>${pay}` +
    `<p class="muted foot">밤낮 앱에서 공유한 읽기 전용 근무표예요. ${until}까지 열려요.</p>` + (reportId ? reportForm(reportId) : ""));
}

/** [SRV-05] A plain form, so reporting works without scripts (the page allows none). */
function reportForm(id) {
  const options = Object.entries(REPORT_REASONS).map(([k, v], i) =>
    `<label><input type="radio" name="reason" value="${k}"${i === 0 ? " required" : ""}> ${esc(v)}</label>`).join("");
  return `<details class="report"><summary>이 링크 신고하기</summary><form method="post" action="/s/${esc(id)}/report">` +
    `<p class="muted">운영자가 확인해 운영 규칙에 어긋나면 링크를 내려요. 누가 신고했는지는 남지 않아요.</p>${options}` +
    `<button type="submit">신고 보내기</button></form></details>`;
}

/**
 * [FEAT-15] RFC 5545: each work day an event at its shift's times (or all day without them), a second
 * shift and timed extra work as events of their own, leave as an all-day event; days off are left out.
 * UIDs stay the same for a date, so a changed day replaces its event in the subscriber's calendar.
 */
export function renderIcs(schedule, id, updatedAt) {
  const types = new Map(schedule.types.map((t) => [t.id, t]));
  const tz = schedule.tz || "Asia/Seoul";
  const stamp = icsStamp(updatedAt ? new Date(updatedAt) : new Date());
  const name = schedule.title ? `${schedule.title}의 근무` : "밤낮 근무";
  const lines = [
    "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Bamnat//Share//KO", "CALSCALE:GREGORIAN", "METHOD:PUBLISH",
    `X-WR-CALNAME:${icsText(name)}`, `X-WR-TIMEZONE:${tz}`, "REFRESH-INTERVAL;VALUE=DURATION:PT6H", "X-PUBLISHED-TTL:PT6H",
  ];
  const event = (uid, summary, date, time, description) => {
    lines.push("BEGIN:VEVENT", `UID:${uid}@${id}.bamnat`, `DTSTAMP:${stamp}`);
    const d = date.replaceAll("-", "");
    if (time) {
      const [from, to] = time.split("-");
      const end = to <= from ? nextDay(date).replaceAll("-", "") : d;
      lines.push(`DTSTART;TZID=${tz}:${d}T${from.replace(":", "")}00`, `DTEND;TZID=${tz}:${end}T${to.replace(":", "")}00`);
    } else {
      lines.push(`DTSTART;VALUE=DATE:${d}`, `DTEND;VALUE=DATE:${nextDay(date).replaceAll("-", "")}`);
    }
    lines.push(`SUMMARY:${icsText(summary)}`);
    if (description) lines.push(`DESCRIPTION:${icsText(description)}`);
    lines.push(time ? "TRANSP:OPAQUE" : "TRANSP:TRANSPARENT", "END:VEVENT");
  };
  for (const day of schedule.days) {
    const memo = day.memo || null;
    for (const [slot, typeId] of [["s", day.type], ["t", day.second]]) {
      const t = typeId == null ? null : types.get(typeId);
      if (t && !t.off) event(`${day.date}-${slot}`, t.name, day.date, t.time, slot === "s" ? memo : null);
    }
    if (day.leave) event(`${day.date}-l`, day.leave, day.date, null, null);
    (day.extras ?? []).forEach((e, i) => { if (e.time) event(`${day.date}-x${i}`, e.name, day.date, e.time, null); });
    (day.jobs ?? []).forEach((j, i) => event(`${day.date}-j${i}`, j.name, day.date, j.time, null));
  }
  lines.push("END:VCALENDAR");
  return lines.map(icsFold).join("\r\n") + "\r\n";
}

function nextDay(date) {
  return new Date(Date.parse(`${date}T00:00:00Z`) + 86400 * 1000).toISOString().slice(0, 10);
}

const icsStamp = (d) => d.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
const icsText = (s) => String(s).replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");

/** Lines longer than 75 octets go on after a space, never splitting a Korean character. */
function icsFold(line) {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;
  let out = "";
  let count = 0;
  let limit = 75;
  for (const ch of line) {
    const n = enc.encode(ch).length;
    if (count + n > limit) { out += "\r\n "; count = 0; limit = 74; }
    out += ch;
    count += n;
  }
  return out;
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
.job{background:#C2A36B33}.memo{font-size:10px;line-height:1.25;color:var(--muted);text-align:center;word-break:break-all;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.app{display:flex;flex-wrap:wrap;align-items:center;gap:6px 12px;margin:0 0 8px}.app a{display:inline-block;padding:10px 16px;border-radius:999px;background:var(--today);color:#15192B;font-weight:700;text-decoration:none}
.pay ul{list-style:none;padding:0;margin:0;background:var(--card);border:1px solid var(--line);border-radius:12px}.pay li{display:flex;justify-content:space-between;gap:12px;padding:12px 14px;border-top:1px solid var(--line)}.pay li:first-child{border-top:none}
.legend{list-style:none;padding:0;margin:20px 0 0;display:flex;flex-wrap:wrap;gap:8px 16px}
.legend li{display:flex;align-items:center;gap:6px}.report{margin-top:16px;font-size:13px}.report summary{color:var(--muted);cursor:pointer}.report label{display:block;padding:4px 0}.report button{margin-top:8px;padding:8px 14px;border-radius:999px;border:1px solid var(--line);background:var(--card);color:var(--fg)}.muted{color:var(--muted)}.foot{margin-top:24px;font-size:13px}.empty{margin-top:40px}
</style></head><body><main>${body}</main></body></html>`;
}
