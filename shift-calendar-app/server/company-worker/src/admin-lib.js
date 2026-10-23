// [ADMIN-18] The developer app's pure helpers: no page, no fetch, no storage, so node:test checks them
// as they are. adminFiles() serializes this function next to the app and hands its result to the app
// (`(adminApp)((adminLib)(), build)`), so nothing here may use a name from outside the function.

export function adminLib() {
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

  /**
   * [ADMIN-18] Every error code the server sends, in plain words. test/admin-lib.test.mjs reads the
   * server's source and fails when a code is missing here, so a new one is never shown as English.
   */
  const ERRORS = {
    action: "이 항목에는 할 수 없는 일이에요",
    active: "이 부서에는 지금 쓰는 규칙이 있어요. 그 규칙을 먼저 내려 주세요",
    adoptAgree: "맞았어요 비율은 50~100% 사이로 정해 주세요",
    adoptPersons: "채택에 필요한 사람은 2~10명 사이로 정해 주세요",
    adoptPoints: "채택 점수는 2~20 사이로 정해 주세요",
    adopted: "이미 채택된 항목이에요",
    amendPersons: "규칙 고치기에 필요한 사람은 2~10명 사이로 정해 주세요",
    amends: "고칠 규칙을 다시 골라 주세요",
    amount: "금액은 적을 수 없어요",
    answer: "답을 골라 주세요",
    app: "앱 버전 번호(versionCode)를 숫자로 적어 주세요. 끝 번호는 시작보다 크거나 같아야 해요",
    ask: "이미 답한 요청이에요",
    assignee: "다른 관리자가 맡은 문의예요",
    autoReward: "보상 자동 지급을 켬이나 끔으로 골라 주세요",
    bad_json: "보낸 내용을 읽지 못했어요. 새로 고친 뒤 다시 해 주세요",
    billingLive: "정식 출시 값을 다시 골라 주세요",
    body: "내용이 너무 길어요. 공지는 500자, 개발사항은 4000자까지예요",
    busy: "다른 일을 처리하는 중이에요. 잠시 뒤 다시 해 주세요",
    change: "바꿀 내용을 다시 확인해 주세요",
    changed: "다른 관리자가 먼저 고쳤어요. 바뀐 내용을 불러왔어요",
    code: "확인 코드가 맞지 않아요",
    code_expired: "확인 코드가 만료됐어요. 새 코드를 받아 주세요",
    company: "회사 이름을 두 글자 이상, 40자 안으로 적어 주세요",
    content_type: "보낸 내용을 읽지 못했어요. 새로 고친 뒤 다시 해 주세요",
    count: "숫자를 다시 확인해 주세요",
    day: "날짜를 다시 확인해 주세요",
    days: "기간을 다시 골라 주세요",
    deleted: "지운 항목이에요. 최고 관리자가 되살린 뒤 고칠 수 있어요",
    detail: "내용을 다시 확인해 주세요",
    device: "기기 정보를 읽지 못했어요",
    done: "이미 처리했어요",
    email: "메일 주소를 다시 확인해 주세요",
    events: "보낸 기록을 읽지 못했어요",
    exists: "이미 있어요",
    forbidden: "이 화면을 새로 고친 뒤 다시 해 주세요",
    freeFeatures: "기능 이름은 영어 대문자와 _로 적어 주세요(예: MONEY_STATS)",
    from: "시작 날짜를 확인해 주세요",
    good: "도움이 됐는지를 다시 골라 주세요",
    hidden: "가려진 항목이에요",
    ids: "고른 항목이 없거나 100개를 넘었어요",
    info: "함께 보낸 정보를 읽지 못했어요",
    inquiry: "문의를 다시 골라 주세요",
    invite: "초대 링크가 만료됐거나 이미 쓰였어요. 최고 관리자에게 새 링크를 부탁해 주세요",
    job: "직군을 다시 골라 주세요",
    key: "없는 항목이에요",
    kind: "종류를 다시 골라 주세요",
    label: "이름을 1~20자로 적어 주세요",
    level: "공지 종류를 골라 주세요",
    locked: "여러 번 틀려서 잠시 잠겼어요. 한 시간 뒤 다시 해 주세요",
    login: "로그인이 끝났어요. 다시 로그인해 주세요",
    mail_failed: "메일을 보내지 못했어요. 잠시 뒤 다시 해 주세요",
    merge: "합칠 수 없는 회사예요",
    month: "달을 다시 골라 주세요",
    months: "보상은 1~12개월로 골라 주세요",
    muted: "쓰기가 막힌 기기예요",
    name: "이름을 1~30자로 적어 주세요",
    nick: "별명을 다시 확인해 주세요",
    no_database: "데이터베이스가 연결되지 않았어요",
    no_mail: "메일이 연결되지 않은 서버예요. 초대 링크로 확인해 주세요",
    no_permission: "이 일을 할 권한이 없어요. 최고 관리자에게 부탁해 주세요",
    not_answered: "아직 답하지 않은 문의예요",
    not_found: "이미 처리됐거나 없는 항목이에요",
    not_set: "서버에 관리자 비밀번호가 아직 없어요. 설정 안내를 따라 넣어 주세요",
    note: "메모가 너무 길어요. 1000자까지예요",
    off: "꺼 둔 기능이에요",
    offline: "인터넷에 연결되지 않았어요",
    ok: "값을 다시 골라 주세요",
    own: "내 항목에는 할 수 없어요",
    owner_password: "최고 관리자 비밀번호는 Cloudflare의 ADMIN_PASSWORD에서 바꿔요",
    parent: "답글을 달 글을 다시 골라 주세요",
    password: "비밀번호는 10자 이상이어야 해요",
    paused: "지금은 꺼 둔 기능이에요",
    perms: "권한을 다시 골라 주세요",
    photo: "사진을 읽지 못했어요",
    photos: "사진은 두 장까지예요",
    play: "Play 구매 정보를 다시 확인해 주세요",
    play_auth: "Play 연결 정보(PLAY_SERVICE_ACCOUNT)를 확인해 주세요",
    play_off: "Play 구매 확인이 꺼져 있어요",
    product: "상품을 다시 골라 주세요",
    public: "모두의 문의가 아니에요",
    purchase: "구매 정보를 다시 확인해 주세요",
    rate_limited: "잠시 뒤 다시 해 주세요",
    reason: "이유를 적어 주세요",
    recent: "너무 최근 항목이에요. 잠시 뒤 다시 해 주세요",
    releasedIn: "반영된 앱 버전을 1.23.0처럼 적어 주세요",
    rewardCapDays: "상한을 세는 기간은 30~365일 사이로 정해 주세요",
    rewardCapMonths: "한 사람 상한은 1~24개월 사이로 정해 주세요",
    rewardMonths: "보상 개월은 0~24 사이로 정해 주세요",
    same: "이미 같은 것을 보냈어요",
    server: "서버에 문제가 생겼어요. 잠시 뒤 다시 해 주세요",
    setup: "설정 파일을 읽지 못했어요",
    setup_version: "앱을 최신 버전으로 업데이트해 주세요",
    share_server: "공유 서버에 닿지 않았어요. SHARE_BASE_URL과 SHARE_ADMIN_TOKEN을 확인해 주세요",
    sla: "답 목표는 1~168시간 사이로 정해 주세요",
    status: "상태를 다시 골라 주세요",
    taken: "이미 쓰인 항목이에요",
    text: "답을 적어 주세요",
    thanksMonths: "감사 보상은 0~12개월로 정해 주세요",
    title: "제목을 적어 주세요. 공지는 40자, 개발사항은 100자까지예요",
    to: "맡길 사람을 다시 골라 주세요",
    too_late: "되돌릴 수 있는 10분이 지났어요",
    token: "링크가 맞지 않아요. 받은 링크를 다시 열어 주세요",
    too_large: "내용이 너무 길어요",
    too_many: "더 넣을 수 없어요. 쓰지 않는 것을 먼저 지워 주세요",
    topic: "주제를 다시 골라 주세요",
    trade: "직종 이름을 다시 확인해 주세요",
    until: "끝 날짜는 시작보다 뒤, 오늘 이후, 90일 안으로 정해 주세요",
    verified: "확인 여부를 다시 골라 주세요",
    version: "앱 버전을 다시 확인해 주세요",
    weak_password: "더 다양한 글자로 비밀번호를 만들어 주세요. 메일 주소와 같으면 안 돼요",
    withdrawn: "이미 내린 규칙이에요",
    wrong: "비밀번호가 맞지 않아요",
    push: "웹 푸시가 연결되지 않은 서버예요",
    subscription: "알림 구독 정보를 읽지 못했어요. 알림을 껐다 다시 켜 주세요",
    quiet: "조용한 시간을 0~23시로 골라 주세요",
  };
  const errorText = (code) => ERRORS[code] ?? "처리하지 못했어요. 잠시 뒤 다시 해 주세요";

  function ago(iso, now = Date.now()) {
    if (!iso) return "";
    const s = (now - new Date(iso).getTime()) / 1000;
    if (s < 60) return "방금";
    if (s < 3600) return `${Math.floor(s / 60)}분 전`;
    if (s < 86400) return `${Math.floor(s / 3600)}시간 전`;
    if (s < 7 * 86400) return `${Math.floor(s / 86400)}일 전`;
    const d = new Date(iso);
    return `${d.getMonth() + 1}월 ${d.getDate()}일`;
  }

  /** [ADMUX-06] Minutes left before an answer target, as a pill's words and tone. */
  function slaText(minutes) {
    if (minutes === null || minutes === undefined) return null;
    const span = (m) => (m < 60 ? `${m}분` : m < 48 * 60 ? `${Math.floor(m / 60)}시간` : `${Math.floor(m / 1440)}일`);
    if (minutes < 0) return { text: `목표 ${span(-minutes)} 넘김`, tone: "bad" };
    return { text: `${span(minutes)} 남음`, tone: minutes < 120 ? "warn" : "" };
  }

  const LEVELS = ["none", "view", "edit"];
  const can = (me, area, level = "view") => Boolean(me && (me.owner || LEVELS.indexOf(me.perms?.[area] ?? "none") >= LEVELS.indexOf(level)));

  /** [ADMIN-15] An admin's permissions in one line, with yes-or-no areas (the server's toggles) as such. */
  function permSummary(perms, areas, toggles = []) {
    const parts = [];
    for (const [area, name] of Object.entries(areas)) {
      const level = perms?.[area] ?? "none";
      if (level === "none") continue;
      parts.push(toggles.includes(area) ? name : `${name} ${level === "edit" ? "고치기" : "보기"}`);
    }
    return parts.length ? parts.join(" · ") : "권한 없음";
  }

  // ---- [ADMUX-02] where the app is, in the address ----

  /** The filters each screen keeps in the address, with what they are when left out. */
  const FILTERS = {
    inquiries: { status: "open", who: "", topic: "", q: "", sort: "default" },
    dev: { status: "open", who: "", q: "" },
    review: { show: "all" },
    community: { show: "flagged", q: "" },
    rewards: { status: "owed", q: "" },
    rates: { status: "review", q: "" },
    companies: { q: "" },
    metrics: { days: "28" },
    audit: { actor: "", kind: "", from: "", to: "" },
    ops: { month: "" },
  };

  /** "#/inquiries/abc?status=open&q=알람" as { name, id, params }; params hold only known filters. */
  function parseRoute(hash) {
    const raw = String(hash ?? "").replace(/^#\/?/, "");
    const [path, query = ""] = raw.split("?");
    const [name, id] = path.split("/").map((p) => {
      try {
        return decodeURIComponent(p);
      } catch {
        return "";
      }
    });
    const known = FILTERS[name] ?? {};
    const params = { ...known };
    for (const pair of query.split("&")) {
      if (!pair) continue;
      const [k, v = ""] = pair.split("=");
      let key;
      let value;
      try {
        key = decodeURIComponent(k);
        value = decodeURIComponent(v.replace(/\+/g, " "));
      } catch {
        continue;
      }
      if (Object.hasOwn(known, key)) params[key] = value.slice(0, 60);
    }
    return { name: name || "home", id: id || null, params: cleanFilters(name, params) };
  }

  /** The address for a screen; filters at their default are left out, so links stay short. */
  function routeHash(name, id = null, params = {}) {
    const known = FILTERS[name] ?? {};
    const clean = cleanFilters(name, { ...known, ...params });
    const query = Object.entries(clean).filter(([k, v]) => Object.hasOwn(known, k) && v !== "" && v !== known[k])
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
    return `#/${name}${id ? `/${encodeURIComponent(id)}` : ""}${query ? `?${query}` : ""}`;
  }

  /** [ADMUX-02] Filters that cannot go together are put right: 놓친 것 is only ever a waiting question. */
  function cleanFilters(name, params) {
    const p = { ...params };
    if (name === "inquiries" && p.who === "late") p.status = "open";
    return p;
  }

  // ---- [ADMUX-03] the bottom tabs ----

  /**
   * The four tabs at the bottom of a phone: the ones the admin chose if they can still see them, else
   * the first visible screens; the fifth is 더 보기. [current] is which tab lights up, so a screen not in
   * the tabs lights 더 보기, and [back] says whether that screen shows a back button.
   */
  function bottomTabs(visible, chosen = [], current = "home") {
    const keys = visible.map((n) => n.key);
    let tabs = (Array.isArray(chosen) ? chosen : []).filter((k) => keys.includes(k));
    if (!tabs.includes("home")) tabs.unshift("home");
    for (const k of keys) if (tabs.length < 4 && !tabs.includes(k)) tabs.push(k);
    tabs = tabs.slice(0, 4);
    const inTabs = tabs.includes(current);
    return { tabs, lit: inTabs ? current : "more", back: !inTabs && current !== "more" };
  }

  // ---- 개발사항 ----

  const words = (t) => new Set(String(t).toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length >= 2));

  /** Items most like [text] first: how many words they share, then how many asked for them. */
  function likeFirst(items, text) {
    const mine = words(text);
    const score = (d) => [...words(`${d.title} ${d.body ?? ""}`)].filter((w) => mine.has(w) || [...mine].some((m) => m.length >= 3 && (w.includes(m) || m.includes(w)))).length;
    return items.map((d) => [score(d), d]).sort((a, b) => b[0] - a[0] || b[1].links - a[1].links).map(([n, d]) => ({ ...d, like: n }));
  }

  const DEV_NAMES = { todo: "접수", doing: "개발중", done: "완료", rejected: "반려" };

  /** [ADMIN-10] 일괄 복사: plain text that reads as a Markdown list, to hand to developers as it is. */
  function devCopyText(items, today = new Date().toLocaleDateString("ko-KR")) {
    const out = [`# 개발사항 ${items.length}건 (${today})`, ""];
    items.forEach((x, i) => {
      out.push(`${i + 1}. [${DEV_NAMES[x.status]}] ${x.title} — 문의 ${x.links}건`);
      if (x.status === "rejected" && x.reason) out.push(`   반려 이유: ${x.reason}`);
      for (const line of (x.body ?? "").split("\n")) if (line.trim()) out.push(`   ${line}`);
      out.push("");
    });
    return out.join("\n").trimEnd();
  }

  /** 0 to 4: length and the kinds of letters used; only a hint, the server decides. */
  function strength(pw) {
    if (!pw) return 0;
    let n = 0;
    if (pw.length >= 10) n++;
    if (pw.length >= 14) n++;
    const kinds = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((r) => r.test(pw)).length;
    if (kinds >= 2) n++;
    if (kinds >= 3) n++;
    return pw.length < 10 ? Math.min(n, 1) : n;
  }

  // ---- [ADMIN-16] saved answers ----

  /** {앱버전}, {주제} and {오늘} in a saved answer, filled from the question being answered. */
  function fillTemplate(text, { app = "", topic = "", today = "" } = {}) {
    const version = String(app ?? "").replace(/\s*\(\d+\)\s*$/, "");
    return String(text).replace(/\{앱버전\}/g, version || "최신").replace(/\{주제\}/g, topic || "말씀하신 내용").replace(/\{오늘\}/g, today);
  }

  /** The question's topic first, then the most used, then the oldest. */
  const sortTemplates = (list, topic) => [...list].sort((a, b) => ((b.topic === topic) - (a.topic === topic)) || (b.uses ?? 0) - (a.uses ?? 0) || a.id - b.id);

  // ---- [ADMUX-07] before and after, CSV, charts ----

  /**
   * Word by word what changed from [a] to [b], as HTML with <del> and <ins> (escaped). Spaces stay with
   * their word; past 2000 words each side it gives the two whole texts instead of a slow table.
   */
  function wordDiff(a, b) {
    const x = String(a ?? "").split(/(\s+)/).filter((t) => t !== "");
    const y = String(b ?? "").split(/(\s+)/).filter((t) => t !== "");
    if (x.length > 2000 || y.length > 2000) return `<del>${esc(a)}</del><ins>${esc(b)}</ins>`;
    const n = x.length;
    const m = y.length;
    const t = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) t[i][j] = x[i] === y[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
    const out = [];
    const push = (kind, s) => {
      const last = out[out.length - 1];
      if (last && last[0] === kind) last[1] += s;
      else out.push([kind, s]);
    };
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (x[i] === y[j]) {
        push("=", x[i]);
        i++;
        j++;
      } else if (t[i + 1][j] >= t[i][j + 1]) push("-", x[i++]);
      else push("+", y[j++]);
    }
    while (i < n) push("-", x[i++]);
    while (j < m) push("+", y[j++]);
    return out.map(([k, s]) => (k === "=" ? esc(s) : k === "-" ? `<del>${esc(s)}</del>` : `<ins>${esc(s)}</ins>`)).join("");
  }

  /**
   * Rows as CSV for a spreadsheet: [columns] is [header, (row) => value]. A UTF-8 mark goes first so Excel
   * reads Korean, and a cell that starts like a formula gets a ' in front so it is never run.
   */
  function csv(rows, columns) {
    const cell = (v) => {
      let s = v === null || v === undefined ? "" : String(v);
      if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
      return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lines = [columns.map(([h]) => cell(h)).join(","), ...rows.map((r) => columns.map(([, f]) => cell(f(r))).join(","))];
    return `﻿${lines.join("\r\n")}\r\n`;
  }

  /** Round steps for a chart's axis up to [max]: 0 and 3 to 5 more, each 1, 2 or 5 times a power of ten. */
  function ticks(max, want = 4) {
    if (!(max > 0)) return [0, 1];
    const raw = max / want;
    const pow = 10 ** Math.floor(Math.log10(raw));
    const step = [1, 2, 5, 10].map((f) => f * pow).find((s) => s >= raw);
    const out = [];
    for (let v = 0; v < max + step / 2; v += step) out.push(Math.round(v * 1e6) / 1e6);
    if (out[out.length - 1] < max) out.push(out[out.length - 1] + step);
    return out;
  }

  /**
   * [ADMUX-07] A bar chart as SVG with no style attributes (the page's CSP allows none): bars for
   * [values], a dashed line for [before] (the period before), round steps on the axis and every bar's
   * label and number in <title> for a pointer. The numbers also go in a table for screen readers.
   */
  function barChart(values, { labels = [], before = null, width = 640, height = 180, name = "" } = {}) {
    const top = Math.max(1, ...values, ...(before ?? []));
    const steps = ticks(top);
    const max = steps[steps.length - 1];
    const left = 34;
    const bottom = 20;
    const plotH = height - bottom - 8;
    const w = (width - left) / Math.max(1, values.length);
    const y = (v) => 8 + plotH - (v / max) * plotH;
    const grid = steps.map((s) => `<line class="grid" x1="${left}" x2="${width}" y1="${y(s).toFixed(1)}" y2="${y(s).toFixed(1)}"/><text class="axis" x="${left - 6}" y="${(y(s) + 4).toFixed(1)}" text-anchor="end">${s}</text>`).join("");
    const bars = values.map((v, i) => {
      const h = Math.max(v > 0 ? 1 : 0, (v / max) * plotH);
      return `<rect class="bar" x="${(left + i * w + w * 0.15).toFixed(1)}" y="${(8 + plotH - h).toFixed(1)}" width="${Math.max(1, w * 0.7).toFixed(1)}" height="${h.toFixed(1)}" rx="2"><title>${esc(labels[i] ?? "")} ${v}</title></rect>`;
    }).join("");
    const line = before && before.length === values.length
      ? `<polyline class="before" fill="none" points="${before.map((v, i) => `${(left + i * w + w / 2).toFixed(1)},${y(v).toFixed(1)}`).join(" ")}"/>` : "";
    const every = Math.max(1, Math.ceil(values.length / 7));
    const xs = labels.map((l, i) => (i % every === 0 ? `<text class="axis" x="${(left + i * w + w / 2).toFixed(1)}" y="${height - 4}" text-anchor="middle">${esc(l)}</text>` : "")).join("");
    const table = `<table class="sr"><caption>${esc(name)}</caption><thead><tr><th>날짜</th><th>값</th>${before ? "<th>이전 기간</th>" : ""}</tr></thead><tbody>${values.map((v, i) => `<tr><td>${esc(labels[i] ?? "")}</td><td>${v}</td>${before ? `<td>${before[i] ?? ""}</td>` : ""}</tr>`).join("")}</tbody></table>`;
    return `<svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(name)}">${grid}${bars}${line}${xs}</svg>${table}`;
  }

  // ---- [ADMUX-05] colour ----

  /** WCAG contrast of two #RRGGBB colours, 1 to 21. */
  function contrast(a, b) {
    const lum = (hex) => {
      const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    };
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  }

  // ---- [ADMUX-04] keys ----

  /** Where g and a letter go. */
  const GO = { h: "home", i: "inquiries", d: "dev", r: "review", c: "companies", t: "community", w: "rewards", o: "ops", m: "metrics", a: "admins", l: "audit", s: "settings" };

  /**
   * What a key press means, or null: [key] as KeyboardEvent.key, [mods] {ctrl, meta, alt, shift},
   * [typing] true in a text box, [pending] "g" after g was pressed. Typing in a box only Ctrl+Enter,
   * Ctrl+K and Escape count.
   */
  function keyAction(key, mods = {}, typing = false, pending = "") {
    const ctrl = Boolean(mods.ctrl || mods.meta);
    if (ctrl && (key === "k" || key === "K")) return { act: "palette" };
    if (ctrl && key === "Enter") return { act: "send" };
    if (key === "Escape") return { act: "escape" };
    if (typing || ctrl || mods.alt) return null;
    if (pending === "g") return GO[key] ? { act: "go", to: GO[key] } : null;
    const plain = {
      g: { act: "pending", key: "g" }, j: { act: "move", by: 1 }, k: { act: "move", by: -1 }, ArrowDown: null, Enter: { act: "open" },
      Backspace: { act: "back" }, "[": { act: "step", by: -1 }, "]": { act: "step", by: 1 }, "/": { act: "search" }, "?": { act: "keys" },
      a: { act: "assign" }, r: { act: "reply" }, e: { act: "close" }, x: { act: "reject" }, s: { act: "skip" }, q: { act: "queue" },
    };
    if (/^[1-9]$/.test(key)) return { act: "template", n: Number(key) };
    return plain[key] ?? null;
  }

  /** The list of keys for the ? screen, as [keys, what]. */
  const KEYS = [
    ["Ctrl+K", "찾기·이동(명령 팔레트)"], ["/", "검색칸으로"], ["j / k", "다음·이전 줄"], ["Enter", "고른 줄 열기"],
    ["Esc · Backspace", "뒤로"], ["[ / ]", "상세에서 이전·다음 항목"], ["a", "문의 맡기"], ["r", "답 칸으로"], ["e", "문의 닫기"],
    ["1~9", "자주 쓰는 답 넣기"], ["Ctrl+Enter", "답 보내기(큐 모드면 보내고 다음)"], ["q", "큐 모드 시작·끝"],
    ["x · s", "큐 모드에서 거절·건너뛰기"], ["g h · g i · g d · g r", "홈·문의·개발사항·검토로"], ["g c · g t · g w · g o", "회사·토론·보상·운영으로"],
    ["g m · g a · g l · g s", "지표·관리자·활동 기록·설정으로"], ["?", "이 목록"],
  ];

  // ---- [ADMUX-04] the command palette ----

  /** Entries that hold every word of [q] (any order, any case) first, by where the first word starts. */
  function paletteFilter(entries, q, max = 12) {
    const terms = String(q ?? "").toLowerCase().split(/\s+/).filter(Boolean);
    if (!terms.length) return entries.slice(0, max);
    return entries.map((e) => [String(`${e.label} ${e.hint ?? ""}`).toLowerCase(), e])
      .filter(([t]) => terms.every((w) => t.includes(w)))
      .map(([t, e]) => [t.indexOf(terms[0]), e])
      .sort((a, b) => a[0] - b[0])
      .slice(0, max)
      .map(([, e]) => e);
  }

  // ---- [ADMUX-01] requests ----

  /**
   * A number per kind of request: [next] starts one and [current] says whether an answer is still the
   * newest of its kind, so a slow answer for a screen the admin left does not draw over the new one.
   */
  function sequencer() {
    const last = {};
    return {
      next: (kind) => (last[kind] = (last[kind] ?? 0) + 1),
      current: (kind, n) => last[kind] === n,
    };
  }

  /** "1.22.0 (33)" and "1.22.0" as [versionCode or null, name]. */
  function appVersion(s) {
    const m = String(s ?? "").match(/^\s*([\d.]+)(?:\s*\((\d+)\))?/);
    return m ? [m[2] ? Number(m[2]) : null, m[1]] : [null, ""];
  }

  return {
    esc, ERRORS, errorText, ago, slaText, can, permSummary, FILTERS, parseRoute, routeHash, cleanFilters, bottomTabs, likeFirst, words,
    devCopyText, DEV_NAMES, strength, fillTemplate, sortTemplates, wordDiff, csv, ticks, barChart, contrast, keyAction, KEYS, GO,
    paletteFilter, sequencer, appVersion,
  };
}
