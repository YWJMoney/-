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
// GET    /api/inquiries/public    모두의 문의: questions sent as public, with the developer's answers
// POST   /api/inquiries/:id/same  나도 그래요; /flag 신고: three people hide a question until the developer looks
// GET    /admin/                  the developer app, installable on a phone [ADMIN-01][ADMIN-03]
// GET    /admin/join#<invite>     an invited admin installs the app and makes an account [ADMIN-04]
// *      /api/admin/*             behind a sign-in: the owner's password (secret ADMIN_PASSWORD), or an
//                                 admin account the owner invited by email and approved, with only the
//                                 permissions the owner gave it
// *      /api/admin/dev...        개발사항 gathered from questions, each with its own history [ADMIN-10][ADMIN-11]
// *      /api/admin/templates...  자주 쓰는 답 shared by admins; inquiries also take 내가 맡기 and notes [ADMIN-12]
// GET    /api/config              the 정식 출시 switch and what goes with it, for every app once a day [MON-02]
// GET    /api/passes              header X-Device: this install's 프로 passes and admin phone link [MON-08][MON-09]
// POST   /api/passes/code         a recovery code for the backup; /claim moves passes to a new phone with it
// POST   /api/admin-link          a six-digit code from the developer app ties this phone to that admin
// POST   /api/purchases           a Play purchase token, checked with Play when PLAY_SERVICE_ACCOUNT is set [MON-03]
// POST   /api/consents            the user's answer before a free trial turns paid (전자상거래법 제13조 6항) [MON-06]
// POST   /api/events              [STAT-01] counts of what consenting phones did each day, no install id
// GET    /api/plaza/news?since=   [COMM-10] header X-Device: my rule changed, my rule or proposal adopted
// GET    /api/inquiries/:id/photos/:n  [SRV-06] header X-Device: a photo sent with one's own question
//
// A report carries no pay amounts, names or contacts; one that looks like it does is refused, not
// cleaned. No IP address is stored: limits and "the same network within an hour" use hashes salted
// with the secret RATE_SALT, cleared within two hours; what stays is a random mark shared by sends
// from one network within the hour. The app's install id is kept only as a hash, so a sender counts
// once and can take a report back.

import { adminFile, adminBuild } from "./admin-app.js";
import { pushReady, sendPush } from "./push.js";

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
  // [COMM-08] 운영 규칙 3항: a post the flags hid is looked at within 30 days (정보통신망법 제44조의2 제4항);
  // the home screen warns a week before.
  hiddenReviewDays: 30,
  loginFailures: 5,
  lockMs: 60 * 60 * 1000,
  sessionMs: 7 * 24 * 60 * 60 * 1000,
  // [ADMIN-15] A wrong password answers 150 ms later for each earlier miss; a sign-in in use runs on
  // a week at a time, for at most 30 days from the password.
  loginDelayMs: 150,
  sessionMaxMs: 30 * 24 * 60 * 60 * 1000,
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
const DEFAULTS = { adoptPoints: 3, rewardMonths: 3, adoptAgree: 75, adoptPersons: 3, amendPersons: 3, autoReward: 1, rewardCapMonths: 3, rewardCapDays: 90 };

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
  // [ADMIN-10] 개발사항: what admins gather from questions for the developer. Adding, changing and deleting
  // is "dev"; setting 개발중·완료·반려 is its own switch, the owner's until given.
  dev: "개발사항",
  devStatus: "개발사항 처리",
  companies: "회사·규칙",
  rewards: "보상",
  settings: "채택 설정",
  // [COMM-06] The talk on rule sets: what people flagged, hiding, showing again and muting a writer.
  community: "토론",
  // [PLZ-06] Taking an adopted rule down (내리기) is its own switch: only an admin given it, or the owner.
  takedown: "광장 규칙 내리기",
  // [SRV-04][ADMIN-14] 공지, 기능 스위치 and 이달 할 일; [ADMIN-13] the gate numbers; [SRV-05] reported share links.
  ops: "운영(공지·기능 스위치·이달 할 일)",
  metrics: "지표",
  shares: "공유 링크 신고",
  audit: "활동 기록",
};
const LEVELS = ["none", "view", "edit"];
const VIEW_ONLY = ["audit"];
/** [ADMIN-10] Areas that are a yes or no: a look at them means nothing, so "view" is stored as "none". */
const TOGGLES = ["devStatus", "takedown"];
/**
 * [ADMIN-10] What an account made before an area existed gets: every admin may add, change and delete
 * 개발사항, so the admin already invited keeps working without a visit to the admins screen. 처리 stays
 * the owner's until given.
 */
const SINCE = { dev: () => "edit" };
const PRESETS = {
  manager: { name: "운영 관리자", perms: { review: "edit", inquiries: "edit", dev: "edit", devStatus: "edit", companies: "edit", rewards: "edit", settings: "edit", community: "edit", takedown: "edit", ops: "edit", metrics: "view", shares: "edit", audit: "view" } },
  reviewer: { name: "검토 담당", perms: { review: "edit", inquiries: "none", dev: "edit", devStatus: "none", companies: "view", rewards: "none", settings: "none", community: "edit", takedown: "none", ops: "none", metrics: "none", shares: "edit", audit: "none" } },
  support: { name: "문의 담당", perms: { review: "view", inquiries: "edit", dev: "edit", devStatus: "none", companies: "view", rewards: "none", settings: "none", community: "view", takedown: "none", ops: "view", metrics: "none", shares: "view", audit: "none" } },
  viewer: { name: "보기 전용", perms: { review: "view", inquiries: "view", dev: "view", devStatus: "none", companies: "view", rewards: "view", settings: "view", community: "view", takedown: "none", ops: "view", metrics: "view", shares: "view", audit: "view" } },
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
    ctx.waitUntil(ready(env).then(() => prune(env, Date.now())).then(() => stopUnconsentedTrials(env, Date.now())));
  },
};

/**
 * Deletes what the server only keeps for a while: network hashes after two hours, spent limits, sign-ins,
 * invites and codes, questions with nothing new for 180 days, hidden posts after 90 days, ended 쓰기 막기
 * and the admin log after a year, usage totals after 400 days and a closed question's photos after 90.
 */
export async function prune(env, now) {
  await env.DB.batch([
    stmt(env, "UPDATE reports SET net = NULL WHERE net IS NOT NULL AND created_at < ?", now - 2 * HOUR),
    stmt(env, "UPDATE votes SET net = NULL WHERE net IS NOT NULL AND at < ?", now - 2 * HOUR),
    stmt(env, "UPDATE rates SET net = NULL WHERE net IS NOT NULL AND created_at < ?", now - 2 * HOUR),
    stmt(env, "UPDATE rate_votes SET net = NULL WHERE net IS NOT NULL AND at < ?", now - 2 * HOUR),
    stmt(env, "DELETE FROM counters WHERE expires < ?", now),
    stmt(env, "DELETE FROM admin_sessions WHERE expires < ?", now),
    stmt(env, "DELETE FROM invites WHERE used_at IS NULL AND expires < ?", now),
    stmt(env, "DELETE FROM codes WHERE expires < ?", now),
    ...dropInquiries(env, "updated_at < ?", now - INQUIRY.keepMs),
    // [COMM-06] A hidden post nobody restored is gone after 90 days, and a 30-day 쓰기 막기 when it ends.
    stmt(env, "DELETE FROM post_flags WHERE post_id IN (SELECT id FROM posts WHERE status = 'hidden' AND hidden_at < ?)", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM posts WHERE status = 'hidden' AND hidden_at < ?", now - LIMITS.hiddenPostDays * DAY),
    stmt(env, "DELETE FROM muted WHERE until < ?", now),
    stmt(env, "DELETE FROM audit WHERE at < ?", now - 365 * DAY),
    // [MON-03][MON-09] Spent link codes, and purchases 30 days after they ended.
    stmt(env, "DELETE FROM link_codes WHERE expires < ?", now),
    stmt(env, "DELETE FROM subs WHERE expiry IS NOT NULL AND expiry < ?", now - MONEY.subKeepMs),
    // [MON-06][MON-11] Consent answers are the record of a contract: kept 5 years (전자상거래법 시행령 제6조), then gone.
    stmt(env, "DELETE FROM consents WHERE at < ?", now - MONEY.consentKeepMs),
    // [STAT-01] Day totals after 400 days (a year and its month before, to compare); [SRV-06] a closed
    // question's photos 90 days after it closed, its words stay until the question goes.
    stmt(env, "DELETE FROM usage_days WHERE day < ?", kstDay(now - USAGE.keepDays * DAY)),
    stmt(env, "DELETE FROM usage_events WHERE day < ?", kstDay(now - USAGE.keepDays * DAY)),
    stmt(env, "DELETE FROM inquiry_photos WHERE inquiry_id IN (SELECT id FROM inquiries WHERE status = 'closed' AND updated_at < ?)",
      now - PHOTO.keepAfterCloseMs),
  ]);
}

/**
 * [ADMIN-11][ADMIN-12] Deletes the questions [where] picks with everything hung on them: messages, marks,
 * admins' notes and links to a 개발사항 (the item stays). The questions themselves go last.
 */
function dropInquiries(env, where, ...params) {
  const of = `inquiry_id IN (SELECT id FROM inquiries WHERE ${where})`;
  return [
    ...["inquiry_messages", "inquiry_marks", "inquiry_notes", "dev_links", "inquiry_photos"].map((t) => stmt(env, `DELETE FROM ${t} WHERE ${of}`, ...params)),
    stmt(env, `DELETE FROM inquiries WHERE ${where}`, ...params),
  ];
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  if (path === "/" || path === "/robots.txt") {
    return new Response("User-agent: *\nDisallow: /\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
  }
  if (path === "/admin") return Response.redirect(`${url.origin}/admin/`, 301);
  if (path.startsWith("/admin/")) return adminFile(path, request);
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
  m = path.match(/^\/api\/posts\/(p[A-Za-z0-9]{15})\/vote$/);
  if (m && method === "POST") return postVote(request, env, m[1]);
  if (path === "/api/uses" && (method === "POST" || method === "DELETE")) return useRule(request, env, method === "POST");
  if (path === "/api/nick" && method === "POST") return setNick(request, env);
  if (path === "/api/plazas" && method === "GET") return json(await plazas(env));
  m = path.match(/^\/api\/companies\/([A-Za-z0-9]{12})\/history$/);
  if (m && method === "GET") return history(env, m[1], url);
  if (path === "/api/rates" && method === "GET") return rates(request, env, url);
  if (path === "/api/rates" && method === "POST") return addRate(request, env);
  m = path.match(/^\/api\/rates\/([A-Za-z0-9]{22})(\/vote)?$/);
  if (m && method === "POST" && m[2]) return rateVote(request, env, m[1]);
  if (m && method === "DELETE" && !m[2]) return takeRateBack(request, env, m[1]);
  if (path === "/api/activity" && method === "GET") return activity(request, env);
  if (path === "/api/inquiries" || path.startsWith("/api/inquiries/")) return inquiries(request, env, path);
  if (path === "/api/config" && method === "GET") return json(await config(env));
  if (path === "/api/events" && method === "POST") return usage(request, env);
  if (path === "/api/plaza/news" && method === "GET") return plazaNews(request, env, url);
  if (path.startsWith("/api/passes") || path === "/api/admin-link" || path === "/api/purchases" || path === "/api/consents") {
    return money(request, env, path);
  }
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
  // [ADMIN-02] 나도 그래요 (kind same) and 신고 (kind flag) on a public question, once per install each.
  `CREATE TABLE IF NOT EXISTS inquiry_marks (inquiry_id TEXT NOT NULL, device TEXT NOT NULL, kind TEXT NOT NULL,
    reason TEXT, at INTEGER NOT NULL, PRIMARY KEY (inquiry_id, device, kind))`,
  "CREATE INDEX IF NOT EXISTS inquiry_marks_device ON inquiry_marks (device)",
  // [ADMIN-10] 개발사항: what admins gather from questions for the developer. A deleted one keeps its row
  // (deleted_at) so its history stays and the owner can bring it back. Who made or holds it is kept by
  // id and by name, so the name still reads after an account is removed.
  `CREATE TABLE IF NOT EXISTS dev_items (id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'todo', reason TEXT NOT NULL DEFAULT '', created_by TEXT NOT NULL, created_by_name TEXT NOT NULL,
    assignee TEXT, assignee_name TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)`,
  "CREATE INDEX IF NOT EXISTS dev_items_status ON dev_items (status, updated_at)",
  // [ADMIN-11] The questions behind an item; how many is how many people want it.
  `CREATE TABLE IF NOT EXISTS dev_links (item_id TEXT NOT NULL, inquiry_id TEXT NOT NULL, by TEXT NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (item_id, inquiry_id))`,
  "CREATE INDEX IF NOT EXISTS dev_links_inquiry ON dev_links (inquiry_id)",
  // [ADMIN-10] Every change to an item, by whom, before and after (detail is JSON); shown on the item.
  `CREATE TABLE IF NOT EXISTS dev_history (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL, at INTEGER NOT NULL,
    actor_id TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '{}')`,
  "CREATE INDEX IF NOT EXISTS dev_history_item ON dev_history (item_id, id)",
  // [ADMIN-12] Notes admins leave each other on a question; never shown in the app.
  `CREATE TABLE IF NOT EXISTS inquiry_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, inquiry_id TEXT NOT NULL, by TEXT NOT NULL,
    text TEXT NOT NULL, at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS inquiry_notes_inquiry ON inquiry_notes (inquiry_id, id)",
  // [ADMIN-12] Answers every admin can drop into a reply with one tap.
  `CREATE TABLE IF NOT EXISTS reply_templates (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL, text TEXT NOT NULL,
    by TEXT NOT NULL, at INTEGER NOT NULL)`,
  // Sign-ins before admin accounts kept only a token; they end once, and the owner signs in again.
  "DROP TABLE IF EXISTS sessions",
  // [MON-08] A recovery code per install, as a hash, to move its passes to a new phone once.
  "CREATE TABLE IF NOT EXISTS pass_codes (hash TEXT PRIMARY KEY, device TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)",
  // [MON-09] Codes the developer app shows an admin, and the phones tied to admins.
  "CREATE TABLE IF NOT EXISTS link_codes (code TEXT PRIMARY KEY, admin TEXT NOT NULL, admin_name TEXT NOT NULL, expires INTEGER NOT NULL)",
  `CREATE TABLE IF NOT EXISTS admin_devices (id TEXT PRIMARY KEY, device TEXT NOT NULL UNIQUE, admin TEXT NOT NULL, admin_name TEXT NOT NULL,
    status TEXT NOT NULL, created_at INTEGER NOT NULL, approved_at INTEGER, seen_at INTEGER)`,
  "CREATE INDEX IF NOT EXISTS admin_devices_admin ON admin_devices (admin)",
  // [MON-03] A subscription Play confirmed, kept while it lasts and 30 days after (consent and reward deferral need it).
  `CREATE TABLE IF NOT EXISTS subs (hash TEXT PRIMARY KEY, device TEXT NOT NULL, token TEXT NOT NULL, product TEXT NOT NULL,
    base_plan TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, expiry INTEGER, trial INTEGER NOT NULL DEFAULT 0,
    auto_renew INTEGER NOT NULL DEFAULT 0, consent_at INTEGER, stopped_at INTEGER, created_at INTEGER NOT NULL, checked_at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS subs_device ON subs (device)",
  "CREATE INDEX IF NOT EXISTS subs_trial ON subs (trial, auto_renew, expiry)",
  // [MON-06] Answers before a trial turns paid, kept as the law's evidence.
  `CREATE TABLE IF NOT EXISTS consents (id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, kind TEXT NOT NULL,
    detail TEXT NOT NULL, at INTEGER NOT NULL)`,
  "CREATE INDEX IF NOT EXISTS consents_device ON consents (device)",
  // [PLZ-01] 맞아요·달라요 on a post, once per install.
  `CREATE TABLE IF NOT EXISTS post_votes (post_id TEXT NOT NULL, device TEXT NOT NULL, ok INTEGER NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (post_id, device))`,
  "CREATE INDEX IF NOT EXISTS post_votes_device ON post_votes (device)",
  // [PLZ-08] Phones that use a rule set (applied it in the app), once each: 쓰는 사람.
  `CREATE TABLE IF NOT EXISTS uses (company_id TEXT NOT NULL, grp TEXT NOT NULL, device TEXT NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (company_id, grp, device))`,
  "CREATE INDEX IF NOT EXISTS uses_device ON uses (device)",
  // [PLZ-08] A name the user chose to be shown by on the plaza, instead of 참여자 N. Optional.
  "CREATE TABLE IF NOT EXISTS nicks (device TEXT PRIMARY KEY, nick TEXT NOT NULL, norm TEXT NOT NULL UNIQUE, at INTEGER NOT NULL)",
  // [PLZ-14] 임금표: market rates people share for a job and region (일당·시급·월급), never one person's pay slip.
  `CREATE TABLE IF NOT EXISTS rates (id TEXT PRIMARY KEY, job TEXT NOT NULL, region TEXT NOT NULL, trade TEXT NOT NULL,
    trade_norm TEXT NOT NULL, unit TEXT NOT NULL, year INTEGER NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '',
    device TEXT NOT NULL, cluster TEXT NOT NULL, net TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL, decided_at INTEGER,
    agree INTEGER NOT NULL DEFAULT 0, disagree INTEGER NOT NULL DEFAULT 0)`,
  "CREATE INDEX IF NOT EXISTS rates_key ON rates (job, region, trade_norm, unit, year)",
  "CREATE INDEX IF NOT EXISTS rates_device ON rates (device)",
  `CREATE TABLE IF NOT EXISTS rate_votes (rate_id TEXT NOT NULL, device TEXT NOT NULL, ok INTEGER NOT NULL, cluster TEXT NOT NULL,
    net TEXT, at INTEGER NOT NULL, PRIMARY KEY (rate_id, device))`,
  "CREATE INDEX IF NOT EXISTS rate_votes_device ON rate_votes (device)",
  // [STAT-01] What consenting phones sent, added up per day and app version: how many phones sent that
  // day, and per event how many of them did it (phones), how often (total) and how many did it within
  // the last 28 days (recent). No install id, address or time of day: nothing ties a row to a phone.
  "CREATE TABLE IF NOT EXISTS usage_days (day TEXT NOT NULL, app TEXT NOT NULL, phones INTEGER NOT NULL, PRIMARY KEY (day, app))",
  `CREATE TABLE IF NOT EXISTS usage_events (day TEXT NOT NULL, app TEXT NOT NULL, event TEXT NOT NULL, phones INTEGER NOT NULL,
    total INTEGER NOT NULL, recent INTEGER NOT NULL, PRIMARY KEY (day, app, event))`,
  // [SRV-06] Photos sent with a question (JPEG as base64, the metadata taken out), at most two.
  `CREATE TABLE IF NOT EXISTS inquiry_photos (inquiry_id TEXT NOT NULL, n INTEGER NOT NULL, data TEXT NOT NULL, bytes INTEGER NOT NULL,
    at INTEGER NOT NULL, PRIMARY KEY (inquiry_id, n))`,
  // H 1.23 시작
  // [ADMIN-15] The log is read by time and by what it is about (and, after COLUMNS, by who did it); rewards by status.
  "CREATE INDEX IF NOT EXISTS audit_at ON audit (at)",
  "CREATE INDEX IF NOT EXISTS audit_target ON audit (target)",
  "CREATE INDEX IF NOT EXISTS rewards_status ON rewards (status, created_at)",
  // [ADMIN-19] Browsers that asked for web push: one row per browser, by a hash of its push address.
  `CREATE TABLE IF NOT EXISTS push_subscriptions (id TEXT PRIMARY KEY, admin TEXT NOT NULL, endpoint TEXT NOT NULL, p256dh TEXT NOT NULL,
    auth TEXT NOT NULL, kinds TEXT NOT NULL, quiet_from INTEGER, quiet_to INTEGER, created_at INTEGER NOT NULL, sent_at INTEGER)`,
  "CREATE INDEX IF NOT EXISTS push_subscriptions_admin ON push_subscriptions (admin)",
  // H 1.23 끝
];

/** Columns added after a server was first deployed; adding one that is already there is skipped. */
const COLUMNS = [
  "ALTER TABLE audit ADD COLUMN actor TEXT NOT NULL DEFAULT ''",
  // [ADMIN-02] 모두의 문의: a question sent as public, why it is hidden, its 신고, whether the developer kept it, 나도 그래요.
  "ALTER TABLE inquiries ADD COLUMN public INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE inquiries ADD COLUMN hidden TEXT",
  "ALTER TABLE inquiries ADD COLUMN flags INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE inquiries ADD COLUMN kept INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE inquiries ADD COLUMN same INTEGER NOT NULL DEFAULT 0",
  // [ADMIN-12] 내가 맡기: the admin answering a question, by id and by name.
  "ALTER TABLE inquiries ADD COLUMN assignee TEXT",
  "ALTER TABLE inquiries ADD COLUMN assignee_name TEXT",
  // [INQ-03] The user's word on the answers: 1 도움이 됐어요, -1 아쉬워요.
  "ALTER TABLE inquiries ADD COLUMN rating INTEGER",
  // [INQ-02] The app version a finished 개발사항 went out in, for the asker's 반영됨 step.
  "ALTER TABLE dev_items ADD COLUMN released_in TEXT",
  // [MON-08] A reward Play gave as a later renewal of a running subscription, not as a pass.
  "ALTER TABLE rewards ADD COLUMN deferred INTEGER NOT NULL DEFAULT 0",
  // [PLZ-12] What kind of place it is (company, hospital, topic for rules not tied to one place), its job plaza and region.
  "ALTER TABLE companies ADD COLUMN kind TEXT NOT NULL DEFAULT 'company'",
  "ALTER TABLE companies ADD COLUMN job TEXT NOT NULL DEFAULT ''",
  "ALTER TABLE companies ADD COLUMN region TEXT NOT NULL DEFAULT ''",
  // [PLZ-02] A setup sent to change the rule in use (its id), and the post that proposed it.
  "ALTER TABLE reports ADD COLUMN amends TEXT",
  "ALTER TABLE reports ADD COLUMN post_id TEXT",
  // [PLZ-01][PLZ-05][PLZ-09] Replies, the kind of post, a structured change and its votes.
  "ALTER TABLE posts ADD COLUMN parent TEXT",
  "ALTER TABLE posts ADD COLUMN kind TEXT NOT NULL DEFAULT 'talk'",
  "ALTER TABLE posts ADD COLUMN change TEXT",
  "ALTER TABLE posts ADD COLUMN report_id TEXT",
  "ALTER TABLE posts ADD COLUMN agree INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE posts ADD COLUMN disagree INTEGER NOT NULL DEFAULT 0",
  // H 1.23 시작
  // [ADMIN-17] Who did it by id, so 내가 한 일 and one admin's activity survive a change of name.
  "ALTER TABLE audit ADD COLUMN actor_id TEXT NOT NULL DEFAULT ''",
  // [ADMIN-16] A saved answer's topic (문의 주제, '' for any) and how often it was sent.
  "ALTER TABLE reply_templates ADD COLUMN topic TEXT NOT NULL DEFAULT ''",
  "ALTER TABLE reply_templates ADD COLUMN uses INTEGER NOT NULL DEFAULT 0",
  // H 1.23 끝
];

/** Indexes on columns COLUMNS adds, so they are made after it. */
const LATE_INDEXES = [
  // H 1.23 시작
  "CREATE INDEX IF NOT EXISTS inquiries_assignee ON inquiries (assignee, status)",
  "CREATE INDEX IF NOT EXISTS audit_actor ON audit (actor_id, at)",
  // H 1.23 끝
];

const schemas = new WeakMap();

/** Changes whenever SCHEMA, COLUMNS or LATE_INDEXES does, so a server made by this code skips making tables again. */
const SCHEMA_VERSION = (() => {
  let h = 5381;
  for (const c of [...SCHEMA, ...COLUMNS, ...LATE_INDEXES].join("\n")) h = ((h * 33) ^ c.charCodeAt(0)) >>> 0;
  return h.toString(36);
})();

/**
 * Tables are made on first use, so setting up the server is binding an empty D1 database. A fresh copy of
 * the worker starts often (a quiet server is woken by each visit), so once the tables match this code it
 * only reads one setting instead of going over every table again.
 */
function ready(env) {
  let done = schemas.get(env.DB);
  if (!done) {
    done = env.DB.prepare("SELECT value FROM settings WHERE key = 'schema'").first("value").catch(() => null).then(async (made) => {
      if (made === SCHEMA_VERSION) return;
      await env.DB.batch(SCHEMA.map((sql) => env.DB.prepare(sql)));
      for (const sql of COLUMNS) {
        try {
          await env.DB.prepare(sql).run();
        } catch (e) {
          if (!/duplicate column/i.test(String(e?.message ?? e))) throw e;
        }
      }
      await env.DB.batch(LATE_INDEXES.map((sql) => env.DB.prepare(sql)));
      await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('schema', ?)").bind(SCHEMA_VERSION).run();
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

const SETTING_KEYS = "'adopt_points', 'reward_months', 'adopt_agree', 'adopt_persons', 'amend_persons', 'auto_reward', 'reward_cap_months', 'reward_cap_days'";

async function settings(env) {
  return readSettings(await all(env, `SELECT key, value FROM settings WHERE key IN (${SETTING_KEYS})`));
}

function readSettings(rows) {
  const read = (key, fallback) => {
    const n = Number(rows.find((r) => r.key === key)?.value);
    return Number.isInteger(n) ? n : fallback;
  };
  return { adoptPoints: read("adopt_points", DEFAULTS.adoptPoints), rewardMonths: read("reward_months", DEFAULTS.rewardMonths),
    adoptAgree: read("adopt_agree", DEFAULTS.adoptAgree),
    // [PLZ-03] How many different people adopt a rule on their own, and an amendment of one in use (결정 D32).
    adoptPersons: read("adopt_persons", DEFAULTS.adoptPersons), amendPersons: read("amend_persons", DEFAULTS.amendPersons),
    // [PLZ-03] Rewards go out as passes by themselves, up to a cap per phone (결정 D33).
    autoReward: read("auto_reward", DEFAULTS.autoReward), rewardCapMonths: read("reward_cap_months", DEFAULTS.rewardCapMonths),
    rewardCapDays: read("reward_cap_days", DEFAULTS.rewardCapDays) };
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

/**
 * [ADMIN-04] Who did it comes from the request: admin() puts the signed-in admin on env.actor. [ADMIN-17]
 * Only the name is written (the email stays on the admins screen), with the id for 내가 한 일. Old rows
 * are cleaned by prune() each hour, not here, so a change costs one statement.
 */
async function audit(env, now, action, target, detail = "") {
  await run(env, "INSERT INTO audit (at, action, target, detail, actor, actor_id) VALUES (?, ?, ?, ?, ?, ?)", now, action, target,
    String(detail).slice(0, 300), env.actor ? actorName(env) : "", env.actor?.id ?? "");
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
  // [PLZ-12] The job plaza and region it belongs to; a rule for a whole job (범용) needs no company.
  const job = given(body.job) ? oneOf(body.job, Object.keys(JOBS), "job") : "";
  const region = given(body.region) && body.region !== "" ? oneOf(body.region, Object.keys(REGIONS), "region") : "";
  const topic = body.topic === true;
  if (topic && !job) refuse("job", "job");
  const company = topic ? topicName(job, region) : str(body.company, LIMITS.company, "company", { empty: false, code: "company" });
  if (normName(company).length < 2) refuse("company", "company");
  const amends = given(body.amends) ? (typeof body.amends === "string" && /^[A-Za-z0-9]{16}$/.test(body.amends) ? body.amends : refuse("amends", "amends")) : null;
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
  return { company, dept, deptNorm: normName(dept), verified: body.verified, note, setup, ask, job, region, topic, amends };
}

// ---- 직군별 광장 [PLZ-12][PLZ-13][PLZ-14] ----

/** The job plazas (결정 D38): every company, hospital and topic sits in one, and the board can show one. */
export const JOBS = {
  shift: "교대 근무",
  factory: "제조·생산",
  nurse: "간호사·병원",
  public: "공무원",
  police: "경찰",
  fire: "소방",
  care: "요양·돌봄",
  security: "경비·시설",
  transport: "운수·운전",
  hourly: "알바·시급",
  construction: "건설 일용직·기능공",
  field: "현장·프리랜서",
  shop: "자영업",
};

/** 시·도 by the code 통계청 gives them (행정구역 분류), so the app and the server agree on the same key. */
export const REGIONS = {
  "11": "서울", "21": "부산", "22": "대구", "23": "인천", "24": "광주", "25": "대전", "26": "울산", "29": "세종",
  "31": "경기", "32": "강원", "33": "충북", "34": "충남", "35": "전북", "36": "전남", "37": "경북", "38": "경남", "39": "제주",
};

/** A rule for a whole job, not one company: "간호사·병원 공통", "건설 일용직·기능공 공통 (서울)". */
const topicName = (job, region) => `${JOBS[job]} 공통${region ? ` (${REGIONS[region]})` : ""}`;

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
  return json({ id: c.id, name: c.name, kind: c.kind ?? "company", job: c.job ?? "", region: c.region ?? "", aliases, setups });
}

const shared = (text, name) => ({ ...JSON.parse(text), company: name });

async function state(env, companyId, grp, threshold, adopted) {
  const s = await score(env, companyId, grp);
  const posts = await one(env, "SELECT COUNT(*) AS n FROM posts WHERE company_id = ? AND grp = ? AND status = 'shown'", companyId, grp);
  // [PLZ-08] Who stands behind it: how many sent it, how many checked it on a pay slip, how many use it,
  // and the names they chose to be shown by (no one else's).
  const who = await one(env, `SELECT
      (SELECT COUNT(*) FROM uses WHERE company_id = ?1 AND grp = ?2) AS uses,
      (SELECT COUNT(*) FROM reports WHERE company_id = ?1 AND grp = ?2 AND status IN ('pending', 'adopted')) AS senders,
      (SELECT COUNT(*) FROM reports WHERE company_id = ?1 AND grp = ?2 AND status IN ('pending', 'adopted') AND verified = 1)
        + (SELECT COUNT(*) FROM votes WHERE company_id = ?1 AND grp = ?2 AND ok = 1 AND verified = 1) AS checked`, companyId, grp);
  const names = (await all(env, `SELECT n.nick FROM reports r JOIN nicks n ON n.device = r.device
      WHERE r.company_id = ? AND r.grp = ? AND r.status IN ('pending', 'adopted') ORDER BY r.created_at LIMIT 5`, companyId, grp)).map((n) => n.nick);
  return { id: grp, status: adopted ? "adopted" : "pending", points: s.points, persons: s.persons, bad: s.bad, threshold,
    ...trust(s, threshold), posts: posts.n, uses: who.uses, senders: who.senders, checked: who.checked, by: names };
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
  if (await paused(env, "COMPANY_REPORT")) return json({ error: "paused" }, 503);
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
  const c = await companyFor(env, r.company, now, r);
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
      rule_id, reason, created_at, decided_at, amends) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)`,
      id, c.id, r.dept, r.deptNorm, grp, JSON.stringify(r.setup), r.verified ? 1 : 0, r.note, dev, net.now, cluster, r.verified ? 2 : 1,
      adopted ? "adopted" : "pending", adopted ? active.id : null, now, adopted ? now : null,
      // [PLZ-02] Only a change of the rule in use counts as one.
      !adopted && active && r.amends === active.id ? active.id : null),
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

async function companyFor(env, name, now, r = {}) {
  const norm = normName(name);
  const found = (await one(env, "SELECT id FROM companies WHERE norm = ? ORDER BY merged_into IS NOT NULL, created_at LIMIT 1", norm))
    ?? (await one(env, "SELECT company_id AS id FROM aliases WHERE norm = ?", norm));
  if (found) {
    const c = await follow(env, found.id);
    // [PLZ-12] A place without a plaza takes the first one a sender says; a developer can change it.
    if (c && ((!c.job && r.job) || (!c.region && r.region))) {
      await run(env, "UPDATE companies SET job = CASE WHEN job = '' THEN ? ELSE job END, region = CASE WHEN region = '' THEN ? ELSE region END WHERE id = ?",
        r.job ?? "", r.region ?? "", c.id);
      return one(env, "SELECT * FROM companies WHERE id = ?", c.id);
    }
    return c;
  }
  const id = randomId(12);
  const kind = r.topic ? "topic" : r.job === "nurse" ? "hospital" : "company";
  await run(env, `INSERT INTO companies (id, name, norm, hidden, merged_into, created_at, kind, job, region) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?)`,
    id, name, norm, now, kind, r.job ?? "", r.region ?? "");
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
  const doubters = (await all(env, "SELECT device FROM votes WHERE company_id = ? AND grp = ? AND ok = 0", companyId, grp)).map((v) => v.device);
  return tally(rows, doubters);
}

/**
 * The score from what was sent and voted, read for many setups at once: [rows] are the reports still
 * pending or adopted and the 맞았어요 votes (device, points, cluster), [doubterDevices] the 달라요 votes.
 */
function tally(rows, doubterDevices) {
  const doubters = new Set(doubterDevices);
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
  const { adoptPoints, adoptAgree, adoptPersons, amendPersons } = await settings(env);
  const s = await score(env, companyId, grp);
  if (s.points < adoptPoints || s.networks < 2) return;
  // [COMM-07] Too many 달라요 for the 맞았어요: the developer decides, however many points it has.
  if (s.persons * 100 < adoptAgree * (s.persons + s.bad)) return;
  const rep = await representative(env, companyId, grp);
  if (!rep) return;
  const why = await blocker(env, companyId, grp, rep.dept_norm);
  if (!why) {
    // [PLZ-03] At least this many different people (결정 D32).
    if (s.persons >= adoptPersons) await adopt(env, companyId, grp, "auto", now);
    return;
  }
  // [PLZ-02] A setup sent to change the rule in use takes its place as the next version once enough
  // people agree, without the developer: the rule grows by what people find, and the old version stays
  // in the history. Anything else for a department with a rule waits for the developer as before.
  if (why !== "active" || s.persons < amendPersons) return;
  const active = await activeRule(env, companyId, rep.dept_norm);
  const amends = await one(env, `SELECT 1 FROM reports WHERE company_id = ? AND grp = ? AND status = 'pending' AND amends = ? LIMIT 1`,
    companyId, grp, active?.id ?? "");
  if (amends) await adopt(env, companyId, grp, "amend", now);
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
  await payOut(env, ruleId, now);
  return ruleId;
}

/**
 * [PLZ-03] Rewards for an adopted rule go out by themselves (결정 D33): the first three phones that sent
 * it, and whoever proposed it in the talk, get their months as a pass at once. A phone past the cap
 * (months in the last days set) keeps its reward 'owed' for an admin to look at, and so does everyone
 * after the first three. Voters get nothing: rewarding votes would buy them.
 */
async function payOut(env, ruleId, now) {
  const { autoReward, rewardCapMonths, rewardCapDays } = await settings(env);
  if (!autoReward) return;
  const owed = await all(env, `SELECT w.id, w.device, w.months, p.post_id FROM rewards w JOIN reports p ON p.id = w.report_id
    WHERE w.rule_id = ? AND w.status = 'owed' ORDER BY p.post_id IS NULL, p.created_at LIMIT 3`, ruleId);
  await payEach(env, owed, { rewardCapMonths, rewardCapDays }, now);
}

/**
 * Gives each owed reward of [owed] (id, device, months) as setReward does by hand: a later renewal from
 * Play for someone already paying, a pass for anyone else, unless the phone is past the cap.
 */
async function payEach(env, owed, { rewardCapMonths, rewardCapDays }, now) {
  for (const w of owed) {
    if (w.device.startsWith("gone")) continue;
    const got = await one(env, "SELECT COALESCE(SUM(months), 0) AS m FROM rewards WHERE device = ? AND status = 'paid' AND paid_at > ?",
      w.device, now - rewardCapDays * DAY);
    if (got.m + w.months > rewardCapMonths) continue;
    let deferred = null;
    try {
      deferred = await deferFor(env, w.device, w.months, now);
    } catch (e) {
      console.error(`defer ${w.id}: ${e?.code ?? e}`);
    }
    const memo = deferred ? `자동 지급 · Play 결제 미룸(다음 결제 ${iso(deferred).slice(0, 10)})` : "자동 지급";
    await run(env, "UPDATE rewards SET status = 'paid', paid_at = ?, memo = ?, deferred = ? WHERE id = ? AND status = 'owed'",
      now, memo, deferred ? 1 : 0, w.id);
    await audit(env, now, "reward_auto", w.id, `${w.months}개월${deferred ? " 미룸" : ""}`);
  }
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
  // [PLZ-04] An adopted rule belongs to everyone who checked it: taking it back only cuts the sender's
  // tie to it (결정 D34). The rule, its versions and its score stay; a pass already given stays theirs.
  const full = await one(env, "SELECT status FROM reports WHERE id = ?", id);
  if (full.status === "adopted") {
    await env.DB.batch([
      stmt(env, "DELETE FROM rewards WHERE report_id = ? AND status = 'owed'", id),
      stmt(env, "UPDATE reports SET device = ? WHERE id = ?", `gone${randomId(12)}`, id),
    ]);
    return json({ kept: true, text: "채택된 규칙은 모두가 쓰는 규칙이라 남아요. 보낸 분과의 연결만 지웠어요." });
  }
  await env.DB.batch([stmt(env, "DELETE FROM rewards WHERE report_id = ?", id), stmt(env, "DELETE FROM reports WHERE id = ?", id)]);
  return new Response(null, { status: 204 });
}

/** Everything one install sent: reports, rewards not yet given and its 맞았어요·달라요. */
async function forgetMine(request, env) {
  const device = deviceId(request.headers.get("x-device"));
  if (!device) return json({ error: "device", field: "device" }, 400);
  const dev = await deviceHash(device);
  // [PLZ-04] Adopted rules and the posts that proposed them stay, cut loose from this install (결정 D34).
  const gone = `gone${randomId(12)}`;
  await env.DB.batch([
    stmt(env, `UPDATE posts SET device = ?2 WHERE device = ?1 AND report_id IN (SELECT id FROM reports WHERE device = ?1 AND status = 'adopted')`, dev, gone),
    stmt(env, "UPDATE reports SET device = ? WHERE device = ? AND status = 'adopted'", gone, dev),
    // [PLZ-01] Their votes on posts come off the posts' counts.
    stmt(env, "UPDATE posts SET agree = agree - 1 WHERE id IN (SELECT post_id FROM post_votes WHERE device = ? AND ok = 1)", dev),
    stmt(env, "UPDATE posts SET disagree = disagree - 1 WHERE id IN (SELECT post_id FROM post_votes WHERE device = ? AND ok = 0)", dev),
    stmt(env, "DELETE FROM post_votes WHERE device = ?", dev),
    stmt(env, "DELETE FROM uses WHERE device = ?", dev),
    stmt(env, "DELETE FROM nicks WHERE device = ?", dev),
    stmt(env, "DELETE FROM rate_votes WHERE device = ?", dev),
    stmt(env, "UPDATE rates SET device = ? WHERE device = ? AND status = 'adopted'", gone, dev),
    stmt(env, "DELETE FROM rates WHERE device = ?", dev),
  ]);
  const [, reports, votes, , posts, , , , , , , , asked] = await env.DB.batch([
    stmt(env, "DELETE FROM rewards WHERE device = ?", dev),
    stmt(env, "DELETE FROM reports WHERE device = ?", dev),
    stmt(env, "DELETE FROM votes WHERE device = ?", dev),
    stmt(env, "DELETE FROM counters WHERE key IN (?, ?)", `report|${dev}`, `post|${dev}`),
    // [COMM-06] Their posts and their 신고 go too; a post they flagged counts one fewer.
    stmt(env, "DELETE FROM posts WHERE device = ?", dev),
    stmt(env, "UPDATE posts SET flags = flags - 1 WHERE id IN (SELECT post_id FROM post_flags WHERE device = ?)", dev),
    stmt(env, "DELETE FROM post_flags WHERE device = ? OR post_id NOT IN (SELECT id FROM posts)", dev),
    // [ADMIN-11][ADMIN-12] Their questions, with what admins attached to them.
    ...dropInquiries(env, "device = ?", dev),
    // [ADMIN-02] Their 나도 그래요 and 신고 on others' questions go too, and those count one fewer.
    stmt(env, `UPDATE inquiries SET same = same - 1 WHERE id IN (SELECT inquiry_id FROM inquiry_marks WHERE device = ? AND kind = 'same')`, dev),
    stmt(env, `UPDATE inquiries SET flags = flags - 1 WHERE id IN (SELECT inquiry_id FROM inquiry_marks WHERE device = ? AND kind = 'flag')`, dev),
    stmt(env, "DELETE FROM inquiry_marks WHERE device = ? OR inquiry_id NOT IN (SELECT id FROM inquiries)", dev),
    // [MON-08][MON-09] Its recovery code and admin phone link. Purchases and consent answers stay as the
    // law's record of a sale until they expire (처리방침 보관 기간).
    stmt(env, "DELETE FROM pass_codes WHERE device = ?", dev),
    stmt(env, "DELETE FROM admin_devices WHERE device = ?", dev),
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
  // A company's name or another name for it, written any way 회사 찾기 accepts; one letter narrows nothing.
  const q = normName(url.searchParams.get("q")).slice(0, 40);
  const like = q.length < 2 ? "" : `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const named = `(?3 = '' OR c.norm LIKE ?3 ESCAPE '\\' OR EXISTS (SELECT 1 FROM aliases a WHERE a.company_id = c.id AND a.norm LIKE ?3 ESCAPE '\\'))
    AND (?4 = '' OR c.job = ?4) AND (?5 = '' OR c.region = ?5)`;
  // [PLZ-12] One job plaza, one region, or all of them.
  const job = Object.hasOwn(JOBS, url.searchParams.get("job") ?? "") ? url.searchParams.get("job") : "";
  const region = Object.hasOwn(REGIONS, url.searchParams.get("region") ?? "") ? url.searchParams.get("region") : "";
  // [PLZ-05] Newest first, or the most trusted, or the most used.
  const sort = ["trust", "used"].includes(url.searchParams.get("sort")) ? url.searchParams.get("sort") : "recent";
  const rows = tab === "adopted"
    ? await all(env, `SELECT r.company_id, r.grp, r.dept, r.setup, r.version, r.adopted_at AS at, c.name, c.kind, c.job, c.region
        FROM rules r JOIN companies c ON c.id = r.company_id
        WHERE r.withdrawn = 0 AND r.superseded = 0 AND c.hidden = 0 AND c.merged_into IS NULL AND ${named}
        ORDER BY ${sort === "used" ? "(SELECT COUNT(*) FROM uses u WHERE u.company_id = r.company_id AND u.grp = r.grp) DESC," : ""} r.adopted_at DESC
        LIMIT ?1 OFFSET ?2`, size + 1, offset, like, job, region)
    : await all(env, `SELECT p.company_id, p.grp, c.name, c.kind, c.job, c.region,
          MAX(MAX(p.created_at),
            COALESCE((SELECT MAX(v.at) FROM votes v WHERE v.company_id = p.company_id AND v.grp = p.grp), 0),
            COALESCE((SELECT MAX(t.created_at) FROM posts t WHERE t.company_id = p.company_id AND t.grp = p.grp AND t.status = 'shown'), 0)) AS at
        FROM reports p JOIN companies c ON c.id = p.company_id
        WHERE p.status = 'pending' AND c.hidden = 0 AND c.merged_into IS NULL AND ${named}
          AND NOT EXISTS (SELECT 1 FROM reports x WHERE x.company_id = p.company_id AND x.grp = p.grp AND x.status = 'rejected')
          AND NOT EXISTS (SELECT 1 FROM rules w WHERE w.company_id = p.company_id AND w.grp = p.grp AND (w.withdrawn = 1 OR w.superseded = 0))
        GROUP BY p.company_id, p.grp ORDER BY at DESC LIMIT ?1 OFFSET ?2`, size + 1, offset, like, job, region);
  const items = [];
  for (const r of rows.slice(0, size)) {
    const rep = tab === "adopted" ? r : await representative(env, r.company_id, r.grp);
    if (!rep) continue;
    const s = await state(env, r.company_id, r.grp, adoptPoints, tab === "adopted");
    if (s.persons === 0 && tab === "voting") continue;
    const lines = describe(JSON.parse(rep.setup));
    items.push({
      company: { id: r.company_id, name: r.name, kind: r.kind ?? "company", job: r.job ?? "", region: r.region ?? "" },
      dept: rep.dept,
      ...s,
      version: r.version ?? null,
      at: iso(r.at),
      gist: lines.find(([label]) => label === "근무")?.[1] ?? "",
      cycle: lines.find(([label]) => label.startsWith("순서"))?.[0] ?? "",
      pay: lines.some(([label]) => label === "사업장"),
    });
  }
  // [PLZ-05] The most trusted first, within the page the database gave.
  if (sort === "trust") items.sort((a, b) => b.trust - a.trust || b.persons - a.persons);
  return { tab, ...(like ? { q } : {}), ...(job ? { job } : {}), ...(region ? { region } : {}), sort, items,
    next: rows.length > size ? offset + size : null, threshold: adoptPoints };
}

/**
 * A rule set people may talk about: adopted and in use, or still gathering votes. [past] also takes an
 * older version a newer one replaced, whose talk stays readable as part of the rule's history ([PLZ-04]).
 */
async function talkable(env, companyId, grp, past = false) {
  const c = await follow(env, companyId);
  if (!c || c.hidden) return null;
  const live = await one(env, `SELECT
      EXISTS (SELECT 1 FROM rules WHERE company_id = ?1 AND grp = ?2 AND withdrawn = 0 AND (superseded = 0 OR ?3)) AS rule,
      EXISTS (SELECT 1 FROM reports WHERE company_id = ?1 AND grp = ?2 AND status = 'pending') AS pending`, c.id, grp, past ? 1 : 0);
  return live.rule || live.pending ? c : null;
}

/**
 * [COMM-06] The talk on one rule set, oldest first. Each writer is 참여자 N of this talk only, with what
 * they said about the rule set: 올린 분 (they sent it), 맞았어요 or 달라요. With X-Device, the caller's own
 * posts are marked, and their own hidden ones show with why.
 */
async function talk(request, env, companyId, grp) {
  const c = await talkable(env, companyId, grp, true);
  if (!c) return json({ error: "not_found" }, 404);
  const device = deviceId(request.headers.get("x-device"));
  const me = device ? await deviceHash(device) : null;
  const rows = await all(env, "SELECT * FROM posts WHERE company_id = ? AND grp = ? ORDER BY created_at, rowid LIMIT 500", c.id, grp);
  // [COMM-09] Writers this phone chose not to see, from its own list (X-Hide); the server keeps no list.
  const key = await authorKey(env);
  const hidden = await hiddenAuthors(key, request.headers.get("x-hide"));
  const dropped = new Set();
  let hiddenByYou = 0;
  const senders = new Set((await all(env, `SELECT device FROM reports WHERE company_id = ? AND grp = ? AND status IN ('pending', 'adopted')`,
    c.id, grp)).map((r) => r.device));
  const votes = new Map((await all(env, "SELECT device, ok, verified FROM votes WHERE company_id = ? AND grp = ?", c.id, grp))
    .map((v) => [v.device, v]));
  const verified = new Set((await all(env, "SELECT device FROM reports WHERE company_id = ? AND grp = ? AND verified = 1", c.id, grp))
    .map((r) => r.device));
  const numbers = new Map();
  const posts = [];
  // [PLZ-01][PLZ-08] My votes on these posts, and the names writers chose to be shown by.
  const myVotes = me ? new Map((await all(env, `SELECT v.post_id, v.ok FROM post_votes v JOIN posts p ON p.id = v.post_id
    WHERE v.device = ? AND p.company_id = ? AND p.grp = ?`, me, c.id, grp)).map((v) => [v.post_id, v.ok === 1])) : new Map();
  const nicks = new Map((await all(env, `SELECT n.device, n.nick FROM nicks n WHERE n.device IN
    (SELECT device FROM posts WHERE company_id = ? AND grp = ?)`, c.id, grp)).map((n) => [n.device, n.nick]));
  const { adoptPoints } = await settings(env);
  for (const p of rows) {
    if (!numbers.has(p.device)) numbers.set(p.device, numbers.size + 1);
    const mine = p.device === me;
    if (p.status !== "shown" && !mine) continue;
    // A hidden writer's posts go, and the replies under them with them, so no answer hangs without its question.
    if (!mine && (hidden.has(p.device) || (p.parent && dropped.has(p.parent)))) {
      if (hidden.has(p.device)) hiddenByYou++;
      dropped.add(p.id);
      continue;
    }
    const v = votes.get(p.device);
    // [PLZ-02] A proposal shows where its setup stands: points, people, adopted.
    let proposal = null;
    if (p.report_id) {
      const r = await one(env, "SELECT grp, status FROM reports WHERE id = ?", p.report_id);
      if (r) proposal = { setup: r.grp, ...(await state(env, c.id, r.grp, adoptPoints, r.status === "adopted")), lines: [] };
    }
    posts.push({
      id: p.id,
      who: numbers.get(p.device),
      nick: nicks.get(p.device) ?? null,
      stance: senders.has(p.device) ? "sender" : v ? (v.ok ? "ok" : "bad") : null,
      verified: verified.has(p.device) || v?.verified === 1,
      body: p.body,
      at: iso(p.created_at),
      mine,
      // [COMM-09] Who wrote it, sealed afresh for each post: the app can say 이 사람 글 안 보기 with it, but
      // two posts' marks never match, so nobody can tie a writer's posts together from them.
      ...(mine ? {} : { author: await authorToken(key, p.device) }),
      parent: p.parent ?? null,
      kind: p.kind ?? "talk",
      change: p.change ? JSON.parse(p.change) : null,
      agree: p.agree ?? 0,
      disagree: p.disagree ?? 0,
      ...postTrust(p),
      ...(myVotes.has(p.id) ? { myVote: myVotes.get(p.id) } : {}),
      ...(proposal ? { proposal } : {}),
      ...(p.status === "shown" ? {} : { hidden: { code: p.hide_reason ?? "flags", text: HIDE_REASONS[p.hide_reason ?? "flags"] ?? "" } }),
    });
  }
  const muted = me ? await one(env, "SELECT until FROM muted WHERE device = ? AND until > ?", me, Date.now()) : null;
  // [PLZ-04] An older version's talk is read only; the app points to the version in use.
  const past = !(await talkable(env, c.id, grp));
  return json({ company: { id: c.id, name: c.name }, setup: grp, posts, ...(past ? { past: true } : {}), ...(muted ? { mutedUntil: iso(muted.until) } : {}),
    ...(hiddenByYou ? { hiddenByYou } : {}) });
}

/** [COMM-06] A post in the talk on a rule set: no email, phone number or amount, like everything else sent here. */
async function post(request, env) {
  if (await paused(env, "PLAZA_WRITE")) return json({ error: "paused" }, 503);
  const body = await readJson(request, LIMITS.reportBytes + 4096);
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
  // [PLZ-05] A reply goes under a post of the same talk; replies to replies go under the same first post.
  let parent = null;
  if (given(body.parent)) {
    const top = typeof body.parent === "string" ? await one(env, "SELECT id, parent FROM posts WHERE id = ? AND company_id = ? AND grp = ?",
      body.parent, c.id, body.setup) : null;
    if (!top) refuse("parent", "parent");
    parent = top.parent ?? top.id;
  }
  const kind = given(body.kind) ? oneOf(body.kind, ["talk", "question", "proposal"], "kind") : "talk";
  const change = kind === "proposal" ? readChange(body.change) : null;
  const id = `p${randomId(15)}`;
  // [PLZ-02][PLZ-09] A proposal carries the whole setup as it should be: it is sent like a report, as a
  // change of the rule this talk is about when that rule is in use, and the post's 맞아요 count for it.
  let reportId = null;
  let proposal = null;
  if (kind === "proposal" && given(body.proposal)) {
    const texts = [];
    const setup = cleanSetup(body.proposal, texts);
    for (const [field, t] of texts) {
      const why = personal(t);
      if (why) refuse(why, field);
    }
    const base = await one(env, `SELECT dept, dept_norm FROM rules WHERE company_id = ?1 AND grp = ?2
      UNION ALL SELECT dept, dept_norm FROM reports WHERE company_id = ?1 AND grp = ?2 LIMIT 1`, c.id, body.setup);
    const grp = (await sha256(`${base.dept_norm}|${await fingerprint(setup)}`)).slice(0, 16);
    if (grp === body.setup) refuse("same", "proposal");
    const active = await activeRule(env, c.id, base.dept_norm);
    reportId = randomId(22);
    await run(env, `INSERT INTO reports (id, company_id, dept, dept_norm, grp, setup, verified, note, device, net, cluster, points, status,
      rule_id, reason, created_at, decided_at, amends, post_id) VALUES (?, ?, ?, ?, ?, ?, 0, '', ?, ?, ?, 1, 'pending', NULL, NULL, ?, NULL, ?, ?)`,
    reportId, c.id, base.dept, base.dept_norm, grp, JSON.stringify(setup), dev, net.now, await clusterFor(env, c.id, net), now,
    active && active.grp === body.setup ? active.id : null, id);
    proposal = grp;
  }
  await run(env, `INSERT INTO posts (id, company_id, grp, device, body, status, flags, kept, hide_reason, created_at, hidden_at,
      parent, kind, change, report_id, agree, disagree)
    VALUES (?, ?, ?, ?, ?, 'shown', 0, 0, NULL, ?, NULL, ?, ?, ?, ?, 0, 0)`, id, c.id, body.setup, dev, text, now,
  parent, kind, change ? JSON.stringify(change) : null, reportId);
  if (proposal) await settle(env, c.id, proposal, now);
  return json({ id, at: iso(now), ...(proposal ? { proposal } : {}) }, 201);
}

/**
 * [PLZ-09] What a proposal says is different, in fields anyone can read without opening the setup:
 * up to six lines of what (one of CHANGE_FIELDS), how it is now, how it should be, and why. No amounts
 * (the same rule as everything else sent here), so 기본급의 10% rather than a sum.
 */
const CHANGE_FIELDS = {
  shift: "근무 시간", order: "근무 순서", break: "휴게", overtime: "연장 근무", night: "야간", holiday: "휴일 근무",
  allowance: "수당", bonus: "상여", leave: "연차·휴가", other: "그 밖의 것",
};

function readChange(raw) {
  const lines = list(raw ?? [], 6, "change");
  if (!lines.length) refuse("change", "change");
  return lines.map((l, i) => {
    object(l, `change.${i}`);
    const out = {
      field: oneOf(l.field, Object.keys(CHANGE_FIELDS), `change.${i}.field`),
      now: str(l.now ?? "", 80, `change.${i}.now`, { code: "change" }),
      want: str(l.want, 80, `change.${i}.want`, { empty: false, code: "change" }),
      why: str(l.why ?? "", 160, `change.${i}.why`, { lines: true, code: "change" }),
    };
    for (const [k, v] of Object.entries(out)) {
      const why = k === "field" ? null : personal(v);
      if (why) refuse(why, `change.${i}.${k}`);
    }
    return out;
  });
}

/**
 * [PLZ-01] How far people trust a post, 0 to 100, from its 맞아요·달라요: the share who agree, held back
 * until a few have voted (3), so one vote never reads as 100.
 */
export function postTrust(p) {
  const n = (p.agree ?? 0) + (p.disagree ?? 0);
  return { trust: n ? Math.round((100 * (p.agree ?? 0)) / Math.max(n, 3)) : 0 };
}

/**
 * [PLZ-01] 맞아요 (ok true), 달라요 (false) or taking the vote back (null) on someone's post. On a
 * proposal it is also a vote on the proposed setup, so a proposal people agree with is adopted like any rule.
 */
async function postVote(request, env, id) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (body.ok !== null && typeof body.ok !== "boolean") refuse("ok", "ok");
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const dev = await deviceHash(device);
  const p = await one(env, "SELECT * FROM posts WHERE id = ?", id);
  if (!p || p.status !== "shown") return json({ error: "not_found" }, 404);
  if (p.device === dev) return json({ error: "own" }, 400);
  const before = await one(env, "SELECT ok FROM post_votes WHERE post_id = ? AND device = ?", id, dev);
  const stmts = [];
  if (before) stmts.push(stmt(env, `UPDATE posts SET ${before.ok ? "agree = agree - 1" : "disagree = disagree - 1"} WHERE id = ?`, id));
  if (body.ok === null) {
    stmts.push(stmt(env, "DELETE FROM post_votes WHERE post_id = ? AND device = ?", id, dev));
  } else {
    stmts.push(stmt(env, `INSERT INTO post_votes (post_id, device, ok, at) VALUES (?, ?, ?, ?)
      ON CONFLICT (post_id, device) DO UPDATE SET ok = excluded.ok, at = excluded.at`, id, dev, body.ok ? 1 : 0, now));
    stmts.push(stmt(env, `UPDATE posts SET ${body.ok ? "agree = agree + 1" : "disagree = disagree + 1"} WHERE id = ?`, id));
  }
  await env.DB.batch(stmts);
  if (p.report_id) {
    const r = await one(env, "SELECT company_id, grp, status FROM reports WHERE id = ?", p.report_id);
    if (r) {
      if (body.ok === null) {
        await run(env, "DELETE FROM votes WHERE company_id = ? AND grp = ? AND device = ?", r.company_id, r.grp, dev);
      } else {
        await forgetNetworks(env, now);
        const cluster = await clusterFor(env, r.company_id, net);
        await run(env, `INSERT INTO votes (company_id, grp, device, cluster, ok, verified, why, net, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (company_id, grp, device) DO UPDATE SET cluster = excluded.cluster, ok = excluded.ok, verified = excluded.verified,
            why = excluded.why, net = excluded.net, at = excluded.at`, r.company_id, r.grp, dev, cluster, body.ok ? 1 : 0,
        body.ok && body.verified === true ? 1 : 0, body.ok ? null : "wrong", net.now, now);
        if (body.ok && r.status === "pending") await settle(env, r.company_id, r.grp, now);
      }
    }
  }
  const after = await one(env, "SELECT agree, disagree FROM posts WHERE id = ?", id);
  return json({ agree: after.agree, disagree: after.disagree, ...postTrust(after), myVote: body.ok });
}

/** [PLZ-08] This phone uses a rule set (applied it in the app), or stopped: 쓰는 사람, counted once. */
async function useRule(request, env, on) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (typeof body.company !== "string" || !/^[A-Za-z0-9]{12}$/.test(body.company)) refuse("company", "company");
  if (typeof body.setup !== "string" || !/^[0-9a-f]{16}$/.test(body.setup)) refuse("setup", "setup");
  const dev = await deviceHash(device);
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const c = await talkable(env, body.company, body.setup);
  if (!c) return json({ error: "not_found" }, 404);
  if (on) await run(env, "INSERT OR IGNORE INTO uses (company_id, grp, device, at) VALUES (?, ?, ?, ?)", c.id, body.setup, dev, now);
  else await run(env, "DELETE FROM uses WHERE company_id = ? AND grp = ? AND device = ?", c.id, body.setup, dev);
  const n = await one(env, "SELECT COUNT(*) AS n FROM uses WHERE company_id = ? AND grp = ?", c.id, body.setup);
  return json({ uses: n.n, using: on });
}

/**
 * [PLZ-08] The name a phone is shown by on the plaza (2~12 letters, no contact or amount, not taken by
 * another phone, not 관리자·개발자·운영자), or none with an empty name.
 */
async function setNick(request, env) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  const dev = await deviceHash(device);
  const nick = str(body.nick ?? "", 12, "nick", { code: "nick" }).trim();
  if (!nick) {
    await run(env, "DELETE FROM nicks WHERE device = ?", dev);
    return json({ nick: null });
  }
  const norm = normName(nick);
  if ([...nick].length < 2 || norm.length < 2 || /관리자|개발자|운영자|밤낮|admin/i.test(nick)) refuse("nick", "nick");
  const why = personal(nick);
  if (why) refuse(why, "nick");
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `nick|${net.day}`, 20, DAY, now))) return json({ error: "rate_limited" }, 429);
  const taken = await one(env, "SELECT device FROM nicks WHERE norm = ?", norm);
  if (taken && taken.device !== dev) return json({ error: "taken", field: "nick" }, 409);
  await run(env, `INSERT INTO nicks (device, nick, norm, at) VALUES (?, ?, ?, ?)
    ON CONFLICT (device) DO UPDATE SET nick = excluded.nick, norm = excluded.norm, at = excluded.at`, dev, nick, norm, now);
  return json({ nick });
}

/** [PLZ-12] The plazas and how much is in each: adopted rule sets and ones being voted on, per job. */
async function plazas(env) {
  const rows = await all(env, `SELECT c.job,
      COUNT(DISTINCT CASE WHEN r.id IS NOT NULL THEN r.id END) AS adopted
    FROM companies c LEFT JOIN rules r ON r.company_id = c.id AND r.withdrawn = 0 AND r.superseded = 0
    WHERE c.hidden = 0 AND c.merged_into IS NULL GROUP BY c.job`);
  const voting = await all(env, `SELECT c.job, COUNT(DISTINCT p.company_id || p.grp) AS n FROM reports p JOIN companies c ON c.id = p.company_id
    WHERE p.status = 'pending' AND c.hidden = 0 GROUP BY c.job`);
  const rates = await all(env, "SELECT job, COUNT(*) AS n FROM rates WHERE status = 'adopted' GROUP BY job");
  const count = (list, job, key = "n") => list.find((r) => r.job === job)?.[key] ?? 0;
  return {
    jobs: Object.entries(JOBS).map(([key, name]) => ({ key, name, adopted: count(rows, key, "adopted"), voting: count(voting, key), rates: count(rates, key) })),
    regions: Object.entries(REGIONS).map(([key, name]) => ({ key, name })),
    changeFields: Object.entries(CHANGE_FIELDS).map(([key, name]) => ({ key, name })),
    rateUnits: Object.entries(RATE_UNITS).map(([key, u]) => ({ key, name: u.name })),
  };
}

/**
 * [PLZ-04][PLZ-05] Every version a department's rule went through, newest first: how it was adopted
 * (people, an amendment, the developer), when, what changed from the one before, and whether it was
 * taken down. Adopted versions never disappear; this is how the rule grew.
 */
async function history(env, companyId, url) {
  const c = await follow(env, companyId);
  if (!c || c.hidden) return json({ error: "not_found" }, 404);
  const grp = url.searchParams.get("setup") ?? "";
  const dept = await one(env, `SELECT dept_norm FROM rules WHERE company_id = ?1 AND grp = ?2
    UNION ALL SELECT dept_norm FROM reports WHERE company_id = ?1 AND grp = ?2 LIMIT 1`, c.id, grp);
  if (!dept) return json({ error: "not_found" }, 404);
  const rules = await all(env, "SELECT * FROM rules WHERE company_id = ? AND dept_norm = ? ORDER BY version", c.id, dept.dept_norm);
  const versions = [];
  let before = null;
  for (const r of rules) {
    const setup = JSON.parse(r.setup);
    const proposed = await one(env, `SELECT p.post_id FROM reports p WHERE p.rule_id = ? AND p.post_id IS NOT NULL LIMIT 1`, r.id);
    const senders = await one(env, "SELECT COUNT(*) AS n FROM reports WHERE rule_id = ?", r.id);
    versions.push({
      version: r.version, setup: r.grp, source: r.source, adoptedAt: iso(r.adopted_at),
      state: r.withdrawn ? "withdrawn" : r.superseded ? "superseded" : "active",
      senders: senders.n, post: proposed?.post_id ?? null,
      changes: before ? diffLines(describe(before), describe(setup)) : [],
    });
    before = setup;
  }
  return json({ company: { id: c.id, name: c.name }, versions: versions.reverse() });
}

// ---- [PLZ-14] 임금표: market rates for a job and region ----

/**
 * What a rate is per, and the amounts it may be: tight enough to catch a 0 too many or too few, wide
 * enough for 기능공 일당 in 서울 or a 전문의 월급. 최저시급 is well above the hour floor.
 */
const RATE_UNITS = {
  day: { name: "일당", min: 50000, max: 1000000 },
  hour: { name: "시급", min: 5000, max: 200000 },
  month: { name: "월급", min: 500000, max: 20000000 },
};

/** The year it is in Korea, which a rate is for. */
const koreaYear = (now) => new Date(now + 9 * HOUR).getUTCFullYear();

/** A rate further than this from the adopted middle of its kind waits for an admin however many agree. */
const RATE_FAR = 2;

async function rateCluster(env, net) {
  const row = await one(env, `SELECT cluster FROM rates WHERE net IN (?1, ?2)
    UNION ALL SELECT cluster FROM rate_votes WHERE net IN (?1, ?2) LIMIT 1`, net.now, net.prev);
  return row?.cluster ?? randomId(12);
}

/** The middle of sorted amounts (the lower of the two middle ones for an even count). */
const middle = (sorted) => sorted[Math.floor((sorted.length - 1) / 2)];

function rateView(r, me, myVotes, nicks) {
  return {
    id: r.id, job: r.job, region: r.region, trade: r.trade, unit: r.unit, year: r.year, amount: r.amount, note: r.note,
    status: r.status, agree: r.agree, disagree: r.disagree, ...postTrust(r), at: iso(r.created_at),
    mine: r.device === me, nick: nicks.get(r.device) ?? null,
    ...(myVotes.has(r.id) ? { myVote: myVotes.get(r.id) } : {}),
  };
}

/**
 * [PLZ-14][PLZ-15] The rates of one job (and region, trade, unit): every rate people sent and its
 * 맞아요·달라요, newest first, and for each kind (region, trade, unit, year) the adopted ones' low,
 * middle and high, which is what the app shows as 광장 시세. With X-Device the caller's are marked.
 */
async function rates(request, env, url) {
  const p = url.searchParams;
  const job = Object.hasOwn(JOBS, p.get("job") ?? "") ? p.get("job") : "";
  const region = Object.hasOwn(REGIONS, p.get("region") ?? "") ? p.get("region") : "";
  const unit = Object.hasOwn(RATE_UNITS, p.get("unit") ?? "") ? p.get("unit") : "";
  const trade = normName(p.get("trade")).slice(0, 20);
  const device = deviceId(request.headers.get("x-device"));
  const me = device ? await deviceHash(device) : null;
  const where = `status IN ('pending', 'adopted', 'review') AND (?1 = '' OR job = ?1) AND (?2 = '' OR region = ?2)
    AND (?3 = '' OR unit = ?3) AND (?4 = '' OR trade_norm = ?4)`;
  const rows = await all(env, `SELECT * FROM rates WHERE ${where} ORDER BY created_at DESC LIMIT 200`, job, region, unit, trade);
  const adopted = await all(env, `SELECT job, region, trade, trade_norm, unit, year, amount FROM rates WHERE ${where} AND status = 'adopted'
    ORDER BY amount LIMIT 2000`, job, region, unit, trade);
  const kinds = new Map();
  for (const r of adopted) {
    const key = [r.job, r.region, r.trade_norm, r.unit, r.year].join("|");
    if (!kinds.has(key)) kinds.set(key, { job: r.job, region: r.region, trade: r.trade, unit: r.unit, year: r.year, amounts: [] });
    kinds.get(key).amounts.push(r.amount);
  }
  const myVotes = me ? new Map((await all(env, "SELECT rate_id, ok FROM rate_votes WHERE device = ?", me)).map((v) => [v.rate_id, v.ok === 1])) : new Map();
  const nicks = new Map((await all(env, `SELECT device, nick FROM nicks WHERE device IN (SELECT device FROM rates WHERE ${where})`,
    job, region, unit, trade)).map((n) => [n.device, n.nick]));
  return json({
    ...(job ? { job } : {}), ...(region ? { region } : {}), ...(unit ? { unit } : {}),
    groups: [...kinds.values()].map(({ amounts, ...k }) => ({ ...k, n: amounts.length, low: amounts[0], mid: middle(amounts), high: amounts[amounts.length - 1] }))
      .sort((a, b) => b.year - a.year || b.n - a.n),
    entries: rows.map((r) => rateView(r, me, myVotes, nicks)),
  });
}

function readRate(body, now) {
  const year = koreaYear(now);
  const unit = oneOf(body.unit, Object.keys(RATE_UNITS), "unit");
  const u = RATE_UNITS[unit];
  const out = {
    job: oneOf(body.job, Object.keys(JOBS), "job"),
    region: oneOf(body.region ?? "", ["", ...Object.keys(REGIONS)], "region"),
    trade: str(body.trade, 20, "trade", { empty: false, code: "trade" }).trim(),
    unit,
    year: int(body.year ?? year, year - 1, year + 1, "year"),
    amount: int(body.amount, u.min, u.max, "amount"),
    note: str(body.note ?? "", 100, "note", { code: "note" }).trim(),
  };
  if ([...out.trade].length < 2) refuse("trade", "trade");
  for (const k of ["trade", "note"]) {
    const why = personal(out[k]);
    if (why) refuse(why, k);
  }
  return out;
}

/**
 * [PLZ-14] A market rate: what a trade is paid in a region per day, hour or month, as heard or as the
 * sender is paid, never with a name or a pay slip. One per phone for each kind; sending again changes it
 * while it is not adopted.
 */
async function addRate(request, env) {
  if (await paused(env, "PLAZA_WRITE")) return json({ error: "paused" }, 503);
  const body = await readJson(request, 2048);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  const now = Date.now();
  const r = readRate(body, now);
  const dev = await deviceHash(device);
  const muted = await one(env, "SELECT until FROM muted WHERE device = ? AND until > ?", dev, now);
  if (muted) return json({ error: "muted", until: iso(muted.until) }, 403);
  const net = await network(request, env, now);
  if (!(await allow(env, `rate|${dev}`, LIMITS.reportsPerDay, DAY, now)) ||
      !(await allow(env, `rate|${net.day}`, LIMITS.reportsPerNetworkDay, DAY, now))) {
    return json({ error: "rate_limited" }, 429);
  }
  const norm = normName(r.trade);
  const before = await one(env, `SELECT id, status FROM rates WHERE device = ? AND job = ? AND region = ? AND trade_norm = ? AND unit = ? AND year = ?
    AND status IN ('pending', 'adopted', 'review')`, dev, r.job, r.region, norm, r.unit, r.year);
  if (before && before.status !== "pending") return json({ error: "adopted", id: before.id }, 409);
  let id = before?.id;
  if (id) {
    // What others agreed to was the old amount: their votes go with it.
    await env.DB.batch([
      stmt(env, "UPDATE rates SET trade = ?, amount = ?, note = ?, agree = 0, disagree = 0, created_at = ? WHERE id = ?", r.trade, r.amount, r.note, now, id),
      stmt(env, "DELETE FROM rate_votes WHERE rate_id = ?", id),
    ]);
  } else {
    id = randomId(22);
    await run(env, `INSERT INTO rates (id, job, region, trade, trade_norm, unit, year, amount, note, device, cluster, net, status, created_at, decided_at, agree, disagree)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, NULL, 0, 0)`,
    id, r.job, r.region, r.trade, norm, r.unit, r.year, r.amount, r.note, dev, await rateCluster(env, net), net.now, now);
  }
  return json({ id, status: "pending", at: iso(now) }, before ? 200 : 201);
}

/** [PLZ-14] 맞아요 (ok true), 달라요 (false) or taking it back (null) on someone's rate. */
async function rateVote(request, env, id) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  if (body.ok !== null && typeof body.ok !== "boolean") refuse("ok", "ok");
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const dev = await deviceHash(device);
  const r = await one(env, "SELECT * FROM rates WHERE id = ?", id);
  if (!r || !["pending", "adopted", "review"].includes(r.status)) return json({ error: "not_found" }, 404);
  if (r.device === dev) return json({ error: "own" }, 400);
  const before = await one(env, "SELECT ok FROM rate_votes WHERE rate_id = ? AND device = ?", id, dev);
  const stmts = [];
  if (before) stmts.push(stmt(env, `UPDATE rates SET ${before.ok ? "agree = agree - 1" : "disagree = disagree - 1"} WHERE id = ?`, id));
  if (body.ok === null) {
    stmts.push(stmt(env, "DELETE FROM rate_votes WHERE rate_id = ? AND device = ?", id, dev));
  } else {
    stmts.push(stmt(env, `INSERT INTO rate_votes (rate_id, device, ok, cluster, net, at) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (rate_id, device) DO UPDATE SET ok = excluded.ok, cluster = excluded.cluster, net = excluded.net, at = excluded.at`,
    id, dev, body.ok ? 1 : 0, await rateCluster(env, net), net.now, now));
    stmts.push(stmt(env, `UPDATE rates SET ${body.ok ? "agree = agree + 1" : "disagree = disagree + 1"} WHERE id = ?`, id));
  }
  await env.DB.batch(stmts);
  if (body.ok && r.status === "pending") await settleRate(env, id, now);
  const after = await one(env, "SELECT agree, disagree, status FROM rates WHERE id = ?", id);
  return json({ agree: after.agree, disagree: after.disagree, ...postTrust(after), status: after.status, myVote: body.ok });
}

/**
 * [PLZ-14] Adopts a rate the way a rule is (결정 D32): enough different people from more than one
 * network agree, and few enough say 달라요. A rate far from what is already adopted for its kind waits
 * for an admin ('review'). The sender earns the reward, given at once like a rule's (결정 D33).
 */
async function settleRate(env, id, now) {
  const set = await settings(env);
  const r = await one(env, "SELECT * FROM rates WHERE id = ?", id);
  if (!r || r.status !== "pending") return;
  const votes = await all(env, "SELECT device, ok, cluster FROM rate_votes WHERE rate_id = ?", id);
  const s = tally([{ device: r.device, points: 1, cluster: r.cluster }, ...votes.filter((v) => v.ok).map((v) => ({ ...v, points: 1 }))],
    votes.filter((v) => !v.ok).map((v) => v.device));
  if (s.persons < set.adoptPersons || s.networks < 2) return;
  if (s.persons * 100 < set.adoptAgree * (s.persons + s.bad)) return;
  const same = (await all(env, `SELECT amount FROM rates WHERE job = ? AND region = ? AND trade_norm = ? AND unit = ? AND year = ? AND status = 'adopted'
    ORDER BY amount`, r.job, r.region, r.trade_norm, r.unit, r.year)).map((x) => x.amount);
  const mid = same.length >= 3 ? middle(same) : null;
  if (mid && (r.amount > mid * RATE_FAR || r.amount * RATE_FAR < mid)) {
    await run(env, "UPDATE rates SET status = 'review' WHERE id = ?", id);
    return;
  }
  await adoptRate(env, r, set, now);
}

async function adoptRate(env, r, set, now) {
  await run(env, "UPDATE rates SET status = 'adopted', decided_at = ? WHERE id = ?", now, r.id);
  if (set.rewardMonths <= 0 || r.device.startsWith("gone")) return;
  await run(env, `INSERT OR IGNORE INTO rewards (id, report_id, rule_id, device, months, status, memo, created_at, paid_at)
    VALUES (?, ?, 'rate', ?, ?, 'owed', '', ?, NULL)`, `w${r.id}`, r.id, r.device, set.rewardMonths, now);
  if (set.autoReward) await payEach(env, [{ id: `w${r.id}`, device: r.device, months: set.rewardMonths }], set, now);
}

/** [PLZ-14] Takes my rate back; an adopted one stays as everyone's, only my tie to it goes (결정 D34). */
async function takeRateBack(request, env, id) {
  const device = deviceId(request.headers.get("x-device"));
  const r = await one(env, "SELECT device, status FROM rates WHERE id = ?", id);
  if (!r) return json({ error: "not_found" }, 404);
  if (!device || (await deviceHash(device)) !== r.device) return json({ error: "forbidden" }, 403);
  if (r.status === "adopted") {
    await env.DB.batch([
      stmt(env, "DELETE FROM rewards WHERE report_id = ? AND status = 'owed'", id),
      stmt(env, "UPDATE rates SET device = ? WHERE id = ?", `gone${randomId(12)}`, id),
    ]);
    return json({ kept: true, text: "채택된 시세는 모두가 쓰는 정보라 남아요. 보낸 분과의 연결만 지웠어요." });
  }
  await env.DB.batch([
    stmt(env, "DELETE FROM rate_votes WHERE rate_id = ?", id),
    stmt(env, "DELETE FROM rewards WHERE report_id = ?", id),
    stmt(env, "DELETE FROM rates WHERE id = ?", id),
  ]);
  return new Response(null, { status: 204 });
}

/**
 * [PLZ-08] 내 활동: what this phone sent to the plaza and where it stands, its proposals with their
 * 맞아요·달라요, its rates, rewards and the name it is shown by. Only for the phone itself (X-Device).
 */
async function activity(request, env) {
  const dev = await installOf(request);
  const { adoptPoints } = await settings(env);
  const reports = await all(env, `SELECT r.id, r.company_id, r.grp, r.dept, r.status, r.created_at, c.name, u.version, u.withdrawn
    FROM reports r JOIN companies c ON c.id = r.company_id LEFT JOIN rules u ON u.id = r.rule_id
    WHERE r.device = ? ORDER BY r.created_at DESC LIMIT 50`, dev);
  const sent = [];
  for (const r of reports) {
    const s = await score(env, r.company_id, r.grp);
    const uses = await one(env, "SELECT COUNT(*) AS n FROM uses WHERE company_id = ? AND grp = ?", r.company_id, r.grp);
    sent.push({ id: r.id, company: { id: r.company_id, name: r.name }, dept: r.dept, setup: r.grp,
      status: r.status === "adopted" && r.withdrawn ? "withdrawn" : r.status, version: r.version ?? null,
      persons: s.persons, bad: s.bad, threshold: adoptPoints, uses: uses.n, at: iso(r.created_at) });
  }
  const posts = await all(env, `SELECT p.id, p.company_id, p.grp, p.kind, p.agree, p.disagree, p.body, p.created_at, c.name
    FROM posts p JOIN companies c ON c.id = p.company_id WHERE p.device = ? AND p.status = 'shown' AND p.kind = 'proposal'
    ORDER BY p.created_at DESC LIMIT 30`, dev);
  const mine = await all(env, "SELECT * FROM rates WHERE device = ? ORDER BY created_at DESC LIMIT 50", dev);
  const rewardRows = await all(env, "SELECT months, status, rule_id, created_at, paid_at FROM rewards WHERE device = ? ORDER BY created_at DESC LIMIT 50", dev);
  const nick = await one(env, "SELECT nick FROM nicks WHERE device = ?", dev);
  return json({
    nick: nick?.nick ?? null,
    reports: sent,
    proposals: posts.map((p) => ({ id: p.id, company: { id: p.company_id, name: p.name }, setup: p.grp, body: p.body,
      agree: p.agree, disagree: p.disagree, ...postTrust(p), at: iso(p.created_at) })),
    rates: mine.map((r) => rateView(r, dev, new Map(), new Map())),
    rewards: rewardRows.map((w) => ({ months: w.months, status: w.status, why: w.rule_id === "rate" ? "rate" : w.rule_id ? "rule" : "idea",
      at: iso(w.created_at), paidAt: w.paid_at ? iso(w.paid_at) : null })),
  });
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
// is shown in the app before sending. Only the install's hash is kept, so only that phone can write to,
// or delete, the thread, and a thread with nothing new for 180 days is deleted. A question sent as
// public is also in 모두의 문의 (below), without anything that went with it.

const INQUIRY = {
  // [INQ-01] Since 1.14.1 suggestions, anything else and a company's way the app cannot set come here too.
  kinds: ["question", "bug", "idea", "company", "other"],
  text: 2000,
  diag: 6000,
  pay: 3000,
  setup: 3000,
  bytes: 16 * 1024,
  perDay: 5,
  perNetworkDay: 20,
  messagesPerHour: 20,
  // [INQ-04] A question waiting on an admin longer than this is 놓친 문의.
  lateMs: DAY,
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
  // 급여 > 계산이 이상해요: the month's rules and each line's hours and rate, never an amount.
  ["급여 계산", "pay", INQUIRY.pay],
  // [INQ-01] 회사 방식 알려 주기: the kind's settings in words, never an amount.
  ["회사 설정", "setup", INQUIRY.setup],
];

/** [INQ-01] A company's way names the workplace: it stays with the developer, whatever the app asks. */
const PRIVATE_KINDS = ["company"];

// ---- sorting questions without reading each one [ADMIN-02] ----
//
// Every question gets a topic and an urgency from its words, its kind and what came with it, worked
// out when the list is read, so better words sort old questions too and nothing new is stored. The
// developer filters by topic and sees the urgent ones first instead of opening each to sort it.

/** Topic, label and the words that point to it; the most matched topic wins, the first on a tie. */
const INQUIRY_TOPICS = [
  ["alarm", "알람", /알람|울리|울려|울림|소리|진동|깨워|스누즈|다시\s*울/g],
  ["pay", "급여 계산", /급여|월급|시급|일당|연봉|수당|가산|명세서|통상임금|주휴|연차|퇴직|세금|4대\s*보험|계산/g],
  ["schedule", "근무표·달력", /근무표|달력|캘린더|교대|패턴|근무\s*유형|대근|휴무|비번|야간|주간|오프|일정|메모/g],
  ["share", "공유", /공유|링크|상대방|가족|동기화|받은\s*사람|보낸\s*사람/g],
  ["company", "회사 설정", /회사|부서|규칙|투표|토론|채택/g],
  ["backup", "백업·기기 이동", /백업|복원|새\s*(폰|휴대폰|기기)|기기\s*변경|옮기|가져오기|내보내기/g],
  ["billing", "결제", /결제|구독|프리미엄|환불|구매|광고|유료|무료/g],
  ["app", "앱 동작", /멈춰|멈춤|멈추|꺼져|꺼짐|튕|강제\s*종료|오류|에러|버그|안\s*열|안\s*켜|느려|느림|로딩|하얀\s*화면|검은\s*화면|위젯|워치|잠금\s*화면|배터리|업데이트/g],
];
const TOPIC_NAMES = Object.fromEntries([...INQUIRY_TOPICS.map(([key, label]) => [key, label]), ["etc", "기타"]]);

/** Words that make a problem urgent: an alarm that did not ring, an app that will not open, money paid. */
const URGENT = /안\s*울|못\s*(들|일어)|놓쳤|늦게\s*울|지각|안\s*열|안\s*켜|튕|강제\s*종료|꺼져|데이터.{0,6}(사라|없어|날아)|사라졌|환불|결제.{0,6}(안|됐|두\s*번)/;

/** What the list and a thread show for sorting: topic, its label and how soon it needs an answer. */
function triage(kind, text, info) {
  const t = String(text ?? "");
  let topic = "etc";
  let best = 0;
  for (const [key, , re] of INQUIRY_TOPICS) {
    const n = t.match(re)?.length ?? 0;
    if (n > best) [topic, best] = [key, n];
  }
  // What came with it says more than any word: 계산이 이상해요 sends the pay check, the alarm form its check.
  if (kind === "company" || info?.setup) topic = "company";
  else if (info?.pay) topic = "pay";
  else if (best === 0 && info?.diag) topic = "alarm";
  const urgency = ["idea", "company", "other"].includes(kind) ? "low" : URGENT.test(t) ? "urgent" : "normal";
  return { topic, topicName: TOPIC_NAMES[topic], urgency };
}

async function inquiries(request, env, path) {
  const method = request.method;
  const device = deviceId(request.headers.get("x-device"));
  if (path === "/api/inquiries" && method === "POST") return ask(request, env);
  if (path === "/api/inquiries/public" && method === "GET") return publicList(env, new URL(request.url).searchParams, device);
  const pub = path.match(/^\/api\/inquiries\/public\/([A-Za-z0-9]{22})$/);
  if (pub && method === "GET") return publicThread(env, pub[1], device);
  const mark = path.match(/^\/api\/inquiries\/([A-Za-z0-9]{22})\/(same|flag)$/);
  if (mark && method === "POST") return inquiryMark(request, env, mark[1], mark[2]);
  if (!device) return json({ error: "device", field: "device" }, 400);
  const dev = await deviceHash(device);
  if (path === "/api/inquiries" && method === "GET") {
    const rows = await all(env, `SELECT i.*, (SELECT COUNT(*) FROM inquiry_photos p WHERE p.inquiry_id = i.id) AS photos
      FROM inquiries i WHERE i.device = ? ORDER BY i.updated_at DESC LIMIT 50`, dev);
    const out = [];
    for (const r of rows) out.push(await inquiryView(env, r));
    return json({ inquiries: out });
  }
  const rated = path.match(/^\/api\/inquiries\/([A-Za-z0-9]{22})\/rating$/);
  if (rated && method === "POST") return inquiryRating(request, env, rated[1], dev);
  // [SRV-06] A photo sent with one's own question; someone else's reads as missing.
  const photo = path.match(/^\/api\/inquiries\/([A-Za-z0-9]{22})\/photos\/([12])$/);
  if (photo && method === "GET") {
    const owner = await one(env, "SELECT device FROM inquiries WHERE id = ?", photo[1]);
    return owner && owner.device === dev ? photoFile(env, photo[1], Number(photo[2])) : json({ error: "not_found" }, 404);
  }
  const m = path.match(/^\/api\/inquiries\/([A-Za-z0-9]{22})(\/messages)?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ?", m[1]);
  // Someone else's question reads as missing, so ids cannot be tried out.
  if (!row || row.device !== dev) return json({ error: "not_found" }, 404);
  if (!m[2] && method === "GET") return json(await inquiryView(env, row));
  if (!m[2] && method === "DELETE") {
    await env.DB.batch(dropInquiries(env, "id = ?", row.id));
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

/** [INQ-03] 도움이 됐어요 (good: true) or 아쉬워요 (false) on the answers to one's own question; null takes it back. */
async function inquiryRating(request, env, id, dev) {
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ?", id);
  if (!row || row.device !== dev) return json({ error: "not_found" }, 404);
  const body = await readJson(request, 1024);
  if (body.good !== true && body.good !== false && body.good !== null) refuse("good", "good");
  const answered = await one(env, "SELECT COUNT(*) AS n FROM inquiry_messages WHERE inquiry_id = ? AND sender = 'admin'", id);
  if (!answered.n) return json({ error: "not_answered", field: "good" }, 409);
  await run(env, "UPDATE inquiries SET rating = ? WHERE id = ?", body.good === null ? null : body.good ? 1 : -1, id);
  return json(await inquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", id)));
}

/** A new question: what the user typed, and what they agreed to send with it. */
async function ask(request, env) {
  // [SRV-06] Room for two photos as base64 next to the words.
  const body = await readJson(request, INQUIRY.bytes + PHOTO.max * Math.ceil((PHOTO.bytes * 4) / 3) + 1024);
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
  const photos = await readPhotos(env, body.photos);
  const id = randomId(22);
  await env.DB.batch([
    // [SRV-06] Photos stay with the developer even on a public question.
    ...photos.list.map((data, i) => stmt(env, "INSERT INTO inquiry_photos (inquiry_id, n, data, bytes, at) VALUES (?, ?, ?, ?, ?)",
      id, i + 1, data.base64, data.bytes, now)),
    // Only an app that says so on its form sends public: true; a question from an older app stays the developer's.
    stmt(env, "INSERT INTO inquiries (id, device, kind, info, status, public, created_at, updated_at) VALUES (?, ?, ?, ?, 'open', ?, ?, ?)",
      id, dev, body.kind, JSON.stringify(info), body.public === true && !PRIVATE_KINDS.includes(body.kind) ? 1 : 0, now, now),
    stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'user', ?, NULL, ?)", id, text, now),
    // Threads with nothing new for half a year go, answers, notes and 개발사항 links and all.
    ...dropInquiries(env, "updated_at < ?", now - INQUIRY.keepMs),
    stmt(env, "DELETE FROM counters WHERE expires < ?", now),
  ]);
  const view = await inquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", id));
  // Photos the server could not take this time (switched off or full): the question went without them.
  return json({ ...view, ...(photos.skipped ? { photosSkipped: photos.skipped } : {}) }, 201);
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
    if ((key === "pay" || key === "setup") && personal(x)) refuse("info", key);
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
    public: row.public === 1,
    same: row.same ?? 0,
    ...(row.hidden ? { hidden: { code: row.hidden, text: INQUIRY_HIDE[row.hidden] ?? "" } } : {}),
    rating: row.rating ?? 0,
    progress: await inquiryProgress(env, row, messages),
    // [SRV-06] How many photos came with it; each one is read on its own address.
    photos: row.photos ?? (await one(env, "SELECT COUNT(*) AS n FROM inquiry_photos WHERE inquiry_id = ?", row.id)).n,
  };
  if (!admin) return view;
  const info = JSON.parse(row.info);
  const context = {};
  for (const [label, key] of INQUIRY_INFO) if (info[key]) context[label] = info[key];
  const [reasons, notes, items] = (await env.DB.batch([
    stmt(env, `SELECT reason, COUNT(*) AS n FROM inquiry_marks WHERE inquiry_id = ? AND kind = 'flag'
      GROUP BY reason ORDER BY n DESC, reason`, row.id),
    stmt(env, "SELECT by, text, at FROM inquiry_notes WHERE inquiry_id = ? ORDER BY id", row.id),
    stmt(env, `SELECT d.id, d.title, d.status, (SELECT COUNT(*) FROM dev_links x JOIN inquiries xi ON xi.id = x.inquiry_id WHERE x.item_id = d.id) AS links FROM dev_links l
      JOIN dev_items d ON d.id = l.item_id AND d.deleted_at IS NULL WHERE l.inquiry_id = ? ORDER BY l.at`, row.id),
  ])).map((r) => r.results);
  return {
    ...view, appVersion: info.app ?? null, context, waitingHours: row.status === "open" ? Math.floor((Date.now() - row.updated_at) / HOUR) : null, ...triage(row.kind, messages.find((m) => m.sender === "user")?.text, info),
    flags: row.flags ?? 0, kept: row.kept === 1, reasons: reasons.map((r) => ({ code: r.reason, text: FLAGS[r.reason] ?? r.reason, n: r.n })),
    // [ADMIN-12] Who is answering it and what admins told each other; [ADMIN-11] the 개발사항 it is in.
    assignee: row.assignee ? { id: row.assignee, name: row.assignee_name ?? "" } : null,
    notes: notes.map((n) => ({ by: n.by, text: n.text, at: iso(n.at) })),
    ...(env.actor && can(env.actor, "dev") ? { dev: items.map((d) => ({ id: d.id, title: d.title, status: d.status, links: d.links })) } : {}),
  };
}

/**
 * [INQ-02] Where a question stands, as the asker sees it: 접수 (received), 확인 중 (an admin took it),
 * 답변 (answered), 개발 목록 (in a 개발사항 being made) and 반영 (in a finished one, with its version). The
 * items themselves stay the admins': only the stage and the version go to the app.
 */
async function inquiryProgress(env, row, messages) {
  const items = await all(env, `SELECT d.status, d.released_in FROM dev_links l JOIN dev_items d ON d.id = l.item_id AND d.deleted_at IS NULL
    WHERE l.inquiry_id = ?`, row.id);
  const done = items.filter((d) => d.status === "done");
  if (done.length) return { stage: "released", version: done.map((d) => d.released_in).find(Boolean) ?? null };
  if (items.some((d) => d.status === "todo" || d.status === "doing")) return { stage: items.some((d) => d.status === "doing") ? "building" : "planned" };
  const answered = messages.some((m) => m.sender === "admin");
  const last = messages[messages.length - 1];
  if (answered && last?.sender === "admin") return { stage: "answered" };
  if (row.assignee) return { stage: "checking" };
  return { stage: answered ? "checking" : "received" };
}

/** Who answered: the signed-in admin's name once there are admin accounts, else "admin". */
function actorName(env) {
  const a = env.actor;
  const name = typeof a === "string" ? a : a && typeof a === "object" ? a.name : null;
  return typeof name === "string" && name ? name.slice(0, 40) : "admin";
}

/** /api/admin/inquiries: the list, one thread, an answer and open or closed. */
// ---- 모두의 문의 [ADMIN-02] ----
//
// A question sent from an app that says so on its form is public: anyone with the app reads it and the
// developer's answers, says 나도 그래요 or 신고s it. The writer is never named, and what went with the
// question (app, phone, alarm check, pay check) stays with the developer. A question sent before was
// promised to the developer only, so it stays private. Three 신고 hide it until the developer looks.

/** Why a public question is hidden, as its writer's app shows it. */
const INQUIRY_HIDE = {
  abuse: "욕설이나 비방이 있어요",
  personal: "개인 정보가 있어요",
  spam: "광고나 도배예요",
  flags: "신고가 모여 개발자가 확인하고 있어요",
};

const PUBLIC_PAGE = 30;

/** What anyone sees of a public question in the list. */
function publicItem(r, me, sameByMe) {
  return {
    id: r.id,
    kind: r.kind,
    status: r.status,
    preview: [...(r.first ?? "")].slice(0, 160).join(""),
    answers: r.answers,
    same: r.same,
    sameByMe: sameByMe.has(r.id),
    mine: r.device === me,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    ...triage(r.kind, r.first, JSON.parse(r.info)),
  };
}

/** 모두의 문의, newest activity first or most 나도 그래요 first, one topic or all, a page at a time. */
async function publicList(env, params, device) {
  const me = device ? await deviceHash(device) : null;
  const topic = Object.hasOwn(TOPIC_NAMES, params.get("topic") ?? "") ? params.get("topic") : null;
  const order = params.get("sort") === "same" ? "i.same DESC, i.updated_at DESC" : "i.updated_at DESC";
  const offset = Math.max(0, Math.min(Number.parseInt(params.get("after") ?? "0", 10) || 0, 1000));
  const rows = await all(env, `SELECT i.id, i.kind, i.status, i.info, i.same, i.device, i.created_at, i.updated_at,
      (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id LIMIT 1) AS first,
      (SELECT COUNT(*) FROM inquiry_messages m WHERE m.inquiry_id = i.id AND m.sender = 'admin') AS answers
    FROM inquiries i WHERE i.public = 1 AND i.hidden IS NULL ORDER BY ${order} LIMIT 500`);
  const sameByMe = new Set(me ? (await all(env, "SELECT inquiry_id FROM inquiry_marks WHERE device = ? AND kind = 'same'", me))
    .map((r) => r.inquiry_id) : []);
  const items = rows.map((r) => publicItem(r, me, sameByMe));
  const topics = {};
  for (const i of items) topics[i.topic] = (topics[i.topic] ?? 0) + 1;
  const shown = items.filter((i) => !topic || i.topic === topic);
  const page = shown.slice(offset, offset + PUBLIC_PAGE);
  return json({ topics, topicNames: TOPIC_NAMES, inquiries: page, next: offset + PUBLIC_PAGE < shown.length ? offset + PUBLIC_PAGE : null });
}

/** One public question with every message; its writer also sees it while hidden, with why. */
async function publicThread(env, id, device) {
  const me = device ? await deviceHash(device) : null;
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ? AND public = 1", id);
  if (!row || (row.hidden && row.device !== me)) return json({ error: "not_found" }, 404);
  const messages = await messagesOf(env, row.id);
  const marks = me ? await all(env, "SELECT kind FROM inquiry_marks WHERE inquiry_id = ? AND device = ?", row.id, me) : [];
  return json({
    id: row.id,
    kind: row.kind,
    status: row.status,
    same: row.same,
    sameByMe: marks.some((m) => m.kind === "same"),
    flaggedByMe: marks.some((m) => m.kind === "flag"),
    mine: row.device === me,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
    messages: messages.map((m) => ({ from: m.sender === "user" ? "user" : "admin", text: m.text, at: iso(m.at) })),
    ...triage(row.kind, messages.find((m) => m.sender === "user")?.text, JSON.parse(row.info)),
    ...(row.hidden ? { hidden: { code: row.hidden, text: INQUIRY_HIDE[row.hidden] ?? "" } } : {}),
  });
}

/** 나도 그래요 (on: false takes it back) or 신고, once per install; not on one's own question. */
async function inquiryMark(request, env, id, kind) {
  const body = await readJson(request, 1024);
  const device = deviceId(body.device);
  if (!device) refuse("device", "device");
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `vote|${net.now}`, LIMITS.votesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  const dev = await deviceHash(device);
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ? AND public = 1 AND hidden IS NULL", id);
  if (!row) return json({ error: "not_found" }, 404);
  if (row.device === dev) return json({ error: "own" }, 400);
  if (kind === "same") {
    await env.DB.batch([
      body.on === false
        ? stmt(env, "DELETE FROM inquiry_marks WHERE inquiry_id = ? AND device = ? AND kind = 'same'", id, dev)
        : stmt(env, "INSERT OR IGNORE INTO inquiry_marks (inquiry_id, device, kind, reason, at) VALUES (?, ?, 'same', NULL, ?)", id, dev, now),
      stmt(env, "UPDATE inquiries SET same = (SELECT COUNT(*) FROM inquiry_marks WHERE inquiry_id = ?1 AND kind = 'same') WHERE id = ?1", id),
    ]);
    const after = await one(env, "SELECT same FROM inquiries WHERE id = ?", id);
    return json({ same: after.same, sameByMe: body.on !== false });
  }
  const reason = Object.hasOwn(FLAGS, body.reason) && body.reason !== "false" ? body.reason : "abuse";
  const added = await run(env, "INSERT OR IGNORE INTO inquiry_marks (inquiry_id, device, kind, reason, at) VALUES (?, ?, 'flag', ?, ?)", id, dev, reason, now);
  if (added.meta.changes) {
    await run(env, `UPDATE inquiries SET flags = flags + 1,
        hidden = CASE WHEN kept = 0 AND flags + 1 >= ?2 THEN 'flags' ELSE hidden END WHERE id = ?1`, id, LIMITS.flagsToHide);
    const after = await one(env, "SELECT hidden FROM inquiries WHERE id = ?", id);
    if (after.hidden) await audit(env, now, "inquiry_autohide", id);
  }
  return json({ ok: true });
}

async function adminInquiries(request, env, url, path, now) {
  const method = request.method;
  if (path === "/inquiries" && method === "GET") return json(await inquiryList(env, url.searchParams, env.actor?.id ?? ""));
  if (path === "/inquiries/bulk" && method === "POST") return inquiryBulk(request, env, now);
  // [SRV-06] A photo sent with a question, for an admin who may read questions.
  const photo = path.match(/^\/inquiries\/([A-Za-z0-9]{22})\/photos\/([12])$/);
  if (photo && method === "GET") return photoFile(env, photo[1], Number(photo[2]));
  const m = path.match(/^\/inquiries\/([A-Za-z0-9]{22})(\/reply|\/status|\/visibility|\/assign|\/notes|\/typing)?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const row = await one(env, "SELECT * FROM inquiries WHERE id = ?", m[1]);
  if (!row) return json({ error: "not_found" }, 404);
  if (!m[2] && method === "GET") return json(await adminInquiryView(env, row));
  if (method !== "POST" || !m[2]) return json({ error: "not_found" }, 404);
  const body = await readJson(request, INQUIRY.bytes);
  if (m[2] === "/typing") {
    // [ADMIN-16] 답 쓰는 중: lasts 30 seconds unless sent again, so a closed tab stops showing by itself.
    const key = `typing|${row.id}|${env.actor.id}|${actorName(env).replace(/\|/g, "")}`;
    if (body.on === false) await run(env, "DELETE FROM counters WHERE key = ?", key);
    else await run(env, "INSERT OR REPLACE INTO counters (key, count, expires) VALUES (?, 1, ?)", key, now + 30 * 1000);
    return json({ ok: true });
  }
  if (m[2] === "/reply") {
    await replyTo(env, row, body, now);
  } else if (m[2] === "/assign") {
    // [ADMIN-12] 내가 맡기, or let it go (to: null); one tap, so two admins do not answer the same question.
    if (body.to !== "me" && body.to !== null) refuse("to", "to");
    // Letting go is for whoever holds it, or the owner; nobody releases someone else's question.
    if (!mayAssign(env.actor, row, body.to)) throw new Refuse("no_permission", "assignee", 403);
    await run(env, "UPDATE inquiries SET assignee = ?, assignee_name = ? WHERE id = ?", body.to ? env.actor.id : null, body.to ? actorName(env) : null, row.id);
    await audit(env, now, "inquiry_assign", row.id, body.to ? actorName(env) : "");
  } else if (m[2] === "/notes") {
    // [ADMIN-12] A note for the other admins: never in the app's view of the question.
    const text = plainText(body.text, DEV.note, "text", true);
    await run(env, "INSERT INTO inquiry_notes (inquiry_id, by, text, at) VALUES (?, ?, ?, ?)", row.id, actorName(env), text, now);
  } else if (m[2] === "/visibility") {
    // 모두의 문의: hide (with a reason the writer's app shows) or show again; either way 신고 no longer hides it.
    if (!row.public) refuse("public", "action");
    if (body.action === "hide") {
      const reason = Object.hasOwn(INQUIRY_HIDE, body.reason) && body.reason !== "flags" ? body.reason : "abuse";
      await run(env, "UPDATE inquiries SET hidden = ?, kept = 1 WHERE id = ?", reason, row.id);
      await audit(env, now, "inquiry_hide", row.id, reason);
    } else if (body.action === "show") {
      await env.DB.batch([
        stmt(env, "UPDATE inquiries SET hidden = NULL, kept = 1, flags = 0 WHERE id = ?", row.id),
        stmt(env, "DELETE FROM inquiry_marks WHERE inquiry_id = ? AND kind = 'flag'", row.id),
      ]);
      await audit(env, now, "inquiry_show", row.id);
    } else {
      refuse("action", "action");
    }
  } else {
    if (!["open", "closed"].includes(body.status)) refuse("status", "status");
    // Opening or closing is the developer's bookkeeping; it does not keep a thread from its 180 days.
    await run(env, "UPDATE inquiries SET status = ? WHERE id = ?", body.status, row.id);
    await audit(env, now, "inquiry_status", row.id, body.status);
  }
  return json(await adminInquiryView(env, await one(env, "SELECT * FROM inquiries WHERE id = ?", row.id)));
}

const mayAssign = (actor, row, to) => to !== null || !row.assignee || row.assignee === actor.id || actor.owner;

/**
 * An admin's answer to one question. [ADMIN-15] The same words from the same admin within a minute are
 * one answer, so a double tap or a resend after a slow network does not reach the asker twice.
 * [ADMIN-16] An answer sent from a saved one counts as a use of it.
 */
async function replyTo(env, row, body, now, audited = true) {
  if (typeof body.text !== "string" || !body.text.trim() || [...body.text.trim()].length > INQUIRY.text) refuse("text", "text");
  const text = body.text.normalize("NFC").replace(/\r\n?/g, "\n").trim();
  const status = body.close === true ? "closed" : "answered";
  const again = await one(env, `SELECT id FROM inquiry_messages WHERE inquiry_id = ? AND sender = 'admin' AND text = ? AND by = ? AND at > ?
    ORDER BY id DESC LIMIT 1`, row.id, text, actorName(env), now - 60 * 1000);
  if (again) return false;
  await env.DB.batch([
    stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'admin', ?, ?, ?)", row.id, text, actorName(env), now),
    // [ADMIN-12] Whoever answers a question nobody took has taken it.
    stmt(env, `UPDATE inquiries SET status = ?, updated_at = ?, assignee = COALESCE(assignee, ?),
      assignee_name = CASE WHEN assignee IS NULL THEN ? ELSE assignee_name END WHERE id = ?`, status, now, env.actor?.id ?? null, actorName(env), row.id),
    ...(Number.isInteger(body.template) ? [stmt(env, "UPDATE reply_templates SET uses = uses + 1 WHERE id = ?", body.template)] : []),
    // Sent, so no longer 답 쓰는 중.
    stmt(env, "DELETE FROM counters WHERE key LIKE ?", `typing|${row.id}|${env.actor?.id ?? ""}|%`),
  ]);
  if (audited) await audit(env, now, "inquiry_reply", row.id, status);
  return true;
}

/** [ADMIN-16] What can be done to many questions at once, and what it needs besides 문의 고치기. */
const BULK = { assign: null, unassign: null, close: null, open: null, reply: null, link: "dev" };

/**
 * [ADMIN-16] Up to 100 questions at once: take them, let them go, close, open again, send each the same
 * answer, or link them to a 개발사항. What one admin may not do to one of them (let go of someone else's)
 * is skipped and counted, the rest goes through, and the activity log gets one line for the lot.
 */
async function inquiryBulk(request, env, now) {
  const body = await readJson(request, INQUIRY.bytes + 4096);
  if (!Object.hasOwn(BULK, body.action)) refuse("action", "action");
  const ids = Array.isArray(body.ids) ? [...new Set(body.ids)] : [];
  if (!ids.length || ids.length > DEV.bulk || ids.some((id) => typeof id !== "string" || !/^[A-Za-z0-9]{22}$/.test(id))) refuse("ids", "ids");
  if (BULK[body.action] && !can(env.actor, BULK[body.action], "edit")) return json({ error: "no_permission", area: BULK[body.action] }, 403);
  let item = null;
  if (body.action === "link") {
    item = typeof body.item === "string" ? await one(env, "SELECT * FROM dev_items WHERE id = ? AND deleted_at IS NULL", body.item) : null;
    if (!item) return json({ error: "not_found" }, 404);
  }
  const rows = await all(env, `SELECT * FROM inquiries WHERE id IN (${ids.map(() => "?").join(", ")})`, ...ids);
  let done = 0;
  let skipped = ids.length - rows.length;
  const a = env.actor;
  for (const row of rows) {
    if (body.action === "assign" || body.action === "unassign") {
      const to = body.action === "assign" ? "me" : null;
      if (!mayAssign(a, row, to)) {
        skipped++;
        continue;
      }
      await run(env, "UPDATE inquiries SET assignee = ?, assignee_name = ? WHERE id = ?", to ? a.id : null, to ? actorName(env) : null, row.id);
    } else if (body.action === "close" || body.action === "open") {
      await run(env, "UPDATE inquiries SET status = ? WHERE id = ?", body.action === "close" ? "closed" : "open", row.id);
    } else if (body.action === "reply") {
      if (!(await replyTo(env, row, { text: body.text, close: body.close === true, template: body.template }, now, false))) {
        skipped++;
        continue;
      }
    } else {
      const r = await run(env, "INSERT OR IGNORE INTO dev_links (item_id, inquiry_id, by, at) VALUES (?, ?, ?, ?)", item.id, row.id, actorName(env), now);
      if (r.meta.changes) await devEvent(env, item.id, now, "link", { inquiry: row.id }).run();
    }
    done++;
  }
  if (item) await run(env, "UPDATE dev_items SET updated_at = ? WHERE id = ?", now, item.id);
  await audit(env, now, "inquiry_bulk", item ? item.title : "", `${body.action} ${done}${skipped ? ` (${skipped} 건너뜀)` : ""}`);
  return json({ done, skipped });
}

/**
 * [ADMIN-16][ADMIN-17] One question for an admin: what inquiryView gives, with the saved answers (its
 * topic's first, then the most used) when this admin may answer, who else is writing to it, and what
 * admins did to it, for the timeline.
 */
async function adminInquiryView(env, row) {
  const view = await inquiryView(env, row, true);
  const now = Date.now();
  const [events, links, typing] = (await env.DB.batch([
    stmt(env, `SELECT at, action, detail, actor FROM audit WHERE target = ? AND action IN ('inquiry_assign', 'inquiry_status', 'inquiry_reply',
      'inquiry_hide', 'inquiry_show') ORDER BY id LIMIT 200`, row.id),
    stmt(env, "SELECT l.at, l.by, d.id, d.title FROM dev_links l JOIN dev_items d ON d.id = l.item_id WHERE l.inquiry_id = ? ORDER BY l.at", row.id),
    stmt(env, "SELECT key FROM counters WHERE key LIKE ? AND expires > ?", `typing|${row.id}|%`, now),
  ])).map((r) => r.results);
  const out = {
    ...view,
    events: [
      ...events.map((e) => ({ at: iso(e.at), action: e.action, detail: e.detail, by: plainActor(e.actor) })),
      ...links.map((l) => ({ at: iso(l.at), action: "dev_link", detail: l.title, item: l.id, by: l.by })),
    ].sort((x, y) => x.at.localeCompare(y.at)),
    typing: typingByInquiry(typing, env.actor?.id ?? "")[row.id] ?? [],
  };
  if (view.waitingHours !== null) {
    const last = view.messages[view.messages.length - 1];
    out.slaLeft = last?.from === "user" ? slaLeft(row.kind, row.updated_at, await slaTargets(env), now) : null;
  }
  if (env.actor && can(env.actor, "inquiries", "edit")) {
    const list = (await templates(env, now)).templates;
    out.templates = list.sort((x, y) => (y.topic === view.topic) - (x.topic === view.topic) || y.uses - x.uses || x.id - y.id);
  }
  return out;
}

/** [ADMIN-17] Who did it, as a name: rows written before 1.23 carried "이름 <메일>", shown without the mail. */
const plainActor = (actor) => String(actor ?? "").replace(/\s*<[^<>]*@[^<>]*>\s*$/, "");

/**
 * [ADMIN-16] How a list is paged: [after] rows are skipped (the last answer's next), [limit] come, 50
 * unless asked for fewer or up to 100.
 */
function page(params, fallback = 50) {
  const after = Number(params.get("after"));
  const limit = Number(params.get("limit"));
  return { after: Number.isInteger(after) && after > 0 ? after : 0, limit: Number.isInteger(limit) && limit > 0 ? Math.min(limit, 100) : fallback };
}

/** One page of [list] and where the next starts, or null at the end. */
const paged = (list, { after, limit }) => ({ items: list.slice(after, after + limit), next: after + limit < list.length ? after + limit : null });

/** [ADMUX-06] How long a question of each kind may wait for an answer, in hours (설정 › 답 목표). */
const SLA = { keys: { slaBug: 8, slaQuestion: 24, slaIdea: 72, slaOther: 72 }, least: 1, most: 168 };
const slaKey = (kind) => (kind === "bug" || kind === "problem" ? "slaBug" : kind === "question" ? "slaQuestion" : kind === "idea" ? "slaIdea" : "slaOther");
const slaSetting = (key) => key.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);

const slaTargets = async (env) => readSla(await all(env, SLA_SQL));

function readSla(rows) {
  const out = {};
  for (const [k, fallback] of Object.entries(SLA.keys)) {
    const n = Number(rows.find((r) => r.key === slaSetting(k))?.value);
    out[k] = Number.isInteger(n) && n >= SLA.least && n <= SLA.most ? n : fallback;
  }
  return out;
}

/** [ADMUX-06] Minutes until a waiting question passes its kind's target (below 0: past it), or null. */
const slaLeft = (kind, waitingSince, targets, now) => (waitingSince === null ? null : Math.round((waitingSince + targets[slaKey(kind)] * HOUR - now) / 60000));

/** The sorts the questions list offers; the default is the one it always had. */
const INQUIRY_SORTS = ["default", "sla", "oldest", "newest", "same"];

/**
 * Questions waiting first, then the newest; [q] finds words in what was asked or answered. [ADMIN-16]
 * Up to 1000 are read in one go, counted by topic before the topic filter and paged after sorting.
 */
async function inquiryList(env, params, actorId = "") {
  // [ADMUX-02] 놓친 것 is only ever a waiting question, so the status filter follows it.
  const late = params.get("late") === "1" ? Date.now() - INQUIRY.lateMs : 0;
  const status = late ? "open" : ["open", "answered", "closed"].includes(params.get("status")) ? params.get("status") : null;
  const q = String(params.get("q") ?? "").trim().slice(0, 40);
  const topic = Object.hasOwn(TOPIC_NAMES, params.get("topic") ?? "") ? params.get("topic") : null;
  // [ADMIN-12] mine: the ones I took; free: the ones nobody took yet.
  const who = ["mine", "free"].includes(params.get("who")) ? params.get("who") : "";
  const sort = INQUIRY_SORTS.includes(params.get("sort")) ? params.get("sort") : "default";
  const like = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const now = Date.now();
  const [rows, statusRows, slaRows, typing] = (await env.DB.batch([stmt(env, `SELECT i.*,
      (SELECT COUNT(*) FROM inquiry_messages m WHERE m.inquiry_id = i.id) AS count,
      (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id LIMIT 1) AS first,
      (SELECT m.sender FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id DESC LIMIT 1) AS last,
      (SELECT COUNT(*) FROM dev_links l JOIN dev_items d ON d.id = l.item_id AND d.deleted_at IS NULL WHERE l.inquiry_id = i.id) AS dev,
      (SELECT COUNT(*) FROM inquiry_notes n WHERE n.inquiry_id = i.id) AS notes
    FROM inquiries i
    WHERE (?1 IS NULL OR i.status = ?1)
      AND (?2 = '' OR EXISTS (SELECT 1 FROM inquiry_messages m WHERE m.inquiry_id = i.id AND m.text LIKE ?3 ESCAPE '\\'))
      AND (?4 = '' OR (?4 = 'mine' AND i.assignee = ?5) OR (?4 = 'free' AND i.assignee IS NULL))
      AND (?6 = 0 OR (i.status = 'open' AND i.updated_at < ?6))
    ORDER BY i.status = 'open' DESC, i.updated_at DESC LIMIT 1000`, status, q, like, who, actorId, late),
  stmt(env, "SELECT status, COUNT(*) AS n FROM inquiries GROUP BY status"),
  slaQuery(env),
  stmt(env, "SELECT key FROM counters WHERE key LIKE 'typing|%' AND expires > ?", now)])).map((r) => r.results);
  const counts = { open: 0, answered: 0, closed: 0 };
  for (const r of statusRows) {
    if (Object.hasOwn(counts, r.status)) counts[r.status] = r.n;
  }
  const targets = readSla(slaRows);
  const writing = typingByInquiry(typing, actorId);
  const listed = rows.map((r) => {
    const info = JSON.parse(r.info);
    const waitingSince = r.status === "open" && r.last === "user" ? r.updated_at : null;
    return {
      id: r.id,
      kind: r.kind,
      status: r.status,
      preview: [...(r.first ?? "")].slice(0, 120).join(""),
      count: r.count,
      waiting: r.last === "user",
      appVersion: info.app ?? null,
      createdAt: iso(r.created_at),
      updatedAt: iso(r.updated_at),
      ...triage(r.kind, r.first, info),
      public: r.public === 1,
      hidden: r.hidden ?? null,
      flags: r.flags ?? 0,
      same: r.same ?? 0,
      assignee: r.assignee ? { id: r.assignee, name: r.assignee_name ?? "" } : null,
      dev: r.dev,
      notes: r.notes,
      // [INQ-04] How long the user has waited on an answer, in hours; null when it is not their turn to wait.
      waitingHours: waitingSince === null ? null : Math.floor((now - waitingSince) / HOUR),
      // [ADMUX-06] Minutes left before this kind's answer target; below 0 once past it.
      slaLeft: slaLeft(r.kind, waitingSince, targets, now),
      rating: r.rating ?? 0,
      // [ADMIN-16] Another admin writing an answer to it right now.
      typing: writing[r.id] ?? [],
    };
  });
  // How many of this view are in each topic, so the filter shows where the questions pile up.
  const topics = {};
  for (const i of listed) topics[i.topic] = (topics[i.topic] ?? 0) + 1;
  const rank = { urgent: 0, normal: 1, low: 2 };
  // Among those waiting for an answer: urgent first, then the ones most people said 나도 그래요 to; the rest
  // stay newest first.
  const waits = (i) => (i.status === "open" ? 0 : 1);
  const left = (i) => i.slaLeft ?? Infinity;
  const order = {
    default: (a, b) => waits(a) - waits(b) || (waits(a) === 0 ? rank[a.urgency] - rank[b.urgency] || b.same - a.same : 0),
    sla: (a, b) => left(a) - left(b) || waits(a) - waits(b),
    oldest: (a, b) => a.updatedAt.localeCompare(b.updatedAt),
    newest: (a, b) => b.updatedAt.localeCompare(a.updatedAt),
    same: (a, b) => b.same - a.same || waits(a) - waits(b),
  }[sort];
  const shown = listed.filter((i) => !topic || i.topic === topic).sort(order);
  const { items, next } = paged(shown, page(params));
  return { counts, topics, topicNames: TOPIC_NAMES, inquiries: items, total: shown.length, next, sort, sla: targets,
    // [ADMUX-02] What the server took the filter to be, so the app shows the status 놓친 것 fixed.
    status: status ?? "", late: Boolean(late) };
}

const SLA_SQL = `SELECT key, value FROM settings WHERE key IN (${Object.keys(SLA.keys).map((k) => `'${slaSetting(k)}'`).join(", ")})`;
const slaQuery = (env) => env.DB.prepare(SLA_SQL);

/** [ADMIN-16] typing|<question>|<admin id>|<name> rows as names per question, leaving out [me]. */
function typingByInquiry(rows, me) {
  const out = {};
  for (const { key } of rows) {
    const [, id, admin, ...name] = key.split("|");
    if (admin === me) continue;
    (out[id] ??= []).push(name.join("|"));
  }
  return out;
}

// ---- 개발사항: what admins gather from questions for the developer [ADMIN-10][ADMIN-11] ----
//
// Any admin with "dev" adds an item (from a question in one tap, or by hand), changes or deletes it, and
// links more questions to it; how many questions are linked is how many people asked. Setting 개발중,
// 완료 or 반려 needs "devStatus" as well (the owner has it and gives it). Every change is written to the
// item's own history with who made it and what it was before, and deleting only marks the item, so the
// owner can bring it back with its history whole.

const DEV = {
  statuses: { todo: "접수", doing: "개발중", done: "완료", rejected: "반려" },
  title: 100,
  body: 4000,
  reason: 300,
  note: 1000,
  // Before and after of a long text in the history; enough to see what changed.
  kept: 1000,
  bulk: 100,
  list: 2000,
};

/** [ADMIN-12] What a new server's admins start with in 자주 쓰는 답; they add their own and delete these. */
const DEFAULT_TEMPLATES = [
  ["확인 중", "알려 주셔서 고마워요. 지금 확인하고 있어요. 고치는 대로 다시 알려 드릴게요."],
  ["고쳤어요", "말씀하신 문제를 고쳤어요. 앱을 최신 버전으로 업데이트한 뒤 다시 확인해 주세요."],
  ["더 알려 주세요", "정확히 확인하려면 조금 더 알려 주세요. 언제, 어떤 화면에서, 무엇을 눌렀을 때 생겼는지 적어 주시면 큰 도움이 돼요."],
  ["설정 안내", "설정에서 바로 바꿀 수 있어요. 설정 > 해당 항목에서 원하는 값으로 바꿔 보세요."],
  ["개발 예정", "좋은 의견 고마워요. 개발할 목록에 올려 두었어요. 반영되면 다시 알려 드릴게요."],
  ["반영했어요", "말씀해 주신 내용을 반영했어요. 앱을 최신 버전으로 업데이트하면 쓸 수 있어요."],
];

/** Text an admin typed: control characters out, lines kept, [max] letters; [required] refuses an empty one. */
function plainText(v, max, field, required = false) {
  if (v === undefined || v === null) v = "";
  if (typeof v !== "string") refuse(field, field);
  const text = v.normalize("NFC").replace(/\r\n?/g, "\n").replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, "").trim();
  if ((required && !text) || [...text].length > max) refuse(field, field);
  return text;
}

const cut = (text) => ([...text].length > DEV.kept ? `${[...text].slice(0, DEV.kept).join("")}…` : text);

/** One line of an item's history, by the signed-in admin. */
const devEvent = (env, id, now, action, detail = {}) => stmt(env,
  "INSERT INTO dev_history (item_id, at, actor_id, actor, action, detail) VALUES (?, ?, ?, ?, ?, ?)",
  id, now, env.actor?.id ?? "", actorName(env), action, JSON.stringify(detail));

async function devRoute(request, env, url, path, now) {
  const method = request.method;
  if (path === "/dev" && method === "GET") return json(await devList(env, url.searchParams));
  if (path === "/dev" && method === "POST") return devCreate(request, env, now);
  if (path === "/dev/bulk" && method === "POST") return devBulk(request, env, now);
  const m = path.match(/^\/dev\/(d[A-Za-z0-9]{15})(\/delete|\/restore|\/links|\/links\/remove|\/notes|\/reply)?$/);
  if (!m) return json({ error: "not_found" }, 404);
  const row = await one(env, "SELECT * FROM dev_items WHERE id = ?", m[1]);
  if (!row) return json({ error: "not_found" }, 404);
  if (!m[2] && method === "GET") return json(await devView(env, row));
  if (method !== "POST") return json({ error: "not_found" }, 404);
  const body = await readJson(request, 16 * 1024);
  if (m[2] === "/restore") {
    // Only the owner brings back what someone deleted, so a deletion cannot be undone behind their back.
    if (!env.actor.owner) return json({ error: "no_permission", area: "owner" }, 403);
    if (row.deleted_at === null) return json(await devView(env, row));
    await env.DB.batch([
      stmt(env, "UPDATE dev_items SET deleted_at = NULL, updated_at = ? WHERE id = ?", now, row.id),
      devEvent(env, row.id, now, "restore"),
    ]);
    await audit(env, now, "dev_restore", row.title, row.id);
  } else if (row.deleted_at !== null) {
    return json({ error: "deleted" }, 409);
  } else if (!m[2]) {
    const changed = await devUpdate(env, row, body, now);
    if (changed === "stale") return json({ error: "changed", item: await devView(env, await one(env, "SELECT * FROM dev_items WHERE id = ?", row.id)) }, 409);
  } else if (m[2] === "/delete") {
    await env.DB.batch([
      stmt(env, "UPDATE dev_items SET deleted_at = ?, updated_at = ? WHERE id = ?", now, now, row.id),
      devEvent(env, row.id, now, "delete", { title: row.title }),
    ]);
    await audit(env, now, "dev_delete", row.title, row.id);
  } else if (m[2] === "/links" || m[2] === "/links/remove") {
    // Linking shows a question to whoever opens the item, so it takes a look at questions too.
    if (!can(env.actor, "inquiries")) return json({ error: "no_permission", area: "inquiries" }, 403);
    const inquiry = typeof body.inquiry === "string" && /^[A-Za-z0-9]{22}$/.test(body.inquiry) ? body.inquiry : refuse("inquiry", "inquiry");
    if (m[2] === "/links") {
      if (!(await one(env, "SELECT id FROM inquiries WHERE id = ?", inquiry))) return json({ error: "not_found" }, 404);
      const r = await run(env, "INSERT OR IGNORE INTO dev_links (item_id, inquiry_id, by, at) VALUES (?, ?, ?, ?)", row.id, inquiry, actorName(env), now);
      if (r.meta.changes) {
        await env.DB.batch([stmt(env, "UPDATE dev_items SET updated_at = ? WHERE id = ?", now, row.id), devEvent(env, row.id, now, "link", { inquiry })]);
        await audit(env, now, "dev_link", row.title, inquiry);
      }
    } else {
      const r = await run(env, "DELETE FROM dev_links WHERE item_id = ? AND inquiry_id = ?", row.id, inquiry);
      if (r.meta.changes) {
        await env.DB.batch([stmt(env, "UPDATE dev_items SET updated_at = ? WHERE id = ?", now, row.id), devEvent(env, row.id, now, "unlink", { inquiry })]);
        await audit(env, now, "dev_unlink", row.title, inquiry);
      }
    }
  } else if (m[2] === "/notes") {
    const text = plainText(body.text, DEV.note, "text", true);
    await env.DB.batch([stmt(env, "UPDATE dev_items SET updated_at = ? WHERE id = ?", now, row.id), devEvent(env, row.id, now, "note", { text })]);
  } else {
    return devReply(env, row, body, now);
  }
  return json(await devView(env, await one(env, "SELECT * FROM dev_items WHERE id = ?", row.id)));
}

/** A new item, by hand or from a question (inquiry): the question is linked in the same step. */
async function devCreate(request, env, now) {
  const body = await readJson(request, 16 * 1024);
  const title = plainText(body.title, DEV.title, "title", true);
  const text = plainText(body.body, DEV.body, "body");
  let inquiry = null;
  if (body.inquiry !== undefined && body.inquiry !== null) {
    if (!can(env.actor, "inquiries")) return json({ error: "no_permission", area: "inquiries" }, 403);
    inquiry = typeof body.inquiry === "string" && /^[A-Za-z0-9]{22}$/.test(body.inquiry) ? body.inquiry : refuse("inquiry", "inquiry");
    if (!(await one(env, "SELECT id FROM inquiries WHERE id = ?", inquiry))) return json({ error: "not_found" }, 404);
  }
  const id = `d${randomId(15)}`;
  const a = env.actor;
  await env.DB.batch([
    stmt(env, `INSERT INTO dev_items (id, title, body, status, reason, created_by, created_by_name, assignee, assignee_name, created_at, updated_at, deleted_at)
      VALUES (?, ?, ?, 'todo', '', ?, ?, NULL, NULL, ?, ?, NULL)`, id, title, text, a.id, actorName(env), now, now),
    devEvent(env, id, now, "create", { title, body: cut(text) }),
    ...(inquiry ? [stmt(env, "INSERT INTO dev_links (item_id, inquiry_id, by, at) VALUES (?, ?, ?, ?)", id, inquiry, actorName(env), now),
      devEvent(env, id, now, "link", { inquiry })] : []),
  ]);
  await audit(env, now, "dev_create", title, id);
  return json(await devView(env, await one(env, "SELECT * FROM dev_items WHERE id = ?", id)), 201);
}

/**
 * Title, body, who holds it and its status, in one call; each change is one line of history with what
 * it was. With [since] (the updatedAt the admin's screen showed), a change made meanwhile by someone
 * else is not written over: "stale", and the screen shows the newer item.
 */
async function devUpdate(env, row, body, now) {
  if (body.since !== undefined && body.since !== iso(row.updated_at)) return "stale";
  const stmts = [];
  const notes = [];
  const edit = {};
  const set = {};
  if (body.title !== undefined) {
    const title = plainText(body.title, DEV.title, "title", true);
    if (title !== row.title) [edit.title, set.title] = [[row.title, title], title];
  }
  if (body.body !== undefined) {
    const text = plainText(body.body, DEV.body, "body");
    if (text !== row.body) [edit.body, set.body] = [[cut(row.body), cut(text)], text];
  }
  if (Object.keys(edit).length) {
    stmts.push(devEvent(env, row.id, now, "edit", edit));
    notes.push(["dev_edit", Object.keys(edit).join(" ")]);
  }
  if (body.assignee !== undefined) {
    if (body.assignee !== "me" && body.assignee !== null) refuse("assignee", "assignee");
    const to = body.assignee ? { id: env.actor.id, name: actorName(env) } : null;
    if ((to?.id ?? null) !== row.assignee) {
      set.assignee = to?.id ?? null;
      set.assignee_name = to?.name ?? null;
      stmts.push(devEvent(env, row.id, now, "assign", { assignee: [row.assignee_name ?? "", to?.name ?? ""] }));
    }
  }
  if (body.releasedIn !== undefined) {
    // [INQ-02] The version a finished item went out in, e.g. 1.18.0; blank clears it.
    const v = body.releasedIn === null || body.releasedIn === "" ? null : String(body.releasedIn).trim();
    if (v !== null && !/^\d{1,2}\.\d{1,2}\.\d{1,3}$/.test(v)) refuse("releasedIn", "releasedIn");
    if (v !== (row.released_in ?? null)) {
      set.released_in = v;
      stmts.push(devEvent(env, row.id, now, "release", { releasedIn: [row.released_in ?? "", v ?? ""] }));
    }
  }
  if (body.status !== undefined) {
    const next = devStatus(env, body.status, body.reason);
    if (next.status !== row.status || next.reason !== row.reason) {
      [set.status, set.reason] = [next.status, next.reason];
      stmts.push(devEvent(env, row.id, now, "status", { status: [row.status, next.status], ...(next.reason ? { reason: next.reason } : {}) }));
      notes.push(["dev_status", `${row.status}→${next.status}`]);
    }
  }
  if (!stmts.length) return false;
  const cols = Object.keys(set);
  // Written only over the item as this admin read it: a change that landed meanwhile makes it "stale",
  // and then nothing (not even a history line) is written.
  const updated = await run(env, `UPDATE dev_items SET ${[...cols.map((c) => `${c} = ?`), "updated_at = ?"].join(", ")} WHERE id = ? AND updated_at = ?`,
    ...cols.map((c) => set[c]), now, row.id, row.updated_at);
  if (!updated.meta.changes) return "stale";
  await env.DB.batch(stmts);
  // The activity screen shows the title; the id goes with what changed.
  for (const [action, detail] of notes) await audit(env, now, action, set.title ?? row.title, `${detail} ${row.id}`);
  return true;
}

/** A status this admin may set: devStatus is needed, and 반려 says why (the reason is kept with it). */
function devStatus(env, status, reason) {
  if (!can(env.actor, "devStatus", "edit")) throw new Refuse("no_permission", "devStatus", 403);
  if (!Object.hasOwn(DEV.statuses, status)) refuse("status", "status");
  const why = status === "rejected" ? plainText(reason, DEV.reason, "reason", true) : "";
  return { status, reason: why };
}

/** The owner (or whoever was given 처리) sets one status on many items at once. */
async function devBulk(request, env, now) {
  const body = await readJson(request, 16 * 1024);
  const next = devStatus(env, body.status, body.reason);
  if (!Array.isArray(body.ids) || !body.ids.length || body.ids.length > DEV.bulk || body.ids.some((x) => typeof x !== "string" || !/^d[A-Za-z0-9]{15}$/.test(x))) {
    refuse("ids", "ids");
  }
  const ids = [...new Set(body.ids)];
  const rows = await all(env, `SELECT id, status, reason FROM dev_items WHERE deleted_at IS NULL AND id IN (${ids.map(() => "?").join(", ")})`, ...ids);
  const change = rows.filter((r) => r.status !== next.status || r.reason !== next.reason).sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id));
  if (change.length) {
    await env.DB.batch(change.flatMap((r) => [
      stmt(env, "UPDATE dev_items SET status = ?, reason = ?, updated_at = ? WHERE id = ?", next.status, next.reason, now, r.id),
      devEvent(env, r.id, now, "status", { status: [r.status, next.status], ...(next.reason ? { reason: next.reason } : {}), bulk: change.length }),
    ]));
    await audit(env, now, "dev_bulk", `${change.length}개`, `${next.status}: ${change.map((r) => r.id).join(" ")}`);
  }
  return json({ changed: change.length, ids: change.map((r) => r.id) });
}

/**
 * [ADMIN-11] One answer to every linked question at once, typically when an item is 완료: the admin
 * reads the prefilled text and sends it. [ids] limits it to some of them; [close] closes them too.
 */
async function devReply(env, row, body, now) {
  if (!can(env.actor, "inquiries", "edit")) return json({ error: "no_permission", area: "inquiries" }, 403);
  const text = plainText(body.text, INQUIRY.text, "text", true);
  const linked = (await all(env, "SELECT l.inquiry_id AS id FROM dev_links l JOIN inquiries i ON i.id = l.inquiry_id WHERE l.item_id = ?", row.id)).map((r) => r.id);
  const pick = Array.isArray(body.ids) ? linked.filter((id) => body.ids.includes(id)) : linked;
  if (!pick.length) refuse("ids", "ids");
  const status = body.close === true ? "closed" : "answered";
  await env.DB.batch([
    ...pick.flatMap((id) => [
      stmt(env, "INSERT INTO inquiry_messages (inquiry_id, sender, text, by, at) VALUES (?, 'admin', ?, ?, ?)", id, text, actorName(env), now),
      stmt(env, "UPDATE inquiries SET status = ?, updated_at = ? WHERE id = ?", status, now, id),
    ]),
    stmt(env, "UPDATE dev_items SET updated_at = ? WHERE id = ?", now, row.id),
    devEvent(env, row.id, now, "reply", { count: pick.length, text: cut(text), close: status === "closed" }),
  ]);
  await audit(env, now, "dev_reply", row.title, `${pick.length} ${status} ${row.id}`);
  return json({ answered: pick.length, item: await devView(env, await one(env, "SELECT * FROM dev_items WHERE id = ?", row.id)) });
}

/** The list: one status, open (접수 and 개발중), all, deleted ones, mine, or words in the title or body. */
async function devList(env, params) {
  const status = Object.hasOwn(DEV.statuses, params.get("status") ?? "") || ["open", "all", "deleted"].includes(params.get("status")) ? params.get("status") : "all";
  const q = String(params.get("q") ?? "").trim().slice(0, 40);
  const like = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
  const mine = params.get("who") === "mine" ? env.actor.id : "";
  const [rows, countRows] = (await env.DB.batch([
    // Only links to questions still kept count.
    stmt(env, `SELECT d.*, (SELECT COUNT(*) FROM dev_links l JOIN inquiries i ON i.id = l.inquiry_id WHERE l.item_id = d.id) AS links,
        (SELECT COUNT(*) FROM dev_links l JOIN inquiries i ON i.id = l.inquiry_id WHERE l.item_id = d.id AND i.status = 'open') AS waiting
      FROM dev_items d
      WHERE ((?1 = 'deleted' AND d.deleted_at IS NOT NULL) OR (?1 != 'deleted' AND d.deleted_at IS NULL
          AND (?1 = 'all' OR d.status = ?1 OR (?1 = 'open' AND d.status IN ('todo', 'doing')))))
        AND (?2 = '' OR d.title LIKE ?3 ESCAPE '\\' OR d.body LIKE ?3 ESCAPE '\\')
        AND (?4 = '' OR d.assignee = ?4)
      ORDER BY d.updated_at DESC LIMIT ${DEV.list}`, status, q, like, mine),
    stmt(env, `SELECT CASE WHEN deleted_at IS NULL THEN status ELSE 'deleted' END AS s, COUNT(*) AS n FROM dev_items GROUP BY s`),
  ])).map((r) => r.results);
  const counts = { todo: 0, doing: 0, done: 0, rejected: 0, deleted: 0 };
  for (const r of countRows) if (Object.hasOwn(counts, r.s)) counts[r.s] = r.n;
  counts.open = counts.todo + counts.doing;
  counts.all = counts.open + counts.done + counts.rejected;
  // Still to do: most asked for first. Finished: newest first.
  const rank = { doing: 0, todo: 1, done: 2, rejected: 3 };
  const open = (r) => r.status === "todo" || r.status === "doing";
  rows.sort((a, b) => (["all", "open"].includes(status) ? rank[a.status] - rank[b.status] : 0) || (open(a) && open(b) ? b.links - a.links : 0) || b.updated_at - a.updated_at);
  // [ADMIN-16] A page at a time, after sorting all of the view.
  const { items, next } = paged(rows, page(params));
  return { counts, statuses: DEV.statuses, items: items.map(devItem), next, total: rows.length };
}

const devItem = (r) => ({
  id: r.id,
  title: r.title,
  // The whole body too, so 일괄 복사 hands developers everything without opening each item.
  body: r.body,
  preview: [...r.body].slice(0, 160).join(""),
  status: r.status,
  reason: r.reason,
  releasedIn: r.released_in ?? null,
  links: r.links ?? 0,
  waiting: r.waiting ?? 0,
  createdBy: { id: r.created_by, name: r.created_by_name },
  assignee: r.assignee ? { id: r.assignee, name: r.assignee_name ?? "" } : null,
  createdAt: iso(r.created_at),
  updatedAt: iso(r.updated_at),
  deletedAt: r.deleted_at ? iso(r.deleted_at) : null,
});

/** One item with its whole history and the questions behind it (their words only for who may read questions). */
async function devView(env, row) {
  const [links, history] = (await env.DB.batch([
    stmt(env, `SELECT l.inquiry_id, l.by, l.at, i.kind, i.status, i.updated_at,
        (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id LIMIT 1) AS first
      FROM dev_links l LEFT JOIN inquiries i ON i.id = l.inquiry_id WHERE l.item_id = ? ORDER BY l.at`, row.id),
    stmt(env, "SELECT id, at, actor, action, detail FROM dev_history WHERE item_id = ? ORDER BY id DESC", row.id),
  ])).map((r) => r.results);
  const read = can(env.actor, "inquiries");
  return {
    ...devItem({ ...row, links: links.filter((l) => l.kind).length, waiting: links.filter((l) => l.status === "open").length }),
    statuses: DEV.statuses,
    inquiries: read ? links.filter((l) => l.kind).map((l) => ({
      id: l.inquiry_id, kind: l.kind, status: l.status, preview: [...(l.first ?? "")].slice(0, 160).join(""),
      updatedAt: iso(l.updated_at), linkedBy: l.by, linkedAt: iso(l.at),
    })) : [],
    history: history.map((h) => {
      let detail = {};
      try {
        detail = JSON.parse(h.detail);
      } catch {
        // An old line keeps its action; nothing more to show.
      }
      // The words sent to questions are theirs to read who may read questions, like the previews above.
      if (!read && h.action === "reply" && detail && typeof detail === "object") delete detail.text;
      return { id: h.id, at: iso(h.at), actor: h.actor, action: h.action, detail };
    }),
  };
}

// ---- 자주 쓰는 답, shared by every admin [ADMIN-12] ----

async function templates(env, now) {
  // A new server starts with a few; once they are written, deleting one keeps it deleted. The key goes in
  // first and only the call that put it there seeds, so two admins opening the list at once add them once.
  const first = await run(env, "INSERT OR IGNORE INTO settings (key, value) VALUES ('templates', '1')");
  if (first.meta.changes) {
    await env.DB.batch(DEFAULT_TEMPLATES.map(([label, text]) => stmt(env, "INSERT INTO reply_templates (label, text, by, at) VALUES (?, ?, '', ?)", label, text, now)));
  }
  const rows = await all(env, "SELECT id, label, text, by, topic, uses FROM reply_templates ORDER BY id");
  return { templates: rows.map((r) => ({ id: r.id, label: r.label, text: r.text, by: r.by, topic: r.topic ?? "", uses: r.uses ?? 0 })), topicNames: TOPIC_NAMES };
}

/** [ADMIN-16] A saved answer's topic: one of the question topics, or '' for any question. */
function templateTopic(v) {
  if (v === undefined || v === null || v === "") return "";
  if (typeof v !== "string" || !Object.hasOwn(TOPIC_NAMES, v)) refuse("topic", "topic");
  return v;
}

async function templateRoute(request, env, path, now) {
  const method = request.method;
  if (path === "/templates" && method === "GET") return json(await templates(env, now));
  if (path === "/templates" && method === "POST") {
    await templates(env, now);
    const body = await readJson(request, 8 * 1024);
    const label = plainText(body.label, 20, "label", true);
    const text = plainText(body.text, INQUIRY.text, "text", true);
    const topic = templateTopic(body.topic);
    if ((await one(env, "SELECT COUNT(*) AS n FROM reply_templates")).n >= 40) return json({ error: "too_many" }, 409);
    await run(env, "INSERT INTO reply_templates (label, text, by, at, topic) VALUES (?, ?, ?, ?, ?)", label, text, actorName(env), now, topic);
    await audit(env, now, "template_add", label);
    return json(await templates(env, now), 201);
  }
  // [ADMIN-16] A saved answer changed in place, keeping how often it was used.
  const edit = path.match(/^\/templates\/(\d{1,9})$/);
  if (edit && method === "POST") {
    const row = await one(env, "SELECT label FROM reply_templates WHERE id = ?", Number(edit[1]));
    if (!row) return json({ error: "not_found" }, 404);
    const body = await readJson(request, 8 * 1024);
    const label = plainText(body.label, 20, "label", true);
    const text = plainText(body.text, INQUIRY.text, "text", true);
    await run(env, "UPDATE reply_templates SET label = ?, text = ?, topic = ?, by = ?, at = ? WHERE id = ?", label, text, templateTopic(body.topic),
      actorName(env), now, Number(edit[1]));
    await audit(env, now, "template_edit", label, row.label === label ? "" : `${row.label} → ${label}`);
    return json(await templates(env, now));
  }
  const m = path.match(/^\/templates\/(\d{1,9})\/remove$/);
  if (m && method === "POST") {
    const row = await one(env, "SELECT label FROM reply_templates WHERE id = ?", Number(m[1]));
    if (!row) return json({ error: "not_found" }, 404);
    await run(env, "DELETE FROM reply_templates WHERE id = ?", Number(m[1]));
    await audit(env, now, "template_remove", row.label);
    return json(await templates(env, now));
  }
  return json({ error: "not_found" }, 404);
}

// ---- the developer app's API [ADMIN-01] ----

const COOKIE = "__Host-bamnat_admin";
const cookie = (value, maxAge) => `${COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${maxAge}`;

/**
 * What is still missing on a new server, shown on the login screen, with why; never a name or value that
 * was set. [ADMIN-15] Only what the login screen needs: which optional services are connected is the
 * owner's to see, after signing in (serverState).
 */
function health(env) {
  const why = { password: secretIssue(env, "ADMIN_PASSWORD", 10), salt: secretIssue(env, "RATE_SALT", 1) };
  return { database: Boolean(env.DB), password: why.password === null, salt: why.salt === null, why };
}

/** [ADMIN-15] The setup screen's full state, for the owner: health plus the optional services. */
function serverState(env) {
  // [MON-03] PLAY_SERVICE_ACCOUNT is optional: without it purchases are not checked with Play.
  // [SRV-05] SHARE_BASE_URL and SHARE_ADMIN_TOKEN are optional: without them 공유 링크 신고 says how to connect.
  // [ADMIN-19] VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are optional: without them there is no web push.
  return { ...health(env), mail: mailReady(env), play: playReady(env) !== null, share: shareAdmin(env) !== null, push: pushReady(env) !== null };
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
  if (path === "/overview" || path === "/logout" || path === "/me" || path.startsWith("/me/")) return null;
  if (path === "/queue" || path.startsWith("/groups/") || path.startsWith("/asks/")) return "review";
  if (path.startsWith("/inquiries") || path.startsWith("/templates")) return "inquiries";
  if (path === "/dev" || path.startsWith("/dev/")) return "dev";
  if (path.startsWith("/companies") || path.startsWith("/rules/")) return "companies";
  if (path.startsWith("/rewards")) return "rewards";
  if (path === "/settings" || path === "/settings/sla") return "settings";
  if (path.startsWith("/posts") || path.startsWith("/rates")) return "community";
  if (path.startsWith("/notices") || path === "/switches" || path === "/ops") return "ops";
  if (path === "/metrics") return "metrics";
  if (path.startsWith("/shares")) return "shares";
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
  // [ADMIN-15] The invite token goes in the body, out of logs and history; the GET stays for 1.23 only,
  // for a developer app still cached from before.
  if (path === "/join/info" && method === "POST") return inviteInfo(request, env, url, now);
  if (path === "/join" && method === "GET") return inviteInfo(request, env, url, now);
  if (path === "/join/code" && method === "POST") return joinCode(request, env, now);
  if (path === "/join" && method === "POST") return join(request, env, now);
  const session = await signedIn(request, env, now);
  if (!session) return json({ error: "login" }, 401);
  let res = await signedRoute(request, env, url, path, method, now, session);
  // [ADMIN-16] A list or screen that did not change since the app last had it is a 304 with no body.
  if (method === "GET" && res.status === 200 && (res.headers.get("content-type") ?? "").startsWith("application/json")) {
    const body = await res.text();
    const tag = `"${(await sha256(`etag|${body}`)).slice(0, 27)}"`;
    const headers = new Headers(res.headers);
    headers.set("etag", tag);
    res = (request.headers.get("if-none-match") ?? "").split(/\s*,\s*/).includes(tag)
      ? new Response(null, { status: 304, headers })
      : new Response(body, { status: 200, headers });
  }
  if (session.renewed && !res.headers.has("set-cookie")) {
    res = new Response(res.body, res);
    res.headers.set("set-cookie", session.renewed);
  }
  return res;
}

async function signedRoute(request, env, url, path, method, now, session) {
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
  // [MON-09] A code for this admin's own phone.
  if (path === "/me/link-code" && method === "POST") return linkCode(env, actor, now);
  if (path === "/me/password" && method === "POST") return changePassword(request, env, actor, session.token, now);
  // [ADMIN-19] This browser's web push.
  if (path === "/me/push" && method === "GET") return json(await pushView(env, actor));
  if (path === "/me/push" && method === "POST") return pushSubscribe(request, env, actor, now);
  if (path === "/me/push/remove" && method === "POST") return pushUnsubscribe(request, env, actor);
  if (path === "/me/push/test" && method === "POST") return json(await pushTest(env, actor, now));
  if (path === "/me/logout-all" && method === "POST") {
    await run(env, "DELETE FROM admin_sessions WHERE admin = ?", actor.id);
    await audit(env, now, "logout_all", actor.id);
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  if (path === "/overview" && method === "GET") return json(await overview(env, actor));
  // [ADMIN-15] Which optional services are connected: the owner's (areaFor gives "owner").
  if (path === "/server" && method === "GET") return json(serverState(env));
  if (path === "/audit" && method === "GET") return json(await auditLog(env, url));
  if (path === "/admins" && method === "GET") return json(await adminList(env));
  if (path === "/invites" && method === "POST") return invite(request, env, url, now);
  if (path === "/invites/remove" && method === "POST") return removeInvite(request, env, now);
  let m = path.match(/^\/admins\/(a[A-Za-z0-9]{15})(\/remove)?$/);
  if (m && method === "POST") return m[2] ? removeAdmin(env, m[1], now) : updateAdmin(request, env, m[1], now);
  if (path === "/queue" && method === "GET") return json(await queue(env));
  m = path.match(/^\/groups\/([A-Za-z0-9]{12})\/([0-9a-f]{16})\/(adopt|reject|reopen)$/);
  if (m && method === "POST") return m[3] === "reopen" ? reopen(env, m[1], m[2], now) : decide(request, env, m[1], m[2], m[3], now);
  m = path.match(/^\/asks\/([A-Za-z0-9]{22})$/);
  if (m && method === "POST") return answer(request, env, m[1], now);
  if (path === "/companies" && method === "GET") return json(await companies(env, url.searchParams.get("q"), url.searchParams));
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
  if (path === "/templates" || path.startsWith("/templates/")) return templateRoute(request, env, path, now);
  if (path === "/dev" || path.startsWith("/dev/")) return devRoute(request, env, url, path, now);
  if (path === "/rewards" && method === "GET") return json(await rewards(env, url.searchParams.get("status"), url.searchParams));
  m = path.match(/^\/rewards\/(w[A-Za-z0-9]{22})$/);
  if (m && method === "POST") return setReward(request, env, m[1], now);
  if (path === "/settings" && method === "POST") return saveSettings(request, env, now);
  // [ADMUX-06] 답 목표 (SLA) per kind of question.
  if (path === "/settings/sla" && method === "POST") return saveSla(request, env, now);
  // [MON-10] The 정식 출시 switch and [MON-09] admins' phones are the owner's (areaFor gives "owner").
  if (path === "/launch" && method === "GET") return json(await launchView(env));
  if (path === "/launch" && method === "POST") return setLaunch(request, env, now);
  if (path === "/devices" && method === "GET") return json(await phoneList(env));
  m = path.match(/^\/devices\/(d[A-Za-z0-9]{15})\/(approve|remove)$/);
  if (m && method === "POST") return setPhone(env, m[1], m[2], now);
  if (path === "/posts" && method === "GET") return json(await postList(env, url));
  m = path.match(/^\/posts\/(p[A-Za-z0-9]{15})$/);
  if (m && method === "POST") return moderate(request, env, m[1], now);
  // [PLZ-14] 임금표 to look at, and an admin's call on one.
  if (path === "/rates" && method === "GET") return json(await rateList(env, url));
  m = path.match(/^\/rates\/([A-Za-z0-9]{22})$/);
  if (m && method === "POST") return setRate(request, env, actor, m[1], now);
  // [SRV-04] 공지 and 기능 스위치, [ADMIN-14] 이달 할 일, [ADMIN-13] 지표, [SRV-05] 공유 링크 신고.
  if (path === "/notices" && method === "GET") return json(await noticeList(env, now));
  if (path === "/notices" && method === "POST") return saveNotice(request, env, now);
  m = path.match(/^\/notices\/(n[A-Za-z0-9]{9})\/remove$/);
  if (m && method === "POST") return removeNotice(env, m[1], now);
  if (path === "/switches" && method === "POST") return setSwitch(request, env, now);
  if (path === "/ops" && method === "GET") return json(await opsMonth(env, url.searchParams.get("month"), now));
  if (path === "/ops" && method === "POST") return checkOps(request, env, now);
  if (path === "/metrics" && method === "GET") return json(await metrics(env, url, now));
  if (path === "/shares" && method === "GET") return shareReports(env);
  m = path.match(/^\/shares\/([A-Za-z0-9]{16,32})\/remove$/);
  if (m && method === "POST") return removeShare(request, env, m[1], now);
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
  // [ADMIN-15] Under a wide guessing run (50 wrong in the hour) only a network or account that was wrong
  // this hour is shut out: the owner on a clean network still gets in, so the run cannot lock everyone.
  if ((worst?.count ?? 0) >= LIMITS.loginFailures || ((everyone?.count ?? 0) >= 10 * LIMITS.loginFailures && (worst?.count ?? 0) > 0)) {
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
    // [ADMIN-15] The email tried is kept only as a short hash, and only for 30 days: enough to see that one
    // account is being guessed at, without a list of addresses someone typed.
    await env.DB.batch([stmt(env, "DELETE FROM audit WHERE action = 'login_failed' AND at < ?", now - 30 * DAY)]);
    await audit(env, now, "login_failed", email ? `mail:${(await sha256(`audit|${email}`)).slice(0, 10)}` : "");
    // Each wrong try from the same place answers a little later, so guessing is slow even before the lock.
    await new Promise((done) => setTimeout(done, Math.min(count - 1, 4) * LIMITS.loginDelayMs));
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
  // The account is read with the session on every call, so a pause or a change of permissions applies at once.
  const row = await one(env, `SELECT s.admin AS session_admin, s.expires AS session_expires, s.created_at AS session_made, a.* FROM admin_sessions s
    LEFT JOIN admins a ON a.id = s.admin AND a.status = 'active' WHERE s.token = ?`, hash);
  if (!row || row.session_expires <= now) return null;
  const actor = row.session_admin === OWNER.id ? OWNER : row.id ? actorOf(row) : null;
  if (!actor) return null;
  // [ADMIN-15] Used with less than half the week left, it runs on for another week, never past 30 days.
  let renewed = null;
  const end = Math.min(now + LIMITS.sessionMs, row.session_made + LIMITS.sessionMaxMs);
  if (row.session_expires - now < LIMITS.sessionMs / 2 && end > row.session_expires) {
    await run(env, "UPDATE admin_sessions SET expires = ? WHERE token = ?", end, hash);
    renewed = cookie(token, Math.floor((end - now) / 1000));
  }
  return { token: hash, actor, renewed };
}

const actorOf = (a) => ({ id: a.id, name: a.name, email: a.email, owner: false, perms: readPerms(a.perms) });

// [ADMIN-15] With the yes-or-no areas and the look-only ones, so the app draws each as the server reads it.
const me = (actor) => ({ id: actor.id, name: actor.name, email: actor.email, owner: actor.owner, perms: actor.perms, areas: AREAS,
  toggles: TOGGLES, viewOnly: VIEW_ONLY });

// ---- web push to admins [ADMIN-19] ----
//
// Only with VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY set (src/push.js). Each admin turns it on per browser,
// picks which kinds of news and a quiet time (KST hours, nothing sent then). What is sent says only what
// happened and where to look: never a question's words, an email or a device.

/** What can be sent, and the area an admin needs to get it ("owner" for the owner only). */
const PUSH_KINDS = {
  inquiry: { label: "새 문의", area: "inquiries" },
  urgent: { label: "급한 문의(문제 신고·결제)", area: "inquiries" },
  flagged: { label: "신고로 가려진 글", area: "community" },
  join: { label: "승인을 기다리는 관리자", area: "owner" },
};
const PUSH = { perAdmin: 5, hosts: /^(fcm\.googleapis\.com|updates\.push\.services\.mozilla\.com|push\.services\.mozilla\.com|web\.push\.apple\.com|[a-z0-9-]+\.notify\.windows\.com|[a-z0-9-]+\.push\.apple\.com)$/ };

const mayGet = (actor, kind) => (PUSH_KINDS[kind].area === "owner" ? actor.owner : can(actor, PUSH_KINDS[kind].area));

/** A KST hour of [now] inside quiet hours [from]..[to] (to may be past midnight: 22..7). */
function quietNow(from, to, now) {
  if (from === null || to === null || from === to) return false;
  const h = new Date(now + 9 * HOUR).getUTCHours();
  return from < to ? h >= from && h < to : h >= from || h < to;
}

async function pushView(env, actor) {
  const keys = pushReady(env);
  const rows = await all(env, "SELECT id, kinds, quiet_from, quiet_to, created_at, sent_at FROM push_subscriptions WHERE admin = ? ORDER BY created_at", actor.id);
  return {
    ready: Boolean(keys), publicKey: keys?.publicKey ?? null,
    kinds: Object.entries(PUSH_KINDS).filter(([k]) => mayGet(actor, k)).map(([key, k]) => ({ key, label: k.label })),
    subscriptions: rows.map((r) => ({ id: r.id, kinds: r.kinds.split(",").filter(Boolean), quiet: r.quiet_from === null ? null : { from: r.quiet_from, to: r.quiet_to },
      createdAt: iso(r.created_at), sentAt: r.sent_at ? iso(r.sent_at) : null })),
  };
}

/** The browser's PushSubscription as JSON, checked: an https address at a known push service and its two keys. */
function readSubscription(v) {
  const sub = v && typeof v === "object" ? v : {};
  let url = null;
  try {
    url = new URL(String(sub.endpoint ?? ""));
  } catch {
    refuse("subscription", "endpoint");
  }
  if (url.protocol !== "https:" || !PUSH.hosts.test(url.hostname) || url.port || String(sub.endpoint).length > 1000) refuse("subscription", "endpoint");
  const p256dh = sub.keys?.p256dh;
  const auth = sub.keys?.auth;
  if (typeof p256dh !== "string" || !/^[A-Za-z0-9_-]{80,100}$/.test(p256dh) || typeof auth !== "string" || !/^[A-Za-z0-9_-]{16,32}$/.test(auth)) {
    refuse("subscription", "keys");
  }
  return { endpoint: String(sub.endpoint), p256dh, auth };
}

async function pushSubscribe(request, env, actor, now) {
  if (!pushReady(env)) return json({ error: "push" }, 409);
  const body = await readJson(request, 4096);
  const sub = readSubscription(body.subscription);
  const kinds = Array.isArray(body.kinds) ? [...new Set(body.kinds)] : Object.keys(PUSH_KINDS);
  if (kinds.some((k) => !Object.hasOwn(PUSH_KINDS, k))) refuse("kind", "kinds");
  let quiet = [null, null];
  if (body.quiet) {
    const { from, to } = body.quiet;
    if (![from, to].every((h) => Number.isInteger(h) && h >= 0 && h <= 23)) refuse("quiet", "quiet");
    quiet = [from, to];
  }
  const id = (await sha256(`push|${sub.endpoint}`)).slice(0, 32);
  const mine = await all(env, "SELECT id FROM push_subscriptions WHERE admin = ?", actor.id);
  if (!mine.some((r) => r.id === id) && mine.length >= PUSH.perAdmin) refuse("too_many", "subscription");
  await run(env, `INSERT INTO push_subscriptions (id, admin, endpoint, p256dh, auth, kinds, quiet_from, quiet_to, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (id) DO UPDATE SET admin = excluded.admin, p256dh = excluded.p256dh, auth = excluded.auth, kinds = excluded.kinds,
      quiet_from = excluded.quiet_from, quiet_to = excluded.quiet_to`,
  id, actor.id, sub.endpoint, sub.p256dh, sub.auth, kinds.filter((k) => mayGet(actor, k)).join(","), quiet[0], quiet[1], now);
  return json(await pushView(env, actor));
}

async function pushUnsubscribe(request, env, actor) {
  const body = await readJson(request, 2048);
  const id = typeof body.id === "string" && /^[0-9a-f]{32}$/.test(body.id) ? body.id
    : typeof body.endpoint === "string" ? (await sha256(`push|${body.endpoint}`)).slice(0, 32) : refuse("subscription", "id");
  await run(env, "DELETE FROM push_subscriptions WHERE id = ? AND admin = ?", id, actor.id);
  return json(await pushView(env, actor));
}

/** A test to every browser of this admin, quiet time or not; how many got it and how many were gone. */
async function pushTest(env, actor, now) {
  const keys = pushReady(env);
  if (!keys) return { sent: 0, gone: 0, ready: false };
  const rows = await all(env, "SELECT * FROM push_subscriptions WHERE admin = ?", actor.id);
  return { ...(await deliver(env, keys, rows, { title: "밤낮 관리자", body: "알림이 잘 와요", url: "/admin/", tag: "test" }, now)), ready: true };
}

/** Sends [message] to [rows]; a browser the push service says is gone (404, 410) is forgotten. */
async function deliver(env, keys, rows, message, now) {
  let sent = 0;
  let gone = 0;
  for (const r of rows) {
    const status = await sendPush(keys, r, message, now, message.urgent ? "high" : "normal");
    if (status === 404 || status === 410) {
      gone++;
      await run(env, "DELETE FROM push_subscriptions WHERE id = ?", r.id);
    } else if (status >= 200 && status < 300) {
      sent++;
      await run(env, "UPDATE push_subscriptions SET sent_at = ? WHERE id = ?", now, r.id);
    }
  }
  return { sent, gone };
}

/**
 * [ADMIN-19] News of [kind] to every admin's browser that asked for it, outside its quiet time and only
 * for admins who may see it. Without VAPID secrets (or with nobody asking) it returns at once; it never
 * throws, so whatever called it goes on as if nothing was sent.
 */
export async function notifyAdmins(env, kind, message, now = Date.now()) {
  try {
    const keys = pushReady(env);
    if (!keys || !env.DB || !Object.hasOwn(PUSH_KINDS, kind)) return { sent: 0, gone: 0 };
    // An urgent question is a question too: it goes once to those who asked for either.
    const also = kind === "urgent" ? "inquiry" : kind;
    const rows = await all(env, `SELECT s.*, a.perms, a.status FROM push_subscriptions s LEFT JOIN admins a ON a.id = s.admin
      WHERE (',' || s.kinds || ',') LIKE ? OR (',' || s.kinds || ',') LIKE ?`, `%,${kind},%`, `%,${also},%`);
    const to = rows.filter((r) => {
      const actor = r.admin === OWNER.id ? OWNER : r.status === "active" ? { owner: false, perms: readPerms(r.perms) } : null;
      return actor && mayGet(actor, kind) && !quietNow(r.quiet_from, r.quiet_to, now);
    });
    if (!to.length) return { sent: 0, gone: 0 };
    return await deliver(env, keys, to, { tag: kind, ...message }, now);
  } catch (e) {
    console.error(e && e.stack ? e.stack : String(e));
    return { sent: 0, gone: 0 };
  }
}

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
    const level = p && LEVELS.includes(p[area]) ? p[area] : SINCE[area]?.(p) ?? "none";
    out[area] = VIEW_ONLY.includes(area) && level === "edit" ? "view" : TOGGLES.includes(area) && level === "view" ? "none" : level;
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
    toggles: TOGGLES,
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
  const token = request.method === "POST" ? (await readJson(request, 1024)).token : url.searchParams.get("t");
  const row = await inviteFor(env, token, now);
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
  // [ADMIN-19] The owner's browsers hear of it too, without the email.
  await notifyAdmins(env, "join", { title: "승인을 기다리는 관리자", body: `${name} 님이 관리자 계정을 만들었어요`, url: "/admin/#/admins" }, now);
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
    // What they had taken on is free again for the others; an item's history says so.
    stmt(env, "UPDATE inquiries SET assignee = NULL, assignee_name = NULL WHERE assignee = ?", id),
    stmt(env, `INSERT INTO dev_history (item_id, at, actor_id, actor, action, detail)
      SELECT id, ?, ?, ?, 'assign', json_object('assignee', json_array(COALESCE(assignee_name, ''), '')) FROM dev_items WHERE assignee = ?`,
      now, env.actor?.id ?? "", actorName(env), id),
    stmt(env, "UPDATE dev_items SET assignee = NULL, assignee_name = NULL WHERE assignee = ?", id),
    // The email can be invited again later.
    stmt(env, "DELETE FROM invites WHERE email = ?", a.email),
    // [MON-09] Their phones stop getting 프로 with them.
    stmt(env, "DELETE FROM admin_devices WHERE admin = ?", id),
    stmt(env, "DELETE FROM link_codes WHERE admin = ?", id),
    // [ADMIN-19] Their browsers stop getting web push.
    stmt(env, "DELETE FROM push_subscriptions WHERE admin = ?", id),
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

/** [ADMIN-17] The activity log's 종류 filter: which actions each one holds (a trailing * is a prefix). */
const AUDIT_KINDS = {
  login: ["login", "login_failed", "logout_all", "password"],
  admins: ["invite*", "join", "admin_*", "device_*"],
  review: ["adopt", "reject", "reject_undo", "answer"],
  companies: ["company_*", "alias_*", "merge", "rule_*"],
  rewards: ["reward_*", "pass_move", "trial_stop"],
  community: ["post_*", "rate_*"],
  inquiries: ["inquiry_*", "template_*"],
  dev: ["dev_*"],
  ops: ["notice_*", "switch_*", "ops_*", "share_remove", "launch_*"],
  settings: ["settings", "sla"],
};

/** A KST day (YYYY-MM-DD) as the time it starts, or null. */
const kstStart = (d) => (typeof d === "string" && /^\d{4}-\d{2}-\d{2}$/.test(d) && Number.isFinite(Date.parse(`${d}T00:00:00+09:00`)) ? Date.parse(`${d}T00:00:00+09:00`) : null);

/** [ADMIN-17] someone@example.com as s•••@example.com, for whoever is not the owner. */
const maskEmail = (e) => e.replace(/^([^@])[^@]*@/, "$1•••@");

/**
 * Activity, newest first, 50 at a time; [before] continues from an entry's id. [ADMIN-17] Filtered by
 * who (an admin's id, "owner" or "me"), 종류 and KST days [from]..[to]; each entry says in words what it
 * was about (a company's name, a question's first words, a 개발사항) and where to open it.
 */
async function auditLog(env, url) {
  const p = url.searchParams;
  const before = Number(p.get("before"));
  const actor = p.get("actor") === "me" ? env.actor?.id ?? "" : /^(owner|a[A-Za-z0-9]{15})$/.test(p.get("actor") ?? "") ? p.get("actor") : "";
  const kind = Object.hasOwn(AUDIT_KINDS, p.get("kind") ?? "") ? p.get("kind") : "";
  const from = kstStart(p.get("from"));
  const to = kstStart(p.get("to"));
  const where = ["a.id < ?"];
  const args = [Number.isInteger(before) && before > 0 ? before : Number.MAX_SAFE_INTEGER];
  if (actor) {
    where.push("a.actor_id = ?");
    args.push(actor);
  }
  if (kind) {
    where.push(`(${AUDIT_KINDS[kind].map((k) => (k.endsWith("*") ? "a.action LIKE ? ESCAPE '\\'" : "a.action = ?")).join(" OR ")})`);
    args.push(...AUDIT_KINDS[kind].map((k) => (k.endsWith("*") ? `${k.slice(0, -1).replace(/_/g, "\\_")}%` : k)));
  }
  if (from !== null) {
    where.push("a.at >= ?");
    args.push(from);
  }
  if (to !== null) {
    where.push("a.at < ?");
    args.push(to + DAY);
  }
  const [rows, admins] = (await env.DB.batch([
    stmt(env, `${AUDIT_SELECT} WHERE ${where.join(" AND ")} ORDER BY a.id DESC LIMIT 51`, ...args),
    stmt(env, "SELECT id, name, email FROM admins ORDER BY name"),
  ])).map((r) => r.results);
  const list = rows.slice(0, 50);
  const owner = Boolean(env.actor?.owner);
  const byEmail = new Map(admins.map((x) => [x.email, x.name]));
  return {
    entries: list.map((r) => {
      const email = /^[^\s@]+@[^\s@]+$/.test(r.target) ? r.target : null;
      const about = email ? { label: byEmail.get(email) ?? maskEmail(email), link: "#/admins" } : auditAbout(r);
      return { id: r.id, at: iso(r.at), action: r.action, target: email && !owner ? maskEmail(email) : r.target, detail: auditDetail(r), actor: plainActor(r.actor),
        actorId: r.actor_id || null, ...about };
    }),
    more: rows.length > 50,
    kinds: Object.keys(AUDIT_KINDS),
    actors: [{ id: "owner", name: OWNER.name }, ...admins.map((x) => ({ id: x.id, name: x.name }))],
  };
}

/** [ADMIN-17] What an entry's detail says, in words where it was stored as data. */
function auditDetail(r) {
  if (r.action !== "company_update") return r.detail;
  try {
    const b = JSON.parse(r.detail);
    const out = [];
    if (typeof b.name === "string") out.push(`이름 → ${b.name}`);
    if (typeof b.hidden === "boolean") out.push(b.hidden ? "검색에서 숨김" : "검색에 보임");
    for (const k of ["kind", "job", "region"]) if (typeof b[k] === "string") out.push(`${{ kind: "종류", job: "직군", region: "지역" }[k]} → ${b[k]}`);
    return out.join(" · ") || r.detail;
  } catch {
    return r.detail;
  }
}

/**
 * [ADMIN-17] Activity rows with the names behind their ids read in the same statement: the company of a
 * setup, post or company change, the first words of a question, whether a 개발사항 is still there.
 */
const AUDIT_SELECT = `SELECT a.id, a.at, a.action, a.target, a.detail, a.actor, a.actor_id,
    (SELECT c.name FROM companies c WHERE c.id = substr(CASE WHEN a.action = 'post_autohide' THEN a.detail ELSE a.target END, 1, 12)) AS company_name,
    (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = CASE WHEN a.action IN ('dev_link', 'dev_unlink') THEN a.detail
      WHEN substr(a.action, 1, 8) = 'inquiry_' THEN a.target END ORDER BY m.id LIMIT 1) AS inquiry_first,
    (SELECT 1 FROM dev_items d WHERE d.id = a.detail) AS dev_kept
  FROM audit a`;

/** [ADMIN-17] What an entry was about, in words, and where it opens (or null). */
function auditAbout(r) {
  const words = (t) => [...(t ?? "")].slice(0, 40).join("");
  if (r.action.startsWith("inquiry_") && /^[A-Za-z0-9]{22}$/.test(r.target)) {
    return r.inquiry_first !== null ? { label: `문의 "${words(r.inquiry_first)}"`, link: `#/inquiries/${r.target}` } : { label: "지워진 문의", link: null };
  }
  if (r.action.startsWith("dev_")) return { label: r.target, link: r.dev_kept ? `#/dev/${r.detail}` : null };
  if (r.company_name !== null && r.company_name !== undefined) {
    const review = ["adopt", "reject", "reject_undo", "answer"].includes(r.action);
    const post = r.action.startsWith("post_");
    return { label: r.company_name, link: post ? "#/community" : review ? "#/review" : `#/companies/${r.target.slice(0, 12)}` };
  }
  return { label: "", link: null };
}

/** The home screen's numbers, only for the areas this admin can see. */
async function overview(env, actor) {
  // One trip to the database for every number: the home screen waits on this call.
  const now = Date.now();
  const today = Date.parse(`${kstDay(now)}T00:00:00+09:00`);
  const counts = { settings: `SELECT key, value FROM settings WHERE key IN (${SETTING_KEYS})`,
    // [MON-10] Every screen shows whether 정식 출시 is on.
    live: "SELECT value FROM settings WHERE key = 'billing_live'",
    // [ADMUX-06] 오늘 내가 처리한 것 (sign-ins left out), and the answer targets.
    doneToday: ["SELECT COUNT(*) AS n FROM audit WHERE actor_id = ? AND at >= ? AND action NOT IN ('login', 'login_failed')", actor.id, today],
    sla: SLA_SQL };
  // [ADMIN-17] The home screen's 최근 활동, so it does not read 50 lines of /audit for ten.
  if (can(actor, "audit")) counts.recent = `${AUDIT_SELECT} WHERE a.action NOT IN ('login', 'login_failed') ORDER BY a.id DESC LIMIT 10`;
  if (can(actor, "review")) {
    counts.pending = "SELECT COUNT(*) AS n FROM (SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending')";
    counts.asks = "SELECT COUNT(*) AS n FROM reports WHERE status = 'ask'";
    counts.flagged = `SELECT COUNT(*) AS n FROM rules r WHERE r.withdrawn = 0 AND r.superseded = 0
      AND EXISTS (SELECT 1 FROM votes v WHERE v.company_id = r.company_id AND v.grp = r.grp AND v.ok = 0)`;
    counts.reports = "SELECT COUNT(*) AS n FROM reports";
    counts.queueGroups = `SELECT p.company_id, p.grp, c.name, MIN(p.dept) AS dept, COUNT(*) AS n, MIN(p.created_at) AS since
      FROM reports p JOIN companies c ON c.id = p.company_id WHERE p.status = 'pending' GROUP BY p.company_id, p.grp ORDER BY since LIMIT 5`;
  }
  if (can(actor, "inquiries")) {
    counts.inquiries = "SELECT COUNT(*) AS n FROM inquiries WHERE status = 'open'";
    // [ADMIN-12] 오늘 할 일: the waiting questions I took, and the ones nobody took yet.
    counts.inquiriesMine = ["SELECT COUNT(*) AS n FROM inquiries WHERE status = 'open' AND assignee = ?", actor.id];
    counts.inquiriesFree = "SELECT COUNT(*) AS n FROM inquiries WHERE status = 'open' AND assignee IS NULL";
    // [INQ-04] 놓친 문의 on the home screen, so nothing waits past a day unseen.
    counts.inquiriesLate = ["SELECT COUNT(*) AS n FROM inquiries WHERE status = 'open' AND updated_at < ?", Date.now() - INQUIRY.lateMs];
    counts.inquiriesBad = "SELECT COUNT(*) AS n FROM inquiries WHERE rating = -1 AND status != 'closed'";
    // [ADMUX-06] New questions today and yesterday, the waiting ones for the 할 일 queue, and who holds how many.
    counts.inquiriesToday = ["SELECT COUNT(*) AS n FROM inquiries WHERE created_at >= ?", today];
    counts.inquiriesYesterday = ["SELECT COUNT(*) AS n FROM inquiries WHERE created_at >= ? AND created_at < ?", today - DAY, today];
    counts.queueInquiries = `SELECT i.id, i.kind, i.updated_at, i.assignee, i.assignee_name, i.same,
        (SELECT m.text FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id LIMIT 1) AS first,
        (SELECT m.sender FROM inquiry_messages m WHERE m.inquiry_id = i.id ORDER BY m.id DESC LIMIT 1) AS last
      FROM inquiries i WHERE i.status = 'open' ORDER BY i.updated_at LIMIT 300`;
    counts.assigned = "SELECT assignee AS id, assignee_name AS name, COUNT(*) AS n FROM inquiries WHERE status = 'open' AND assignee IS NOT NULL GROUP BY assignee ORDER BY n DESC";
  }
  if (can(actor, "dev")) {
    // [ADMIN-10] 개발사항 still to look at (접수), being made (개발중), and the ones I hold.
    counts.devTodo = "SELECT COUNT(*) AS n FROM dev_items WHERE deleted_at IS NULL AND status = 'todo'";
    counts.devDoing = "SELECT COUNT(*) AS n FROM dev_items WHERE deleted_at IS NULL AND status = 'doing'";
    counts.devMine = ["SELECT COUNT(*) AS n FROM dev_items WHERE deleted_at IS NULL AND status IN ('todo', 'doing') AND assignee = ?", actor.id];
  }
  if (can(actor, "rewards")) counts.owed = "SELECT COUNT(*) AS n FROM rewards WHERE status = 'owed'";
  if (can(actor, "community")) {
    // [COMM-06] Flagged posts still shown, and ones the flags hid that nobody has looked at yet.
    counts.flaggedPosts = "SELECT COUNT(*) AS n FROM posts WHERE kept = 0 AND (flags > 0 OR hide_reason = 'flags')";
    counts.flaggedPostsLate = ["SELECT COUNT(*) AS n FROM posts WHERE kept = 0 AND status = 'hidden' AND hide_reason = 'flags' AND hidden_at < ?",
      Date.now() - (LIMITS.hiddenReviewDays - 7) * DAY];
    counts.posts = "SELECT COUNT(*) AS n FROM posts WHERE status = 'shown'";
    counts.queuePosts = `SELECT p.id, p.body, p.flags, p.status, p.created_at, c.name FROM posts p JOIN companies c ON c.id = p.company_id
      WHERE p.kept = 0 AND (p.flags > 0 OR p.hide_reason = 'flags') ORDER BY p.flags DESC, p.created_at LIMIT 5`;
  }
  if (can(actor, "companies")) {
    counts.companies = "SELECT COUNT(*) AS n FROM companies WHERE merged_into IS NULL";
    counts.rules = "SELECT COUNT(*) AS n FROM rules WHERE withdrawn = 0 AND superseded = 0";
  }
  // [ADMIN-14] 이달 할 일 still open on the home screen.
  if (can(actor, "ops")) counts.opsDone = ["SELECT value FROM settings WHERE key = ?", `ops:${kstDay(Date.now()).slice(0, 7)}`];
  if (actor.owner) {
    counts.waiting = "SELECT COUNT(*) AS n FROM admins WHERE status = 'pending'";
    // [MON-09] Admins' phones waiting for the owner's 승인.
    counts.phones = "SELECT COUNT(*) AS n FROM admin_devices WHERE status = 'pending'";
  }
  const keys = Object.keys(counts);
  const results = await env.DB.batch(keys.map((k) => (Array.isArray(counts[k]) ? stmt(env, ...counts[k]) : env.DB.prepare(counts[k]))));
  const got = Object.fromEntries(keys.map((k, i) => [k, results[i].results]));
  const out = { me: me(actor), settings: readSettings(got.settings), reasons: REASONS, answers: ANSWERS, hideReasons: HIDE_REASONS,
    flagReasons: FLAGS, salt: secretIssue(env, "RATE_SALT", 1) === null };
  const rows = new Set(["settings", "waiting", "phones", "live", "opsDone", "sla", "recent", "queueInquiries", "queueGroups", "queuePosts", "assigned"]);
  for (const k of keys) {
    if (rows.has(k)) continue;
    out[k] = got[k][0].n;
  }
  // [ADMUX-08] The developer app's build, so an open app can say a new one is out.
  out.build = adminBuild();
  out.sla = readSla(got.sla);
  if (got.recent) {
    out.recent = got.recent.map((r) => ({ id: r.id, at: iso(r.at), action: r.action, target: /@/.test(r.target) && !actor.owner ? maskEmail(r.target) : r.target,
      detail: auditDetail(r), actor: plainActor(r.actor), ...auditAbout(r) }));
  }
  // [ADMUX-06] 할 일: the waiting questions closest to their target, the oldest setups to review and the
  // most flagged posts, each with what the home screen needs to act on it there.
  const queue = [];
  let lateToday = 0;
  if (got.queueInquiries) {
    const end = today + DAY;
    for (const r of got.queueInquiries) {
      if (r.last !== "user") continue;
      const left = slaLeft(r.kind, r.updated_at, out.sla, now);
      if (r.updated_at + out.sla[slaKey(r.kind)] * HOUR < end) lateToday++;
      queue.push({ type: "inquiry", id: r.id, kind: r.kind, text: [...(r.first ?? "")].slice(0, 80).join(""), slaLeft: left, same: r.same ?? 0,
        assignee: r.assignee ? { id: r.assignee, name: r.assignee_name ?? "" } : null, at: iso(r.updated_at) });
    }
    queue.sort((a, b) => a.slaLeft - b.slaLeft);
    queue.splice(8);
    out.assigned = got.assigned.map((a) => ({ id: a.id, name: a.name ?? "", n: a.n }));
    out.slaLateToday = lateToday;
  }
  for (const g of got.queueGroups ?? []) {
    queue.push({ type: "group", id: `${g.company_id}/${g.grp}`, company: g.name, dept: g.dept, n: g.n, at: iso(g.since) });
  }
  for (const p of got.queuePosts ?? []) {
    queue.push({ type: "post", id: p.id, company: p.name, text: [...p.body].slice(0, 80).join(""), flags: p.flags, hidden: p.status === "hidden", at: iso(p.created_at) });
  }
  out.queue = queue;
  if (actor.owner) out.admins = { waiting: got.waiting[0].n, phones: got.phones[0].n };
  out.billingLive = got.live[0]?.value === "1";
  if (got.opsDone) out.opsLeft = opsItems(kstDay(Date.now()).slice(0, 7), got.opsDone[0]?.value).filter((i) => !i.done).length;
  return out;
}

/** Rows of [list] grouped by company and setup. */
function byGroup(list) {
  const out = new Map();
  for (const r of list) {
    const key = `${r.company_id}/${r.grp}`;
    if (!out.has(key)) out.set(key, []);
    out.get(key).push(r);
  }
  return out;
}

/** The score of each setup in [reports] (pending or adopted) and [votes], keyed company/setup. */
function tallies(reports, votes) {
  const sent = byGroup(reports);
  const voted = byGroup(votes);
  const keys = new Set([...sent.keys(), ...voted.keys()]);
  const out = new Map();
  for (const key of keys) {
    const v = voted.get(key) ?? [];
    const rows = [...(sent.get(key) ?? []).map((r) => ({ device: r.device, points: r.points, cluster: r.cluster })),
      ...v.filter((x) => x.ok === 1).map((x) => ({ device: x.device, points: x.verified === 1 ? 2 : 1, cluster: x.cluster }))];
    out.set(key, tally(rows, v.filter((x) => x.ok === 0).map((x) => x.device)));
  }
  return out;
}

const NO_SCORE = { points: 0, persons: 0, networks: 0, bad: 0 };

/**
 * Setups waiting for a decision, the ones with most behind them first, and rules people doubt. Everything
 * is read in one trip to the database, however many setups wait.
 */
async function queue(env) {
  // The same 60 setups in every statement: one order with nothing left to chance, read in one batch.
  const waiting = `WITH g AS (SELECT company_id, grp, MAX(created_at) AS last FROM reports WHERE status = 'pending'
    GROUP BY company_id, grp ORDER BY last DESC, company_id, grp LIMIT 60)`;
  const doubted = `WITH f AS (SELECT r.id FROM rules r WHERE r.withdrawn = 0 AND r.superseded = 0
    AND EXISTS (SELECT 1 FROM votes v WHERE v.company_id = r.company_id AND v.grp = r.grp AND v.ok = 0) ORDER BY r.id LIMIT 30),
    fg AS (SELECT DISTINCT r.company_id, r.grp FROM rules r JOIN f ON f.id = r.id)`;
  const [settingRows, groups, companyRows, reports, votes, posts, ruleRows, rejected, flaggedRows, flaggedReports, flaggedVotes, askRows] =
    (await env.DB.batch([
      env.DB.prepare(`SELECT key, value FROM settings WHERE key IN (${SETTING_KEYS})`),
      env.DB.prepare(`${waiting} SELECT * FROM g`),
      env.DB.prepare(`${waiting} SELECT id, name, hidden FROM companies WHERE id IN (SELECT company_id FROM g)`),
      env.DB.prepare(`${waiting} SELECT r.* FROM reports r JOIN g ON g.company_id = r.company_id AND g.grp = r.grp
        WHERE r.status IN ('pending', 'adopted')`),
      env.DB.prepare(`${waiting} SELECT v.company_id, v.grp, v.device, v.ok, v.verified, v.cluster FROM votes v
        JOIN g ON g.company_id = v.company_id AND g.grp = v.grp`),
      env.DB.prepare(`${waiting} SELECT p.company_id, p.grp, COUNT(*) AS n FROM posts p JOIN g ON g.company_id = p.company_id AND g.grp = p.grp
        WHERE p.status = 'shown' GROUP BY p.company_id, p.grp`),
      env.DB.prepare(`${waiting} SELECT id, company_id, dept_norm, grp, version, setup, withdrawn, superseded FROM rules
        WHERE company_id IN (SELECT company_id FROM g)`),
      env.DB.prepare(`${waiting} SELECT DISTINCT r.company_id, r.grp FROM reports r JOIN g ON g.company_id = r.company_id AND g.grp = r.grp
        WHERE r.status = 'rejected'`),
      env.DB.prepare(`${doubted}
        SELECT r.id, r.company_id, r.grp, r.dept, r.version, r.setup, c.name,
          SUM(CASE WHEN v.ok = 0 AND v.why = 'name' THEN 1 ELSE 0 END) AS names,
          SUM(CASE WHEN v.ok = 0 AND v.why = 'old' THEN 1 ELSE 0 END) AS old
        FROM f JOIN rules r ON r.id = f.id JOIN companies c ON c.id = r.company_id
          JOIN votes v ON v.company_id = r.company_id AND v.grp = r.grp
        GROUP BY r.id`),
      env.DB.prepare(`${doubted} SELECT r.company_id, r.grp, r.device, r.points, r.cluster FROM reports r
        JOIN fg ON fg.company_id = r.company_id AND fg.grp = r.grp WHERE r.status IN ('pending', 'adopted')`),
      env.DB.prepare(`${doubted} SELECT v.company_id, v.grp, v.device, v.ok, v.verified, v.cluster FROM votes v
        JOIN fg ON fg.company_id = v.company_id AND fg.grp = v.grp`),
      env.DB.prepare(`SELECT p.id, p.company_id, p.dept, p.note, p.verified, p.setup, p.created_at, c.name
        FROM reports p JOIN companies c ON c.id = p.company_id WHERE p.status = 'ask' ORDER BY p.created_at LIMIT 60`),
    ])).map((r) => r.results);
  const { adoptPoints, adoptAgree } = readSettings(settingRows);
  const companiesById = new Map(companyRows.map((c) => [c.id, c]));
  const sent = byGroup(reports);
  const scores = tallies(reports, votes);
  const postCounts = new Map(posts.map((p) => [`${p.company_id}/${p.grp}`, p.n]));
  const turnedDown = new Set(rejected.map((r) => `${r.company_id}/${r.grp}`));
  const out = [];
  for (const g of groups) {
    const key = `${g.company_id}/${g.grp}`;
    const c = companiesById.get(g.company_id);
    const mine = sent.get(key) ?? [];
    // The report a setup is shown and adopted from, as representative() picks it.
    const rep = [...mine].sort((a, b) => b.verified - a.verified || b.created_at - a.created_at)[0];
    const s = scores.get(key) ?? NO_SCORE;
    const pending = mine.filter((r) => r.status === "pending").sort((a, b) => a.created_at - b.created_at);
    const rules = ruleRows.filter((r) => r.company_id === g.company_id);
    const active = rules.filter((r) => r.dept_norm === rep.dept_norm && r.withdrawn === 0 && r.superseded === 0)
      .sort((a, b) => b.version - a.version)[0] ?? null;
    // As blocker() decides it.
    const why = active ? "active" : rules.some((r) => r.grp === g.grp && r.withdrawn === 1) ? "withdrawn" : turnedDown.has(key) ? "rejected" : null;
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
      posts: postCounts.get(key) ?? 0,
      // Enough points, but another rule is in use, this one was turned down, everyone sent it from one
      // network or too many say 달라요: only the developer decides.
      blocked: (Boolean(why) || s.networks < 2 || s.persons * 100 < adoptAgree * (s.persons + s.bad)) && s.persons >= 2 && s.points >= adoptPoints,
      turnedDown: why === "withdrawn" || why === "rejected" ? why : null,
      replaces: active ? { id: active.id, version: active.version } : null,
      reports: pending.map((r) => ({ id: r.id, verified: r.verified === 1, note: r.note, at: iso(r.created_at) })),
      lines,
      changes: active ? diffLines(describe(JSON.parse(active.setup)), lines) : null,
    });
  }
  out.sort((a, b) => b.blocked - a.blocked || b.points - a.points || cmp(a.reports[0].at, b.reports[0].at));
  const flaggedScores = tallies(flaggedReports, flaggedVotes);
  const flagged = flaggedRows.map((r) => {
    const s = flaggedScores.get(`${r.company_id}/${r.grp}`) ?? NO_SCORE;
    return { rule: r.id, company: { id: r.company_id, name: r.name }, dept: r.dept, version: r.version, good: s.persons, bad: s.bad,
      names: r.names, old: r.old, lines: describe(JSON.parse(r.setup)) };
  });
  flagged.sort((a, b) => b.bad - a.bad);
  const asks = askRows.map((a) => ({
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

/**
 * [ADMIN-15] 되돌리기 for a 거절: within 10 minutes the reports that 거절 turned down wait for review
 * again, as if it never happened. Later, or for an older 거절, it is too late (a sender's app may have
 * shown it already).
 */
async function reopen(env, companyId, grp, now) {
  const last = await one(env, "SELECT MAX(decided_at) AS at FROM reports WHERE company_id = ? AND grp = ? AND status = 'rejected'", companyId, grp);
  if (!last?.at) return json({ error: "not_found" }, 404);
  if (now - last.at > REOPEN_MS) return json({ error: "too_late" }, 409);
  const r = await run(env, `UPDATE reports SET status = 'pending', reason = NULL, decided_at = NULL
    WHERE company_id = ? AND grp = ? AND status = 'rejected' AND decided_at = ?`, companyId, grp, last.at);
  await audit(env, now, "reject_undo", `${companyId}/${grp}`, String(r.meta.changes));
  return json({ ok: true, reopened: r.meta.changes });
}

const REOPEN_MS = 10 * 60 * 1000;

/** [ADMIN-16] Rows read with one more than a page: the page, and where the next one starts. */
const sqlPage = (rows, { after, limit }) => ({ items: rows.slice(0, limit), next: rows.length > limit ? after + limit : null });

/** [ADMIN-16] A company name typed into a list's search, as LIKE on companies.norm ('' for none). */
function companyLike(q) {
  const n = normName(q).slice(0, 40);
  return n ? `%${n.replace(/[\\%_]/g, (c) => `\\${c}`)}%` : "";
}

async function companies(env, query, params = new URLSearchParams()) {
  const pg = page(params);
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
    ORDER BY c.merged_into IS NOT NULL, c.created_at DESC LIMIT ?3 OFFSET ?4`, q, like, pg.limit + 1, pg.after);
  const { items, next } = sqlPage(rows, pg);
  return {
    next,
    companies: items.map((c) => ({
      id: c.id, name: c.name, hidden: c.hidden === 1, mergedInto: c.merged_into, aliases: c.aliases, rules: c.rules,
      pending: c.pending, reports: c.reports, createdAt: iso(c.created_at),
    })),
  };
}

async function companyInfo(env, id) {
  // One trip for the whole page, however many rules the company has.
  const [[c], intoRows, aliasRows, ruleRows, reports, votes, [pending]] = (await env.DB.batch([
    stmt(env, "SELECT * FROM companies WHERE id = ?", id),
    stmt(env, "SELECT m.id, m.name FROM companies c JOIN companies m ON m.id = c.merged_into WHERE c.id = ?", id),
    stmt(env, "SELECT name FROM aliases WHERE company_id = ? ORDER BY name", id),
    stmt(env, "SELECT * FROM rules WHERE company_id = ? ORDER BY dept_norm, version DESC", id),
    stmt(env, "SELECT company_id, grp, device, points, cluster FROM reports WHERE company_id = ? AND status IN ('pending', 'adopted')", id),
    stmt(env, "SELECT company_id, grp, device, ok, verified, cluster FROM votes WHERE company_id = ?", id),
    stmt(env, "SELECT COUNT(DISTINCT grp) AS n FROM reports WHERE company_id = ? AND status = 'pending'", id),
  ])).map((r) => r.results);
  if (!c) return json({ error: "not_found" }, 404);
  const into = intoRows[0] ?? null;
  const aliases = aliasRows.map((a) => a.name);
  const scores = tallies(reports, votes);
  const rules = ruleRows.map((r) => {
    const s = scores.get(`${id}/${r.grp}`) ?? NO_SCORE;
    return {
      id: r.id, dept: r.dept, version: r.version, source: r.source, adoptedAt: iso(r.adopted_at), withdrawn: r.withdrawn === 1,
      superseded: r.superseded === 1, ok: s.persons, bad: s.bad, lines: describe(JSON.parse(r.setup)),
    };
  });
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
  // [PLZ-06] Taking an adopted rule down is for the owner and admins given 광장 규칙 내리기 (결정 D35);
  // nobody else, the sender included, can make it disappear.
  if (!can(env.actor, "takedown", "edit")) return json({ error: "no_permission", area: "takedown" }, 403);
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

async function rewards(env, status, params = new URLSearchParams()) {
  const wanted = ["owed", "paid", "void"].includes(status) ? status : "owed";
  const pg = page(params);
  // [ADMIN-16] A company's name finds its rewards; a rate's reward has no company and is left out then.
  const like = companyLike(params.get("q"));
  const rows = await all(env, `
    SELECT w.id, w.months, w.status, w.memo, w.created_at, w.paid_at, w.rule_id, w.deferred, p.dept, c.id AS company_id, c.name AS company, u.version
      , t.trade, t.unit, t.region, t.job
    FROM rewards w LEFT JOIN reports p ON p.id = w.report_id LEFT JOIN companies c ON c.id = p.company_id LEFT JOIN rules u ON u.id = w.rule_id
      LEFT JOIN rates t ON t.id = w.report_id
    WHERE w.status = ?1 AND (?2 = '' OR c.norm LIKE ?2 ESCAPE '\\') ORDER BY w.created_at DESC LIMIT ?3 OFFSET ?4`, wanted, like, pg.limit + 1, pg.after);
  const counts = await all(env, "SELECT status, COUNT(*) AS n FROM rewards GROUP BY status");
  const { items, next } = sqlPage(rows, pg);
  return {
    status: wanted,
    next,
    counts: Object.fromEntries(counts.map((x) => [x.status, x.n])),
    rewards: items.map((w) => ({
      id: w.id, months: w.months, status: w.status, memo: w.memo,
      // [PLZ-14] A rate's reward names the rate instead of a company.
      company: w.trade ? { id: "", name: `임금표 · ${JOBS[w.job] ?? ""} ${REGIONS[w.region] ?? "전국"}` } : { id: w.company_id, name: w.company ?? "" },
      dept: w.trade ? `${w.trade} ${RATE_UNITS[w.unit]?.name ?? ""}` : w.dept, rate: Boolean(w.trade),
      version: w.version, ask: w.rule_id === "", createdAt: iso(w.created_at), paidAt: w.paid_at ? iso(w.paid_at) : null, deferred: Boolean(w.deferred),
    })),
  };
}

async function setReward(request, env, id, now) {
  const body = await readJson(request, 2048);
  if (!["owed", "paid", "void"].includes(body.status)) refuse("status", "status");
  const memo = typeof body.memo === "string" ? body.memo.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 200) : null;
  // [MON-08] How long, chosen when it is given: 회사 방식 3, 제안 1, 큰 개선 6~12 (수익화 확정 4장 7).
  if (given(body.months) && (!Number.isInteger(body.months) || body.months < 1 || body.months > MONEY.passMonths)) refuse("months", "months");
  const r = await one(env, "SELECT * FROM rewards WHERE id = ?", id);
  if (!r) return json({ error: "not_found" }, 404);
  const months = body.status === "paid" && given(body.months) ? body.months : r.months;
  // Someone already paying gets the months as a later renewal from Play; anyone else a pass.
  let deferred = null;
  if (body.status === "paid" && r.status !== "paid") {
    try {
      deferred = await deferFor(env, r.device, months, now);
    } catch (e) {
      console.error(`defer ${id}: ${e?.code ?? e}`);
    }
  }
  const note = deferred ? `Play 결제 미룸(다음 결제 ${iso(deferred).slice(0, 10)})${memo ? ` · ${memo}` : ""}` : memo;
  const paidAt = body.status === "paid" ? (r.status === "paid" ? r.paid_at : now) : null;
  const wasDeferred = body.status === "paid" ? (deferred ? 1 : r.status === "paid" ? r.deferred : 0) : 0;
  await run(env, "UPDATE rewards SET status = ?, months = ?, memo = COALESCE(?, memo), paid_at = ?, deferred = ? WHERE id = ?",
    body.status, months, note, paidAt, wasDeferred, id);
  await audit(env, now, `reward_${body.status}`, id, `${months}개월${deferred ? " 미룸" : ""}${memo ? ` ${memo}` : ""}`);
  return json({ ok: true, months, deferred: Boolean(deferred) });
}

async function saveSettings(request, env, now) {
  const body = await readJson(request, 1024);
  if (!Number.isInteger(body.adoptPoints) || body.adoptPoints < 2 || body.adoptPoints > 20) refuse("adoptPoints", "adoptPoints");
  if (!Number.isInteger(body.rewardMonths) || body.rewardMonths < 0 || body.rewardMonths > 24) refuse("rewardMonths", "rewardMonths");
  // [COMM-07] Left out by a developer app from before it, the agreement share stays as it was.
  const agree = body.adoptAgree ?? (await settings(env)).adoptAgree;
  if (!Number.isInteger(agree) || agree < 50 || agree > 100) refuse("adoptAgree", "adoptAgree");
  // [PLZ-03] The plaza's own bars (결정 D32·D33); a developer app from before them leaves them as they were.
  const was = await settings(env);
  const pick = (key, lo, hi) => {
    const v = body[key] ?? was[key];
    if (!Number.isInteger(v) || v < lo || v > hi) refuse(key, key);
    return v;
  };
  const extra = { adopt_persons: pick("adoptPersons", 2, 10), amend_persons: pick("amendPersons", 2, 10), auto_reward: pick("autoReward", 0, 1),
    reward_cap_months: pick("rewardCapMonths", 1, 24), reward_cap_days: pick("rewardCapDays", 30, 365) };
  await env.DB.batch([
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('adopt_points', ?)", String(body.adoptPoints)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('reward_months', ?)", String(body.rewardMonths)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('adopt_agree', ?)", String(agree)),
    ...Object.entries(extra).map(([k, v]) => stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", k, String(v))),
  ]);
  // A lower bar may already be met.
  for (const g of await all(env, "SELECT DISTINCT company_id, grp FROM reports WHERE status = 'pending' LIMIT 200")) {
    await settle(env, g.company_id, g.grp, now);
  }
  for (const t of await all(env, "SELECT id FROM rates WHERE status = 'pending' ORDER BY created_at DESC LIMIT 200")) await settleRate(env, t.id, now);
  await audit(env, now, "settings", "", `${body.adoptPoints}/${body.rewardMonths}/${agree}% · ${extra.adopt_persons}명/${extra.amend_persons}명 · 자동 ${extra.auto_reward ? "켬" : "끔"} ${extra.reward_cap_months}개월/${extra.reward_cap_days}일`);
  return json(await settings(env));
}

/** [ADMUX-06] 답 목표: hours a question of each kind may wait, 1 to 168; a key left out stays as it was. */
async function saveSla(request, env, now) {
  const body = await readJson(request, 1024);
  const was = await slaTargets(env);
  const next = {};
  for (const k of Object.keys(SLA.keys)) {
    const v = body[k] ?? was[k];
    if (!Number.isInteger(v) || v < SLA.least || v > SLA.most) refuse("sla", k);
    next[k] = v;
  }
  await env.DB.batch(Object.entries(next).map(([k, v]) => stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", slaSetting(k), String(v))));
  await audit(env, now, "sla", "", `문제 ${next.slaBug} · 질문 ${next.slaQuestion} · 제안 ${next.slaIdea} · 그 밖 ${next.slaOther}시간`);
  return json({ sla: next });
}

/** [PLZ-14] Rates for admins: "review" (far from the rest, waiting), "pending", "adopted", "hidden". */
async function rateList(env, url) {
  const status = ["review", "pending", "adopted", "hidden"].includes(url.searchParams.get("status")) ? url.searchParams.get("status") : "review";
  const pg = page(url.searchParams);
  // [ADMIN-16] A rate has no company: q finds the trade (직종) typed in it.
  const q = String(url.searchParams.get("q") ?? "").trim().slice(0, 40);
  const like = q ? `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%` : "";
  const rows = await all(env, `SELECT * FROM rates WHERE status = ?1 AND (?2 = '' OR trade LIKE ?2 ESCAPE '\\')
    ORDER BY created_at DESC LIMIT ?3 OFFSET ?4`, status, like, pg.limit + 1, pg.after);
  const counts = await all(env, "SELECT status, COUNT(*) AS n FROM rates GROUP BY status");
  const { items, next } = sqlPage(rows, pg);
  return {
    status,
    next,
    counts: Object.fromEntries(counts.map((x) => [x.status, x.n])),
    rates: items.map((r) => ({ id: r.id, job: r.job, jobName: JOBS[r.job] ?? r.job, region: r.region, regionName: REGIONS[r.region] ?? "전국",
      trade: r.trade, unit: r.unit, unitName: RATE_UNITS[r.unit]?.name ?? r.unit, year: r.year, amount: r.amount, note: r.note,
      agree: r.agree, disagree: r.disagree, writer: r.device.slice(0, 6), at: iso(r.created_at), decidedAt: r.decided_at ? iso(r.decided_at) : null })),
  };
}

/**
 * [PLZ-14] An admin adopts a rate waiting for review, hides one, or brings one back to voting. Taking an
 * adopted rate down needs 광장 규칙 내리기 like a rule (결정 D35).
 */
async function setRate(request, env, actor, id, now) {
  const body = await readJson(request, 1024);
  const status = oneOf(body.status, ["adopted", "hidden", "pending"], "status");
  const r = await one(env, "SELECT * FROM rates WHERE id = ?", id);
  if (!r) return json({ error: "not_found" }, 404);
  if (r.status === "adopted" && status !== "adopted" && !can(actor, "takedown", "edit")) return json({ error: "no_permission", area: "takedown" }, 403);
  if (status === "adopted" && r.status !== "adopted") await adoptRate(env, r, await settings(env), now);
  else await run(env, "UPDATE rates SET status = ?, decided_at = ? WHERE id = ?", status, now, id);
  await audit(env, now, `rate_${status}`, id, `${r.trade} ${r.amount}`);
  return json({ ok: true, status });
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
  const pg = page(url.searchParams);
  const like = companyLike(url.searchParams.get("q"));
  const rows = one_talk
    ? await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
        WHERE p.company_id = ? AND p.grp = ? ORDER BY p.created_at, p.rowid LIMIT 500`, company, grp)
    : status === "flagged"
      ? await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
          WHERE p.kept = 0 AND (p.flags > 0 OR p.hide_reason = 'flags') AND (?1 = '' OR c.norm LIKE ?1 ESCAPE '\\')
          ORDER BY p.flags DESC, p.created_at DESC LIMIT ?2 OFFSET ?3`, like, pg.limit + 1, pg.after)
      : await all(env, `SELECT p.*, c.name FROM posts p JOIN companies c ON c.id = p.company_id
          WHERE (?1 = '' OR c.norm LIKE ?1 ESCAPE '\\') ${status === "hidden" ? "AND p.status = 'hidden'" : ""}
          ORDER BY p.created_at DESC LIMIT ?2 OFFSET ?3`, like, pg.limit + 1, pg.after);
  // [ADMIN-16] One talk comes whole; the lists come a page at a time.
  const next = one_talk ? null : sqlPage(rows, pg).next;
  if (!one_talk && rows.length > pg.limit) rows.length = pg.limit;
  // What each post needs besides itself, for all of them in one trip.
  const ids = JSON.stringify(rows.map((p) => p.id));
  const talks = JSON.stringify([...new Map(rows.map((p) => [`${p.company_id}/${p.grp}`, [p.company_id, p.grp]])).values()]);
  const pairs = "SELECT json_extract(value, '$[0]') AS company_id, json_extract(value, '$[1]') AS grp FROM json_each(?)";
  const [ruleDepts, reportDepts, flagRows, mutedRows] = rows.length ? (await env.DB.batch([
    stmt(env, `SELECT r.company_id, r.grp, r.dept FROM rules r JOIN (${pairs}) t ON t.company_id = r.company_id AND t.grp = r.grp
      ORDER BY r.version DESC`, talks),
    stmt(env, `SELECT r.company_id, r.grp, r.dept FROM reports r JOIN (${pairs}) t ON t.company_id = r.company_id AND t.grp = r.grp`, talks),
    stmt(env, `SELECT post_id, reason, COUNT(*) AS n FROM post_flags WHERE post_id IN (SELECT value FROM json_each(?))
      GROUP BY post_id, reason ORDER BY n DESC, reason`, ids),
    stmt(env, `SELECT device, until FROM muted WHERE until > ? AND device IN (SELECT value FROM json_each(?))`, Date.now(),
      JSON.stringify([...new Set(rows.map((p) => p.device))])),
  ])).map((r) => r.results) : [[], [], [], []];
  const depts = new Map();
  // The newest rule's department, else any report's, as before.
  for (const r of [...ruleDepts, ...reportDepts]) {
    const key = `${r.company_id}/${r.grp}`;
    if (!depts.has(key)) depts.set(key, r.dept);
  }
  const mutedUntil = new Map(mutedRows.map((m) => [m.device, m.until]));
  const out = [];
  for (const p of rows) {
    const key = `${p.company_id}/${p.grp}`;
    const reasons = flagRows.filter((f) => f.post_id === p.id);
    const muted = mutedUntil.has(p.device) ? { until: mutedUntil.get(p.device) } : null;
    out.push({
      id: p.id,
      company: { id: p.company_id, name: p.name },
      setup: p.grp,
      dept: depts.get(key) ?? "",
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
  return { status: one_talk ? "talk" : status, posts: out, next };
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

// ---- 정식 출시, 프로 이용권, 관리자 휴대폰, 구매 확인 [MON-02][MON-03][MON-06][MON-08][MON-09][MON-10] ----

const MONEY = {
  // [MON-10] Phones installed before the launch get this many months of 프로 from it, until changed.
  thanksMonths: 1,
  // [MON-07] Days after install without ads; the app has the same default.
  adQuietDays: 3,
  // [MON-06] Days before a trial ends that the app asks for consent (결정 D17: day 23 of 30).
  trialAskDays: 7,
  // [MON-09] A code from the developer app ties a phone to an admin for ten minutes, once; two phones each.
  linkMs: 10 * 60 * 1000,
  linkCodesPerHour: 10,
  linkTriesPerHour: 10,
  phonesPerAdmin: 2,
  // [MON-08] A recovery code moves passes to a new phone once.
  codesPerDay: 10,
  claimsPerHour: 10,
  passMonths: 12,
  // [MON-03] What the server keeps of a purchase, and for how long after it ends.
  subKeepMs: 30 * DAY,
  // [MON-11] 계약 또는 청약철회 등에 관한 기록: 5년 (전자상거래법 시행령 제6조 제1항).
  consentKeepMs: 5 * 365 * DAY,
  freeFeatures: 30,
};

/** The app's names for features the server may open to everyone; never the other way round. */
const FEATURE = /^[A-Z][A-Z_]{1,39}$/;

async function launchState(env) {
  const rows = await all(env, "SELECT key, value FROM settings WHERE key IN ('billing_live', 'launched_at', 'thanks_months', 'free_features')");
  const get = (k) => rows.find((r) => r.key === k)?.value;
  const thanks = Number(get("thanks_months"));
  let free = [];
  try {
    free = JSON.parse(get("free_features") ?? "[]").filter((f) => typeof f === "string" && FEATURE.test(f));
  } catch {
    free = [];
  }
  const launched = Number(get("launched_at"));
  return {
    billingLive: get("billing_live") === "1",
    launchedAt: Number.isFinite(launched) && launched > 0 ? launched : null,
    thanksMonths: Number.isInteger(thanks) && thanks >= 0 ? thanks : MONEY.thanksMonths,
    freeFeatures: free,
  };
}

/** [MON-02] What every app reads once a day: the 정식 출시 switch and what goes with it. No device, nothing kept. */
async function config(env) {
  const [s, ops] = await Promise.all([launchState(env), opsState(env)]);
  const now = Date.now();
  return {
    v: 1, billingLive: s.billingLive, launchedAt: s.launchedAt ? iso(s.launchedAt) : null, thanksMonths: s.thanksMonths,
    freeFeatures: s.freeFeatures, adQuietDays: MONEY.adQuietDays, trialAskDays: MONEY.trialAskDays,
    // [SRV-04] 공지 running now (the app picks the ones for its version) and what is switched off.
    notices: ops.notices.filter((n) => n.from <= now && now < n.until).map(noticeView), off: ops.off,
  };
}

/** [MON-10] The owner's switch. The first time it goes on is the launch; switching it off and on keeps that day. */
async function setLaunch(request, env, now) {
  const body = await readJson(request, 4096);
  const before = await launchState(env);
  const live = body.billingLive ?? before.billingLive;
  if (typeof live !== "boolean") refuse("billingLive", "billingLive");
  const thanks = body.thanksMonths ?? before.thanksMonths;
  if (!Number.isInteger(thanks) || thanks < 0 || thanks > 12) refuse("thanksMonths", "thanksMonths");
  const free = body.freeFeatures ?? before.freeFeatures;
  if (!Array.isArray(free) || free.length > MONEY.freeFeatures || !free.every((f) => typeof f === "string" && FEATURE.test(f))) {
    refuse("freeFeatures", "freeFeatures");
  }
  const launchedAt = before.launchedAt ?? (live ? now : null);
  await env.DB.batch([
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('billing_live', ?)", live ? "1" : "0"),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('thanks_months', ?)", String(thanks)),
    stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('free_features', ?)", JSON.stringify([...new Set(free)])),
    ...(launchedAt ? [stmt(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('launched_at', ?)", String(launchedAt))] : []),
  ]);
  if (live !== before.billingLive) await audit(env, now, live ? "launch_on" : "launch_off", "", launchedAt ? iso(launchedAt) : "");
  if (thanks !== before.thanksMonths || JSON.stringify(free) !== JSON.stringify(before.freeFeatures)) {
    await audit(env, now, "launch_settings", "", `감사 ${thanks}개월 · 무료로 연 기능 ${free.join(",") || "없음"}`);
  }
  return json(await launchView(env));
}

async function launchView(env) {
  const s = await launchState(env);
  const n = await one(env, "SELECT COUNT(*) AS n FROM subs WHERE state IN ('ACTIVE', 'GRACE') AND expiry > ?", Date.now());
  return { ...(await config(env)), play: playReady(env) !== null, subscribers: n.n, launched: s.launchedAt !== null };
}

/** [MON-08] A date [months] calendar months after [ms], on the last day of a shorter month when needed. */
export function addMonths(ms, months) {
  const d = new Date(ms);
  const day = d.getUTCDate();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() + months);
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  d.setUTCDate(Math.min(day, last));
  return d.getTime();
}

/**
 * [MON-08] Passes given to one install, stacked: each starts when the one before ends, when it was given,
 * or at the launch, whichever is last, so months given while everything was free are not used up.
 * A reward Play paid as a deferred renewal is not a pass.
 */
export function stack(rows, launchedAt) {
  const given = rows.filter((r) => r.status === "paid" && !r.deferred).sort((a, b) => a.paid_at - b.paid_at);
  const months = given.reduce((n, r) => n + r.months, 0);
  if (!launchedAt) return { until: null, waiting: months, items: given };
  let until = 0;
  for (const r of given) until = addMonths(Math.max(until, r.paid_at, launchedAt), r.months);
  return { until: given.length ? until : null, waiting: 0, items: given };
}

async function installOf(request) {
  const id = deviceId(request.headers.get("x-device"));
  if (!id) throw new Refuse("device", "device");
  return deviceHash(id);
}

/** [MON-08][MON-09] What this install has: its passes, its admin link and whether it holds a recovery code. */
async function passes(env, dev, now) {
  const [rows, link, code, s] = await Promise.all([
    all(env, "SELECT months, status, paid_at, deferred, rule_id FROM rewards WHERE device = ?", dev),
    one(env, "SELECT status, admin_name FROM admin_devices WHERE device = ?", dev),
    one(env, "SELECT created_at FROM pass_codes WHERE device = ?", dev),
    launchState(env),
  ]);
  if (link) await run(env, "UPDATE admin_devices SET seen_at = ? WHERE device = ?", now, dev);
  const p = stack(rows, s.launchedAt);
  return {
    until: p.until ? iso(p.until) : null,
    waitingMonths: p.waiting,
    items: p.items.map((r) => ({ months: r.months, at: iso(r.paid_at), why: r.rule_id === "rate" ? "rate" : r.rule_id ? "rule" : "idea" })),
    deferred: rows.filter((r) => r.status === "paid" && r.deferred).length,
    admin: link ? { status: link.status, name: link.admin_name } : null,
    code: Boolean(code),
    checkedAt: iso(now),
  };
}

const passCode = () => randomId(16).toUpperCase().replace(/(.{4})(?=.)/g, "$1-");
const passCodeHash = async (code) => (await sha256(`pass|${String(code).toUpperCase().replace(/[^A-Z0-9]/g, "")}`)).slice(0, 40);

/** [MON-08] /api/passes, /api/passes/code, /api/passes/claim and /api/admin-link, all for one install. */
async function money(request, env, path) {
  const method = request.method;
  const now = Date.now();
  const dev = await installOf(request);
  if (path === "/api/passes" && method === "GET") return json(await passes(env, dev, now));
  if (path === "/api/passes/code" && method === "POST") {
    if (!(await allow(env, `passcode|${dev}`, MONEY.codesPerDay, DAY, now))) return json({ error: "rate_limited" }, 429);
    // A new code replaces the old one; it is shown once and kept only as a hash.
    const code = passCode();
    await env.DB.batch([
      stmt(env, "DELETE FROM pass_codes WHERE device = ?", dev),
      stmt(env, "INSERT INTO pass_codes (hash, device, created_at) VALUES (?, ?, ?)", await passCodeHash(code), dev, now),
    ]);
    return json({ code }, 201);
  }
  if (path === "/api/passes/claim" && method === "POST") {
    const body = await readJson(request, 1024);
    const net = await network(request, env, now);
    if (!(await allow(env, `claim|${net.day}`, MONEY.claimsPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
    if (typeof body.code !== "string" || body.code.length > 40) refuse("code", "code");
    const hash = await passCodeHash(body.code);
    const row = await one(env, "SELECT device FROM pass_codes WHERE hash = ?", hash);
    if (!row) return json({ error: "code", field: "code" }, 404);
    if (row.device !== dev) {
      // The passes move; the old phone keeps nothing and the code is spent.
      await env.DB.batch([
        stmt(env, "UPDATE rewards SET device = ? WHERE device = ?", dev, row.device),
        stmt(env, "DELETE FROM pass_codes WHERE device IN (?, ?)", row.device, dev),
      ]);
      await audit(env, now, "pass_move", dev.slice(0, 8), row.device.slice(0, 8));
    }
    return json(await passes(env, dev, now));
  }
  if (path === "/api/admin-link" && method === "POST") return linkPhone(request, env, dev, now);
  if (path === "/api/admin-link" && method === "DELETE") {
    await run(env, "DELETE FROM admin_devices WHERE device = ?", dev);
    return new Response(null, { status: 204 });
  }
  if (path === "/api/purchases" && method === "POST") return purchase(request, env, dev, now);
  if (path === "/api/consents" && method === "POST") return consent(request, env, dev, now);
  return json({ error: "not_found" }, 404);
}

// ---- 관리자 휴대폰 [MON-09] ----

/** The developer app makes a code for the signed-in admin's own phone. */
async function linkCode(env, actor, now) {
  if (!(await allow(env, `linkcode|${actor.id}`, MONEY.linkCodesPerHour, HOUR, now))) return json({ error: "rate_limited" }, 429);
  let code;
  for (let i = 0; i < 5 && !code; i++) {
    const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
    const c = String(n).padStart(6, "0");
    if (!(await one(env, "SELECT 1 FROM link_codes WHERE code = ? AND expires > ?", c, now))) code = c;
  }
  if (!code) return json({ error: "busy" }, 503);
  await env.DB.batch([
    stmt(env, "DELETE FROM link_codes WHERE admin = ? OR expires < ?", actor.id, now),
    stmt(env, "INSERT INTO link_codes (code, admin, admin_name, expires) VALUES (?, ?, ?, ?)", code, actor.id, actor.name, now + MONEY.linkMs),
  ]);
  return json({ code, expiresAt: iso(now + MONEY.linkMs) }, 201);
}

async function linkPhone(request, env, dev, now) {
  const body = await readJson(request, 1024);
  const net = await network(request, env, now);
  if (!(await allow(env, `link|${net.day}`, MONEY.linkTriesPerHour, HOUR, now)) ||
      !(await allow(env, `link|${dev}`, MONEY.linkTriesPerHour, HOUR, now))) {
    return json({ error: "rate_limited" }, 429);
  }
  if (typeof body.code !== "string" || !/^\d{6}$/.test(body.code)) refuse("code", "code");
  const row = await one(env, "SELECT * FROM link_codes WHERE code = ? AND expires > ?", body.code, now);
  if (!row) return json({ error: "code", field: "code" }, 404);
  const owner = row.admin === OWNER.id;
  if (!owner && !(await one(env, "SELECT 1 FROM admins WHERE id = ? AND status = 'active'", row.admin))) {
    await run(env, "DELETE FROM link_codes WHERE code = ?", body.code);
    return json({ error: "code", field: "code" }, 404);
  }
  const others = await one(env, "SELECT COUNT(*) AS n FROM admin_devices WHERE admin = ? AND device != ?", row.admin, dev);
  if (others.n >= MONEY.phonesPerAdmin) return json({ error: "too_many", field: "code" }, 409);
  const status = owner ? "approved" : "pending";
  await env.DB.batch([
    stmt(env, "DELETE FROM link_codes WHERE code = ?", body.code),
    stmt(env, "DELETE FROM admin_devices WHERE device = ?", dev),
    stmt(env, `INSERT INTO admin_devices (id, device, admin, admin_name, status, created_at, approved_at, seen_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, `d${randomId(15)}`, dev, row.admin, row.admin_name, status, now, owner ? now : null, now),
  ]);
  await audit(env, now, "device_link", row.admin_name, status);
  return json({ status, name: row.admin_name }, 201);
}

async function phoneList(env) {
  const rows = await all(env, "SELECT id, admin, admin_name, status, created_at, approved_at, seen_at FROM admin_devices ORDER BY created_at DESC");
  return {
    devices: rows.map((d) => ({
      id: d.id, admin: d.admin, adminName: d.admin_name, status: d.status, createdAt: iso(d.created_at),
      approvedAt: d.approved_at ? iso(d.approved_at) : null, seenAt: d.seen_at ? iso(d.seen_at) : null,
    })),
    perAdmin: MONEY.phonesPerAdmin,
  };
}

async function setPhone(env, id, action, now) {
  const d = await one(env, "SELECT * FROM admin_devices WHERE id = ?", id);
  if (!d) return json({ error: "not_found" }, 404);
  if (action === "approve") await run(env, "UPDATE admin_devices SET status = 'approved', approved_at = ? WHERE id = ?", now, id);
  else await run(env, "DELETE FROM admin_devices WHERE id = ?", id);
  await audit(env, now, action === "approve" ? "device_approve" : "device_remove", d.admin_name);
  return json(await phoneList(env));
}

// ---- 구매 확인과 체험 전환 동의 [MON-03][MON-06] ----

const PLAY = {
  scope: "https://www.googleapis.com/auth/androidpublisher",
  token: "https://oauth2.googleapis.com/token",
  api: "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
  states: {
    SUBSCRIPTION_STATE_ACTIVE: "ACTIVE", SUBSCRIPTION_STATE_IN_GRACE_PERIOD: "GRACE", SUBSCRIPTION_STATE_CANCELED: "CANCELED",
    SUBSCRIPTION_STATE_ON_HOLD: "ON_HOLD", SUBSCRIPTION_STATE_PAUSED: "PAUSED", SUBSCRIPTION_STATE_EXPIRED: "EXPIRED",
    SUBSCRIPTION_STATE_PENDING: "PENDING", SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED: "EXPIRED",
  },
};
const PRODUCT = /^[a-z0-9_.]{1,40}$/;
const playTokens = new WeakMap();

/** The service account the owner pasted as the secret PLAY_SERVICE_ACCOUNT, or null; its key is never shown. */
function playReady(env) {
  if (typeof env.PLAY_SERVICE_ACCOUNT !== "string" || !env.PLAY_SERVICE_ACCOUNT.trim()) return null;
  try {
    const a = JSON.parse(env.PLAY_SERVICE_ACCOUNT);
    return typeof a.client_email === "string" && typeof a.private_key === "string" ? a : null;
  } catch {
    return null;
  }
}

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (text) => b64url(new TextEncoder().encode(text));

/** An OAuth token for the Play Developer API from the service account (JWT bearer, RS256), kept until near its end. */
async function playToken(env, account, now) {
  const kept = playTokens.get(env.DB ?? env);
  if (kept && kept.until > now + 60_000) return kept.token;
  const pem = account.private_key.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const iat = Math.floor(now / 1000);
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({ iss: account.client_email, scope: PLAY.scope, aud: PLAY.token, iat, exp: iat + 3600 }));
  const sig = b64url(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`)));
  const res = await fetch(PLAY.token, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${head}.${claims}.${sig}`,
  });
  if (!res.ok) throw new Refuse("play_auth", "play", 502);
  const t = await res.json();
  playTokens.set(env.DB ?? env, { token: t.access_token, until: now + (Number(t.expires_in) || 3600) * 1000 });
  return t.access_token;
}

async function playCall(env, method, path, body) {
  const account = playReady(env);
  if (!account) throw new Refuse("play_off", "play", 503);
  const pkg = env.PLAY_PACKAGE || "com.bamnat.app";
  const res = await fetch(`${PLAY.api}/${encodeURIComponent(pkg)}${path}`, {
    method,
    headers: { authorization: `Bearer ${await playToken(env, account, Date.now())}`, ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 404 || res.status === 410) throw new Refuse("purchase", "token", 404);
  if (!res.ok) throw new Refuse("play", "play", 502);
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

/** [MON-03] subscriptionsv2.get read the way the app needs it. */
export function readSub(p, product) {
  const items = Array.isArray(p.lineItems) ? p.lineItems : [];
  const item = items.find((i) => i.productId === product) ?? items[0] ?? {};
  const expiry = Date.parse(item.expiryTime ?? "");
  return {
    state: PLAY.states[p.subscriptionState] ?? "PENDING",
    expiry: Number.isFinite(expiry) ? expiry : null,
    trial: Boolean(item.offerPhase?.freeTrial),
    autoRenew: item.autoRenewingPlan?.autoRenewEnabled === true,
    basePlan: item.offerDetails?.basePlanId ?? "",
    acknowledged: p.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    etag: typeof p.etag === "string" ? p.etag : "",
  };
}

/**
 * [MON-03] The app sends what Play gave it after a purchase and on each start. With the owner's service
 * account the server asks Play itself and keeps the token while the subscription lasts (for consent and
 * reward deferral); without one it keeps nothing and the app goes by the phone's Play.
 */
async function purchase(request, env, dev, now) {
  const body = await readJson(request, 4096);
  if (typeof body.product !== "string" || !PRODUCT.test(body.product)) refuse("product", "product");
  if (typeof body.token !== "string" || body.token.length < 10 || body.token.length > 1000) refuse("token", "token");
  if (!playReady(env)) return json({ verified: false });
  const s = readSub(await playCall(env, "GET", `/purchases/subscriptionsv2/tokens/${encodeURIComponent(body.token)}`), body.product);
  const hash = (await sha256(`sub|${body.token}`)).slice(0, 40);
  await run(env, `INSERT INTO subs (hash, device, token, product, base_plan, state, expiry, trial, auto_renew, created_at, checked_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (hash) DO UPDATE SET device = excluded.device, state = excluded.state, expiry = excluded.expiry, trial = excluded.trial,
      auto_renew = excluded.auto_renew, base_plan = excluded.base_plan, checked_at = excluded.checked_at`,
  hash, dev, body.token, body.product, s.basePlan, s.state, s.expiry, s.trial ? 1 : 0, s.autoRenew ? 1 : 0, now, now);
  const row = await one(env, "SELECT consent_at, stopped_at FROM subs WHERE hash = ?", hash);
  return json({
    verified: true, state: s.state, expiry: s.expiry ? iso(s.expiry) : null, trial: s.trial, autoRenew: s.autoRenew,
    acknowledged: s.acknowledged, consentAt: row.consent_at ? iso(row.consent_at) : null, stoppedAt: row.stopped_at ? iso(row.stopped_at) : null,
  });
}

/**
 * [MON-06] The user's own answer before a trial turns paid (전자상거래법 제13조 6항): "keep" or "cancel",
 * with the price and the date they were shown. Kept as evidence; the trial's record says consent was given.
 */
async function consent(request, env, dev, now) {
  const body = await readJson(request, 2048);
  if (!["keep", "cancel"].includes(body.answer)) refuse("answer", "answer");
  if (typeof body.product !== "string" || !PRODUCT.test(body.product)) refuse("product", "product");
  const price = typeof body.price === "string" ? body.price.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 40) : "";
  const ends = Date.parse(body.trialEnd ?? "");
  if (!price || !Number.isFinite(ends)) refuse("detail", "price");
  if (!(await allow(env, `consent|${dev}`, 20, DAY, now))) return json({ error: "rate_limited" }, 429);
  const detail = JSON.stringify({ answer: body.answer, product: body.product, price, trialEnd: iso(ends), shown: String(body.shown ?? "").slice(0, 300) });
  await env.DB.batch([
    stmt(env, "INSERT INTO consents (device, kind, detail, at) VALUES (?, 'trial_paid', ?, ?)", dev, detail, now),
    ...(body.answer === "keep" ? [stmt(env, "UPDATE subs SET consent_at = ? WHERE device = ? AND trial = 1 AND product = ?", now, dev, body.product)] : []),
  ]);
  return json({ ok: true, at: iso(now) }, 201);
}

/**
 * [MON-06] Hourly: a trial ending within a day whose user has not said "keep" does not turn paid. The
 * next renewal is stopped as if the user had cancelled (they can resubscribe in Play); without the
 * service account nothing can be stopped from here and the app keeps asking instead.
 */
export async function stopUnconsentedTrials(env, now) {
  if (!playReady(env)) return 0;
  const due = await all(env, `SELECT hash, token, product, expiry FROM subs WHERE trial = 1 AND auto_renew = 1 AND consent_at IS NULL
    AND stopped_at IS NULL AND state IN ('ACTIVE', 'GRACE') AND expiry > ? AND expiry <= ?`, now, now + DAY);
  let stopped = 0;
  for (const s of due) {
    try {
      await playCall(env, "POST", `/purchases/subscriptionsv2/tokens/${encodeURIComponent(s.token)}:cancel`,
        { cancellationContext: { cancellationType: "USER_REQUESTED_STOP_RENEWALS" } });
      await run(env, "UPDATE subs SET stopped_at = ?, auto_renew = 0 WHERE hash = ?", now, s.hash);
      await audit(env, now, "trial_stop", s.product, iso(s.expiry));
      stopped++;
    } catch (e) {
      console.error(`trial stop ${s.hash.slice(0, 8)}: ${e?.code ?? e}`);
    }
  }
  return stopped;
}

/**
 * [MON-08] A reward for someone paying a running subscription is given as a later renewal (Play's
 * deferral) instead of a pass running beside what they already pay for. Null when it cannot be.
 */
async function deferFor(env, dev, months, now) {
  if (!playReady(env)) return null;
  const sub = await one(env, `SELECT * FROM subs WHERE device = ? AND trial = 0 AND auto_renew = 1 AND state = 'ACTIVE' AND expiry > ?
    ORDER BY expiry DESC LIMIT 1`, dev, now);
  if (!sub) return null;
  const path = `/purchases/subscriptionsv2/tokens/${encodeURIComponent(sub.token)}`;
  const fresh = readSub(await playCall(env, "GET", path), sub.product);
  if (fresh.state !== "ACTIVE" || !fresh.expiry) return null;
  const seconds = Math.round((addMonths(fresh.expiry, months) - fresh.expiry) / 1000);
  const r = await playCall(env, "POST", `${path}:defer`, { deferralContext: { etag: fresh.etag, deferDuration: `${seconds}s` } });
  const next = Date.parse(r.itemExpiryTimeDetails?.[0]?.expiryTime ?? "") || addMonths(fresh.expiry, months);
  await run(env, "UPDATE subs SET expiry = ?, checked_at = ? WHERE hash = ?", next, now, sub.hash);
  return next;
}

// ---- running the service, 1.22 [SRV-04][SRV-05][SRV-06][STAT-01][COMM-09][COMM-10][ADMIN-13][ADMIN-14] ----

/** A day as people in Korea count it (KST, no daylight saving), "2026-10-22". */
const kstDay = (ms) => new Date(ms + 9 * HOUR).toISOString().slice(0, 10);

/**
 * [SRV-04] What the developer app can switch off for every phone at once. The app hides the way in and
 * says it is resting; the server stops its own part too, so an older app cannot go around it.
 */
const SWITCHES = {
  PLAZA_WRITE: "광장 글쓰기(글·답글·제안·임금표)",
  COMPANY_REPORT: "회사 규칙 보내기",
  INQUIRY_PHOTOS: "문의 사진 받기",
  SHARE_CREATE: "근무표 링크 새로 만들기",
  USAGE_STATS: "사용 통계 받기",
  PLAZA_NEWS: "광장 소식 알림",
};

/** [SRV-04] A notice the app shows as one line over the calendar; tapping it opens the rest. */
const NOTICE = { max: 10, title: 40, body: 500, levels: ["info", "important"], longestDays: 90 };

async function opsState(env) {
  const rows = await all(env, "SELECT key, value FROM settings WHERE key IN ('notices', 'switches_off')");
  const read = (k) => {
    try {
      const v = JSON.parse(rows.find((r) => r.key === k)?.value ?? "[]");
      return Array.isArray(v) ? v : [];
    } catch {
      return [];
    }
  };
  return {
    notices: read("notices").filter((n) => n && typeof n.id === "string" && Number.isFinite(n.from) && Number.isFinite(n.until)),
    off: read("switches_off").filter((s) => Object.hasOwn(SWITCHES, s)),
  };
}

/** [SRV-04] Whether the developer switched [name] off. */
const paused = async (env, name) => (await opsState(env)).off.includes(name);

const noticeView = (n) => ({ id: n.id, title: n.title, body: n.body, level: n.level, from: iso(n.from), until: iso(n.until),
  minApp: n.minApp ?? null, maxApp: n.maxApp ?? null });

/** A notice as the developer app sends it; the app version range is the app's versionCode, either end open. */
function readNotice(body, now) {
  const title = plainText(body.title, NOTICE.title, "title", true);
  const text = plainText(body.body, NOTICE.body, "body", true);
  const level = NOTICE.levels.includes(body.level) ? body.level : refuse("level", "level");
  const from = Date.parse(body.from ?? "");
  const until = Date.parse(body.until ?? "");
  if (!Number.isFinite(from)) refuse("from", "from");
  if (!Number.isFinite(until) || until <= from || until < now || until - from > NOTICE.longestDays * DAY) refuse("until", "until");
  const version = (v, field) => {
    if (v === undefined || v === null || v === "") return null;
    return Number.isInteger(v) && v > 0 && v < 1_000_000 ? v : refuse("app", field);
  };
  const minApp = version(body.minApp, "minApp");
  const maxApp = version(body.maxApp, "maxApp");
  if (minApp !== null && maxApp !== null && maxApp < minApp) refuse("app", "maxApp");
  return { title, body: text, level, from, until, minApp, maxApp };
}

async function noticeList(env, now) {
  const s = await opsState(env);
  return {
    // [ADMUX-07] The newest app this server has heard from, for the notice form's 앱 버전 칸.
    latestApp: await latestApp(env, now),
    notices: s.notices.sort((a, b) => b.from - a.from).map((n) => ({ ...noticeView(n), state: now < n.from ? "later" : now < n.until ? "on" : "ended" })),
    switches: Object.entries(SWITCHES).map(([key, label]) => ({ key, label, off: s.off.includes(key) })),
  };
}

/** [ADMUX-07] The highest app version (name and versionCode) in questions of the last 60 days, or null. */
async function latestApp(env, now) {
  const rows = await all(env, `SELECT DISTINCT json_extract(info, '$.app') AS app FROM inquiries WHERE created_at > ? AND json_valid(info)
    ORDER BY created_at DESC LIMIT 300`, now - 60 * DAY);
  let best = null;
  for (const { app } of rows) {
    const m = String(app ?? "").match(/^\s*([\d.]+)\s*\((\d+)\)/);
    if (m && (!best || Number(m[2]) > best.code)) best = { name: m[1], code: Number(m[2]) };
  }
  return best;
}

/** Adds a notice, or changes one with the same id. Ended ones are dropped when the list is full. */
async function saveNotice(request, env, now) {
  const body = await readJson(request, 4096);
  const n = readNotice(body, now);
  const s = await opsState(env);
  let list = s.notices.filter((x) => x.until > now - 30 * DAY);
  const id = typeof body.id === "string" && /^n[A-Za-z0-9]{9}$/.test(body.id) ? body.id : null;
  if (id && !list.some((x) => x.id === id)) return json({ error: "not_found" }, 404);
  if (id) list = list.map((x) => (x.id === id ? { id, ...n } : x));
  else {
    if (list.length >= NOTICE.max) list = list.filter((x) => x.until > now);
    if (list.length >= NOTICE.max) refuse("too_many", "notices");
    list.push({ id: `n${randomId(9)}`, ...n });
  }
  await run(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('notices', ?)", JSON.stringify(list));
  await audit(env, now, id ? "notice_edit" : "notice_add", n.title);
  return json(await noticeList(env, now));
}

async function removeNotice(env, id, now) {
  const s = await opsState(env);
  const gone = s.notices.find((x) => x.id === id);
  if (!gone) return json({ error: "not_found" }, 404);
  await run(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('notices', ?)", JSON.stringify(s.notices.filter((x) => x.id !== id)));
  await audit(env, now, "notice_remove", gone.title);
  return json(await noticeList(env, now));
}

async function setSwitch(request, env, now) {
  const body = await readJson(request, 1024);
  if (!Object.hasOwn(SWITCHES, body.key)) refuse("key", "key");
  if (typeof body.off !== "boolean") refuse("off", "off");
  const s = await opsState(env);
  const off = body.off ? [...new Set([...s.off, body.key])] : s.off.filter((k) => k !== body.key);
  await run(env, "INSERT OR REPLACE INTO settings (key, value) VALUES ('switches_off', ?)", JSON.stringify(off));
  await audit(env, now, body.off ? "switch_off" : "switch_on", body.key, SWITCHES[body.key]);
  return json(await noticeList(env, now));
}

/**
 * [ADMIN-14] The year's chores, month by month, the same list as docs/ops-calendar.md (a test keeps the
 * two together). Shown on the developer app's home screen in place of mail (결정 D1: no mail key).
 */
const OPS_CALENDAR = [
  [1, "rates-new-year", "새해 최저임금·4대 보험 요율이 앱 계산과 맞는지 확인", "최저임금법 제10조 제2항, 국민건강보험법 시행령 제44조, 노인장기요양보험법 시행령 제4조"],
  [1, "wages-jan", "1월 공표 시중노임단가를 remote/v1/wages.json에 넣기(판 올리고 서명)", "대한건설협회 시중노임단가(1월·9월 공표)"],
  [1, "holidays-this-year", "올해 공휴일·대체공휴일이 달력과 맞는지 확인", "관공서의 공휴일에 관한 규정 제2조·제3조"],
  [3, "tax-table", "근로소득 간이세액표가 바뀌었으면 remote/v1/taxtable.json 고치기(판 올리고 서명)", "소득세법 시행령 별표 2(2026년 개정은 3월 1일 이후 원천징수분부터)"],
  [3, "privacy-review", "처리방침·데이터 보안 답안을 지금 서버와 대조", "개인정보 보호법 제30조, docs/server-data.md"],
  [3, "incident-drill", "유출 대응 절차서를 한 번 따라 해 보기(연락 순서·공지 문구)", "개인정보 보호법 제34조, docs/incident-response.md"],
  [4, "android-preview", "새 Android 미리보기에서 알람·알림 점검", "Play 대상 API 수준 요건(매년 8월 31일)"],
  [5, "store-listing", "스토어 등록정보·화면 사진이 지금 앱과 맞는지 확인", "Google Play 메타데이터 정책"],
  [6, "pension-prepare", "7월부터 쓰는 국민연금 기준소득월액 상하한 준비", "국민연금법 시행령 제5조"],
  [7, "pension-apply", "국민연금 기준소득월액 상하한을 remote/v1/rates.json에 넣어 7월 급여부터 맞추기(판 올리고 서명)", "국민연금법 시행령 제5조"],
  [8, "minwage-next", "다음 해 최저임금 결정(8월 5일까지)을 앱에 넣을 준비", "최저임금법 제8조 제1항"],
  [8, "target-api", "대상 API 수준 마감(8월 31일) 전에 업데이트 출시", "Google Play 대상 API 수준 요건"],
  [9, "wages-sep", "9월 공표 시중노임단가를 remote/v1/wages.json에 넣기(판 올리고 서명)", "대한건설협회 시중노임단가(1월·9월 공표)"],
  [9, "holidays-next", "다음 해 공휴일·대체공휴일·선거일 확인", "관공서의 공휴일에 관한 규정"],
  [10, "health-next", "다음 해 건강보험료율이 결정되면 remote/v1/rates.json에 넣기(결정 시기는 해마다 확인)", "국민건강보험법 시행령 제44조"],
  [11, "ltc-next", "다음 해 장기요양보험료율이 결정되면 remote/v1/rates.json에 넣기(2027년 요율은 결정되는 대로)", "노인장기요양보험법 시행령 제4조"],
  [12, "release-new-year", "새해 요율·최저임금·공휴일을 넣은 업데이트를 1월 1일 전에 출시", "위 항목들"],
  [12, "server-data", "서버 최소 데이터 점검(지울 것은 지우기)", "개인정보 보호법 제21조, docs/server-data.md"],
].map(([month, key, text, source]) => ({ month, key, text, source }));

/** One month's chores with which of them are ticked ([done] is the stored list of keys). */
function opsItems(month, done) {
  let ticked = [];
  try {
    ticked = JSON.parse(done ?? "[]");
  } catch {
    ticked = [];
  }
  const m = Number(month.slice(5, 7));
  return OPS_CALENDAR.filter((i) => i.month === m).map((i) => ({ key: i.key, text: i.text, source: i.source, done: ticked.includes(i.key) }));
}

const MONTH = /^20\d\d-(0[1-9]|1[0-2])$/;

async function opsMonth(env, month, now) {
  const m = MONTH.test(month ?? "") ? month : kstDay(now).slice(0, 7);
  const row = await one(env, "SELECT value FROM settings WHERE key = ?", `ops:${m}`);
  return { month: m, items: opsItems(m, row?.value) };
}

async function checkOps(request, env, now) {
  const body = await readJson(request, 1024);
  if (!MONTH.test(body.month ?? "")) refuse("month", "month");
  const item = OPS_CALENDAR.find((i) => i.key === body.key && i.month === Number(body.month.slice(5, 7)));
  if (!item) refuse("key", "key");
  if (typeof body.done !== "boolean") refuse("done", "done");
  const row = await one(env, "SELECT value FROM settings WHERE key = ?", `ops:${body.month}`);
  const was = opsItems(body.month, row?.value).filter((i) => i.done).map((i) => i.key);
  const next = body.done ? [...new Set([...was, item.key])] : was.filter((k) => k !== item.key);
  await run(env, "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", `ops:${body.month}`, JSON.stringify(next));
  await audit(env, now, body.done ? "ops_done" : "ops_undo", body.month, item.text);
  return json(await opsMonth(env, body.month, now));
}

// [STAT-01] Usage statistics (결정 D2·D42): only phones whose user said yes send, once a day, the days
// before today as event names and counts. The app keeps the same list (core UsageEvents.kt); a name the
// server does not know is skipped, so a newer app still sends the rest.

const USAGE = { days: 7, keepDays: 400, count: 1000, bundlesPerNetworkDay: 6, bytes: 8 * 1024 };

/** Event names and what the dashboard calls them. */
export const EVENTS = {
  open: "앱 열기",
  pay_view: "급여 화면",
  pay_setup: "급여 설정 마침",
  pay_report: "급여 리포트",
  paywall_view: "프로 화면 보기",
  trial_start: "무료 체험 시작",
  subscribe: "구독 시작",
  pro_sub: "프로 구독 중",
  share_made: "근무표 링크 만들기",
  share_received: "받은 근무표 넣기",
  plaza_post: "광장 글쓰기",
  plaza_apply: "광장 규칙 적용",
  company_report: "회사 규칙 보내기",
  inquiry: "문의 보내기",
  backup: "백업 만들기",
  alarm_rang: "근무 알람 울림",
  ics_export: "캘린더로 내보내기",
};

async function usage(request, env) {
  // Switched off, the server takes nothing and the app does not try again until tomorrow.
  if (await paused(env, "USAGE_STATS")) return new Response(null, { status: 204 });
  const body = await readJson(request, USAGE.bytes);
  if (body.v !== 1) refuse("version", "v");
  if (typeof body.app !== "string" || !/^\d{1,2}\.\d{1,3}\.\d{1,3}$/.test(body.app)) refuse("app", "app");
  if (!Array.isArray(body.days) || body.days.length < 1 || body.days.length > USAGE.days) refuse("days", "days");
  const now = Date.now();
  const net = await network(request, env, now);
  if (!(await allow(env, `events|${net.day}`, USAGE.bundlesPerNetworkDay, DAY, now))) return json({ error: "rate_limited" }, 429);
  // A phone's day can be a day off Korea's; one ahead of tomorrow or older than the week is not a day it kept.
  const latest = kstDay(now + DAY);
  const oldest = kstDay(now - (USAGE.days + 1) * DAY);
  const days = new Set();
  const rows = [];
  for (const d of body.days) {
    if (!d || typeof d !== "object" || typeof d.day !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(d.day) ||
        d.day > latest || d.day < oldest || days.has(d.day)) refuse("day", "day");
    days.add(d.day);
    if (!d.events || typeof d.events !== "object" || Array.isArray(d.events)) refuse("events", "events");
    const recent = d.recent ?? [];
    if (!Array.isArray(recent) || recent.length > Object.keys(EVENTS).length * 2) refuse("recent", "recent");
    const counts = new Map();
    for (const [name, n] of Object.entries(d.events)) {
      if (!Number.isInteger(n) || n < 1 || n > USAGE.count) refuse("count", "events");
      if (Object.hasOwn(EVENTS, name)) counts.set(name, n);
    }
    const lately = new Set(recent.filter((e) => typeof e === "string" && Object.hasOwn(EVENTS, e)));
    for (const e of counts.keys()) lately.add(e);
    for (const e of lately) rows.push([d.day, e, counts.has(e) ? 1 : 0, counts.get(e) ?? 0, 1]);
  }
  const app = body.app;
  const stmts = [...days].map((day) => stmt(env, `INSERT INTO usage_days (day, app, phones) VALUES (?, ?, 1)
    ON CONFLICT (day, app) DO UPDATE SET phones = phones + 1`, day, app));
  // D1 binds at most 100 values a query: 16 rows of six.
  for (let i = 0; i < rows.length; i += 16) {
    const part = rows.slice(i, i + 16);
    stmts.push(stmt(env, `INSERT INTO usage_events (day, app, event, phones, total, recent) VALUES ${part.map(() => "(?, ?, ?, ?, ?, ?)").join(", ")}
      ON CONFLICT (day, app, event) DO UPDATE SET phones = phones + excluded.phones, total = total + excluded.total, recent = recent + excluded.recent`,
    ...part.flatMap(([day, e, phones, total, recent]) => [day, app, e, phones, total, recent])));
  }
  await env.DB.batch(stmts);
  return new Response(null, { status: 204 });
}

/**
 * [ADMIN-13] The gate numbers from what consenting phones sent over the last [days] full days. A share
 * is phone-days that did it within 28 days over all phone-days: about how many of the phones in use
 * do it, without knowing which phones they are. Under 30 phones a day it says 데이터 부족.
 */
async function metrics(env, url, now) {
  const days = Math.min(90, Math.max(7, Number.parseInt(url.searchParams.get("days") ?? "28", 10) || 28));
  const to = kstDay(now - DAY);
  const from = kstDay(now - days * DAY);
  // [ADMUX-07] The period just before, day by day, for the chart's dashed line.
  const beforeFrom = kstDay(now - 2 * days * DAY);
  const beforeTo = kstDay(now - (days + 1) * DAY);
  const [dayRows, eventRows, subs, beforeRows] = (await env.DB.batch([
    stmt(env, "SELECT day, SUM(phones) AS phones FROM usage_days WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day", from, to),
    stmt(env, `SELECT event, SUM(phones) AS phones, SUM(total) AS total, SUM(recent) AS recent FROM usage_events
      WHERE day BETWEEN ? AND ? GROUP BY event`, from, to),
    stmt(env, `SELECT SUM(CASE WHEN trial = 1 THEN 1 ELSE 0 END) AS trials, SUM(CASE WHEN trial = 0 THEN 1 ELSE 0 END) AS paid,
      SUM(CASE WHEN auto_renew = 0 THEN 1 ELSE 0 END) AS ending FROM subs WHERE state IN ('ACTIVE', 'GRACE') AND expiry > ?`, now),
    stmt(env, "SELECT day, SUM(phones) AS phones FROM usage_days WHERE day BETWEEN ? AND ? GROUP BY day", beforeFrom, beforeTo),
  ])).map((r) => r.results);
  const was = new Map(beforeRows.map((r) => [r.day, r.phones]));
  const phoneDays = dayRows.reduce((n, r) => n + r.phones, 0);
  const daily = dayRows.length ? Math.round(phoneDays / dayRows.length) : 0;
  const ev = new Map(eventRows.map((r) => [r.event, r]));
  const share = (e) => (phoneDays ? (ev.get(e)?.recent ?? 0) / phoneDays : null);
  const enough = daily >= 30;
  const gate = (name, rule, e, least) => {
    const v = share(e);
    return { name, rule, value: v === null ? null : Math.round(v * 1000) / 10, status: !enough || v === null ? "few" : v * 100 >= least ? "pass" : "fail" };
  };
  const s = subs[0] ?? {};
  return {
    from, to, days, phoneDays, daily, enough,
    series: dayRows.map((r) => ({ day: r.day, phones: r.phones })),
    // The same day [days] earlier for each day of the series, 0 when nothing came that day.
    before: dayRows.map((r) => was.get(kstDay(Date.parse(`${r.day}T00:00:00+09:00`) - days * DAY)) ?? 0),
    events: Object.entries(EVENTS).map(([event, label]) => {
      const r = ev.get(event);
      return { event, label, phones: r?.phones ?? 0, total: r?.total ?? 0, share: share(event) === null ? null : Math.round(share(event) * 1000) / 10 };
    }),
    gates: [
      // Play's numbers come with Play Reporting access (순번 52); until then the row says where to look.
      { gate: "G0", name: "비정상 종료", rule: "1.09% 미만(내부 0.5%)", value: null, status: "play" },
      { gate: "G0", name: "ANR", rule: "0.47% 미만(내부 0.2%)", value: null, status: "play" },
      { gate: "G0", name: "테스터", rule: "12명이 14일", value: null, status: "play" },
      { gate: "G1", ...gate("급여 화면을 본 사용자", "30% 이상", "pay_view", 30) },
      { gate: "G2", ...gate("근무표 링크를 만든 사용자", "15% 이상", "share_made", 15) },
      { gate: "G2", ...gate("유료 구독 사용자", "2.0% 이상", "pro_sub", 2) },
    ],
    funnel: {
      paywall: ev.get("paywall_view")?.phones ?? 0, trial: ev.get("trial_start")?.total ?? 0, subscribe: ev.get("subscribe")?.total ?? 0,
      trials: s.trials ?? 0, paid: s.paid ?? 0, ending: s.ending ?? 0,
    },
    shares: await shareStats(env, days),
  };
}

// [COMM-09] 이 사람 글 안 보기 without a list on the server. Each post carries its writer sealed with a key
// only this server has (AES-GCM, a fresh nonce each time), so the marks of two posts never match and
// reading them ties nothing together. The phone keeps the marks it chose and sends them back (X-Hide);
// the server opens them and leaves those writers out.

const HIDE = { max: 50, token: /^[A-Za-z0-9_-]{64}$/ };
const authorKeys = new Map();

async function authorKey(env) {
  const s = await salt(env);
  let key = authorKeys.get(s);
  if (!key) {
    const raw = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`author|${s}`));
    key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
    authorKeys.set(s, key);
  }
  return key;
}

const unb64url = (text) => Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

async function authorToken(key, device) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = Uint8Array.from(device.match(/../g), (h) => Number.parseInt(h, 16));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
  return b64url(new Uint8Array([...iv, ...sealed]));
}

/** The writers behind the marks in [header]; a mark this server did not make is skipped. */
async function hiddenAuthors(key, header) {
  const out = new Set();
  const marks = String(header ?? "").split(",").map((m) => m.trim()).filter((m) => HIDE.token.test(m)).slice(0, HIDE.max);
  for (const m of marks) {
    try {
      const bytes = unb64url(m);
      const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes.slice(0, 12) }, key, bytes.slice(12)));
      out.add([...plain].map((b) => b.toString(16).padStart(2, "0")).join(""));
    } catch {
      // Sealed with another salt, or not a mark at all.
    }
  }
  return out;
}

/**
 * [COMM-10] 광장 소식 for one phone since [since]: a rule set it uses was replaced by a newer version, and
 * what it sent (a rule set, a proposal, an 임금표 entry) was adopted. Asked once a day; nothing is kept.
 */
async function plazaNews(request, env, url) {
  const dev = await installOf(request);
  const now = Date.now();
  if (await paused(env, "PLAZA_NEWS")) return json({ items: [], now: iso(now), paused: true });
  const asked = Date.parse(url.searchParams.get("since") ?? "");
  const since = Math.max(Number.isFinite(asked) ? asked : now - DAY, now - 14 * DAY);
  const [changed, adopted, rates] = (await env.DB.batch([
    stmt(env, `SELECT n.company_id, c.name, n.dept, n.version, n.adopted_at AS at FROM uses u
        JOIN rules o ON o.company_id = u.company_id AND o.grp = u.grp AND o.superseded = 1
        JOIN rules n ON n.company_id = o.company_id AND n.dept_norm = o.dept_norm AND n.superseded = 0 AND n.withdrawn = 0 AND n.adopted_at > ?
        JOIN companies c ON c.id = n.company_id
      WHERE u.device = ? AND NOT EXISTS (SELECT 1 FROM uses x WHERE x.device = u.device AND x.company_id = n.company_id AND x.grp = n.grp)
      GROUP BY n.id ORDER BY n.adopted_at DESC LIMIT 10`, since, dev),
    stmt(env, `SELECT r.company_id, c.name, r.dept, r.post_id, u.version, r.decided_at AS at FROM reports r
        JOIN companies c ON c.id = r.company_id LEFT JOIN rules u ON u.id = r.rule_id
      WHERE r.device = ? AND r.status = 'adopted' AND r.decided_at > ? ORDER BY r.decided_at DESC LIMIT 10`, dev, since),
    stmt(env, "SELECT trade, region, decided_at AS at FROM rates WHERE device = ? AND status = 'adopted' AND decided_at > ? ORDER BY decided_at DESC LIMIT 10",
      dev, since),
  ])).map((r) => r.results);
  const items = [
    ...changed.map((r) => ({ kind: "changed", company: { id: r.company_id, name: r.name }, dept: r.dept, version: r.version, at: iso(r.at) })),
    ...adopted.map((r) => ({ kind: r.post_id ? "proposal" : "adopted", company: { id: r.company_id, name: r.name }, dept: r.dept,
      version: r.version ?? null, at: iso(r.at) })),
    ...rates.map((r) => ({ kind: "rate", trade: r.trade, region: r.region, at: iso(r.at) })),
  ].sort((a, b) => b.at.localeCompare(a.at));
  return json({ items, now: iso(now) });
}

// [SRV-06] Photos with a question: what the phone made (JPEG, long side 1600px, no metadata) checked again.

const PHOTO = { max: 2, bytes: 700 * 1024, totalBytes: 150 * 1024 * 1024, keepAfterCloseMs: 90 * DAY };

/**
 * A JPEG without what could say where or on what it was taken: APP1~APP15 (Exif, XMP, maker notes) and
 * comments go, the picture stays. Null when it is not a whole JPEG.
 */
export function cleanJpeg(bytes) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  const keep = [bytes.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= bytes.length) {
    if (bytes[i] !== 0xff) return null;
    const marker = bytes[i + 1];
    if (marker === 0xff) {
      i += 1;
      continue;
    }
    if (marker === 0xda) {
      // Start of scan: the picture itself, to the end.
      keep.push(bytes.subarray(i));
      const out = new Uint8Array(keep.reduce((n, p) => n + p.length, 0));
      let at = 0;
      for (const p of keep) {
        out.set(p, at);
        at += p.length;
      }
      return out;
    }
    const length = (bytes[i + 2] << 8) | bytes[i + 3];
    if (length < 2 || i + 2 + length > bytes.length) return null;
    if (!((marker >= 0xe1 && marker <= 0xef) || marker === 0xfe)) keep.push(bytes.subarray(i, i + 2 + length));
    i += 2 + length;
  }
  return null;
}

/** The photos of a new question, or why they were left out ("paused", "full"); a bad photo is refused. */
async function readPhotos(env, raw) {
  if (raw === undefined || raw === null) return { list: [] };
  if (!Array.isArray(raw) || raw.length > PHOTO.max) refuse("photos", "photos");
  const list = raw.map((p) => {
    if (typeof p !== "string" || !/^[A-Za-z0-9+/]+=*$/.test(p) || p.length > Math.ceil((PHOTO.bytes * 4) / 3) + 4) refuse("photo", "photos");
    let bytes;
    try {
      bytes = Uint8Array.from(atob(p), (c) => c.charCodeAt(0));
    } catch {
      refuse("photo", "photos");
    }
    const clean = cleanJpeg(bytes);
    if (!clean || clean.length > PHOTO.bytes) refuse("photo", "photos");
    let text = "";
    for (let i = 0; i < clean.length; i += 0x8000) text += String.fromCharCode(...clean.subarray(i, i + 0x8000));
    return { base64: btoa(text), bytes: clean.length };
  });
  if (!list.length) return { list };
  if (await paused(env, "INQUIRY_PHOTOS")) return { list: [], skipped: "paused" };
  const used = await one(env, "SELECT COALESCE(SUM(bytes), 0) AS n FROM inquiry_photos");
  if (used.n + list.reduce((n, p) => n + p.bytes, 0) > PHOTO.totalBytes) return { list: [], skipped: "full" };
  return { list };
}

async function photoFile(env, id, n) {
  const row = await one(env, "SELECT data FROM inquiry_photos WHERE inquiry_id = ? AND n = ?", id, n);
  if (!row) return json({ error: "not_found" }, 404);
  return new Response(Uint8Array.from(atob(row.data), (c) => c.charCodeAt(0)), {
    headers: { "content-type": "image/jpeg", "cache-control": "private, no-store", "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'none'" },
  });
}

// [SRV-05][STAT-04] The share server, from here: its reported links, taking one down, its day totals.
// Both workers hold the same secret SHARE_ADMIN_TOKEN; this one also SHARE_BASE_URL. Without them the
// developer app says how to connect, and nothing is called.

function shareAdmin(env) {
  const base = typeof env.SHARE_BASE_URL === "string" ? env.SHARE_BASE_URL.trim().replace(/\/+$/, "") : "";
  const token = typeof env.SHARE_ADMIN_TOKEN === "string" ? env.SHARE_ADMIN_TOKEN.trim() : "";
  return /^https:\/\/[A-Za-z0-9.-]+$/.test(base) && token.length >= 20 ? { base, token } : null;
}

async function shareCall(env, method, path) {
  const s = shareAdmin(env);
  if (!s) return null;
  const res = await fetch(`${s.base}${path}`, { method, headers: { authorization: `Bearer ${s.token}` } });
  if (res.status === 204) return {};
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Refuse(data.error === "not_found" ? "not_found" : "share_server", "share", res.status === 404 ? 404 : 502);
  return data;
}

async function shareReports(env) {
  if (!shareAdmin(env)) return json({ connected: false, reports: [] });
  const data = await shareCall(env, "GET", "/api/admin/reports");
  const s = shareAdmin(env);
  return json({ connected: true, reports: (data.reports ?? []).map((r) => ({ ...r, url: `${s.base}/s/${r.id}` })) });
}

async function removeShare(request, env, id, now) {
  const body = await readJson(request, 1024);
  const reason = plainText(body.reason ?? "운영 규칙 위반", 60, "reason", true);
  if (!shareAdmin(env)) return json({ error: "share_server" }, 409);
  await shareCall(env, "DELETE", `/api/admin/share/${id}`);
  await audit(env, now, "share_remove", id, reason);
  return shareReports(env);
}

async function shareStats(env, days) {
  if (!shareAdmin(env)) return null;
  try {
    return await shareCall(env, "GET", `/api/admin/stats?days=${days}`);
  } catch {
    return null;
  }
}

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
