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
// GET    /admin/                  the developer app, installable on a phone [ADMIN-01]
// *      /api/admin/*             behind the admin password (secret ADMIN_PASSWORD)
//
// A report carries no pay amounts, names or contacts; one that looks like it does is refused, not
// cleaned. No IP address is stored: limits and "the same network within an hour" use hashes salted
// with the secret RATE_SALT, cleared within two hours; what stays is a random mark shared by sends
// from one network within the hour. The app's install id is kept only as a hash, so a sender counts
// once and can take a report back.

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
  loginFailures: 5,
  lockMs: 60 * 60 * 1000,
  sessionMs: 7 * 24 * 60 * 60 * 1000,
};

/** Until changed in the developer app. */
const DEFAULTS = { adoptPoints: 3, rewardMonths: 3 };

/** The newest company settings file this server reads (CompanyShare.VERSION in the app). */
const SETUP_VERSION = 2;

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
};

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
  "CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, expires INTEGER NOT NULL)",
  `CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL,
    target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')`,
];

const schemas = new WeakMap();

/** Tables are made on first use, so setting up the server is binding an empty D1 database. */
function ready(env) {
  let done = schemas.get(env.DB);
  if (!done) {
    done = env.DB.batch(SCHEMA.map((sql) => env.DB.prepare(sql))).catch((e) => {
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
  const rows = await all(env, "SELECT key, value FROM settings WHERE key IN ('adopt_points', 'reward_months')");
  const read = (key, fallback) => {
    const n = Number(rows.find((r) => r.key === key)?.value);
    return Number.isInteger(n) ? n : fallback;
  };
  return { adoptPoints: read("adopt_points", DEFAULTS.adoptPoints), rewardMonths: read("reward_months", DEFAULTS.rewardMonths) };
}

async function salt(env) {
  if (env.RATE_SALT) return env.RATE_SALT;
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

async function audit(env, now, action, target, detail = "") {
  await env.DB.batch([
    stmt(env, "INSERT INTO audit (at, action, target, detail) VALUES (?, ?, ?, ?)", now, action, target, String(detail).slice(0, 300)),
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
  return setup;
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
      p.flexPeriod ? `탄력 ${p.flexPeriod === "MONTH" ? "1개월" : "3개월"}` : ""].filter(Boolean).join(" · "));
    const r = p.rules;
    if (r) {
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
  const parts = [how, given(i.percentOfBase) ? `기본급 ${pct(i.percentOfBase)}` : "", i.festivals.map((f) => (f === "SEOLLAL" ? "설" : "추석")).join("·")];
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
  const { adoptPoints, rewardMonths } = await settings(env);
  return { threshold: adoptPoints, rewardMonths };
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
    setups.push({ ...(await state(env, c.id, r.grp, adoptPoints, true)), dept: r.dept, version: r.version, adoptedAt: iso(r.adopted_at), setup: shared(r.setup, c.name) });
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
    setups.push({ ...s, dept: rep.dept, setup: shared(rep.setup, c.name) });
  }
  return json({ id: c.id, name: c.name, aliases, setups });
}

const shared = (text, name) => ({ ...JSON.parse(text), company: name });

async function state(env, companyId, grp, threshold, adopted) {
  const s = await score(env, companyId, grp);
  return { id: grp, status: adopted ? "adopted" : "pending", points: s.points, persons: s.persons, bad: s.bad, threshold };
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
  const { adoptPoints } = await settings(env);
  const s = await score(env, companyId, grp);
  if (s.persons < 2 || s.points < adoptPoints || s.networks < 2) return;
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
  const [, reports, votes] = await env.DB.batch([
    stmt(env, "DELETE FROM rewards WHERE device = ?", dev),
    stmt(env, "DELETE FROM reports WHERE device = ?", dev),
    stmt(env, "DELETE FROM votes WHERE device = ?", dev),
    stmt(env, "DELETE FROM counters WHERE key = ?", `report|${dev}`),
  ]);
  return json({ reports: reports.meta.changes, votes: votes.meta.changes });
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

// ---- the developer app's API [ADMIN-01] ----

const COOKIE = "__Host-bamnat_admin";
const cookie = (value, maxAge) => `${COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${maxAge}`;

/** What is still missing on a new server, shown on the login screen. */
function health(env) {
  return {
    database: Boolean(env.DB),
    password: typeof env.ADMIN_PASSWORD === "string" && env.ADMIN_PASSWORD.length >= 10,
    salt: Boolean(env.RATE_SALT),
  };
}

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
  const session = await signedIn(request, env, now);
  if (!session) return json({ error: "login" }, 401);
  if (path === "/logout" && method === "POST") {
    await run(env, "DELETE FROM sessions WHERE token = ?", session);
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  if (path === "/overview" && method === "GET") return json(await overview(env));
  if (path === "/queue" && method === "GET") return json(await queue(env));
  let m = path.match(/^\/groups\/([A-Za-z0-9]{12})\/([0-9a-f]{16})\/(adopt|reject)$/);
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
  if (path === "/rewards" && method === "GET") return json(await rewards(env, url.searchParams.get("status")));
  m = path.match(/^\/rewards\/(w[A-Za-z0-9]{22})$/);
  if (m && method === "POST") return setReward(request, env, m[1], now);
  if (path === "/settings" && method === "POST") return saveSettings(request, env, now);
  return json({ error: "not_found" }, 404);
}

/** Five wrong passwords from one network lock it out for an hour from the fifth. */
async function login(request, env, now) {
  const password = env.ADMIN_PASSWORD;
  if (typeof password !== "string" || password.length < 10) return json({ error: "not_set" }, 503);
  const net = await network(request, env, now);
  const key = `login|${net.login}`;
  const failed = await one(env, "SELECT count, expires FROM counters WHERE key = ? AND expires > ?", key, now);
  const everyone = await one(env, "SELECT count FROM counters WHERE key = 'login|all' AND expires > ?", now);
  if ((failed?.count ?? 0) >= LIMITS.loginFailures || (everyone?.count ?? 0) >= 10 * LIMITS.loginFailures) {
    return json({ error: "locked", until: iso(failed?.expires ?? now + LIMITS.lockMs) }, 429);
  }
  const body = await readJson(request, 1024);
  if (typeof body.password !== "string" || !(await sameSecret(body.password, password))) {
    await allow(env, key, Infinity, LIMITS.lockMs, now);
    await allow(env, "login|all", Infinity, LIMITS.lockMs, now);
    const count = (failed?.count ?? 0) + 1;
    if (count >= LIMITS.loginFailures) await run(env, "UPDATE counters SET expires = ? WHERE key = ?", now + LIMITS.lockMs, key);
    await audit(env, now, "login_failed", "");
    return json({ error: count >= LIMITS.loginFailures ? "locked" : "wrong", left: Math.max(0, LIMITS.loginFailures - count) }, 401);
  }
  const token = randomId(40);
  await env.DB.batch([
    stmt(env, "DELETE FROM counters WHERE key = ?", key),
    stmt(env, "DELETE FROM sessions WHERE expires < ?", now),
    stmt(env, "INSERT INTO sessions (token, expires) VALUES (?, ?)", await sha256(`session|${token}`), now + LIMITS.sessionMs),
  ]);
  await audit(env, now, "login", "");
  return json({ ok: true }, 200, { "set-cookie": cookie(token, LIMITS.sessionMs / 1000) });
}

/** The session's stored hash when the cookie holds a live session. */
async function signedIn(request, env, now) {
  const token = (request.headers.get("cookie") ?? "").split(/;\s*/).find((c) => c.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1);
  if (!token || !/^[A-Za-z0-9]{40}$/.test(token)) return null;
  const hash = await sha256(`session|${token}`);
  const row = await one(env, "SELECT expires FROM sessions WHERE token = ?", hash);
  return row && row.expires > now ? hash : null;
}

async function overview(env) {
  const count = async (sql) => (await one(env, sql)).n;
  return {
    pending: await count("SELECT COUNT(*) AS n FROM (SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending')"),
    asks: await count("SELECT COUNT(*) AS n FROM reports WHERE status = 'ask'"),
    flagged: await count(`SELECT COUNT(*) AS n FROM rules r WHERE r.withdrawn = 0 AND r.superseded = 0
      AND EXISTS (SELECT 1 FROM votes v WHERE v.company_id = r.company_id AND v.grp = r.grp AND v.ok = 0)`),
    owed: await count("SELECT COUNT(*) AS n FROM rewards WHERE status = 'owed'"),
    companies: await count("SELECT COUNT(*) AS n FROM companies WHERE merged_into IS NULL"),
    rules: await count("SELECT COUNT(*) AS n FROM rules WHERE withdrawn = 0 AND superseded = 0"),
    reports: await count("SELECT COUNT(*) AS n FROM reports"),
    settings: await settings(env),
    reasons: REASONS,
    answers: ANSWERS,
    salt: Boolean(env.RATE_SALT),
  };
}

/** Setups waiting for a decision, the ones with most behind them first, and rules people doubt. */
async function queue(env) {
  const { adoptPoints } = await settings(env);
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
      // Enough points, but another rule is in use, this one was turned down or everyone sent it from one
      // network: only the developer decides.
      blocked: (Boolean(why) || s.networks < 2) && s.persons >= 2 && s.points >= adoptPoints,
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
  await env.DB.batch([
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('adopt_points', ?)", String(body.adoptPoints)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('reward_months', ?)", String(body.rewardMonths)),
  ]);
  // A lower bar may already be met.
  for (const g of await all(env, "SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending' LIMIT 200")) {
    await settle(env, g.company_id, g.grp, now);
  }
  await audit(env, now, "settings", "", `${body.adoptPoints}/${body.rewardMonths}`);
  return json(await settings(env));
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

// ---- the developer app's files [ADMIN-01] ----

const ADMIN_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
  "manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

function adminFile(path) {
  const png = ICONS[path];
  if (png) {
    return new Response(Uint8Array.from(atob(png), (c) => c.charCodeAt(0)), {
      headers: { "content-type": "image/png", "cache-control": "public, max-age=86400", "x-content-type-options": "nosniff" },
    });
  }
  const files = {
    "/admin/": [ADMIN_HTML, "text/html; charset=utf-8"],
    "/admin/app.js": [`(${adminApp})();\n`, "text/javascript; charset=utf-8"],
    "/admin/sw.js": [`(${adminWorker})();\n`, "text/javascript; charset=utf-8"],
    "/admin/app.css": [ADMIN_CSS, "text/css; charset=utf-8"],
    "/admin/manifest.webmanifest": [JSON.stringify(MANIFEST), "application/manifest+json; charset=utf-8"],
    "/admin/icon.svg": [ICON_SVG, "image/svg+xml"],
  };
  const file = files[path];
  if (!file) return new Response("Not found", { status: 404 });
  return new Response(file[0], {
    headers: {
      "content-type": file[1],
      "cache-control": "no-cache",
      "content-security-policy": ADMIN_CSP,
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      "x-robots-tag": "noindex, nofollow",
      "permissions-policy": "camera=(), microphone=(), geolocation=()",
    },
  });
}

const MANIFEST = {
  name: "밤낮 관리자",
  short_name: "밤낮 관리",
  description: "회사 정보 제보를 검토하고 관리해요",
  lang: "ko",
  start_url: "/admin/",
  scope: "/admin/",
  display: "standalone",
  background_color: "#15192B",
  theme_color: "#15192B",
  icons: [
    { src: "/admin/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
    { src: "/admin/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
    { src: "/admin/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    { src: "/admin/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
  ],
};

const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="112" fill="#15192B"/>` +
  `<mask id="m"><rect width="512" height="512" fill="#fff"/><circle cx="302" cy="220" r="120" fill="#000"/></mask>` +
  `<circle cx="241" cy="271" r="138" fill="#C2A36B" mask="url(#m)"/><circle cx="348" cy="169" r="18" fill="#C2A36B"/></svg>`;

const ADMIN_HTML = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<meta name="theme-color" content="#15192B">
<meta name="color-scheme" content="light dark">
<title>밤낮 관리자</title>
<link rel="manifest" href="/admin/manifest.webmanifest">
<link rel="icon" href="/admin/icon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/admin/icon-192.png">
<link rel="stylesheet" href="/admin/app.css">
<script src="/admin/app.js" defer></script>
</head>
<body>
<div id="app"><div class="center"><div class="spinner" role="progressbar" aria-label="불러오는 중"></div></div></div>
<div id="toast" class="toast" role="status" aria-live="polite"></div>
<dialog id="dialog"></dialog>
<noscript>이 화면은 자바스크립트가 켜져 있어야 열려요.</noscript>
</body>
</html>
`;

const ADMIN_CSS = `
:root{--bg:#F7F4EE;--card:#fff;--fg:#15192B;--muted:#5d6176;--line:#e4dfd4;--soft:#f0ebe1;--accent:#2F5BEA;--on-accent:#fff;--gold:#C2A36B;
--ok:#1b7a47;--ok-bg:#e2f3e9;--warn:#8f5600;--warn-bg:#fcefd6;--bad:#c0392b;--bad-bg:#fbe6e3;--shadow:0 1px 2px rgba(21,25,43,.05),0 6px 20px rgba(21,25,43,.06)}
@media (prefers-color-scheme:dark){:root{--bg:#0f1220;--card:#191e31;--fg:#f2ece1;--muted:#a3a6b6;--line:#2a3048;--soft:#222842;--accent:#8ea8ff;
--on-accent:#0c1022;--ok:#7fd6a5;--ok-bg:#14322a;--warn:#f2c374;--warn-bg:#3a2d12;--bad:#ff8f84;--bad-bg:#3b1c1b;--shadow:none}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;color-scheme:light dark}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,-apple-system,"Noto Sans KR","Apple SD Gothic Neo","Malgun Gothic",sans-serif;
-webkit-tap-highlight-color:transparent;word-break:keep-all;overflow-wrap:anywhere}
button,input,textarea{font:inherit;color:inherit}
h1,h2,h3{margin:0;line-height:1.3}
svg{fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.top{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 90%,transparent);backdrop-filter:blur(12px);
-webkit-backdrop-filter:blur(12px);border-bottom:1px solid var(--line);padding-top:env(safe-area-inset-top)}
.bar{display:flex;align-items:center;gap:8px;max-width:760px;margin:0 auto;padding:8px 10px;min-height:60px}
.bar h1{flex:1;font-size:19px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mark{width:34px;height:34px;margin:0 6px;border-radius:10px}
.progress{height:2px;overflow:hidden}
.progress.on{background:linear-gradient(90deg,transparent,var(--accent),transparent) no-repeat;background-size:50% 100%;animation:slide 1s linear infinite}
@keyframes slide{from{background-position:-50% 0}to{background-position:150% 0}}
.main{max-width:760px;margin:0 auto;padding:14px 16px calc(100px + env(safe-area-inset-bottom))}
.tabs{position:fixed;left:0;right:0;bottom:0;z-index:5;display:flex;justify-content:center;background:var(--card);border-top:1px solid var(--line);
padding:6px 6px calc(6px + env(safe-area-inset-bottom))}
.tab{position:relative;flex:1;max-width:160px;display:flex;flex-direction:column;align-items:center;gap:2px;padding:4px;border:0;background:none;
color:var(--muted);font-size:12px;font-weight:700;cursor:pointer}
.tab svg{width:24px;height:24px;padding:4px 18px;box-sizing:content-box;border-radius:999px;transition:background .2s}
.tab.on{color:var(--fg)}
.tab.on svg{background:var(--soft)}
.badge{position:absolute;top:0;left:calc(50% + 10px);min-width:20px;height:20px;padding:0 6px;border-radius:10px;background:var(--bad);color:#fff;
font-size:11px;line-height:20px;text-align:center}
.icon-btn{display:inline-grid;place-items:center;width:44px;height:44px;border:0;border-radius:14px;background:none;cursor:pointer;font-size:22px;flex:none}
.icon-btn svg{width:24px;height:24px}
.icon-btn:active,.btn:active,.row:active,.seg:active{transform:scale(.97)}
.card{background:var(--card);border:1px solid var(--line);border-radius:22px;padding:16px;margin:0 0 12px;box-shadow:var(--shadow)}
.card h2{font-size:16px;margin-bottom:6px}
.card h3{font-size:17px}
.head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}
.head>:first-child{flex:1;min-width:0}
.dept{font-weight:500;color:var(--muted);font-size:15px}
.ver{font-size:13px;color:var(--muted);font-weight:700}
.meta{margin:2px 0 0;font-size:13px;color:var(--muted)}
.gist{margin:10px 0 0;font-size:14px}
.hint{margin:2px 0 12px;font-size:14px;color:var(--muted)}
.section{font-size:15px;margin:24px 4px 10px;color:var(--muted)}
.pill{flex:none;display:inline-block;padding:4px 10px;border-radius:999px;background:var(--soft);color:var(--muted);font-size:12px;font-weight:800;white-space:nowrap}
.pill.warn{background:var(--warn-bg);color:var(--warn)}
.pill.ok{background:var(--ok-bg);color:var(--ok)}
.pill.bad{background:var(--bad-bg);color:var(--bad)}
.score{display:flex;align-items:center;gap:10px;margin:14px 0 4px}
.score b{font-size:14px;white-space:nowrap}
meter{flex:1;height:10px;-webkit-appearance:none;appearance:none;border:0;border-radius:99px;background:var(--soft);overflow:hidden}
meter::-webkit-meter-bar{height:10px;border:0;border-radius:99px;background:var(--soft)}
meter::-webkit-meter-optimum-value,meter::-webkit-meter-suboptimum-value,meter::-webkit-meter-even-less-good-value{background:var(--gold);border-radius:99px}
meter::-moz-meter-bar{background:var(--gold);border-radius:99px}
details{margin:12px 0 0;border-top:1px solid var(--line);padding-top:8px}
summary{cursor:pointer;font-size:14px;font-weight:700;color:var(--accent);padding:6px 0;list-style:none}
summary::-webkit-details-marker{display:none}
summary::after{content:" ▾"}
details[open]>summary::after{content:" ▴"}
.lines{margin:6px 0 0;display:grid;gap:6px}
.lines div{display:grid;grid-template-columns:7.5em 1fr;gap:10px;font-size:14px}
.lines dt{color:var(--muted)}
.lines dd{margin:0;word-break:keep-all;overflow-wrap:anywhere}
.diff{margin:12px 0 0;padding:10px 12px;border-radius:14px;background:var(--soft)}
.diff-title{margin:0 0 6px;font-size:13px;font-weight:800;color:var(--muted)}
.diff ul{margin:0;padding:0;list-style:none;display:grid;gap:4px}
.diff li{display:flex;gap:6px;font-size:14px}
.diff .sign{flex:none;width:16px;text-align:center;font-weight:900}
.diff .add .sign{color:var(--ok)}
.diff .del{color:var(--muted)}
.diff .del .sign{color:var(--bad)}
blockquote{margin:12px 0 0;padding:10px 12px;border-left:3px solid var(--gold);background:var(--soft);border-radius:4px 14px 14px 4px;font-size:14px;white-space:pre-wrap}
cite{display:block;margin-top:4px;font-style:normal;font-size:12px;color:var(--muted)}
.note{margin:12px 0 0;font-size:13px;padding:8px 10px;border-radius:12px;background:var(--soft)}
.note.warn{background:var(--warn-bg);color:var(--warn)}
.note.bad{background:var(--bad-bg);color:var(--bad)}
.actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px;flex-wrap:wrap}
.btn{min-height:44px;padding:0 18px;border-radius:14px;border:1px solid var(--line);background:var(--card);font-weight:800;font-size:15px;cursor:pointer;
transition:transform .1s,background .2s}
.btn.primary{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}
.btn.danger{color:var(--bad);border-color:color-mix(in srgb,var(--bad) 35%,var(--line))}
.btn.wide{width:100%}
.btn:disabled{opacity:.45;cursor:default;transform:none}
.link{border:0;background:none;padding:0;color:var(--accent);font-weight:800;cursor:pointer;text-decoration:underline}
input:not([type]),input[type=search],input[type=text],input[type=password]{width:100%;min-height:48px;padding:0 14px;border-radius:14px;border:1px solid var(--line);
background:var(--card);outline:none}
input:focus-visible,button:focus-visible,summary:focus-visible{outline:3px solid color-mix(in srgb,var(--accent) 45%,transparent);outline-offset:2px}
label{display:block;font-size:14px;font-weight:700;margin-bottom:6px}
.field{display:flex;gap:8px}
.field input{flex:1;min-width:0}
.inline{margin:0 0 12px}
.searchbar{display:flex;gap:8px;margin-bottom:12px}
.searchbar input{flex:1;min-width:0}
.rows{list-style:none;margin:0 0 12px;padding:0;background:var(--card);border:1px solid var(--line);border-radius:22px;overflow:hidden;box-shadow:var(--shadow)}
.rows li+li{border-top:1px solid var(--line)}
.row{display:flex;align-items:center;gap:10px;width:100%;min-height:64px;padding:12px 16px;border:0;background:none;text-align:left;cursor:pointer}
.row svg{flex:none;width:20px;height:20px;color:var(--muted)}
.row-main{flex:1;min-width:0;display:flex;flex-direction:column}
.row-main b{font-size:16px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.row-main small{font-size:13px;color:var(--muted)}
.memo{font-style:italic}
.reward{display:flex;align-items:center;gap:12px;padding:14px 16px;flex-wrap:wrap}
.reward .row-main{flex:1 1 14em}
.reward .actions{margin:0 0 0 auto}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 12px}
.chip{display:inline-flex;align-items:center;gap:2px;padding:4px 4px 4px 12px;border-radius:999px;background:var(--soft);font-size:14px;font-weight:700}
.chip button{width:30px;height:30px;border:0;border-radius:50%;background:none;cursor:pointer;font-size:18px;line-height:1;color:var(--muted)}
.switch{position:relative;display:flex;align-items:center;gap:12px;margin:12px 0 0;font-size:15px;font-weight:700;cursor:pointer}
.switch input{position:absolute;opacity:0;width:1px;height:1px}
.track{position:relative;flex:none;width:50px;height:30px;border-radius:99px;background:var(--line);transition:background .2s}
.track::after{content:"";position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.25);
transition:transform .2s}
.switch input:checked+.track{background:var(--accent)}
.switch input:checked+.track::after{transform:translateX(20px)}
.switch input:focus-visible+.track{outline:3px solid color-mix(in srgb,var(--accent) 45%,transparent);outline-offset:2px}
.rule{padding:12px 0;border-top:1px solid var(--line)}
.rule:first-of-type{border-top:0;padding-top:4px}
.rule.dim{opacity:.72}
.segmented{display:flex;gap:4px;padding:4px;border-radius:16px;background:var(--soft);margin-bottom:10px}
.seg{flex:1;min-height:42px;border:0;border-radius:12px;background:none;font-weight:800;font-size:14px;color:var(--muted);cursor:pointer}
.seg.on{background:var(--card);color:var(--fg);box-shadow:var(--shadow)}
.stepper{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:10px 0 2px}
.stepper>span{font-weight:800}
.stepper-ctl{display:flex;align-items:center;gap:2px;background:var(--soft);border-radius:16px;padding:2px}
.stepper output{min-width:68px;text-align:center;font-weight:900;font-size:17px}
.checks{list-style:none;margin:0 0 6px;padding:0;display:grid;gap:10px}
.checks li{display:flex;gap:10px;font-size:14px;align-items:flex-start}
.checks span{flex:none;display:inline-grid;place-items:center;width:22px;height:22px;border-radius:50%;font-size:13px;font-weight:900}
.checks .ok span{background:var(--ok-bg);color:var(--ok)}
.checks .todo span{background:var(--warn-bg);color:var(--warn)}
.empty{text-align:center;padding:48px 16px;color:var(--muted)}
.empty svg{width:52px;height:52px;color:var(--gold);stroke-width:1.5}
.empty h2{color:var(--fg);font-size:17px;margin:12px 0 4px}
.empty p{margin:0;font-size:14px}
.skeleton{height:150px;background:linear-gradient(90deg,var(--card),var(--soft),var(--card));background-size:200% 100%;animation:shine 1.2s ease-in-out infinite}
@keyframes shine{from{background-position:100% 0}to{background-position:-100% 0}}
.center{min-height:100vh;display:grid;place-items:center}
.spinner{width:32px;height:32px;border-radius:50%;border:3px solid var(--line);border-top-color:var(--gold);animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.login{max-width:420px;margin:0 auto;padding:calc(56px + env(safe-area-inset-top)) 16px 32px}
.brand{text-align:center;margin-bottom:24px}
.brand img{border-radius:22px;box-shadow:var(--shadow)}
.brand h1{font-size:24px;margin:14px 0 4px}
.brand p{margin:0;color:var(--muted)}
.error{min-height:1.5em;margin:8px 0;color:var(--bad);font-size:14px}
.toast{position:fixed;left:50%;bottom:calc(92px + env(safe-area-inset-bottom));transform:translate(-50%,16px);max-width:min(92vw,520px);padding:12px 18px;
border-radius:14px;background:var(--fg);color:var(--bg);font-size:14px;font-weight:700;opacity:0;pointer-events:none;transition:opacity .2s,transform .2s;
z-index:20;text-align:center}
.toast.show{opacity:1;transform:translate(-50%,0)}
dialog{border:0;padding:0;border-radius:26px;background:var(--card);color:var(--fg);width:min(92vw,440px);box-shadow:0 24px 64px rgba(0,0,0,.3)}
dialog::backdrop{background:rgba(10,12,22,.5)}
dialog form{padding:22px 20px 18px}
dialog h2{font-size:19px;margin-bottom:6px}
dialog p{margin:0 0 12px;color:var(--muted);font-size:14px}
.radios{display:grid;gap:8px;margin:6px 0}
.radio{display:flex;align-items:center;gap:10px;margin:0;padding:12px;border:1px solid var(--line);border-radius:14px;font-weight:600;cursor:pointer}
.radio input{flex:none;width:20px;height:20px;accent-color:var(--accent)}
.radio:has(input:checked){border-color:var(--accent);background:color-mix(in srgb,var(--accent) 8%,var(--card))}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
`;

/**
 * The developer app, sent to the browser as /admin/app.js. It runs there, not in the worker, so it
 * uses nothing from this file: server text such as the reasons for rejecting comes from the API.
 */
function adminApp() {
  const app = document.getElementById("app");
  const toastBox = document.getElementById("toast");
  const dialog = document.getElementById("dialog");
  const S = { authed: null, health: null, tab: "queue", overview: null, queue: null, list: null, query: "", company: null, rewards: null,
    rewardTab: "owed", draft: null, error: "", busy: 0, back: "companies" };
  const TABS = [
    ["queue", "검토", '<path d="M4 13h4l2 3h4l2-3h4"/><path d="M5.5 5h13L20 13v6H4v-6z"/>'],
    ["companies", "회사", '<path d="M4 20V5.5L12 3v17M12 9h8v11M3 20h18M7 8h2M7 12h2M7 16h2M15 13h2M15 17h2"/>'],
    ["rewards", "보상", '<path d="M4 11h16v9H4zM3 7.5h18V11H3zM12 7.5V20"/><path d="M12 7.5C10.5 4 7 4 7.5 6.5c.3 1 2 1 4.5 1M12 7.5C13.5 4 17 4 16.5 6.5c-.3 1-2 1-4.5 1"/>'],
    ["settings", "설정", '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>'],
  ];
  const ERRORS = {
    exists: "같은 이름의 회사가 이미 있어요",
    company: "회사 이름을 두 글자 이상, 40자 안으로 적어 주세요",
    not_found: "이미 처리됐거나 없는 항목이에요",
    forbidden: "다시 열어서 해 주세요",
    merge: "합칠 수 없는 회사예요",
    active: "이 부서에는 지금 쓰는 규칙이 있어요. 그 규칙을 먼저 내려 주세요",
    too_large: "내용이 너무 길어요",
    offline: "인터넷에 연결되지 않았어요",
    no_database: "데이터베이스가 연결되지 않았어요",
    server: "서버에 문제가 생겼어요. 잠시 뒤 다시 해 주세요",
  };
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const day = (iso) => (iso ? new Date(iso).toLocaleDateString("ko-KR", { month: "long", day: "numeric" }) : "");
  const moment = (iso) => (iso ? new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" }) : "");
  const icon = (paths) => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths}</svg>`;
  let pendingConfirm = null;
  let toastTimer = 0;
  let searchTimer = 0;

  async function api(path, method = "GET", body) {
    S.busy++;
    progress();
    try {
      let res;
      try {
        res = await fetch(`/api/admin${path}`, {
          method,
          credentials: "same-origin",
          headers: method === "GET" ? {} : { "content-type": "application/json", "x-admin": "1" },
          body: method === "GET" ? undefined : JSON.stringify(body || {}),
        });
      } catch {
        throw Object.assign(new Error("offline"), { data: {} });
      }
      const data = await res.json().catch(() => ({}));
      if (res.status === 401 && data.error === "login") {
        S.authed = false;
        render();
        throw Object.assign(new Error("login"), { quiet: true, data });
      }
      if (!res.ok) throw Object.assign(new Error(data.error || "server"), { data });
      return data;
    } finally {
      S.busy--;
      progress();
    }
  }

  async function health() {
    try {
      return await (await fetch("/api/admin/health")).json();
    } catch {
      return null;
    }
  }

  function progress() {
    document.getElementById("progress")?.classList.toggle("on", S.busy > 0);
  }

  function toast(text) {
    toastBox.textContent = text;
    toastBox.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastBox.classList.remove("show"), 2800);
  }

  function fail(e) {
    if (!e.quiet) toast(ERRORS[e.message] || `문제가 생겼어요 (${e.message})`);
  }

  async function load() {
    try {
      S.overview = await api("/overview");
      if (S.tab === "queue") S.queue = await api("/queue");
      else if (S.tab === "companies") {
        if (S.company) S.company = await api(`/companies/${S.company.id}`);
        else S.list = await api(`/companies?q=${encodeURIComponent(S.query)}`);
      } else if (S.tab === "rewards") S.rewards = await api(`/rewards?status=${S.rewardTab}`);
      else S.health = await health();
    } catch (e) {
      fail(e);
    }
    render();
  }

  // ---- screens ----

  function render() {
    if (S.authed === null) return;
    if (!S.authed) {
      app.innerHTML = loginView();
      document.getElementById("password")?.focus();
      return;
    }
    const inCompany = S.tab === "companies" && S.company;
    const title = inCompany ? S.company.name : TABS.find((t) => t[0] === S.tab)[1];
    const badge = (key) => (key === "queue" ? (S.overview?.pending ?? 0) + (S.overview?.flagged ?? 0) + (S.overview?.asks ?? 0)
      : key === "rewards" ? S.overview?.owed ?? 0 : 0);
    app.innerHTML = `
      <header class="top">
        <div class="bar">
          ${inCompany ? `<button class="icon-btn" data-act="back" aria-label="뒤로">${icon('<path d="M15 5l-7 7 7 7"/>')}</button>`
            : '<img class="mark" src="/admin/icon.svg" alt="">'}
          <h1>${esc(title)}</h1>
          <button class="icon-btn" data-act="refresh" aria-label="새로 고침">${icon('<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>')}</button>
        </div>
        <div id="progress" class="progress${S.busy ? " on" : ""}"></div>
      </header>
      <main class="main">${view()}</main>
      <nav class="tabs" aria-label="메뉴">${TABS.map(([key, label, paths]) => {
        const n = badge(key);
        return `<button class="tab${S.tab === key ? " on" : ""}" data-act="tab" data-tab="${key}"${S.tab === key ? ' aria-current="page"' : ""}>
          ${icon(paths)}<span>${label}</span>${n ? `<b class="badge">${n > 99 ? "99+" : n}</b>` : ""}</button>`;
      }).join("")}</nav>`;
  }

  function view() {
    if (S.tab === "queue") return queueView();
    if (S.tab === "companies") return S.company ? companyView(S.company) : companiesView();
    if (S.tab === "rewards") return rewardsView();
    return settingsView();
  }

  const empty = (title, text) => `<div class="empty">${icon('<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>')}
    <h2>${esc(title)}</h2>${text ? `<p>${esc(text)}</p>` : ""}</div>`;
  const skeleton = () => '<div class="card skeleton"></div><div class="card skeleton"></div>';
  const lines = (list) => `<dl class="lines">${list.map(([label, text]) => `<div><dt>${esc(label)}</dt><dd>${esc(text)}</dd></div>`).join("")}</dl>`;

  function healthList() {
    const h = S.health;
    if (!h) return "";
    const rows = [
      [h.database, "데이터베이스(D1)가 연결돼 있어요", "D1 데이터베이스를 변수 이름 DB로 연결해 주세요 (Worker → 설정 → 바인딩)"],
      [h.password, "관리자 비밀번호가 정해져 있어요", "변수 및 비밀에 ADMIN_PASSWORD를 비밀 유형으로, 10자 이상 넣어 주세요"],
      [h.salt, "RATE_SALT가 정해져 있어요", "변수 및 비밀에 RATE_SALT를 비밀 유형으로 넣어 주세요 (아무 긴 글자)"],
    ];
    return `<ul class="checks">${rows.map(([ok, good, todo]) =>
      `<li class="${ok ? "ok" : "todo"}"><span aria-hidden="true">${ok ? "✓" : "!"}</span>${esc(ok ? good : todo)}</li>`).join("")}</ul>`;
  }

  function loginView() {
    const h = S.health;
    const usable = !h || (h.database && h.password);
    return `<main class="login">
      <div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>밤낮 관리자</h1><p>회사 정보 제보를 검토하고 관리해요.</p></div>
      <form class="card" data-form="login">
        <label for="password">관리자 비밀번호</label>
        <input id="password" name="password" type="password" autocomplete="current-password" required${usable ? "" : " disabled"}>
        <p class="error" role="alert">${esc(S.error)}</p>
        <button class="btn primary wide" type="submit"${usable ? "" : " disabled"}>로그인</button>
      </form>
      ${h && !(h.database && h.password && h.salt) ? `<section class="card"><h2>서버 준비</h2>${healthList()}</section>` : ""}
    </main>`;
  }

  function queueView() {
    const q = S.queue;
    const points = S.overview?.settings?.adoptPoints ?? 3;
    const head = `<p class="hint">같은 규칙을 서로 다른 2명 이상이 보내 ${points}점이 되면 자동으로 채택돼요. 명세서로 맞춰 본 제보는 2점이에요.</p>`;
    if (!q) return head + skeleton();
    const asks = q.asks ?? [];
    if (!q.groups.length && !q.flagged.length && !asks.length) return head + empty("검토할 제보가 없어요", "새 제보가 오면 여기에 보여요.");
    // With many waiting, each card shows the gist and opens on a tap.
    return head + q.groups.map((g) => groupCard(g, !g.changes && q.groups.length <= 2)).join("") +
      (asks.length ? `<h2 class="section">앱에 없는 방식</h2><p class="hint">앱으로 설정하지 못한 방식이에요. 답을 고르면 보낸 분 앱에 보여요.</p>${asks.map(askCard).join("")}` : "") +
      (q.flagged.length ? `<h2 class="section">달라요가 있는 규칙</h2>${q.flagged.map(flagCard).join("")}` : "");
  }

  function askCard(a) {
    return `<article class="card">
      <div class="head"><h3>${esc(a.company.name)}${a.dept ? ` <span class="dept">${esc(a.dept)}</span>` : ""}</h3><span class="pill warn">앱에 없는 방식</span></div>
      <p class="meta">${a.verified ? "명세서 확인 · " : ""}${moment(a.at)}</p>
      <blockquote>${esc(a.note)}</blockquote>
      <details><summary>보낸 분이 맞춰 둔 설정</summary>${lines(a.lines)}</details>
      <div class="actions"><button class="btn primary" data-act="answer" data-id="${a.id}">답하기</button></div>
    </article>`;
  }

  function groupCard(g, open) {
    const verified = g.reports.filter((r) => r.verified).length;
    const kind = g.blocked && g.replaces ? '<span class="pill warn">새 버전 후보</span>'
      : g.replaces ? `<span class="pill">지금 v${g.replaces.version}와 달라요</span>` : '<span class="pill">새 규칙</span>';
    const gist = g.changes ? "" : g.lines.find(([label]) => label === "근무")?.[1];
    return `<article class="card">
      <div class="head"><h3>${esc(g.company.name)}${g.dept ? ` <span class="dept">${esc(g.dept)}</span>` : ""}</h3>${kind}</div>
      <p class="meta">${g.persons}명 · 제보 ${g.reports.length}건${verified ? ` · 명세서 확인 ${verified}건` : ""} · ${day(g.reports[0].at)}</p>
      ${gist ? `<p class="gist">${esc(gist)}</p>` : ""}
      <div class="score"><meter min="0" max="${g.threshold}" value="${Math.min(g.points, g.threshold)}" aria-label="채택 점수"></meter><b>${g.points}/${g.threshold}점</b></div>
      ${g.company.hidden ? '<p class="note warn">숨긴 회사예요. 채택해도 회사 찾기에 나오지 않아요.</p>' : ""}
      ${g.turnedDown ? `<p class="note warn">${g.turnedDown === "withdrawn" ? "전에 내린 규칙과 같은 설정이에요" : "전에 거절한 설정이에요"}. 자동으로 채택되지 않아요.</p>`
        : g.persons >= 2 && g.networks < 2 ? '<p class="note warn">모두 1시간 안에 같은 네트워크에서 보냈어요. 한 분이 여러 번 보냈을 수도 있어서 자동으로 채택하지 않았어요.</p>' : ""}
      ${g.changes ? changes(g.changes) : ""}
      <details${open ? " open" : ""}><summary>보낸 설정</summary>${lines(g.lines)}</details>
      ${g.reports.filter((r) => r.note).map((r) => `<blockquote>${esc(r.note)}<cite>${r.verified ? "명세서 확인 · " : ""}${moment(r.at)}</cite></blockquote>`).join("")}
      ${g.bad ? `<p class="note bad">달라요 ${g.bad}명</p>` : ""}
      <div class="actions">
        <button class="btn" data-act="reject" data-company="${g.company.id}" data-setup="${g.setup}">거절</button>
        <button class="btn primary" data-act="adopt" data-company="${g.company.id}" data-setup="${g.setup}">채택</button>
      </div>
    </article>`;
  }

  function changes(c) {
    const items = [...c.removed.map((l) => ["del", l]), ...c.added.map((l) => ["add", l])];
    if (!items.length) return '<p class="note">지금 쓰는 규칙과 내용이 같아요.</p>';
    return `<div class="diff"><p class="diff-title">지금 쓰는 규칙과 다른 점</p><ul>${items.map(([k, [label, text]]) =>
      `<li class="${k}"><span class="sign" aria-label="${k === "add" ? "새 값" : "지금 값"}">${k === "add" ? "+" : "−"}</span><span><b>${esc(label)}</b> ${esc(text)}</span></li>`).join("")}</ul></div>`;
  }

  function flagCard(f) {
    return `<article class="card">
      <h3>${esc(f.company.name)}${f.dept ? ` <span class="dept">${esc(f.dept)}</span>` : ""} <span class="ver">v${f.version}</span></h3>
      <p class="meta">맞았어요 ${f.good}명 · 달라요 ${f.bad}명${f.names ? ` · 이름이 이상해요 ${f.names}` : ""}${f.old ? ` · 예전 정보 ${f.old}` : ""}</p>
      <details><summary>규칙 내용</summary>${lines(f.lines)}</details>
      <div class="actions"><button class="btn" data-act="open-company" data-id="${f.company.id}">회사 열기</button>
        <button class="btn danger" data-act="withdraw-rule" data-id="${f.rule}">규칙 내리기</button></div>
    </article>`;
  }

  function companiesView() {
    return `<div class="searchbar"><input id="search" type="search" placeholder="회사 이름이나 다른 이름" value="${esc(S.query)}" autocomplete="off"
        enterkeyhint="search" aria-label="회사 찾기"><button class="btn primary" data-act="new-company">회사 만들기</button></div>
      <div id="company-list">${companyRows()}</div>`;
  }

  function companyRows() {
    const list = S.list?.companies;
    if (!list) return skeleton();
    if (!list.length) {
      return empty(S.query ? "찾는 회사가 없어요" : "아직 회사가 없어요", S.query ? "회사 만들기로 직접 만들 수 있어요." : "제보가 오면 회사가 저절로 생겨요.");
    }
    return `<ul class="rows">${list.map((c) => `<li><button class="row" data-act="open-company" data-id="${c.id}">
      <span class="row-main"><b>${esc(c.name)}</b><small>${[c.rules ? `규칙 ${c.rules}` : "규칙 없음", c.pending ? `대기 ${c.pending}` : "", `제보 ${c.reports}`,
        c.aliases ? `다른 이름 ${c.aliases}` : ""].filter(Boolean).join(" · ")}</small></span>
      ${c.mergedInto ? '<span class="pill">합쳐짐</span>' : c.hidden ? '<span class="pill">숨김</span>' : ""}${icon('<path d="M9 5l7 7-7 7"/>')}</button></li>`).join("")}</ul>`;
  }

  function companyView(c) {
    if (c.mergedInto) {
      return `<section class="card"><p>이 회사는 <button class="link" data-act="open-company" data-id="${c.mergedInto.id}">${esc(c.mergedInto.name)}</button>에 합쳐졌어요.</p></section>`;
    }
    return `
      <section class="card">
        <form class="inline" data-form="rename"><label for="name">이름</label>
          <div class="field"><input id="name" name="name" value="${esc(c.name)}" maxlength="40" required><button class="btn" type="submit">저장</button></div></form>
        <label class="switch"><input type="checkbox" data-change="hidden"${c.hidden ? " checked" : ""}><span class="track" aria-hidden="true"></span><span>회사 찾기에서 숨기기</span></label>
        <p class="meta">${day(c.createdAt)}에 생김${c.pending ? ` · 검토 대기 ${c.pending}` : ""}</p>
      </section>
      <section class="card">
        <h2>다른 이름</h2>
        <p class="hint">영문 이름, 줄임말, 예전 이름처럼 사람들이 찾을 만한 이름을 넣어 두면 회사 찾기에 잡혀요.</p>
        <div class="chips">${c.aliases.length ? c.aliases.map((a) => `<span class="chip">${esc(a)}<button data-act="remove-alias" data-name="${esc(a)}"
          aria-label="${esc(a)} 지우기">×</button></span>`).join("") : '<span class="meta">아직 없어요</span>'}</div>
        <form data-form="alias"><div class="field"><input name="name" placeholder="예: Bamnat Steel, 밤스틸" maxlength="40" required aria-label="다른 이름">
          <button class="btn" type="submit">추가</button></div></form>
      </section>
      <section class="card">
        <h2>규칙</h2>
        ${c.rules.length ? c.rules.map(ruleCard).join("") : '<p class="meta">채택된 규칙이 아직 없어요.</p>'}
      </section>
      <section class="card">
        <h2>다른 회사에 합치기</h2>
        <p class="hint">같은 회사가 다른 이름으로 두 번 생겼을 때 써요. 제보와 규칙이 모두 옮겨지고 이 이름은 다른 이름으로 남아요.</p>
        <form data-form="merge-search"><div class="field"><input name="q" placeholder="합칠 회사 이름" required aria-label="합칠 회사 이름">
          <button class="btn" type="submit">찾기</button></div></form>
        <div id="merge-results"></div>
      </section>`;
  }

  function ruleCard(r) {
    const [tone, label] = r.withdrawn ? ["bad", "내림"] : r.superseded ? ["", "이전 버전"] : ["ok", "쓰는 중"];
    const action = r.withdrawn ? `<button class="btn" data-act="restore-rule" data-id="${r.id}">다시 올리기</button>`
      : r.superseded ? "" : `<button class="btn danger" data-act="withdraw-rule" data-id="${r.id}">내리기</button>`;
    return `<div class="rule${r.withdrawn || r.superseded ? " dim" : ""}">
      <div class="head"><div><b>${esc(r.dept || "회사 전체")}</b> <span class="ver">v${r.version}</span>
        <p class="meta">${r.source === "auto" ? "자동 채택" : "직접 채택"} · ${day(r.adoptedAt)} · 맞았어요 ${r.ok} · 달라요 ${r.bad}</p></div>
        <span class="pill ${tone}">${label}</span></div>
      <details><summary>내용</summary>${lines(r.lines)}</details>
      ${action ? `<div class="actions">${action}</div>` : ""}
    </div>`;
  }

  function rewardsView() {
    const r = S.rewards;
    const counts = r?.counts ?? {};
    const segs = [["owed", "지급 대기"], ["paid", "지급함"], ["void", "취소"]].map(([key, label]) =>
      `<button class="seg${S.rewardTab === key ? " on" : ""}" data-act="reward-tab" data-tab="${key}" aria-pressed="${S.rewardTab === key}">${label}${counts[key] ? ` ${counts[key]}` : ""}</button>`).join("");
    const body = !r ? skeleton() : !r.rewards.length ? empty(S.rewardTab === "owed" ? "드릴 보상이 없어요" : "목록이 비어 있어요", "")
      : `<ul class="rows">${r.rewards.map((w) => `<li class="reward">
        <div class="row-main"><b>${esc(w.company.name)}${w.dept ? ` · ${esc(w.dept)}` : ""}</b>
          <small>프로 ${w.months}개월 · ${day(w.createdAt)} ${w.ask ? "앱에 없는 방식" : "채택"}${w.version ? ` · 규칙 v${w.version}` : ""}${w.paidAt ? ` · ${day(w.paidAt)} 지급` : ""}</small>
          ${w.memo ? `<small class="memo">${esc(w.memo)}</small>` : ""}</div>
        <div class="actions">${w.status === "owed" ? `<button class="btn" data-act="reward" data-id="${w.id}" data-status="void">취소</button>
          <button class="btn primary" data-act="reward" data-id="${w.id}" data-status="paid">지급함</button>`
          : `<button class="btn" data-act="reward" data-id="${w.id}" data-status="owed">대기로 되돌리기</button>`}</div>
      </li>`).join("")}</ul>`;
    return `<div class="segmented" role="group" aria-label="보상 상태">${segs}</div>
      <p class="hint">채택된 정보를 보낸 분들께 드릴 프로 이용 기간이에요. 유료 기능이 시작되면 앱에서 받게 할 예정이고, 스토어 리뷰와는 묶지 않아요.</p>${body}`;
  }

  function settingsView() {
    const s = S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3 };
    return `<section class="card">
        <h2>자동 채택</h2>
        ${stepper("adoptPoints", "채택 점수", s.adoptPoints, "점")}
        <p class="hint">같은 규칙을 서로 다른 2명 이상이 보내 이 점수가 되면 자동으로 채택해요. 명세서로 맞춰 본 제보는 2점, 나머지는 1점이에요.</p>
        ${stepper("rewardMonths", "채택 보상", s.rewardMonths, "개월")}
        <p class="hint">채택된 정보를 보낸 분께 드릴 프로 이용 기간이에요. 0개월이면 보상을 기록하지 않아요.</p>
        <button class="btn primary wide" data-act="save-settings"${S.draft ? "" : " disabled"}>저장</button>
      </section>
      <section class="card"><h2>서버 상태</h2>${healthList()}
        <p class="meta">회사 ${S.overview?.companies ?? 0} · 쓰는 규칙 ${S.overview?.rules ?? 0} · 제보 ${S.overview?.reports ?? 0}</p></section>
      <section class="card"><h2>휴대폰 홈 화면에 두기</h2>
        <p class="hint">크롬에서 이 화면을 연 채 메뉴(⋮) → 홈 화면에 추가를 누르면 앱처럼 열려요.</p></section>
      <button class="btn wide" data-act="logout">로그아웃</button>`;
  }

  function stepper(key, label, value, unit) {
    return `<div class="stepper"><span>${label}</span><div class="stepper-ctl">
      <button class="icon-btn" data-act="step" data-key="${key}" data-d="-1" aria-label="${label} 줄이기">−</button>
      <output aria-live="polite">${value}${unit}</output>
      <button class="icon-btn" data-act="step" data-key="${key}" data-d="1" aria-label="${label} 늘리기">+</button></div></div>`;
  }

  // ---- dialogs ----

  function openDialog(html) {
    dialog.innerHTML = html;
    dialog.showModal();
    dialog.querySelector("input:not([type=radio])")?.focus();
  }

  function confirmBox(title, text, label, then) {
    pendingConfirm = then;
    openDialog(`<form data-form="confirm"><h2>${esc(title)}</h2><p>${esc(text)}</p>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn danger" type="submit">${esc(label)}</button></div></form>`);
  }

  function openReject(company, setup) {
    const reasons = Object.entries(S.overview?.reasons ?? {});
    openDialog(`<form data-form="reject" data-company="${company}" data-setup="${setup}"><h2>거절할까요?</h2><p>보낸 분 앱에 이 이유가 보여요.</p>
      <div class="radios">${reasons.map(([key, text], i) => `<label class="radio"><input type="radio" name="reason" value="${key}"${i === 0 ? " checked" : ""}>
        <span>${esc(text)}</span></label>`).join("")}</div>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn danger" type="submit">거절하기</button></div></form>`);
  }

  function openAnswer(id) {
    const answers = Object.entries(S.overview?.answers ?? {});
    const months = S.overview?.settings?.rewardMonths ?? 0;
    openDialog(`<form data-form="answer" data-id="${id}"><h2>어떻게 답할까요?</h2><p>보낸 분 앱에 이 답이 보여요.</p>
      <div class="radios">${answers.map(([key, text], i) => `<label class="radio"><input type="radio" name="answer" value="${key}"${i === 0 ? " checked" : ""}>
        <span>${esc(text)}</span></label>`).join("")}</div>
      ${months ? `<label class="switch"><input type="checkbox" name="reward" checked><span class="track" aria-hidden="true"></span>
        <span>앱에 넣을 방식이면 프로 ${months}개월 보상 기록</span></label>` : ""}
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">답하기</button></div></form>`);
  }

  function openNewCompany() {
    openDialog(`<form data-form="new-company"><h2>회사 만들기</h2><p>제보가 오기 전에 이름과 다른 이름을 정해 둘 때 써요.</p>
      <label for="new-name">회사 이름</label><input id="new-name" name="name" maxlength="40" required>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">만들기</button></div></form>`);
  }

  function openPaid(id) {
    openDialog(`<form data-form="paid" data-id="${id}"><h2>지급했어요</h2><p>어떻게 드렸는지 적어 두면 나중에 찾기 쉬워요. 보낸 분의 이름이나 연락처는 적지 마세요.</p>
      <label for="memo">메모 (선택)</label><input id="memo" name="memo" maxlength="200" placeholder="예: 프로 3개월 쿠폰">
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">기록하기</button></div></form>`);
  }

  // ---- what taps do ----

  async function openCompany(id) {
    try {
      const c = await api(`/companies/${id}`);
      if (S.tab === "companies" && S.company) history.replaceState({ company: id }, "");
      else {
        // Back returns to the list it was opened from, the review queue included.
        S.back = S.tab;
        history.pushState({ company: id }, "");
      }
      S.tab = "companies";
      S.company = c;
      render();
      window.scrollTo(0, 0);
    } catch (e) {
      fail(e);
    }
  }

  async function setRule(id, withdrawn) {
    try {
      const c = await api(`/rules/${id}`, "POST", { withdrawn });
      if (S.company) S.company = c;
      toast(withdrawn ? "규칙을 내렸어요. 회사 찾기에서 바로 빠져요" : "규칙을 다시 올렸어요");
    } catch (e) {
      fail(e);
    }
    load();
  }

  async function setReward(id, status, memo) {
    try {
      await api(`/rewards/${id}`, "POST", memo === undefined ? { status } : { status, memo });
      toast(status === "paid" ? "지급을 기록했어요" : status === "void" ? "보상을 취소했어요" : "대기로 되돌렸어요");
    } catch (e) {
      fail(e);
    }
    load();
  }

  const ACTIONS = {
    tab: ({ tab }) => {
      if (S.company) history.back();
      S.tab = tab;
      S.company = null;
      render();
      window.scrollTo(0, 0);
      load();
    },
    refresh: () => load(),
    back: () => history.back(),
    close: () => dialog.close(),
    adopt: async ({ company, setup }) => {
      try {
        await api(`/groups/${company}/${setup}/adopt`, "POST");
        toast("채택했어요. 이제 회사 찾기에 나와요");
      } catch (e) {
        fail(e);
      }
      load();
    },
    reject: ({ company, setup }) => openReject(company, setup),
    answer: ({ id }) => openAnswer(id),
    "open-company": ({ id }) => openCompany(id),
    "new-company": () => openNewCompany(),
    "remove-alias": async ({ name }) => {
      try {
        S.company = await api(`/companies/${S.company.id}/aliases/remove`, "POST", { name });
        render();
      } catch (e) {
        fail(e);
      }
    },
    "withdraw-rule": ({ id }) => confirmBox("이 규칙을 내릴까요?", "회사 찾기에서 바로 빠져요. 나중에 다시 올릴 수 있어요.", "내리기", () => setRule(id, true)),
    "restore-rule": ({ id }) => setRule(id, false),
    "merge-into": ({ id, name }) => confirmBox(`${S.company.name}을 ${name}에 합칠까요?`, "제보와 규칙이 모두 옮겨지고 되돌릴 수 없어요.", "합치기", async () => {
      S.company = await api(`/companies/${S.company.id}/merge`, "POST", { into: id });
      history.replaceState({ company: S.company.id }, "");
      toast("합쳤어요");
      render();
    }),
    "reward-tab": ({ tab }) => {
      S.rewardTab = tab;
      S.rewards = null;
      render();
      load();
    },
    reward: ({ id, status }) => (status === "paid" ? openPaid(id) : setReward(id, status)),
    step: ({ key, d }) => {
      const s = { ...(S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3 }) };
      const [lo, hi] = key === "adoptPoints" ? [2, 20] : [0, 24];
      s[key] = Math.min(hi, Math.max(lo, s[key] + Number(d)));
      S.draft = s;
      render();
    },
    "save-settings": async () => {
      try {
        S.overview.settings = await api("/settings", "POST", S.draft);
        S.draft = null;
        toast("저장했어요");
      } catch (e) {
        fail(e);
      }
      render();
    },
    logout: async () => {
      try {
        await api("/logout", "POST");
      } catch {
        // Signed out either way.
      }
      S.authed = false;
      S.error = "";
      render();
    },
  };

  const FORMS = {
    login: async (form) => {
      S.error = "";
      try {
        await api("/login", "POST", { password: new FormData(form).get("password") });
        S.authed = true;
        render();
        load();
      } catch (e) {
        const left = e.data?.left;
        S.error = e.message === "wrong" ? `비밀번호가 달라요. ${left}번 더 틀리면 1시간 동안 잠겨요.`
          : e.message === "locked" ? "여러 번 틀려서 1시간 동안 잠겼어요. 나중에 다시 해 주세요."
          : e.message === "not_set" ? "관리자 비밀번호가 아직 없어요. 아래 서버 준비를 봐 주세요."
          : ERRORS[e.message] || "로그인하지 못했어요. 잠시 뒤 다시 해 주세요.";
        render();
      }
    },
    confirm: async () => {
      const then = pendingConfirm;
      pendingConfirm = null;
      dialog.close();
      try {
        await then?.();
      } catch (e) {
        fail(e);
      }
    },
    reject: async (form) => {
      const reason = new FormData(form).get("reason");
      dialog.close();
      try {
        await api(`/groups/${form.dataset.company}/${form.dataset.setup}/reject`, "POST", { reason });
        toast("거절했어요. 보낸 분 앱에 이유가 보여요");
      } catch (e) {
        fail(e);
      }
      load();
    },
    answer: async (form) => {
      const data = new FormData(form);
      dialog.close();
      try {
        const r = await api(`/asks/${form.dataset.id}`, "POST", { answer: data.get("answer"), reward: data.get("reward") === "on" });
        toast(r.reward ? "답했어요. 보상 탭에 지급 대기로 올렸어요" : "답했어요. 보낸 분 앱에 보여요");
      } catch (e) {
        fail(e);
      }
      load();
    },
    "new-company": async (form) => {
      try {
        const c = await api("/companies", "POST", { name: new FormData(form).get("name") });
        dialog.close();
        toast("만들었어요");
        openCompany(c.id);
      } catch (e) {
        fail(e);
      }
    },
    paid: (form) => {
      dialog.close();
      setReward(form.dataset.id, "paid", new FormData(form).get("memo"));
    },
    rename: async (form) => {
      try {
        S.company = await api(`/companies/${S.company.id}`, "POST", { name: new FormData(form).get("name") });
        toast("이름을 바꿨어요. 예전 이름으로도 찾아져요");
        render();
      } catch (e) {
        fail(e);
      }
    },
    alias: async (form) => {
      try {
        S.company = await api(`/companies/${S.company.id}/aliases`, "POST", { name: new FormData(form).get("name") });
        render();
      } catch (e) {
        fail(e);
      }
    },
    "merge-search": async (form) => {
      const box = document.getElementById("merge-results");
      try {
        const r = await api(`/companies?q=${encodeURIComponent(new FormData(form).get("q"))}`);
        const found = r.companies.filter((c) => c.id !== S.company.id && !c.mergedInto);
        box.innerHTML = found.length ? `<ul class="rows">${found.map((c) => `<li><button class="row" data-act="merge-into" data-id="${c.id}" data-name="${esc(c.name)}">
          <span class="row-main"><b>${esc(c.name)}</b><small>규칙 ${c.rules} · 제보 ${c.reports}</small></span><span class="pill">여기로 합치기</span></button></li>`).join("")}</ul>`
          : '<p class="meta">찾는 회사가 없어요.</p>';
      } catch (e) {
        fail(e);
      }
    },
  };

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el || !ACTIONS[el.dataset.act]) return;
    e.preventDefault();
    ACTIONS[el.dataset.act](el.dataset, el);
  });
  document.addEventListener("submit", (e) => {
    const form = e.target.closest("form[data-form]");
    if (!form) return;
    e.preventDefault();
    FORMS[form.dataset.form]?.(form);
  });
  document.addEventListener("change", async (e) => {
    if (e.target.dataset?.change !== "hidden" || !S.company) return;
    try {
      S.company = await api(`/companies/${S.company.id}`, "POST", { hidden: e.target.checked });
      toast(S.company.hidden ? "회사 찾기에서 숨겼어요" : "다시 회사 찾기에 나와요");
    } catch (err) {
      fail(err);
    }
    render();
  });
  document.addEventListener("input", (e) => {
    if (e.target.id !== "search") return;
    S.query = e.target.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
      try {
        S.list = await api(`/companies?q=${encodeURIComponent(S.query)}`);
      } catch (err) {
        fail(err);
      }
      const box = document.getElementById("company-list");
      if (box) box.innerHTML = companyRows();
    }, 250);
  });
  dialog.addEventListener("click", (e) => {
    // A tap outside the box closes it.
    if (e.target === dialog) dialog.close();
  });
  window.addEventListener("popstate", () => {
    if (!S.company) return;
    S.company = null;
    S.tab = S.back;
    render();
    load();
  });

  (async () => {
    if ("serviceWorker" in navigator) navigator.serviceWorker.register("/admin/sw.js").catch(() => {});
    S.health = await health();
    try {
      S.overview = await api("/overview");
      S.authed = true;
    } catch {
      S.authed = false;
    }
    render();
    if (S.authed) load();
  })();
}

/** The developer app's service worker: the newest files from the network, saved copies without one. */
function adminWorker() {
  const CACHE = "bamnat-admin-1";
  const SHELL = ["/admin/", "/admin/app.js", "/admin/app.css", "/admin/icon.svg", "/admin/manifest.webmanifest"];
  self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
  });
  self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()));
  });
  self.addEventListener("fetch", (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== "GET" || url.origin !== self.location.origin || !url.pathname.startsWith("/admin/")) return;
    event.respondWith(fetch(event.request).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(event.request, copy));
      }
      return res;
    }).catch(() => caches.match(event.request)));
  });
}

const ICONS = {
  "/admin/icon-192.png": "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAMAAABlApw1AAAASFBMVEXOrXDMqm/Hp23Do2vCo2vBo2vBomvAoWq/oWq8nmmojmF/bVJHQT0gIi8YGywXGywWGisWGSsVGSsUGSsSFyoNEygKECcIDyast4RLAAAGVklEQVR42u2c23LrKgxAAbtgc68N+P//9EC6mzTNpYCJHc8RT57OtNVCEkICgaaDDwQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAewFYc2AAq83kw3xQAKutD2FWUjl7QIAo/eKUFByLbeVvAmD1HLwSnCKM5GKPZkJWu8VI3iHcj1gs+mhOHMWPkx+lH9iAuTfl/Fqb/QDMFMUfERlY31MyFjuwjb6zLH4y+wBY7WeZxKd9HIzIUGhAxkffEULO3uwBYOZF8m/x+wEVO4CO4ncIIcJlMNsDaK8Fwf/Ej/PP9WwL5z+qjw7DwAiuJqgGsGZRHFHW/xsMlxqQdWokw9cv91hWWhGq9t4gKR6+xe/jClQ6hzoI9P0Hov7cphowzgnUnae/p31XOoUnBdCzAkmlCuoAtNMcsfN/Twoo9uDoAeQyAxVLwAqAk/wX86mbwAiAdwK4I39FDE4aoLsA3MofLag4hqXBLzZEO7WVD5hb+Wl5DPi1Cg2Ie7vNKmRv5a/Uv50cR2OyIjqkbdRGccB68Vv+qIGqNTAupHEnQhkjaKyNY8UAerknf40FnaxxFpxgMgrla/MIVGq3P5eOiwWFuv9vpzBJKdXiNtqNXkfPC4AMtROoJx+80/V5aBmA8Rz/NqBkQ8rXS2CMWZNGozIHkOhW/moX2Dwnvm9AMQwLb6YjANxbgdbsArYGiJuXru/vAdT78MYAdz045WKHMKHr7e8hAfhdANqNG9dz6wCuE6irVfQgAEHc9YCjAKQY0NEDAzyIAUfSwH0XPgqA9ap/MI6xCv3MXw8ZBx4FgaMAPF6DDrIXMkE+VMAhdqPPXOAQ+YDx4rEGDpCR2Vlz8sgF1ubEmwA88+GdvRjlWdATH15TF9oMIDwF2NUJsgAe7+RW1UbfB2DPSJDpA+KZCe1qQ5kA/ClA7QnN+wDUnJG9FUD9Me+7AAx4LzduBFBxUv9eADV3JbaMA+F5HKi7rfJGgazyvtB7AewVjlvsRi9L6Q5GlAtAPv4E2KfClZ3QPMvILivR9ktpXk786Tj524Z2cYMGVYmfVoTl1gS5ADIPoN+cILeskuPFuxBkl9d5hhefCezbAeQ6wRdBmMy7AfxRl7gioEi4mus/L71yZmeVaUORgCGuys1Iu/mVPpBvQ6eINsrgipRgzKK0eyHAw2Pi+wQdFqrAE6z2QQr3Sg1MU14wvpjRKJ03Ok98F5RAdetvNkBuLDsrgSAufZj/9AVj/KJEj/hr40Duhu6HEgZCuDRLVMNDBmv0HELqwqmuCuSbUFZWc50gMIx49IXgrb69FxeFN84vWgqGyFB9VpsPUKyChBANiQmp3BL8bIzW5jRS59VnFD6cev9wN9D6fWzBhaeilfRiSB1GlEeIuNAskSON+OGdkkl6RBhblQsVANhpLlmIfqghMuCe89Rw9TXiFx8JitJ/9RANqHoLWHTpL38/ccPAeoIRQjiN9BUNhw7fPSAM13bQFN4bNTVGdAkNbBjY10gftM0BSRmAU6yjfeuxKhMtu3pcGs0yd07c1JfECm+vrzKiR9d1qPKbNYPa2d6/PFo/GFqVhBY3QHg1dqytA6yq5xW3oKQb7B+0ofxxBbVbApyiQU/bya/dqgS6po9saUawXv66Tr5WBCf5V1aR6nopIwFlbyF/bTdr9ORueAf5a/uJoyeP6+IBo0g0kL+6o1unhvSh3hFivim8a1DAq+6p196KajOiqXK02BYl1PpXDcwc5IAYq5p+xFWj87QVzzJYE5TApNiOWJ96P12jIvyqlz20Cz9exsguVXRCtTGf1QCTMWEWYz7CB6OYcLm4dmcga1+30f9eh6EZDDG7T6+QeN/yAGT9+0LpfR7JybnC8CgjHnqMRiF9ME1PPxq88JRqs+6rxhPFZDcUH+mnHUZ9qtL51s+gNXljy6au4FRlS6UeEk3lqvzQEYzwGKV3izPNz55avXJmohqCTcW2sTsXgPCpABRlF1L5xdtXHP61e2fu0+jZh3AqGQrBT+NUi1PaL8GZFx1dtn3pz2g9uUsN1Pv05WajzcsOXtu/tWi/qs+XOrT9nF444LFIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPi/AfwHwjw/dxGi3n0AAAAASUVORK5CYII=",
  "/admin/icon-512.png": "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",
};
