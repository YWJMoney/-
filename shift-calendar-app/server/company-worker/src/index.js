// [COMM-04] 밤낮 company rules: what coworkers at one company share, so the next person sets up in a
// minute. One Cloudflare Worker with one D1 database (binding DB); the developer app is at /admin.
//
// GET    /api/companies?q=        search by name; spaces, (주) and 주식회사 do not count [COMM-01]
// GET    /api/companies/:id       a company's setups: adopted ones, and ones coworkers are still checking
// POST   /api/reports             a coworker's setup, without any amounts [COMM-02]
// GET    /api/reports/:id         where a report stands: points, adopted or not, the reward
// DELETE /api/reports/:id         header X-Device: the sender takes their report back
// DELETE /api/mine                header X-Device: everything this install sent, gone
// POST   /api/votes               맞았어요 / 달라요 on a setup [COMM-03]
// GET    /api/board?tab=&q=        every company's rule sets: ones still being voted on, or adopted; q finds a company [COMM-07]
// GET    /api/companies/:id/setups/:setup/posts   the talk on one rule set [COMM-06]
// POST   /api/posts               a post in that talk; DELETE /api/posts/:id takes it back (header X-Device)
// POST   /api/posts/:id/flag      신고: three people hide a post until the developer looks
// *      /api/inquiries...        header X-Device: questions and problem reports from the app [ADMIN-02]
// GET    /admin/                  the developer app, installable on a phone [ADMIN-01][ADMIN-03]
// GET    /admin/join#<invite>     an invited admin installs the app and makes an account [ADMIN-04]
// *      /api/admin/*             behind a sign-in: the owner's password (secret ADMIN_PASSWORD), or an
//                                 admin account the owner invited by email and approved, with only the
//                                 permissions the owner gave it
//
// A report carries no pay amounts, names or contacts; one that looks like it does is refused, not
// cleaned. No IP address is stored: limits and "the same network within an hour" use hashes salted
// with the secret RATE_SALT, cleared within two hours; what stays is a random mark shared by sends
// from one network within the hour. The app's install id is kept only as a hash, so a sender counts
// once and can take a report back.

import { adminFile } from "./admin-app.js";

const LIMITS = {
  reportBytes: 32 * 1024,
  reportsPerDay: 10,
  reportsPerNetworkDay: 30,
  votesPerHour: 60,
  company: 40,
  dept: 20,
  note: 500,
  // A way of paying the app cannot set yet needs room to explain it and give an example.
  ask: 1500,
  // [COMM-06] A post in the talk on a rule set.
  post: 500,
  postsPerDay: 20,
  postsPerNetworkDay: 60,
  flagsToHide: 3,
  muteDays: 30,
  hiddenPostDays: 90,
  loginFailures: 5,
  lockMs: 60 * 60 * 1000,
  sessionMs: 7 * 24 * 60 * 60 * 1000,
  // [ADMIN-04] An invite link works for a week; a mailed code for 15 minutes and five tries.
  inviteMs: 7 * 24 * 60 * 60 * 1000,
  codeMs: 15 * 60 * 1000,
  codeTries: 5,
  joinsPerHour: 20,
  email: 120,
  adminName: 30,
};

/** Until changed in the developer app. */
// [COMM-07] adoptAgree: the share of 맞았어요 among everyone who voted that a rule set needs to be adopted on its own.
const DEFAULTS = { adoptPoints: 3, rewardMonths: 3, adoptAgree: 75 };

/** The newest company settings file this server reads (CompanyShare.VERSION in the app). */
// [PAYS-03] 3 carries 감시·단속 승인, 5인 미만 공휴일, 유급 휴게 and allowances paid instead of a premium.
const SETUP_VERSION = 3;

/** Why a report was not adopted, as the sender's app shows it. */
const REASONS = {
  different: "같은 회사 분들이 알려 준 설정과 달라요",
  unclear: "정보가 부족해서 확인하기 어려워요",
  wrong: "실제와 다른 정보로 보여요",
  duplicate: "이미 같은 내용이 있어요",
  inappropriate: "알맞지 않은 내용이 들어 있어요",
};

/** [EXTRA-06] The developer's answer to a way of paying the app cannot set yet, as the sender's app shows it. */
const ANSWERS = {
  planned: "알려 주신 방식을 앱에 넣을게요. 업데이트되면 바로 설정할 수 있어요",
  possible: "지금 앱으로도 맞출 수 있는 방식이에요. 연장 휴무일 근무 지정과 급여 설정을 다시 봐 주세요",
  later: "지금은 넣기 어려워요. 같은 방식을 알려 주는 분이 많아지면 다시 볼게요",
  unclear: "내용만으로는 알기 어려워요. 예시를 더 넣어 다시 보내 주세요",
};

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
const ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

/**
 * [ADMIN-04] What an admin account can be given, area by area: nothing, a look, or changes too. The owner
 * (signed in with ADMIN_PASSWORD) has everything and alone manages admins. Activity can only be looked at.
 */
const AREAS = {
  review: "검토",
  inquiries: "문의",
  companies: "회사·규칙",
  rewards: "보상",
  settings: "채택 설정",
  // [COMM-06] The talk on rule sets: what people flagged, hiding, showing again and muting a writer.
  community: "토론",
  audit: "활동 기록",
};
const LEVELS = ["none", "view", "edit"];
const VIEW_ONLY = ["audit"];
const PRESETS = {
  manager: { name: "운영 관리자", perms: { review: "edit", inquiries: "edit", companies: "edit", rewards: "edit", settings: "edit", community: "edit", audit: "view" } },
  reviewer: { name: "검토 담당", perms: { review: "edit", inquiries: "none", companies: "view", rewards: "none", settings: "none", community: "edit", audit: "none" } },
  support: { name: "문의 담당", perms: { review: "view", inquiries: "edit", companies: "view", rewards: "none", settings: "none", community: "view", audit: "none" } },
  viewer: { name: "보기 전용", perms: { review: "view", inquiries: "view", companies: "view", rewards: "view", settings: "view", community: "view", audit: "view" } },
};
const OWNER = { id: "owner", name: "최고 관리자", email: "", owner: true, perms: Object.fromEntries(Object.keys(AREAS).map((a) => [a, VIEW_ONLY.includes(a) ? "view" : "edit"])) };

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof Refuse) return json({ error: e.code, field: e.field }, e.status);
      console.error(e && e.stack ? e.stack : String(e));
      return json({ error: "server" }, 500);
    }
  },
  // Hourly (wrangler.toml [triggers], or the dashboard's Cron Triggers): what is promised to go, goes on
  // time even when nobody sends anything.
  async scheduled(event, env, ctx) {
    if (!env.DB) return;
    ctx.waitUntil(ready(env).then(() => prune(env, Date.now())));
  },
};

/**
 * Deletes what the server only keeps for a while: network hashes after two hours, spent limits, sign-ins,
 * invites and codes, questions with nothing new for 180 days, hidden posts after 90 days, ended 쓰기 막기
 * and the admin log after a year.
 */
export async function prune(env, now) {
  await env.DB.batch([
    stmt(env, "UPDATE reports SET net = NULL WHERE net IS NOT NULL AND created_at < ?", now - 2 * HOUR),
    stmt(env, "UPDATE votes SET net = NULL WHERE net IS NOT NULL AND at < ?", now - 2 * HOUR),
    stmt(env, "DELETE FROM counters WHERE expires < ?", now),
    stmt(env, "DELETE FROM admin_sessions WHERE expires < ?", now),
    stmt(env, "DELETE FROM invites WHERE used_at IS NULL AND expires < ?", now),
    stmt(env, "DELETE FROM codes WHERE expires < ?", now),
    stmt(env, "DELETE FROM inquiry_messages WHERE inquiry_id IN (SELECT id FROM inquiries WHERE updated_at < ?)", now - INQUIRY.keepMs),
    stmt(env, "DELETE FROM inquiries WHERE updated_at < ?", now - INQUIRY.keepMs),
    // [COMM-06] A hidden post nobody restored is gone after 90 days, and a 30-day 쓰기 막기 when it ends.
    stmt(env, "DELETE FROM post_flags WHERE post_id IN (SELECT id FROM posts WHERE status = 'hidden' AND hidden_at < ?)", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM posts WHERE status = 'hidden' AND hidden_at < ?", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM muted WHERE until < ?", now),
    stmt(env, "DELETE FROM audit WHERE at < ?", now - 365 * DAY),
  ]);
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  if (path === "/" || path === "/robots.txt") {
    return new Response("User-agent: *\nDisallow: /\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
  }
  if (path === "/admin") return Response.redirect(`${url.origin}/admin/`, 301);
  if (path.startsWith("/admin/")) return adminFile(path);
  if (path === "/api/admin/health" && method === "GET") return json(health(env));
  if (!env.DB) return json({ error: "no_database" }, 503);
  await ready(env);
  if (path.startsWith("/api/admin/")) return admin(request, env, url);
  if (path === "/api/companies" && method === "GET") return search(env, url);
  if (path === "/api/terms" && method === "GET") return json(await terms(env));
  let m = path.match(/^\/api\/companies\/([A-Za-z0-9]{12})$/);
  if (m && method === "GET") return company(env, m[1]);
  if (path === "/api/reports" && method === "POST") return report(request, env);
  m = path.match(/^\/api\/reports\/([A-Za-z0-9]{22})$/);
  if (m && method === "GET") return reportStatus(env, m[1]);
  if (m && method === "DELETE") return takeBack(request, env, m[1]);
  if (path === "/api/mine" && method === "DELETE") return forgetMine(request, env);
  if (path === "/api/votes" && method === "POST") return vote(request, env);
  if (path === "/api/board" && method === "GET") return json(await board(env, url));
  m = path.match(/^\/api\/companies\/([A-Za-z0-9]{12})\/setups\/([0-9a-f]{16})\/posts$/);
  if (m && method === "GET") return talk(request, env, m[1], m[2]);
  if (path === "/api/posts" && method === "POST") return post(request, env);
  m = path.match(/^\/api\/posts\/(p[A-Za-z0-9]{15})(\/flag)?$/);
  if (m && method === "DELETE" && !m[2]) return takePostBack(request, env, m[1]);
  if (m && method === "POST" && m[2]) return flag(request, env, m[1]);
  if (path === "/api/inquiries" || path.startsWith("/api/inquiries/")) return inquiries(request, env, path);
  return json({ error: "not_found" }, 404);
}

// ---- storage ----

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS companies (id TEXT PRIMARY KEY, name TEXT NOT NULL, norm TEXT NOT NULL,
    hidden INTEGER NOT NULL DEFAULT 0, merged_into TEXT, created_at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS companies_norm ON companies (norm)",
  "CREATE TABLE IF NOT EXISTS aliases (norm TEXT PRIMARY KEY, company_id TEXT NOT NULL, name TEXT NOT NULL)",
  "CREATE INDEX IF NOT EXISTS aliases_company ON aliases (company_id)",
  `CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, company_id TEXT NOT NULL, dept TEXT NOT NULL,
    dept_norm TEXT NOT NULL, grp TEXT NOT NULL, setup TEXT NOT NULL, verified INTEGER NOT NULL, note TEXT NOT NULL,
    device TEXT NOT NULL, net TEXT, cluster TEXT NOT NULL, points INTEGER NOT NULL, status TEXT NOT NULL,
    rule_id TEXT, reason TEXT, created_at INTEGER NOT NULL, decided_at INTEGER)`,
  "CREATE INDEX IF NOT EXISTS reports_group ON reports (company_id, grp)",
  "CREATE INDEX IF NOT EXISTS reports_status ON reports (status, created_at)",
  "CREATE INDEX IF NOT EXISTS reports_device ON reports (device)",
  `CREATE TABLE IF NOT EXISTS votes (company_id TEXT NOT NULL, grp TEXT NOT NULL, device TEXT NOT NULL,
    cluster TEXT NOT NULL, ok INTEGER NOT NULL, verified INTEGER NOT NULL, why TEXT, net TEXT, at INTEGER NOT NULL,
    PRIMARY KEY (company_id, grp, device))`,
  `CREATE TABLE IF NOT EXISTS rules (id TEXT PRIMARY KEY, company_id TEXT NOT NULL, dept TEXT NOT NULL,
    dept_norm TEXT NOT NULL, grp TEXT NOT NULL, version INTEGER NOT NULL, setup TEXT NOT NULL, source TEXT NOT NULL,
    adopted_at INTEGER NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0, superseded INTEGER NOT NULL DEFAULT 0)`,
  "CREATE INDEX IF NOT EXISTS rules_company ON rules (company_id, dept_norm)",
  `CREATE TABLE IF NOT EXISTS rewards (id TEXT PRIMARY KEY, report_id TEXT NOT NULL UNIQUE, rule_id TEXT NOT NULL,
    device TEXT NOT NULL, months INTEGER NOT NULL, status TEXT NOT NULL, memo TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL, paid_at INTEGER)`,
  "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
  "CREATE TABLE IF NOT EXISTS counters (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL)",
  `CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL,
    target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')`,
  // [ADMIN-04] Admin accounts, the emails the owner allowed, and who is signed in (the owner is "owner").
  `CREATE TABLE IF NOT EXISTS admins (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL, pw TEXT NOT NULL,
    perms TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, approved_at INTEGER, last_login INTEGER)`,
  `CREATE TABLE IF NOT EXISTS invites (email TEXT PRIMARY KEY, perms TEXT NOT NULL, note TEXT NOT NULL DEFAULT '',
    token TEXT NOT NULL UNIQUE, expires INTEGER NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER)`,
  `CREATE TABLE IF NOT EXISTS admin_sessions (token TEXT PRIMARY KEY, admin TEXT NOT NULL, expires INTEGER NOT NULL,
    created_at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS admin_sessions_admin ON admin_sessions (admin)",
  `CREATE TABLE IF NOT EXISTS codes (email TEXT PRIMARY KEY, code TEXT NOT NULL, expires INTEGER NOT NULL,
    tries INTEGER NOT NULL DEFAULT 0)`,
  // [COMM-06] The talk on a rule set. A writer is kept as the install's hash only, shown to others as 참여자 N
  // of that one talk, so posts in two talks cannot be tied together by anyone reading them.
  `CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, company_id TEXT NOT NULL, grp TEXT NOT NULL, device TEXT NOT NULL,
    body TEXT NOT NULL, status TEXT NOT NULL, flags INTEGER NOT NULL DEFAULT 0, kept INTEGER NOT NULL DEFAULT 0,
    hide_reason TEXT, created_at INTEGER NOT NULL, hidden_at INTEGER)`,
  "CREATE INDEX IF NOT EXISTS posts_talk ON posts (company_id, grp, created_at)",
  "CREATE INDEX IF NOT EXISTS posts_status ON posts (status, flags)",
  "CREATE INDEX IF NOT EXISTS posts_device ON posts (device)",
  `CREATE TABLE IF NOT EXISTS post_flags (post_id TEXT NOT NULL, device TEXT NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (post_id, device))`,
  "CREATE TABLE IF NOT EXISTS muted (device TEXT PRIMARY KEY, until INTEGER NOT NULL, reason TEXT NOT NULL)",
  // [ADMIN-02] Questions from the app and the developer's answers.
  `CREATE TABLE IF NOT EXISTS inquiries (id TEXT PRIMARY KEY, device TEXT NOT NULL, kind TEXT NOT NULL, info TEXT NOT NULL,
    status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS inquiries_device ON inquiries (device, updated_at)",
  "CREATE INDEX IF NOT EXISTS inquiries_status ON inquiries (status, updated_at)",
  `CREATE TABLE IF NOT EXISTS inquiry_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, inquiry_id TEXT NOT NULL,
    sender TEXT NOT NULL, text TEXT NOT NULL, by TEXT, at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS inquiry_messages_inquiry ON inquiry_messages (inquiry_id, id)",
  // Sign-ins before admin accounts kept only a token; they end once, and the owner signs in again.
  "DROP TABLE IF EXISTS sessions",
];

/** Columns added after a server was first deployed; adding one that is already there is skipped. */
const COLUMNS = [
  "ALTER TABLE audit ADD COLUMN actor TEXT NOT NULL DEFAULT ''",
];

const schemas = new WeakMap();

/** Tables are made on first use, so setting up the server is binding an empty D1 database. */
function ready(env) {
  let done = schemas.get(env.DB);
  if (!done) {
    done = env.DB.batch(SCHEMA.map((sql) => env.DB.prepare(sql))).then(async () => {
      for (const sql of COLUMNS) {
        try {
          await env.DB.prepare(sql).run();
        } catch (e) {
          if (!/duplicate column/i.test(String(e?.message ?? e))) throw e;
        }
      }
    }).catch((e) => {
      schemas.delete(env.DB);
      throw e;
    });
    schemas.set(env.DB, done);
  }
  return done;
}

const stmt = (env, sql, ...params) => env.DB.prepare(sql).bind(...params);
const one = (env, sql, ...params) => stmt(env, sql, ...params).first();
const all = async (env, sql, ...params) => (await stmt(env, sql, ...params).all()).results;
const run = (env, sql, ...params) => stmt(env, sql, ...params).run();

async function settings(env) {
  const rows = await all(env, "SELECT key, value FROM settings WHERE key IN ('adopt_points', 'reward_months', 'adopt_agree')");
  const read = (key, fallback) => {
    const n = Number(rows.find((r) => r.key === key)?.value);
    return Number.isInteger(n) ? n : fallback;
  };
  return { adoptPoints: read("adopt_points", DEFAULTS.adoptPoints), rewardMonths: read("reward_months", DEFAULTS.rewardMonths),
    adoptAgree: read("adopt_agree", DEFAULTS.adoptAgree) };
}

async function salt(env) {
  if (typeof env.RATE_SALT === "string" && env.RATE_SALT) return env.RATE_SALT;
  // Until the secret is set the server keeps a salt of its own; the developer app asks for RATE_SALT.
  await run(env, "INSERT OR IGNORE INTO settings (key, value) VALUES ('salt', ?)", randomId(32));
  return (await one(env, "SELECT value FROM settings WHERE key = 'salt'")).value;
}

/** Salted hashes of the caller's address: this hour's, last hour's, the day's and one for logins. */
async function network(request, env, now) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const s = await salt(env);
  const hash = async (what) => (await sha256(`${s}|${what}|${ip}`)).slice(0, 32);
  const hour = Math.floor(now / HOUR);
  return {
    now: await hash(`net:${hour}`),
    prev: await hash(`net:${hour - 1}`),
    day: await hash(`day:${Math.floor(now / DAY)}`),
    login: await hash("login"),
  };
}

/** Counts one use against [limit] for the window; false once the window's uses are spent. */
async function allow(env, key, limit, windowMs, now) {
  const row = await one(env, `INSERT INTO counters (key, count, expires) VALUES (?1, 1, ?2)
    ON CONFLICT (key) DO UPDATE SET
      count = CASE WHEN expires <= ?3 THEN 1 ELSE count + 1 END,
      expires = CASE WHEN expires <= ?3 THEN ?2 ELSE expires END
    RETURNING count`, key, now + windowMs, now);
  return row.count <= limit;
}

const deviceId = (v) => (typeof v === "string" && /^[A-Za-z0-9-]{16,64}$/.test(v) ? v : null);

/** An install as the server keeps it: a hash of the app's random id, never the id itself. */
const deviceHash = async (id) => (await sha256(`device|${id}`)).slice(0, 40);

/** [ADMIN-04] Who did it comes from the request: admin() puts the signed-in admin on env.actor. */
async function audit(env, now, action, target, detail = "") {
  const who = env.actor ? (env.actor.owner ? OWNER.name : `${env.actor.name} <${env.actor.email}>`) : "";
  await env.DB.batch([
    stmt(env, "INSERT INTO audit (at, action, target, detail, actor) VALUES (?, ?, ?, ?, ?)", now, action, target,
      String(detail).slice(0, 300), who),
    // [COMM-06] A hidden post nobody restored is gone after 90 days, and a 30-day 쓰기 막기 when it ends.
    stmt(env, "DELETE FROM post_flags WHERE post_id IN (SELECT id FROM posts WHERE status = 'hidden' AND hidden_at < ?)", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM posts WHERE status = 'hidden' AND hidden_at < ?", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM muted WHERE until < ?", now),
    stmt(env, "DELETE FROM audit WHERE at < ?", now - 365 * DAY),
  ]);
}

// ---- names and what may not be shared ----

/** A name as search sees it: no spaces, marks or company forms such as (주), 주식회사, Co., Ltd. */
export function normName(s) {
  return String(s ?? "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/\((주|株|유|사|재|합)\)|주식회사|유한회사|유한책임회사|합자회사|합명회사|사단법인|재단법인|\b(co|ltd|inc|corp|llc)\b\.?/g, "")
    .replace(/[^\p{L}\p{N}]/gu, "");
}

const PERSONAL = [
  ["email", /[^\s@]+@[^\s@]+\.[a-z]{2,}/i],
  ["phone", /(\+?82[\s.-]?0?|0)\d{1,2}[\s.-]?\d{3,4}[\s.-]?\d{4}/],
  ["amount", /\d[\d,.]*\s*(원|만\s*원|천\s*원|억\s*원|won\b|krw\b)|[일이삼사오육칠팔구십백천]+\s*만\s*원|\d{1,3}(,\d{3})+|\d{5,}/i],
];

/** Why a text cannot be shared: it holds an email, a phone number or an amount of money. */
export function personal(text) {
  for (const [code, re] of PERSONAL) if (re.test(text)) return code;
  return null;
}

// ---- a setup: what a report may hold ----

class Refuse extends Error {
  constructor(code, field, status = 400) {
    super(code);
    this.code = code;
    this.field = field;
    this.status = status;
  }
}

const refuse = (code, field) => {
  throw new Refuse(code, field);
};

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;
const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const BASES = ["LEGAL", "FIXED_PER_TIME", "PERCENT_OF_BASE", "FIXED_PER_HOUR", "HOURLY_RATE", "TIERED", "UNIT"];
const UNITS = ["ORDINARY", "BASE_SHARE", "PER_HOUR", "PER_TIME"];
const CADENCES = ["MONTHLY", "PER_WORK_DAY", "PER_NIGHT_SHIFT", "PER_SHIFT_TYPE", "BONUS"];
const BREAK_PAY = ["UNPAID", "PAID", "AS_WORK"];
/** The premiums an allowance can stand in for, in the app's order. */
const COVERABLE = ["OVERTIME", "NIGHT", "HOLIDAY"];
const KIND_KEY = /^(\d{1,6}|OVERTIME|SPECIAL|COVER)$/;
const LEGACY_KINDS = { OVERTIME: "1", SPECIAL: "2", COVER: "3" };
const SCOPE = /^(all|over:\d{1,4}|law|night|window:([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d|holiday|rest)$/;
const TIMING = /^(B,([01]\d|2[0-3]):[0-5]\d,([01]\d|2[0-3]):[0-5]\d,\d{1,3},(-|([01]\d|2[0-3]):[0-5]\d)|A,\d{1,4}|P,\d{1,4}|S,(-|\d{1,6}(;\d{1,6})*))$/;

const given = (v) => v !== undefined && v !== null;
const object = (v, field) => (v && typeof v === "object" && !Array.isArray(v) ? v : refuse("setup", field));
const list = (v, max, field) => (Array.isArray(v) && v.length <= max ? v : refuse("setup", field));
const bool = (v, field) => (typeof v === "boolean" ? v : refuse("setup", field));
const int = (v, lo, hi, field) => (Number.isInteger(v) && v >= lo && v <= hi ? v : refuse("setup", field));
const real = (v, lo, hi, field) => (typeof v === "number" && Number.isFinite(v) && v >= lo && v <= hi ? v : refuse("setup", field));
const oneOf = (v, choices, field) => (choices.includes(v) ? v : refuse("setup", field));
const time = (v, field) => (typeof v === "string" && TIME.test(v) ? v : refuse("setup", field));

function str(v, max, field, { empty = true, lines = false, code = "setup" } = {}) {
  if (typeof v !== "string") refuse(code, field);
  const s = v.replace(lines ? /[\u0000-\u0009\u000b-\u001f\u007f]/g : /[\u0000-\u001f\u007f]/g, "").trim();
  if (s.length > max || (!empty && !s)) refuse(code, field);
  return s;
}

function date(v, field) {
  const ok = typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(`${v}T00:00:00Z`));
  return ok ? v : refuse("setup", field);
}

/**
 * [COMM-02] A company settings file (CompanyShare in the app) cut down to the fields the app reads,
 * each one checked. Anything carrying money is refused rather than dropped, so a bug in the app shows
 * up instead of the server quietly keeping something the sender never saw. [texts] collects every
 * name in it for the personal-information check.
 */
export function cleanSetup(raw, texts = []) {
  const say = (field, s) => {
    if (s) texts.push([field, s]);
    return s;
  };
  object(raw, "setup");
  if (raw.format !== "bamnat-company") refuse("setup", "format");
  if (int(raw.v, 1, 99, "v") > SETUP_VERSION) refuse("setup_version", "v");
  const shiftTypes = list(raw.shiftTypes, 24, "shiftTypes").map((t) => {
    object(t, "shiftTypes");
    return {
      name: say("shiftTypes", str(t.name, 40, "shiftTypes", { empty: false })),
      label: say("shiftTypes", str(t.label ?? "", 8, "shiftTypes")),
      color: int(t.color ?? 4284246976, 0, 4294967295, "shiftTypes"),
      start: given(t.start) ? time(t.start, "shiftTypes") : null,
      end: given(t.end) ? time(t.end, "shiftTypes") : null,
      break: int(t.break ?? 0, 0, 720, "shiftTypes"),
      breakStart: given(t.breakStart) ? time(t.breakStart, "shiftTypes") : null,
      off: bool(t.off ?? false, "shiftTypes"),
      icon: given(t.icon) ? str(t.icon, 32, "shiftTypes") : null,
    };
  });
  const names = new Set(shiftTypes.map((t) => t.name));
  if (shiftTypes.length === 0 || names.size !== shiftTypes.length) refuse("setup", "shiftTypes");
  const setup = {
    app: typeof raw.app === "string" ? raw.app.slice(0, 40) : "com.bamnat.app",
    format: "bamnat-company",
    v: raw.v,
    company: "",
    shiftTypes,
  };

  const kindIds = new Set();
  if (given(raw.extraKinds)) {
    const lines = str(raw.extraKinds, 4000, "extraKinds", { lines: true }).split("\n");
    if (lines[0].trim() !== "v2" || lines.length > 41) refuse("setup", "extraKinds");
    const kept = lines.slice(1).filter((l) => l.trim()).map((line) => {
      const f = line.split("|");
      if (f.length !== 8) refuse("setup", "extraKinds");
      const id = int(Number(f[0]), 1, 999999, "extraKinds");
      if (kindIds.has(id)) refuse("setup", "extraKinds");
      kindIds.add(id);
      const name = say("extraKinds", str(f[1], 12, "extraKinds", { empty: false }));
      const short = say("extraKinds", str(f[2], 2, "extraKinds"));
      const color = int(Number(f[3]), 0, 4294967295, "extraKinds");
      if (!TIMING.test(f[4])) refuse("setup", "extraKinds");
      // Shift ids differ from phone to phone; the shifts a 대근 covers travel by name below.
      const timing = f[4].startsWith("S,") ? "S,-" : f[4];
      return [id, name, short, color, timing, oneOf(f[5], ["BY_DAY", "HOLIDAY"], "extraKinds"),
        oneOf(f[6], ["0", "1"], "extraKinds"), oneOf(f[7], ["0", "1"], "extraKinds")].join("|");
    });
    setup.extraKinds = ["v2", ...kept].join("\n");
    const shifts = {};
    const sets = {};
    if (given(raw.extraKindShifts)) {
      for (const [k, n] of Object.entries(object(raw.extraKindShifts, "extraKindShifts"))) {
        if (kindIds.has(Number(k)) && names.has(n)) shifts[k] = n;
      }
    }
    if (given(raw.extraKindShiftSets)) {
      for (const [k, a] of Object.entries(object(raw.extraKindShiftSets, "extraKindShiftSets"))) {
        const covered = [...new Set(list(a, 24, "extraKindShiftSets").filter((n) => names.has(n)))];
        if (kindIds.has(Number(k)) && covered.length) sets[k] = covered;
      }
    }
    setup.extraKindShifts = shifts;
    setup.extraKindShiftSets = sets;
  }

  if (given(raw.rotation)) {
    const r = object(raw.rotation, "rotation");
    const steps = list(r.steps, 60, "rotation").map((s) => (s === null ? null : names.has(s) ? s : refuse("setup", "rotation")));
    if (steps.length === 0) refuse("setup", "rotation");
    setup.rotation = { anchor: date(r.anchor, "rotation"), steps };
  }

  if (given(raw.pay)) {
    const p = object(raw.pay, "pay");
    if (p.withAmounts === true) refuse("amount", "pay");
    const pay = {
      fiveOrMore: bool(p.fiveOrMore, "pay"),
      monthlyHours: int(p.monthlyHours, 1, 400, "pay"),
      holidayMode: oneOf(p.holidayMode, ["FULL", "PREMIUM_ONLY"], "pay"),
      dailyLimit: int(p.dailyLimit ?? -1, -1, 1440, "pay"),
      weeklyLimit: int(p.weeklyLimit ?? -1, -1, 10080, "pay"),
      scheduleIsRegular: bool(p.scheduleIsRegular ?? false, "pay"),
      weekStart: oneOf(p.weekStart ?? "MONDAY", DAYS, "pay"),
      period: period(p.period),
      withAmounts: false,
      items: given(p.items) ? list(p.items, 30, "items").map((i) => item(i, names, say)) : [],
    };
    if (given(p.flexPeriod)) pay.flexPeriod = oneOf(p.flexPeriod, ["MONTH", "QUARTER"], "pay");
    if (given(p.rules)) pay.rules = rules(p.rules, names, kindIds, say);
    setup.pay = pay;
  }
  // [PAYS-03] Only a setup that uses the newer choices is 3, so apps before them can still read the rest.
  setup.v = usesNewer(setup.pay) ? 3 : Math.min(setup.v, 2);
  return setup;
}

/** Whether [pay] keeps any choice only version 3 of the file has. */
function usesNewer(pay) {
  const r = pay?.rules;
  return Boolean(r && (r.supervisory || r.holidaysU5 || r.breakPay) || pay?.items.some((i) => i.covers));
}

function period(raw) {
  if (!given(raw)) return { endDay: -1, payDay: -1, payNextMonth: false };
  const o = object(raw, "pay");
  return {
    endDay: int(o.endDay ?? -1, -1, 31, "pay"),
    payDay: int(o.payDay ?? -1, -1, 31, "pay"),
    payNextMonth: bool(o.payNextMonth ?? false, "pay"),
  };
}

/** A kind named in the rules; one the file does not list could land on someone else's own kind. */
function kindRef(key, kindIds) {
  if (typeof key !== "string" || !KIND_KEY.test(key)) refuse("setup", "rules");
  const id = LEGACY_KINDS[key] ?? String(Number(key));
  return Number(id) <= 3 || kindIds.has(Number(id)) ? id : null;
}

function rules(raw, names, kindIds, say) {
  const r = object(raw, "rules");
  const rate = (v) => (given(v) ? real(v, 0, 5, "rules") : null);
  const out = {
    ot: rate(r.ot),
    night: rate(r.night),
    hw: rate(r.hw),
    ho: rate(r.ho),
    restDay: oneOf(r.restDay ?? "LAW", ["LAW", "HOLIDAY", "OVERTIME"], "rules"),
    offDay: oneOf(r.offDay ?? "HOLIDAY", ["HOLIDAY", "OVERTIME"], "rules"),
    weeklyHolidayDay: oneOf(r.weeklyHolidayDay ?? "UNKNOWN", ["FIRST_REST", "UNKNOWN", ...DAYS], "rules"),
    publicHolidays: bool(r.publicHolidays ?? true, "rules"),
    roundUnit: int(r.roundUnit ?? 1, 1, 60, "rules"),
    round: oneOf(r.round ?? "DOWN", ["UP", "NEAREST", "DOWN"], "rules"),
    weeklyHoliday: bool(r.weeklyHoliday ?? true, "rules"),
    // Leave days are the sender's own, never the company's.
    annualLeave: null,
    leaveCarry: 0,
    holidayLeaveFree: bool(r.holidayLeaveFree ?? false, "rules"),
    holidayByStart: bool(r.holidayByStart ?? false, "rules"),
    extraByShift: {},
    extra: {},
  };
  // [PAYS-03] Kept only when set, so a setup without them keeps its fingerprint and reads as before.
  if (bool(r.supervisory ?? false, "rules")) out.supervisory = true;
  if (bool(r.holidaysU5 ?? false, "rules")) out.holidaysU5 = true;
  const breakPay = oneOf(r.breakPay ?? "UNPAID", BREAK_PAY, "rules");
  if (breakPay !== "UNPAID") out.breakPay = breakPay;
  if (given(r.publicHolidayAs)) {
    const kind = kindRef(String(r.publicHolidayAs), kindIds);
    if (kind) out.publicHolidayAs = kind;
  }
  if (given(r.nightStart)) out.nightStart = time(r.nightStart, "rules");
  if (given(r.nightEnd)) out.nightEnd = time(r.nightEnd, "rules");
  if (given(r.extra)) {
    for (const [k, e] of Object.entries(object(r.extra, "rules"))) {
      const kind = kindRef(k, kindIds);
      if (kind) out.extra[kind] = extraRule(e, say);
    }
  }
  if (given(r.extraByShift)) {
    for (const [k, byShift] of Object.entries(object(r.extraByShift, "rules"))) {
      const kind = kindRef(k, kindIds);
      if (!kind) continue;
      const kept = {};
      for (const [n, e] of Object.entries(object(byShift, "rules"))) if (names.has(n)) kept[n] = extraRule(e, say);
      out.extraByShift[kind] = kept;
    }
  }
  return out;
}

function extraRule(raw, say) {
  const e = object(raw, "rules");
  // The company's way, never its money: amounts per time or hour and 1회 수당 stay on the phone.
  if ((e.amount ?? 0) !== 0 || (e.bonus ?? 0) !== 0) refuse("amount", "rules");
  const out = {
    basis: oneOf(e.basis, BASES, "rules"),
    multiplier: real(e.multiplier ?? 1.5, 0, 10, "rules"),
    amount: 0,
    min: int(e.min ?? 0, 0, 1440, "rules"),
    night: bool(e.night ?? true, "rules"),
    percent: real(e.percent ?? 0.1, 0, 10, "rules"),
    cover: int(e.cover ?? -1, -1, 1440, "rules"),
    premiums: bool(e.premiums ?? false, "rules"),
    bonus: 0,
    unit: oneOf(e.unit ?? "ORDINARY", UNITS, "rules"),
    unitMin: int(e.unitMin ?? 480, 30, 1440, "rules"),
    adds: given(e.adds) ? list(e.adds, 10, "rules").map((p) => premium(p, say)) : [],
  };
  if (given(e.dayTiers)) out.dayTiers = tiers(e.dayTiers);
  if (given(e.nightTiers)) out.nightTiers = tiers(e.nightTiers);
  return out;
}

function tiers(v) {
  return list(v, 8, "rules").map((t) => {
    object(t, "rules");
    return { min: int(t.min, 0, 1440, "rules"), premium: real(t.premium, 0, 5, "rules") };
  });
}

function premium(raw, say) {
  const p = object(raw, "rules");
  const key = str(p.key, 8, "rules", { empty: false });
  if (!/^[A-Za-z0-9_]+$/.test(key)) refuse("setup", "rules");
  if (typeof p.scope !== "string" || !SCOPE.test(p.scope)) refuse("setup", "rules");
  return {
    key,
    name: say("rules", str(p.name ?? "", 12, "rules")),
    rate: real(p.rate, 0, 3, "rules"),
    on: bool(p.on ?? false, "rules"),
    scope: p.scope,
    offDays: bool(p.offDays ?? false, "rules"),
  };
}

function item(raw, names, say) {
  const i = object(raw, "items");
  const a = i.amount;
  // Allowance names and how they are paid travel; what anyone is paid does not.
  if (given(a) && (typeof a !== "object" || (Array.isArray(a.entries) && a.entries.length > 0) || given(a.raise))) refuse("amount", "items");
  const out = {
    id: str(i.id, 24, "items", { empty: false }),
    name: say("items", str(i.name, 20, "items", { empty: false })),
    amount: { entries: [], raise: null },
    cadence: oneOf(i.cadence ?? "MONTHLY", CADENCES, "items"),
    types: given(i.types) ? [...new Set(list(i.types, 24, "items").filter((n) => names.has(n)))] : [],
    ordinary: bool(i.ordinary ?? false, "items"),
    taxable: bool(i.taxable ?? true, "items"),
    months: given(i.months) ? [...new Set(list(i.months, 12, "items").map((m) => int(m, 1, 12, "items")))].sort((x, y) => x - y) : [],
    fixedOvertime: int(i.fixedOvertime ?? 0, 0, 100000, "items"),
    festivals: given(i.festivals) ? [...new Set(list(i.festivals, 2, "items").map((f) => oneOf(f, ["SEOLLAL", "CHUSEOK"], "items")))] : [],
    festivalBefore: bool(i.festivalBefore ?? false, "items"),
  };
  // Left out when there is none: the app reads a present value as a number.
  if (given(i.percentOfBase)) out.percentOfBase = real(i.percentOfBase, 0, 50, "items");
  // [PAYS-03] The premiums it is paid instead of; kept only when not what the app assumes anyway
  // (a 고정 연장수당 stands in for 연장, any other allowance for none).
  if (given(i.covers)) {
    const picked = new Set(list(i.covers, COVERABLE.length, "items").map((k) => oneOf(k, COVERABLE, "items")));
    const covers = COVERABLE.filter((k) => picked.has(k));
    const usual = out.fixedOvertime > 0 && out.cadence === "MONTHLY" ? ["OVERTIME"] : [];
    if (covers.join() !== usual.join()) out.covers = covers;
  }
  return out;
}

/** A report body as sent by the app, checked; throws [Refuse] with what is wrong. */
export function readReport(body) {
  object(body, "body");
  if (body.v !== 1) refuse("version", "v");
  const company = str(body.company, LIMITS.company, "company", { empty: false, code: "company" });
  if (normName(company).length < 2) refuse("company", "company");
  const dept = given(body.dept) ? str(body.dept, LIMITS.dept, "dept", { code: "dept" }) : "";
  if (typeof body.verified !== "boolean") refuse("verified", "verified");
  // [EXTRA-06] A way the app cannot set yet: the setup is only as close as the sender could get.
  if (given(body.ask) && typeof body.ask !== "boolean") refuse("ask", "ask");
  const ask = body.ask === true;
  const note = given(body.note) ? str(body.note, ask ? LIMITS.ask : LIMITS.note, "note", { lines: true, code: "note" }) : "";
  if (ask && !note) refuse("note", "note");
  const texts = [];
  const setup = cleanSetup(body.setup, texts);
  for (const [field, text] of [["company", company], ["dept", dept], ["note", note], ...texts]) {
    const why = personal(text);
    if (why) refuse(why, field);
  }
  return { company, dept, deptNorm: normName(dept), verified: body.verified, note, setup, ask };
}

// ---- the same rule ----

/**
 * [COMM-03] What makes two reports "the same rule": shift times and their order, the kinds of extra
 * work and every pay rule; not what anyone called their shifts, their colors, or which day of the
 * rotation their team is on.
 */
function canonical(setup) {
  const sigs = new Map(setup.shiftTypes.map((t) => [t.name, t.off ? "off" : `${t.start ?? ""}-${t.end ?? ""}/${t.break}@${t.breakStart ?? ""}`]));
  const sig = (name) => (name === null ? "-" : sigs.get(name) ?? "?");
  const kinds = kindLines(setup).map((k) => ({
    id: k.id,
    sig: { timing: k.timing.startsWith("S,") ? "S" : k.timing, covers: k.covers.map(sig).sort(), offDay: k.offDay, counts: k.counts },
  }));
  // Built-in kinds keep their number; a company's own are numbered by what they are.
  const keys = new Map(kinds.filter((k) => Number(k.id) <= 3).map((k) => [k.id, k.id]));
  kinds.filter((k) => Number(k.id) > 3).sort((a, b) => cmp(stable(a.sig), stable(b.sig))).forEach((k, i) => keys.set(k.id, `c${i}`));
  const kindOf = (id) => keys.get(String(id)) ?? (Number(id) <= 3 ? String(id) : `?${id}`);
  const rule = (e) => ({ ...e, adds: e.adds.map((a) => ({ ...a, name: normName(a.name) })).sort((a, b) => cmp(a.key, b.key)) });
  const p = setup.pay;
  const r = p?.rules;
  return {
    cycle: smallestRotation((setup.rotation?.steps ?? []).map(sig)),
    kinds: kinds.map((k) => ({ key: kindOf(k.id), ...k.sig })).sort((a, b) => cmp(a.key, b.key)),
    pay: p ? {
      fiveOrMore: p.fiveOrMore,
      monthlyHours: p.monthlyHours,
      holidayMode: p.holidayMode,
      dailyLimit: p.dailyLimit,
      weeklyLimit: p.weeklyLimit,
      scheduleIsRegular: p.scheduleIsRegular,
      flexPeriod: p.flexPeriod ?? null,
      weekStart: p.weekStart,
      period: p.period,
      rules: r ? {
        ...r,
        offDay: null,
        publicHolidayAs: r.publicHolidayAs ? kindOf(r.publicHolidayAs) : null,
        extra: Object.fromEntries(Object.entries(r.extra).map(([k, e]) => [kindOf(k), rule(e)])),
        extraByShift: Object.fromEntries(Object.entries(r.extraByShift).map(([k, by]) =>
          [kindOf(k), Object.fromEntries(Object.entries(by).map(([n, e]) => [sig(n), rule(e)]))])),
      } : null,
      items: p.items.map((i) => ({
        name: normName(i.name),
        cadence: i.cadence,
        ordinary: i.ordinary,
        taxable: i.taxable,
        months: i.months,
        percentOfBase: i.percentOfBase ?? null,
        fixedOvertime: i.fixedOvertime,
        festivals: [...i.festivals].sort(),
        festivalBefore: i.festivalBefore,
        types: i.types.map(sig).sort(),
        // Only when set, so allowances from before version 3 keep their fingerprint.
        ...(i.covers ? { covers: i.covers } : {}),
      })).sort((a, b) => cmp(stable(a), stable(b))),
    } : null,
  };
}

export async function fingerprint(setup) {
  return sha256(stable(canonical(setup)));
}

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

function smallestRotation(steps) {
  let best = null;
  for (let i = 0; i < steps.length; i++) {
    const turned = [...steps.slice(i), ...steps.slice(0, i)];
    const key = turned.join("\u0001");
    if (best === null || key < best.key) best = { key, turned };
  }
  return best ? best.turned : [];
}

/** JSON with keys in order, so equal values give equal text. */
function stable(v) {
  if (Array.isArray(v)) return `[${v.map(stable).join(",")}]`;
  if (v && typeof v === "object") return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${stable(v[k])}`).join(",")}}`;
  return JSON.stringify(v ?? null);
}

function kindLines(setup) {
  return (setup.extraKinds ?? "").split("\n").slice(1).filter(Boolean).map((line) => {
    const [id, name, , , timing, offDay, counts, hidden] = line.split("|");
    const covers = setup.extraKindShiftSets?.[id] ?? (setup.extraKindShifts?.[id] ? [setup.extraKindShifts[id]] : []);
    return { id, name, timing, offDay, counts: counts === "1", hidden: hidden === "1", covers };
  });
}

// ---- a setup in words, for the developer app ----

const KIND_NAMES = { 1: "잔업", 2: "특근", 3: "대근" };
const WEEKDAYS = { MONDAY: "월", TUESDAY: "화", WEDNESDAY: "수", THURSDAY: "목", FRIDAY: "금", SATURDAY: "토", SUNDAY: "일" };
const pct = (x) => `${Math.round(x * 1000) / 10}%`;
const hours = (m) => (m % 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분` : `${m / 60}시간`);

/** [ADMIN-01] A setup in a few lines of plain Korean, to review at a glance: [label, text] pairs. */
export function describe(setup) {
  const lines = [];
  const add = (label, text) => {
    if (text) lines.push([label, text]);
  };
  add("근무", setup.shiftTypes.map((t) => (t.off ? `${t.name}(쉬는 날)`
    : `${t.name} ${t.start ?? "?"}~${t.end ?? "?"}${t.break ? ` 휴게 ${t.break}분` : ""}`)).join(" · "));
  if (setup.rotation) add(`순서 ${setup.rotation.steps.length}일`, setup.rotation.steps.map((s) => s ?? "빈 날").join(" "));
  const kinds = kindLines(setup);
  const kindName = (id) => kinds.find((k) => k.id === String(id))?.name ?? KIND_NAMES[id] ?? `종류 ${id}`;
  add("연장·휴무일 근무", kinds.filter((k) => !k.hidden).map((k) => `${k.name}(${timingText(k)})`).join(" · "));
  const p = setup.pay;
  if (p) {
    add("사업장", [p.fiveOrMore ? "5인 이상" : "5인 미만", `월 ${p.monthlyHours}시간`,
      p.flexPeriod ? `탄력 ${p.flexPeriod === "MONTH" ? "1개월" : "3개월"}` : "", p.rules?.supervisory ? "감시·단속 승인" : ""].filter(Boolean).join(" · "));
    const r = p.rules;
    if (r) {
      if (!p.fiveOrMore && r.holidaysU5) add("공휴일", "유급휴일로 줌 (5인 미만)");
      if (r.breakPay) add("휴게", r.breakPay === "PAID" ? "유급 (일한 시간은 아님)" : "일한 시간으로 침");
      add("가산율", [["연장", r.ot], ["야간", r.night], ["휴일", r.hw], ["휴일 8시간 넘게", r.ho]]
        .filter(([, v]) => v !== null).map(([n, v]) => `${n} ${pct(v)}`).join(" · ") || "법대로");
      if (r.nightStart && r.nightEnd) add("야간 시간", `${r.nightStart}~${r.nightEnd}`);
      add("쉬는 날 근무", { LAW: "법대로", HOLIDAY: "휴일근로로", OVERTIME: "연장근로로" }[r.restDay]);
      add("주휴일", r.weeklyHolidayDay === "FIRST_REST" ? "쉬는 날 중 첫날" : WEEKDAYS[r.weeklyHolidayDay] ? `${WEEKDAYS[r.weeklyHolidayDay]}요일` : "");
      if (r.publicHolidayAs) add("공휴일 근무", `${kindName(r.publicHolidayAs)} 방식`);
      for (const [k, e] of Object.entries(r.extra)) add(kindName(k), ruleText(e));
      for (const [k, by] of Object.entries(r.extraByShift)) for (const [n, e] of Object.entries(by)) add(`${kindName(k)} · ${n}`, ruleText(e));
    }
    if (p.items.length) add("수당", p.items.map(itemText).join(" · "));
  }
  return lines;
}

function timingText(k) {
  const f = k.timing.split(",");
  if (f[0] === "B") return `${f[1]}~${f[2]}`;
  if (f[0] === "A") return `근무 뒤 ${hours(Number(f[1]))}`;
  if (f[0] === "P") return `근무 앞 ${hours(Number(f[1]))}`;
  return k.covers.length ? `${k.covers.join("·")} 대신` : "모든 근무 대신";
}

function ruleText(e) {
  switch (e.basis) {
    case "LEGAL": return "법대로";
    case "FIXED_PER_TIME": return "1회 정액 (금액은 각자 넣어요)";
    case "FIXED_PER_HOUR": return "시간당 정액 (금액은 각자 넣어요)";
    case "PERCENT_OF_BASE": return `${e.cover > 0 ? hours(e.cover) : "한 번"}에 기본급 ${pct(e.percent)}${e.premiums ? " + 법정 가산" : ""}`;
    case "HOURLY_RATE": return `통상시급 × ${pct(e.multiplier)}`;
    case "TIERED": return `시간 구간별 가산 ${(e.dayTiers ?? []).map((t) => `${hours(t.min)}부터 +${pct(t.premium)}`).join(", ")}`.trim();
    default: {
      const unit = {
        ORDINARY: "통상시급",
        BASE_SHARE: `${hours(e.unitMin)}에 기본급 ${pct(e.percent)}`,
        PER_HOUR: "시간당 정액 (금액은 각자)",
        PER_TIME: `1회 정액 (금액은 각자)${e.cover > 0 ? ` ${hours(e.cover)}까지` : ""}`,
      }[e.unit];
      const adds = e.adds.filter((a) => a.on).map((a) => `${a.name || a.key} ${pct(a.rate)}${scopeText(a.scope)}${a.offDays ? " (쉬는 날만)" : ""}`);
      return [unit, ...adds].join(" + ") + (e.min > 0 ? ` · 최소 ${hours(e.min)}` : "");
    }
  }
}

function scopeText(scope) {
  if (scope.startsWith("over:")) return ` (${hours(Number(scope.slice(5)))} 넘게)`;
  if (scope.startsWith("window:")) return ` (${scope.slice(7).replace("-", "~")})`;
  return "";
}

function itemText(i) {
  const how = {
    MONTHLY: "매달",
    PER_WORK_DAY: "근무일마다",
    PER_NIGHT_SHIFT: "야간마다",
    PER_SHIFT_TYPE: `${i.types.join("·") || "정한 근무"}마다`,
    BONUS: i.months.length ? `${i.months.join("·")}월` : "정한 달",
  }[i.cadence];
  const covers = i.covers ? (i.covers.length ? `${i.covers.map((k) => ({ OVERTIME: "연장", NIGHT: "야간", HOLIDAY: "휴일" })[k]).join("·")} 가산 대신` : "가산과 따로") : "";
  const parts = [how, given(i.percentOfBase) ? `기본급 ${pct(i.percentOfBase)}` : "", i.festivals.map((f) => (f === "SEOLLAL" ? "설" : "추석")).join("·"), covers];
  return `${i.name}(${parts.filter(Boolean).join(" ")})`;
}

function diffLines(before, after) {
  const key = ([label, text]) => `${label}\u0001${text}`;
  const was = new Set(before.map(key));
  const now = new Set(after.map(key));
  return { added: after.filter((l) => !was.has(key(l))), removed: before.filter((l) => !now.has(key(l))) };
}

// ---- what everyone can read and send ----

/** What the app tells someone before they send: the points a setup needs and the reward once it is adopted. */
async function terms(env) {
  const { adoptPoints, rewardMonths, adoptAgree } = await settings(env);
  return { threshold: adoptPoints, rewardMonths, agree: adoptAgree };
}

async function search(env, url) {
  const q = normName(url.searchParams.get("q")).slice(0, 40);
  if (q.length < 2) return json({ companies: [] });
  const like = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const rows = await all(env, `
    SELECT c.id, c.name, c.norm,
      (SELECT a.name FROM aliases a WHERE a.company_id = c.id AND a.norm LIKE ?1 ESCAPE '\\' ORDER BY a.norm = ?2 DESC LIMIT 1) AS alias,
      EXISTS (SELECT 1 FROM aliases a WHERE a.company_id = c.id AND a.norm = ?2) AS alias_exact,
      (SELECT COUNT(*) FROM rules r WHERE r.company_id = c.id AND r.withdrawn = 0 AND r.superseded = 0) AS adopted,
      (SELECT COUNT(DISTINCT p.grp) FROM reports p WHERE p.company_id = c.id AND p.status = 'pending') AS pending
    FROM companies c
    WHERE c.hidden = 0 AND c.merged_into IS NULL
      AND (c.norm LIKE ?1 ESCAPE '\\' OR EXISTS (SELECT 1 FROM aliases a WHERE a.company_id = c.id AND a.norm LIKE ?1 ESCAPE '\\'))
    LIMIT 200`, like, q);
  const exact = (c) => (c.norm === q || c.alias_exact === 1 ? 1 : 0);
  const companies = rows
    // One that coworkers are still checking shows up for its exact name, never as a guess.
    .filter((c) => c.adopted > 0 || (exact(c) && c.pending > 0))
    .sort((a, b) => exact(b) - exact(a) || (b.adopted > 0) - (a.adopted > 0) || b.norm.startsWith(q) - a.norm.startsWith(q) ||
      a.name.length - b.name.length || a.name.localeCompare(b.name, "ko"))
    .slice(0, 20)
    .map((c) => ({ id: c.id, name: c.name, alias: c.norm.includes(q) ? null : c.alias, adopted: c.adopted, pending: c.pending }));
  return json({ companies });
}

async function company(env, id) {
  const c = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  if (!c || c.hidden) return json({ error: "not_found" }, 404);
  if (c.merged_into) return json({ mergedInto: c.merged_into });
  const { adoptPoints } = await settings(env);
  const aliases = (await all(env, "SELECT name FROM aliases WHERE company_id = ? ORDER BY name", c.id)).map((a) => a.name);
  const setups = [];
  const rules = await all(env, "SELECT * FROM rules WHERE company_id = ? AND withdrawn = 0 AND superseded = 0 ORDER BY dept_norm", c.id);
  for (const r of rules) {
    setups.push({ ...(await state(env, c.id, r.grp, adoptPoints, true)), dept: r.dept, version: r.version, adoptedAt: iso(r.adopted_at),
      setup: shared(r.setup, c.name), lines: describe(JSON.parse(r.setup)) });
  }
  const groups = await all(env, `SELECT grp, MAX(created_at) AS last FROM reports WHERE company_id = ? AND status = 'pending'
    GROUP BY grp ORDER BY last DESC LIMIT 30`, c.id);
  const pending = [];
  for (const g of groups) {
    if (rules.some((r) => r.grp === g.grp)) continue;
    const s = await state(env, c.id, g.grp, adoptPoints, false);
    if (s.persons === 0) continue;
    const rep = await representative(env, c.id, g.grp);
    // What the developer turned down is not offered to coworkers again.
    const why = await blocker(env, c.id, g.grp, rep.dept_norm);
    if (why === "withdrawn" || why === "rejected") continue;
    pending.push({ s, rep });
  }
  // The likeliest first, and a few for each department are enough.
  pending.sort((a, b) => b.s.points - a.s.points);
  const shown = new Map();
  for (const { s, rep } of pending) {
    const n = shown.get(rep.dept_norm) ?? 0;
    if (n >= 3) continue;
    shown.set(rep.dept_norm, n + 1);
    setups.push({ ...s, dept: rep.dept, setup: shared(rep.setup, c.name), lines: describe(JSON.parse(rep.setup)) });
  }
  return json({ id: c.id, name: c.name, aliases, setups });
}

const shared = (text, name) => ({ ...JSON.parse(text), company: name });

async function state(env, companyId, grp, threshold, adopted) {
  const s = await score(env, companyId, grp);
  const posts = await one(env, "SELECT COUNT(*) AS n FROM posts WHERE company_id = ? AND grp = ? AND status = 'shown'", companyId, grp);
  return { id: grp, status: adopted ? "adopted" : "pending", points: s.points, persons: s.persons, bad: s.bad, threshold,
    ...trust(s, threshold), posts: posts.n };
}

/**
 * [COMM-07] How far people trust a rule set, 0 to 100: the share who said 맞았어요 among all who voted,
 * times how much of the adoption points it has. [agree] is that share alone, as a percent.
 */
export function trust(s, threshold) {
  const voters = s.persons + s.bad;
  const agree = voters ? s.persons / voters : 0;
  return { trust: Math.round(100 * agree * Math.min(1, s.points / threshold)), agree: Math.round(100 * agree) };
}

async function report(request, env) {
  const body = await readJson(request, LIMITS.reportBytes);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  const r = readReport(body);
  const now = Date.now();
  const net = await network(request, env, now);
  const dev = await deviceHash(device);
  if (!(await allow(env, `report|${dev}`, LIMITS.reportsPerDay, DAY, now)) ||
      !(await allow(env, `report|${net.day}`, LIMITS.reportsPerNetworkDay, DAY, now))) {
    return json({ error: "rate_limited" }, 429);
  }
  const c = await companyFor(env, r.company, now);
  const grp = (await sha256(`${r.deptNorm}|${await fingerprint(r.setup)}`)).slice(0, 16);
  await forgetNetworks(env, now);
  if (r.ask) return askDeveloper(env, c, grp, r, dev, net, now);
  // The same install sending the same setup again gets its earlier report back.
  const again = await one(env, "SELECT id FROM reports WHERE company_id = ? AND grp = ? AND device = ? AND status IN ('pending', 'adopted')", c.id, grp, dev);
  if (again) return json({ ...(await reportView(env, again.id)), again: true });
  const cluster = await clusterFor(env, c.id, net);
  const active = await activeRule(env, c.id, r.deptNorm);
  const adopted = active?.grp === grp;
  const id = randomId(22);
  await env.DB.batch([
    // A newer setup from the same sender for the same place takes the place of the older one.
    stmt(env, "UPDATE reports SET status = 'replaced', decided_at = ? WHERE company_id = ? AND dept_norm = ? AND device = ? AND status = 'pending'",
      now, c.id, r.deptNorm, dev),
    stmt(env, `INSERT INTO reports (id, company_id, dept, dept_norm, grp, setup, verified, note, device, net, cluster, points, status,
      rule_id, reason, created_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`,
      id, c.id, r.dept, r.deptNorm, grp, JSON.stringify(r.setup), r.verified ? 1 : 0, r.note, dev, net.now, cluster, r.verified ? 2 : 1,
      adopted ? "adopted" : "pending", adopted ? active.id : null, now, adopted ? now : null),
  ]);
  if (!adopted) await settle(env, c.id, grp, now);
  return json(await reportView(env, id), 201);
}

/**
 * [EXTRA-06] A way of paying the app cannot set yet. Only the developer reads it: it never counts for a
 * setup or waits to be adopted, since the setup in it is only as close as the sender could get.
 */
async function askDeveloper(env, c, grp, r, dev, net, now) {
  const again = await one(env, "SELECT id FROM reports WHERE company_id = ? AND grp = ? AND device = ? AND status = 'ask' AND note = ?",
    c.id, grp, dev, r.note);
  if (again) return json({ ...(await reportView(env, again.id)), again: true });
  const id = randomId(22);
  await run(env, `INSERT INTO reports (id, company_id, dept, dept_norm, grp, setup, verified, note, device, net, cluster, points, status,
    rule_id, reason, created_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ask', NULL, NULL, ?, NULL)`,
  id, c.id, r.dept, r.deptNorm, grp, JSON.stringify(r.setup), r.verified ? 1 : 0, r.note, dev, net.now, await clusterFor(env, c.id, net), now);
  return json(await reportView(env, id), 201);
}

async function companyFor(env, name, now) {
  const norm = normName(name);
  const found = (await one(env, "SELECT id FROM companies WHERE norm = ? ORDER BY merged_into IS NOT NULL, created_at LIMIT 1", norm))
    ?? (await one(env, "SELECT company_id AS id FROM aliases WHERE norm = ?", norm));
  if (found) return follow(env, found.id);
  const id = randomId(12);
  await run(env, "INSERT INTO companies (id, name, norm, hidden, merged_into, created_at) VALUES (?, ?, ?, 0, NULL, ?)", id, name, norm, now);
  return one(env, "SELECT * FROM companies WHERE id = ?", id);
}

/** A company, or the one it was merged into. */
async function follow(env, id) {
  let c = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  for (let i = 0; c?.merged_into && i < 8; i++) c = await one(env, "SELECT * FROM companies WHERE id = ?", c.merged_into);
  return c;
}

/** Network hashes only ever link sends within the hour; two hours on they are gone. */
function forgetNetworks(env, now) {
  return env.DB.batch([
    stmt(env, "UPDATE reports SET net = NULL WHERE net IS NOT NULL AND created_at < ?", now - 2 * HOUR),
    stmt(env, "UPDATE votes SET net = NULL WHERE net IS NOT NULL AND at < ?", now - 2 * HOUR),
    stmt(env, "DELETE FROM counters WHERE expires < ?", now),
  ]);
}

/**
 * The mark shared by sends to this company from one network within the hour: a random id, so the
 * network itself is not kept. Coworkers on the company Wi-Fi still count as people of their own, but
 * a setup only they agree on is not adopted without the developer, nor is one phone reinstalled.
 */
async function clusterFor(env, companyId, net) {
  const row = await one(env, `SELECT cluster FROM reports WHERE company_id = ?1 AND net IN (?2, ?3)
    UNION ALL SELECT cluster FROM votes WHERE company_id = ?1 AND net IN (?2, ?3) LIMIT 1`, companyId, net.now, net.prev);
  return row?.cluster ?? randomId(12);
}

/**
 * Points of one setup: each install once, at 2 when they checked it against a pay slip, and the
 * networks they sent from. Someone who says 달라요 does not count for it.
 */
async function score(env, companyId, grp) {
  const rows = await all(env, `
    SELECT device, points, cluster FROM reports WHERE company_id = ?1 AND grp = ?2 AND status IN ('pending', 'adopted')
    UNION ALL
    SELECT device, CASE WHEN verified = 1 THEN 2 ELSE 1 END, cluster FROM votes WHERE company_id = ?1 AND grp = ?2 AND ok = 1`, companyId, grp);
  const doubters = new Set((await all(env, "SELECT device FROM votes WHERE company_id = ? AND grp = ? AND ok = 0", companyId, grp))
    .map((v) => v.device));
  const people = new Map();
  const networks = new Set();
  for (const r of rows) {
    if (doubters.has(r.device)) continue;
    people.set(r.device, Math.max(people.get(r.device) ?? 0, r.points));
    networks.add(r.cluster);
  }
  return { points: [...people.values()].reduce((sum, p) => sum + p, 0), persons: people.size, networks: networks.size, bad: doubters.size };
}

/** The report a setup is shown and adopted from: one checked against a pay slip, else the newest. */
const representative = (env, companyId, grp) => one(env, `SELECT * FROM reports WHERE company_id = ? AND grp = ?
  AND status IN ('pending', 'adopted') ORDER BY verified DESC, created_at DESC LIMIT 1`, companyId, grp);

const activeRule = (env, companyId, deptNorm) => one(env, `SELECT * FROM rules WHERE company_id = ? AND dept_norm = ?
  AND withdrawn = 0 AND superseded = 0 ORDER BY version DESC LIMIT 1`, companyId, deptNorm);

/** [COMM-03] Adopts a setup once different people agree on it, unless it is the developer's call. */
async function settle(env, companyId, grp, now) {
  const { adoptPoints, adoptAgree } = await settings(env);
  const s = await score(env, companyId, grp);
  if (s.persons < 2 || s.points < adoptPoints || s.networks < 2) return;
  // [COMM-07] Too many 달라요 for the 맞았어요: the developer decides, however many points it has.
  if (s.persons * 100 < adoptAgree * (s.persons + s.bad)) return;
  const rep = await representative(env, companyId, grp);
  if (!rep || (await blocker(env, companyId, grp, rep.dept_norm))) return;
  await adopt(env, companyId, grp, "auto", now);
}

/**
 * Why a setup waits for the developer however many agree: another rule is in use for its department
 * ("active"), or the developer took this setup down ("withdrawn") or turned it down ("rejected").
 */
async function blocker(env, companyId, grp, deptNorm) {
  const row = await one(env, `SELECT
      EXISTS (SELECT 1 FROM rules WHERE company_id = ?1 AND dept_norm = ?3 AND withdrawn = 0 AND superseded = 0) AS active,
      EXISTS (SELECT 1 FROM rules WHERE company_id = ?1 AND grp = ?2 AND withdrawn = 1) AS withdrawn,
      EXISTS (SELECT 1 FROM reports WHERE company_id = ?1 AND grp = ?2 AND status = 'rejected') AS rejected`, companyId, grp, deptNorm);
  return row.active ? "active" : row.withdrawn ? "withdrawn" : row.rejected ? "rejected" : null;
}

/** Makes a setup the company's rule for its department; each install that sent it earns the reward. */
async function adopt(env, companyId, grp, source, now) {
  const rep = await representative(env, companyId, grp);
  if (!rep) return null;
  const { rewardMonths } = await settings(env);
  const last = await one(env, "SELECT MAX(version) AS v FROM rules WHERE company_id = ? AND dept_norm = ?", companyId, rep.dept_norm);
  const ruleId = randomId(16);
  const stmts = [
    stmt(env, "UPDATE rules SET superseded = 1 WHERE company_id = ? AND dept_norm = ? AND superseded = 0", companyId, rep.dept_norm),
    stmt(env, `INSERT INTO rules (id, company_id, dept, dept_norm, grp, version, setup, source, adopted_at, withdrawn, superseded)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)`, ruleId, companyId, rep.dept, rep.dept_norm, grp, (last?.v ?? 0) + 1, rep.setup, source, now),
    stmt(env, "UPDATE reports SET status = 'adopted', rule_id = ?, decided_at = ? WHERE company_id = ? AND grp = ? AND status = 'pending'",
      ruleId, now, companyId, grp),
  ];
  if (rewardMonths > 0) {
    stmts.push(stmt(env, `
      INSERT OR IGNORE INTO rewards (id, report_id, rule_id, device, months, status, memo, created_at, paid_at)
      SELECT 'w' || id, id, rule_id, device, ?, 'owed', '', ?, NULL FROM reports WHERE company_id = ? AND grp = ? AND rule_id = ?`,
    rewardMonths, now, companyId, grp, ruleId));
  }
  await env.DB.batch(stmts);
  return ruleId;
}

async function reportView(env, id) {
  const r = await one(env, "SELECT * FROM reports WHERE id = ?", id);
  if (!r) return null;
  const c = await one(env, "SELECT id, name FROM companies WHERE id = ?", r.company_id);
  const { adoptPoints, rewardMonths } = await settings(env);
  const s = await score(env, r.company_id, r.grp);
  const rule = r.rule_id ? await one(env, "SELECT withdrawn FROM rules WHERE id = ?", r.rule_id) : null;
  const reward = await one(env, "SELECT months, status FROM rewards WHERE report_id = ?", r.id);
  const ask = r.status === "ask" || r.status === "answered";
  return {
    id: r.id,
    status: r.status === "adopted" && rule?.withdrawn === 1 ? "withdrawn" : r.status,
    ask,
    company: { id: c.id, name: c.name },
    dept: r.dept,
    setup: r.grp,
    verified: r.verified === 1,
    points: ask ? 0 : s.points,
    persons: ask ? 0 : s.persons,
    threshold: adoptPoints,
    // Enough coworkers agree, and the developer is checking it.
    review: r.status === "pending" && s.persons >= 2 && s.points >= adoptPoints,
    createdAt: iso(r.created_at),
    decidedAt: r.decided_at ? iso(r.decided_at) : null,
    reason: r.reason ? { code: r.reason, text: (ask ? ANSWERS : REASONS)[r.reason] ?? "" } : null,
    reward: reward ? { months: reward.months, status: reward.status } : null,
    rewardMonths,
  };
}

async function reportStatus(env, id) {
  const view = await reportView(env, id);
  return view ? json(view) : json({ error: "not_found" }, 404);
}

async function takeBack(request, env, id) {
  const device = deviceId(request.headers.get("x-device"));
  const r = await one(env, "SELECT device FROM reports WHERE id = ?", id);
  if (!r) return json({ error: "not_found" }, 404);
  if (!device || (await deviceHash(device)) !== r.device) return json({ error: "forbidden" }, 403);
  await env.DB.batch([stmt(env, "DELETE FROM rewards WHERE report_id = ?", id), stmt(env, "DELETE FROM reports WHERE id = ?", id)]);
  return new Response(null, { status: 204 });
}

/** Everything one install sent: reports, rewards not yet given and its 맞았어요·달라요. */
async function forgetMine(request, env) {
  const device = deviceId(request.headers.get("x-device"));
  if (!device) return json({ error: "device", field: "device" }, 400);
  const dev = await deviceHash(device);
  const [, reports, votes, , posts, , , , asked] = await env.DB.batch([
    stmt(env, "DELETE FROM rewards WHERE device = ?", dev),
    stmt(env, "DELETE FROM reports WHERE device = ?", dev),
    stmt(env, "DELETE FROM votes WHERE device = ?", dev),
    stmt(env, "DELETE FROM counters WHERE key IN (?, ?)", `report|${dev}`, `post|${dev}`),
    // [COMM-06] Their posts and their 신고 go too; a post they flagged counts one fewer.
    stmt(env, "DELETE FROM posts WHERE device = ?", dev),
    stmt(env, "UPDATE posts SET flags = flags - 1 WHERE id IN (SELECT post_id FROM post_flags WHERE device = ?)", dev),
    stmt(env, "DELETE FROM post_flags WHERE device = ? OR post_id NOT IN (SELECT id FROM posts)", dev),
    stmt(env, "DELETE FROM inquiry_messages WHERE inquiry_id IN (SELECT id FROM inquiries WHERE device = ?)", dev),
    stmt(env, "DELETE FROM inquiries WHERE device = ?", dev),
  ]);
  return json({ reports: reports.meta.changes, votes: votes.meta.changes, posts: posts.meta.changes, inquiries: asked.meta.changes });
}

async function vote(request, env) {
  const body = await readJson(request, 2048);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (typeof body.company !== "string" || !/^[A-Za-z0-9]{12}$/.test(body.company)) refuse("company", "company");
  if (typeof body.setup !== "string" || !/^[0-9a-f]{16}$/.test(body.setup)) refuse("setup", "setup");
  if (typeof body.ok !== "boolean") refuse("ok", "ok");
  const why = body.ok ? null : ["wrong", "name", "old"].includes(body.why) ? body.why : "wrong";
  const verified = body.ok && body.verified === true;
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const c = await follow(env, body.company);
  if (!c || c.hidden) return json({ error: "not_found" }, 404);
  const grp = body.setup;
  const rule = await one(env, "SELECT id FROM rules WHERE company_id = ? AND grp = ? AND withdrawn = 0 AND superseded = 0", c.id, grp);
  const pending = rule ? null : await one(env, "SELECT id FROM reports WHERE company_id = ? AND grp = ? AND status = 'pending' LIMIT 1", c.id, grp);
  if (!rule && !pending) return json({ error: "not_found" }, 404);
  const dev = await deviceHash(device);
  await forgetNetworks(env, now);
  const cluster = await clusterFor(env, c.id, net);
  await run(env, `INSERT INTO votes (company_id, grp, device, cluster, ok, verified, why, net, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (company_id, grp, device) DO UPDATE SET cluster = excluded.cluster, ok = excluded.ok, verified = excluded.verified,
      why = excluded.why, net = excluded.net, at = excluded.at`, c.id, grp, dev, cluster, body.ok ? 1 : 0, verified ? 1 : 0, why, net.now, now);
  if (body.ok && !rule) await settle(env, c.id, grp, now);
  const adopted = rule ?? (await one(env, "SELECT id FROM rules WHERE company_id = ? AND grp = ? AND withdrawn = 0 AND superseded = 0", c.id, grp));
  return json(await state(env, c.id, grp, (await settings(env)).adoptPoints, Boolean(adopted)));
}

// ---- rule sets everyone can see, vote on and talk about [COMM-06][COMM-07] ----

/** Why a post is flagged, as the app offers it. */
const FLAGS = {
  abuse: "욕설·비방",
  personal: "개인 정보가 있어요",
  spam: "광고·도배",
  false: "일부러 틀린 정보",
};

/** Why the developer hid a post, as its writer's app shows it. */
const HIDE_REASONS = {
  abuse: "욕설이나 비방이 있어요",
  personal: "개인 정보가 있어요",
  spam: "광고나 도배예요",
  offtopic: "이 규칙과 상관없는 글이에요",
  flags: "신고가 모여 개발자가 확인하고 있어요",
};

/**
 * [COMM-07] Every company's rule sets in one list: ones people are still voting on (newest activity
 * first), or adopted ones anyone can use (newest first). What the developer turned down or took down,
 * and hidden companies, are not here.
 */
async function board(env, url) {
  const tab = url.searchParams.get("tab") === "adopted" ? "adopted" : "voting";
  const offset = Math.min(600, Math.max(0, Number.parseInt(url.searchParams.get("from") ?? "0", 10) || 0));
  const size = 20;
  const { adoptPoints } = await settings(env);
  // A company's name or another name for it, written any way 회사 찾기 accepts; one letter finds nothing.
  const q = normName(url.searchParams.get("q")).slice(0, 40);
  const like = q.length < 2 ? "" : `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const named = `(?3 = '' OR c.norm LIKE ?3 ESCAPE '\\' OR EXISTS (SELECT 1 FROM aliases a WHERE a.company_id = c.id AND a.norm LIKE ?3 ESCAPE '\\'))`;
  const rows = tab === "adopted"
    ? await all(env, `SELECT r.company_id, r.grp, r.dept, r.setup, r.version, r.adopted_at AS at, c.name
        FROM rules r JOIN companies c ON c.id = r.company_id
        WHERE r.withdrawn = 0 AND r.superseded = 0 AND c.hidden = 0 AND c.merged_into IS NULL AND ${named}
        ORDER BY r.adopted_at DESC LIMIT ?1 OFFSET ?2`, size + 1, offset, like)
    : await all(env, `SELECT p.company_id, p.grp, c.name,
          MAX(MAX(p.created_at),
            COALESCE((SELECT MAX(v.at) FROM votes v WHERE v.company_id = p.company_id AND v.grp = p.grp), 0),
            COALESCE((SELECT MAX(t.created_at) FROM posts t WHERE t.company_id = p.company_id AND t.grp = p.grp AND t.status = 'shown'), 0)) AS at
        FROM reports p JOIN companies c ON c.id = p.company_id
        WHERE p.status = 'pending' AND c.hidden = 0 AND c.merged_into IS NULL AND ${named}
          AND NOT EXISTS (SELECT 1 FROM reports x WHERE x.company_id = p.company_id AND x.grp = p.grp AND x.status = 'rejected')
          AND NOT EXISTS (SELECT 1 FROM rules w WHERE w.company_id = p.company_id AND w.grp = p.grp AND (w.withdrawn = 1 OR w.superseded = 0))
        GROUP BY p.company_id, p.grp ORDER BY at DESC LIMIT ?1 OFFSET ?2`, size + 1, offset, like);
  const items = [];
  for (const r of rows.slice(0, size)) {
    const rep = tab === "adopted" ? r : await representative(env, r.company_id, r.grp);
    if (!rep) continue;
    const s = await state(env, r.company_id, r.grp, adoptPoints, tab === "adopted");
    if (s.persons === 0 && tab === "voting") continue;
    const lines = describe(JSON.parse(rep.setup));
    items.push({
      company: { id: r.company_id, name: r.name },
      dept: rep.dept,
      ...s,
      version: r.version ?? null,
      at: iso(r.at),
      gist: lines.find(([label]) => label === "근무")?.[1] ?? "",
      cycle: lines.find(([label]) => label.startsWith("순서"))?.[0] ?? "",
      pay: lines.some(([label]) => label === "사업장"),
    });
  }
  return { tab, ...(like ? { q } : {}), items, next: rows.length > size ? offset + size : null, threshold: adoptPoints };
}

/** A rule set people may talk about: adopted and in use, or still gathering votes. */
async function talkable(env, companyId, grp) {
  const c = await follow(env, companyId);
  if (!c || c.hidden) return null;
  const live = await one(env, `SELECT
      EXISTS (SELECT 1 FROM rules WHERE company_id = ?1 AND grp = ?2 AND withdrawn = 0 AND superseded = 0) AS rule,
      EXISTS (SELECT 1 FROM reports WHERE company_id = ?1 AND grp = ?2 AND status = 'pending') AS pending`, c.id, grp);
  return live.rule || live.pending ? c : null;
}

/**
 * [COMM-06] The talk on one rule set, oldest first. Each writer is 참여자 N of this talk only, with what
 * they said about the rule set: 올린 분 (they sent it), 맞았어요 or 달라요. With X-Device, the caller's own
 * posts are marked, and their own hidden ones show with why.
 */
async function talk(request, env, companyId, grp) {
  const c = await talkable(env, companyId, grp);
  if (!c) return json({ error: "not_found" }, 404);
  const device = deviceId(request.headers.get("x-device"));
  const me = device ? await deviceHash(device) : null;
  const rows = await all(env, "SELECT * FROM posts WHERE company_id = ? AND grp = ? ORDER BY created_at, id LIMIT 500", c.id, grp);
  const senders = new Set((await all(env, `SELECT device FROM reports WHERE company_id = ? AND grp = ? AND status IN ('pending', 'adopted')`,
    c.id, grp)).map((r) => r.device));
  const votes = new Map((await all(env, "SELECT device, ok, verified FROM votes WHERE company_id = ? AND grp = ?", c.id, grp))
    .map((v) => [v.device, v]));
  const verified = new Set((await all(env, "SELECT device FROM reports WHERE company_id = ? AND grp = ? AND verified = 1", c.id, grp))
    .map((r) => r.device));
  const numbers = new Map();
  const posts = [];
  for (const p of rows) {
    if (!numbers.has(p.device)) numbers.set(p.device, numbers.size + 1);
    const mine = p.device === me;
    if (p.status !== "shown" && !mine) continue;
    const v = votes.get(p.device);
    posts.push({
      id: p.id,
      who: numbers.get(p.device),
      stance: senders.has(p.device) ? "sender" : v ? (v.ok ? "ok" : "bad") : null,
      verified: verified.has(p.device) || v?.verified === 1,
      body: p.body,
      at: iso(p.created_at),
      mine,
      ...(p.status === "shown" ? {} : { hidden: { code: p.hide_reason ?? "flags", text: HIDE_REASONS[p.hide_reason ?? "flags"] ?? "" } }),
    });
  }
  const muted = me ? await one(env, "SELECT until FROM muted WHERE device = ? AND until > ?", me, Date.now()) : null;
  return json({ company: { id: c.id, name: c.name }, setup: grp, posts, ...(muted ? { mutedUntil: iso(muted.until) } : {}) });
}

/** [COMM-06] A post in the talk on a rule set: no email, phone number or amount, like everything else sent here. */
async function post(request, env) {
  const body = await readJson(request, 4096);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (typeof body.company !== "string" || !/^[A-Za-z0-9]{12}$/.test(body.company)) refuse("company", "company");
  if (typeof body.setup !== "string" || !/^[0-9a-f]{16}$/.test(body.setup)) refuse("setup", "setup");
  const text = str(body.body, LIMITS.post, "body", { empty: false, lines: true, code: "body" }).trim();
  if (text.length < 2) refuse("body", "body");
  const why = personal(text);
  if (why) refuse(why, "body");
  const now = Date.now();
  const dev = await deviceHash(device);
  const muted = await one(env, "SELECT until FROM muted WHERE device = ? AND until > ?", dev, now);
  if (muted) return json({ error: "muted", until: iso(muted.until) }, 403);
  const c = await talkable(env, body.company, body.setup);
  if (!c) return json({ error: "not_found" }, 404);
  const net = await network(request, env, now);
  if (!(await allow(env, `post|${dev}`, LIMITS.postsPerDay, DAY, now)) ||
      !(await allow(env, `post|${net.day}`, LIMITS.postsPerNetworkDay, DAY, now))) {
    return json({ error: "rate_limited" }, 429);
  }
  const id = `p${randomId(15)}`;
  await run(env, `INSERT INTO posts (id, company_id, grp, device, body, status, flags, kept, hide_reason, created_at, hidden_at)
    VALUES (?, ?, ?, ?, ?, 'shown', 0, 0, NULL, ?, NULL)`, id, c.id, body.setup, dev, text, now);
  return json({ id, at: iso(now) }, 201);
}

async function takePostBack(request, env, id) {
  const device = deviceId(request.headers.get("x-device"));
  const p = await one(env, "SELECT device FROM posts WHERE id = ?", id);
  if (!p) return json({ error: "not_found" }, 404);
  if (!device || (await deviceHash(device)) !== p.device) return json({ error: "forbidden" }, 403);
  await env.DB.batch([stmt(env, "DELETE FROM post_flags WHERE post_id = ?", id), stmt(env, "DELETE FROM posts WHERE id = ?", id)]);
  return new Response(null, { status: 204 });
}

/**
 * [COMM-06] 신고. Each install once per post; enough of them hide it until the developer looks, unless
 * the developer already looked and kept it.
 */
async function flag(request, env, id) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  const reason = Object.hasOwn(FLAGS, body.reason) ? body.reason : "abuse";
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const dev = await deviceHash(device);
  const p = await one(env, "SELECT * FROM posts WHERE id = ?", id);
  if (!p || p.status !== "shown") return json({ error: "not_found" }, 404);
  if (p.device === dev) return json({ error: "own" }, 400);
  const added = await run(env, "INSERT OR IGNORE INTO post_flags (post_id, device, reason, at) VALUES (?, ?, ?, ?)", id, dev, reason, now);
  if (added.meta.changes) {
    await run(env, `UPDATE posts SET flags = flags + 1,
        status = CASE WHEN kept = 0 AND flags + 1 >= ?2 THEN 'hidden' ELSE status END,
        hide_reason = CASE WHEN kept = 0 AND flags + 1 >= ?2 THEN 'flags' ELSE hide_reason END,
        hidden_at = CASE WHEN kept = 0 AND flags + 1 >= ?2 THEN ?3 ELSE hidden_at END
      WHERE id = ?1`, id, LIMITS.flagsToHide, now);
    const after = await one(env, "SELECT status FROM posts WHERE id = ?", id);
    if (after.status === "hidden") await audit(env, now, "post_autohide", id, `${p.company_id}/${p.grp}`);
  }
  return json({ ok: true });
}

// ---- questions from the app [ADMIN-02] ----
//
// 설정 > 문의하기 sends here instead of opening a mail app, and the developer answers in /admin; the
// answer shows in the app. Like a report it carries no amount, contact or name: those are refused, not
// cleaned. What goes with it (app version, phone model, Android, the alarm check if the user ticks it)
// is shown in the app before sending. Only the install's hash is kept, so only that phone sees the
// thread, and a thread with nothing new for 180 days is deleted.

const INQUIRY = {
  kinds: ["question", "bug", "idea"],
  text: 2000,
  diag: 6000,
  bytes: 16 * 1024,
  perDay: 5,
  perNetworkDay: 20,
  messagesPerHour: 20,
  // Each side of a thread; a longer back and forth is better as a new question.
  messages: 40,
  keepMs: 180 * DAY,
};

/** What the app may send with a question, as the developer app shows it: label, field, longest. */
const INQUIRY_INFO = [
  ["앱", "app", 40],
  ["기기", "model", 80],
  ["Android", "android", 40],
  ["알람 점검", "diag", INQUIRY.diag],
];

async function inquiries(request, env, path) {
  const method = request.method;
  const device = deviceId(request.headers.get("x-device"));
  if (path === "/api/inquiries" && method === "POST") return ask(request, env);
  if (!device) return json({ error: "device", field: "device" }, 400);
  const dev = await deviceHash(device);
  if (path === "/api/inquiries" && method === "GET") {
    const rows = await all(env, "SELECT * FROM inquiries WHERE device = ? ORDER BY updated_at DESC LIMIT 50", dev);
    const out = [];
    for (const r of rows) out.push(await inquiryView(env, r));
    return json({ inquiries: out });
  }
  const m = path.match(/^\/api\/inquiries\/([A-Za-z0-9]{22})(\/messages)?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ?", m[1]);
  // Someone else's question reads as missing, so ids cannot be tried out.
  if (!row || row.device !== dev) return json({ error: "not_found" }, 404);
  if (!m[2] && method === "GET") return json(await inquiryView(env, row));
  if (!m[2] && method === "DELETE") {
    await env.DB.batch([
      stmt(env, "DELETE FROM inquiry_messages WHERE inquiry_id = ?", row.id),
      stmt(env, "DELETE FROM inquiries WHERE id = ?", row.id),
    ]);
    return new Response(null, { status: 204 });
  }
  if (m[2] && method === "POST") {
    const body = await readJson(request, INQUIRY.bytes);
    const text = inquiryText(body.text);
    const now = Date.now();
    if (!(await allow(env, `inquiry_msg|${dev}`, INQUIRY.messagesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
    const count = await one(env, "SELECT COUNT(*) AS n FROM inquiry_messages WHERE inquiry_id = ? AND sender = 'user'", row.id);
    if (count.n >= INQUIRY.messages) return json({ error: "too_many", field: "text" }, 409);
    await env.DB.batch([
      stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'user', ?, NULL, ?)", row.id, text, now),
      stmt(env, "UPDATE inquiries SET status = 'open', updated_at = ? WHERE id = ?", now, row.id),
    ]);
    return json(await inquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", row.id)), 201);
  }
  return json({ error: "not_found" }, 404);
}

/** A new question: what the user typed, and what they agreed to send with it. */
async function ask(request, env) {
  const body = await readJson(request, INQUIRY.bytes);
  if (body.v !== 1) refuse("version", "v");
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (!INQUIRY.kinds.includes(body.kind)) refuse("kind", "kind");
  const text = inquiryText(body.text);
  const info = inquiryInfo(body.info);
  const now = Date.now();
  const net = await network(request, env, now);
  const dev = await deviceHash(device);
  if (!(await allow(env, `inquiry|${dev}`, INQUIRY.perDay, DAY, now)) ||
      !(await allow(env, `inquiry|${net.day}`, INQUIRY.perNetworkDay, DAY, now))) {
    return json({ error: "rate_limited" }, 429);
  }
  const id = randomId(22);
  await env.DB.batch([
    stmt(env, "INSERT INTO inquiries (id, device, kind, info, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'open', ?, ?)",
      id, dev, body.kind, JSON.stringify(info), now, now),
    stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'user', ?, NULL, ?)", id, text, now),
    // Threads with nothing new for half a year go, answers and all.
    stmt(env, "DELETE FROM inquiry_messages WHERE inquiry_id IN (SELECT id FROM inquiries WHERE updated_at < ?)", now - INQUIRY.keepMs),
    stmt(env, "DELETE FROM inquiries WHERE updated_at < ?", now - INQUIRY.keepMs),
    stmt(env, "DELETE FROM counters WHERE expires < ?", now),
  ]);
  return json(await inquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", id)), 201);
}

/** What the user typed: up to 2000 characters, without an email, phone number or amount. */
function inquiryText(v) {
  if (typeof v !== "string") refuse("text", "text");
  const text = v.normalize("NFC").replace(/\r\n?/g, "\n").trim();
  if (!text || [...text].length > INQUIRY.text) refuse("text", "text");
  const why = personal(text);
  if (why) refuse(why, "text");
  return text;
}

/**
 * What goes with a question, only the fields the app shows before sending. The alarm check is made by
 * the app, not typed: its times and shift names are what it is for, so only its length is checked.
 */
function inquiryInfo(v) {
  if (v === undefined || v === null) return {};
  if (typeof v !== "object" || Array.isArray(v)) refuse("info", "info");
  const out = {};
  for (const [, key, longest] of INQUIRY_INFO) {
    const x = v[key];
    if (x === undefined || x === null || x === "") continue;
    if (typeof x !== "string" || [...x].length > longest) refuse("info", key);
    if (key !== "diag" && personal(x) === "email") refuse("info", key);
    out[key] = x.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, "");
  }
  return out;
}

const messagesOf = (env, id) => all(env, "SELECT sender, text, by, at FROM inquiry_messages WHERE inquiry_id = ? ORDER BY id", id);

/** A question as the app sees it; [admin] adds what was sent with it and who answered. */
async function inquiryView(env, row, admin = false) {
  const messages = await messagesOf(env, row.id);
  const view = {
    id: row.id,
    kind: row.kind,
    status: row.status,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
    messages: messages.map((m) => ({ from: m.sender, text: m.text, at: iso(m.at), ...(admin && m.by ? { by: m.by } : {}) })),
  };
  if (!admin) return view;
  const info = JSON.parse(row.info);
  const context = {};
  for (const [label, key] of INQUIRY_INFO) if (info[key]) context[label] = info[key];
  return { ...view, appVersion: info.app ?? null, context };
}

/** Who answered: the signed-in admin's name once there are admin accounts, else "admin". */
function actorName(env) {
  const a = env.actor;
  const name = typeof a === "string" ? a : a && typeof a === "object" ? a.name : null;
  return typeof name === "string" && name ? name.slice(0, 40) : "admin";
}

/** /api/admin/inquiries: the list, one thread, an answer and open or closed. */
async function adminInquiries(request, env, url, path, now) {
  const method = request.method;
  if (path === "/inquiries" && method === "GET") return json(await inquiryList(env, url.searchParams));
  const m = path.match(/^\/inquiries\/([A-Za-z0-9]{22})(\/reply|\/status)?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ?", m[1]);
  if (!row) return json({ error: "not_found" }, 404);
  if (!m[2] && method === "GET") return json(await inquiryView(env, row, true));
  if (method !== "POST" || !m[2]) return json({ error: "not_found" }, 404);
  const body = await readJson(request, INQUIRY.bytes);
  if (m[2] === "/reply") {
    if (typeof body.text !== "string" || !body.text.trim() || [...body.text.trim()].length > INQUIRY.text) refuse("text", "text");
    const status = body.close === true ? "closed" : "answered";
    await env.DB.batch([
      stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'admin', ?, ?, ?)",
        row.id, body.text.normalize("NFC").replace(/\r\n?/g, "\n").trim(), actorName(env), now),
      stmt(env, "UPDATE inquiries SET status = ?, updated_at = ? WHERE id = ?", status, now, row.id),
    ]);
    await audit(env, now, "inquiry_reply", row.id, status);
  } else {
    if (!["open", "closed"].includes(body.status)) refuse("status", "status");
    // Opening or closing is the developer's bookkeeping; it does not keep a thread from its 180 days.
    await run(env, "UPDATE inquiries SET status = ? WHERE id = ?", body.status, row.id);
    await audit(env, now, "inquiry_status", row.id, body.status);
  }
  return json(await inquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", row.id), true));
}

/** Questions waiting first, then the newest; [q] finds words in what was asked or answered. */
async function inquiryList(env, params) {
  const status = ["open", "answered", "closed"].includes(params.get("status")) ? params.get("status") : null;
  const q = String(params.get("q") ?? "").trim().slice(0, 40);
  const like = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const rows = await all(env, `SELECT i.*,
      (SELECT COUNT(*) FROM inquiry_messages m WHERE m.inquiry_id = i.id) AS count,
      (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id LIMIT 1) AS first,
      (SELECT m.sender FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id DESC LIMIT 1) AS last
    FROM inquiries i
    WHERE (?1 IS NULL OR i.status = ?1)
      AND (?2 = '' OR EXISTS (SELECT 1 FROM inquiry_messages m WHERE m.inquiry_id = i.id AND m.text LIKE ?3 ESCAPE '\\'))
    ORDER BY i.status = 'open' DESC, i.updated_at DESC LIMIT 200`, status, q, like);
  const counts = { open: 0, answered: 0, closed: 0 };
  for (const r of await all(env, "SELECT status, COUNT(*) AS n FROM inquiries GROUP BY status")) {
    if (Object.hasOwn(counts, r.status)) counts[r.status] = r.n;
  }
  return {
    counts,
    inquiries: rows.map((r) => ({
      id: r.id,
      kind: r.kind,
      status: r.status,
      preview: [...(r.first ?? "")].slice(0, 120).join(""),
      count: r.count,
      waiting: r.last === "user",
      appVersion: JSON.parse(r.info).app ?? null,
      createdAt: iso(r.created_at),
      updatedAt: iso(r.updated_at),
    })),
  };
}

// ---- the developer app's API [ADMIN-01] ----

const COOKIE = "__Host-bamnat_admin";
const cookie = (value, maxAge) => `${COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${maxAge}`;

/** What is still missing on a new server, shown on the login screen, with why; never a name or value that was set. */
function health(env) {
  const why = { password: secretIssue(env, "ADMIN_PASSWORD", 10), salt: secretIssue(env, "RATE_SALT", 1) };
  return { database: Boolean(env.DB), password: why.password === null, salt: why.salt === null, mail: mailReady(env), why };
}

/**
 * Why a secret is not usable: "short", "store" (a Secrets Store binding, not a runtime secret), "name"
 * (set under a name that differs only in case or spaces) or "missing"; null when it is fine.
 */
function secretIssue(env, name, least) {
  const v = env[name];
  if (typeof v === "string") return v.length >= least ? null : "short";
  if (v && typeof v === "object" && typeof v.get === "function") return "store";
  return Object.keys(env).some((k) => k !== name && k.replace(/\s+/g, "").toUpperCase() === name) ? "name" : "missing";
}

/**
 * [ADMIN-04] Which area a developer-app call belongs to: null for what every signed-in admin may do,
 * "owner" for managing admins and anything not listed, so a new route stays the owner's until it is.
 */
function areaFor(path) {
  if (path === "/overview" || path === "/logout" || path.startsWith("/me")) return null;
  if (path === "/queue" || path.startsWith("/groups/") || path.startsWith("/asks/")) return "review";
  if (path.startsWith("/inquiries")) return "inquiries";
  if (path.startsWith("/companies") || path.startsWith("/rules/")) return "companies";
  if (path.startsWith("/rewards")) return "rewards";
  if (path === "/settings") return "settings";
  if (path.startsWith("/posts")) return "community";
  if (path === "/audit") return "audit";
  return "owner";
}

const can = (actor, area, level = "view") => actor.owner || (LEVELS.indexOf(actor.perms[area] ?? "none") >= LEVELS.indexOf(level));

async function admin(request, env, url) {
  const path = url.pathname.slice("/api/admin".length);
  const method = request.method;
  const now = Date.now();
  if (method !== "GET") {
    // Changes come only from the developer app on this address; a page elsewhere cannot add the header.
    const origin = request.headers.get("origin");
    if (request.headers.get("x-admin") !== "1" || (origin && origin !== url.origin)) return json({ error: "forbidden" }, 403);
  }
  if (path === "/login" && method === "POST") return login(request, env, now);
  if (path === "/join" && method === "GET") return inviteInfo(request, env, url, now);
  if (path === "/join/code" && method === "POST") return joinCode(request, env, now);
  if (path === "/join" && method === "POST") return join(request, env, now);
  const session = await signedIn(request, env, now);
  if (!session) return json({ error: "login" }, 401);
  const actor = session.actor;
  const area = areaFor(path);
  if (area === "owner" ? !actor.owner : area && !can(actor, area, method === "GET" ? "view" : "edit")) {
    return json({ error: "no_permission", area }, 403);
  }
  // Every change below is recorded under the admin who made it (audit reads env.actor).
  env = Object.assign(Object.create(env), { actor });
  if (path === "/logout" && method === "POST") {
    await run(env, "DELETE FROM admin_sessions WHERE token = ?", session.token);
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  if (path === "/me" && method === "GET") return json(me(actor));
  if (path === "/me/password" && method === "POST") return changePassword(request, env, actor, session.token, now);
  if (path === "/me/logout-all" && method === "POST") {
    await run(env, "DELETE FROM admin_sessions WHERE admin = ?", actor.id);
    await audit(env, now, "logout_all", actor.id);
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  if (path === "/overview" && method === "GET") return json(await overview(env, actor));
  if (path === "/audit" && method === "GET") return json(await auditLog(env, url));
  if (path === "/admins" && method === "GET") return json(await adminList(env));
  if (path === "/invites" && method === "POST") return invite(request, env, url, now);
  if (path === "/invites/remove" && method === "POST") return removeInvite(request, env, now);
  let m = path.match(/^\/admins\/(a[A-Za-z0-9]{15})(\/remove)?$/);
  if (m && method === "POST") return m[2] ? removeAdmin(env, m[1], now) : updateAdmin(request, env, m[1], now);
  if (path === "/queue" && method === "GET") return json(await queue(env));
  m = path.match(/^\/groups\/([A-Za-z0-9]{12})\/([0-9a-f]{16})\/(adopt|reject)$/);
  if (m && method === "POST") return decide(request, env, m[1], m[2], m[3], now);
  m = path.match(/^\/asks\/([A-Za-z0-9]{22})$/);
  if (m && method === "POST") return answer(request, env, m[1], now);
  if (path === "/companies" && method === "GET") return json(await companies(env, url.searchParams.get("q")));
  if (path === "/companies" && method === "POST") return createCompany(request, env, now);
  m = path.match(/^\/companies\/([A-Za-z0-9]{12})(\/aliases|\/aliases\/remove|\/merge)?$/);
  if (m && method === "GET" && !m[2]) return companyInfo(env, m[1]);
  if (m && method === "POST") {
    if (!m[2]) return updateCompany(request, env, m[1], now);
    if (m[2] === "/aliases") return addAlias(request, env, m[1], now);
    if (m[2] === "/aliases/remove") return removeAlias(request, env, m[1], now);
    return merge(request, env, m[1], now);
  }
  m = path.match(/^\/rules\/([A-Za-z0-9]{16})$/);
  if (m && method === "POST") return setRule(request, env, m[1], now);
  if (path === "/inquiries" || path.startsWith("/inquiries/")) return adminInquiries(request, env, url, path, now);
  if (path === "/rewards" && method === "GET") return json(await rewards(env, url.searchParams.get("status")));
  m = path.match(/^\/rewards\/(w[A-Za-z0-9]{22})$/);
  if (m && method === "POST") return setReward(request, env, m[1], now);
  if (path === "/settings" && method === "POST") return saveSettings(request, env, now);
  if (path === "/posts" && method === "GET") return json(await postList(env, url));
  m = path.match(/^\/posts\/(p[A-Za-z0-9]{15})$/);
  if (m && method === "POST") return moderate(request, env, m[1], now);
  return json({ error: "not_found" }, 404);
}

/**
 * Five wrong passwords from one network lock it out for an hour from the fifth; for an admin account,
 * five on that account lock the account too, from any network. With an email it is an admin account,
 * without one the owner's password.
 */
async function login(request, env, now) {
  const body = await readJson(request, 1024);
  const net = await network(request, env, now);
  const keys = [`login|${net.login}`];
  const email = body.email === undefined ? null : cleanEmail(body.email);
  if (email) keys.push(`login|mail|${(await sha256(`mail|${email}`)).slice(0, 32)}`);
  else if (body.email !== undefined) return json({ error: "email" }, 400);
  const password = env.ADMIN_PASSWORD;
  if (!email && (typeof password !== "string" || password.length < 10)) return json({ error: "not_set" }, 503);
  let worst = null;
  for (const key of keys) {
    const row = await one(env, "SELECT count, expires FROM counters WHERE key = ? AND expires > ?", key, now);
    if (row && (!worst || row.count > worst.count)) worst = row;
  }
  const everyone = await one(env, "SELECT count FROM counters WHERE key = 'login|all' AND expires > ?", now);
  if ((worst?.count ?? 0) >= LIMITS.loginFailures || (everyone?.count ?? 0) >= 10 * LIMITS.loginFailures) {
    return json({ error: "locked", until: iso(worst?.expires ?? now + LIMITS.lockMs) }, 429);
  }
  let account = null;
  let right = false;
  if (email) {
    account = await one(env, "SELECT * FROM admins WHERE email = ?", email);
    // A made-up email takes as long as a real one, so the time says nothing about who has an account.
    right = await checkPassword(typeof body.password === "string" ? body.password : "", account?.pw ?? DUMMY_HASH) && Boolean(account);
  } else {
    right = typeof body.password === "string" && (await sameSecret(body.password, password));
  }
  if (!right) {
    let count = 0;
    for (const key of keys) {
      await allow(env, key, Infinity, LIMITS.lockMs, now);
      const row = await one(env, "SELECT count FROM counters WHERE key = ?", key);
      if (row.count >= LIMITS.loginFailures) await run(env, "UPDATE counters SET expires = ? WHERE key = ?", now + LIMITS.lockMs, key);
      count = Math.max(count, row.count);
    }
    await allow(env, "login|all", Infinity, LIMITS.lockMs, now);
    await audit(env, now, "login_failed", email ?? "");
    return json({ error: count >= LIMITS.loginFailures ? "locked" : "wrong", left: Math.max(0, LIMITS.loginFailures - count) }, 401);
  }
  // A right password on an account the owner has not approved, or has paused, still does not get in.
  if (account && account.status !== "active") return json({ error: account.status === "pending" ? "pending" : "suspended" }, 403);
  const token = randomId(40);
  const who = account ? account.id : OWNER.id;
  await env.DB.batch([
    ...keys.map((key) => stmt(env, "DELETE FROM counters WHERE key = ?", key)),
    stmt(env, "DELETE FROM admin_sessions WHERE expires < ?", now),
    stmt(env, "INSERT INTO admin_sessions (token, admin, expires, created_at) VALUES (?, ?, ?, ?)",
      await sha256(`session|${token}`), who, now + LIMITS.sessionMs, now),
    ...(account ? [stmt(env, "UPDATE admins SET last_login = ? WHERE id = ?", now, account.id)] : []),
  ]);
  await audit(Object.assign(Object.create(env), { actor: account ? actorOf(account) : OWNER }), now, "login", "");
  return json({ ok: true }, 200, { "set-cookie": cookie(token, LIMITS.sessionMs / 1000) });
}

/** The session and who it belongs to, when the cookie holds a live one of an active admin. */
async function signedIn(request, env, now) {
  const token = (request.headers.get("cookie") ?? "").split(/;\s*/).find((c) => c.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1);
  if (!token || !/^[A-Za-z0-9]{40}$/.test(token)) return null;
  const hash = await sha256(`session|${token}`);
  const row = await one(env, "SELECT admin, expires FROM admin_sessions WHERE token = ?", hash);
  if (!row || row.expires <= now) return null;
  if (row.admin === OWNER.id) return { token: hash, actor: OWNER };
  // Read on every call, so a pause or a change of permissions applies at once.
  const account = await one(env, "SELECT * FROM admins WHERE id = ? AND status = 'active'", row.admin);
  return account ? { token: hash, actor: actorOf(account) } : null;
}

const actorOf = (a) => ({ id: a.id, name: a.name, email: a.email, owner: false, perms: readPerms(a.perms) });

const me = (actor) => ({ id: actor.id, name: actor.name, email: actor.email, owner: actor.owner, perms: actor.perms, areas: AREAS });

// ---- admin accounts [ADMIN-04] ----

const EMAIL = /^[^\s@<>()",;:]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;

function cleanEmail(v) {
  const email = typeof v === "string" ? v.trim().toLowerCase() : "";
  return email.length <= LIMITS.email && EMAIL.test(email) ? email : null;
}

/** Permissions as stored and as sent: every area present, unknown areas and levels dropped. */
function readPerms(raw) {
  let p = raw;
  if (typeof raw === "string") {
    try {
      p = JSON.parse(raw);
    } catch {
      p = {};
    }
  }
  const out = {};
  for (const area of Object.keys(AREAS)) {
    const level = p && LEVELS.includes(p[area]) ? p[area] : "none";
    out[area] = VIEW_ONLY.includes(area) && level === "edit" ? "view" : level;
  }
  return out;
}

function permsFrom(body) {
  if (!body.perms || typeof body.perms !== "object" || Array.isArray(body.perms)) refuse("perms", "perms");
  for (const [area, level] of Object.entries(body.perms)) {
    if (!Object.hasOwn(AREAS, area) || !LEVELS.includes(level)) refuse("perms", area);
  }
  return readPerms(body.perms);
}

function adminName(v) {
  const name = typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f<>]/g, "").trim() : "";
  if (!name || name.length > LIMITS.adminName) refuse("name", "name");
  return name;
}

/** At least 10 letters, not one letter over and over, and not the email itself. */
function newPassword(v, email) {
  if (typeof v !== "string" || v.length < 10 || v.length > 200) refuse("password", "password");
  if (new Set(v).size < 4 || v.toLowerCase() === email || v.toLowerCase() === email.split("@")[0]) refuse("weak_password", "password");
  return v;
}

// PBKDF2 at a cost a Worker's CPU limit allows; the count is stored with each hash so it can rise later.
const PBKDF2_ROUNDS = 20000;
const b64 = (bytes) => btoa(String.fromCharCode(...bytes));

async function pbkdf2(password, saltBytes, rounds) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: saltBytes, iterations: rounds }, key, 256);
  return new Uint8Array(bits);
}

async function hashPassword(password) {
  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  return `pbkdf2$${PBKDF2_ROUNDS}$${b64(saltBytes)}$${b64(await pbkdf2(password, saltBytes, PBKDF2_ROUNDS))}`;
}

const DUMMY_HASH = `pbkdf2$${PBKDF2_ROUNDS}$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`;

async function checkPassword(password, stored) {
  const [kind, rounds, saltText, hashText] = String(stored).split("$");
  if (kind !== "pbkdf2") return false;
  const got = await pbkdf2(password, Uint8Array.from(atob(saltText), (c) => c.charCodeAt(0)), Number(rounds));
  const want = Uint8Array.from(atob(hashText), (c) => c.charCodeAt(0));
  let diff = got.length ^ want.length;
  for (let i = 0; i < Math.min(got.length, want.length); i++) diff |= got[i] ^ want[i];
  return diff === 0;
}

function mailReady(env) {
  return typeof env.MAIL_API_KEY === "string" && env.MAIL_API_KEY.length > 0 && Boolean(cleanEmail(String(env.MAIL_FROM ?? "").replace(/^.*<|>$/g, "")));
}

/**
 * Sends one plain mail through Resend when MAIL_API_KEY and MAIL_FROM are set; false when mail is off or
 * the send failed. Nothing else on the server sends mail.
 */
async function sendMail(env, to, subject, text) {
  if (!mailReady(env)) return false;
  try {
    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: `Bearer ${env.MAIL_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ from: env.MAIL_FROM, to: [to], subject, text }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

async function adminList(env) {
  const admins = await all(env, "SELECT id, email, name, perms, status, created_at, approved_at, last_login FROM admins ORDER BY created_at DESC");
  const invites = await all(env, "SELECT email, perms, note, expires, created_at, used_at FROM invites ORDER BY created_at DESC");
  const now = Date.now();
  return {
    admins: admins.map((a) => ({
      id: a.id, email: a.email, name: a.name, perms: readPerms(a.perms), status: a.status, createdAt: iso(a.created_at),
      approvedAt: a.approved_at ? iso(a.approved_at) : null, lastLogin: a.last_login ? iso(a.last_login) : null,
    })),
    invites: invites.map((i) => ({
      email: i.email, perms: readPerms(i.perms), note: i.note, createdAt: iso(i.created_at), expiresAt: iso(i.expires),
      state: i.used_at ? "joined" : i.expires <= now ? "expired" : "waiting",
    })),
    areas: AREAS,
    viewOnly: VIEW_ONLY,
    presets: PRESETS,
    mail: mailReady(env),
  };
}

/**
 * The owner allows one email, with the permissions its account will start with. The link is shown
 * once; the owner sends it to that email, and only that email can make an account with it.
 */
async function invite(request, env, url, now) {
  const body = await readJson(request, 2048);
  const email = cleanEmail(body.email);
  if (!email) refuse("email", "email");
  const perms = permsFrom(body);
  const note = typeof body.note === "string" ? body.note.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 100) : "";
  if (await one(env, "SELECT id FROM admins WHERE email = ?", email)) return json({ error: "exists" }, 409);
  const token = randomId(32);
  await run(env, `INSERT INTO invites (email, perms, note, token, expires, created_at, used_at) VALUES (?, ?, ?, ?, ?, ?, NULL)
    ON CONFLICT (email) DO UPDATE SET perms = excluded.perms, note = excluded.note, token = excluded.token, expires = excluded.expires,
      created_at = excluded.created_at, used_at = NULL`,
    email, JSON.stringify(perms), note, await sha256(`invite|${token}`), now + LIMITS.inviteMs, now);
  await audit(env, now, "invite", email, Object.entries(perms).filter(([, l]) => l !== "none").map(([a, l]) => `${a}:${l}`).join(" "));
  return json({ email, perms, expiresAt: iso(now + LIMITS.inviteMs), link: `${url.origin}/admin/join#${token}`, mail: mailReady(env) }, 201);
}

async function removeInvite(request, env, now) {
  const email = cleanEmail((await readJson(request, 1024)).email);
  const r = email ? await run(env, "DELETE FROM invites WHERE email = ? AND used_at IS NULL", email) : null;
  if (!r?.meta.changes) return json({ error: "not_found" }, 404);
  await audit(env, now, "invite_remove", email);
  return json({ ok: true });
}

async function inviteFor(env, token, now) {
  if (typeof token !== "string" || !/^[A-Za-z0-9]{32}$/.test(token)) return null;
  const row = await one(env, "SELECT * FROM invites WHERE token = ?", await sha256(`invite|${token}`));
  return row && !row.used_at && row.expires > now ? row : null;
}

/** Too many tries at invite links from one network in an hour, so links cannot be guessed. */
async function joinLimited(request, env, now) {
  const net = await network(request, env, now);
  return !(await allow(env, `join|${net.now}`, LIMITS.joinsPerHour, HOUR, now));
}

/** What the person who opened an invite link sees before making an account. */
async function inviteInfo(request, env, url, now) {
  if (await joinLimited(request, env, now)) return json({ error: "rate_limited" }, 429);
  const row = await inviteFor(env, url.searchParams.get("t"), now);
  if (!row) return json({ error: "invite" }, 404);
  return json({ email: row.email, perms: readPerms(row.perms), areas: AREAS, expiresAt: iso(row.expires), mail: mailReady(env) });
}

/** With mail set up, a six-digit code goes to the invited email, so the account is made from that mailbox. */
async function joinCode(request, env, now) {
  if (await joinLimited(request, env, now)) return json({ error: "rate_limited" }, 429);
  const body = await readJson(request, 1024);
  const row = await inviteFor(env, body.token, now);
  if (!row) return json({ error: "invite" }, 404);
  if (!mailReady(env)) return json({ error: "no_mail" }, 409);
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
  await run(env, `INSERT INTO codes (email, code, expires, tries) VALUES (?, ?, ?, 0)
    ON CONFLICT (email) DO UPDATE SET code = excluded.code, expires = excluded.expires, tries = 0`,
    row.email, await sha256(`code|${row.email}|${code}`), now + LIMITS.codeMs);
  const sent = await sendMail(env, row.email, "[밤낮 관리자] 메일 확인 코드",
    `밤낮 관리자 계정을 만들려면 아래 코드를 입력해 주세요.\n\n${code}\n\n15분 동안 쓸 수 있어요. 직접 요청하지 않았다면 이 메일은 지워 주세요.`);
  if (!sent) return json({ error: "mail_failed" }, 502);
  return json({ ok: true, expiresAt: iso(now + LIMITS.codeMs) });
}

/**
 * An invited person makes their account. It waits for the owner's approval before it can sign in, and
 * starts with the permissions the owner chose when inviting.
 */
async function join(request, env, now) {
  if (await joinLimited(request, env, now)) return json({ error: "rate_limited" }, 429);
  const body = await readJson(request, 2048);
  const row = await inviteFor(env, body.token, now);
  if (!row) return json({ error: "invite" }, 404);
  const name = adminName(body.name);
  const password = newPassword(body.password, row.email);
  if (mailReady(env)) {
    const c = await one(env, "SELECT * FROM codes WHERE email = ?", row.email);
    if (!c || c.expires <= now || c.tries >= LIMITS.codeTries) return json({ error: "code_expired" }, 400);
    if (typeof body.code !== "string" || (await sha256(`code|${row.email}|${body.code.trim()}`)) !== c.code) {
      await run(env, "UPDATE codes SET tries = tries + 1 WHERE email = ?", row.email);
      return json({ error: "code", left: Math.max(0, LIMITS.codeTries - c.tries - 1) }, 400);
    }
  }
  if (await one(env, "SELECT id FROM admins WHERE email = ?", row.email)) return json({ error: "exists" }, 409);
  const id = `a${randomId(15)}`;
  await env.DB.batch([
    stmt(env, `INSERT INTO admins (id, email, name, pw, perms, status, created_at, approved_at, last_login)
      VALUES (?, ?, ?, ?, ?, 'pending', ?, NULL, NULL)`, id, row.email, name, await hashPassword(password), row.perms, now),
    stmt(env, "UPDATE invites SET used_at = ? WHERE email = ?", now, row.email),
    stmt(env, "DELETE FROM codes WHERE email = ?", row.email),
  ]);
  await audit(Object.assign(Object.create(env), { actor: { name, email: row.email } }), now, "join", row.email);
  const owner = cleanEmail(env.OWNER_EMAIL);
  if (owner) await sendMail(env, owner, "[밤낮 관리자] 승인을 기다리는 관리자", `${name} (${row.email}) 님이 관리자 계정을 만들었어요. 개발자 앱의 관리자 화면에서 승인해 주세요.`);
  return json({ ok: true, status: "pending" }, 201);
}

/** The owner approves, pauses, resumes or changes what an admin can do; it applies on their next call. */
async function updateAdmin(request, env, id, now) {
  const body = await readJson(request, 2048);
  const a = await one(env, "SELECT * FROM admins WHERE id = ?", id);
  if (!a) return json({ error: "not_found" }, 404);
  const stmts = [];
  const notes = [];
  if (body.perms !== undefined) {
    const perms = permsFrom(body);
    stmts.push(stmt(env, "UPDATE admins SET perms = ? WHERE id = ?", JSON.stringify(perms), id));
    notes.push(Object.entries(perms).filter(([, l]) => l !== "none").map(([k, l]) => `${k}:${l}`).join(" ") || "none");
  }
  if (body.status !== undefined) {
    if (!["active", "suspended"].includes(body.status)) refuse("status", "status");
    if (body.status === "active" && a.status === "pending") stmts.push(stmt(env, "UPDATE admins SET approved_at = ? WHERE id = ?", now, id));
    stmts.push(stmt(env, "UPDATE admins SET status = ? WHERE id = ?", body.status, id));
    if (body.status === "suspended") stmts.push(stmt(env, "DELETE FROM admin_sessions WHERE admin = ?", id));
    notes.push(body.status === "active" ? (a.status === "pending" ? "approve" : "resume") : "suspend");
  }
  if (!stmts.length) refuse("status", "status");
  await env.DB.batch(stmts);
  await audit(env, now, "admin_update", a.email, notes.join(" / "));
  return json(await adminList(env));
}

async function removeAdmin(env, id, now) {
  const a = await one(env, "SELECT email FROM admins WHERE id = ?", id);
  if (!a) return json({ error: "not_found" }, 404);
  await env.DB.batch([
    stmt(env, "DELETE FROM admin_sessions WHERE admin = ?", id),
    stmt(env, "DELETE FROM admins WHERE id = ?", id),
    // The email can be invited again later.
    stmt(env, "DELETE FROM invites WHERE email = ?", a.email),
  ]);
  await audit(env, now, "admin_remove", a.email);
  return json(await adminList(env));
}

/** An admin changes their own password; their other sign-ins end. The owner's lives in Cloudflare. */
async function changePassword(request, env, actor, token, now) {
  if (actor.owner) return json({ error: "owner_password" }, 409);
  const body = await readJson(request, 1024);
  const a = await one(env, "SELECT * FROM admins WHERE id = ?", actor.id);
  if (typeof body.current !== "string" || !(await checkPassword(body.current, a.pw))) return json({ error: "wrong" }, 400);
  const next = newPassword(body.next, a.email);
  await env.DB.batch([
    stmt(env, "UPDATE admins SET pw = ? WHERE id = ?", await hashPassword(next), a.id),
    stmt(env, "DELETE FROM admin_sessions WHERE admin = ? AND token != ?", a.id, token),
  ]);
  await audit(env, now, "password", a.id);
  return json({ ok: true });
}

/** Activity, newest first, 50 at a time; [before] continues from an entry's id. */
async function auditLog(env, url) {
  const before = Number(url.searchParams.get("before"));
  const rows = await all(env, `SELECT id, at, action, target, detail, actor FROM audit WHERE id < ? ORDER BY id DESC LIMIT 51`,
    Number.isInteger(before) && before > 0 ? before : Number.MAX_SAFE_INTEGER);
  return {
    entries: rows.slice(0, 50).map((r) => ({ id: r.id, at: iso(r.at), action: r.action, target: r.target, detail: r.detail, actor: r.actor })),
    more: rows.length > 50,
  };
}

/** The home screen's numbers, only for the areas this admin can see. */
async function overview(env, actor) {
  const count = async (sql) => (await one(env, sql)).n;
  const out = { me: me(actor), settings: await settings(env), reasons: REASONS, answers: ANSWERS, hideReasons: HIDE_REASONS, flagReasons: FLAGS,
    salt: secretIssue(env, "RATE_SALT", 1) === null };
  if (can(actor, "review")) {
    out.pending = await count("SELECT COUNT(*) AS n FROM (SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending')");
    out.asks = await count("SELECT COUNT(*) AS n FROM reports WHERE status = 'ask'");
    out.flagged = await count(`SELECT COUNT(*) AS n FROM rules r WHERE r.withdrawn = 0 AND r.superseded = 0
      AND EXISTS (SELECT 1 FROM votes v WHERE v.company_id = r.company_id AND v.grp = r.grp AND v.ok = 0)`);
    out.reports = await count("SELECT COUNT(*) AS n FROM reports");
  }
  if (can(actor, "inquiries")) out.inquiries = await count("SELECT COUNT(*) AS n FROM inquiries WHERE status = 'open'");
  if (can(actor, "rewards")) out.owed = await count("SELECT COUNT(*) AS n FROM rewards WHERE status = 'owed'");
  if (can(actor, "community")) {
    // [COMM-06] Flagged posts still shown, and ones the flags hid that nobody has looked at yet.
    out.flaggedPosts = await count("SELECT COUNT(*) AS n FROM posts WHERE kept = 0 AND (flags > 0 OR hide_reason = 'flags')");
    out.posts = await count("SELECT COUNT(*) AS n FROM posts WHERE status = 'shown'");
  }
  if (can(actor, "companies")) {
    out.companies = await count("SELECT COUNT(*) AS n FROM companies WHERE merged_into IS NULL");
    out.rules = await count("SELECT COUNT(*) AS n FROM rules WHERE withdrawn = 0 AND superseded = 0");
  }
  if (actor.owner) out.admins = { waiting: await count("SELECT COUNT(*) AS n FROM admins WHERE status = 'pending'") };
  return out;
}

/** Setups waiting for a decision, the ones with most behind them first, and rules people doubt. */
async function queue(env) {
  const { adoptPoints, adoptAgree } = await settings(env);
  const groups = await all(env, `SELECT company_id, grp, MAX(created_at) AS last FROM reports WHERE status = 'pending'
    GROUP BY company_id, grp ORDER BY last DESC LIMIT 60`);
  const out = [];
  for (const g of groups) {
    const c = await one(env, "SELECT id, name, hidden FROM companies WHERE id = ?", g.company_id);
    const rep = await representative(env, g.company_id, g.grp);
    const s = await score(env, g.company_id, g.grp);
    const reports = await all(env, `SELECT id, verified, note, created_at FROM reports WHERE company_id = ? AND grp = ? AND status = 'pending'
      ORDER BY created_at`, g.company_id, g.grp);
    const active = await activeRule(env, g.company_id, rep.dept_norm);
    const why = await blocker(env, g.company_id, g.grp, rep.dept_norm);
    const lines = describe(JSON.parse(rep.setup));
    out.push({
      company: { id: c.id, name: c.name, hidden: c.hidden === 1 },
      dept: rep.dept,
      setup: g.grp,
      points: s.points,
      persons: s.persons,
      networks: s.networks,
      bad: s.bad,
      threshold: adoptPoints,
      ...trust(s, adoptPoints),
      // [COMM-07] More 달라요 than the setting allows: auto adoption waits for the developer.
      doubted: s.persons * 100 < adoptAgree * (s.persons + s.bad),
      posts: (await one(env, "SELECT COUNT(*) AS n FROM posts WHERE company_id = ? AND grp = ? AND status = 'shown'", g.company_id, g.grp)).n,
      // Enough points, but another rule is in use, this one was turned down, everyone sent it from one
      // network or too many say 달라요: only the developer decides.
      blocked: (Boolean(why) || s.networks < 2 || s.persons * 100 < adoptAgree * (s.persons + s.bad)) && s.persons >= 2 && s.points >= adoptPoints,
      turnedDown: why === "withdrawn" || why === "rejected" ? why : null,
      replaces: active ? { id: active.id, version: active.version } : null,
      reports: reports.map((r) => ({ id: r.id, verified: r.verified === 1, note: r.note, at: iso(r.created_at) })),
      lines,
      changes: active ? diffLines(describe(JSON.parse(active.setup)), lines) : null,
    });
  }
  out.sort((a, b) => b.blocked - a.blocked || b.points - a.points || cmp(a.reports[0].at, b.reports[0].at));
  const flagged = [];
  for (const r of await all(env, `
    SELECT r.id, r.company_id, r.grp, r.dept, r.version, r.setup, c.name,
      SUM(CASE WHEN v.ok = 0 AND v.why = 'name' THEN 1 ELSE 0 END) AS names,
      SUM(CASE WHEN v.ok = 0 AND v.why = 'old' THEN 1 ELSE 0 END) AS old
    FROM rules r JOIN companies c ON c.id = r.company_id JOIN votes v ON v.company_id = r.company_id AND v.grp = r.grp
    WHERE r.withdrawn = 0 AND r.superseded = 0
    GROUP BY r.id HAVING SUM(CASE WHEN v.ok = 0 THEN 1 ELSE 0 END) > 0 LIMIT 30`)) {
    const s = await score(env, r.company_id, r.grp);
    flagged.push({ rule: r.id, company: { id: r.company_id, name: r.name }, dept: r.dept, version: r.version, good: s.persons, bad: s.bad,
      names: r.names, old: r.old, lines: describe(JSON.parse(r.setup)) });
  }
  flagged.sort((a, b) => b.bad - a.bad);
  const asks = (await all(env, `SELECT p.id, p.company_id, p.dept, p.note, p.verified, p.setup, p.created_at, c.name
    FROM reports p JOIN companies c ON c.id = p.company_id WHERE p.status = 'ask' ORDER BY p.created_at LIMIT 60`)).map((a) => ({
    id: a.id, company: { id: a.company_id, name: a.name }, dept: a.dept, note: a.note, verified: a.verified === 1, at: iso(a.created_at),
    lines: describe(JSON.parse(a.setup)),
  }));
  return { groups: out, flagged, asks };
}

/**
 * [EXTRA-06] The developer's answer to a way the app cannot set yet. One the app will learn can earn
 * the sender the same reward as an adopted setup.
 */
async function answer(request, env, id, now) {
  const body = await readJson(request, 1024);
  if (!Object.hasOwn(ANSWERS, body.answer)) refuse("answer", "answer");
  const r = await one(env, "SELECT id, device FROM reports WHERE id = ? AND status = 'ask'", id);
  if (!r) return json({ error: "not_found" }, 404);
  const { rewardMonths } = await settings(env);
  const stmts = [stmt(env, "UPDATE reports SET status = 'answered', reason = ?, decided_at = ? WHERE id = ?", body.answer, now, id)];
  const reward = body.answer === "planned" && body.reward === true && rewardMonths > 0;
  if (reward) {
    stmts.push(stmt(env, `INSERT OR IGNORE INTO rewards (id, report_id, rule_id, device, months, status, memo, created_at, paid_at)
      VALUES (?, ?, '', ?, ?, 'owed', '', ?, NULL)`, `w${id}`, id, r.device, rewardMonths, now));
  }
  await env.DB.batch(stmts);
  await audit(env, now, "answer", id, `${body.answer}${reward ? "+reward" : ""}`);
  return json({ ok: true, reward });
}

async function decide(request, env, companyId, grp, action, now) {
  const waiting = await one(env, "SELECT COUNT(*) AS n FROM reports WHERE company_id = ? AND grp = ? AND status = 'pending'", companyId, grp);
  if (!waiting.n) return json({ error: "not_found" }, 404);
  if (action === "adopt") {
    const rule = await adopt(env, companyId, grp, "admin", now);
    await audit(env, now, "adopt", `${companyId}/${grp}`, rule);
    return json({ ok: true, rule });
  }
  const body = await readJson(request, 1024);
  const reason = Object.hasOwn(REASONS, body.reason) ? body.reason : "unclear";
  await run(env, "UPDATE reports SET status = 'rejected', reason = ?, decided_at = ? WHERE company_id = ? AND grp = ? AND status = 'pending'",
    reason, now, companyId, grp);
  await audit(env, now, "reject", `${companyId}/${grp}`, reason);
  return json({ ok: true });
}

async function companies(env, query) {
  const q = normName(query).slice(0, 40);
  const like = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const rows = await all(env, `
    SELECT c.*,
      (SELECT COUNT(*) FROM aliases a WHERE a.company_id = c.id) AS aliases,
      (SELECT COUNT(*) FROM rules r WHERE r.company_id = c.id AND r.withdrawn = 0 AND r.superseded = 0) AS rules,
      (SELECT COUNT(DISTINCT p.grp) FROM reports p WHERE p.company_id = c.id AND p.status = 'pending') AS pending,
      (SELECT COUNT(*) FROM reports p WHERE p.company_id = c.id) AS reports
    FROM companies c
    WHERE ?1 = '' OR c.norm LIKE ?2 ESCAPE '\\' OR EXISTS (SELECT 1 FROM aliases a WHERE a.company_id = c.id AND a.norm LIKE ?2 ESCAPE '\\')
    ORDER BY c.merged_into IS NOT NULL, c.created_at DESC LIMIT 100`, q, like);
  return {
    companies: rows.map((c) => ({
      id: c.id, name: c.name, hidden: c.hidden === 1, mergedInto: c.merged_into, aliases: c.aliases, rules: c.rules,
      pending: c.pending, reports: c.reports, createdAt: iso(c.created_at),
    })),
  };
}

async function companyInfo(env, id) {
  const c = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  if (!c) return json({ error: "not_found" }, 404);
  const into = c.merged_into ? await one(env, "SELECT id, name FROM companies WHERE id = ?", c.merged_into) : null;
  const aliases = (await all(env, "SELECT name FROM aliases WHERE company_id = ? ORDER BY name", id)).map((a) => a.name);
  const rules = [];
  for (const r of await all(env, "SELECT * FROM rules WHERE company_id = ? ORDER BY dept_norm, version DESC", id)) {
    const s = await score(env, id, r.grp);
    rules.push({
      id: r.id, dept: r.dept, version: r.version, source: r.source, adoptedAt: iso(r.adopted_at), withdrawn: r.withdrawn === 1,
      superseded: r.superseded === 1, ok: s.persons, bad: s.bad, lines: describe(JSON.parse(r.setup)),
    });
  }
  const pending = await one(env, "SELECT COUNT(DISTINCT grp) AS n FROM reports WHERE company_id = ? AND status = 'pending'", id);
  return json({
    id: c.id, name: c.name, hidden: c.hidden === 1, mergedInto: into, createdAt: iso(c.created_at), aliases, rules, pending: pending.n,
  });
}

function companyName(body) {
  const name = typeof body.name === "string" ? body.name.replace(/[\u0000-\u001f\u007f]/g, "").trim() : "";
  if (!name || name.length > LIMITS.company || normName(name).length < 2) refuse("company", "name");
  return name;
}

/** The company already called [norm], by its name or by another name. */
async function named(env, norm) {
  return (await one(env, "SELECT id, name FROM companies WHERE norm = ? AND merged_into IS NULL LIMIT 1", norm))
    ?? (await one(env, "SELECT c.id, c.name FROM aliases a JOIN companies c ON c.id = a.company_id WHERE a.norm = ?", norm));
}

async function createCompany(request, env, now) {
  const name = companyName(await readJson(request, 1024));
  const norm = normName(name);
  const taken = await named(env, norm);
  if (taken) return json({ error: "exists", company: taken }, 409);
  const id = randomId(12);
  await run(env, "INSERT INTO companies (id, name, norm, hidden, merged_into, created_at) VALUES (?, ?, ?, 0, NULL, ?)", id, name, norm, now);
  await audit(env, now, "company_create", id, name);
  return json({ id, name }, 201);
}

async function updateCompany(request, env, id, now) {
  const body = await readJson(request, 1024);
  const c = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  if (!c) return json({ error: "not_found" }, 404);
  const stmts = [];
  if (body.name !== undefined) {
    const name = companyName(body);
    const norm = normName(name);
    const taken = await named(env, norm);
    if (taken && taken.id !== id) return json({ error: "exists", company: taken }, 409);
    // The old name keeps finding the company.
    if (norm !== c.norm) stmts.push(stmt(env, "INSERT OR IGNORE INTO aliases (norm, company_id, name) VALUES (?, ?, ?)", c.norm, id, c.name));
    stmts.push(stmt(env, "DELETE FROM aliases WHERE norm = ? AND company_id = ?", norm, id));
    stmts.push(stmt(env, "UPDATE companies SET name = ?, norm = ? WHERE id = ?", name, norm, id));
  }
  if (body.hidden !== undefined) {
    if (typeof body.hidden !== "boolean") refuse("hidden", "hidden");
    stmts.push(stmt(env, "UPDATE companies SET hidden = ? WHERE id = ?", body.hidden ? 1 : 0, id));
  }
  if (stmts.length) await env.DB.batch(stmts);
  await audit(env, now, "company_update", id, JSON.stringify(body));
  return companyInfo(env, id);
}

async function addAlias(request, env, id, now) {
  const name = companyName(await readJson(request, 1024));
  const norm = normName(name);
  const c = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  if (!c) return json({ error: "not_found" }, 404);
  const taken = await named(env, norm);
  if (taken && taken.id !== id) return json({ error: "exists", company: taken }, 409);
  if (norm !== c.norm) await run(env, "INSERT OR IGNORE INTO aliases (norm, company_id, name) VALUES (?, ?, ?)", norm, id, name);
  await audit(env, now, "alias_add", id, name);
  return companyInfo(env, id);
}

async function removeAlias(request, env, id, now) {
  const body = await readJson(request, 1024);
  await run(env, "DELETE FROM aliases WHERE norm = ? AND company_id = ?", normName(body.name), id);
  await audit(env, now, "alias_remove", id, String(body.name ?? ""));
  return companyInfo(env, id);
}

/** One company found under two names: everything moves to [into] and the name stays as another name. */
async function merge(request, env, id, now) {
  const body = await readJson(request, 1024);
  const from = await one(env, "SELECT * FROM companies WHERE id = ?", id);
  const into = typeof body.into === "string" && /^[A-Za-z0-9]{12}$/.test(body.into) ? await follow(env, body.into) : null;
  if (!from || !into) return json({ error: "not_found" }, 404);
  if (from.merged_into || into.id === from.id) return json({ error: "merge" }, 400);
  // Where both have a rule for a department, the one already in use at [into] stays.
  const theirs = new Set((await all(env, "SELECT dept_norm FROM rules WHERE company_id = ? AND withdrawn = 0 AND superseded = 0", into.id))
    .map((r) => r.dept_norm));
  const mine = await all(env, "SELECT id, dept_norm FROM rules WHERE company_id = ? AND withdrawn = 0 AND superseded = 0", from.id);
  const stmts = mine.filter((r) => theirs.has(r.dept_norm)).map((r) => stmt(env, "UPDATE rules SET superseded = 1 WHERE id = ?", r.id));
  stmts.push(stmt(env, "UPDATE aliases SET company_id = ? WHERE company_id = ?", into.id, from.id));
  if (from.norm !== into.norm) stmts.push(stmt(env, "INSERT OR IGNORE INTO aliases (norm, company_id, name) VALUES (?, ?, ?)", from.norm, into.id, from.name));
  stmts.push(
    stmt(env, "UPDATE reports SET company_id = ? WHERE company_id = ?", into.id, from.id),
    stmt(env, "UPDATE OR IGNORE votes SET company_id = ? WHERE company_id = ?", into.id, from.id),
    stmt(env, "DELETE FROM votes WHERE company_id = ?", from.id),
    stmt(env, "UPDATE rules SET company_id = ? WHERE company_id = ?", into.id, from.id),
    stmt(env, "UPDATE companies SET merged_into = ? WHERE id = ? OR merged_into = ?", into.id, from.id, from.id),
  );
  await env.DB.batch(stmts);
  // Reports that now stand together may be enough to adopt.
  for (const g of await all(env, "SELECT DISTINCT grp FROM reports WHERE company_id = ? AND status = 'pending'", into.id)) {
    await settle(env, into.id, g.grp, now);
  }
  await audit(env, now, "merge", from.id, into.id);
  return companyInfo(env, into.id);
}

async function setRule(request, env, id, now) {
  const body = await readJson(request, 1024);
  if (typeof body.withdrawn !== "boolean") refuse("withdrawn", "withdrawn");
  const r = await one(env, "SELECT * FROM rules WHERE id = ?", id);
  if (!r) return json({ error: "not_found" }, 404);
  if (body.withdrawn) {
    await run(env, "UPDATE rules SET withdrawn = 1 WHERE id = ?", id);
  } else {
    // A department uses one rule; one adopted since stays unless the developer withdraws it first.
    const active = r.superseded ? null : await activeRule(env, r.company_id, r.dept_norm);
    if (active && active.id !== id) return json({ error: "active" }, 409);
    await env.DB.batch([
      stmt(env, "UPDATE rules SET withdrawn = 0 WHERE id = ?", id),
      // Coworkers who sent the same setup meanwhile join it.
      ...(r.superseded ? [] : [stmt(env, `UPDATE reports SET status = 'adopted', rule_id = ?, decided_at = ?
        WHERE company_id = ? AND grp = ? AND status = 'pending'`, id, now, r.company_id, r.grp)]),
    ]);
  }
  await audit(env, now, body.withdrawn ? "rule_withdraw" : "rule_restore", id);
  return companyInfo(env, r.company_id);
}

async function rewards(env, status) {
  const wanted = ["owed", "paid", "void"].includes(status) ? status : "owed";
  const rows = await all(env, `
    SELECT w.id, w.months, w.status, w.memo, w.created_at, w.paid_at, w.rule_id, p.dept, c.id AS company_id, c.name AS company, u.version
    FROM rewards w JOIN reports p ON p.id = w.report_id JOIN companies c ON c.id = p.company_id LEFT JOIN rules u ON u.id = w.rule_id
    WHERE w.status = ? ORDER BY w.created_at DESC LIMIT 200`, wanted);
  const counts = await all(env, "SELECT status, COUNT(*) AS n FROM rewards GROUP BY status");
  return {
    status: wanted,
    counts: Object.fromEntries(counts.map((x) => [x.status, x.n])),
    rewards: rows.map((w) => ({
      id: w.id, months: w.months, status: w.status, memo: w.memo, company: { id: w.company_id, name: w.company }, dept: w.dept,
      version: w.version, ask: w.rule_id === "", createdAt: iso(w.created_at), paidAt: w.paid_at ? iso(w.paid_at) : null,
    })),
  };
}

async function setReward(request, env, id, now) {
  const body = await readJson(request, 2048);
  if (!["owed", "paid", "void"].includes(body.status)) refuse("status", "status");
  const memo = typeof body.memo === "string" ? body.memo.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 200) : null;
  const r = await run(env, "UPDATE rewards SET status = ?, memo = COALESCE(?, memo), paid_at = ? WHERE id = ?",
    body.status, memo, body.status === "paid" ? now : null, id);
  if (!r.meta.changes) return json({ error: "not_found" }, 404);
  await audit(env, now, `reward_${body.status}`, id, memo ?? "");
  return json({ ok: true });
}

async function saveSettings(request, env, now) {
  const body = await readJson(request, 1024);
  if (!Number.isInteger(body.adoptPoints) || body.adoptPoints < 2 || body.adoptPoints > 20) refuse("adoptPoints", "adoptPoints");
  if (!Number.isInteger(body.rewardMonths) || body.rewardMonths < 0 || body.rewardMonths > 24) refuse("rewardMonths", "rewardMonths");
  // [COMM-07] Left out by a developer app from before it, the agreement share stays as it was.
  const agree = body.adoptAgree ?? (await settings(env)).adoptAgree;
  if (!Number.isInteger(agree) || agree < 50 || agree > 100) refuse("adoptAgree", "adoptAgree");
  await env.DB.batch([
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('adopt_points', ?)", String(body.adoptPoints)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('reward_months', ?)", String(body.rewardMonths)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('adopt_agree', ?)", String(agree)),
  ]);
  // A lower bar may already be met.
  for (const g of await all(env, "SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending' LIMIT 200")) {
    await settle(env, g.company_id, g.grp, now);
  }
  await audit(env, now, "settings", "", `${body.adoptPoints}/${body.rewardMonths}/${agree}%`);
  return json(await settings(env));
}

// ---- the talk on rule sets, for the developer [COMM-06] ----

/**
 * Posts to look at: "flagged" (신고가 있거나 신고로 가려졌는데 아직 아무도 안 본 것, the most flagged first),
 * "hidden", or "all" newest first; with company and setup, one talk whole. A writer shows as the first
 * six letters of the install's hash, enough to see the same writer again and nothing more.
 */
async function postList(env, url) {
  const status = ["flagged", "hidden", "all"].includes(url.searchParams.get("status")) ? url.searchParams.get("status") : "flagged";
  const company = url.searchParams.get("company");
  const grp = url.searchParams.get("setup");
  const one_talk = company && /^[A-Za-z0-9]{12}$/.test(company) && grp && /^[0-9a-f]{16}$/.test(grp);
  const rows = one_talk
    ? await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
        WHERE p.company_id = ? AND p.grp = ? ORDER BY p.created_at LIMIT 500`, company, grp)
    : status === "flagged"
      ? await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
          WHERE p.kept = 0 AND (p.flags > 0 OR p.hide_reason = 'flags') ORDER BY p.flags DESC, p.created_at DESC LIMIT 100`)
      : await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
          ${status === "hidden" ? "WHERE p.status = 'hidden'" : ""} ORDER BY p.created_at DESC LIMIT 100`);
  const out = [];
  const depts = new Map();
  for (const p of rows) {
    const key = `${p.company_id}/${p.grp}`;
    if (!depts.has(key)) {
      const r = (await one(env, "SELECT dept FROM rules WHERE company_id = ? AND grp = ? ORDER BY version DESC LIMIT 1", p.company_id, p.grp))
        ?? (await one(env, "SELECT dept FROM reports WHERE company_id = ? AND grp = ? LIMIT 1", p.company_id, p.grp));
      depts.set(key, r?.dept ?? "");
    }
    const reasons = await all(env, "SELECT reason, COUNT(*) AS n FROM post_flags WHERE post_id = ? GROUP BY reason ORDER BY n DESC, reason", p.id);
    const muted = await one(env, "SELECT until FROM muted WHERE device = ? AND until > ?", p.device, Date.now());
    out.push({
      id: p.id,
      company: { id: p.company_id, name: p.name },
      setup: p.grp,
      dept: depts.get(key),
      body: p.body,
      writer: p.device.slice(0, 6),
      at: iso(p.created_at),
      status: p.status,
      kept: p.kept === 1,
      flags: p.flags,
      reasons: reasons.map((r) => ({ code: r.reason, text: FLAGS[r.reason] ?? r.reason, n: r.n })),
      hidden: p.status === "hidden" ? { code: p.hide_reason, text: HIDE_REASONS[p.hide_reason] ?? "", at: p.hidden_at ? iso(p.hidden_at) : null } : null,
      mutedUntil: muted ? iso(muted.until) : null,
    });
  }
  return { status: one_talk ? "talk" : status, posts: out };
}

/**
 * hide (with a reason the writer's app shows), show (kept: flags no longer hide it), delete, or mute:
 * the writer cannot post for 30 days, and their post is hidden.
 */
async function moderate(request, env, id, now) {
  const body = await readJson(request, 1024);
  const p = await one(env, "SELECT * FROM posts WHERE id = ?", id);
  if (!p) return json({ error: "not_found" }, 404);
  const target = `${p.company_id}/${p.grp}/${id}`;
  if (body.action === "hide" || body.action === "mute") {
    const reason = Object.hasOwn(HIDE_REASONS, body.reason) && body.reason !== "flags" ? body.reason : "abuse";
    const stmts = [stmt(env, "UPDATE posts SET status = 'hidden', hide_reason = ?, hidden_at = ?, kept = 1 WHERE id = ?", reason, now, id)];
    if (body.action === "mute") {
      stmts.push(stmt(env, `INSERT INTO muted (device, until, reason) VALUES (?, ?, ?)
        ON CONFLICT (device) DO UPDATE SET until = excluded.until, reason = excluded.reason`, p.device, now + LIMITS.muteDays * DAY, reason));
    }
    await env.DB.batch(stmts);
    await audit(env, now, body.action === "mute" ? "post_mute" : "post_hide", target, reason);
  } else if (body.action === "show") {
    await env.DB.batch([
      stmt(env, "UPDATE posts SET status = 'shown', hide_reason = NULL, hidden_at = NULL, kept = 1, flags = 0 WHERE id = ?", id),
      stmt(env, "DELETE FROM post_flags WHERE post_id = ?", id),
      stmt(env, "DELETE FROM muted WHERE device = ?", p.device),
    ]);
    await audit(env, now, "post_show", target);
  } else if (body.action === "delete") {
    await env.DB.batch([stmt(env, "DELETE FROM post_flags WHERE post_id = ?", id), stmt(env, "DELETE FROM posts WHERE id = ?", id)]);
    await audit(env, now, "post_delete", target);
    return json({ deleted: true });
  } else {
    refuse("action", "action");
  }
  const after = await one(env, "SELECT id, status, hide_reason, kept FROM posts WHERE id = ?", id);
  return json({ id, status: after.status, kept: after.kept === 1, hidden: after.hide_reason });
}

// ---- input and output ----

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff", ...headers },
  });
}

async function readJson(request, maxBytes) {
  if (!(request.headers.get("content-type") || "").startsWith("application/json")) throw new Refuse("content_type", "body");
  if (Number(request.headers.get("content-length") || 0) > maxBytes) throw new Refuse("too_large", "body", 413);
  const text = await request.text();
  if (new TextEncoder().encode(text).length > maxBytes) throw new Refuse("too_large", "body", 413);
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Refuse("bad_json", "body");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new Refuse("bad_json", "body");
  return body;
}

const iso = (ms) => new Date(ms).toISOString();

function randomId(length) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  let out = "";
  // 256 % 57 leaves a small bias; skipping the top values keeps every character equally likely.
  for (const b of bytes) out += b < 228 ? ID_CHARS[b % 57] : "";
  return out.length >= length ? out.slice(0, length) : out + randomId(length - out.length);
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Compares digests, so the time taken says nothing about how much of the password was right. */
async function sameSecret(given, secret) {
  const [a, b] = await Promise.all([sha256(`pw|${given}`), sha256(`pw|${secret}`)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
