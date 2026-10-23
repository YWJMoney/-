// [ADMIN-01][ADMIN-03][ADMIN-04] The developer app: one installable web app at /admin/ for phones, the
// unfolded Fold and computers. Admins sign in with an account the owner invited and approved, and see
// only the areas the owner gave them; the owner signs in with ADMIN_PASSWORD and manages admins.
//
// It imports only its pure helpers (src/admin-lib.js), so `npm run bundle` can inline both into one
// worker.js for the dashboard.

import { adminLib } from "./admin-lib.js";

const ADMIN_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
  "manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

/** The pages the app answers on: the app itself, the invite link, and the page that explains installing. */
const PAGES = ["/admin/", "/admin/join", "/admin/install"];

export function adminFile(path, request) {
  const png = ICONS[path];
  if (png) {
    return new Response(Uint8Array.from(atob(png), (c) => c.charCodeAt(0)), {
      headers: { "content-type": "image/png", "cache-control": "public, max-age=86400", "x-content-type-options": "nosniff" },
    });
  }
  const file = adminFiles()[path];
  if (!file) return new Response("Not found", { status: 404 });
  const headers = {
    "content-type": file.type,
    // Checked each time, but an unchanged file is answered with 304 and no body (ETag).
    "cache-control": "no-cache",
    etag: file.etag,
    "content-security-policy": ADMIN_CSP,
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "x-robots-tag": "noindex, nofollow",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    "x-frame-options": "DENY",
  };
  const asked = request?.headers.get("if-none-match");
  if (asked && asked.split(/\s*,\s*/).some((t) => t.replace(/^W\//, "") === file.etag)) return new Response(null, { status: 304, headers });
  return new Response(file.body, { headers });
}

const hashText = (text) => {
  let h = 5381;
  for (let i = 0; i < text.length; i++) h = ((h * 33) ^ text.charCodeAt(i)) >>> 0;
  return h.toString(36);
};

let builtFiles = null;
let builtVersion = null;

/** [ADMUX-08] A name for this build of the app's files; /api/admin/overview sends it as build. */
export function adminBuild() {
  builtVersion ??= hashText(String(adminApp) + String(adminLib) + String(adminWorker) + ADMIN_CSS + ADMIN_HTML + JSON.stringify(MANIFEST));
  return builtVersion;
}

/**
 * [ADMUX-08] Smaller files for the phone: whole-line comments and the indentation go. Only lines that are
 * nothing but a comment are dropped, so a "//" inside a string or an address stays; the tests run the app
 * as it is sent.
 */
function slimJs(code) {
  const out = [];
  let block = false;
  for (const line of code.split("\n")) {
    const t = line.trim();
    if (block) {
      if (t.includes("*/")) block = false;
      continue;
    }
    if (t.startsWith("/*") && !t.includes("`")) {
      if (!t.includes("*/")) block = true;
      continue;
    }
    if (!t || t.startsWith("//")) continue;
    out.push(t);
  }
  return `${out.join("\n")}\n`;
}

const slimCss = (css) => css.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\s*\n\s*/g, "").replace(/\s*([{};,>])\s*/g, "$1").replace(/;}/g, "}").trim();

/** The app's files with their tags, made once per worker copy. The phone keeps the app's own files under a name that changes with them. */
function adminFiles() {
  if (builtFiles) return builtFiles;
  const version = adminBuild();
  // [ADMUX-08] The app knows its own build, so it can say when the server has a newer one.
  const app = slimJs(`(${adminApp})((${adminLib})(), ${JSON.stringify(version)});`);
  const list = {
    "/admin/app.js": [app, "text/javascript; charset=utf-8"],
    "/admin/sw.js": [slimJs(`(${adminWorker})(${JSON.stringify(version)});`), "text/javascript; charset=utf-8"],
    "/admin/app.css": [slimCss(ADMIN_CSS), "text/css; charset=utf-8"],
    "/admin/manifest.webmanifest": [JSON.stringify(MANIFEST), "application/manifest+json; charset=utf-8"],
    "/admin/icon.svg": [ICON_SVG, "image/svg+xml"],
  };
  for (const page of PAGES) list[page] = [ADMIN_HTML, "text/html; charset=utf-8"];
  builtFiles = Object.fromEntries(Object.entries(list).map(([p, [body, type]]) => [p, { body, type, etag: `"${hashText(body)}"` }]));
  return builtFiles;
}

const MANIFEST = {
  id: "/admin/",
  name: "밤낮 관리자",
  short_name: "밤낮 관리",
  description: "밤낮 앱의 제보, 문의, 회사 규칙과 관리자를 관리해요",
  lang: "ko",
  start_url: "/admin/",
  scope: "/admin/",
  display: "standalone",
  // [ADMUX-05] No window-controls-overlay: the app draws no title bar of its own to put there.
  display_override: ["standalone"],
  orientation: "any",
  background_color: "#15192B",
  theme_color: "#15192B",
  categories: ["business", "productivity"],
  icons: [
    { src: "/admin/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
    { src: "/admin/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
    { src: "/admin/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    { src: "/admin/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
  ],
  shortcuts: [
    { name: "검토", url: "/admin/#/review", icons: [{ src: "/admin/icon-192.png", sizes: "192x192" }] },
    { name: "문의", url: "/admin/#/inquiries", icons: [{ src: "/admin/icon-192.png", sizes: "192x192" }] },
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
<meta name="theme-color" content="#F5F3EE" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0E111C" media="(prefers-color-scheme: dark)">
<meta name="color-scheme" content="light dark">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="밤낮 관리">
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
<dialog id="dialog" aria-modal="true"></dialog>
<noscript>이 화면은 자바스크립트가 켜져 있어야 열려요.</noscript>
</body>
</html>
`;

// Sizes are in rem so the phone's text size setting scales everything together; layouts reflow instead of clipping.
// [ADMUX-03] The layout steps are in px (rail from 600, sidebar from 1024) so a folded Fold (345) is a phone and
// an unfolded one (673) has the rail whatever the text size.
const ADMIN_CSS = `
.photos{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.photos img{width:120px;height:120px;object-fit:cover;border-radius:12px;border:1px solid var(--line)}
.bars{list-style:none;display:flex;align-items:flex-end;gap:2px;height:96px;padding:0;margin:8px 0}.bars li{flex:1;height:100%;display:flex;align-items:flex-end}
.bars span{display:block;width:100%;min-height:2px;border-radius:3px 3px 0 0;background:var(--gold, #C2A36B)}
:root{--bg:#F5F3EE;--surface:#fff;--raised:#fff;--fg:#14182A;--muted:#5B6075;--faint:#62677A;--line:#E3DFD6;--line-strong:#D2CCBF;
--soft:#EFEBE3;--hover:#F2EFE8;--accent:#2B57E0;--accent-soft:#E6ECFD;--on-accent:#fff;--gold:#B8955A;--gold-soft:#F4EBDC;--nav:#15192B;
--nav-fg:#C9CCDA;--nav-on:#fff;--nav-hi:rgba(255,255,255,.08);--ok:#17784A;--ok-bg:#E1F2E8;--warn:#8A5300;--warn-bg:#FBEED5;--bad:#BE3326;
--bad-bg:#FBE5E2;--info:#2B57E0;--info-bg:#E6ECFD;--shadow:0 1px 2px rgba(20,24,42,.05),0 4px 16px rgba(20,24,42,.05);
--shadow-lg:0 20px 60px rgba(10,12,22,.28);--r:16px;--r-sm:12px;--r-lg:22px;--focus:0 0 0 3px color-mix(in srgb,var(--accent) 40%,transparent)}
/* [ADMUX-05] Light, dark or as the system: 설정 › 이 기기 sets data-theme; without it the system decides. */
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0E111C;--surface:#161A28;--raised:#1C2131;--fg:#EEEAE2;--muted:#A3A7B8;--faint:#9095AB;--line:#272D40;
--line-strong:#343B52;--soft:#1F2435;--hover:#20263A;--accent:#8EA8FF;--accent-soft:#1E2747;--on-accent:#0B1022;--gold:#D2B47A;--gold-soft:#2C2618;
--nav:#0A0D17;--nav-hi:rgba(255,255,255,.06);--ok:#7DD3A3;--ok-bg:#12302A;--warn:#F0C272;--warn-bg:#352A12;--bad:#FF8F84;--bad-bg:#3A1C1B;
--info:#8EA8FF;--info-bg:#1E2747;--shadow:none;--shadow-lg:0 20px 60px rgba(0,0,0,.6);color-scheme:dark}}
:root[data-theme="dark"]{--bg:#0E111C;--surface:#161A28;--raised:#1C2131;--fg:#EEEAE2;--muted:#A3A7B8;--faint:#9095AB;--line:#272D40;
--line-strong:#343B52;--soft:#1F2435;--hover:#20263A;--accent:#8EA8FF;--accent-soft:#1E2747;--on-accent:#0B1022;--gold:#D2B47A;--gold-soft:#2C2618;
--nav:#0A0D17;--nav-hi:rgba(255,255,255,.06);--ok:#7DD3A3;--ok-bg:#12302A;--warn:#F0C272;--warn-bg:#352A12;--bad:#FF8F84;--bad-bg:#3A1C1B;
--info:#8EA8FF;--info-bg:#1E2747;--shadow:none;--shadow-lg:0 20px 60px rgba(0,0,0,.6);color-scheme:dark}
:root[data-theme="light"]{color-scheme:light}
/* [ADMUX-05] 글자 크기: everything is in rem, so one root size scales the app. */
:root[data-size="small"]{font-size:93.75%}:root[data-size="large"]{font-size:112.5%}:root[data-size="xlarge"]{font-size:125%}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;color-scheme:light dark;scroll-padding-top:5rem}
:root[data-theme]{color-scheme:light}
body{margin:0;min-height:100vh;min-height:100dvh;background:var(--bg);color:var(--fg);
font:1rem/1.55 system-ui,-apple-system,"Pretendard","Noto Sans KR","Apple SD Gothic Neo","Malgun Gothic",sans-serif;
-webkit-tap-highlight-color:transparent;word-break:keep-all;overflow-wrap:anywhere;font-feature-settings:"tnum" 1}
button,input,textarea,select{font:inherit;color:inherit}
h1,h2,h3{margin:0;line-height:1.3;letter-spacing:-.01em}
a{color:var(--accent)}
svg{fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
:focus-visible{outline:none;box-shadow:var(--focus);border-radius:var(--r-sm)}
.sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
.skip{position:fixed;left:.5rem;top:-4rem;z-index:50;padding:.6rem 1rem;border-radius:var(--r-sm);background:var(--accent);color:var(--on-accent);font-weight:800}
.skip:focus{top:.5rem}

/* ---- layout: phone (bottom bar), Fold unfolded and tablets (rail), computers (sidebar) ---- */
.shell{min-height:100vh;min-height:100dvh}
.side{display:none}
.content{min-width:0}
.top{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 88%,transparent);backdrop-filter:saturate(1.4) blur(14px);
-webkit-backdrop-filter:saturate(1.4) blur(14px);border-bottom:1px solid var(--line);padding-top:env(safe-area-inset-top)}
.bar{display:flex;align-items:center;gap:.5rem;max-width:72rem;margin:0 auto;padding:.5rem .75rem;min-height:3.75rem}
.bar h1{flex:1;min-width:0;font-size:1.2rem;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.bar .sub{display:block;font-size:.78rem;font-weight:600;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mark{width:2.1rem;height:2.1rem;margin:0 .35rem;border-radius:.65rem;flex:none}
.progress{height:2px;overflow:hidden}
.progress.on{background:linear-gradient(90deg,transparent,var(--accent),transparent) no-repeat;background-size:50% 100%;animation:slide 1s linear infinite}
@keyframes slide{from{background-position:-50% 0}to{background-position:150% 0}}
.main{max-width:72rem;margin:0 auto;padding:1rem 1rem calc(6.5rem + env(safe-area-inset-bottom))}
.main:focus{box-shadow:none}
.tabs{position:fixed;left:0;right:0;bottom:0;z-index:6;display:flex;justify-content:center;background:color-mix(in srgb,var(--surface) 94%,transparent);
backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);border-top:1px solid var(--line);padding:.35rem .35rem calc(.35rem + env(safe-area-inset-bottom))}
.tab{position:relative;flex:1;max-width:9rem;display:flex;flex-direction:column;align-items:center;gap:.1rem;padding:.25rem;border:0;background:none;
color:var(--muted);font-size:.8rem;font-weight:700;cursor:pointer;text-decoration:none;min-height:3.4rem;justify-content:center}
.tab{min-width:0}
.tab .nav-label{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tab svg{width:1.45rem;height:1.45rem;padding:.25rem min(1.1rem,3vw);box-sizing:content-box;border-radius:999px;transition:background .2s,color .2s}
.tab[aria-current=page]{color:var(--fg)}
.tab[aria-current=page] svg{background:var(--accent-soft);color:var(--accent)}
.badge{min-width:1.25rem;height:1.25rem;padding:0 .4rem;border-radius:.65rem;background:var(--bad);color:#fff;font-size:.7rem;font-weight:800;
line-height:1.25rem;text-align:center;font-style:normal}
.tab .badge{position:absolute;top:.1rem;left:calc(50% + .55rem)}
.badge:empty{display:none}
@media (min-width:600px){
  .shell{display:grid;grid-template-columns:5.5rem 1fr}
  .tabs{display:none}
  .side{display:flex;flex-direction:column;position:sticky;top:0;height:100vh;height:100dvh;background:var(--nav);color:var(--nav-fg);
  padding:calc(.75rem + env(safe-area-inset-top)) .5rem .75rem;overflow-y:auto;overscroll-behavior:contain}
  .side .brand-row{display:flex;justify-content:center;padding:.25rem 0 1rem}
  .side .brand-row img{width:2.4rem;height:2.4rem;border-radius:.75rem}
  .side .brand-row b,.side .who,.side .group{display:none}
  .nav{list-style:none;margin:0;padding:0;display:grid;gap:.15rem}
  .nav a{position:relative;display:flex;flex-direction:column;align-items:center;gap:.15rem;padding:.55rem .25rem;border-radius:var(--r-sm);
  color:var(--nav-fg);text-decoration:none;font-size:.72rem;font-weight:700;text-align:center}
  .nav a:hover{background:var(--nav-hi);color:var(--nav-on)}
  .nav a[aria-current=page]{background:rgba(255,255,255,.12);color:var(--nav-on)}
  .nav svg{width:1.4rem;height:1.4rem}
  .nav .badge{position:absolute;top:.2rem;right:.35rem}
  .side .foot{margin-top:auto;display:grid;gap:.25rem;padding-top:.75rem}
  .main{padding:1.25rem 1.5rem 3rem}
  .bar{padding:.5rem 1.5rem}
  .bar .mark{display:none}
  .toast{bottom:1.5rem}
}
@media (min-width:1024px){
  .shell{grid-template-columns:15.5rem 1fr}
  .side{padding:calc(1rem + env(safe-area-inset-top)) .75rem 1rem}
  .side .brand-row{justify-content:flex-start;align-items:center;gap:.65rem;padding:.25rem .5rem 1.25rem}
  .side .brand-row b{display:block;color:var(--nav-on);font-size:1.05rem}
  .side .group{display:block;margin:1rem .75rem .35rem;font-size:.72rem;font-weight:800;letter-spacing:.04em;color:color-mix(in srgb,var(--nav-fg) 70%,transparent)}
  .nav a{flex-direction:row;gap:.75rem;padding:.6rem .75rem;font-size:.92rem;text-align:left}
  .nav .badge{position:static;margin-left:auto}
  .side .who{display:flex;align-items:center;gap:.6rem;padding:.6rem .75rem;border-radius:var(--r-sm);color:var(--nav-on);text-decoration:none}
  .side .who:hover{background:var(--nav-hi)}
  .side .who small{display:block;color:var(--nav-fg);font-size:.75rem;font-weight:600}
  .side .compact-only{display:none}
  .avatar{flex:none;display:inline-grid;place-items:center;width:2.1rem;height:2.1rem;border-radius:50%;background:var(--gold);color:#15192B;font-weight:900}
}

/* ---- buttons and fields ---- */
.icon-btn{display:inline-grid;place-items:center;width:2.75rem;height:2.75rem;border:0;border-radius:var(--r-sm);background:none;cursor:pointer;flex:none;color:inherit}
.icon-btn:hover{background:var(--hover)}
.icon-btn svg{width:1.4rem;height:1.4rem}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:.4rem;min-height:2.75rem;padding:0 1.05rem;border-radius:var(--r-sm);
border:1px solid var(--line-strong);background:var(--surface);font-weight:800;font-size:.94rem;cursor:pointer;text-decoration:none;color:var(--fg);
transition:transform .1s,background .15s,border-color .15s;white-space:nowrap}
.btn:hover{background:var(--hover)}
.btn svg{width:1.15rem;height:1.15rem}
.btn.primary{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}
.btn.primary:hover{background:color-mix(in srgb,var(--accent) 88%,#000)}
.btn.danger{color:var(--bad);border-color:color-mix(in srgb,var(--bad) 35%,var(--line))}
.btn.ghost{border-color:transparent;background:none}
.btn.small{min-height:2.25rem;padding:0 .75rem;font-size:.85rem}
.btn.wide{width:100%}
.btn:disabled{opacity:.45;cursor:default;transform:none}
.btn:active:not(:disabled),.icon-btn:active,.row:active,.tile:active{transform:scale(.98)}
.link{border:0;background:none;padding:0;color:var(--accent);font-weight:800;cursor:pointer;text-decoration:underline}
input:not([type]),input[type=search],input[type=text],input[type=email],input[type=password],input[type=url],textarea,select{width:100%;min-height:3rem;
padding:.6rem .9rem;border-radius:var(--r-sm);border:1px solid var(--line-strong);background:var(--surface);outline:none;transition:border-color .15s}
input:focus,textarea:focus,select:focus{border-color:var(--accent);box-shadow:var(--focus)}
input[readonly]{background:var(--soft)}
textarea{resize:vertical;min-height:7rem;line-height:1.55}
label,.label{display:block;font-size:.88rem;font-weight:800;margin:0 0 .4rem}
.help{display:block;margin:.35rem 0 0;font-size:.82rem;color:var(--muted);font-weight:500}
.fieldset{margin:0 0 1rem}
.field{display:flex;gap:.5rem}
.field input{flex:1;min-width:0}
.searchbar{display:flex;gap:.5rem;margin-bottom:.75rem;flex-wrap:wrap}
.searchbar input{flex:1 1 12rem;min-width:0}

/* ---- surfaces ---- */
.card{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-lg);padding:1rem 1.1rem;margin:0 0 .75rem;box-shadow:var(--shadow)}
/* [ADMUX-05] One step per level: screen title 1.2, card 1.06, item .98, text .94. */
.card>h2,.card-head h2{font-size:1.06rem;font-weight:800}
.card-head{display:flex;align-items:center;justify-content:space-between;gap:.75rem;margin:0 0 .6rem}
.card h3{font-size:.98rem}
.grid{display:grid;grid-template-columns:minmax(0,1fr);gap:.75rem}
@media (min-width:600px){.grid.two{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.grid.two>.card{margin:0}}
@media (min-width:1024px){.cols{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:1rem;align-items:start}.cols>*{min-width:0}}
.head{display:flex;align-items:flex-start;justify-content:space-between;gap:.6rem}
.head>:first-child{flex:1;min-width:0}
.dept{font-weight:500;color:var(--muted);font-size:.94rem}
.ver{font-size:.8rem;color:var(--muted);font-weight:800}
.meta{margin:.15rem 0 0;font-size:.82rem;color:var(--muted)}
.gist{margin:.6rem 0 0;font-size:.9rem}
.hint{margin:.1rem 0 .75rem;font-size:.88rem;color:var(--muted)}
.section{display:flex;align-items:center;gap:.5rem;font-size:.92rem;margin:1.5rem .25rem .6rem;color:var(--muted);font-weight:800}
.section .count{font-size:.75rem;background:var(--soft);padding:.05rem .5rem;border-radius:99px}
.pill{flex:none;display:inline-flex;align-items:center;gap:.3rem;padding:.2rem .6rem;border-radius:999px;background:var(--soft);color:var(--muted);
font-size:.8rem;font-weight:800;white-space:nowrap;line-height:1.5}
.pill.warn{background:var(--warn-bg);color:var(--warn)}
.pill.ok{background:var(--ok-bg);color:var(--ok)}
.code-big{font-size:2.2rem;font-weight:700;letter-spacing:.12em;text-align:center;font-variant-numeric:tabular-nums;margin:.6rem 0}
.pill.bad{background:var(--bad-bg);color:var(--bad)}
.pill.info{background:var(--info-bg);color:var(--info)}
.pill.gold{background:var(--gold-soft);color:var(--gold)}
.score{display:flex;align-items:center;gap:.6rem;margin:.85rem 0 .25rem}
.score b{font-size:.85rem;white-space:nowrap}
meter{flex:1;width:100%;height:.6rem;-webkit-appearance:none;appearance:none;border:0;border-radius:99px;background:var(--soft);overflow:hidden}
meter::-webkit-meter-bar{height:.6rem;border:0;border-radius:99px;background:var(--soft)}
meter::-webkit-meter-optimum-value,meter::-webkit-meter-suboptimum-value,meter::-webkit-meter-even-less-good-value{background:var(--gold);border-radius:99px}
meter::-moz-meter-bar{background:var(--gold);border-radius:99px}
meter.strength::-webkit-meter-even-less-good-value{background:var(--bad)}
meter.strength::-webkit-meter-suboptimum-value{background:var(--gold)}
meter.strength::-webkit-meter-optimum-value{background:var(--ok)}
details{margin:.75rem 0 0;border-top:1px solid var(--line);padding-top:.5rem}
summary{cursor:pointer;font-size:.88rem;font-weight:800;color:var(--accent);padding:.35rem 0;list-style:none}
summary::-webkit-details-marker{display:none}
summary::after{content:" ▾"}
details[open]>summary::after{content:" ▴"}
.lines{margin:.4rem 0 0;display:grid;gap:.4rem}
.lines div{display:grid;grid-template-columns:minmax(5.5rem,30%) 1fr;gap:.6rem;font-size:.88rem}
.lines dt{color:var(--muted)}
.lines dd{margin:0}
.diff{margin:.75rem 0 0;padding:.6rem .75rem;border-radius:var(--r-sm);background:var(--soft)}
.diff-title{margin:0 0 .4rem;font-size:.8rem;font-weight:800;color:var(--muted)}
.diff ul{margin:0;padding:0;list-style:none;display:grid;gap:.25rem}
.diff li{display:flex;gap:.4rem;font-size:.88rem}
.diff .sign{flex:none;width:1rem;text-align:center;font-weight:900}
.diff .add .sign{color:var(--ok)}
.diff .del{color:var(--muted)}
.diff .del .sign{color:var(--bad)}
blockquote{margin:.75rem 0 0;padding:.6rem .75rem;border-left:3px solid var(--gold);background:var(--soft);border-radius:4px var(--r-sm) var(--r-sm) 4px;
font-size:.9rem;white-space:pre-wrap}
cite{display:block;margin-top:.25rem;font-style:normal;font-size:.75rem;color:var(--muted)}
.note{margin:.75rem 0 0;font-size:.82rem;padding:.5rem .65rem;border-radius:var(--r-sm);background:var(--soft)}
.note.warn{background:var(--warn-bg);color:var(--warn)}
.notice-preview{margin:0 0 .75rem}.np{display:grid;gap:.2rem;padding:.65rem .8rem;border-radius:var(--r-sm);background:var(--info-bg);color:var(--fg);font-size:.88rem}.np span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:var(--muted)}.np.important{background:var(--warn-bg)}.np.important b{color:var(--warn)}
.note.bad{background:var(--bad-bg);color:var(--bad)}
.note.ok{background:var(--ok-bg);color:var(--ok)}
.note.info{background:var(--info-bg);color:var(--info)}
.actions{display:flex;justify-content:flex-end;gap:.5rem;margin-top:.9rem;flex-wrap:wrap}
.actions.start{justify-content:flex-start}
#admins-top{margin:0 0 .75rem}
details.card{margin-top:0;padding-top:.6rem}
details.card>summary{color:var(--fg)}
details.card .steps{margin-top:.6rem}

/* ---- lists ---- */
.rows{list-style:none;margin:0 0 .75rem;padding:0;background:var(--surface);border:1px solid var(--line);border-radius:var(--r-lg);overflow:hidden;box-shadow:var(--shadow)}
.rows>li+li{border-top:1px solid var(--line)}
.row{display:flex;align-items:center;gap:.65rem;width:100%;min-height:4rem;padding:.75rem 1rem;border:0;background:none;text-align:left;cursor:pointer;color:inherit;text-decoration:none}
.row:hover{background:var(--hover)}
.row>svg{flex:none;width:1.25rem;height:1.25rem;color:var(--faint)}
.row-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:.05rem}
.row-main b{font-size:1rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.row-main small{font-size:.8rem;color:var(--muted)}
.row-main .preview{font-size:.9rem;color:var(--fg);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.row.unread .row-main b::before{content:"";display:inline-block;width:.5rem;height:.5rem;border-radius:50%;background:var(--accent);margin-right:.4rem;vertical-align:middle}
.row-side{display:flex;flex-direction:column;align-items:flex-end;gap:.3rem;flex:none;font-size:.75rem;color:var(--muted)}
.item{display:flex;align-items:center;gap:.75rem;padding:.85rem 1rem;flex-wrap:wrap}
.item .row-main{flex:1 1 14rem}
.item .actions{margin:0 0 0 auto}
.memo{font-style:italic}
.chips{display:flex;flex-wrap:wrap;gap:.5rem;margin:0 0 .75rem}
.chip{display:inline-flex;align-items:center;gap:.15rem;padding:.25rem .25rem .25rem .75rem;border-radius:999px;background:var(--soft);font-size:.88rem;font-weight:700}
.chip button{width:1.9rem;height:1.9rem;border:0;border-radius:50%;background:none;cursor:pointer;font-size:1.1rem;line-height:1;color:var(--muted)}
.chip.pick{padding:.35rem .8rem;border:1px solid var(--line-strong);background:var(--surface);cursor:pointer}
.chip.pick:hover{background:var(--hover)}
.chip svg{width:1rem;height:1rem}
.chip.pick[aria-pressed=true]{background:var(--accent-soft);border-color:var(--accent);color:var(--accent)}

/* ---- home ---- */
.hello{margin:.25rem .25rem 1rem}
.hello h2{font-size:1.35rem;font-weight:900}
.hello p{margin:.2rem 0 0;color:var(--muted);font-size:.92rem}
.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,10.5rem),1fr));gap:.65rem;margin:0 0 1rem}
.tile{display:flex;flex-direction:column;gap:.35rem;padding:.9rem 1rem;border-radius:var(--r-lg);background:var(--surface);border:1px solid var(--line);
box-shadow:var(--shadow);text-decoration:none;color:inherit;min-height:6.5rem;transition:border-color .15s,transform .1s}
a.tile:hover{border-color:var(--accent)}
.tile .k{font-size:.8rem;font-weight:800;color:var(--muted);display:flex;align-items:center;gap:.4rem}
.tile .k svg{width:1.05rem;height:1.05rem}
.tile .v{font-size:1.9rem;font-weight:900;line-height:1.1;letter-spacing:-.02em}
.tile .d{font-size:.78rem;color:var(--muted);margin-top:auto}
@media (max-width:599.98px){.tile{min-height:0;padding:.7rem .85rem;gap:.15rem}.tile .v{font-size:1.5rem}.tile .d{display:none}}
/* The phone lists what waits; wider screens show the counts as tiles, with the activity beside. [ADMUX-06] The
   queue of what to answer first shows on both. */
@media (max-width:599.98px){.tiles{display:none}}
@media (min-width:600px){.todo-card .todo,.todo-card>.hint{display:none}.todo-card:not(:has(.queue-list)){display:none}}
.stats{display:flex;flex-wrap:wrap;gap:.4rem 1.25rem;margin:0 .25rem 1rem;font-size:.86rem;color:var(--muted)}
.stats b{color:var(--fg);font-size:1rem;margin-left:.3rem}
.tile.hot{border-color:color-mix(in srgb,var(--bad) 40%,var(--line))}
.tile.hot .v{color:var(--bad)}
.todo{list-style:none;margin:0;padding:0;display:grid;gap:.15rem}
.todo a{display:flex;align-items:center;gap:.65rem;padding:.6rem .5rem;border-radius:var(--r-sm);text-decoration:none;color:inherit;font-weight:700;font-size:.94rem}
.todo a:hover{background:var(--hover)}
.todo .dot{flex:none;width:.55rem;height:.55rem;border-radius:50%;background:var(--accent)}
.todo .dot.bad{background:var(--bad)}
.todo .dot.warn{background:var(--gold)}
.todo small{margin-left:auto;color:var(--muted);font-weight:600;white-space:nowrap}
.feed{list-style:none;margin:0;padding:0}
.feed li{display:grid;grid-template-columns:auto 1fr;gap:.15rem .65rem;padding:.55rem 0;border-top:1px solid var(--line);font-size:.88rem}
.feed li:first-child{border-top:0}
.feed .when{grid-row:span 2;color:var(--muted);font-size:.75rem;min-width:3.4rem;padding-top:.1rem}
.feed .who{color:var(--muted);font-size:.78rem}
.day-head{margin:1rem .25rem .4rem;font-size:.8rem;font-weight:800;color:var(--muted)}

/* ---- conversation ---- */
.thread{display:flex;flex-direction:column;gap:.6rem;margin:.25rem 0 .75rem}
.bubble{max-width:min(85%,36rem);padding:.65rem .85rem;border-radius:1.1rem;white-space:pre-wrap;font-size:.94rem;line-height:1.55}
.bubble.user{align-self:flex-start;background:var(--soft);border-bottom-left-radius:.35rem}
.bubble.admin{align-self:flex-end;background:var(--accent);color:var(--on-accent);border-bottom-right-radius:.35rem}
.bubble-meta{font-size:.72rem;color:var(--muted);margin:-.3rem .35rem 0}
.bubble-meta.admin{align-self:flex-end}
.composer{position:sticky;bottom:calc(4.4rem + env(safe-area-inset-bottom));background:var(--surface);border:1px solid var(--line);border-radius:var(--r-lg);
padding:.75rem;box-shadow:var(--shadow)}
@media (min-width:600px){.composer{bottom:1rem}}
.composer textarea{min-height:5.5rem;border:0;padding:.25rem;box-shadow:none;background:none}
.composer .actions{margin-top:.5rem;align-items:center}
.composer .count{margin-right:auto;font-size:.75rem;color:var(--muted)}

/* ---- permissions ---- */
.perm-table{display:grid;gap:.4rem;margin:.25rem 0 .75rem}
.perm{display:flex;align-items:center;justify-content:space-between;gap:.75rem;flex-wrap:wrap;padding:.5rem .1rem;border-top:1px solid var(--line)}
.perm:first-child{border-top:0}
.perm b{font-size:.92rem}
.levels{display:inline-flex;padding:.2rem;border-radius:var(--r-sm);background:var(--soft);gap:.15rem}
.levels button{min-height:2.25rem;min-width:3.6rem;padding:0 .6rem;border:0;border-radius:.6rem;background:none;font-weight:800;font-size:.82rem;color:var(--muted);cursor:pointer}
.levels button[aria-pressed=true]{background:var(--surface);color:var(--fg);box-shadow:var(--shadow)}
.levels button[aria-pressed=true][data-level=edit]{color:var(--accent)}
.levels button:disabled{opacity:.35;cursor:default}
.perm-sum{display:flex;flex-wrap:wrap;gap:.3rem;margin:.35rem 0 0}
.steps{list-style:none;margin:0 0 .5rem;padding:0;display:grid;gap:.6rem;counter-reset:s}
.steps li{display:grid;grid-template-columns:1.8rem 1fr;gap:.6rem;font-size:.9rem;counter-increment:s}
.steps li::before{content:counter(s);display:inline-grid;place-items:center;width:1.8rem;height:1.8rem;border-radius:50%;background:var(--accent-soft);color:var(--accent);font-weight:900;font-size:.85rem}
.steps li.done::before{content:"✓";background:var(--ok-bg);color:var(--ok)}

/* ---- toggles, segments, steppers ---- */
.switch{position:relative;display:flex;align-items:center;gap:.75rem;margin:.75rem 0 0;font-size:.94rem;font-weight:700;cursor:pointer}
.switch input{position:absolute;opacity:0;width:1px;height:1px}
.track{position:relative;flex:none;width:3.1rem;height:1.85rem;border-radius:99px;background:var(--line-strong);transition:background .2s}
.track::after{content:"";position:absolute;top:.2rem;left:.2rem;width:1.45rem;height:1.45rem;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.25);transition:transform .2s}
.switch input:checked+.track{background:var(--accent)}
.switch input:checked+.track::after{transform:translateX(1.25rem)}
.switch input:focus-visible+.track{box-shadow:var(--focus)}
.rule{padding:.75rem 0;border-top:1px solid var(--line)}
.rule:first-of-type{border-top:0;padding-top:.25rem}
.rule.dim{opacity:.72}
.segmented{display:flex;gap:.25rem;padding:.25rem;border-radius:var(--r);background:var(--soft);margin-bottom:.75rem;overflow-x:auto;scrollbar-width:none}
.seg{flex:1 0 auto;min-height:2.6rem;padding:0 .8rem;border:0;border-radius:.75rem;background:none;font-weight:800;font-size:.86rem;color:var(--muted);cursor:pointer;white-space:nowrap}
.seg[aria-pressed=true]{background:var(--surface);color:var(--fg);box-shadow:var(--shadow)}
.seg .n{margin-left:.3rem;font-size:.75rem;color:var(--faint)}
.stepper{display:flex;align-items:center;justify-content:space-between;gap:.75rem;margin:.6rem 0 .1rem;flex-wrap:wrap}
.stepper>span{font-weight:800}
.stepper-ctl{display:flex;align-items:center;gap:.1rem;background:var(--soft);border-radius:var(--r);padding:.1rem}
.stepper output{min-width:4.2rem;text-align:center;font-weight:900;font-size:1.05rem}
.checks{list-style:none;margin:0 0 .4rem;padding:0;display:grid;gap:.6rem}
.checks li{display:flex;gap:.6rem;font-size:.88rem;align-items:flex-start}
.checks span{flex:none;display:inline-grid;place-items:center;width:1.4rem;height:1.4rem;border-radius:50%;font-size:.8rem;font-weight:900}
.checks .ok span{background:var(--ok-bg);color:var(--ok)}
.checks .todo span{background:var(--warn-bg);color:var(--warn)}
.kv{display:grid;grid-template-columns:auto 1fr;gap:.35rem .9rem;margin:0;font-size:.88rem}
.kv dt{color:var(--muted)}
.kv dd{margin:0;font-weight:600}

/* ---- states ---- */
.empty{text-align:center;padding:3rem 1rem;color:var(--muted)}
.empty svg{width:3.25rem;height:3.25rem;color:var(--gold);stroke-width:1.5}
.empty h2{color:var(--fg);font-size:1.06rem;margin:.75rem 0 .25rem}
.empty p{margin:0 auto;font-size:.88rem;max-width:26rem}
.empty .btn{margin-top:1rem}
.skeleton{height:9rem;border-color:transparent;background:linear-gradient(90deg,var(--surface),var(--soft),var(--surface));background-size:200% 100%;animation:shine 1.2s ease-in-out infinite}
.skeleton.short{height:4.5rem}
@keyframes shine{from{background-position:100% 0}to{background-position:-100% 0}}
.center{min-height:100vh;min-height:100dvh;display:grid;place-items:center}
.spinner{width:2rem;height:2rem;border-radius:50%;border:3px solid var(--line);border-top-color:var(--gold);animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.offline{position:sticky;top:0;z-index:7;background:var(--warn-bg);color:var(--warn);text-align:center;font-size:.85rem;font-weight:800;padding:.4rem 1rem}

/* ---- sign-in, invite and install pages ---- */
.gate{max-width:28rem;margin:0 auto;padding:calc(2.5rem + env(safe-area-inset-top)) 1rem 2rem}
.brand{text-align:center;margin-bottom:1.5rem}
.brand img{border-radius:1.35rem;box-shadow:var(--shadow)}
.brand h1{font-size:1.5rem;margin:.85rem 0 .25rem}
.brand p{margin:0;color:var(--muted)}
.error{min-height:1.4em;margin:.5rem 0;color:var(--bad);font-size:.88rem;font-weight:600}
.alt{text-align:center;margin:1rem 0 0;font-size:.88rem;color:var(--muted)}
.toast{position:fixed;left:50%;bottom:calc(5.25rem + env(safe-area-inset-bottom));transform:translate(-50%,1rem);max-width:min(92vw,32rem);padding:.75rem 1.1rem;
border-radius:var(--r);background:var(--fg);color:var(--bg);font-size:.88rem;font-weight:700;opacity:0;pointer-events:none;transition:opacity .2s,transform .2s;
z-index:60;text-align:center;box-shadow:var(--shadow-lg)}
.toast.show{opacity:1;transform:translate(-50%,0)}
dialog{border:0;padding:0;border-radius:var(--r-lg);background:var(--raised);color:var(--fg);width:min(94vw,30rem);max-height:min(92vh,48rem);box-shadow:var(--shadow-lg)}
dialog.wide{width:min(94vw,40rem)}
dialog::backdrop{background:rgba(8,10,20,.55);backdrop-filter:blur(2px)}
dialog[open]{animation:pop .18s ease-out}
@keyframes pop{from{opacity:0;transform:translateY(.6rem) scale(.98)}to{opacity:1;transform:none}}
dialog form,dialog .box{padding:1.35rem 1.25rem 1.1rem}
dialog h2{font-size:1.15rem;margin-bottom:.35rem}
dialog p{margin:0 0 .75rem;color:var(--muted);font-size:.88rem}
dialog .actions:last-child{position:sticky;bottom:0;margin:1rem -1.25rem -1.1rem;padding:.75rem 1.25rem calc(.9rem + env(safe-area-inset-bottom));
background:var(--raised);border-top:1px solid var(--line)}
@media (max-width:599.98px){dialog{margin:auto 0 0;width:100vw;max-width:100vw;border-radius:var(--r-lg) var(--r-lg) 0 0;padding-bottom:env(safe-area-inset-bottom)}
@keyframes pop{from{transform:translateY(100%)}to{transform:none}}}
.radios{display:grid;gap:.5rem;margin:.4rem 0}
.radio{display:flex;align-items:center;gap:.6rem;margin:0;padding:.75rem;border:1px solid var(--line-strong);border-radius:var(--r-sm);font-weight:600;cursor:pointer;font-size:.92rem}
.radio input{flex:none;width:1.25rem;height:1.25rem;accent-color:var(--accent)}
.radio:has(input:checked){border-color:var(--accent);background:var(--accent-soft)}
/* ---- 개발사항 [ADMIN-10] ---- */
.row .check{flex:none;width:1.35rem;height:1.35rem;margin:0;accent-color:var(--accent)}
.row.picked{background:var(--accent-soft)}
.bulkbar{position:sticky;bottom:calc(4.6rem + env(safe-area-inset-bottom));z-index:4;display:flex;align-items:center;gap:.5rem;flex-wrap:wrap;
margin:.75rem 0 0;padding:.6rem .75rem;background:var(--raised);border:1px solid var(--line-strong);border-radius:var(--r-lg);box-shadow:var(--shadow-lg)}
.bulkbar b{margin-right:auto;font-size:.9rem}
@media (min-width:600px){.bulkbar{bottom:1rem}}
.dev-body{margin:.6rem 0 0;white-space:pre-wrap;font-size:.94rem}
.history{list-style:none;margin:0;padding:0}
.history li{padding:.6rem 0;border-top:1px solid var(--line);font-size:.88rem}
.history li:first-child{border-top:0}
.history .who{font-weight:800}
.history .when{color:var(--muted);font-size:.75rem;margin-left:.35rem}
.history .what{display:block;margin-top:.1rem}
.history .was{color:var(--muted);text-decoration:line-through}
.notes{list-style:none;margin:0 0 .5rem;padding:0;display:grid;gap:.5rem}
.notes li{padding:.5rem .65rem;border-radius:var(--r-sm);background:var(--gold-soft);font-size:.88rem;white-space:pre-wrap}
.notes cite{margin:0 0 .15rem}
.mini textarea{min-height:3.5rem}
.item a.row-main{color:inherit;text-decoration:none}
.item a.row-main:hover b{text-decoration:underline}
.kbd{display:inline-block;min-width:1.6rem;padding:.05rem .4rem;border:1px solid var(--line-strong);border-bottom-width:2px;border-radius:.4rem;font-size:.8rem;
font-weight:800;text-align:center;background:var(--surface)}
.keys{display:grid;grid-template-columns:auto 1fr;gap:.5rem .9rem;align-items:center;font-size:.9rem;margin:0 0 .5rem}
/* ---- 1.23 [ADMUX-01]~[ADMUX-08] ---- */
/* Touch: on a finger, every small control is still 44 px. */
@media (pointer:coarse){.btn.small,.seg,.levels button{min-height:2.75rem}.chip button{width:2.75rem;height:2.75rem}.chip.pick{min-height:2.75rem}.icon-btn{width:2.75rem;height:2.75rem}}
.btn[aria-busy=true]{opacity:.7;cursor:progress}
.row.static{cursor:default}.row.static:hover{background:none}.row.static:active{transform:none}
.rows .row.selected,.rows .item.selected{box-shadow:inset 3px 0 0 var(--accent);background:var(--hover)}
.row-icons{display:flex;flex-wrap:wrap;gap:.3rem;margin-top:.3rem}
.row-icons .pill{font-size:.8rem}
.more{display:flex;justify-content:center;margin:0 0 1rem}
.toolbar{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center;margin:0 0 .75rem}
.toolbar input[type=search]{flex:1 1 12rem;min-width:0}
.toolbar select,.toolbar input[type=date]{width:auto;min-height:2.75rem;flex:0 1 auto}
select{appearance:auto}
.banner{position:sticky;top:0;z-index:7;display:flex;align-items:center;justify-content:center;gap:.75rem;flex-wrap:wrap;padding:.45rem 1rem;
background:var(--info-bg);color:var(--info);font-size:.86rem;font-weight:800;text-align:center}
.banner.warn{background:var(--warn-bg);color:var(--warn)}
.banner .btn{min-height:2.25rem}
.failed{text-align:center;padding:2.5rem 1rem;color:var(--muted)}
.failed h2{color:var(--fg);font-size:1.06rem;margin:.5rem 0 .25rem}
.toast .undo{margin-left:.75rem;border:0;background:none;color:var(--accent-soft);font-weight:900;text-decoration:underline;cursor:pointer;pointer-events:auto;min-height:2rem}
.toast.show.has-undo{pointer-events:auto}
/* Two panes: on a wide screen the list stays beside the open item. */
@media (min-width:1024px){.split{display:grid;grid-template-columns:minmax(17rem,24rem) minmax(0,1fr);gap:1rem;align-items:start}
.split>.pane-list{position:sticky;top:4.75rem;max-height:calc(100vh - 6rem);max-height:calc(100dvh - 6rem);overflow:auto;border-radius:var(--r-lg)}
.split>.pane-list .rows{margin:0}.split .row[aria-current=true]{background:var(--accent-soft)}}
@media (max-width:1023.98px){.split>.pane-list{display:none}}
@media (min-width:1024px) and (max-width:1439.98px){.split .cols{display:block}}
/* Charts: drawn as SVG, coloured here (the CSP allows no style attributes). */
.chart{display:block;width:100%;height:auto;margin:.5rem 0}
.chart .bar{fill:var(--accent);stroke:none}
.chart .bar:hover{fill:color-mix(in srgb,var(--accent) 75%,var(--fg))}
.chart .grid{stroke:var(--line);stroke-width:1}
.chart .axis{fill:var(--muted);stroke:none;font-size:11px;font-family:inherit}
.chart .before{stroke:var(--gold);stroke-width:2;stroke-dasharray:5 4}
.legend{display:flex;gap:1rem;flex-wrap:wrap;font-size:.8rem;color:var(--muted)}
.legend .sw{display:inline-block;width:.9rem;height:.5rem;border-radius:2px;background:var(--accent);margin-right:.35rem;vertical-align:middle}
.legend .sw.before{background:none;border-top:2px dashed var(--gold);height:0}
ins{background:var(--ok-bg);color:var(--ok);text-decoration:none;border-radius:3px}
del{background:var(--bad-bg);color:var(--bad);border-radius:3px}
.wdiff{white-space:pre-wrap;overflow-wrap:anywhere}
.diffbox{white-space:pre-wrap;font-size:.9rem;padding:.6rem .75rem;background:var(--soft);border-radius:var(--r-sm);margin:.5rem 0 0}
/* The home queue and the dashboard numbers. */
.queue-list{list-style:none;margin:0;padding:0;display:grid;grid-template-columns:minmax(0,1fr);gap:.1rem}
.queue-list li{display:flex;align-items:center;gap:.6rem;padding:.55rem .25rem;border-top:1px solid var(--line);flex-wrap:wrap}
.queue-list li:first-child{border-top:0}
.queue-list .q-main{flex:1 1 12rem;min-width:0}
.queue-list .q-main b{display:block;font-size:.94rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.queue-list .q-main a{color:inherit;text-decoration:none}.queue-list .q-main a:hover{text-decoration:underline}
.queue-list .q-main small{color:var(--muted);font-size:.8rem}
.delta{font-size:.8rem;font-weight:800;margin-left:.35rem}
.delta.up{color:var(--bad)}.delta.down{color:var(--ok)}
.people{list-style:none;margin:0;padding:0;display:flex;flex-wrap:wrap;gap:.4rem}
.people li{padding:.3rem .7rem;border-radius:99px;background:var(--soft);font-size:.86rem;font-weight:700}
/* The timeline of a question. */
.timeline{list-style:none;margin:0;padding:0 0 0 .9rem;border-left:2px solid var(--line)}
.timeline li{position:relative;padding:.35rem 0 .35rem .6rem;font-size:.86rem}
.timeline li::before{content:"";position:absolute;left:-1.28rem;top:.75rem;width:.6rem;height:.6rem;border-radius:50%;background:var(--line-strong)}
.timeline li.user::before{background:var(--gold)}.timeline li.admin::before{background:var(--accent)}.timeline li.note::before{background:var(--warn)}
.timeline .when{color:var(--muted);font-size:.75rem;margin-left:.35rem}
.typing{font-size:.82rem;color:var(--accent);font-weight:700;margin:.25rem 0}
.typing::before{content:"✎ "}
.stepnav{display:flex;gap:.5rem;justify-content:space-between;align-items:center;margin:0 0 .75rem}
.queue-bar{position:sticky;top:4rem;z-index:4;display:flex;align-items:center;gap:.5rem;flex-wrap:wrap;padding:.5rem .75rem;margin:0 0 .75rem;
background:var(--accent-soft);color:var(--accent);border-radius:var(--r);font-weight:800;font-size:.88rem}
.queue-bar b{margin-right:auto}
/* The command palette. */
dialog.palette{margin-top:12vh;width:min(94vw,38rem)}
@media (max-width:599.98px){dialog.palette{margin:0;width:100vw;max-width:100vw;max-height:100dvh;height:100dvh;border-radius:0}}
.palette .box{padding:.75rem}
.palette input{font-size:1.05rem}
.palette .rows{margin:.5rem 0 0;max-height:60vh;overflow:auto}
.palette .row{min-height:3rem}
.palette .row[aria-selected=true]{background:var(--accent-soft)}
.palette small.kind{color:var(--muted);font-size:.75rem;margin-left:auto;flex:none}
.pw{position:relative}.pw input{padding-right:5.5rem}
.pw .show{position:absolute;right:.35rem;top:50%;transform:translateY(-50%);min-height:2.25rem}
.saved{font-size:.75rem;color:var(--muted)}
.prefs .field select{flex:1}
.check-row{display:flex;align-items:center;gap:.5rem;margin:.4rem 0;font-weight:600;font-size:.92rem}
.check-row input{width:1.2rem;height:1.2rem;accent-color:var(--accent)}
.who.link{background:none;border:0;padding:0;font:inherit;color:var(--accent);cursor:pointer;text-align:left;text-decoration:underline;text-underline-offset:2px}
.btn.disabled{opacity:.45;pointer-events:none}
.pane-main{min-width:0}
.toolbar .btn{flex:none}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
@media (forced-colors:active){.btn,.seg,.tab,.card{border:1px solid CanvasText}}
`;

/** The app in the browser. Serialized into /admin/app.js, so it uses nothing from this module. */
function adminApp(L, BUILD) {
  const app = document.getElementById("app");
  const toastBox = document.getElementById("toast");
  const dialog = document.getElementById("dialog");

  const P = {
    home: '<path d="M4 11.5 12 4l8 7.5V20h-5.5v-5h-5v5H4z"/>',
    review: '<path d="M4 13h4l2 3h4l2-3h4"/><path d="M5.5 5h13L20 13v6H4v-6z"/>',
    inquiries: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 9.5h8M8 12.5h5"/>',
    companies: '<path d="M4 20V5.5L12 3v17M12 9h8v11M3 20h18M7 8h2M7 12h2M7 16h2M15 13h2M15 17h2"/>',
    community: '<path d="M3.5 5h11v8h-6l-4 3.5V13h-1z"/><path d="M14.5 9h6v8h-1v3l-3.5-3h-5.5v-4"/>',
    rewards: '<path d="M4 11h16v9H4zM3 7.5h18V11H3zM12 7.5V20"/><path d="M12 7.5C10.5 4 7 4 7.5 6.5c.3 1 2 1 4.5 1M12 7.5C13.5 4 17 4 16.5 6.5c-.3 1-2 1-4.5 1"/>',
    admins: '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3.3 2.8-5 5.5-5s4.9 1.7 5.5 5"/><path d="M16 4.8a3 3 0 0 1 0 6M17.5 14c1.7.6 2.8 2.2 3.1 5"/>',
    audit: '<path d="M12 7v5l3 2"/><circle cx="12" cy="12" r="8.5"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
    account: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20c.8-3.8 3.6-5.8 7-5.8s6.2 2 7 5.8"/>',
    more: '<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    chevron: '<path d="M9 5l7 7-7 7"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
    share: '<path d="M12 3v12M7.5 7.5 12 3l4.5 4.5"/><path d="M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m4 7 8 6 8-6"/>',
    install: '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5"/><path d="M5 19h14"/>',
    logout: '<path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10"/>',
    moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 8-8M16 7l2 2M14 9l2 2"/>',
    help: '<circle cx="12" cy="12" r="8.5"/><path d="M9.6 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-1 .8-1 1.5V14M12 17.2v.1"/>',
    dev: '<path d="M8 8 4 12l4 4M16 8l4 4-4 4M13.5 5l-3 14"/>',
    check: '<path d="M5 12.5 10 17l9-10"/>',
    // [SRV-04][ADMIN-14] 운영, [ADMIN-13] 지표, [SRV-05] 공유 링크 신고.
    ops: '<path d="M4 6h16M4 12h16M4 18h10"/><circle cx="18" cy="18" r="2.5"/>',
    metrics: '<path d="M4 20V4M4 20h16"/><path d="M8 16v-4M12 16V8M16 16v-6"/>',
    shares: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  };
  /** Where each screen lives and who sees it; badges count what waits for someone there. */
  const NAV = [
    { key: "home", label: "홈", group: "main" },
    { key: "review", label: "검토", area: "review", group: "main", badge: (o) => (o.pending ?? 0) + (o.asks ?? 0) + (o.flagged ?? 0) },
    { key: "inquiries", label: "문의", area: "inquiries", group: "main", badge: (o) => o.inquiries ?? 0 },
    // [ADMIN-10] 접수 waits for whoever may set 개발중·완료·반려; for the rest it is not a count to clear.
    { key: "dev", label: "개발사항", area: "dev", group: "main", badge: (o) => (can("devStatus", "edit") ? o.devTodo ?? 0 : 0) },
    { key: "companies", label: "회사", area: "companies", group: "main" },
    // [COMM-06] The talk on rule sets: what people flagged first.
    { key: "community", label: "토론", area: "community", group: "main", badge: (o) => o.flaggedPosts ?? 0 },
    { key: "rewards", label: "보상", area: "rewards", group: "main", badge: (o) => o.owed ?? 0 },
    // [SRV-05] Reported share links live on the share server; this screen reads them through the company server.
    { key: "shares", label: "공유 링크", area: "shares", group: "main" },
    // [SRV-04][ADMIN-14] Notices, switches and this month's chores; the badge is the chores left.
    { key: "ops", label: "운영", area: "ops", group: "manage", badge: (o) => o.opsLeft ?? 0 },
    { key: "metrics", label: "지표", area: "metrics", group: "manage" },
    { key: "admins", label: "관리자", owner: true, group: "manage", badge: (o) => (o.admins?.waiting ?? 0) + (o.admins?.phones ?? 0) },
    { key: "audit", label: "활동 기록", area: "audit", group: "manage" },
    { key: "settings", label: "설정", group: "manage" },
  ];
  const LEVEL_NAMES = { none: "없음", view: "보기", edit: "고치기" };
  /** [ADMIN-10][ADMIN-15] Areas that are a yes or no: the server says which (me.toggles). */
  const toggles = () => S.me?.toggles ?? S.data.admins?.toggles ?? ["devStatus"];
  const DEV_NAMES = L.DEV_NAMES;
  const DEV_TONES = { todo: "info", doing: "warn", done: "ok", rejected: "bad" };
  const DEV_TO = { todo: "접수로", doing: "개발중으로", done: "완료로", rejected: "반려로" };
  /** [ADMIN-11] What 반려 usually says, one tap each; the admin can still type their own. */
  const REJECT_REASONS = ["이미 있는 기능이에요", "지금 계획과 맞지 않아요", "기술적으로 어려워요", "같은 항목이 있어요", "더 많은 요청을 기다려요"];
  /** [ADMIN-11] The answer to linked questions when an item is 완료 or 반려, ready to send after a read. */
  const DEV_REPLIES = {
    done: "말씀해 주신 내용을 반영했어요. 앱을 최신 버전으로 업데이트하면 쓸 수 있어요. 알려 주셔서 고마워요.",
    rejected: "보내 주신 의견을 꼼꼼히 살펴봤어요. 아쉽지만 지금은 넣기 어려워요. 같은 의견이 더 모이면 다시 볼게요.",
    doing: "말씀해 주신 내용을 지금 만들고 있어요. 업데이트되면 다시 알려 드릴게요.",
  };
  const KIND_NAMES = { question: "질문", bug: "문제 신고", problem: "문제 신고", idea: "건의·제안", company: "회사 방식", other: "그 밖의 이야기" };
  const STATUS_NAMES = { open: "답 대기", answered: "답함", closed: "닫힘" };
  // [INQ-02] The stages the asker's app shows.
  const PROGRESS_NAMES = { received: "접수됨", checking: "확인 중", answered: "답변 완료", planned: "개발 목록에 올림", building: "개발 중", released: "반영됨" };
  /** [INQ-04] How long the asker has waited: red past a day, so nothing is missed. */
  const waitPill = (q) => q.waitingHours === null || q.waitingHours === undefined || q.status !== "open" ? ""
    : `<span class="pill ${q.waitingHours >= 24 ? "bad" : q.waitingHours >= 8 ? "warn" : ""}">${q.waitingHours < 1 ? "방금" : q.waitingHours < 24 ? `${q.waitingHours}시간` : `${Math.floor(q.waitingHours / 24)}일`} 기다림</span>`;
  const ACTION_NAMES = {
    login: "로그인", login_failed: "로그인 실패", logout_all: "모든 기기에서 로그아웃", password: "비밀번호 바꿈",
    invite: "관리자 초대", invite_remove: "초대 취소", join: "관리자 계정 만듦", admin_update: "관리자 권한·상태 바꿈", admin_remove: "관리자 삭제",
    adopt: "제보 채택", reject: "제보 거절", answer: "앱에 없는 방식에 답함", company_create: "회사 만듦", company_update: "회사 고침",
    alias_add: "다른 이름 넣음", alias_remove: "다른 이름 지움", merge: "회사 합침", rule_withdraw: "규칙 내림", rule_restore: "규칙 다시 올림",
    reward_paid: "보상 지급", reward_void: "보상 취소", reward_owed: "보상 대기로 되돌림", settings: "채택 설정 바꿈",
    post_autohide: "신고로 글이 가려짐", post_show: "글 다시 보이기", post_hide: "글 가림", post_delete: "글 지움", post_mute: "글쓴이 30일 쓰기 막음",
    inquiry_reply: "문의에 답함", inquiry_status: "문의 상태 바꿈", inquiry_close: "문의 닫음", inquiry_open: "문의 다시 엶",
    inquiry_hide: "모두의 문의 숨김", inquiry_show: "모두의 문의 다시 보이기", inquiry_assign: "문의 맡음",
    dev_create: "개발사항 만듦", dev_edit: "개발사항 고침", dev_status: "개발사항 처리", dev_bulk: "개발사항 한 번에 처리",
    dev_delete: "개발사항 지움", dev_restore: "개발사항 되살림", dev_link: "문의를 개발사항에 연결", dev_unlink: "개발사항에서 문의 뗌",
    dev_reply: "개발사항의 문의에 한 번에 답함", template_add: "자주 쓰는 답 추가", template_remove: "자주 쓰는 답 지움",
    // [MON-08][MON-09][MON-10]
    launch_on: "정식 출시 켬(결제·광고)", launch_off: "정식 출시 끔", launch_settings: "정식 출시 설정 바꿈",
    device_link: "관리자 휴대폰 연결", device_approve: "관리자 휴대폰 승인", device_remove: "관리자 휴대폰 끊음",
    pass_move: "이용권을 새 휴대폰으로 옮김", trial_stop: "동의 없는 무료 체험의 다음 결제 멈춤",
    // [SRV-04][SRV-05][ADMIN-14]
    notice_add: "공지 올림", notice_edit: "공지 고침", notice_remove: "공지 내림", switch_off: "기능 끔", switch_on: "기능 다시 켬",
    ops_done: "이달 할 일 마침", ops_undo: "이달 할 일 되돌림", share_remove: "공유 링크 내림",
    // [ADMIN-16][ADMIN-17][ADMUX-06]
    inquiry_bulk: "문의 한 번에 처리", reject_undo: "거절 되돌림", template_edit: "자주 쓰는 답 고침", sla: "답 목표 바꿈",
    rate_set: "임금표 처리",
  };
  /** [ADMIN-17] The activity screen's 종류 filter, as the server names them. */
  const AUDIT_KIND_NAMES = { login: "로그인", admins: "관리자", review: "검토", companies: "회사·규칙", rewards: "보상", community: "토론·임금표",
    inquiries: "문의", dev: "개발사항", ops: "운영", settings: "설정" };
  // [ADMUX-01] Every error code the server sends has words in src/admin-lib.js (a test checks).
  const ERRORS = L.ERRORS;

  const TEMPLATES = [
    ["확인 중", "알려 주셔서 고마워요. 지금 확인하고 있어요. 고치는 대로 다시 알려 드릴게요."],
    ["고쳤어요", "말씀하신 문제를 고쳤어요. 앱을 {앱버전}보다 새 버전으로 업데이트한 뒤 다시 확인해 주세요."],
    ["더 알려 주세요", "정확히 확인하려면 조금 더 알려 주세요. 언제, 어떤 화면에서, 무엇을 눌렀을 때 생겼는지 적어 주시면 큰 도움이 돼요."],
    ["설정 안내", "설정에서 바로 바꿀 수 있어요. 설정 > 해당 항목에서 원하는 값으로 바꿔 보세요."],
  ].map(([label, text], i) => ({ id: -1 - i, label, text, topic: "", uses: 0 }));

  /**
   * [ADMIN-12][ADMIN-16] The shared answers: the question's own list (its topic first, then the most
   * used) when one is open, else the list as loaded, else the built-in ones until they come.
   */
  const templateList = () => (S.route.name === "inquiries" && S.data.inquiry?.id === S.route.id && S.data.inquiry.templates
    ? S.data.inquiry.templates : S.templates ?? TEMPLATES);

  // ---- [ADMUX-03] this device's choices, kept in the browser only ----

  const PREF_KEY = "bamnat-admin-prefs";
  const PREFS = { theme: "system", size: "normal", tabs: [], sound: false, next: true, views: [], loginMode: "account" };
  function readPrefs() {
    try {
      const v = JSON.parse(localStorage.getItem(PREF_KEY) ?? "{}");
      return { ...PREFS, ...(v && typeof v === "object" ? v : {}) };
    } catch {
      return { ...PREFS };
    }
  }
  function savePrefs() {
    try {
      localStorage.setItem(PREF_KEY, JSON.stringify(S.prefs));
    } catch {
      // A private window: the choice lasts until the tab closes.
    }
    applyPrefs();
  }
  function applyPrefs() {
    const root = document.documentElement;
    if (S.prefs.theme === "light" || S.prefs.theme === "dark") root.dataset.theme = S.prefs.theme;
    else delete root.dataset.theme;
    if (S.prefs.size === "small" || S.prefs.size === "large") root.dataset.size = S.prefs.size;
    else delete root.dataset.size;
  }

  // ---- [ADMUX-01] drafts: what was typed survives a repaint, a reload and a lost sign-in ----

  const DRAFT = "bamnat-admin-draft:";
  /** Boxes whose words are kept per screen; anything else is only kept across a repaint. */
  const KEEP = ["reply", "inq-note", "dev-note"];
  const draftKey = (id) => `${DRAFT}${S.route.name}/${S.route.id ?? ""}:${id}`;
  function readDraft(id) {
    try {
      return localStorage.getItem(draftKey(id)) ?? "";
    } catch {
      return "";
    }
  }
  function writeDraft(id, value) {
    try {
      if (value) localStorage.setItem(draftKey(id), value);
      else localStorage.removeItem(draftKey(id));
    } catch {
      // Not kept; the box still has it.
    }
  }
  function clearDrafts() {
    try {
      for (const k of Object.keys(localStorage)) if (k.startsWith(DRAFT)) localStorage.removeItem(k);
    } catch {
      // Nothing kept.
    }
  }

  const S = {
    phase: "boot", // boot, login, app, join, install
    me: null, overview: null, health: null, server: null,
    route: { name: "home", id: null, params: {} },
    recent: [], // [ADMUX-04] details opened this visit, for the palette
    opened: new Map(), // [ADMUX-05] <details data-key> the admin opened or shut, by key
    data: {}, // per screen
    // [ADMIN-13] 지표 기간(일), [ADMIN-14] 운영 화면의 달(YYYY-MM, 비면 이번 달).
    metricDays: 28, opsMonth: "",
    reviewFilter: "all", inquiryStatus: "open", inquiryTopic: "", rewardTab: "owed", query: "", postFilter: "flagged", rateFilter: "review",
    // [ADMIN-12] Whose questions: everyone's, mine, nobody's. [ADMIN-10] The 개발사항 list's filter, words and picked items.
    inquiryWho: "", devStatus: "open", devQuery: "", devWho: "", devPick: null, templates: null,
    // [ADMIN-16] 문의 찾기·정렬, 여러 개 고르기. [ADMIN-17] 활동 기록 거르기. 토론·보상 찾기.
    inquiryQuery: "", inquirySort: "default", inquiryPick: null, postQuery: "", rewardQuery: "",
    audit: { actor: "", kind: "", from: "", to: "" },
    draft: null, slaDraft: null, error: "", busy: 0, online: navigator.onLine,
    join: null, // invite info on the join page
    installPrompt: null, installed: matchMedia("(display-mode: standalone)").matches || navigator.standalone === true,
    prefs: readPrefs(),
    // [ADMUX-01] the screen whose data failed to come, [ADMUX-08] a newer build, answers from the phone's copy.
    failed: "", newBuild: false, stale: false, relogin: false,
    // [ADMUX-04] 큐 모드: answering the waiting questions one after another.
    queue: null, cursor: -1,
  };

  const esc = L.esc;
  const icon = (paths) => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths}</svg>`;
  const day = (iso) => (iso ? new Date(iso).toLocaleDateString("ko-KR", { month: "long", day: "numeric" }) : "");
  const moment = (iso) => (iso ? new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" }) : "");
  const clock = (iso) => new Date(iso).toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
  const ago = (iso) => L.ago(iso);
  const can = (area, level = "view") => L.can(S.me, area, level);
  const visible = (n) => (n.owner ? S.me?.owner : n.area ? can(n.area) : true);
  const wide = () => matchMedia("(min-width:1024px)").matches;
  /** [ADMUX-02] A screen's address; a list keeps the filters it has now, so 뒤로 lands where the admin was. */
  const href = (name, id) => L.routeHash(name, id ?? null, id ? {} : paramsOf(name));
  const routeKey = () => `${S.route.name}/${S.route.id ?? ""}`;
  /** The pill for minutes left before an answer target (null: nobody waits). */
  const slaPill = (minutes) => {
    const t = L.slaText(minutes);
    return t ? `<span class="pill ${t.tone}">${esc(t.text)}</span>` : "";
  };
  let toastTimer = 0;
  let searchTimer = 0;
  let pendingConfirm = null;
  let gKey = "";
  let gTimer = 0;
  let typingSent = 0;
  let pollTimer = 0;
  const seq = L.sequencer();

  // ---- [ADMUX-02] filters in the address ----

  /** Which state each screen's address filters stand for. */
  const BIND = {
    inquiries: { status: "inquiryStatus", who: "inquiryWho", topic: "inquiryTopic", q: "inquiryQuery", sort: "inquirySort" },
    dev: { status: "devStatus", who: "devWho", q: "devQuery" },
    review: { show: "reviewFilter" },
    community: { show: "postFilter", q: "postQuery" },
    rewards: { status: "rewardTab", q: "rewardQuery" },
    companies: { q: "query" },
    metrics: { days: "metricDays" },
    ops: { month: "opsMonth" },
  };
  function paramsOf(name) {
    if (name === "audit") return { ...S.audit };
    const bind = BIND[name];
    return bind ? Object.fromEntries(Object.entries(bind).map(([k, field]) => [k, String(S[field] ?? "")])) : {};
  }
  function applyParams(route) {
    if (route.id) return;
    const p = route.params ?? {};
    if (route.name === "audit") {
      S.audit = { actor: p.actor ?? "", kind: p.kind ?? "", from: p.from ?? "", to: p.to ?? "" };
      return;
    }
    for (const [k, field] of Object.entries(BIND[route.name] ?? {})) {
      if (!Object.hasOwn(p, k)) continue;
      S[field] = field === "metricDays" ? ([7, 28, 90].includes(Number(p[k])) ? Number(p[k]) : 28) : p[k];
    }
  }
  /** Writes the filters into the address without a new history step or a reload. */
  function syncUrl() {
    if (S.phase !== "app") return;
    const next = `/admin/${href(S.route.name, S.route.id)}`;
    if (`${location.pathname}${location.hash}` !== next) history.replaceState(null, "", next);
  }

  // ---- talking to the server ----

  /** [ADMUX-01] GET answers by path with their ETag: an unchanged list comes back as 304 and no body. */
  const etags = new Map();

  async function api(path, method = "GET", body, { signal } = {}) {
    S.busy++;
    progress();
    try {
      let res;
      const cached = method === "GET" ? etags.get(path) : null;
      try {
        res = await fetch(`/api/admin${path}`, {
          method,
          credentials: "same-origin",
          signal,
          headers: method === "GET" ? (cached ? { "if-none-match": cached.etag } : {}) : { "content-type": "application/json", "x-admin": "1" },
          body: method === "GET" ? undefined : JSON.stringify(body || {}),
        });
      } catch (e) {
        if (e?.name === "AbortError") throw Object.assign(new Error("aborted"), { quiet: true, data: {} });
        throw Object.assign(new Error("offline"), { data: {} });
      }
      if (res.status === 304 && cached) return structuredClone(cached.data);
      if (method === "GET") {
        // [ADMUX-01] The service worker answers from the phone's copy when the network is gone.
        const stale = res.headers.get("x-admin-cache") === "1";
        if (stale !== S.stale) {
          S.stale = stale;
          paintBanners();
        }
      }
      const data = await res.json().catch(() => ({}));
      if (res.status === 401 && data.error === "login" && S.phase === "app") {
        // [ADMUX-01] Signed out under way: sign in again over the screen, so nothing typed is lost.
        openRelogin();
        throw Object.assign(new Error("login"), { quiet: true, data });
      }
      if (!res.ok) throw Object.assign(new Error(data.error || "server"), { data, status: res.status });
      const etag = res.headers.get("etag");
      if (method === "GET" && etag) etags.set(path, { etag, data: structuredClone(data) });
      if (method !== "GET") etags.clear();
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
    document.getElementById("main")?.setAttribute("aria-busy", String(S.busy > 0));
  }

  /** [ADMUX-01] A short note at the bottom; with [undo], a button that takes the last step back for 8 seconds. */
  function toast(text, undo) {
    toastBox.innerHTML = `<span>${esc(text)}</span>${undo ? '<button class="undo" type="button" data-undo="1">되돌리기</button>' : ""}`;
    toastBox.classList.add("show");
    toastBox.classList.toggle("has-undo", Boolean(undo));
    S.undo = undo ?? null;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastBox.classList.remove("show", "has-undo");
      S.undo = null;
    }, undo ? 8000 : 3000);
  }

  function fail(e) {
    if (!e.quiet) toast(e.message === "server" || !ERRORS[e.message] ? L.errorText(e.message) : ERRORS[e.message]);
  }

  // ---- routes ----

  function readRoute() {
    const route = L.parseRoute(location.hash);
    const known = [...NAV.map((n) => n.key), "account", "more"];
    if (!known.includes(route.name)) Object.assign(route, { name: "home", id: null, params: {} });
    const nav = NAV.find((n) => n.key === route.name);
    if (nav && !visible(nav)) Object.assign(route, { name: "home", id: null, params: {} });
    return route;
  }

  function go(name, id) {
    const next = href(name, id);
    if (location.hash === next) load();
    else location.hash = next;
  }

  /**
   * Fetches what the current screen shows; the home numbers come along so badges stay right. [ADMUX-01]
   * Each call has a number: an answer for a screen the admin already left is dropped instead of drawn.
   */
  async function load({ quiet = false, overview = true } = {}) {
    const { name, id } = S.route;
    const key = routeKey();
    const n = seq.next("load");
    const keep = (fn) => (d) => {
      if (seq.current("load", n)) fn(d);
    };
    const jobs = [];
    try {
      if (overview) {
        jobs.push(api("/overview").then(keep((o) => {
          S.overview = o;
          S.me = o.me;
          // [ADMUX-08] The server has a newer app than this tab: one tap reloads it.
          S.newBuild = Boolean(o.build && o.build !== BUILD);
        })));
      }
      if (name === "review") jobs.push(api("/queue").then(keep((d) => { S.data.queue = d; })));
      if (name === "inquiries" && (!id || wide())) jobs.push(loadInquiries(n));
      if (name === "inquiries" && id) jobs.push(api(`/inquiries/${id}`).then(keep((d) => { S.data.inquiry = d; })));
      if (name === "dev" && !id) jobs.push(loadDev(n));
      if (name === "dev" && id) jobs.push(api(`/dev/${id}`).then(keep((d) => { S.data.dev = d; })));
      if (name === "companies" && !id) jobs.push(api(`/companies?q=${encodeURIComponent(S.query)}`).then(keep((d) => { S.data.list = d; })));
      if (name === "companies" && id) jobs.push(api(`/companies/${id}`).then(keep((d) => { S.data.company = d; })));
      if (name === "community") {
        // An id is one talk: company.setup, opened from the review screen.
        const [company, setup] = (id ?? "").split(".");
        const q = S.postQuery ? `&q=${encodeURIComponent(S.postQuery)}` : "";
        if (!id && S.postFilter === "rates") jobs.push(api(`/rates?status=${S.rateFilter}${q}`).then(keep((d) => { S.data.rates = d; })));
        else jobs.push(api(id ? `/posts?company=${company}&setup=${setup}` : `/posts?status=${S.postFilter}${q}`).then(keep((d) => { S.data.posts = d; })));
      }
      if (name === "rewards") {
        jobs.push(api(`/rewards?status=${S.rewardTab}${S.rewardQuery ? `&q=${encodeURIComponent(S.rewardQuery)}` : ""}`).then(keep((d) => { S.data.rewards = d; })));
      }
      if (name === "admins") jobs.push(api("/admins").then(keep((d) => { S.data.admins = d; })), api("/devices").then(keep((d) => { S.data.phones = d; })));
      if (name === "audit") jobs.push(api(`/audit${auditQuery()}`).then(keep((d) => { S.data.audit = d; })));
      if (name === "ops") {
        jobs.push(api("/notices").then(keep((d) => { S.data.notices = d; })), api(`/ops${S.opsMonth ? `?month=${S.opsMonth}` : ""}`).then(keep((d) => { S.data.ops = d; })));
      }
      if (name === "metrics") jobs.push(api(`/metrics?days=${S.metricDays}`).then(keep((d) => { S.data.metrics = d; })));
      if (name === "shares") jobs.push(api("/shares").then(keep((d) => { S.data.shares = d; })));
      // [ADMIN-19] 알림 on this device; [ADMIN-15] the owner's server state comes from /server now.
      if (name === "settings") jobs.push(api("/me/push").then(keep((d) => { S.data.push = d; })).catch(() => {}));
      if (name === "settings" && S.me?.owner) {
        jobs.push(api("/server").then(keep((d) => { S.server = d; })));
        // [MON-10] The 정식 출시 card is the owner's; S.me is known from the last overview.
        jobs.push(api("/launch").then(keep((d) => { S.data.launch = d; })));
      }
      const results = await Promise.allSettled(jobs);
      const failed = results.find((r) => r.status === "rejected" && !r.reason?.quiet);
      if (seq.current("load", n)) {
        // [ADMUX-01] Nothing came for the screen: it says so with a button to try again, not a skeleton forever.
        S.failed = failed && results.some((r, i) => r.status === "rejected" && (i > 0 || !overview)) ? key : "";
        if (failed && !quiet) fail(failed.reason);
      }
    } catch (e) {
      if (!quiet) fail(e);
    }
    // A screen left while it loaded does not repaint over the new one.
    if (seq.current("load", n) && routeKey() === key && S.phase === "app") render({ keepScroll: true });
  }

  /** [ADMIN-16] The questions list: one page, or the next page added to the one shown ([more]). */
  async function loadInquiries(n, more = false) {
    try {
      // [INQ-04] 놓친 문의 is its own filter beside who holds it.
      const who = S.inquiryWho === "late" ? "&late=1" : S.inquiryWho ? `&who=${S.inquiryWho}` : "";
      const q = S.inquiryQuery ? `&q=${encodeURIComponent(S.inquiryQuery)}` : "";
      const after = more && S.data.inquiries?.next ? `&after=${S.data.inquiries.next}` : "";
      const d = await api(`/inquiries?status=${S.inquiryStatus}${S.inquiryTopic ? `&topic=${S.inquiryTopic}` : ""}${who}${q}&sort=${S.inquirySort}${after}`);
      if (n !== undefined && !seq.current("load", n)) return;
      S.data.inquiries = more ? { ...d, inquiries: [...S.data.inquiries.inquiries, ...d.inquiries] } : d;
      S.data.inquiriesMissing = false;
    } catch (e) {
      // The server has no inquiry box yet: the screen says so instead of failing.
      if (e.message === "not_found") S.data.inquiriesMissing = true;
      else throw e;
    }
  }

  async function loadDev(n, more = false) {
    const after = more && S.data.devList?.next ? `&after=${S.data.devList.next}` : "";
    const d = await api(`/dev?status=${S.devStatus}&q=${encodeURIComponent(S.devQuery)}${S.devWho ? `&who=${S.devWho}` : ""}${after}`);
    if (n !== undefined && !seq.current("load", n)) return;
    S.data.devList = more ? { ...d, items: [...S.data.devList.items, ...d.items] } : d;
  }

  /** [ADMIN-12] The answers every admin shares; until the server has them, the ones built in. */
  async function loadTemplates() {
    try {
      const d = await api("/templates");
      S.templates = d.templates;
      S.topicNames = d.topicNames;
    } catch {
      S.templates = null;
    }
  }

  /** [ADMIN-17] The activity filters as the address the server reads. */
  const auditQuery = (before) => {
    const p = Object.entries({ ...S.audit, ...(before ? { before } : {}) }).filter(([, v]) => v);
    return p.length ? `?${p.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&")}` : "";
  };

  // ---- the frame around every screen ----

  /** [ADMUX-01] What the frame was drawn for; while it holds, a repaint only redraws the screen inside. */
  let shellKey = "";

  function render({ keepScroll = false } = {}) {
    if (S.phase === "boot") return;
    if (S.phase !== "app") {
      shellKey = "";
      app.innerHTML = S.phase === "login" ? loginView() : S.phase === "join" ? joinView() : installView();
      if (S.phase === "login") document.querySelector(".gate input:not([disabled])")?.focus();
      return;
    }
    const y = window.scrollY;
    const items = NAV.filter(visible);
    const tabs = L.bottomTabs(items, S.prefs.tabs, S.route.name);
    const key = JSON.stringify([items.map((n) => n.key), tabs.tabs, S.me?.name, S.me?.owner]);
    if (key !== shellKey || !document.getElementById("main")) {
      app.innerHTML = shell(items, tabs);
      shellKey = key;
    }
    paintFrame(items, tabs);
    paintMain();
    if (keepScroll) window.scrollTo(0, y);
  }

  function navLink(n, cls, lit) {
    const count = n.badge ? n.badge(S.overview ?? {}) : 0;
    return `<a class="${cls}" href="${href(n.key)}"${lit ? ' aria-current="page"' : ""} data-nav="${n.key}">${icon(P[n.key])}
      <span class="nav-label">${esc(n.label)}</span><i class="badge" data-badge="${n.key}">${count || ""}</i></a>`;
  }

  function shell(items, tabs) {
    const groups = [["main", "업무"], ["manage", "관리"]];
    const bottom = tabs.tabs.map((k) => items.find((n) => n.key === k)).filter(Boolean);
    const rest = items.filter((n) => !tabs.tabs.includes(n.key));
    const more = { key: "more", label: "더 보기", badge: (o) => rest.reduce((n, x) => n + (x.badge ? x.badge(o) : 0), 0) };
    return `
      <div id="banners"></div>
      <a class="skip" href="#main">본문으로 건너뛰기</a>
      <div class="shell">
        <aside class="side" aria-label="메뉴">
          <div class="brand-row"><img src="/admin/icon.svg" alt=""><b>밤낮 관리자</b></div>
          ${groups.map(([g, label]) => {
            const list = items.filter((n) => n.group === g);
            return list.length ? `<p class="group">${label}</p><ul class="nav">${list.map((n) => `<li>${navLink(n, "", false)}</li>`).join("")}</ul>` : "";
          }).join("")}
          <div class="foot">
            <a class="who" href="${href("account")}" data-nav="account">
              <span class="avatar" aria-hidden="true">${esc((S.me?.name ?? "?").slice(0, 1))}</span>
              <span><b>${esc(S.me?.name ?? "")}</b><small>${S.me?.owner ? "최고 관리자" : esc(S.me?.email ?? "")}</small></span></a>
            <!-- On the narrow rail the account link is an icon; the wide sidebar shows the name card instead. -->
            <ul class="nav compact-only">${navLink({ key: "account", label: "내 계정" }, "", false)}</ul>
          </div>
        </aside>
        <div class="content">
          <header class="top">
            <div class="bar" id="bar"></div>
            <div id="progress" class="progress${S.busy ? " on" : ""}"></div>
          </header>
          <main class="main" id="main" tabindex="-1"></main>
        </div>
      </div>
      <nav class="tabs" aria-label="메뉴">${bottom.map((n) => navLink(n, "tab", false)).join("")}${navLink(more, "tab", false)}</nav>`;
  }

  /** The parts of the frame that change with the screen: banners, the title bar, which menu item is lit. */
  function paintFrame(items, tabs) {
    paintBanners();
    const { title, sub, back } = heading(tabs);
    const bar = document.getElementById("bar");
    if (bar) {
      bar.innerHTML = `${back ? `<a class="icon-btn" href="${back}" aria-label="뒤로">${icon(P.back)}</a>` : '<img class="mark" src="/admin/icon.svg" alt="">'}
        <h1 tabindex="-1" id="title">${esc(title)}${sub ? `<span class="sub">${esc(sub)}</span>` : ""}</h1>
        <button class="icon-btn" data-act="palette" aria-label="찾기·이동" title="찾기·이동 (Ctrl+K)">${icon('<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>')}</button>
        <button class="icon-btn" data-act="shortcuts" aria-label="단축키" title="단축키 (?)">${icon(P.help)}</button>
        <button class="icon-btn" data-act="refresh" aria-label="새로 고침" title="새로 고침 (R)">${icon(P.refresh)}</button>`;
    }
    for (const el of document.querySelectorAll("[data-nav]")) {
      const k = el.dataset.nav;
      const lit = el.classList.contains("tab") ? k === tabs.lit : k === S.route.name;
      if (lit) el.setAttribute("aria-current", "page");
      else el.removeAttribute("aria-current");
      el.setAttribute("href", href(k));
    }
    updateBadges(items, tabs);
    const left = (S.overview?.inquiries ?? 0) + (S.overview?.pending ?? 0);
    document.title = left ? `(${left}) 밤낮 관리자` : "밤낮 관리자";
  }

  /** Offline, answers from the phone's copy, a newer app, 큐 모드. */
  function paintBanners() {
    const banners = document.getElementById("banners");
    if (!banners) return;
    banners.innerHTML = `${S.online ? "" : '<div class="offline" role="status">인터넷 연결이 끊겼어요. 다시 연결되면 저절로 새로 고쳐요.</div>'}
      ${S.stale ? '<div class="banner warn" role="status">연결이 끊겨 이 기기에 저장된 내용을 보여요. 바꾸는 일은 연결된 뒤에 해 주세요.</div>' : ""}
      ${S.newBuild ? '<div class="banner" role="status">관리자 앱이 새로 나왔어요.<button class="btn small" data-act="reload">새로 열기</button></div>' : ""}`;
  }

  /**
   * [ADMUX-01] Draws the screen into the frame and keeps what the admin was doing: words typed in a box,
   * where the cursor was, which folds were open. Replies and notes also stay in the browser until sent.
   */
  function paintMain() {
    const main = document.getElementById("main");
    if (!main) return;
    const typed = new Map();
    for (const el of main.querySelectorAll("input[id],textarea[id],select[id]")) {
      if (el.type === "hidden" || el.type === "checkbox" || el.type === "radio" || el.readOnly) continue;
      const was = el.tagName === "SELECT" ? [...el.options].find((o) => o.defaultSelected)?.value ?? el.options[0]?.value : el.defaultValue;
      if (el.value !== was) typed.set(el.id, el.value);
    }
    // A <details> with data-key is remembered by that key (S.opened), the others by their summary's words.
    const keyOf = (d) => d.dataset.key || d.querySelector(":scope>summary")?.textContent.trim();
    const open = new Set([...main.querySelectorAll("details[open]")].map(keyOf));
    const shut = new Set([...main.querySelectorAll("details:not([open])")].map(keyOf));
    const focus = document.activeElement && main.contains(document.activeElement) ? document.activeElement : null;
    const at = focus?.id ? { id: focus.id, start: focus.selectionStart ?? null, end: focus.selectionEnd ?? null } : null;
    main.innerHTML = view();
    rememberOpened();
    for (const id of KEEP) {
      const el = document.getElementById(id);
      if (el && !typed.has(id) && !el.value) el.value = readDraft(id);
    }
    for (const [id, value] of typed) {
      const el = document.getElementById(id);
      if (el && !el.readOnly) el.value = value;
    }
    for (const d of main.querySelectorAll("details")) {
      const t = keyOf(d);
      if (open.has(t)) d.open = true;
      else if (shut.has(t)) d.open = false;
    }
    document.getElementById("reply")?.dispatchEvent(new Event("input", { bubbles: true }));
    if (at) {
      const el = document.getElementById(at.id);
      if (el) {
        el.focus({ preventScroll: true });
        if (at.start !== null && typeof el.setSelectionRange === "function") {
          try {
            el.setSelectionRange(at.start, at.end);
          } catch {
            // Not a text box.
          }
        }
      }
    }
    if (S.cursor >= 0) markCursor();
  }

  function updateBadges(items = NAV.filter(visible), tabs = L.bottomTabs(items, S.prefs.tabs, S.route.name)) {
    const o = S.overview ?? {};
    for (const n of NAV) {
      document.querySelectorAll(`[data-badge="${n.key}"]`).forEach((el) => { el.textContent = (n.badge ? n.badge(o) : 0) || ""; });
    }
    const rest = items.filter((n) => !tabs.tabs.includes(n.key)).reduce((n, x) => n + (x.badge ? x.badge(o) : 0), 0);
    document.querySelectorAll('[data-badge="more"]').forEach((el) => { el.textContent = rest || ""; });
    // [ADMUX-08] The installed app's icon shows how many questions wait.
    try {
      if (o.inquiries) navigator.setAppBadge?.(o.inquiries)?.catch?.(() => {});
      else navigator.clearAppBadge?.()?.catch?.(() => {});
    } catch {
      // Not offered here.
    }
  }

  function heading(tabs) {
    const { name, id } = S.route;
    const phone = !matchMedia("(min-width:600px)").matches;
    if (name === "companies" && id) return { title: S.data.company?.name ?? "회사", sub: "회사·규칙", back: href("companies") };
    if (name === "inquiries" && id) {
      const q = S.data.inquiry;
      return { title: q ? KIND_NAMES[q.kind] ?? "문의" : "문의", sub: q ? `${STATUS_NAMES[q.status] ?? ""} · ${moment(q.createdAt)}` : "", back: href("inquiries") };
    }
    if (name === "dev" && id) {
      const d = S.data.dev;
      return { title: d && d.id === id ? d.title : "개발사항", sub: d && d.id === id ? `${DEV_NAMES[d.status]}${d.deletedAt ? " · 지움" : ""} · 문의 ${d.links}건` : "", back: href("dev") };
    }
    if (name === "community" && id) {
      const first = S.data.posts?.posts?.[0];
      return { title: first ? first.company.name : "토론", sub: first ? `${first.dept || "회사 전체"} 규칙의 토론` : "규칙 토론", back: href("community") };
    }
    if (name === "account") return { title: "내 계정", back: phone ? href("more") : null };
    if (name === "more") return { title: "더 보기" };
    const n = NAV.find((x) => x.key === name);
    // [ADMUX-03] A screen that is not one of the four tabs has a way back to 더 보기 on a phone.
    return { title: name === "home" ? "밤낮 관리자" : n?.label ?? "", back: phone && tabs?.back ? href("more") : null };
  }

  function view() {
    const { name, id } = S.route;
    // [ADMUX-01] The data did not come: say so, with a way to try again, instead of a skeleton for ever.
    if (S.failed === routeKey() && !hasData()) {
      return `<div class="failed" role="alert">${icon(P.refresh)}<h2>불러오지 못했어요</h2><p>${S.online ? "서버가 잠시 답하지 않아요." : "인터넷 연결을 확인해 주세요."}</p>
        <button class="btn primary" data-act="refresh">다시 불러오기</button></div>`;
    }
    if (name === "home") return homeView();
    if (name === "review") return reviewView();
    if (name === "inquiries") return id ? inquiryView() : inquiriesView();
    if (name === "dev") return id ? devView() : devListView();
    if (name === "companies") return id ? companyView(S.data.company) : companiesView();
    if (name === "community") return communityView();
    if (name === "rewards") return rewardsView();
    if (name === "admins") return adminsView();
    if (name === "audit") return auditView();
    if (name === "ops") return opsView();
    if (name === "metrics") return metricsView();
    if (name === "shares") return sharesView();
    if (name === "settings") return settingsView();
    if (name === "account") return accountView();
    if (name === "more") return moreView();
    return "";
  }

  /** Whether the current screen has anything to show yet. */
  function hasData() {
    const { name, id } = S.route;
    const d = S.data;
    return Boolean({
      home: S.overview, review: d.queue, inquiries: id ? d.inquiry?.id === id : d.inquiries, dev: id ? d.dev?.id === id : d.devList,
      companies: id ? d.company?.id === id : d.list, community: d.posts || d.rates, rewards: d.rewards, admins: d.admins, audit: d.audit,
      ops: d.ops && d.notices, metrics: d.metrics, shares: d.shares, settings: true, account: true, more: true,
    }[name]);
  }

  const empty = (title, text, action = "") => `<div class="empty">${icon(P.moon)}<h2>${esc(title)}</h2>${text ? `<p>${esc(text)}</p>` : ""}${action}</div>`;
  const skeleton = (n = 2) => Array.from({ length: n }, () => '<div class="card skeleton"></div>').join("");
  const lines = (list) => `<dl class="lines">${list.map(([label, text]) => `<div><dt>${esc(label)}</dt><dd>${esc(text)}</dd></div>`).join("")}</dl>`;
  const seg = (act, key, current, label, n) =>
    `<button class="seg" data-act="${act}" data-key="${key}" aria-pressed="${current === key}">${esc(label)}${n ? `<span class="n">${n}</span>` : ""}</button>`;
  /** [ADMIN-16] 더 보기 under a paged list. */
  const moreButton = (next, act, total, shown) => (next ? `<div class="more"><button class="btn" data-act="${act}">더 보기${total ? ` <span class="meta">${shown}/${total}</span>` : ""}</button></div>` : "");

  // ---- home [ADMUX-06] ----

  function homeView() {
    const o = S.overview;
    if (!o) return skeleton(3);
    const hour = new Date().getHours();
    const hi = hour < 6 ? "늦은 밤이에요" : hour < 12 ? "좋은 아침이에요" : hour < 18 ? "좋은 오후예요" : "좋은 저녁이에요";
    const tiles = [];
    const tile = (k, v, d, to, hot, extra = "") => tiles.push(`<a class="tile${hot && v ? " hot" : ""}" href="${to}"><span class="k">${esc(k)}</span>
      <span class="v">${v ?? 0}${extra}</span><span class="d">${esc(d)}</span></a>`);
    const inq = (params) => L.routeHash("inquiries", null, params);
    if (can("inquiries")) {
      const diff = (o.inquiriesToday ?? 0) - (o.inquiriesYesterday ?? 0);
      tile("오늘 새 문의", o.inquiriesToday, `어제 ${o.inquiriesYesterday ?? 0}건`, inq({ status: "open", sort: "newest" }), false,
        diff ? `<span class="delta ${diff > 0 ? "up" : "down"}">${diff > 0 ? "▲" : "▼"}${Math.abs(diff)}</span>` : "");
      tile("답 대기", o.inquiries, "답을 기다리는 문의", inq({ status: "open", sort: "sla" }), true);
      tile("오늘 목표 넘김", o.slaLateToday, "오늘 안에 답 목표를 넘겨요", inq({ status: "open", sort: "sla" }), true);
      tile("아쉬워요", o.inquiriesBad, "답이 아쉬웠다는 문의", inq({ status: "answered" }), true);
    }
    if (can("review")) {
      tile("검토 대기", o.pending, "채택이나 거절을 기다려요", href("review"), true);
      tile("앱에 없는 방식", o.asks, "답을 기다려요", L.routeHash("review", null, { show: "asks" }), true);
    }
    if (can("dev")) tile("개발사항 접수", o.devTodo, can("devStatus", "edit") ? "처리를 기다려요" : "개발 전이에요", L.routeHash("dev", null, { status: "todo" }), can("devStatus", "edit"));
    if (can("community")) tile("신고된 글", o.flaggedPosts, "토론에서 확인할 글", href("community"), true);
    if (can("rewards")) tile("보상 대기", o.owed, "드릴 프로 기간", href("rewards"), false);
    if (S.me.owner) tile("승인 대기 관리자", o.admins?.waiting, "가입한 관리자", href("admins"), true);
    // [ADMIN-14] 이달 할 일 in place of reminder mail (결정 D1).
    if (can("ops")) tile("이달 할 일", o.opsLeft, "요율·공휴일·처리방침 점검", href("ops"), false);
    const stats = [];
    if (can("companies")) stats.push(["회사", o.companies], ["쓰는 규칙", o.rules]);
    if (can("review")) stats.push(["모든 제보", o.reports]);
    if (can("community")) stats.push(["토론 글", o.posts]);
    // [ADMUX-06] 지금 할 일: the waiting questions nearest their target, then setups and flagged posts,
    // each with the one step it most likely needs, right here.
    const editInq = can("inquiries", "edit");
    const queue = (o.queue ?? []).map((x) => {
      if (x.type === "inquiry") {
        const mine = x.assignee?.id === S.me.id;
        return `<li><span class="q-main"><b><a href="${href("inquiries", x.id)}">${esc(KIND_NAMES[x.kind] ?? "문의")} · ${esc(x.text || "(내용 없음)")}</a></b>
          <small>${esc(ago(x.at))}${x.same ? ` · 나도 ${x.same}` : ""}${x.assignee ? ` · ${mine ? "내가 맡음" : esc(x.assignee.name)}` : " · 아무도 안 맡음"}</small></span>
          ${slaPill(x.slaLeft)}${editInq && !x.assignee ? `<button class="btn small" data-act="queue-assign" data-id="${x.id}">맡기</button>` : ""}
          <a class="btn small${mine ? " primary" : ""}" href="${href("inquiries", x.id)}">답하기</a></li>`;
      }
      if (x.type === "group") {
        return `<li><span class="q-main"><b><a href="${L.routeHash("review", null, { show: "groups" })}">제보 · ${esc(x.company)}${x.dept ? ` ${esc(x.dept)}` : ""}</a></b>
          <small>${x.n}건 · ${esc(ago(x.at))}부터</small></span><a class="btn small" href="${L.routeHash("review", null, { show: "groups" })}">검토</a></li>`;
      }
      return `<li><span class="q-main"><b><a href="${href("community")}">신고된 글 · ${esc(x.company)}</a></b><small>${esc(x.text)}</small></span>
        <span class="pill ${x.hidden ? "bad" : "warn"}">${x.hidden ? "가려짐" : `신고 ${x.flags}`}</span><a class="btn small" href="${href("community")}">보기</a></li>`;
    });
    const todo = [];
    // [ADMIN-12] An entry opens its screen already filtered, so the first tap lands on the work.
    const add = (n, text, to, tone) => n && todo.push(`<li><a href="${to}"><span class="dot ${tone}" aria-hidden="true"></span>${esc(text)}<small>${n}건</small></a></li>`);
    if (S.me.owner) add(o.admins?.waiting, "승인을 기다리는 관리자", href("admins"), "bad");
    if (can("inquiries")) {
      add(o.inquiriesMine, "내가 맡은, 답을 기다리는 문의", inq({ who: "mine", sort: "sla" }), "bad");
      add(o.inquiriesFree, "아무도 맡지 않은 문의", inq({ who: "free", sort: "sla" }), "bad");
      add(o.inquiriesLate, "하루 넘게 답을 기다리는 문의(놓친 문의)", inq({ who: "late" }), "bad");
    }
    if (can("dev")) {
      add(o.devMine, "내가 맡은 개발사항", L.routeHash("dev", null, { who: "mine" }), "");
      if (can("devStatus", "edit")) add(o.devTodo, "처리를 기다리는 개발사항(접수)", L.routeHash("dev", null, { status: "todo" }), "warn");
    }
    if (can("review")) {
      add(o.asks, "앱에 없는 방식", L.routeHash("review", null, { show: "asks" }), "warn");
      add(o.flagged, "달라요가 달린 규칙", L.routeHash("review", null, { show: "flagged" }), "warn");
    }
    // [COMM-08] The rules promise a look within 30 days of a post the flags hid.
    if (can("community")) add(o.flaggedPostsLate, "신고로 가려진 지 3주 넘은 글(30일 안에 확인)", L.routeHash("community", null, { show: "hidden" }), "bad");
    if (can("rewards")) add(o.owed, "드릴 보상", href("rewards"), "");
    if (can("ops")) add(o.opsLeft, "이달 운영 할 일", href("ops"), "");
    const feed = o.recent ?? null;
    const people = (o.assigned ?? []).length ? `<section class="card"><h2>누가 몇 건 맡았나</h2><ul class="people">${o.assigned.map((a) =>
      `<li>${a.id === S.me.id ? "나" : esc(a.name)} ${a.n}</li>`).join("")}</ul></section>` : "";
    return `<div class="hello"><h2>${esc(hi)}, ${esc(S.me.name)}님</h2>
        <p>${o.doneToday ? `오늘 ${o.doneToday}건 처리했어요. ` : ""}${S.me.owner ? "최고 관리자로 로그인했어요." : `내 권한: ${esc(L.permSummary(S.me.perms, S.me.areas ?? {}, toggles()))}`}</p></div>
      ${S.prefs.views.length ? `<nav class="chips" aria-label="저장한 보기">${S.prefs.views.filter((v) => String(v.hash).startsWith("#/")).map((v) => `<a class="chip pick" href="${esc(v.hash)}">${esc(v.name)}</a>`).join("")}</nav>` : ""}
      <section class="card todo-card"><div class="card-head"><h2>지금 할 일</h2>${S.queue ? "" : can("inquiries", "edit") && o.inquiries ? '<button class="btn small primary" data-act="queue-start">큐 모드로 답하기</button>' : ""}</div>
        ${queue.length ? `<ul class="queue-list">${queue.join("")}</ul>` : ""}
        ${todo.length ? `<ul class="todo">${todo.join("")}</ul>` : queue.length ? "" : '<p class="hint">기다리는 일이 없어요. 새로 오면 여기와 메뉴에 숫자로 보여요.</p>'}</section>
      <div class="tiles">${tiles.join("")}</div>
      ${stats.length ? `<p class="stats">${stats.map(([k, v]) => `<span>${esc(k)}<b>${v ?? 0}</b></span>`).join("")}</p>` : ""}
      <div class="cols">${people}
        ${can("audit") ? `<section class="card"><div class="card-head"><h2>최근 활동</h2><a class="btn small ghost" href="${href("audit")}">모두 보기</a></div>
          ${feed === null ? '<div class="card skeleton short"></div>' : feed.length ? `<ul class="feed">${feed.map(feedItem).join("")}</ul>` : '<p class="hint">아직 활동이 없어요.</p>'}</section>` : ""}
      </div>`;
  }

  function feedItem(e) {
    const about = e.label ? (e.link ? ` <a class="meta" href="${esc(e.link)}">${esc(e.label)}</a>` : ` <span class="meta">${esc(e.label)}</span>`)
      : e.target ? ` <span class="meta">${esc(e.target)}</span>` : "";
    return `<li><span class="when">${esc(ago(e.at))}</span><span>${esc(ACTION_NAMES[e.action] ?? e.action)}${about}</span>
      <span class="who">${esc(e.actor || "앱 사용자")}</span></li>`;
  }

  function permPills(perms, areas) {
    const on = Object.entries(perms ?? {}).filter(([, l]) => l !== "none");
    if (!on.length) return '<span class="pill">권한 없음</span>';
    return on.map(([a, l]) => `<span class="pill ${l === "edit" ? "info" : ""}">${esc(areas[a] ?? a)}${toggles().includes(a) ? "" : ` ${LEVEL_NAMES[l]}`}</span>`).join("");
  }

  // ---- review ----

  function reviewView() {
    const q = S.data.queue;
    const points = S.overview?.settings?.adoptPoints ?? 3;
    const editable = can("review", "edit");
    const counts = q ? { all: q.groups.length + q.asks.length + q.flagged.length, groups: q.groups.length, asks: q.asks.length, flagged: q.flagged.length } : {};
    const head = `<div class="segmented" role="group" aria-label="보기">
        ${seg("review-filter", "all", S.reviewFilter, "전체", counts.all)}${seg("review-filter", "groups", S.reviewFilter, "제보", counts.groups)}
        ${seg("review-filter", "asks", S.reviewFilter, "앱에 없는 방식", counts.asks)}${seg("review-filter", "flagged", S.reviewFilter, "달라요 달린 규칙", counts.flagged)}</div>
      <p class="hint">같은 규칙을 서로 다른 ${S.overview?.settings?.adoptPersons ?? 3}명 이상이 보내 ${points}점이 되고, 투표한 분의 ${S.overview?.settings?.adoptAgree ?? 75}% 이상이 맞았어요라고 하면 자동으로 채택돼요. 명세서로 맞춰 본 제보는 2점이에요.${editable ? "" : " 보기 권한이라 채택·거절은 할 수 없어요."}</p>`;
    if (!q) return head + skeleton();
    const f = S.reviewFilter;
    const parts = [];
    if ((f === "all" || f === "groups") && q.groups.length) {
      parts.push((f === "all" ? `<h2 class="section">채택을 기다리는 제보<span class="count">${q.groups.length}</span></h2>` : "") +
        q.groups.map((g) => groupCard(g, !g.changes && q.groups.length <= 2, editable)).join(""));
    }
    if ((f === "all" || f === "asks") && q.asks.length) {
      parts.push(`<h2 class="section">앱에 없는 방식<span class="count">${q.asks.length}</span></h2><p class="hint">앱으로 설정하지 못한 방식이에요. 답을 고르면 보낸 분 앱에 보여요.</p>` +
        q.asks.map((a) => askCard(a, editable)).join(""));
    }
    if ((f === "all" || f === "flagged") && q.flagged.length) {
      parts.push(`<h2 class="section">달라요가 있는 규칙<span class="count">${q.flagged.length}</span></h2>${q.flagged.map((x) => flagCard(x, editable)).join("")}`);
    }
    if (!parts.length) {
      return head + empty("검토할 것이 없어요", "새 제보가 오면 여기와 메뉴에 숫자로 보여요.",
        can("community") && S.overview?.flaggedPosts ? `<a class="btn" href="${L.routeHash("community", null, {})}">신고된 글 ${S.overview.flaggedPosts}개 보기</a>` : "");
    }
    return head + `<div class="grid">${parts.join("")}</div>`;
  }

  function askCard(a, editable) {
    return `<article class="card">
      <div class="head"><h3>${esc(a.company.name)}${a.dept ? ` <span class="dept">${esc(a.dept)}</span>` : ""}</h3><span class="pill warn">앱에 없는 방식</span></div>
      <p class="meta">${a.verified ? "명세서 확인 · " : ""}${moment(a.at)}</p>
      <blockquote>${esc(a.note)}</blockquote>
      <details><summary>보낸 분이 맞춰 둔 설정</summary>${lines(a.lines)}</details>
      ${editable ? `<div class="actions"><button class="btn primary" data-act="answer" data-id="${a.id}">답하기</button></div>` : ""}
    </article>`;
  }

  function groupCard(g, open, editable) {
    const verified = g.reports.filter((r) => r.verified).length;
    const kind = g.blocked && g.replaces ? '<span class="pill warn">새 버전 후보</span>'
      : g.replaces ? `<span class="pill">지금 v${g.replaces.version}와 달라요</span>` : '<span class="pill info">새 규칙</span>';
    const gist = g.changes ? "" : g.lines.find(([label]) => label === "근무")?.[1];
    // [ADMUX-05] Opened or closed by the admin stays so across refreshes and screens (this visit).
    const key = `g:${g.company.id}.${g.setup}`;
    const siblings = (S.data.queue?.groups ?? []).filter((x) => x.company.id === g.company.id && x !== g && (x.dept ?? "") !== (g.dept ?? "")).length;
    return `<article class="card">
      <div class="head"><h3>${esc(g.company.name)}${g.dept ? ` <span class="dept">${esc(g.dept)}</span>` : ""}</h3>${kind}</div>
      <p class="meta">${g.persons}명 · 제보 ${g.reports.length}건${verified ? ` · 명세서 확인 ${verified}건` : ""} · ${day(g.reports[0].at)}</p>
      ${gist ? `<p class="gist">${esc(gist)}</p>` : ""}
      <div class="score"><meter min="0" max="${g.threshold}" value="${Math.min(g.points, g.threshold)}" aria-label="채택 점수"></meter><b>${g.points}/${g.threshold}점</b></div>
      <p class="meta">신뢰도 ${g.trust ?? 0} · 맞았어요 ${g.agree ?? 0}%${g.posts ? ` · 토론 ${g.posts}개` : ""}</p>
      ${g.company.hidden ? '<p class="note warn">숨긴 회사예요. 채택해도 회사 찾기에 나오지 않아요.</p>' : ""}
      ${g.turnedDown ? `<p class="note warn">${g.turnedDown === "withdrawn" ? "전에 내린 규칙과 같은 설정이에요" : "전에 거절한 설정이에요"}. 자동으로 채택되지 않아요.</p>`
        : g.persons >= 2 && g.networks < 2 ? '<p class="note warn">모두 1시간 안에 같은 네트워크에서 보냈어요. 한 분이 여러 번 보냈을 수도 있어서 자동으로 채택하지 않았어요.</p>'
        : g.doubted && g.bad ? `<p class="note warn">달라요가 많아 자동으로 채택하지 않아요. 토론을 읽고 정해 주세요.</p>` : ""}
      ${g.changes ? changes(g.changes) : ""}
      ${siblings ? `<p class="meta">같은 회사 다른 부서 제보 ${siblings}건도 기다려요</p>` : ""}
      <details data-key="${key}"${S.opened.get(key) ?? open ? " open" : ""}><summary>보낸 설정</summary>${lines(g.lines)}</details>
      ${g.reports.filter((r) => r.note).map((r) => `<blockquote>${esc(r.note)}<cite>${r.verified ? "명세서 확인 · " : ""}${moment(r.at)}</cite></blockquote>`).join("")}
      ${g.bad ? `<p class="note bad">달라요 ${g.bad}명</p>` : ""}
      <div class="actions">
        ${can("community") && g.posts ? `<a class="btn ghost" href="${href("community", `${g.company.id}.${g.setup}`)}">토론 ${g.posts}개 읽기</a>` : ""}
        ${can("companies") ? `<a class="btn ghost" href="${href("companies", g.company.id)}">회사 열기</a>` : ""}
        ${editable ? `<button class="btn" data-act="reject" data-company="${g.company.id}" data-setup="${g.setup}">거절</button>
        <button class="btn primary" data-act="adopt" data-company="${g.company.id}" data-setup="${g.setup}">채택</button>` : ""}
      </div>
    </article>`;
  }

  function changes(c) {
    const items = [...c.removed.map((l) => ["del", l]), ...c.added.map((l) => ["add", l])];
    if (!items.length) return '<p class="note">지금 쓰는 규칙과 내용이 같아요.</p>';
    return `<div class="diff"><p class="diff-title">지금 쓰는 규칙과 다른 점</p><ul>${items.map(([k, [label, text]]) =>
      `<li class="${k}"><span class="sign" aria-label="${k === "add" ? "새 값" : "지금 값"}">${k === "add" ? "+" : "−"}</span><span><b>${esc(label)}</b> ${esc(text)}</span></li>`).join("")}</ul></div>`;
  }

  function flagCard(f, editable) {
    return `<article class="card">
      <div class="head"><h3>${esc(f.company.name)}${f.dept ? ` <span class="dept">${esc(f.dept)}</span>` : ""} <span class="ver">v${f.version}</span></h3>
        <span class="pill bad">달라요 ${f.bad}</span></div>
      <p class="meta">맞았어요 ${f.good}명 · 달라요 ${f.bad}명${f.names ? ` · 이름이 이상해요 ${f.names}` : ""}${f.old ? ` · 예전 정보 ${f.old}` : ""}</p>
      <details><summary>규칙 내용</summary>${lines(f.lines)}</details>
      <div class="actions">${can("companies") ? `<a class="btn ghost" href="${href("companies", f.company.id)}">회사 열기</a>` : ""}
        ${can("takedown", "edit") ? `<button class="btn danger" data-act="withdraw-rule" data-id="${f.rule}">규칙 내리기</button>` : ""}</div>
    </article>`;
  }

  // ---- the talk on rule sets [COMM-06] ----

  function communityView() {
    if (S.postFilter === "rates" && !S.route.id) return ratesView();
    const d = S.data.posts;
    const edit = can("community", "edit");
    const talk = Boolean(S.route.id);
    const head = talk ? `<p class="hint">이 규칙에 남긴 글 전부예요. 가려진 글도 보여요.${edit ? "" : " 보기 권한이라 가리거나 지울 수 없어요."}</p>`
      : `<div class="segmented" role="group" aria-label="보기">${seg("post-filter", "flagged", S.postFilter, "신고됨", S.overview?.flaggedPosts)}
        ${seg("post-filter", "hidden", S.postFilter, "가려짐")}${seg("post-filter", "all", S.postFilter, "모든 글")}${seg("post-filter", "rates", S.postFilter, "임금표")}</div>
        <div class="toolbar"><input id="search" type="search" placeholder="회사 이름으로 찾기" value="${esc(S.postQuery)}" autocomplete="off" enterkeyhint="search" aria-label="토론 찾기"></div>
        <p class="hint">앱 사용자가 회사 규칙마다 남긴 글이에요. 서로 다른 세 분이 신고하면 개발자가 볼 때까지 가려져요. 다시 보이게 한 글은 신고로 가려지지 않아요.${edit ? "" : " 보기 권한이라 가리거나 지울 수 없어요."}</p>`;
    if (!d) return head + skeleton();
    if (!d.posts.length) {
      return head + empty(S.postFilter === "flagged" && !talk ? "신고된 글이 없어요" : "글이 없어요", "신고가 오면 여기와 메뉴에 숫자로 보여요.");
    }
    return head + `<div class="grid">${d.posts.map((p) => postCard(p, edit, talk)).join("")}</div>${moreButton(d.next, "posts-more")}`;
  }

  /**
   * [PLZ-14] 임금표: market rates people sent. 확인 필요 is one far from the adopted ones that enough people
   * agreed to; an admin adopts it or hides it. Taking an adopted one down needs 광장 규칙 내리기.
   */
  function ratesView() {
    const d = S.data.rates;
    const edit = can("community", "edit");
    const n = d?.counts ?? {};
    const head = `<div class="segmented" role="group" aria-label="보기">${seg("post-filter", "flagged", S.postFilter, "신고됨", S.overview?.flaggedPosts)}
        ${seg("post-filter", "hidden", S.postFilter, "가려짐")}${seg("post-filter", "all", S.postFilter, "모든 글")}${seg("post-filter", "rates", S.postFilter, "임금표")}</div>
      <div class="chips" role="group" aria-label="임금표 상태">${[["review", "확인 필요"], ["pending", "투표 중"], ["adopted", "채택"], ["hidden", "가려짐"]]
        .map(([k, label]) => `<button type="button" class="chip pick" data-act="rate-filter" data-key="${k}" aria-pressed="${S.rateFilter === k}">${label}${n[k] ? ` ${n[k]}` : ""}</button>`).join("")}</div>
      <div class="toolbar"><input id="search" type="search" placeholder="직종으로 찾기" value="${esc(S.postQuery)}" autocomplete="off" enterkeyhint="search" aria-label="임금표 찾기"></div>
      <p class="hint">직군·지역별 일당·시급·월급 시세예요. 서로 다른 분들이 맞아요라고 하면 채택되고, 이미 채택된 시세와 두 배 넘게 차이 나면 여기서 확인을 기다려요.</p>`;
    if (!d) return head + skeleton();
    if (!d.rates.length) return head + empty("시세가 없어요", "앱에서 임금표에 시세를 올리면 여기에 보여요.");
    const won = (v) => `${Number(v).toLocaleString("ko-KR")}원`;
    return head + `<div class="grid">${d.rates.map((r) => `<article class="card">
      <div class="head"><h3>${esc(r.trade)} <span class="dept">${esc(r.unitName)} ${esc(won(r.amount))}</span></h3><span class="pill">${esc(r.year)}년</span></div>
      <p class="meta">${esc(r.jobName)} · ${esc(r.regionName)} · 맞아요 ${r.agree} · 달라요 ${r.disagree} · 글쓴이 ${esc(r.writer)} · ${esc(ago(r.at))}</p>
      ${r.note ? `<blockquote>${esc(r.note)}</blockquote>` : ""}
      ${edit ? `<div class="actions">
        ${r.status === "adopted" ? "" : `<button class="btn primary" data-act="rate-set" data-id="${r.id}" data-key="adopted">채택</button>`}
        ${r.status === "adopted" && !can("takedown", "edit") ? "" : `${r.status === "hidden" ? "" : `<button class="btn danger" data-act="rate-set" data-id="${r.id}" data-key="hidden">가리기</button>`}
          ${r.status === "pending" ? "" : `<button class="btn" data-act="rate-set" data-id="${r.id}" data-key="pending">투표로 되돌리기</button>`}`}
      </div>` : ""}
    </article>`).join("")}</div>${moreButton(d.next, "rates-more")}`;
  }

  function postCard(p, edit, talk) {
    const state = p.status === "hidden" ? `<span class="pill bad">가려짐</span>` : p.flags ? `<span class="pill warn">신고 ${p.flags}</span>`
      : p.kept ? '<span class="pill ok">확인함</span>' : "";
    return `<article class="card">
      <div class="head"><h3>${esc(p.company.name)}${p.dept ? ` <span class="dept">${esc(p.dept)}</span>` : ""}</h3>${state}</div>
      <p class="meta">글쓴이 ${esc(p.writer)} · ${esc(ago(p.at))}${p.mutedUntil ? ` · ${esc(day(p.mutedUntil))}까지 쓰기 막힘` : ""}</p>
      <blockquote>${esc(p.body)}</blockquote>
      ${p.reasons.length ? `<p class="meta">신고 이유: ${p.reasons.map((r) => `${esc(r.text)} ${r.n}`).join(", ")}</p>` : ""}
      ${p.hidden ? `<p class="note warn">${esc(p.hidden.text)}</p>` : ""}
      <div class="actions">
        ${talk ? "" : `<a class="btn ghost" href="${href("community", `${p.company.id}.${p.setup}`)}">이 토론 전부</a>`}
        ${edit ? `${p.status === "hidden" || p.flags ? `<button class="btn" data-act="post-show" data-id="${p.id}">${p.status === "hidden" ? "다시 보이기" : "문제없음"}</button>` : ""}
          ${p.status === "hidden" ? "" : `<button class="btn" data-act="post-hide" data-id="${p.id}">가리기</button>`}
          <button class="btn" data-act="post-mute" data-id="${p.id}">30일 쓰기 막기</button>
          <button class="btn danger" data-act="post-delete" data-id="${p.id}">지우기</button>` : ""}
      </div>
    </article>`;
  }

  function openHidePost(id, mute) {
    const reasons = Object.entries(S.overview?.hideReasons ?? {}).filter(([key]) => key !== "flags");
    openDialog(`<form data-form="hide-post" data-id="${id}" data-mute="${mute ? 1 : 0}"><h2>${mute ? "30일 동안 쓰기를 막을까요?" : "이 글을 가릴까요?"}</h2>
      <p>${mute ? "이 글은 가려지고, 글쓴이는 30일 동안 어느 토론에도 글을 쓸 수 없어요. " : ""}글쓴이 앱에 이 이유가 보여요.</p>
      <div class="radios">${reasons.map(([key, text], i) => `<label class="radio"><input type="radio" name="reason" value="${key}"${i === 0 ? " checked" : ""}>
        <span>${esc(text)}</span></label>`).join("")}</div>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn danger" type="submit">${mute ? "막기" : "가리기"}</button></div></form>`);
  }

  async function moderate(id, body, done, undo) {
    await act(() => api(`/posts/${id}`, "POST", body), done, undo);
    load();
  }

  // ---- inquiries ----

  /** [ADMIN-02] The server sorts each question by topic; a chip shows one topic, with how many there are. */
  function topicChips(d) {
    const topics = d?.topics ?? {};
    const names = d?.topicNames ?? {};
    const keys = Object.keys(names).filter((k) => topics[k] || k === S.inquiryTopic);
    if (!keys.length) return "";
    const chip = (key, label, n) => `<button type="button" class="chip pick" data-act="inquiry-topic" data-key="${key}" aria-pressed="${S.inquiryTopic === key}">${esc(label)}${n ? ` ${n}` : ""}</button>`;
    return `<div class="chips" role="group" aria-label="주제">${chip("", "전체")}${keys.map((k) => chip(k, names[k], topics[k])).join("")}</div>`;
  }

  /** [ADMIN-02] 모두의 문의: whether others see it, and what they said: 나도 그래요 and 신고. */
  function publicPills(q) {
    if (!q.public) return "";
    return (q.hidden ? '<span class="pill bad">숨김</span>' : '<span class="pill info">공개</span>') +
      (q.same ? `<span class="pill">나도 ${q.same}</span>` : "") + (q.flags ? `<span class="pill warn">신고 ${q.flags}</span>` : "");
  }

  const SORTS = [["default", "기본(급한 것 먼저)"], ["sla", "답 목표가 가까운 순"], ["oldest", "오래 기다린 순"], ["newest", "최근 순"], ["same", "나도 그래요 많은 순"]];

  /** One question in a list; [compact] for the list beside an open question. */
  function inquiryRow(q, { compact = false, picking = false } = {}) {
    const current = S.route.id === q.id;
    const inner = `<span class="row-main"><b>${q.urgency === "urgent" ? '<span class="pill bad">급함</span> ' : ""}${esc(KIND_NAMES[q.kind] ?? "문의")}${q.topicName ? ` · ${esc(q.topicName)}` : ""}${!compact && q.appVersion ? ` <span class="meta">앱 ${esc(q.appVersion)}</span>` : ""}</b>
        <span class="preview">${esc(q.preview)}</span>
        ${q.typing?.length ? `<span class="typing">${esc(q.typing.join(", "))}님이 답 쓰는 중</span>` : ""}</span>
      <span class="row-side"><span>${esc(ago(q.updatedAt ?? q.createdAt))}</span>${slaPill(q.slaLeft)}
        ${compact ? "" : `${q.count > 1 ? `<span class="pill">${q.count}</span>` : ""}${publicPills(q)}
        ${q.rating ? `<span class="pill ${q.rating > 0 ? "ok" : "bad"}">${q.rating > 0 ? "도움 됐어요" : "아쉬워요"}</span>` : ""}
        ${q.dev ? '<span class="pill ok">개발사항</span>' : ""}${q.notes ? `<span class="pill gold">메모 ${q.notes}</span>` : ""}`}
        ${q.assignee ? `<span class="pill${q.assignee.id === S.me.id ? " info" : ""}">${q.assignee.id === S.me.id ? "내가 맡음" : esc(q.assignee.name)}</span>` : ""}</span>`;
    if (picking) {
      const on = S.inquiryPick.has(q.id);
      return `<li><label class="row${on ? " picked" : ""}"><input class="check" type="checkbox" data-pick-inquiry="${q.id}"${on ? " checked" : ""} aria-label="고르기">${inner}</label></li>`;
    }
    return `<li><a class="row${q.waiting ? " unread" : ""}" href="${href("inquiries", q.id)}"${current ? ' aria-current="true"' : ""} data-row="${q.id}">${inner}${icon(P.chevron)}</a></li>`;
  }

  function inquiriesView() {
    if (S.data.inquiriesMissing) {
      return empty("문의함이 아직 서버에 없어요", "앱 안 문의가 켜진 서버로 업데이트하면 여기서 바로 답할 수 있어요.");
    }
    const d = S.data.inquiries;
    const counts = d?.counts ?? {};
    const edit = can("inquiries", "edit");
    const picking = Boolean(S.inquiryPick) && edit;
    const head = `<div class="segmented" role="group" aria-label="문의 상태">
      ${seg("inquiry-status", "open", S.inquiryStatus, "답 대기", counts.open)}${seg("inquiry-status", "answered", S.inquiryStatus, "답함", counts.answered)}
      ${seg("inquiry-status", "closed", S.inquiryStatus, "닫힘", counts.closed)}</div>
      <div class="toolbar"><input id="search" type="search" placeholder="문의와 답에서 찾기" value="${esc(S.inquiryQuery)}" autocomplete="off" enterkeyhint="search" aria-label="문의 찾기">
        <label class="sr" for="inq-sort">정렬</label><select id="inq-sort" data-change="inquiry-sort">${SORTS.map(([k, label]) => `<option value="${k}"${S.inquirySort === k ? " selected" : ""}>${label}</option>`).join("")}</select>
        ${edit ? `<button class="btn" data-act="inquiry-pick" aria-pressed="${picking}">${picking ? "선택 마침" : `${icon(P.check)}여러 개 고르기`}</button>` : ""}
        ${picking ? "" : csvButton("inquiries", S.data.inquiries?.inquiries)}
        ${edit && S.inquiryStatus === "open" ? '<button class="btn primary" data-act="queue-start">큐 모드</button>' : ""}</div>
      <div class="chips" role="group" aria-label="맡은 사람">${[["", "모두"], ["mine", "내가 맡은 것"], ["free", "아무도 안 맡은 것"], ["late", "놓친 것(하루 넘음)"]].map(([key, label]) =>
        `<button type="button" class="chip pick" data-act="inquiry-who" data-key="${key}" aria-pressed="${S.inquiryWho === key}">${label}</button>`).join("")}</div>${topicChips(d)}`;
    if (!d) return head + skeleton();
    if (!d.inquiries.length) {
      return head + empty(S.inquiryQuery ? "찾는 문의가 없어요" : S.inquiryWho === "late" ? "하루 넘게 기다리는 문의가 없어요. 잘하고 있어요" : S.inquiryWho === "mine" ? "내가 맡은 문의가 없어요" : S.inquiryStatus === "open" ? "답을 기다리는 문의가 없어요" : "목록이 비어 있어요",
        S.inquiryQuery ? "다른 말로 찾아보세요. 보낸 분과 관리자가 쓴 글을 모두 찾아요." : S.inquiryWho === "mine" ? "문의를 열어 내가 맡기를 누르거나 답하면 여기에 모여요." : "앱에서 문의가 오면 여기와 메뉴에 숫자로 보여요.",
        S.inquiryQuery || S.inquiryWho || S.inquiryTopic ? '<button class="btn" data-act="inquiry-clear">조건 지우기</button>'
          : can("inquiries", "edit") && S.inquiryStatus === "open" ? '<button class="btn" data-act="templates">한가할 때 자주 쓰는 답 정리하기</button>' : "");
    }
    const n = picking ? S.inquiryPick.size : 0;
    const bar = picking ? `<div class="bulkbar" role="toolbar" aria-label="고른 문의"><b>${n}개 골랐어요</b>
        <button class="btn small ghost" data-act="inquiry-pick-all">${n === d.inquiries.length ? "모두 풀기" : "보이는 것 모두 고르기"}</button>
        ${[["assign", "내가 맡기"], ["unassign", "맡기 취소"], ["reply", "같은 답 보내기"], ["close", "닫기"], ["open", "다시 열기"]].map(([k, label]) =>
          `<button class="btn small${k === "reply" ? " primary" : ""}" data-act="inquiry-bulk" data-key="${k}"${n ? "" : " disabled"}>${label}</button>`).join("")}
        ${can("dev", "edit") ? `<button class="btn small" data-act="inquiry-bulk" data-key="link"${n ? "" : " disabled"}>개발사항에 연결</button>` : ""}</div>` : "";
    return head + `<p class="meta">${d.total ?? d.inquiries.length}건${d.sla ? ` · 답 목표: 문제 ${d.sla.slaBug}시간, 질문 ${d.sla.slaQuestion}시간, 제안 ${d.sla.slaIdea}시간` : ""}</p>
      <ul class="rows">${d.inquiries.map((q) => inquiryRow(q, { picking })).join("")}</ul>
      ${moreButton(d.next, "inquiries-more", d.total, d.inquiries.length)}${bar}`;
  }

  /** [ADMIN-16] Where this question is in the list the admin came from: the ones before and after it. */
  function neighbours() {
    const list = S.data.inquiries?.inquiries ?? [];
    const i = list.findIndex((x) => x.id === S.route.id);
    return i < 0 ? { prev: null, next: null, at: -1, of: list.length } : { prev: list[i - 1]?.id ?? null, next: list[i + 1]?.id ?? null, at: i, of: list.length };
  }

  /** [ADMIN-17] What happened to a question, oldest first: the messages, admins' steps and notes together. */
  function timeline(q) {
    const items = [
      ...q.messages.map((m) => ({ at: m.at, cls: m.from === "user" ? "user" : "admin", text: m.from === "user" ? "보낸 분이 글을 남겼어요" : `${m.by && m.by !== "admin" ? m.by : "개발자"}님이 답했어요` })),
      ...(q.events ?? []).filter((e) => e.action !== "inquiry_reply").map((e) => ({ at: e.at, cls: "", text: eventText(e) })),
      ...(q.notes ?? []).map((n) => ({ at: n.at, cls: "note", text: `${n.by}님이 메모를 남겼어요` })),
    ].sort((a, b) => String(a.at).localeCompare(String(b.at)));
    return `<ul class="timeline">${items.map((x) => `<li class="${x.cls}">${esc(x.text)}<span class="when">${esc(moment(x.at))}</span></li>`).join("")}</ul>`;
  }

  function eventText(e) {
    const by = e.by || "관리자";
    if (e.action === "inquiry_assign") return e.detail ? `${e.detail}님이 맡았어요` : `${by}님이 맡기를 취소했어요`;
    if (e.action === "inquiry_status") return `${by}님이 ${e.detail === "closed" ? "닫았어요" : e.detail === "open" ? "다시 열었어요" : `상태를 ${STATUS_NAMES[e.detail] ?? e.detail}(으)로 바꿨어요`}`;
    if (e.action === "inquiry_hide") return `${by}님이 모두의 문의에서 숨겼어요`;
    if (e.action === "inquiry_show") return `${by}님이 다시 보이게 했어요`;
    if (e.action === "dev_link") return `${by}님이 개발사항 "${e.detail}"에 연결했어요`;
    return `${by} · ${ACTION_NAMES[e.action] ?? e.action}`;
  }

  function inquiryView() {
    const q = S.data.inquiry;
    const detail = !q || q.id !== S.route.id ? skeleton(2) : inquiryDetail(q);
    // [ADMIN-16] On a wide screen the list stays beside the open question.
    const list = S.data.inquiries?.inquiries;
    if (!wide() || !list?.length) return detail;
    return `<div class="split"><nav class="pane-list card" aria-label="문의 목록"><ul class="rows">${list.map((x) => inquiryRow(x, { compact: true })).join("")}</ul>
      ${moreButton(S.data.inquiries.next, "inquiries-more", S.data.inquiries.total, list.length)}</nav><div class="pane-main">${detail}</div></div>`;
  }

  function inquiryDetail(q) {
    const editable = can("inquiries", "edit");
    const ctx = Object.entries(q.context ?? q.info ?? {}).filter(([, v]) => v !== null && v !== undefined && v !== "");
    const thread = q.messages.map((m) => {
      const mine = m.from !== "user";
      return `<div class="bubble ${mine ? "admin" : "user"}">${esc(m.text)}</div>
        <span class="bubble-meta ${mine ? "admin" : ""}">${mine ? esc(m.by && m.by !== "admin" ? m.by : "개발자") : "보낸 분"} · ${esc(moment(m.at))}</span>`;
    }).join("");
    const status = { open: ["warn", "답 대기"], answered: ["ok", "답함"], closed: ["", "닫힘"] }[q.status] ?? ["", q.status];
    // [INQ-02] What the asker's app shows as the stage, so the admin answers with it in mind.
    const stage = PROGRESS_NAMES[q.progress?.stage] ?? "";
    const nb = neighbours();
    const step = nb.at >= 0 ? `<div class="stepnav"><a class="btn small ghost${nb.prev ? "" : " disabled"}" ${nb.prev ? `href="${href("inquiries", nb.prev)}"` : 'aria-disabled="true"'} title="이전 ([)">‹ 이전</a>
      <span class="meta">${nb.at + 1}/${nb.of}</span><a class="btn small ghost${nb.next ? "" : " disabled"}" ${nb.next ? `href="${href("inquiries", nb.next)}"` : 'aria-disabled="true"'} title="다음 (])">다음 ›</a></div>` : "";
    const list = templateList();
    const queueBar = S.queue ? `<div class="queue-bar" role="status"><b>큐 모드 · ${S.queue.at + 1}/${S.queue.ids.length}</b>
      <span>Ctrl+Enter 보내고 다음 · s 건너뛰기 · e 닫고 다음</span><button class="btn small" data-act="queue-skip">건너뛰기</button><button class="btn small ghost" data-act="queue-stop">끝내기</button></div>` : "";
    return `${queueBar}${step}<div class="cols">
      <section>
        <div class="card"><div class="card-head"><h2>${q.urgency === "urgent" ? '<span class="pill bad">급함</span> ' : ""}${esc(KIND_NAMES[q.kind] ?? "문의")}${q.topicName ? ` · ${esc(q.topicName)}` : ""}</h2><span class="pill ${status[0]}">${status[1]}</span></div>
          <p class="meta">보낸 분 앱에는 <b>${esc(stage)}</b>${q.progress?.version ? ` (${esc(q.progress.version)})` : ""}로 보여요. ${waitPill(q)}${slaPill(q.slaLeft)}${q.rating ? ` <span class="pill ${q.rating > 0 ? "ok" : "bad"}">${q.rating > 0 ? "답이 도움 됐대요" : "답이 아쉬웠대요"}</span>` : ""}</p>
          <div class="thread">${thread}</div>
          ${q.photos ? `<div class="photos">${Array.from({ length: q.photos }, (_, i) =>
            `<a href="/api/admin/inquiries/${esc(q.id)}/photos/${i + 1}" target="_blank" rel="noopener"><img src="/api/admin/inquiries/${esc(q.id)}/photos/${i + 1}" alt="보낸 분이 붙인 사진 ${i + 1}" loading="lazy"></a>`).join("")}</div>
            <p class="meta">사진은 위치 정보를 지운 채로 받았어요. 문의를 닫고 90일 뒤에 지워져요.</p>` : ""}</div>
        ${q.typing?.length ? `<p class="typing" role="status">${esc(q.typing.join(", "))}님이 지금 답을 쓰고 있어요. 겹치지 않게 확인해 주세요.</p>` : ""}
        ${editable && q.status !== "closed" ? `<form class="composer" data-form="reply">
          <label class="sr" for="reply">답</label>
          <textarea id="reply" name="text" maxlength="2000" placeholder="${q.public ? "보낸 분 앱과 모두의 문의에" : "보낸 분 앱에"} 보일 답을 적어요. 이름, 연락처, 금액은 묻지 않아요." required></textarea>
          <input type="hidden" name="template" value="">
          <div class="chips" role="group" aria-label="자주 쓰는 답">${list.map((t, i) => `<button type="button" class="chip pick" data-act="template" data-i="${i}" title="${esc(t.text.slice(0, 120))}">${i < 9 ? `<span class="kbd">${i + 1}</span> ` : ""}${esc(t.label)}</button>`).join("")}
            <button type="button" class="chip pick" data-act="templates">${icon(P.plus)}답 관리</button></div>
          <p class="saved" id="reply-saved" aria-live="polite"></p>
          <div class="actions"><span class="count" id="reply-count">0/2000</span>
            <button class="btn" type="submit" data-close="1">답하고 닫기</button><button class="btn primary" type="submit">답하기</button></div>
        </form>` : ""}
      </section>
      <aside>
        ${editable ? `<section class="card"><h2>상태</h2>
          <p class="meta">${q.assignee ? (q.assignee.id === S.me.id ? "내가 맡았어요." : `${esc(q.assignee.name)}님이 맡았어요.`) : "아직 아무도 맡지 않았어요. 답하면 내가 맡아요."}</p>
          <div class="actions start">${q.assignee?.id === S.me.id ? '<button class="btn" data-act="inquiry-assign" data-to="">맡기 취소</button>'
            : `<button class="btn" data-act="inquiry-assign" data-to="me">${q.assignee ? "내가 넘겨받기" : "내가 맡기"}</button>`}
            ${q.status === "closed" ? '<button class="btn" data-act="inquiry-state" data-status="open">다시 열기</button>'
            : '<button class="btn" data-act="inquiry-state" data-status="closed">닫기</button>'}</div></section>` : ""}
        <section class="card"><h2>함께 온 정보</h2><p class="hint">보낸 분이 보내기 전에 보고 동의한 정보예요.</p>
          ${ctx.length ? `<dl class="kv">${ctx.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(typeof v === "object" ? JSON.stringify(v) : v)}</dd>`).join("")}</dl>` : '<p class="meta">없어요</p>'}</section>
        ${q.public ? `<section class="card"><h2>모두의 문의</h2>
          <p class="hint">${q.hidden ? `숨겼어요: ${esc(q.hidden.text)}. 보낸 분 앱에는 이유가 보여요.` : "앱을 쓰는 모두가 이 문의와 답을 봐요. 함께 온 정보는 개발자만 봐요."}</p>
          <div class="chips">${publicPills(q)}${(q.reasons ?? []).map((r) => `<span class="chip">${esc(r.text)} ${r.n}</span>`).join("")}</div>
          ${editable ? `<div class="actions start">${q.hidden ? '<button class="btn" data-act="inquiry-show">다시 보이기</button>'
            : `${[["abuse", "욕설·비방"], ["personal", "개인 정보"], ["spam", "광고·도배"]].map(([code, label]) =>
              `<button class="btn" data-act="inquiry-hide" data-reason="${code}">숨기기: ${label}</button>`).join("")}${q.flags && !q.kept ? '<button class="btn" data-act="inquiry-show">괜찮아요</button>' : ""}`}</div>` : ""}</section>` : ""}
        ${q.dev ? devCard(q) : ""}
        <section class="card"><h2>관리자 메모</h2><p class="hint">관리자끼리만 봐요. 보낸 분 앱에는 보이지 않아요.</p>
          ${q.notes?.length ? `<ul class="notes">${q.notes.map((n) => `<li><cite>${esc(n.by)} · ${esc(ago(n.at))}</cite>${esc(n.text)}</li>`).join("")}</ul>` : ""}
          ${editable ? `<form class="mini" data-form="inquiry-note"><label class="sr" for="inq-note">메모</label>
            <textarea id="inq-note" name="text" maxlength="1000" placeholder="예: 다음 버전에서 고칠 예정, 비슷한 문의 많음" required></textarea>
            <div class="actions"><button class="btn small" type="submit">메모 남기기</button></div></form>` : ""}</section>
        <details class="card"><summary>기록</summary>${timeline(q)}</details>
      </aside></div>`;
  }

  /**
   * [ADMIN-11] On a question: the 개발사항 it is in, and one tap to add it to one (new, or one that exists).
   * Shown to whoever may see 개발사항; adding takes changing them.
   */
  function devCard(q) {
    const add = can("dev", "edit");
    return `<section class="card"><div class="card-head"><h2>개발사항</h2>${q.dev.length ? `<span class="pill ok">${q.dev.length}</span>` : ""}</div>
      ${q.dev.length ? `<ul class="rows">${q.dev.map((d) => `<li><a class="row" href="${href("dev", d.id)}"><span class="row-main"><b>${esc(d.title)}</b>
        <small>문의 ${d.links}건</small></span><span class="pill ${DEV_TONES[d.status]}">${DEV_NAMES[d.status]}</span>${icon(P.chevron)}</a></li>`).join("")}</ul>`
        : '<p class="hint">개발이 필요한 문의면 개발사항에 올려 두세요. 누가 올렸는지 남고, 같은 요청이 몇 건인지 모여요.</p>'}
      ${add ? `<div class="actions start"><button class="btn${q.dev.length ? "" : " primary"}" data-act="dev-from-inquiry">${icon(P.plus)}개발사항에 추가</button></div>` : ""}</section>`;
  }

  const firstText = (q) => q.messages.find((m) => m.from === "user")?.text ?? "";

  /**
   * [ADMIN-11] The 개발사항 stays as an improvement record after the question goes (privacy policy 2.4), so
   * the text copied from a question opens with this; the line itself is dropped when the item is added.
   */
  const SCRUB = "※ 개인을 알 수 있는 내용은 지우고 요약해 주세요";

  /** [ADMIN-11] 개발사항에 추가: a new item filled in from the question, or the question joined to one that exists. */
  async function openDevFromInquiry(mode = "new") {
    const q = S.data.inquiry;
    const text = firstText(q);
    const line = text.split("\n").find((l) => l.trim())?.trim() ?? "";
    const title = [...line].length > 50 ? `${[...line].slice(0, 50).join("")}…` : line;
    const body = `${SCRUB}\n\n${text}\n\n— ${KIND_NAMES[q.kind] ?? "문의"}${q.topicName ? ` · ${q.topicName}` : ""}${q.appVersion ? ` · 앱 ${q.appVersion}` : ""}`;
    const tabs = `<div class="segmented" role="group" aria-label="추가 방법">${seg("dev-add-mode", "new", mode, "새로 만들기")}${seg("dev-add-mode", "attach", mode, "있는 항목에 붙이기")}</div>`;
    if (mode === "new") {
      openDialog(`<form data-form="dev-new" data-inquiry="${q.id}"><h2>개발사항에 추가</h2>${tabs}
        <p>문의 내용으로 채워 두었어요. 개발사항은 문의가 지워져도 개선 기록으로 남으니, 이름·연락처·회사처럼 개인을 알 수 있는 내용은 지우고 요약해 주세요. 추가한 사람으로 내 이름이 남아요.</p>
        <div class="fieldset"><label for="dev-title">제목</label><input id="dev-title" name="title" maxlength="100" required value="${esc(title)}"></div>
        <div class="fieldset"><label for="dev-body">내용</label><textarea id="dev-body" name="body" maxlength="4000">${esc(body)}</textarea></div>
        <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">추가</button></div></form>`, "wide");
      return;
    }
    openDialog(`<div class="box"><h2>있는 항목에 붙이기</h2>${tabs}
      <p>비슷한 항목이 위에 있어요. 붙이면 그 항목의 요청 수가 늘어요.</p>
      <input id="dev-find" type="search" placeholder="제목으로 찾기" autocomplete="off" aria-label="개발사항 찾기">
      <div id="dev-found"><div class="card skeleton short"></div></div>
      <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`, "wide");
    try {
      const d = await api("/dev?status=open");
      S.devCandidates = L.likeFirst(d.items.filter((x) => !(q.dev ?? []).some((y) => y.id === x.id)), text);
    } catch (e) {
      fail(e);
      S.devCandidates = [];
    }
    showCandidates("");
  }

  function showCandidates(find) {
    const box = document.getElementById("dev-found");
    if (!box) return;
    const f = find.trim().toLowerCase();
    const list = (S.devCandidates ?? []).filter((d) => !f || d.title.toLowerCase().includes(f) || (d.body ?? "").toLowerCase().includes(f)).slice(0, 20);
    box.innerHTML = list.length ? `<ul class="rows">${list.map((d) => `<li><button class="row" data-act="dev-attach" data-id="${d.id}">
      <span class="row-main"><b>${esc(d.title)}</b><small>${DEV_NAMES[d.status]} · 문의 ${d.links}건${d.like ? " · 비슷해요" : ""}</small></span>
      <span class="pill">붙이기</span></button></li>`).join("")}</ul>`
      : '<p class="meta">붙일 항목이 없어요. 새로 만들기를 눌러 주세요.</p>';
  }

  // ---- 개발사항 [ADMIN-10][ADMIN-11] ----

  function devListView() {
    const d = S.data.devList;
    const counts = d?.counts ?? {};
    const edit = can("dev", "edit");
    const picking = Boolean(S.devPick) && can("devStatus", "edit");
    const head = `<div class="segmented" role="group" aria-label="개발사항 상태">
        ${seg("dev-status", "open", S.devStatus, "할 일", counts.open)}${seg("dev-status", "todo", S.devStatus, "접수", counts.todo)}
        ${seg("dev-status", "doing", S.devStatus, "개발중", counts.doing)}${seg("dev-status", "done", S.devStatus, "완료", counts.done)}
        ${seg("dev-status", "rejected", S.devStatus, "반려", counts.rejected)}${seg("dev-status", "all", S.devStatus, "전체", counts.all)}
        ${seg("dev-status", "deleted", S.devStatus, "지움", counts.deleted)}</div>
      <div class="searchbar"><input id="search" type="search" placeholder="제목이나 내용으로 찾기" value="${esc(S.devQuery)}" autocomplete="off" enterkeyhint="search" aria-label="개발사항 찾기">
        ${edit && !picking ? `<button class="btn primary" data-act="dev-new">${icon(P.plus)}새 개발사항</button>` : ""}
        ${can("devStatus", "edit") ? `<button class="btn" data-act="dev-pick" aria-pressed="${picking}">${picking ? "선택 마침" : `${icon(P.check)}여러 개 고르기`}</button>` : ""}
        ${picking ? "" : csvButton("dev", d?.items)}</div>
      <div class="chips" role="group" aria-label="맡은 사람">${[["", "모두"], ["mine", "내가 맡은 것"]].map(([key, label]) =>
        `<button type="button" class="chip pick" data-act="dev-who" data-key="${key}" aria-pressed="${S.devWho === key}">${label}</button>`).join("")}</div>
      <p class="hint">문의에서 "개발사항에 추가"로 올리거나 여기서 바로 만들어요. 문의 수가 많은 것이 위에 와요. 고친 것은 모두 항목마다 기록에 남아요.${can("devStatus", "edit") ? " 여러 개 고르기로 한 번에 복사하거나 처리해요." : " 처리(개발중·완료·반려)는 최고 관리자나 처리 권한이 있는 관리자가 해요."}</p>`;
    return head + `<div id="dev-list">${devRows()}</div>`;
  }

  function devRows() {
    const d = S.data.devList;
    if (!d) return skeleton();
    const picking = Boolean(S.devPick) && can("devStatus", "edit");
    if (!d.items.length) {
      return empty(S.devQuery ? "찾는 개발사항이 없어요" : S.devStatus === "deleted" ? "지운 항목이 없어요" : "개발사항이 없어요",
        S.devQuery ? "" : "문의를 열고 개발사항에 추가를 누르면 여기에 모여요.");
    }
    const row = (x) => {
      const inner = `<span class="row-main"><b>${esc(x.title)}</b>
          <small>${[`문의 ${x.links}건${x.waiting ? ` (답 대기 ${x.waiting})` : ""}`, `${esc(x.createdBy.name)} · ${esc(ago(x.updatedAt))}`,
            x.assignee ? (x.assignee.id === S.me.id ? "내가 맡음" : `맡음 ${esc(x.assignee.name)}`) : ""].filter(Boolean).join(" · ")}</small>
          ${x.preview ? `<span class="preview">${esc(x.preview)}</span>` : ""}</span>
        <span class="pill ${DEV_TONES[x.status]}">${x.deletedAt ? "지움" : DEV_NAMES[x.status]}</span>`;
      return picking ? `<li><label class="row${S.devPick.has(x.id) ? " picked" : ""}"><input class="check" type="checkbox" data-pick="${x.id}"${S.devPick.has(x.id) ? " checked" : ""}>${inner}</label></li>`
        : `<li><a class="row" href="${href("dev", x.id)}">${inner}${icon(P.chevron)}</a></li>`;
    };
    const n = picking ? S.devPick.size : 0;
    const status = can("devStatus", "edit");
    return `<ul class="rows">${d.items.map(row).join("")}</ul>${moreButton(d.next, "dev-more", d.total, d.items.length)}
      ${picking ? `<div class="bulkbar" role="toolbar" aria-label="고른 항목"><b>${n}개 골랐어요</b>
        <button class="btn small ghost" data-act="dev-pick-all">${n === d.items.length ? "모두 풀기" : "모두 고르기"}</button>
        <button class="btn small" data-act="dev-copy"${n ? "" : " disabled"}>${icon(P.copy)}복사</button>
        ${status && S.devStatus !== "deleted" ? ["doing", "done", "rejected"].map((k) => `<button class="btn small${k === "done" ? " primary" : ""}" data-act="dev-bulk" data-status="${k}"${n ? "" : " disabled"}>${DEV_NAMES[k]}</button>`).join("") : ""}
      </div>` : ""}`;
  }

  function devView() {
    const d = S.data.dev;
    if (!d || d.id !== S.route.id) return skeleton(3);
    const edit = can("dev", "edit") && !d.deletedAt;
    const status = can("devStatus", "edit") && !d.deletedAt;
    const reply = can("inquiries", "edit") && d.inquiries.length && !d.deletedAt;
    const mine = d.assignee?.id === S.me.id;
    return `<div class="cols"><section>
      <div class="card"><div class="card-head"><h2>${esc(d.title)}</h2><span class="pill ${DEV_TONES[d.status]}">${DEV_NAMES[d.status]}</span></div>
        <p class="meta">${esc(d.createdBy.name)}님이 ${esc(moment(d.createdAt))}에 추가 · 문의 ${d.links}건${d.releasedIn ? ` · ${esc(d.releasedIn)}에 반영` : ""}${d.assignee ? ` · ${mine ? "내가 맡음" : `${esc(d.assignee.name)}님이 맡음`}` : ""}</p>
        ${d.body ? `<p class="dev-body">${esc(d.body)}</p>` : '<p class="meta">내용이 없어요.</p>'}
        ${d.status === "rejected" && d.reason ? `<p class="note bad">반려 이유: ${esc(d.reason)}</p>` : ""}
        ${d.deletedAt ? `<p class="note warn">${esc(moment(d.deletedAt))}에 지운 항목이에요. 기록은 그대로 남아 있어요.${S.me.owner ? "" : " 되살리기는 최고 관리자가 해요."}</p>` : ""}
        <div class="actions">${d.deletedAt ? (S.me.owner ? '<button class="btn primary" data-act="dev-restore">되살리기</button>' : "")
          : `${edit ? `<button class="btn danger" data-act="dev-delete">지우기</button>
            <button class="btn" data-act="dev-assign" data-to="${mine ? "" : "me"}">${mine ? "맡기 취소" : d.assignee ? "내가 넘겨받기" : "내가 맡기"}</button>
            <button class="btn" data-act="dev-edit">고치기</button>` : ""}
            <button class="btn" data-act="dev-copy-one">${icon(P.copy)}복사</button>`}</div></div>
      ${status ? `<section class="card"><h2>처리</h2><p class="hint">누르면 바로 바뀌고 기록에 남아요. 반려는 이유를 함께 남겨요.</p>
        <div class="chips" role="group" aria-label="처리 상태">${Object.keys(DEV_NAMES).map((k) =>
          `<button type="button" class="chip pick" data-act="dev-status-set" data-status="${k}" aria-pressed="${d.status === k}">${DEV_NAMES[k]}</button>`).join("")}</div>
        <form class="mini" data-form="dev-release"><label for="dev-release">반영 버전</label>
          <p class="hint">완료한 항목이 나간 앱 버전(예: 1.18.0). 연결된 문의를 보낸 분 앱에 '반영됨 1.18.0'으로 보여요.</p>
          <div class="actions start"><input id="dev-release" name="v" inputmode="decimal" pattern="\\d{1,2}\\.\\d{1,2}\\.\\d{1,3}" placeholder="1.18.0" value="${esc(d.releasedIn ?? "")}">
          <button class="btn small" type="submit">저장</button></div></form></section>` : ""}
      <section class="card"><div class="card-head"><h2>연결된 문의</h2><span class="pill">${d.links}</span></div>
        ${d.inquiries.length ? `<ul class="rows">${d.inquiries.map((q) => `<li class="item"><a class="row-main" href="${href("inquiries", q.id)}">
            <b>${esc(KIND_NAMES[q.kind] ?? "문의")} <span class="pill ${q.status === "open" ? "warn" : q.status === "answered" ? "ok" : ""}">${STATUS_NAMES[q.status] ?? q.status}</span></b>
            <small>${esc(q.preview)}</small><small>${esc(q.linkedBy)}님이 ${esc(ago(q.linkedAt))} 연결</small></a>
            ${edit ? `<button class="btn small ghost" data-act="dev-unlink" data-id="${q.id}" aria-label="연결 떼기">떼기</button>` : ""}</li>`).join("")}</ul>`
          : `<p class="hint">${d.links && !can("inquiries") ? "문의 권한이 있어야 내용을 볼 수 있어요." : "문의 화면의 개발사항에 추가로 연결해요."}</p>`}
        ${reply ? `<div class="actions start"><button class="btn${d.status === "done" ? " primary" : ""}" data-act="dev-reply">연결된 문의에 한 번에 답하기</button></div>` : ""}</section>
    </section><aside>
      ${edit ? `<form class="card mini" data-form="dev-note"><h2>메모</h2><label class="sr" for="dev-note">메모</label>
        <textarea id="dev-note" name="text" maxlength="1000" placeholder="다른 관리자에게 남길 말. 기록에 남아요." required></textarea>
        <div class="actions"><button class="btn small" type="submit">남기기</button></div></form>` : ""}
      <section class="card"><h2>기록</h2><p class="hint">누가 언제 무엇을 바꿨는지 모두 남아요.</p>
        <ul class="history">${d.history.map(historyItem).join("")}</ul></section>
    </aside></div>`;
  }

  /** One line of an item's history, in words, with what it was before. */
  function historyItem(h) {
    const x = h.detail ?? {};
    const was = (a, b) => `<span class="was">${esc(a || "없음")}</span> → ${esc(b || "없음")}`;
    const what = {
      create: () => "만들었어요",
      edit: () => [x.title ? `제목: ${L.wordDiff(x.title[0] ?? "", x.title[1] ?? "")}` : "", x.body ? `내용을 고쳤어요<details><summary>바뀐 곳</summary><blockquote class="wdiff">${L.wordDiff(x.body[0] ?? "", x.body[1] ?? "")}</blockquote></details>` : ""].filter(Boolean).join("<br>"),
      status: () => `처리: ${was(DEV_NAMES[x.status?.[0]], DEV_NAMES[x.status?.[1]])}${x.bulk > 1 ? ` <span class="meta">(${x.bulk}개 한 번에)</span>` : ""}${x.reason ? `<blockquote>${esc(x.reason)}</blockquote>` : ""}`,
      assign: () => `맡은 사람: ${was(x.assignee?.[0], x.assignee?.[1])}`,
      note: () => `<blockquote>${esc(x.text)}</blockquote>`,
      link: () => `문의를 연결했어요${can("inquiries") && x.inquiry ? ` <a href="${href("inquiries", x.inquiry)}">열기</a>` : ""}`,
      unlink: () => "문의 연결을 뗐어요",
      release: () => `반영 버전: ${was(x.releasedIn?.[0], x.releasedIn?.[1])}`,
      delete: () => "지웠어요",
      restore: () => "되살렸어요",
      reply: () => `연결된 문의 ${x.count}건에 답했어요${x.close ? "(닫음)" : ""}<blockquote>${esc(x.text)}</blockquote>`,
    }[h.action]?.() ?? esc(h.action);
    return `<li><span class="who">${esc(h.actor || "관리자")}</span><span class="when">${esc(moment(h.at))}</span><span class="what">${what}</span></li>`;
  }

  function openDevEdit() {
    const d = S.data.dev;
    openDialog(`<form data-form="dev-edit" data-since="${d.updatedAt}"><h2>개발사항 고치기</h2><p>고친 내용은 전과 후가 함께 기록에 남아요.</p>
      <div class="fieldset"><label for="dev-title">제목</label><input id="dev-title" name="title" maxlength="100" required value="${esc(d.title)}"></div>
      <div class="fieldset"><label for="dev-body">내용</label><textarea id="dev-body" name="body" maxlength="4000">${esc(d.body)}</textarea></div>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">저장</button></div></form>`, "wide");
  }

  function openDevNew() {
    openDialog(`<form data-form="dev-new"><h2>새 개발사항</h2><p>추가한 사람으로 내 이름이 남아요. 문의에서 추가하면 문의가 함께 연결돼요.</p>
      <div class="fieldset"><label for="dev-title">제목</label><input id="dev-title" name="title" maxlength="100" required></div>
      <div class="fieldset"><label for="dev-body">내용</label><textarea id="dev-body" name="body" maxlength="4000"></textarea></div>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">추가</button></div></form>`, "wide");
  }

  /** 반려 needs a reason: one tap on a usual one fills it in. [ids] is set for many items at once. */
  function openReason(ids) {
    openDialog(`<form data-form="dev-reject" data-ids="${ids ? ids.join(",") : ""}"><h2>${ids ? `${ids.length}개를 반려할까요?` : "반려할까요?"}</h2>
      <p>이유는 항목마다 기록에 남아요. 연결된 문의에 답할 때도 참고해요.</p>
      <div class="chips">${REJECT_REASONS.map((r, i) => `<button type="button" class="chip pick" data-act="reason-pick" data-i="${i}">${esc(r)}</button>`).join("")}</div>
      <label for="dev-reason">반려 이유</label><textarea id="dev-reason" name="reason" maxlength="300" required></textarea>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn danger" type="submit">반려</button></div></form>`);
  }

  /** [ADMIN-11] One answer to the linked questions, filled in for the item's status; the admin reads it and sends. */
  function openDevReply() {
    const d = S.data.dev;
    const open = d.inquiries.filter((q) => q.status !== "closed");
    openDialog(`<form data-form="dev-reply"><h2>연결된 문의에 답하기</h2>
      <p>고른 문의를 보낸 분들 앱에 같은 답이 가요. 읽어 보고 보내 주세요.</p>
      <div class="radios">${d.inquiries.map((q) => `<label class="radio"><input type="checkbox" name="ids" value="${q.id}"${q.status !== "closed" || !open.length ? " checked" : ""}>
        <span>${esc(q.preview.slice(0, 60))} <span class="meta">${STATUS_NAMES[q.status] ?? ""}</span></span></label>`).join("")}</div>
      <label for="dev-reply-text">답</label><textarea id="dev-reply-text" name="text" maxlength="2000" required>${esc(d.status === "done" && d.releasedIn ? DEV_REPLIES.done.replace("반영했어요.", `${d.releasedIn}에 반영했어요.`) : DEV_REPLIES[d.status] ?? DEV_REPLIES.done)}</textarea>
      <label class="switch"><input type="checkbox" name="close"${d.status === "done" || d.status === "rejected" ? " checked" : ""}><span class="track" aria-hidden="true"></span><span>답하고 닫기</span></label>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">보내기</button></div></form>`, "wide");
  }

  /**
   * [ADMIN-12][ADMIN-16] 자주 쓰는 답: every admin's, added from what was typed or from here, changed or
   * deleted. A topic puts it first on questions of that topic; {앱버전} {주제} {오늘} are filled in.
   */
  function openTemplates(editId) {
    const list = S.templates ?? [];
    const names = S.topicNames ?? S.data.inquiries?.topicNames ?? {};
    const t = editId ? list.find((x) => x.id === Number(editId)) : null;
    const typed = t ? t.text : document.getElementById("reply")?.value.trim() ?? "";
    const topic = t ? t.topic : S.data.inquiry?.topic ?? "";
    openDialog(`<form data-form="template-add" data-id="${t ? t.id : ""}"><h2>${t ? "자주 쓰는 답 고치기" : "자주 쓰는 답"}</h2><p>모든 관리자가 같이 써요. 누르면 답 칸에 들어가요. {앱버전}, {주제}, {오늘}은 그 문의에 맞게 채워져요.</p>
      ${!t && list.length ? `<ul class="rows">${list.map((x) => `<li class="item"><div class="row-main"><b>${esc(x.label)}${x.topic ? ` <span class="pill">${esc(names[x.topic] ?? x.topic)}</span>` : ""}</b>
        <small>${esc(x.text.slice(0, 80))}${x.by ? ` · ${esc(x.by)}` : ""} · ${x.uses ?? 0}번 씀</small></div>
        <button type="button" class="btn small" data-act="template-edit" data-id="${x.id}">고치기</button>
        <button type="button" class="btn small danger" data-act="template-remove" data-id="${x.id}">지우기</button></li>`).join("")}</ul>` : ""}
      <div class="fieldset"><label for="tpl-label">${t ? "이름" : "새 답 이름"}</label><input id="tpl-label" name="label" maxlength="20" required placeholder="예: 업데이트 안내" value="${esc(t?.label ?? "")}"></div>
      <div class="fieldset"><label for="tpl-topic">주제(이 주제의 문의에서 먼저 보여요)</label><div class="field"><select id="tpl-topic" name="topic"><option value="">모든 문의</option>
        ${Object.entries(names).map(([k, label]) => `<option value="${k}"${topic === k ? " selected" : ""}>${esc(label)}</option>`).join("")}</select></div></div>
      <div class="fieldset"><label for="tpl-text">내용</label><textarea id="tpl-text" name="text" maxlength="2000" required>${esc(typed)}</textarea></div>
      <div class="actions"><button class="btn" type="button" data-act="${t ? "templates" : "close"}">${t ? "목록으로" : "닫기"}</button><button class="btn primary" type="submit">${t ? "저장" : "넣기"}</button></div></form>`, "wide");
  }

  /** [ADMIN-16] One answer to every picked question. */
  function openBulkReply() {
    const n = S.inquiryPick.size;
    openDialog(`<form data-form="bulk-reply"><h2>${n}개 문의에 같은 답 보내기</h2><p>고른 문의를 보낸 분들 앱에 같은 답이 가요. {앱버전} 같은 칸은 채워지지 않으니 그대로 읽히는 말로 써 주세요.</p>
      <div class="chips">${templateList().map((t, i) => `<button type="button" class="chip pick" data-act="bulk-template" data-i="${i}">${esc(t.label)}</button>`).join("")}</div>
      <label for="bulk-text">답</label><textarea id="bulk-text" name="text" maxlength="2000" required></textarea>
      <input type="hidden" name="template" value="">
      <label class="switch"><input type="checkbox" name="close"><span class="track" aria-hidden="true"></span><span>답하고 닫기</span></label>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">보내기</button></div></form>`, "wide");
  }

  /** [ADMIN-16] The picked questions joined to one 개발사항, most alike first. */
  async function openBulkLink() {
    openDialog(`<div class="box"><h2>개발사항에 연결</h2><p>고른 문의 ${S.inquiryPick.size}개를 한 항목에 붙여요. 그 항목의 요청 수가 늘어요.</p>
      <input id="dev-find" type="search" placeholder="제목으로 찾기" autocomplete="off" aria-label="개발사항 찾기">
      <div id="dev-found"><div class="card skeleton short"></div></div>
      <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`, "wide");
    try {
      const d = await api("/dev?status=open&limit=100");
      const text = (S.data.inquiries?.inquiries ?? []).filter((q) => S.inquiryPick.has(q.id)).map((q) => q.preview).join(" ");
      S.devCandidates = L.likeFirst(d.items, text);
    } catch (e) {
      fail(e);
      S.devCandidates = [];
    }
    S.linkMode = "bulk";
    showCandidates("");
  }

  async function devSave(body, done) {
    try {
      const d = await api(`/dev/${S.route.id}`, "POST", body);
      S.data.dev = d;
      if (done) toast(done);
      render({ keepScroll: true });
      load({ quiet: true });
      return d;
    } catch (e) {
      if (e.message === "changed" && e.data?.item) {
        S.data.dev = e.data.item;
        render({ keepScroll: true });
      }
      fail(e);
      return null;
    }
  }

  /** Sets a status; 완료 with questions still waiting offers the one answer to all of them at once. */
  async function devSetStatus(status, reason) {
    const d = await devSave(reason ? { status, reason } : { status }, `${DEV_TO[status]} 바꿨어요`);
    if (d && (status === "done" || status === "rejected") && can("inquiries", "edit") && d.inquiries.some((q) => q.status !== "closed")) openDevReply();
  }

  async function devBulk(status, reason) {
    const ids = [...S.devPick];
    const r = await act(() => api("/dev/bulk", "POST", reason ? { ids, status, reason } : { ids, status }),
      (x) => `${x.changed}개를 ${DEV_TO[status]} 바꿨어요${status === "done" ? ". 항목을 열어 연결된 문의에 답할 수 있어요" : ""}`);
    if (r) {
      S.devPick = null;
      load();
    }
  }

  // ---- companies ----

  function companiesView() {
    return `<div class="searchbar"><input id="search" type="search" placeholder="회사 이름이나 다른 이름" value="${esc(S.query)}" autocomplete="off"
        enterkeyhint="search" aria-label="회사 찾기">${can("companies", "edit") ? `<button class="btn primary" data-act="new-company">${icon(P.plus)}회사 만들기</button>` : ""}</div>
      <div id="company-list">${companyRows()}</div>`;
  }

  function companyRows() {
    const list = S.data.list?.companies;
    if (!list) return skeleton();
    if (!list.length) {
      return empty(S.query ? "찾는 회사가 없어요" : "아직 회사가 없어요", S.query ? "회사 만들기로 직접 만들 수 있어요." : "제보가 오면 회사가 저절로 생겨요.");
    }
    return `<ul class="rows">${list.map((c) => `<li><a class="row" href="${href("companies", c.id)}">
      <span class="row-main"><b>${esc(c.name)}</b><small>${[c.rules ? `규칙 ${c.rules}` : "규칙 없음", c.pending ? `대기 ${c.pending}` : "", `제보 ${c.reports}`,
        c.aliases ? `다른 이름 ${c.aliases}` : ""].filter(Boolean).join(" · ")}</small></span>
      ${c.mergedInto ? '<span class="pill">합쳐짐</span>' : c.hidden ? '<span class="pill">숨김</span>' : c.pending ? '<span class="pill warn">대기</span>' : ""}${icon(P.chevron)}</a></li>`).join("")}</ul>`;
  }

  function companyView(c) {
    if (!c || c.id !== S.route.id) return skeleton(3);
    if (c.mergedInto) {
      return `<section class="card"><p>이 회사는 <a href="${href("companies", c.mergedInto.id)}">${esc(c.mergedInto.name)}</a>에 합쳐졌어요.</p></section>`;
    }
    const edit = can("companies", "edit");
    const dis = edit ? "" : " disabled";
    return `<div class="cols"><div>
      <section class="card">
        <h2>규칙</h2>
        ${c.rules.length ? c.rules.map((r) => ruleCard(r, edit)).join("") : '<p class="meta">채택된 규칙이 아직 없어요.</p>'}
      </section></div><div>
      <section class="card">
        <form data-form="rename"><label for="name">이름</label>
          <div class="field"><input id="name" name="name" value="${esc(c.name)}" maxlength="40" required${dis}>${edit ? '<button class="btn" type="submit">저장</button>' : ""}</div></form>
        <label class="switch"><input type="checkbox" data-change="hidden"${c.hidden ? " checked" : ""}${dis}><span class="track" aria-hidden="true"></span><span>회사 찾기에서 숨기기</span></label>
        <p class="meta">${day(c.createdAt)}에 생김${c.pending ? ` · 검토 대기 ${c.pending}` : ""}</p>
      </section>
      <section class="card">
        <h2>다른 이름</h2>
        <p class="hint">영문 이름, 줄임말, 예전 이름처럼 사람들이 찾을 만한 이름을 넣어 두면 회사 찾기에 잡혀요.</p>
        <div class="chips">${c.aliases.length ? c.aliases.map((a) => `<span class="chip">${esc(a)}${edit ? `<button data-act="remove-alias" data-name="${esc(a)}"
          aria-label="${esc(a)} 지우기">×</button>` : ""}</span>`).join("") : '<span class="meta">아직 없어요</span>'}</div>
        ${edit ? `<form data-form="alias"><div class="field"><input name="name" placeholder="예: Bamnat Steel, 밤스틸" maxlength="40" required aria-label="다른 이름">
          <button class="btn" type="submit">추가</button></div></form>` : ""}
      </section>
      ${edit ? `<section class="card">
        <h2>다른 회사에 합치기</h2>
        <p class="hint">같은 회사가 다른 이름으로 두 번 생겼을 때 써요. 제보와 규칙이 모두 옮겨지고 이 이름은 다른 이름으로 남아요.</p>
        <form data-form="merge-search"><div class="field"><input name="q" placeholder="합칠 회사 이름" required aria-label="합칠 회사 이름">
          <button class="btn" type="submit">찾기</button></div></form>
        <div id="merge-results"></div>
      </section>` : ""}</div></div>`;
  }

  function ruleCard(r, edit) {
    const [tone, label] = r.withdrawn ? ["bad", "내림"] : r.superseded ? ["", "이전 버전"] : ["ok", "쓰는 중"];
    // [PLZ-06] 내리기·다시 올리기 need 광장 규칙 내리기 (결정 D35).
    const action = !edit || !can("takedown", "edit") ? "" : r.withdrawn ? `<button class="btn" data-act="restore-rule" data-id="${r.id}">다시 올리기</button>`
      : r.superseded ? "" : `<button class="btn danger" data-act="withdraw-rule" data-id="${r.id}">내리기</button>`;
    return `<div class="rule${r.withdrawn || r.superseded ? " dim" : ""}">
      <div class="head"><div><b>${esc(r.dept || "회사 전체")}</b> <span class="ver">v${r.version}</span>
        <p class="meta">${r.source === "auto" ? "자동 채택" : "직접 채택"} · ${day(r.adoptedAt)} · 맞았어요 ${r.ok} · 달라요 ${r.bad}</p></div>
        <span class="pill ${tone}">${label}</span></div>
      <details><summary>내용</summary>${lines(r.lines)}</details>
      ${action ? `<div class="actions">${action}</div>` : ""}
    </div>`;
  }

  // ---- rewards ----

  function rewardsView() {
    const r = S.data.rewards;
    const counts = r?.counts ?? {};
    const edit = can("rewards", "edit");
    const segs = [["owed", "드릴 보상"], ["paid", "지급함"], ["void", "취소"]].map(([key, label]) => seg("reward-tab", key, S.rewardTab, label, counts[key])).join("");
    const body = !r || r.status !== S.rewardTab ? skeleton() : !r.rewards.length ? empty(S.rewardTab === "owed" ? "드릴 보상이 없어요" : "목록이 비어 있어요", "")
      : `<ul class="rows">${r.rewards.map((w) => `<li class="item">
        <div class="row-main"><b>${esc(w.company.name)}${w.dept ? ` · ${esc(w.dept)}` : ""}</b>
          <small>프로 ${w.months}개월 · ${day(w.createdAt)} ${w.ask ? "앱에 없는 방식" : "채택"}${w.version ? ` · 규칙 v${w.version}` : ""}${w.paidAt ? ` · ${day(w.paidAt)} 지급함` : ""}</small>
          ${w.deferred ? '<span class="pill ok">Play 결제 미룸</span>' : ""}
          ${w.memo ? `<small class="memo">${esc(w.memo)}</small>` : ""}</div>
        ${edit ? `<div class="actions">${w.status === "owed" ? `<button class="btn" data-act="reward" data-id="${w.id}" data-status="void">취소</button>
          <button class="btn primary" data-act="reward" data-id="${w.id}" data-status="paid" data-months="${w.months}">지급하기</button>`
          : `<button class="btn" data-act="reward" data-id="${w.id}" data-status="owed">대기로 되돌리기</button>`}</div>` : ""}
      </li>`).join("")}</ul>${moreButton(r.next, "rewards-more", r.total, r.rewards.length)}`;
    return `<div class="segmented" role="group" aria-label="보상 상태">${segs}</div>
      <div class="toolbar"><input id="search" type="search" placeholder="회사나 부서로 찾기" value="${esc(S.rewardQuery)}" autocomplete="off" enterkeyhint="search" aria-label="보상 찾기">${csvButton("rewards", r?.status === S.rewardTab ? r.rewards : null)}</div>
      <p class="hint">[지급하기]를 누르면 보낸 분 휴대폰에 프로 이용권이 생겨요. 이용권은 쌓이고, 정식 출시 전에 드린 것은 출시 날부터 세요. 이미 구독 중인 분은 Play의 다음 결제일이 그만큼 미뤄져요(서버에 Play 키가 있을 때). 스토어 리뷰와는 묶지 않아요.</p>${body}`;
  }

  // ---- admins (the owner only) ----

  function adminsView() {
    const d = S.data.admins;
    if (!d) return skeleton(3);
    const waiting = d.admins.filter((a) => a.status === "pending");
    const members = d.admins.filter((a) => a.status !== "pending");
    const invites = d.invites.filter((i) => i.state !== "joined");
    const statusPill = (a) => (a.status === "active" ? '<span class="pill ok">사용 중</span>' : '<span class="pill bad">멈춤</span>');
    const first = !members.length && !invites.length && !waiting.length;
    return `<div class="actions start" id="admins-top"><button class="btn primary" data-act="invite">${icon(P.plus)}관리자 초대</button></div>
      <details class="card how"${first ? " open" : ""}><summary>관리자는 어떻게 들어오나요</summary>
        <ol class="steps"><li>여기서 초대할 메일과 권한을 정하면 초대 링크가 생겨요.</li>
          <li>그 링크를 그 메일로 보내요. 메일을 받은 분만 계정을 만들 수 있어요${d.mail ? " (확인 코드도 메일로 가요)" : ""}.</li>
          <li>링크를 연 분이 이름과 비밀번호를 정하고 앱을 설치해요.</li>
          <li>여기에서 승인하면 그때부터 로그인할 수 있어요.</li></ol></details>
      ${waiting.length ? `<h2 class="section">승인을 기다려요<span class="count">${waiting.length}</span></h2><ul class="rows">${waiting.map((a) => `<li class="item">
        <div class="row-main"><b>${esc(a.name)}</b><small>${esc(a.email)} · ${esc(ago(a.createdAt))} 가입</small><div class="perm-sum">${permPills(a.perms, d.areas)}</div></div>
        <div class="actions"><button class="btn danger" data-act="admin-remove" data-id="${a.id}" data-name="${esc(a.name)}">거절</button>
          <button class="btn primary" data-act="admin-approve" data-id="${a.id}">승인</button></div></li>`).join("")}</ul>` : ""}
      <h2 class="section">관리자<span class="count">${members.length + 1}</span></h2>
      <ul class="rows">
        <li class="item"><div class="row-main"><b>최고 관리자</b><small>ADMIN_PASSWORD로 로그인 · 모든 권한과 관리자 관리</small></div><span class="pill gold">최고</span></li>
        ${members.map((a) => `<li class="item">
          <div class="row-main"><b>${esc(a.name)}</b><small>${esc(a.email)}${a.lastLogin ? ` · 마지막 로그인 ${esc(ago(a.lastLogin))}` : " · 아직 로그인 안 함"}</small>
            <div class="perm-sum">${permPills(a.perms, d.areas)}</div></div>
          ${statusPill(a)}
          <div class="actions">${can("audit") ? `<a class="btn small ghost" href="${L.routeHash("audit", null, { actor: a.id })}">한 일</a>` : ""}<button class="btn small" data-act="admin-edit" data-id="${a.id}">권한</button>
            ${a.status === "active" ? `<button class="btn small" data-act="admin-status" data-id="${a.id}" data-status="suspended" data-name="${esc(a.name)}">멈추기</button>`
              : `<button class="btn small" data-act="admin-status" data-id="${a.id}" data-status="active">다시 쓰기</button>`}
            <button class="btn small danger" data-act="admin-remove" data-id="${a.id}" data-name="${esc(a.name)}">삭제</button></div></li>`).join("")}
      </ul>
      ${phonesSection()}
      <h2 class="section">초대한 메일<span class="count">${invites.length}</span></h2>
      ${invites.length ? `<ul class="rows">${invites.map((i) => `<li class="item">
        <div class="row-main"><b>${esc(i.email)}</b><small>${i.state === "expired" ? "링크 만료" : `${esc(day(i.expiresAt))}까지`}${i.note ? ` · ${esc(i.note)}` : ""}</small>
          <div class="perm-sum">${permPills(i.perms, d.areas)}</div></div>
        <span class="pill ${i.state === "expired" ? "bad" : "warn"}">${i.state === "expired" ? "만료" : "가입 전"}</span>
        <div class="actions"><button class="btn small" data-act="invite-again" data-email="${esc(i.email)}">새 링크</button>
          <button class="btn small danger" data-act="invite-remove" data-email="${esc(i.email)}">취소</button></div></li>`).join("")}</ul>`
        : '<p class="hint">가입을 기다리는 초대가 없어요.</p>'}`;
  }

  /** [MON-09] Admins' phones tied with a code from 내 정보: approved ones get 프로 free while the admin stays. */
  function phonesSection() {
    const p = S.data.phones;
    if (!p) return "";
    const pill = (d) => (d.status === "approved" ? '<span class="pill ok">프로 무료</span>' : '<span class="pill warn">승인 대기</span>');
    return `<h2 class="section">관리자 휴대폰<span class="count">${p.devices.length}</span></h2>
      <p class="hint">관리자가 내 정보 › '내 휴대폰 밤낮 연결'로 만든 코드를 앱에 넣으면 여기에 나와요. 승인하면 그 휴대폰은 프로를 무료로 써요. 한 사람당 ${p.perAdmin}대, 관리자를 지우면 같이 끊겨요.</p>
      ${p.devices.length ? `<ul class="rows">${p.devices.map((d) => `<li class="item">
        <div class="row-main"><b>${esc(d.adminName)}의 휴대폰</b><small>${esc(ago(d.createdAt))} 연결${d.seenAt ? ` · 마지막 확인 ${esc(ago(d.seenAt))}` : ""}</small></div>
        ${pill(d)}
        <div class="actions">${d.status === "approved" ? "" : `<button class="btn small primary" data-act="phone" data-id="${d.id}" data-key="approve">승인</button>`}
          <button class="btn small danger" data-act="phone" data-id="${d.id}" data-key="remove">끊기</button></div></li>`).join("")}</ul>`
        : '<p class="hint">연결된 휴대폰이 없어요.</p>'}`;
  }

  /** Area by area: nothing, a look, or changes too; presets fill the table in one tap. */
  function permEditor(perms, d) {
    const presets = Object.entries(d.presets ?? {});
    return `<p class="label">빠르게 고르기</p>
      <div class="chips" role="group" aria-label="역할">${presets.map(([key, p]) => `<button type="button" class="chip pick" data-act="preset" data-key="${key}">${esc(p.name)}</button>`).join("")}</div>
      <p class="label">영역별 권한</p>
      <div class="perm-table">${Object.entries(d.areas).map(([area, label]) => {
        const yesNo = (d.toggles ?? toggles()).includes(area);
        const levels = yesNo ? ["none", "edit"] : ["none", "view", "edit"];
        const cur = levels.includes(perms[area]) ? perms[area] : "none";
        // [ADMUX-03] A radio group: one stop for Tab, the arrow keys move between levels.
        return `<div class="perm"><b id="perm-l-${area}">${esc(label)}</b>
        <span class="levels" role="radiogroup" aria-labelledby="perm-l-${area}">${levels.map((l) => `<button type="button" data-act="level" data-area="${area}"
          data-level="${l}" role="radio" aria-checked="${cur === l}" aria-pressed="${cur === l}" tabindex="${cur === l ? 0 : -1}"${l === "edit" && d.viewOnly?.includes(area) ? " disabled" : ""}>${yesNo ? (l === "edit" ? "줌" : "없음") : LEVEL_NAMES[l]}</button>`).join("")}</span>
        <input type="hidden" name="perm-${area}" value="${cur}"></div>`;
      }).join("")}</div>
      <p class="help">보기는 화면을 보기만 하고, 고치기는 채택·답하기 같은 바꾸는 일까지 해요. 개발사항 처리는 개발중·완료·반려를 정하고 여러 항목을 한 번에 처리하는 권한이에요. 관리자 관리는 최고 관리자만 해요.</p>`;
  }

  const readPermForm = (form, d) => Object.fromEntries(Object.keys(d.areas).map((a) => [a, form.elements[`perm-${a}`]?.value ?? "none"]));

  function openInvite(prefill) {
    const d = S.data.admins;
    const perms = prefill?.perms ?? d.presets.reviewer.perms;
    openDialog(`<form data-form="invite"><h2>관리자 초대</h2>
      <p>초대한 메일로만 계정을 만들 수 있어요. 가입한 뒤에도 승인해야 로그인돼요.</p>
      <div class="fieldset"><label for="inv-email">메일</label><input id="inv-email" name="email" type="email" autocomplete="off" inputmode="email"
        value="${esc(prefill?.email ?? "")}" required${prefill?.email ? " readonly" : ""}></div>
      <div class="fieldset"><label for="inv-note">메모 (선택)</label><input id="inv-note" name="note" maxlength="100" value="${esc(prefill?.note ?? "")}" placeholder="예: 검토 도와줄 민지"></div>
      ${permEditor(perms, d)}
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">초대 링크 만들기</button></div></form>`, "wide");
  }

  function openInviteLink(r) {
    const text = `밤낮 관리자로 초대해요.\n\n아래 링크를 휴대폰에서 열어 계정을 만들고 앱을 설치해 주세요. 이 링크는 ${r.email}에서만 쓸 수 있고 ${day(r.expiresAt)}까지 열려요.\n\n${r.link}`;
    const mailto = `mailto:${encodeURIComponent(r.email)}?subject=${encodeURIComponent("[밤낮 관리자] 관리자 초대")}&body=${encodeURIComponent(text)}`;
    S.inviteText = text;
    S.inviteLink = r.link;
    openDialog(`<div class="box"><h2>초대 링크가 생겼어요</h2>
      <p>${esc(r.email)}로 보내 주세요. 링크는 지금 한 번만 보여요. 잃어버리면 새 링크를 만들면 돼요.</p>
      <div class="fieldset"><label for="inv-link">초대 링크</label><input id="inv-link" type="url" readonly value="${esc(r.link)}"></div>
      <div class="actions start"><a class="btn primary" href="${esc(mailto)}">${icon(P.mail)}메일로 보내기</a>
        <button class="btn" data-act="copy-invite">${icon(P.copy)}복사</button>
        ${navigator.share ? `<button class="btn" data-act="share-invite">${icon(P.share)}공유</button>` : ""}</div>
      <p class="note info">메일 앱으로 보내야 그 메일함을 가진 분만 링크를 받아요. 메신저로 보내면 메일 확인이 되지 않아요${r.mail ? " (이 서버는 가입할 때 메일로 확인 코드도 보내요)" : ""}.</p>
      <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`, "wide");
  }

  function openAdminEdit(id, approve) {
    const d = S.data.admins;
    const a = d.admins.find((x) => x.id === id);
    if (!a) return;
    openDialog(`<form data-form="admin-perms" data-id="${id}" data-approve="${approve ? 1 : ""}"><h2>${approve ? `${esc(a.name)}님을 승인할까요?` : `${esc(a.name)}님의 권한`}</h2>
      <p>${esc(a.email)}${approve ? " · 승인하면 바로 로그인할 수 있어요. 초대할 때 고른 권한으로 시작해요." : " · 바꾸면 다음 화면부터 바로 적용돼요."}</p>
      ${permEditor(a.perms, d)}
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">${approve ? "승인" : "저장"}</button></div></form>`, "wide");
  }

  // ---- activity [ADMIN-17] ----

  function auditView() {
    const d = S.data.audit;
    const f = S.audit;
    const actors = d?.actors ?? [];
    const filters = `<div class="toolbar" role="group" aria-label="활동 기록 거르기">
        <label class="sr" for="au-actor">누가</label><select id="au-actor" data-change="audit-actor"><option value="">모든 사람</option><option value="me"${f.actor === "me" ? " selected" : ""}>나</option>
          ${actors.map((a) => `<option value="${esc(a.id)}"${f.actor === a.id ? " selected" : ""}>${esc(a.name)}</option>`).join("")}</select>
        <label class="sr" for="au-kind">종류</label><select id="au-kind" data-change="audit-kind"><option value="">모든 일</option>
          ${(d?.kinds ?? Object.keys(AUDIT_KIND_NAMES)).map((k) => `<option value="${k}"${f.kind === k ? " selected" : ""}>${esc(AUDIT_KIND_NAMES[k] ?? k)}</option>`).join("")}</select>
        <label class="sr" for="au-from">부터</label><input id="au-from" type="date" value="${esc(f.from)}" data-change="audit-from" aria-label="이날부터">
        <label class="sr" for="au-to">까지</label><input id="au-to" type="date" value="${esc(f.to)}" data-change="audit-to" aria-label="이날까지">
        ${f.actor || f.kind || f.from || f.to ? '<button class="btn small ghost" data-act="audit-clear">거르기 풀기</button>' : ""}
        <button class="btn small" data-act="audit-csv"${d?.entries?.length ? "" : " disabled"}>${icon(P.install)}CSV로 받기</button></div>`;
    if (!d) return filters + skeleton(3);
    if (!d.entries.length) return filters + empty(f.actor || f.kind || f.from || f.to ? "고른 조건에 맞는 활동이 없어요" : "아직 활동이 없어요", "채택, 답하기, 로그인 같은 일이 여기 남아요. 1년 지난 기록은 지워져요.",
      f.actor || f.kind || f.from || f.to ? '<button class="btn" data-act="audit-clear">조건 지우기</button>' : "");
    let last = "";
    const out = [];
    for (const e of d.entries) {
      const dd = new Date(e.at).toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric", weekday: "short" });
      if (dd !== last) {
        if (last) out.push("</ul></section>");
        out.push(`<h2 class="day-head">${esc(dd)}</h2><section class="card"><ul class="feed">`);
        last = dd;
      }
      const about = e.label ? (e.link ? ` <a class="meta" href="${esc(e.link)}">${esc(e.label)}</a>` : ` <span class="meta">${esc(e.label)}</span>`)
        : e.target ? ` <span class="meta">${esc(e.target)}</span>` : "";
      const who = e.actorId ? `<button class="who link" data-act="audit-who" data-id="${esc(e.actorId)}" title="이 사람이 한 일만 보기">${esc(e.actor || "관리자")}</button>`
        : `<span class="who">${esc(e.actor || "앱 사용자·시스템")}</span>`;
      out.push(`<li><span class="when">${esc(clock(e.at))}</span><span><b>${esc(ACTION_NAMES[e.action] ?? e.action)}</b>${about}
        ${e.detail ? `<span class="meta">${esc(e.detail)}</span>` : ""}</span>${who}</li>`);
    }
    out.push("</ul></section>");
    return `${filters}<p class="hint">누가 언제 무엇을 했는지 남아요. 이름을 누르면 그 사람이 한 일만 보여요. 1년 지난 기록은 지워져요.</p>${out.join("")}
      ${d.more ? '<div class="actions start"><button class="btn wide" data-act="audit-more">더 보기</button></div>' : ""}`;
  }

  // ---- [SRV-04][ADMIN-14] 운영 ----

  const NOTICE_STATE = { on: ["ok", "보이는 중"], later: ["info", "예정"], ended: ["", "끝남"] };
  const kstDate = (iso) => new Date(new Date(iso).getTime() + 9 * 3600e3).toISOString().slice(0, 10);

  function opsView() {
    const n = S.data.notices;
    const o = S.data.ops;
    const edit = can("ops", "edit");
    if (!n || !o) return skeleton(3);
    const [y, m] = o.month.split("-").map(Number);
    const left = o.items.filter((i) => !i.done).length;
    const chores = `<section class="card"><div class="card-head"><h2>${y}년 ${m}월에 할 일</h2>
        <div class="actions start"><button class="icon-btn" data-act="ops-month" data-d="-1" aria-label="지난달">${icon(P.back)}</button>
        <button class="icon-btn" data-act="ops-month" data-d="1" aria-label="다음 달">${icon(P.chevron)}</button></div></div>
      <p class="hint">해마다 이 달에 하는 일이에요. ${left ? `${left}개 남았어요.` : "모두 마쳤어요."} 근거는 docs/ops-calendar.md에 있어요.</p>
      <ul class="rows">${o.items.map((i) => `<li><div class="row"><span class="row-main"><b>${esc(i.text)}</b><small>${esc(i.source)}</small></span>
        <span class="row-side">${edit ? `<button class="btn small${i.done ? "" : " primary"}" data-act="ops-tick" data-key="${i.key}" data-done="${i.done ? "" : "1"}">${i.done ? "되돌리기" : "마침"}</button>`
          : `<span class="pill ${i.done ? "ok" : ""}">${i.done ? "마침" : "남음"}</span>`}</span></div></li>`).join("")}</ul></section>`;
    const notices = `<section class="card"><div class="card-head"><h2>앱 공지</h2>${edit ? `<button class="btn small primary" data-act="notice-new">${icon(P.plus)}새 공지</button>` : ""}</div>
      <p class="hint">앱 달력 위에 한 줄로 보이고, 누르면 내용이 열려요. 사용자가 닫으면 그 공지는 다시 보이지 않아요. 앱은 하루에 한 번, 그리고 열 때 새로 받아요.</p>
      ${n.notices.length ? `<ul class="rows">${n.notices.map((x) => {
        const [tone, label] = NOTICE_STATE[x.state] ?? ["", x.state];
        const range = x.minApp || x.maxApp ? ` · 앱 ${x.minApp ?? "처음"}~${x.maxApp ?? "최신"}` : "";
        return `<li><div class="row"><span class="row-main"><b>${x.level === "important" ? '<span class="pill bad">중요</span> ' : ""}${esc(x.title)}</b>
          <small>${esc(kstDate(x.from))} ~ ${esc(kstDate(x.until))}${esc(range)}</small><small>${esc(x.body)}</small></span>
          <span class="row-side"><span class="pill ${tone}">${label}</span>${edit ? `<button class="btn small" data-act="notice-edit" data-id="${x.id}">고치기</button>
            <button class="btn small" data-act="notice-remove" data-id="${x.id}">내리기</button>` : ""}</span></div></li>`;
      }).join("")}</ul>` : '<p class="meta">지금 올린 공지가 없어요.</p>'}</section>`;
    const switches = `<section class="card"><h2>기능 스위치</h2>
      <p class="hint">문제가 생겼을 때 앱 업데이트 없이 그 기능만 잠시 꺼요. 서버도 바로 받지 않고, 앱은 하루 안에 따라와요. 끈 기능은 앱에 "잠시 쉬고 있어요"로 보여요.</p>
      <ul class="rows">${n.switches.map((w) => `<li><div class="row"><span class="row-main"><b>${esc(w.label)}</b><small>${esc(w.key)}</small></span>
        <span class="row-side"><span class="pill ${w.off ? "bad" : "ok"}">${w.off ? "꺼짐" : "켜짐"}</span>
        ${edit ? `<button class="btn small${w.off ? " primary" : ""}" data-act="switch" data-key="${w.key}" data-off="${w.off ? "" : "1"}">${w.off ? "다시 켜기" : "끄기"}</button>` : ""}</span></div></li>`).join("")}</ul>
      ${edit ? "" : '<p class="note">보기 권한이라 바꿀 수 없어요.</p>'}</section>`;
    return `<div class="grid two">${chores}${notices}${switches}</div>`;
  }

  function openNotice(x) {
    const now = new Date();
    const from = x ? kstDate(x.from) : kstDate(now.toISOString());
    const until = x ? kstDate(new Date(new Date(x.until).getTime() - 1).toISOString()) : kstDate(new Date(now.getTime() + 7 * 864e5).toISOString());
    openDialog(`<form data-form="notice" data-id="${x?.id ?? ""}"><h2>${x ? "공지 고치기" : "새 공지"}</h2>
      <p>이름·연락처·금액은 적지 않아요. 앱을 쓰는 모두에게 보여요.</p>
      <div class="fieldset"><label for="nt-title">제목</label><input id="nt-title" name="title" maxlength="40" required value="${esc(x?.title ?? "")}" placeholder="예: 10월 25일 새벽 서버 점검"></div>
      <div class="fieldset"><label for="nt-body">내용</label><textarea id="nt-body" name="body" maxlength="500" required>${esc(x?.body ?? "")}</textarea></div>
      <div class="radios"><label class="radio"><input type="radio" name="level" value="info"${x?.level === "important" ? "" : " checked"}><span>알림</span></label>
        <label class="radio"><input type="radio" name="level" value="important"${x?.level === "important" ? " checked" : ""}><span>중요(색을 넣어 보여요)</span></label></div>
      <div class="fieldset"><label for="nt-from">시작(한국 시간 0시)</label><input id="nt-from" name="from" type="date" required value="${from}"></div>
      <div class="fieldset"><label for="nt-until">마지막 날(그날 밤까지, 90일 안)</label><input id="nt-until" name="until" type="date" required value="${until}"></div>
      <div class="fieldset"><label for="nt-min">앱 버전 번호부터(선택)</label><input id="nt-min" name="minApp" type="number" min="1" value="${x?.minApp ?? ""}">
        <span class="help">특정 버전에만 보일 때. ${S.data.notices?.latestApp ? `최근 문의에 온 가장 새 앱은 ${esc(S.data.notices.latestApp.name)}(번호 ${S.data.notices.latestApp.code})예요.` : "앱 설정 › 앱 정보의 괄호 안 숫자예요."}</span></div>
      <div class="fieldset"><label for="nt-max">앱 버전 번호까지(선택)</label><input id="nt-max" name="maxApp" type="number" min="1" value="${x?.maxApp ?? ""}"></div>
      <p class="label">앱에서 보이는 모양</p><div class="notice-preview" id="nt-preview" aria-live="polite">${noticePreview(x?.title ?? "", x?.body ?? "", x?.level)}</div>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">${x ? "저장" : "올리기"}</button></div></form>`);
  }

  /** [ADMUX-07] One notice line as the phone shows it: the title, the first lines of the text, 중요 in colour. */
  function noticePreview(title, body, level) {
    return `<div class="np${level === "important" ? " important" : ""}"><b>${esc(title.trim() || "제목")}</b><span>${esc(body.trim() || "내용이 여기에 보여요")}</span></div>`;
  }

  // ---- [ADMIN-13][STAT-03][STAT-04] 지표 ----

  const GATE_STATE = { pass: ["ok", "통과"], fail: ["bad", "못 미침"], few: ["warn", "데이터 부족"], play: ["", "Play Console에서 확인"] };

  function metricsView() {
    const d = S.data.metrics;
    const head = `<div class="segmented" role="group" aria-label="기간">${[7, 28, 90].map((n) => seg("metric-days", String(n), String(S.metricDays), `${n}일`)).join("")}</div>`;
    if (!d) return head + skeleton(3);
    const pct = (v) => (v === null || v === undefined ? "–" : `${v}%`);
    const gates = `<section class="card"><h2>출시 관문</h2>
      <p class="hint">사용 통계 보내기에 동의한 분들만 세요(첫 실행에서 묻고, 언제든 끌 수 있어요). 하루 평균 30대 미만이면 숫자가 흔들려서 "데이터 부족"으로 보여요. 지금 하루 평균 ${d.daily}대예요.</p>
      <ul class="rows">${d.gates.map((g) => {
        const [tone, label] = GATE_STATE[g.status] ?? ["", g.status];
        return `<li><div class="row"><span class="row-main"><b>${esc(g.gate)} · ${esc(g.name)}</b><small>기준 ${esc(g.rule)}</small></span>
          <span class="row-side"><b>${g.value === null ? "" : pct(g.value)}</b><span class="pill ${tone}">${label}</span></span></div></li>`;
      }).join("")}</ul></section>`;
    const f = d.funnel;
    const funnel = `<section class="card"><h2>프로 전환</h2>
      <dl class="kv"><dt>프로 화면을 본 휴대폰(날 합)</dt><dd>${f.paywall}</dd><dt>무료 체험 시작</dt><dd>${f.trial}</dd><dt>구독 시작</dt><dd>${f.subscribe}</dd>
        <dt>지금 체험 중(서버 확인)</dt><dd>${f.trials}</dd><dt>지금 유료 구독(서버 확인)</dt><dd>${f.paid}</dd><dt>자동 갱신 끈 구독</dt><dd>${f.ending}</dd></dl>
      <p class="hint">위 셋은 사용 통계, 아래 셋은 Play 구매 확인으로 센 숫자라 서로 다를 수 있어요.</p></section>`;
    // [ADMUX-07] An SVG chart (the CSP allows no style attributes), with the period before as a dashed line.
    const before = Array.isArray(d.before) && d.before.length === d.series.length ? d.before : null;
    const sum = (list) => list.reduce((n, v) => n + v, 0);
    const now = sum(d.series.map((x) => x.phones));
    const was = before ? sum(before) : 0;
    const change = before && was ? Math.round(((now - was) / was) * 100) : null;
    const series = `<section class="card"><div class="card-head"><h2>날마다 보낸 휴대폰</h2>${change === null ? "" : `<span class="pill ${change >= 0 ? "ok" : "warn"}">이전 ${d.days}일보다 ${change >= 0 ? "+" : ""}${change}%</span>`}</div>
      ${d.series.length ? `${L.barChart(d.series.map((x) => x.phones), { labels: d.series.map((x) => x.day.slice(5).replace("-", "/")), before, name: "날마다 보낸 휴대폰 수" })}
        <p class="legend"><span><i class="sw" aria-hidden="true"></i>이 기간</span>${before ? '<span><i class="sw before" aria-hidden="true"></i>이전 기간</span>' : ""}</p>
        <p class="meta">${esc(d.from)} ~ ${esc(d.to)} · 휴대폰-날 ${d.phoneDays}</p>` : '<p class="meta">아직 받은 통계가 없어요.</p>'}</section>`;
    const events = `<section class="card"><h2>기능별</h2><p class="hint">비율은 최근 28일 안에 한 번이라도 한 휴대폰의 비율이에요.</p>
      <ul class="rows">${d.events.map((e) => `<li><div class="row"><span class="row-main"><b>${esc(e.label)}</b><small>${esc(e.event)} · ${e.total}번</small></span>
        <span class="row-side"><b>${pct(e.share)}</b></span></div></li>`).join("")}</ul></section>`;
    const sh = d.shares;
    // [STAT-04] The share server counts views roughly (approximate), so the numbers say 대략.
    const about = (v) => (sh?.approximate !== false ? `대략 ${v}` : v);
    const shares = `<section class="card"><h2>근무표 링크</h2>${sh ? `<dl class="kv"><dt>만든 링크</dt><dd>${about(sh.created)}</dd><dt>페이지 열람</dt><dd>${about(sh.views)}</dd>
        <dt>신고</dt><dd>${sh.reports ?? 0}</dd><dt>내린 링크</dt><dd>${sh.removed ?? 0}</dd></dl><p class="hint">공유 서버가 5분마다 모아 세는 대략의 숫자예요.</p>`
      : '<p class="meta">공유 서버가 연결되지 않았어요. 설정 → 서버 상태를 봐 주세요.</p>'}</section>`;
    return head + `<div class="grid two">${gates}${funnel}${series}${shares}${events}</div>`;
  }

  // ---- [SRV-05] 공유 링크 신고 ----

  const SHARE_REASONS = { personal: "다른 사람의 개인정보", abuse: "욕설·괴롭힘", spam: "광고·스팸", illegal: "불법 정보", other: "그 밖의 문제" };

  function sharesView() {
    const d = S.data.shares;
    if (!d) return skeleton(2);
    if (!d.connected) return empty("공유 서버가 연결되지 않았어요", "Worker 설정에 SHARE_BASE_URL과 SHARE_ADMIN_TOKEN을 넣으면 신고된 근무표 링크가 여기 보여요.");
    if (!d.reports.length) return empty("신고된 링크가 없어요", "근무표 링크 페이지와 앱의 받은 달력에서 신고하면 여기 모여요.");
    const edit = can("shares", "edit");
    return `<p class="hint">링크를 열어 확인하고, 운영 규칙에 어긋나면 내려요. 내린 링크는 근무표가 바로 지워지고 "운영 규칙에 따라 내려졌어요"로 보여요. 신고한 사람은 남지 않아요.</p>
      <ul class="rows">${d.reports.map((r) => `<li><div class="row"><span class="row-main"><b>신고 ${r.count}건</b>
        <small>${Object.entries(r.reasons ?? {}).map(([k, n]) => `${esc(SHARE_REASONS[k] ?? k)} ${n}`).join(" · ")}</small><small>마지막 신고 ${esc(ago(r.last))}</small></span>
        <span class="row-side"><a class="btn small" href="${esc(r.url)}" target="_blank" rel="noopener noreferrer">열어 보기</a>
        ${edit ? `<button class="btn small danger" data-act="share-remove" data-id="${esc(r.id)}">내리기</button>` : ""}</span></div></li>`).join("")}</ul>`;
  }

  // ---- settings, account, more ----

  /** What the server still lacks: [h] is /health on the sign-in screen, /server (with the optional services) here. */
  function healthList(h = S.health) {
    if (!h) return "";
    const todo = (name, why, missing) => ({
      short: `${name}가 10자보다 짧아요. 10자 이상으로 다시 넣고 Add variable and deploy를 눌러 주세요`,
      name: `이름이 조금 달라요. 대문자와 밑줄 그대로 ${name}로 다시 넣어 주세요`,
      store: "Secrets Store가 아니라 Worker → 설정 → 런타임 변수에 Secret으로 넣어 주세요",
    })[why] ?? missing;
    const rows = [
      [h.database, "데이터베이스(D1)가 연결돼 있어요", "D1 데이터베이스를 변수 이름 DB로 연결해 주세요 (Worker → 설정 → 바인딩)"],
      [h.password, "최고 관리자 비밀번호가 정해져 있어요", todo("ADMIN_PASSWORD", h.why?.password,
        "Worker → 설정 → 런타임 변수에 ADMIN_PASSWORD를 Secret으로, 10자 이상 넣고 Add variable and deploy를 눌러 주세요")],
      [h.salt, "RATE_SALT가 정해져 있어요", todo("RATE_SALT", h.why?.salt,
        "같은 곳에 RATE_SALT를 Secret으로 넣어 주세요 (아무 긴 글자). 넣은 뒤 이 화면을 새로 고쳐요")],
    ];
    // [ADMIN-15] The optional services only come to the owner (설정 › 서버 상태).
    if ("mail" in h) rows.push([h.mail, "가입 확인 메일을 보내요 (MAIL_API_KEY)", "선택: MAIL_API_KEY가 없으면 초대 링크를 메일로 보내 확인해요"]);
    if ("play" in h) rows.push([h.play, "Play 구매를 서버가 확인해요 (PLAY_SERVICE_ACCOUNT)", "선택: PLAY_SERVICE_ACCOUNT가 없으면 휴대폰의 Play 결제로만 판단해요"]);
    // [SRV-05] Optional: without it the 공유 링크 screen only says it is not connected.
    if ("share" in h) {
      rows.push([h.share, "공유 서버와 연결돼 있어요 (신고된 링크·링크 숫자)",
        "선택: 공유 서버의 신고를 보려면 SHARE_BASE_URL(텍스트)과 SHARE_ADMIN_TOKEN(Secret, 공유 서버와 같은 값)을 넣어 주세요"]);
    }
    // [ADMIN-19]
    if ("push" in h) {
      rows.push([h.push, "관리자 앱 알림(웹 푸시)을 보낼 수 있어요",
        "선택: 새 문의·신고를 알림으로 받으려면 VAPID_PUBLIC_KEY와 VAPID_PRIVATE_KEY를 Secret으로 넣어 주세요 (만드는 법은 README)"]);
    }
    return `<ul class="checks">${rows.map(([ok, good, todo]) =>
      `<li class="${ok ? "ok" : "todo"}"><span aria-hidden="true">${ok ? "✓" : "!"}</span>${esc(ok ? good : todo)}</li>`).join("")}</ul>`;
  }

  const SLA_NAMES = [["slaBug", "문제 신고"], ["slaQuestion", "질문"], ["slaIdea", "건의·제안"], ["slaOther", "그 밖의 이야기"]];

  function settingsView() {
    const s = S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3, adoptAgree: 75 };
    const edit = can("settings", "edit");
    const sla = S.slaDraft ?? S.overview?.sla ?? { slaBug: 8, slaQuestion: 24, slaIdea: 72, slaOther: 72 };
    const auto = (s.autoReward ?? 1) ? 1 : 0;
    return `<div class="grid two">
      ${can("settings") ? `<section class="card">
        <h2>자동 채택</h2>
        ${stepper("adoptPoints", "채택 점수", s.adoptPoints, "점", edit)}
        <p class="hint">같은 규칙을 서로 다른 2명 이상이 보내 이 점수가 되면 자동으로 채택해요. 명세서로 맞춰 본 제보는 2점, 나머지는 1점이에요.</p>
        ${stepper("adoptAgree", "맞았어요 비율", s.adoptAgree ?? 75, "%", edit)}
        <p class="hint">투표한 분 가운데 이만큼이 맞았어요라고 해야 자동으로 채택해요. 모자라면 검토에 남아 개발자가 정해요. 앱의 신뢰도는 이 비율과 점수를 함께 봐요.</p>
        ${stepper("rewardMonths", "채택 보상", s.rewardMonths, "개월", edit)}
        <p class="hint">채택된 정보를 보낸 분께 드릴 프로 이용 기간이에요. 0개월이면 보상을 기록하지 않아요.</p>
        ${stepper("adoptPersons", "채택에 필요한 사람", s.adoptPersons ?? 3, "명", edit)}
        <p class="hint">점수와 비율을 채워도 서로 다른 휴대폰이 이만큼은 돼야 자동으로 채택해요(결정 D32). 임금표 시세도 같아요.</p>
        ${stepper("amendPersons", "쓰는 규칙 고치기", s.amendPersons ?? 3, "명", edit)}
        <p class="hint">토론의 고치자는 제안이나 개정 제보에 이만큼 맞아요가 모이면 개발자 없이 다음 버전으로 바꿔요. 이전 버전은 이력에 남아요.</p>
        <label class="switch"><input type="checkbox" data-change="auto-reward"${auto ? " checked" : ""}${edit ? "" : " disabled"}><span class="track" aria-hidden="true"></span><span>보상 자동 지급</span></label>
        <p class="hint">켜면 채택될 때 처음 보낸 세 분과 제안한 분께 바로 프로를 드려요. 그 뒤 분들은 보상 화면에서 정해요. 투표한 분께는 드리지 않아요.</p>
        ${stepper("rewardCapMonths", "한 사람 상한", s.rewardCapMonths ?? 3, "개월", edit)}
        ${stepper("rewardCapDays", "상한을 세는 기간", s.rewardCapDays ?? 90, "일", edit)}
        <p class="hint">한 휴대폰이 이 기간에 자동으로 받는 보상은 상한까지예요. 넘는 보상은 보상 화면에 남아요.</p>
        ${edit ? `<div class="actions">${S.draft ? '<button class="btn ghost" data-act="settings-undo">되돌리기</button>' : ""}<button class="btn primary" data-act="save-settings"${S.draft ? "" : " disabled"}>${S.draft ? "바꾼 것 저장" : "저장"}</button></div>` : '<p class="note">보기 권한이라 바꿀 수 없어요.</p>'}
      </section>
      <section class="card"><h2>답 목표</h2>
        <p class="hint">문의 종류마다 이 시간 안에 첫 답을 하려고 해요. 목록과 홈의 남은 시간, 놓친 문의가 이걸 따라요. 1~168시간이에요.</p>
        ${SLA_NAMES.map(([k, label]) => stepper(k, label, sla[k], "시간", edit, "sla-step")).join("")}
        ${edit ? `<div class="actions">${S.slaDraft ? '<button class="btn ghost" data-act="sla-undo">되돌리기</button>' : ""}<button class="btn primary" data-act="save-sla"${S.slaDraft ? "" : " disabled"}>저장</button></div>` : ""}
      </section>` : ""}
      ${pushCard()}
      ${prefsCard()}
      ${S.me?.owner ? launchCard() : ""}
      <section class="card"><h2>앱으로 설치</h2>
        <p class="hint">${S.installed ? "이 기기에는 앱으로 설치돼 있어요." : "홈 화면에 두면 앱처럼 바로 열리고, 주소창 없이 넓게 보여요."}</p>
        <div class="actions start">${S.installPrompt ? `<button class="btn primary" data-act="install">${icon(P.install)}지금 설치</button>` : ""}
          <a class="btn" href="/admin/install">설치 방법 보기</a></div></section>
      ${S.me?.owner ? `<section class="card"><h2>서버 상태</h2>${S.server ? healthList(S.server) : skeleton(1)}
        <p class="meta">회사 ${S.overview?.companies ?? 0} · 쓰는 규칙 ${S.overview?.rules ?? 0} · 제보 ${S.overview?.reports ?? 0} · 앱 판 ${esc(BUILD)}</p></section>` : ""}
      <section class="card"><h2>단축키</h2><p class="hint">키보드가 있으면 <span class="kbd">?</span>로 단축키를, <span class="kbd">Ctrl</span>+<span class="kbd">K</span>로 찾기·이동을 열어요.</p>
        <div class="actions start"><button class="btn" data-act="shortcuts">단축키 보기</button></div></section>
      </div>`;
  }

  /** [ADMIN-19] 알림 on this device: which news, quiet hours, a test. */
  function pushCard() {
    const d = S.data.push;
    const supported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
    const head = '<section class="card"><h2>이 기기 알림</h2>';
    if (!supported) {
      return `${head}<p class="hint">이 브라우저는 알림을 받을 수 없어요. 휴대폰이면 관리자 앱을 홈 화면에 설치한 뒤 열어 주세요(iPhone은 iOS 16.4 이상, 홈 화면에 추가한 앱에서만 돼요).</p></section>`;
    }
    if (!d) return `${head}${skeleton(1)}</section>`;
    if (!d.ready) {
      return `${head}<p class="hint">서버에 알림 키가 아직 없어요. ${S.me?.owner ? "README의 '관리자 앱 알림'대로 VAPID_PUBLIC_KEY와 VAPID_PRIVATE_KEY를 넣으면 켤 수 있어요." : "최고 관리자가 서버에 알림 키를 넣으면 켤 수 있어요."}</p></section>`;
    }
    const mine = d.subscriptions.find((x) => x.id === S.pushId) ?? null;
    const blocked = Notification.permission === "denied";
    const kinds = mine?.kinds ?? d.kinds.map((k) => k.key);
    const hours = Array.from({ length: 24 }, (_, h) => h);
    const quiet = mine?.quiet ?? null;
    return `${head}
      <p class="hint">새 문의, 급한 문제 신고, 신고로 가려진 글, 새 관리자 가입을 이 기기로 알려요. 내 권한으로 볼 수 있는 것만 와요.${d.subscriptions.length ? ` 지금 ${d.subscriptions.length}대에서 받고 있어요.` : ""}</p>
      ${blocked ? '<p class="note warn">이 브라우저에서 알림을 막아 두었어요. 브라우저 설정 › 사이트 설정 › 알림에서 허용한 뒤 다시 눌러 주세요.</p>' : ""}
      <form data-form="push">
        ${d.kinds.map((k) => `<label class="check-row"><input type="checkbox" name="kind" value="${k.key}"${kinds.includes(k.key) ? " checked" : ""}>${esc(k.label)}</label>`).join("")}
        <label class="check-row"><input type="checkbox" name="quiet"${quiet ? " checked" : ""}>방해 금지 시간</label>
        <div class="field"><label class="sr" for="q-from">부터</label><select id="q-from" name="from">${hours.map((h) => `<option value="${h}"${(quiet?.from ?? 23) === h ? " selected" : ""}>${h}시부터</option>`).join("")}</select>
          <label class="sr" for="q-to">까지</label><select id="q-to" name="to">${hours.map((h) => `<option value="${h}"${(quiet?.to ?? 7) === h ? " selected" : ""}>${h}시까지</option>`).join("")}</select></div>
        <p class="help">한국 시간이에요. 이 시간에는 알림을 보내지 않아요(시험 알림은 빼고).</p>
        <div class="actions start">${mine ? `<button class="btn primary" type="submit">저장</button><button class="btn" type="button" data-act="push-test">시험 알림</button>
          <button class="btn danger" type="button" data-act="push-off">이 기기 끄기</button>` : `<button class="btn primary" type="submit"${blocked ? " disabled" : ""}>이 기기에서 알림 켜기</button>`}</div>
      </form></section>`;
  }

  /** [ADMUX-03][ADMUX-05] What this device remembers: colours, text size, the bottom tabs, sounds, saved views. */
  function prefsCard() {
    const p = S.prefs;
    const items = NAV.filter(visible).filter((n) => n.key !== "home");
    const tabs = L.bottomTabs(NAV.filter(visible), p.tabs, "home").tabs;
    const opt = (v, label, cur) => `<option value="${v}"${cur === v ? " selected" : ""}>${label}</option>`;
    return `<section class="card prefs"><h2>이 기기</h2><p class="hint">이 기기와 브라우저에만 남아요. 다른 관리자에게는 보이지 않아요.</p>
      <div class="fieldset"><label for="pref-theme">화면 색</label><div class="field"><select id="pref-theme" data-change="pref-theme">${opt("system", "기기 설정을 따라요", p.theme)}${opt("light", "밝게", p.theme)}${opt("dark", "어둡게", p.theme)}</select></div></div>
      <div class="fieldset"><label for="pref-size">글자 크기</label><div class="field"><select id="pref-size" data-change="pref-size">${opt("small", "작게", p.size)}${opt("normal", "보통", p.size)}${opt("large", "크게", p.size)}</select></div></div>
      <p class="label">휴대폰 아래 탭(홈 말고 3개까지)</p>
      <div class="chips" role="group" aria-label="아래 탭">${items.map((n) => `<label class="chip pick"><input type="checkbox" data-change="pref-tab" value="${n.key}"${tabs.includes(n.key) ? " checked" : ""}> ${esc(n.label)}</label>`).join("")}</div>
      <label class="switch"><input type="checkbox" data-change="pref-sound"${p.sound ? " checked" : ""}><span class="track" aria-hidden="true"></span><span>새 문의가 오면 작은 소리</span></label>
      <label class="switch"><input type="checkbox" data-change="pref-next"${p.next ? " checked" : ""}><span class="track" aria-hidden="true"></span><span>문의를 닫으면 다음 문의 열기</span></label>
      <p class="label">저장한 보기</p>
      ${p.views.length ? `<ul class="rows">${p.views.map((v, i) => `<li class="item"><a class="row-main" href="${esc(v.hash)}"><b>${esc(v.name)}</b><small>${esc(v.hash)}</small></a>
        <button class="btn small ghost" data-act="view-remove" data-i="${i}">지우기</button></li>`).join("")}</ul>` : '<p class="hint">목록 화면에서 찾기·이동(Ctrl+K)의 "지금 보기 저장"으로 거른 목록을 저장해 두고 한 번에 열어요.</p>'}
    </section>`;
  }

  /**
   * [MON-10] 정식 출시: the switch every app reads once a day. On, the app charges for 프로, shows the ad
   * banner and gives phones installed before today the thank-you months. Off, everything is free again.
   */
  function launchCard() {
    const l = S.data.launch;
    if (!l) return '<section class="card"><h2>정식 출시</h2>' + skeleton(1) + "</section>";
    return `<section class="card"><div class="card-head"><h2>정식 출시 (결제·광고)</h2>
        <span class="pill ${l.billingLive ? "ok" : "warn"}">${l.billingLive ? "켜짐" : "꺼짐"}</span></div>
      <p class="hint">${l.billingLive ? `${esc(day(l.launchedAt))}에 켰어요. 앱은 하루 안에 따라와요. 끄면 다시 모두 무료가 돼요.`
        : "켜기 전에는 모든 기능이 무료이고 광고가 없어요. 켜면 앱이 하루 안에 결제·광고·프로 잠금을 시작해요."}</p>
      <dl class="kv"><dt>Play 구매 확인</dt><dd>${l.play ? "연결됨 (PLAY_SERVICE_ACCOUNT)" : "꺼짐: 휴대폰의 Play 결제로만 판단, 체험 동의가 없어도 결제를 멈출 수 없어요"}</dd>
        <dt>구독 중</dt><dd>${l.subscribers}명 (서버가 확인한 수)</dd></dl>
      <form data-form="launch">
        <div class="fieldset"><label for="thanks">테스트 참여자 감사 보상</label><input id="thanks" name="thanks" type="number" min="0" max="12" value="${l.thanksMonths}">
          <span class="help">정식 출시 전에 설치한 휴대폰에 출시 날부터 드릴 프로 개월 수</span></div>
        <div class="fieldset"><label for="free">모두에게 무료로 연 프로 기능</label><input id="free" name="free" value="${esc(l.freeFeatures.join(", "))}" placeholder="예: MONEY_STATS">
          <span class="help">프로 기능을 모두에게 열 때만 적어요. 연 기능을 다시 잠그지는 않아요(이미 준 것은 거두지 않음).</span></div>
        <div class="actions"><button class="btn" type="submit">저장</button></div></form>
      <div class="actions start"><button class="btn ${l.billingLive ? "danger" : "primary"}" data-act="launch-switch" data-key="${l.billingLive ? "off" : "on"}">${l.billingLive ? "정식 출시 끄기" : "정식 출시 켜기"}</button></div>
    </section>`;
  }

  function stepper(key, label, value, unit, edit, act = "step") {
    return `<div class="stepper"><span>${label}</span><div class="stepper-ctl">
      <button class="icon-btn" data-act="${act}" data-key="${key}" data-d="-1" aria-label="${label} 줄이기"${edit ? "" : " disabled"}>−</button>
      <output aria-live="polite">${value}${unit}</output>
      <button class="icon-btn" data-act="${act}" data-key="${key}" data-d="1" aria-label="${label} 늘리기"${edit ? "" : " disabled"}>+</button></div></div>`;
  }

  function accountView() {
    const m = S.me;
    if (!m) return skeleton();
    const done = S.overview?.doneToday ?? 0;
    return `<div class="grid two">
      <section class="card"><div class="card-head"><h2>${esc(m.name)}</h2>${m.owner ? '<span class="pill gold">최고 관리자</span>' : ""}</div>
        ${m.owner ? '<p class="hint">Cloudflare의 ADMIN_PASSWORD로 로그인했어요. 모든 화면을 쓰고 관리자를 초대·승인해요.</p>'
          : `<dl class="kv"><dt>메일</dt><dd>${esc(m.email)}</dd></dl><p class="label">권한</p><div class="perm-sum">${permPills(m.perms, m.areas ?? {})}</div>`}
      </section>
      ${can("audit") ? `<section class="card"><h2>내가 한 일</h2><p class="hint">오늘 ${done}건 처리했어요(로그인은 빼고).</p>
        <div class="actions start"><a class="btn" href="${L.routeHash("audit", null, { actor: "me" })}">내 활동 기록 보기</a></div></section>`
        : `<section class="card"><h2>내가 한 일</h2><p class="hint">오늘 ${done}건 처리했어요(로그인은 빼고).</p></section>`}
      ${m.owner ? `<section class="card"><h2>비밀번호</h2><p class="hint">최고 관리자 비밀번호는 Cloudflare 대시보드의 ADMIN_PASSWORD에서 바꿔요. 바꾸면 이 기기도 다시 로그인해요.</p></section>`
        : `<form class="card" data-form="password"><h2>비밀번호 바꾸기</h2>
          <div class="fieldset"><label for="pw-now">지금 비밀번호</label>${pwInput("pw-now", "current", "current-password")}</div>
          <div class="fieldset"><label for="pw-new">새 비밀번호</label>${pwInput("pw-new", "next", "new-password", ' minlength="10" data-strength="pw-meter"')}
            <meter id="pw-meter" class="strength" min="0" max="4" low="2" high="3" optimum="4" value="0" aria-label="비밀번호 안전도"></meter>
            <span class="help">10자 이상. 바꾸면 다른 기기의 로그인은 끝나요.</span></div>
          <div class="actions"><button class="btn primary" type="submit">바꾸기</button></div></form>`}
      <section class="card"><h2>내 휴대폰 밤낮 연결</h2>
        <p class="hint">관리자 휴대폰은 프로를 무료로 써요. 코드를 만들어 밤낮 앱 › 설정 › 프로 › '관리자 연결'에 10분 안에 넣어요${m.owner ? "" : ". 최고 관리자가 승인하면 시작돼요"}. 한 사람당 2대까지예요.</p>
        <div class="actions start"><button class="btn primary" data-act="link-code">연결 코드 만들기</button></div></section>
      <section class="card"><h2>로그인</h2><p class="hint">로그인은 쓰는 동안 저절로 이어지고, 7일 동안 안 쓰면 끝나요(길어도 30일). 휴대폰을 잃어버렸다면 모든 기기에서 로그아웃해요.</p>
        <div class="actions start"><button class="btn" data-act="logout">${icon(P.logout)}로그아웃</button>
          <button class="btn danger" data-act="logout-all">모든 기기에서 로그아웃</button></div></section>
      </div>`;
  }

  /** [ADMUX-03] A password box with a 보기 button beside it. */
  const pwInput = (id, name, auto, extra = "") => `<div class="pw"><input id="${id}" name="${name}" type="password" autocomplete="${auto}" required${extra}>
    <button class="btn small ghost show" type="button" data-act="pw-show" data-for="${id}" aria-pressed="false" aria-label="비밀번호 보기">보기</button></div>`;

  function moreView() {
    const items = NAV.filter(visible);
    const tabs = L.bottomTabs(items, S.prefs.tabs, "more").tabs;
    const rest = items.filter((n) => !tabs.includes(n.key));
    const o = S.overview ?? {};
    return `<ul class="rows">${[...rest, { key: "account", label: "내 계정" }].map((n) => `<li><a class="row" href="${href(n.key)}">
      ${icon(P[n.key])}<span class="row-main"><b>${esc(n.label)}</b></span>${n.badge && n.badge(o) ? `<i class="badge">${n.badge(o)}</i>` : ""}${icon(P.chevron)}</a></li>`).join("")}</ul>
      <div class="actions start"><button class="btn wide" data-act="logout">${icon(P.logout)}로그아웃</button></div>`;
  }

  // ---- sign in, invite and install pages ----

  function loginView() {
    const h = S.health;
    const owner = S.prefs.loginMode === "owner";
    const usable = !owner || !h || (h.database && h.password);
    return `<main class="gate">
      <div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>밤낮 관리자</h1><p>제보, 문의, 회사 규칙을 관리해요.</p></div>
      <div class="segmented" role="group" aria-label="로그인 방법">${seg("login-mode", "account", S.prefs.loginMode, "관리자 계정")}${seg("login-mode", "owner", S.prefs.loginMode, "최고 관리자")}</div>
      <form class="card" data-form="login">
        ${owner ? "" : `<div class="fieldset"><label for="email">메일</label><input id="email" name="email" type="email" autocomplete="username" inputmode="email" required></div>`}
        <div class="fieldset"><label for="password">${owner ? "최고 관리자 비밀번호" : "비밀번호"}</label>
          ${pwInput("password", "password", "current-password", usable ? "" : " disabled")}</div>
        ${usable ? "" : '<p class="hint">서버가 비밀번호를 읽으면 입력할 수 있어요. 아래 서버 준비를 마친 뒤 새로 고쳐 주세요.</p>'}
        <p class="error" role="alert">${esc(S.error)}</p>
        <button class="btn primary wide" type="submit"${usable ? "" : " disabled"}>로그인</button>
      </form>
      ${owner ? "" : '<p class="alt">계정은 최고 관리자가 보낸 초대 링크로만 만들 수 있어요.</p>'}
      ${owner && h && !(h.database && h.password && h.salt) ? `<section class="card"><h2>서버 준비</h2>${healthList()}</section>` : ""}
      ${S.installed ? "" : '<p class="alt"><a href="/admin/install">휴대폰에 앱으로 설치하기</a></p>'}
    </main>`;
  }


  function installSteps() {
    const ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
    return ios ? `<ol class="steps"><li>Safari에서 이 화면을 열어요.</li><li>아래 공유 버튼(□↑)을 눌러요.</li><li>홈 화면에 추가를 눌러요.</li></ol>`
      : `<ol class="steps"><li>Chrome이나 삼성 인터넷에서 이 화면을 열어요.</li><li>${S.installPrompt ? "아래 앱 설치 버튼을 누르거나, " : ""}메뉴(⋮)에서 앱 설치 또는 홈 화면에 추가를 눌러요.</li>
          <li>홈 화면의 밤낮 관리 아이콘으로 열어요.</li></ol>`;
  }

  function installView() {
    return `<main class="gate">
      <div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>밤낮 관리자 설치</h1><p>스토어 없이 이 주소에서 바로 설치해요.</p></div>
      <section class="card">${S.installed ? '<p class="note ok">이 기기에는 이미 앱으로 설치돼 있어요.</p>' : installSteps()}
        ${S.installPrompt ? `<button class="btn primary wide" data-act="install">${icon(P.install)}앱 설치</button>` : ""}</section>
      <section class="card"><h2>처음이라면</h2><p class="hint">관리자 계정은 최고 관리자가 보낸 초대 링크로 만들어요. 초대 메일의 링크를 먼저 열어 주세요.</p></section>
      <p class="alt"><a href="/admin/">로그인 화면으로</a></p></main>`;
  }

  function joinView() {
    const j = S.join;
    if (!j) return '<div class="center"><div class="spinner" role="progressbar" aria-label="불러오는 중"></div></div>';
    if (j.error) {
      return `<main class="gate"><div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>초대 링크를 열 수 없어요</h1></div>
        <section class="card"><p>${esc(ERRORS[j.error] ?? ERRORS.invite)}</p></section><p class="alt"><a href="/admin/">로그인 화면으로</a></p></main>`;
    }
    if (j.done) {
      return `<main class="gate"><div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>계정을 만들었어요</h1>
        <p>최고 관리자가 승인하면 로그인할 수 있어요.</p></div>
        <section class="card"><ol class="steps"><li class="done">초대 메일 확인</li><li class="done">계정 만들기</li><li>앱 설치</li><li>승인 뒤 로그인</li></ol></section>
        <section class="card"><h2>앱으로 설치</h2>${S.installed ? '<p class="note ok">이 기기에는 이미 설치돼 있어요.</p>' : installSteps()}
          ${S.installPrompt ? `<button class="btn primary wide" data-act="install">${icon(P.install)}앱 설치</button>` : ""}</section>
        <p class="alt"><a href="/admin/">로그인 화면으로</a></p></main>`;
    }
    return `<main class="gate">
      <div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>관리자 초대</h1><p>${esc(j.email)}로 초대받았어요.</p></div>
      <section class="card"><ol class="steps"><li class="done">초대 메일 확인</li><li>계정 만들기</li><li>앱 설치</li><li>승인 뒤 로그인</li></ol>
        <p class="label">받을 권한</p><div class="perm-sum">${permPills(j.perms, j.areas)}</div></section>
      <form class="card" data-form="join">
        <div class="fieldset"><label for="j-email">메일</label><input id="j-email" type="email" value="${esc(j.email)}" readonly autocomplete="username"></div>
        <div class="fieldset"><label for="j-name">이름</label><input id="j-name" name="name" maxlength="30" required autocomplete="name" placeholder="다른 관리자에게 보일 이름"></div>
        <div class="fieldset"><label for="j-pw">비밀번호</label>${pwInput("j-pw", "password", "new-password", ' minlength="10" data-strength="j-meter"')}
          <meter id="j-meter" class="strength" min="0" max="4" low="2" high="3" optimum="4" value="0" aria-label="비밀번호 안전도"></meter>
          <span class="help">10자 이상, 메일 주소와 다르게요.</span></div>
        <div class="fieldset"><label for="j-pw2">비밀번호 한 번 더</label>${pwInput("j-pw2", "password2", "new-password", ' minlength="10"')}</div>
        ${j.mail ? `<div class="fieldset"><label for="j-code">메일 확인 코드</label><div class="field"><input id="j-code" name="code" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" required
          autocomplete="one-time-code"><button class="btn" type="button" data-act="join-code">${j.codeSent ? "다시 보내기" : "코드 보내기"}</button></div>
          <span class="help">${j.codeSent ? `${esc(j.email)}로 6자리 코드를 보냈어요. 15분 동안 쓸 수 있어요.` : "버튼을 누르면 초대받은 메일로 코드가 가요."}</span></div>` : ""}
        <p class="error" role="alert">${esc(S.error)}</p>
        <button class="btn primary wide" type="submit">계정 만들기</button>
      </form></main>`;
  }

  // ---- dialogs ----

  function openDialog(html, size = "") {
    dialog.className = size;
    dialog.innerHTML = html;
    if (!dialog.open) dialog.showModal();
    dialog.querySelector("input:not([type=radio]):not([type=hidden]):not([readonly]),textarea")?.focus();
  }

  function confirmBox(title, text, label, then, tone = "danger") {
    pendingConfirm = then;
    openDialog(`<form data-form="confirm"><h2>${esc(title)}</h2><p>${esc(text)}</p>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn ${tone}" type="submit">${esc(label)}</button></div></form>`);
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
      ${months && can("rewards", "edit") ? `<label class="switch"><input type="checkbox" name="reward" checked><span class="track" aria-hidden="true"></span>
        <span>앱에 넣을 방식이면 프로 ${months}개월 보상 기록</span></label>` : ""}
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">답하기</button></div></form>`);
  }

  function openNewCompany() {
    openDialog(`<form data-form="new-company"><h2>회사 만들기</h2><p>제보가 오기 전에 이름과 다른 이름을 정해 둘 때 써요.</p>
      <label for="new-name">회사 이름</label><input id="new-name" name="name" maxlength="40" required>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">만들기</button></div></form>`);
  }

  function openPaid(id, months = 3) {
    // [MON-08] 회사 방식 채택 3개월, 제안 1개월, 큰 개선 6개월~1년 (수익화 확정 4장 7).
    const choices = [[1, "제안 채택"], [3, "회사 방식 채택"], [6, "큰 개선"], [12, "아주 큰 개선"]];
    openDialog(`<form data-form="paid" data-id="${id}"><h2>프로 이용권 드리기</h2><p>보낸 분 휴대폰에 이용권이 생겨요. 이미 받은 이용권 뒤에 이어져요.</p>
      <p class="label">기간</p><div class="chips" role="radiogroup" aria-label="기간">${choices.map(([n, why]) => `<label class="chip pick"><input type="radio" name="months" value="${n}"${Number(months) === n ? " checked" : ""}> ${n}개월 · ${why}</label>`).join("")}</div>
      <label for="memo">메모 (선택)</label><input id="memo" name="memo" maxlength="200" placeholder="예: 근태 방식 제보 채택">
      <p class="help">보낸 분의 이름이나 연락처는 적지 마세요.</p>
      <div class="actions"><button class="btn" type="button" data-act="close">취소</button><button class="btn primary" type="submit">드리기</button></div></form>`);
  }

  /** [ADMUX-01] Signed out while working: sign in again in a box over the screen, which stays as it was. */
  function openRelogin() {
    if (S.relogin) return;
    S.relogin = true;
    const owner = S.me?.owner;
    openDialog(`<form data-form="relogin"><h2>다시 로그인해 주세요</h2><p>로그인이 끝났어요. 적던 글과 화면은 그대로 있어요.</p>
      ${owner ? "" : `<div class="fieldset"><label for="re-email">메일</label><input id="re-email" name="email" type="email" autocomplete="username" value="${esc(S.me?.email ?? "")}" required></div>`}
      <div class="fieldset"><label for="re-pw">${owner ? "최고 관리자 비밀번호" : "비밀번호"}</label>${pwInput("re-pw", "password", "current-password")}</div>
      <p class="error" role="alert" id="re-error"></p>
      <div class="actions"><button class="btn" type="button" data-act="relogin-out">로그인 화면으로</button><button class="btn primary" type="submit">로그인</button></div></form>`);
  }

  /** [ADMUX-04] 찾기·이동: screens, saved views, the questions in the list, and a few steps, by typing a few letters. */
  /** [ADMUX-04] The last details opened (this visit), first in the palette. */
  function rememberOpened() {
    const { name, id } = S.route;
    if (!id || !["inquiries", "dev", "companies"].includes(name) || !hasData()) return;
    const q = name === "inquiries" ? S.data.inquiry : null;
    const label = q ? `${KIND_NAMES[q.kind] ?? "문의"} · ${[...(q.messages?.[0]?.text ?? "")].slice(0, 40).join("")}` : heading().title;
    const to = L.routeHash(name, id, {});
    S.recent = [{ label, hint: "최근 연 것", kind: "최근", to }, ...S.recent.filter((x) => x.to !== to)].slice(0, 6);
  }

  function paletteEntries() {
    const out = S.recent.filter((x) => x.to !== L.routeHash(S.route.name, S.route.id, {}));
    out.push(...NAV.filter(visible).map((n) => ({ label: n.label, hint: "화면", kind: "화면", to: href(n.key) })));
    out.push({ label: "내 계정", hint: "화면 account", kind: "화면", to: href("account") });
    for (const v of S.prefs.views) out.push({ label: v.name, hint: "저장한 보기", kind: "보기", to: v.hash });
    for (const q of S.data.inquiries?.inquiries ?? []) out.push({ label: q.preview.slice(0, 60) || "(내용 없음)", hint: `문의 ${KIND_NAMES[q.kind] ?? ""} ${q.topicName ?? ""}`, kind: "문의", to: href("inquiries", q.id) });
    for (const x of S.data.devList?.items ?? []) out.push({ label: x.title, hint: "개발사항", kind: "개발사항", to: href("dev", x.id) });
    for (const c of S.data.list?.companies ?? []) out.push({ label: c.name, hint: "회사", kind: "회사", to: href("companies", c.id) });
    out.push(
      { label: "새로 고침", hint: "refresh", kind: "할 일", act: "refresh" },
      { label: "단축키 보기", hint: "keys", kind: "할 일", act: "shortcuts" },
      { label: "화면 색 바꾸기(밝게·어둡게)", hint: "테마 theme dark light", kind: "할 일", act: "theme-next" },
      { label: "로그아웃", hint: "logout", kind: "할 일", act: "logout" },
    );
    if (!S.route.id && (BIND[S.route.name] || S.route.name === "audit")) out.push({ label: "지금 보기 저장", hint: "save view", kind: "할 일", act: "view-save" });
    if (can("inquiries", "edit")) out.push({ label: "큐 모드로 답하기", hint: "queue", kind: "할 일", act: "queue-start" });
    return out;
  }

  function openPalette() {
    S.palette = { q: "", at: 0 };
    openDialog(`<div class="box" role="combobox" aria-expanded="true" aria-haspopup="listbox" aria-owns="pal-list">
      <label class="sr" for="pal">찾기·이동</label><input id="pal" type="search" autocomplete="off" placeholder="화면, 문의, 회사, 할 일 찾기" aria-controls="pal-list" aria-autocomplete="list">
      <ul class="rows" id="pal-list" role="listbox" aria-label="찾은 것"></ul>
      <p class="hint">↑↓로 고르고 Enter로 열어요. Esc로 닫아요.</p></div>`, "palette");
    paintPalette();
  }

  function paintPalette() {
    const box = document.getElementById("pal-list");
    if (!box || !S.palette) return;
    const list = L.paletteFilter(paletteEntries(), S.palette.q, 12);
    S.palette.list = list;
    S.palette.at = Math.min(S.palette.at, Math.max(0, list.length - 1));
    box.innerHTML = list.length ? list.map((e, i) => `<li><button class="row" type="button" role="option" id="pal-${i}" data-act="palette-go" data-i="${i}" aria-selected="${i === S.palette.at}">
      <span class="row-main"><b>${esc(e.label)}</b></span><small class="kind">${esc(e.kind)}</small></button></li>`).join("") : '<li class="meta">찾는 것이 없어요.</li>';
    document.getElementById("pal")?.setAttribute("aria-activedescendant", list.length ? `pal-${S.palette.at}` : "");
  }

  function paletteGo(i) {
    const e = S.palette?.list?.[i];
    if (!e) return;
    dialog.close();
    S.palette = null;
    if (e.act) ACTIONS[e.act]?.({});
    else if (location.hash === e.to) load();
    else location.hash = e.to;
  }

  function openShortcuts() {
    const items = NAV.filter(visible);
    openDialog(`<div class="box"><h2>단축키</h2>
      <div class="keys">${L.KEYS.map(([k, what]) => `<span class="kbd">${esc(k)}</span><span>${esc(what)}</span>`).join("")}
        ${items.slice(0, 9).map((n, i) => `<span class="kbd">${i + 1}</span><span>${esc(n.label)} (문의를 연 동안은 자주 쓰는 답)</span>`).join("")}
        <span class="kbd">R</span><span>새로 고침 (문의를 연 동안은 답 칸으로)</span></div>
      <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`, "wide");
  }


  // ---- what taps do ----

  /**
   * Runs one step and says how it went. [undo], given the answer, is what 되돌리기 on the note does.
   * A form being sent is emptied once it went through, with what was kept of it in the browser.
   */
  async function act(work, done, undo) {
    try {
      const r = await work();
      const form = S.submitting;
      if (form?.isConnected && !form.closest("dialog")) {
        for (const id of KEEP) if (form.querySelector(`#${id}`)) writeDraft(id, "");
        form.reset();
      }
      if (done) toast(typeof done === "function" ? done(r) : done, undo ? () => undo(r) : undefined);
      return r;
    } catch (e) {
      fail(e);
      return null;
    }
  }

  async function setRule(id, withdrawn) {
    const c = await act(() => api(`/rules/${id}`, "POST", { withdrawn }), withdrawn ? "규칙을 내렸어요. 회사 찾기에서 바로 빠져요" : "규칙을 다시 올렸어요");
    if (c && S.route.name === "companies") S.data.company = c;
    load();
  }

  async function setReward(id, status, memo, months) {
    const body = { status, ...(memo === undefined ? {} : { memo }), ...(months ? { months } : {}) };
    await act(() => api(`/rewards/${id}`, "POST", body),
      (r) => status === "paid" ? (r?.deferred ? `Play 다음 결제를 ${r.months}개월 미뤘어요` : `프로 ${r?.months ?? months}개월 이용권을 드렸어요`)
        : status === "void" ? "보상을 취소했어요" : "대기로 되돌렸어요",
      // [ADMUX-01] 취소 is taken back in one tap; 지급함 is not (the pass is on the phone already).
      status === "void" ? () => setReward(id, "owed") : undefined);
    load();
  }

  /** [ADMUX-04] 큐 모드: the next waiting question, or the end of the queue. */
  function queueNext(skip = false) {
    const qm = S.queue;
    if (!qm) return;
    qm.at += 1;
    if (skip) qm.skipped += 1;
    if (qm.at >= qm.ids.length) {
      const n = qm.ids.length - qm.skipped;
      S.queue = null;
      toast(`큐를 마쳤어요. ${n}건 처리${qm.skipped ? `, ${qm.skipped}건 건너뜀` : ""}`);
      go("inquiries");
      return;
    }
    go("inquiries", qm.ids[qm.at]);
  }

  /** [ADMIN-16] 답 쓰는 중, so another admin opening the same question sees it; at most every 20 seconds. */
  function pingTyping(on) {
    if (S.route.name !== "inquiries" || !S.route.id || !can("inquiries", "edit")) return;
    const now = Date.now();
    if (on && now - typingSent < 20000) return;
    if (!on && !typingSent) return;
    typingSent = on ? now : 0;
    api(`/inquiries/${S.route.id}/typing`, "POST", { on }).catch(() => {});
  }

  /** [ADMIN-16] The step for the picked questions; skipped ones (someone else's) are counted. */
  async function inquiryBulk(action, extra = {}) {
    const ids = [...S.inquiryPick];
    const r = await act(() => api("/inquiries/bulk", "POST", { action, ids, ...extra }),
      (x) => `${x.done}개를 처리했어요${x.skipped ? `. ${x.skipped}개는 건너뛰었어요(다른 관리자가 맡았거나 이미 같은 답)` : ""}`);
    if (r) {
      S.inquiryPick = null;
      S.data.inquiries = null;
      load();
    }
    return r;
  }

  /** [ADMIN-17] The activity lines as a CSV file Excel opens (UTF-8 with BOM, formulas made plain text). */
  /**
   * [ADMUX-07] The list on screen as a CSV for a spreadsheet. Only the columns without personal details: no
   * question text, contact, memo or install id, just what, when and how it stands.
   */
  const CSV_KINDS = {
    audit: () => [S.data.audit?.entries, S.data.audit?.more, "audit", [["시각", (e) => new Date(e.at).toLocaleString("ko-KR")], ["누가", (e) => e.actor || "앱 사용자·시스템"],
      ["한 일", (e) => ACTION_NAMES[e.action] ?? e.action], ["대상", (e) => e.label || e.target || ""], ["내용", (e) => e.detail ?? ""]]],
    inquiries: () => [S.data.inquiries?.inquiries, S.data.inquiries?.next, "inquiries", [["받은 때", (q) => new Date(q.createdAt).toLocaleString("ko-KR")],
      ["종류", (q) => KIND_NAMES[q.kind] ?? q.kind], ["주제", (q) => q.topicName ?? ""], ["상태", (q) => STATUS_NAMES[q.status ?? S.inquiryStatus] ?? ""],
      ["맡은 사람", (q) => q.assignee?.name ?? ""], ["앱 버전", (q) => q.appVersion ?? ""], ["나도 그래요", (q) => q.count ?? 1], ["개발사항", (q) => (q.dev ? "있음" : "")]]],
    dev: () => [S.data.devList?.items, S.data.devList?.next, "dev", [["제목", (x) => x.title], ["처리", (x) => DEV_NAMES[x.status] ?? x.status],
      ["연결된 문의", (x) => x.links ?? 0], ["답 대기", (x) => x.waiting ?? 0], ["맡은 사람", (x) => x.assignee?.name ?? ""], ["반영 버전", (x) => x.releasedIn ?? ""],
      ["고친 때", (x) => new Date(x.updatedAt).toLocaleString("ko-KR")]]],
    rewards: () => [S.data.rewards?.rewards, S.data.rewards?.next, "rewards", [["회사", (w) => w.company?.name ?? ""], ["부서", (w) => w.dept ?? ""],
      ["개월", (w) => w.months], ["종류", (w) => (w.ask ? "앱에 없는 방식" : "채택")], ["상태", (w) => ({ owed: "드릴 보상", paid: "지급함", void: "취소" })[w.status] ?? w.status],
      ["생긴 날", (w) => day(w.createdAt)], ["지급한 날", (w) => (w.paidAt ? day(w.paidAt) : "")]]],
  };

  const csvButton = (kind, rows) => `<button class="btn small" data-act="csv" data-kind="${kind}"${rows?.length ? "" : " disabled"}>${icon(P.install)}CSV로 받기</button>`;

  function downloadCsv(kind = "audit") {
    const [rows, more, name, cols] = CSV_KINDS[kind]?.() ?? [];
    if (!rows?.length) return;
    const url = URL.createObjectURL(new Blob([L.csv(rows, cols)], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    // ASCII file names: some browsers save a Korean one as "download".
    a.download = `bamnat-${name}-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast(`${rows.length}줄을 받았어요${more ? ". 더 보기로 늘린 만큼만 들어가요" : ""}`);
  }

  /** [ADMIN-19] This browser's subscription id, as the server makes it (sha256 of push|endpoint). */
  async function pushIdOf(endpoint) {
    const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`push|${endpoint}`));
    return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
  }

  async function currentPush() {
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.getSubscription();
      S.pushId = sub ? await pushIdOf(sub.endpoint) : null;
      return sub;
    } catch {
      S.pushId = null;
      return null;
    }
  }

  /** [ADMUX-08] A short tone for a new question, made here (no sound file to fetch). */
  function chime() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.frequency.value = 880;
      g.gain.setValueAtTime(0.08, ctx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.35);
      o.connect(g).connect(ctx.destination);
      o.start();
      o.stop(ctx.currentTime + 0.4);
    } catch {
      // No sound here.
    }
  }

  /** Filters changed on a list: the address follows, the old answer is dropped and the list comes again. */
  function refilter(dataKey) {
    if (dataKey) S.data[dataKey] = null;
    syncUrl();
    render({ keepScroll: true });
    load({ overview: false });
  }

  async function copy(text, done = "복사했어요") {
    try {
      await navigator.clipboard.writeText(text);
      toast(done);
    } catch {
      document.getElementById("inv-link")?.select();
      toast("링크를 길게 눌러 복사해 주세요");
    }
  }

  const ACTIONS = {
    refresh: () => load(),
    close: () => dialog.close(),
    shortcuts: () => openShortcuts(),
    reload: () => location.reload(),
    palette: () => openPalette(),
    "palette-go": ({ i }) => paletteGo(Number(i)),
    "theme-next": () => {
      S.prefs.theme = { system: "light", light: "dark", dark: "system" }[S.prefs.theme] ?? "system";
      savePrefs();
      toast({ system: "기기 설정 색을 따라요", light: "밝게 보여요", dark: "어둡게 보여요" }[S.prefs.theme]);
    },
    "view-save": () => {
      const n = NAV.find((x) => x.key === S.route.name);
      const name = `${n?.label ?? S.route.name}${S.route.name === "inquiries" && S.inquiryQuery ? ` "${S.inquiryQuery}"` : ""} ${new Date().toLocaleDateString("ko-KR", { month: "numeric", day: "numeric" })}`;
      S.prefs.views = [...S.prefs.views.filter((v) => v.hash !== href(S.route.name)), { name, hash: href(S.route.name) }].slice(-12);
      savePrefs();
      toast("이 보기를 저장했어요. 홈과 찾기·이동(Ctrl+K)에서 바로 열어요");
    },
    "view-remove": ({ i }) => {
      S.prefs.views = S.prefs.views.filter((_, k) => k !== Number(i));
      savePrefs();
      render({ keepScroll: true });
    },
    "pw-show": ({ for: id }, el) => {
      const box = document.getElementById(id);
      if (!box) return;
      const show = box.type === "password";
      box.type = show ? "text" : "password";
      el.setAttribute("aria-pressed", String(show));
      el.textContent = show ? "숨기기" : "보기";
      el.setAttribute("aria-label", show ? "비밀번호 숨기기" : "비밀번호 보기");
    },
    "relogin-out": () => {
      dialog.close();
      signedOut();
    },
    // [ADMUX-04] 큐 모드
    "queue-start": async () => {
      const r = await act(() => api("/inquiries?status=open&sort=sla&limit=100"));
      const ids = (r?.inquiries ?? []).filter((q) => q.waiting && !(q.assignee && q.assignee.id !== S.me.id)).map((q) => q.id);
      if (!r) return;
      if (!ids.length) return toast("지금 답할 문의가 없어요. 다른 관리자가 맡은 것은 빼요");
      S.queue = { ids, at: 0, skipped: 0 };
      S.data.inquiries = { ...r, inquiries: r.inquiries.filter((q) => ids.includes(q.id)) };
      go("inquiries", ids[0]);
    },
    "queue-skip": () => queueNext(true),
    "queue-stop": () => {
      S.queue = null;
      toast("큐 모드를 끝냈어요");
      render({ keepScroll: true });
    },
    "queue-assign": async ({ id }) => {
      await act(() => api(`/inquiries/${id}/assign`, "POST", { to: "me" }), "내가 맡았어요", () => api(`/inquiries/${id}/assign`, "POST", { to: null }).then(() => load()));
      load();
    },
    // [ADMIN-16] 문의 여러 개
    "inquiry-pick": () => {
      S.inquiryPick = S.inquiryPick ? null : new Set();
      render({ keepScroll: true });
    },
    "inquiry-pick-all": () => {
      const items = S.data.inquiries?.inquiries ?? [];
      S.inquiryPick = S.inquiryPick.size === items.length ? new Set() : new Set(items.map((x) => x.id));
      render({ keepScroll: true });
    },
    "inquiry-bulk": ({ key }) => {
      if (!S.inquiryPick?.size) return;
      if (key === "reply") return openBulkReply();
      if (key === "link") return openBulkLink();
      const n = S.inquiryPick.size;
      if (key === "close") return confirmBox(`${n}개 문의를 닫을까요?`, "보낸 분 앱에서 닫힌 문의로 보여요. 다시 열 수 있어요.", "닫기", () => inquiryBulk("close"));
      return inquiryBulk(key);
    },
    "bulk-template": ({ i }) => {
      const t = templateList()[Number(i)];
      const box = document.getElementById("bulk-text");
      if (!t || !box) return;
      box.value = L.fillTemplate(t.text, { today: new Date().toLocaleDateString("ko-KR") });
      if (t.id > 0) dialog.querySelector('input[name="template"]').value = String(t.id);
      box.focus();
    },
    "inquiries-more": () => act(() => loadInquiries(undefined, true)).then(() => render({ keepScroll: true })),
    "dev-more": () => act(() => loadDev(undefined, true)).then(() => render({ keepScroll: true })),
    "rewards-more": async () => {
      const r = S.data.rewards;
      const next = await act(() => api(`/rewards?status=${S.rewardTab}${S.rewardQuery ? `&q=${encodeURIComponent(S.rewardQuery)}` : ""}&after=${r.next}`));
      if (next) S.data.rewards = { ...next, rewards: [...r.rewards, ...next.rewards] };
      render({ keepScroll: true });
    },
    "posts-more": async () => {
      const d = S.data.posts;
      const q = S.postQuery ? `&q=${encodeURIComponent(S.postQuery)}` : "";
      const next = await act(() => api(`/posts?status=${S.postFilter}${q}&after=${d.next}`));
      if (next) S.data.posts = { ...next, posts: [...d.posts, ...next.posts] };
      render({ keepScroll: true });
    },
    "rates-more": async () => {
      const d = S.data.rates;
      const q = S.postQuery ? `&q=${encodeURIComponent(S.postQuery)}` : "";
      const next = await act(() => api(`/rates?status=${S.rateFilter}${q}&after=${d.next}`));
      if (next) S.data.rates = { ...next, rates: [...d.rates, ...next.rates] };
      render({ keepScroll: true });
    },
    // [ADMIN-17]
    "audit-who": ({ id }) => {
      S.audit = { ...S.audit, actor: id };
      refilter("audit");
    },
    "audit-clear": () => {
      S.audit = { actor: "", kind: "", from: "", to: "" };
      refilter("audit");
    },
    "audit-csv": () => downloadCsv("audit"),
    csv: ({ kind }) => downloadCsv(kind),
    // [ADMUX-06]
    "sla-step": ({ key, d }) => {
      const s = { ...(S.slaDraft ?? S.overview?.sla ?? {}) };
      const by = (s[key] ?? 24) >= 48 ? 12 : (s[key] ?? 24) >= 12 ? 2 : 1;
      s[key] = Math.min(168, Math.max(1, (s[key] ?? 24) + Number(d) * by));
      S.slaDraft = s;
      render({ keepScroll: true });
    },
    "sla-undo": () => {
      S.slaDraft = null;
      render({ keepScroll: true });
    },
    "save-sla": async () => {
      const r = await act(() => api("/settings/sla", "POST", S.slaDraft), "답 목표를 저장했어요. 목록과 홈이 바로 따라요");
      if (r) {
        S.overview.sla = r.sla;
        S.slaDraft = null;
      }
      render({ keepScroll: true });
    },
    "settings-undo": () => {
      S.draft = null;
      render({ keepScroll: true });
    },
    // [ADMIN-19]
    "push-test": async () => {
      const r = await act(() => api("/me/push/test", "POST", {}));
      if (r) toast(r.sent ? `시험 알림을 ${r.sent}대로 보냈어요` : "보낼 기기가 없어요. 이 기기 알림을 다시 켜 주세요");
    },
    "push-off": async () => {
      const sub = await currentPush();
      const d = await act(() => api("/me/push/remove", "POST", sub ? { endpoint: sub.endpoint } : { id: S.pushId }), "이 기기 알림을 껐어요");
      try {
        await sub?.unsubscribe();
      } catch {
        // Already gone.
      }
      S.pushId = null;
      if (d) S.data.push = d;
      render({ keepScroll: true });
    },
    "template-edit": ({ id }) => openTemplates(id),
    "login-mode": ({ key }) => {
      // [ADMUX-03] The sign-in screen opens on the way chosen last time.
      S.prefs.loginMode = key;
      savePrefs();
      S.error = "";
      render();
    },
    "review-filter": ({ key }) => {
      S.reviewFilter = key;
      syncUrl();
      render({ keepScroll: true });
    },
    // [ADMUX-01] 채택 puts a rule in front of every user, so it is asked once.
    adopt: ({ company, setup }) => confirmBox("이 제보를 채택할까요?", "바로 회사 찾기에 나오고 같은 회사를 쓰는 분들 앱에 들어가요. 나중에 규칙을 내릴 수는 있어요.", "채택",
      () => act(() => api(`/groups/${company}/${setup}/adopt`, "POST"), "채택했어요. 이제 회사 찾기에 나와요").then(() => load()), "primary"),
    reject: ({ company, setup }) => openReject(company, setup),
    answer: ({ id }) => openAnswer(id),
    "new-company": () => openNewCompany(),
    "remove-alias": async ({ name }) => {
      const c = await act(() => api(`/companies/${S.route.id}/aliases/remove`, "POST", { name }));
      if (c) S.data.company = c;
      render({ keepScroll: true });
    },
    "withdraw-rule": ({ id }) => confirmBox("이 규칙을 내릴까요?", "회사 찾기에서 바로 빠져요. 나중에 다시 올릴 수 있어요.", "내리기", () => setRule(id, true)),
    "restore-rule": ({ id }) => setRule(id, false),
    "merge-into": ({ id, name }) => confirmBox(`${S.data.company.name}을 ${name}에 합칠까요?`, "제보와 규칙이 모두 옮겨지고 되돌릴 수 없어요.", "합치기", async () => {
      const c = await api(`/companies/${S.route.id}/merge`, "POST", { into: id });
      S.data.company = c;
      toast("합쳤어요");
      go("companies", c.id);
    }),
    "post-filter": ({ key }) => {
      S.postFilter = key;
      S.data.rates = null;
      refilter("posts");
    },
    "rate-filter": ({ key }) => {
      S.rateFilter = key;
      refilter("rates");
    },
    "rate-set": async ({ id, key }) => {
      await act(() => api(`/rates/${id}`, "POST", { status: key }), key === "adopted" ? "채택했어요" : key === "hidden" ? "가렸어요" : "투표로 되돌렸어요");
      S.data.rates = null;
      load();
    },
    "post-show": ({ id }) => moderate(id, { action: "show" }, "다시 보이게 했어요. 신고로는 더 가려지지 않아요"),
    "post-hide": ({ id }) => openHidePost(id, false),
    "post-mute": ({ id }) => openHidePost(id, true),
    "post-delete": ({ id }) => confirmBox("이 글을 지울까요?", "지운 글은 되돌릴 수 없어요. 글쓴이 앱에서도 사라져요.", "지우기", () => moderate(id, { action: "delete" }, "지웠어요")),
    "reward-tab": ({ key }) => {
      S.rewardTab = key;
      refilter("rewards");
    },
    reward: ({ id, status, months }) => (status === "paid" ? openPaid(id, months) : setReward(id, status)),
    step: ({ key, d }) => {
      const s = { adoptAgree: 75, ...(S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3 }) };
      const RANGES = { adoptPoints: [2, 20, 1], adoptAgree: [50, 100, 5], adoptPersons: [2, 10, 1], amendPersons: [2, 10, 1],
        rewardCapMonths: [1, 24, 1], rewardCapDays: [30, 365, 30] };
      const DEFAULT = { adoptPersons: 3, amendPersons: 3, autoReward: 1, rewardCapMonths: 3, rewardCapDays: 90 };
      const [lo, hi, by] = RANGES[key] ?? [0, 24, 1];
      s[key] = Math.min(hi, Math.max(lo, (s[key] ?? DEFAULT[key]) + Number(d) * by));
      S.draft = s;
      render({ keepScroll: true });
    },
    "save-settings": async () => {
      const s = await act(() => api("/settings", "POST", S.draft), "저장했어요");
      if (s) {
        S.overview.settings = s;
        S.draft = null;
      }
      render({ keepScroll: true });
    },
    "inquiry-status": ({ key }) => {
      S.inquiryStatus = key;
      if (S.inquiryWho === "late" && key !== "open") S.inquiryWho = "";
      S.inquiryPick = null;
      refilter("inquiries");
    },
    "inquiry-topic": ({ key }) => {
      S.inquiryTopic = key;
      refilter("inquiries");
    },
    template: ({ i }) => {
      // [ADMIN-16] A saved answer goes in filled for this question: {앱버전}, {주제}, {오늘}.
      const box = document.getElementById("reply");
      const t = templateList()[Number(i)];
      if (!box || !t) return;
      const q = S.data.inquiry;
      const text = L.fillTemplate(t.text, { app: q?.appVersion ?? "", topic: q?.topicName ?? "", today: new Date().toLocaleDateString("ko-KR") });
      box.value = (box.value ? `${box.value.trimEnd()}\n\n` : "") + text;
      if (t.id > 0) box.form.elements.template.value = String(t.id);
      box.dispatchEvent(new Event("input", { bubbles: true }));
      box.focus();
    },
    "inquiry-clear": () => {
      S.inquiryQuery = "";
      S.inquiryWho = "";
      S.inquiryTopic = "";
      refilter("inquiries");
    },
    "inquiry-who": ({ key }) => {
      S.inquiryWho = key;
      // [ADMUX-02] 놓친 것 is only ever a waiting question.
      if (key === "late") S.inquiryStatus = "open";
      refilter("inquiries");
    },
    "inquiry-assign": async ({ to }) => {
      const id = S.route.id;
      const q = await act(() => api(`/inquiries/${id}/assign`, "POST", { to: to || null }), to ? "내가 맡았어요" : "맡기를 취소했어요");
      if (q) S.data.inquiry = q;
      render({ keepScroll: true });
      load({ quiet: true });
    },
    templates: async () => {
      if (!S.templates) await loadTemplates();
      openTemplates();
    },
    "template-remove": ({ id }) => confirmBox("이 답을 지울까요?", "모든 관리자의 목록에서 빠져요.", "지우기", async () => {
      const r = await act(() => api(`/templates/${id}/remove`, "POST"), "지웠어요");
      if (r) {
        S.templates = r.templates;
        if (S.data.inquiry?.templates) S.data.inquiry.templates = S.data.inquiry.templates.filter((t) => t.id !== Number(id));
        openTemplates();
        render({ keepScroll: true });
      }
    }),
    "dev-from-inquiry": () => openDevFromInquiry("new"),
    "dev-add-mode": ({ key }) => openDevFromInquiry(key),
    "dev-attach": async ({ id }) => {
      if (S.linkMode === "bulk") {
        dialog.close();
        await inquiryBulk("link", { item: id });
        return;
      }
      const r = await act(() => api(`/dev/${id}/links`, "POST", { inquiry: S.route.id }), (d) => `"${d.title}"에 붙였어요. 문의 ${d.links}건이에요`);
      if (r) {
        dialog.close();
        load();
      }
    },
    "dev-status": ({ key }) => {
      S.devStatus = key;
      refilter("devList");
    },
    "dev-who": ({ key }) => {
      S.devWho = key;
      refilter("devList");
    },
    "dev-new": () => openDevNew(),
    "dev-pick": () => {
      // [ADMIN-10] 여러 개 고르기 (일괄 복사·처리) is for the owner and admins given 처리.
      S.devPick = S.devPick || !can("devStatus", "edit") ? null : new Set();
      render({ keepScroll: true });
    },
    "dev-pick-all": () => {
      const items = S.data.devList?.items ?? [];
      S.devPick = S.devPick.size === items.length ? new Set() : new Set(items.map((x) => x.id));
      render({ keepScroll: true });
    },
    "dev-copy": () => {
      const items = (S.data.devList?.items ?? []).filter((x) => S.devPick.has(x.id));
      copy(L.devCopyText(items), `${items.length}개를 복사했어요. 메신저나 메모에 붙여 넣으세요`);
    },
    "dev-copy-one": () => copy(L.devCopyText([S.data.dev]), "복사했어요"),
    "dev-bulk": ({ status }) => {
      if (!S.devPick?.size) return;
      if (status === "rejected") openReason([...S.devPick]);
      else devBulk(status);
    },
    "dev-edit": () => openDevEdit(),
    "dev-assign": ({ to }) => devSave({ assignee: to || null }, to ? "내가 맡았어요" : "맡기를 취소했어요"),
    "dev-status-set": ({ status }) => {
      if (status === S.data.dev.status) return;
      if (status === "rejected") openReason(null);
      else devSetStatus(status);
    },
    "reason-pick": ({ i }) => {
      const box = document.getElementById("dev-reason");
      box.value = REJECT_REASONS[Number(i)];
      box.focus();
    },
    "dev-delete": () => confirmBox("이 개발사항을 지울까요?", "목록에서 빠지고 기록은 남아요. 최고 관리자가 되살릴 수 있어요.", "지우기", async () => {
      const d = await api(`/dev/${S.route.id}/delete`, "POST");
      S.data.dev = d;
      toast("지웠어요");
      go("dev");
    }),
    "dev-restore": async () => {
      const d = await act(() => api(`/dev/${S.route.id}/restore`, "POST"), "되살렸어요");
      if (d) S.data.dev = d;
      render({ keepScroll: true });
    },
    "dev-unlink": ({ id }) => confirmBox("이 문의를 떼어 낼까요?", "문의는 그대로 있고 이 개발사항과의 연결만 끊겨요. 기록에 남아요.", "떼기", async () => {
      S.data.dev = await api(`/dev/${S.route.id}/links/remove`, "POST", { inquiry: id });
      toast("떼어 냈어요");
      render({ keepScroll: true });
    }),
    "dev-reply": () => openDevReply(),
    "inquiry-hide": async ({ reason }) => {
      const q = await act(() => api(`/inquiries/${S.route.id}/visibility`, "POST", { action: "hide", reason }), "숨겼어요. 보낸 분 앱에 이유가 보여요");
      if (q) S.data.inquiry = q;
      load();
    },
    "inquiry-show": async () => {
      const q = await act(() => api(`/inquiries/${S.route.id}/visibility`, "POST", { action: "show" }), "다시 보이게 했어요. 신고로는 더 가려지지 않아요");
      if (q) S.data.inquiry = q;
      load();
    },
    "inquiry-state": async ({ status }) => {
      const id = S.route.id;
      const reopen = () => api(`/inquiries/${id}/status`, "POST", { status: "open" }).then(() => load());
      const q = await act(() => api(`/inquiries/${id}/status`, "POST", { status }), status === "closed" ? "문의를 닫았어요" : "문의를 다시 열었어요",
        status === "closed" ? reopen : undefined);
      if (!q) return;
      S.data.inquiry = q;
      // [ADMUX-04] Closed in 큐 모드, or with 닫으면 다음 문의 on: the next one opens.
      if (status === "closed" && S.queue) return queueNext();
      const next = neighbours().next;
      if (status === "closed" && S.prefs.next && next) return go("inquiries", next);
      load();
    },
    invite: () => openInvite(),
    "invite-again": ({ email }) => {
      const i = S.data.admins.invites.find((x) => x.email === email);
      openInvite(i);
    },
    "invite-remove": ({ email }) => confirmBox(`${email} 초대를 취소할까요?`, "보낸 링크로는 더 이상 계정을 만들 수 없어요.", "초대 취소", async () => {
      await api("/invites/remove", "POST", { email });
      toast("초대를 취소했어요");
      load();
    }),
    "copy-invite": () => copy(S.inviteLink),
    "share-invite": async () => {
      try {
        await navigator.share({ title: "밤낮 관리자 초대", text: S.inviteText });
      } catch {
        // Closed without sharing.
      }
    },
    preset: ({ key }) => {
      const p = S.data.admins.presets[key];
      for (const [area, level] of Object.entries(p.perms)) setLevel(area, level);
    },
    level: ({ area, level }) => setLevel(area, level),
    "admin-approve": ({ id }) => openAdminEdit(id, true),
    // [MON-09]
    phone: async ({ id, key }) => {
      const go = async () => {
        const d = await act(() => api(`/devices/${id}/${key}`, "POST", {}), key === "approve" ? "승인했어요. 그 휴대폰은 프로를 무료로 써요" : "끊었어요");
        if (d) S.data.phones = d;
        load();
      };
      if (key === "remove") confirmBox("이 휴대폰을 끊을까요?", "그 휴대폰은 다음 확인 때부터 무료 등급이 돼요.", "끊기", go);
      else go();
    },
    "link-code": async () => {
      const r = await act(() => api("/me/link-code", "POST", {}));
      if (!r) return;
      openDialog(`<div class="box"><h2>연결 코드</h2><p class="code-big" aria-label="연결 코드">${esc(r.code.replace(/(\d{3})(\d{3})/, "$1 $2"))}</p>
        <p class="hint">${esc(clock(r.expiresAt))}까지 한 번 쓸 수 있어요. 밤낮 앱 › 설정 › 프로 › '관리자 연결'에 넣어 주세요.</p>
        <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`);
    },
    // [MON-10]
    "launch-switch": ({ key }) => {
      const on = key === "on";
      const go = async () => {
        const d = await act(() => api("/launch", "POST", { billingLive: on }), on ? "정식 출시를 켰어요. 앱이 하루 안에 따라와요" : "정식 출시를 껐어요. 다시 모두 무료예요");
        if (d) S.data.launch = d;
        load();
      };
      if (on) {
        confirmBox("정식 출시를 켤까요?", "켜기 전에 차례대로 확인해 주세요: ① Play 정기 결제 상품 활성 ② 이용약관·처리방침 개정판 게시 뒤 7일 ③ Play 데이터 보안·광고 포함 선언 ④ 1.19.0 이상이 프로덕션에. 켜면 결제·광고가 시작되고 출시 전에 설치한 분들께 감사 보상이 가요.", "켜기", go);
      } else {
        confirmBox("정식 출시를 끌까요?", "앱이 다음 확인 때부터 모든 기능을 무료로 열고 광고를 멈춰요. 이미 구독한 분의 Play 결제는 그대로예요.", "끄기", go);
      }
    },
    "admin-edit": ({ id }) => openAdminEdit(id, false),
    "admin-status": ({ id, status, name }) => {
      const run = async () => {
        S.data.admins = await api(`/admins/${id}`, "POST", { status });
        toast(status === "active" ? "다시 쓸 수 있어요" : "멈췄어요. 그 기기들은 바로 로그아웃돼요");
        render({ keepScroll: true });
        load({ quiet: true });
      };
      if (status === "suspended") confirmBox(`${name}님을 멈출까요?`, "바로 로그아웃되고, 다시 쓰기를 누를 때까지 로그인할 수 없어요.", "멈추기", run);
      else act(run);
    },
    "admin-remove": ({ id, name }) => confirmBox(`${name}님을 삭제할까요?`, "계정이 지워지고 바로 로그아웃돼요. 다시 쓰려면 새로 초대해야 해요.", "삭제", async () => {
      S.data.admins = await api(`/admins/${id}/remove`, "POST");
      toast("삭제했어요");
      render({ keepScroll: true });
      load({ quiet: true });
    }),
    // [SRV-04][ADMIN-14]
    "ops-month": ({ d }) => {
      const [y, m] = (S.data.ops?.month ?? new Date().toISOString().slice(0, 7)).split("-").map(Number);
      const next = new Date(Date.UTC(y, m - 1 + Number(d), 1));
      S.opsMonth = next.toISOString().slice(0, 7);
      syncUrl();
      load({ overview: false });
    },
    "ops-tick": async ({ key, done }) => {
      const o = await act(() => api("/ops", "POST", { month: S.data.ops.month, key, done: Boolean(done) }), done ? "마쳤어요" : "되돌렸어요");
      if (o) S.data.ops = o;
      load();
    },
    "notice-new": () => openNotice(null),
    "notice-edit": ({ id }) => openNotice(S.data.notices?.notices.find((x) => x.id === id)),
    "notice-remove": ({ id }) => confirmBox("공지를 내릴까요?", "앱은 다음에 새로 받을 때 이 공지를 빼요.", "내리기", async () => {
      const n = await act(() => api(`/notices/${id}/remove`, "POST", {}), "공지를 내렸어요");
      if (n) S.data.notices = n;
      render({ keepScroll: true });
    }),
    switch: ({ key, off }) => {
      const label = S.data.notices?.switches.find((w) => w.key === key)?.label ?? key;
      const flip = async () => {
        const n = await act(() => api("/switches", "POST", { key, off: Boolean(off) }), off ? `${label}을(를) 껐어요` : `${label}을(를) 다시 켰어요`,
          off ? () => ACTIONS.switch({ key, off: "" }) : undefined);
        if (n) S.data.notices = n;
        render({ keepScroll: true });
      };
      if (off) confirmBox(`${label}을(를) 끌까요?`, "서버가 바로 받지 않고, 앱은 하루 안에 이 기능을 잠시 쉬게 해요. 다시 켜면 돌아와요.", "끄기", flip);
      else flip();
    },
    // [ADMIN-13]
    "metric-days": ({ key }) => {
      S.metricDays = Number(key);
      refilter("metrics");
    },
    // [SRV-05]
    "share-remove": ({ id }) => confirmBox("이 링크를 내릴까요?", "근무표가 바로 지워지고 되돌릴 수 없어요. 공유한 분은 새 링크를 만들 수 있어요.", "내리기", async () => {
      const d = await act(() => api(`/shares/${id}/remove`, "POST", { reason: "운영 규칙 위반" }), "링크를 내렸어요");
      if (d) S.data.shares = d;
      render({ keepScroll: true });
    }),
    "audit-more": async () => {
      const d = S.data.audit;
      const next = await act(() => api(`/audit${auditQuery(d.entries[d.entries.length - 1].id)}`));
      if (next) S.data.audit = { ...next, entries: [...d.entries, ...next.entries] };
      render({ keepScroll: true });
    },
    install: async () => {
      const p = S.installPrompt;
      if (!p) return;
      p.prompt();
      const choice = await p.userChoice.catch(() => null);
      S.installPrompt = null;
      if (choice?.outcome === "accepted") toast("설치했어요. 홈 화면에서 열 수 있어요");
      render({ keepScroll: true });
    },
    "join-code": async () => {
      S.error = "";
      const r = await act(() => api("/join/code", "POST", { token: S.join.token }));
      if (r) {
        S.join.codeSent = true;
        const keep = readJoinForm();
        render();
        fillJoinForm(keep);
        document.getElementById("j-code")?.focus();
      }
    },
    logout: async () => {
      try {
        await api("/logout", "POST");
      } catch {
        // Signed out either way.
      }
      signedOut(true);
    },
    "logout-all": () => confirmBox("모든 기기에서 로그아웃할까요?", "이 기기를 포함해 이 계정으로 로그인한 모든 기기가 다시 로그인해야 해요.", "로그아웃", async () => {
      await api("/me/logout-all", "POST");
      signedOut(true);
    }),
  };

  function setLevel(area, level) {
    const form = dialog.querySelector("form");
    const input = form?.elements[`perm-${area}`];
    if (!input) return;
    const btn = form.querySelector(`[data-area="${area}"][data-level="${level}"]`);
    const value = btn?.disabled ? "view" : level;
    input.value = value;
    form.querySelectorAll(`[data-area="${area}"]`).forEach((b) => {
      const on = b.dataset.level === value;
      b.setAttribute("aria-pressed", String(on));
      b.setAttribute("aria-checked", String(on));
      b.tabIndex = on ? 0 : -1;
    });
  }

  /**
   * Back to the sign-in screen. [forget]: signed out on purpose, so what this device kept of the
   * admin's work (the service worker's copies of answers, unsent drafts) goes too.
   */
  function signedOut(forget = false) {
    S.phase = "login";
    S.me = null;
    S.overview = null;
    S.data = {};
    S.error = "";
    S.queue = null;
    S.relogin = false;
    etags.clear();
    clearInterval(pollTimer);
    if (forget) {
      clearDrafts();
      navigator.serviceWorker?.controller?.postMessage({ type: "forget" });
    }
    history.replaceState(null, "", "/admin/");
    render();
  }

  const readJoinForm = () => Object.fromEntries(["j-name", "j-pw", "j-pw2"].map((id) => [id, document.getElementById(id)?.value ?? ""]));
  const fillJoinForm = (v) => Object.entries(v).forEach(([id, value]) => { const el = document.getElementById(id); if (el) el.value = value; });

  const FORMS = {
    login: async (form) => {
      S.error = "";
      const data = new FormData(form);
      const body = S.prefs.loginMode === "owner" ? { password: data.get("password") } : { email: data.get("email"), password: data.get("password") };
      try {
        await api("/login", "POST", body);
        await enter();
      } catch (e) {
        const left = e.data?.left;
        S.error = e.message === "wrong" ? `${S.prefs.loginMode === "owner" ? "비밀번호가" : "메일이나 비밀번호가"} 달라요. ${left}번 더 틀리면 1시간 동안 잠겨요.`
          : e.message === "locked" ? "여러 번 틀려서 1시간 동안 잠겼어요. 나중에 다시 해 주세요."
          : e.message === "pending" ? "최고 관리자의 승인을 기다리고 있어요. 승인되면 로그인할 수 있어요."
          : e.message === "suspended" ? "사용이 멈춘 계정이에요. 최고 관리자에게 물어봐 주세요."
          : e.message === "not_set" ? "최고 관리자 비밀번호가 아직 없어요. 아래 서버 준비를 봐 주세요."
          : ERRORS[e.message] || "로그인하지 못했어요. 잠시 뒤 다시 해 주세요.";
        const email = form.elements.email?.value;
        render();
        if (email && document.getElementById("email")) document.getElementById("email").value = email;
      }
    },
    // [ADMUX-01] Signed in again over the screen: the box closes and the screen carries on.
    relogin: async (form) => {
      const data = new FormData(form);
      const body = S.me?.owner ? { password: data.get("password") } : { email: data.get("email"), password: data.get("password") };
      try {
        await api("/login", "POST", body);
        S.relogin = false;
        dialog.close();
        toast("다시 로그인했어요");
        load();
      } catch (e) {
        const box = document.getElementById("re-error");
        if (box) box.textContent = e.message === "wrong" ? "메일이나 비밀번호가 달라요." : ERRORS[e.message] ?? "로그인하지 못했어요.";
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
      const { company, setup } = form.dataset;
      dialog.close();
      // [ADMIN-15] A 거절 can be taken back for 10 minutes (the server reopens the reports).
      await act(() => api(`/groups/${company}/${setup}/reject`, "POST", { reason }), "거절했어요. 보낸 분 앱에 이유가 보여요",
        () => act(() => api(`/groups/${company}/${setup}/reopen`, "POST"), "거절을 되돌렸어요. 다시 검토를 기다려요").then(() => load()));
      load();
    },
    answer: async (form) => {
      const data = new FormData(form);
      dialog.close();
      await act(() => api(`/asks/${form.dataset.id}`, "POST", { answer: data.get("answer"), reward: data.get("reward") === "on" }),
        (r) => (r.reward ? "답했어요. 보상 탭에 지급 대기로 올렸어요" : "답했어요. 보낸 분 앱에 보여요"));
      load();
    },
    "new-company": async (form) => {
      const c = await act(() => api("/companies", "POST", { name: new FormData(form).get("name") }), "만들었어요");
      if (c) {
        dialog.close();
        go("companies", c.id);
      }
    },
    "hide-post": (form) => {
      const mute = form.dataset.mute === "1";
      const id = form.dataset.id;
      dialog.close();
      moderate(id, { action: mute ? "mute" : "hide", reason: new FormData(form).get("reason") },
        mute ? "가리고 30일 동안 쓰기를 막았어요" : "가렸어요. 글쓴이 앱에 이유가 보여요",
        mute ? undefined : () => moderate(id, { action: "show" }, "다시 보이게 했어요"));
    },
    paid: (form) => {
      dialog.close();
      const data = new FormData(form);
      setReward(form.dataset.id, "paid", data.get("memo"), Number(data.get("months")) || undefined);
    },
    // [MON-10] 감사 보상 개월 수와 서버가 무료로 연 기능.
    // [SRV-04] Dates are Korean days: from 0시 of the first to the end of the last.
    notice: async (form) => {
      const data = new FormData(form);
      const at = (d, plus = 0) => new Date(Date.parse(`${d}T00:00:00+09:00`) + plus).toISOString();
      const num = (v) => (String(v ?? "").trim() ? Number(v) : null);
      const body = {
        ...(form.dataset.id ? { id: form.dataset.id } : {}),
        title: data.get("title"), body: data.get("body"), level: data.get("level"),
        from: at(data.get("from")), until: at(data.get("until"), 864e5), minApp: num(data.get("minApp")), maxApp: num(data.get("maxApp")),
      };
      const n = await act(() => api("/notices", "POST", body), form.dataset.id ? "공지를 고쳤어요" : "공지를 올렸어요. 앱은 하루 안에 받아요");
      if (!n) return;
      S.data.notices = n;
      dialog.close();
      render({ keepScroll: true });
    },
    launch: async (form) => {
      const data = new FormData(form);
      const free = String(data.get("free") ?? "").split(/[\s,]+/).map((f) => f.trim().toUpperCase()).filter(Boolean);
      const d = await act(() => api("/launch", "POST", { thanksMonths: Number(data.get("thanks")), freeFeatures: free }), "저장했어요");
      if (d) S.data.launch = d;
      render({ keepScroll: true });
    },
    rename: async (form) => {
      const c = await act(() => api(`/companies/${S.route.id}`, "POST", { name: new FormData(form).get("name") }), "이름을 바꿨어요. 예전 이름으로도 찾아져요");
      if (c) S.data.company = c;
      render({ keepScroll: true });
    },
    alias: async (form) => {
      const c = await act(() => api(`/companies/${S.route.id}/aliases`, "POST", { name: new FormData(form).get("name") }));
      if (c) S.data.company = c;
      render({ keepScroll: true });
    },
    "merge-search": async (form) => {
      const box = document.getElementById("merge-results");
      const r = await act(() => api(`/companies?q=${encodeURIComponent(new FormData(form).get("q"))}`));
      if (!r || !box) return;
      const found = r.companies.filter((c) => c.id !== S.route.id && !c.mergedInto);
      box.innerHTML = found.length ? `<ul class="rows">${found.map((c) => `<li><button class="row" data-act="merge-into" data-id="${c.id}" data-name="${esc(c.name)}">
        <span class="row-main"><b>${esc(c.name)}</b><small>규칙 ${c.rules} · 제보 ${c.reports}</small></span><span class="pill">여기로 합치기</span></button></li>`).join("")}</ul>`
        : '<p class="meta">찾는 회사가 없어요.</p>';
    },
    reply: async (form, submitter) => {
      const text = form.elements.text.value.trim();
      if (!text) return toast(ERRORS.text);
      const close = submitter?.dataset.close === "1";
      const template = Number(form.elements.template?.value) || undefined;
      const id = S.route.id;
      const q = await act(() => api(`/inquiries/${id}/reply`, "POST", { text, ...(close ? { close: true } : {}), ...(template ? { template } : {}) }),
        close ? "답하고 닫았어요. 보낸 분 앱에 보여요" : "답했어요. 보낸 분 앱에 보여요");
      if (!q) return;
      typingSent = 0;
      S.data.inquiry = q;
      // [ADMUX-04] 큐 모드 goes on to the next question; 닫으면 다음 문의 does the same after 답하고 닫기.
      if (S.queue) return queueNext();
      const next = neighbours().next;
      if (close && S.prefs.next && next) return go("inquiries", next);
      render();
      window.scrollTo(0, document.body.scrollHeight);
      load({ quiet: true });
    },
    "inquiry-note": async (form) => {
      const q = await act(() => api(`/inquiries/${S.route.id}/notes`, "POST", { text: form.elements.text.value }), "메모를 남겼어요");
      if (q) {
        S.data.inquiry = q;
        render({ keepScroll: true });
      }
    },
    "template-add": async (form) => {
      const id = form.dataset.id;
      const body = { label: form.elements.label.value, text: form.elements.text.value, topic: form.elements.topic.value };
      const r = await act(() => api(id ? `/templates/${id}` : "/templates", "POST", body), id ? "고쳤어요" : "넣었어요. 답 칸 아래에서 바로 써요");
      if (!r) return;
      S.templates = r.templates;
      // The open question's list (its topic first) comes again with the change.
      if (S.route.name === "inquiries" && S.route.id) {
        S.data.inquiry = await api(`/inquiries/${S.route.id}`).catch(() => S.data.inquiry);
      }
      if (id) openTemplates();
      else dialog.close();
      render({ keepScroll: true });
    },
    // [ADMIN-16]
    "bulk-reply": async (form) => {
      const text = form.elements.text.value.trim();
      if (!text) return toast(ERRORS.text);
      const template = Number(form.elements.template.value) || undefined;
      dialog.close();
      await inquiryBulk("reply", { text, close: form.elements.close.checked, ...(template ? { template } : {}) });
    },
    // [ADMIN-19] Turned on here: the browser asks, makes a subscription for the server's key, and the server keeps it.
    push: async (form) => {
      const d = S.data.push;
      const kinds = [...form.querySelectorAll('input[name="kind"]:checked')].map((x) => x.value);
      const quiet = form.elements.quiet.checked ? { from: Number(form.elements.from.value), to: Number(form.elements.to.value) } : null;
      let sub = await currentPush();
      if (!sub) {
        const allowed = await Notification.requestPermission().catch(() => "denied");
        if (allowed !== "granted") {
          toast("알림을 허용해야 받을 수 있어요");
          return render({ keepScroll: true });
        }
        try {
          const key = Uint8Array.from(atob(d.publicKey.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
          const reg = await navigator.serviceWorker.ready;
          sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
        } catch {
          return toast("이 브라우저가 알림 등록을 받지 않았어요. 앱으로 설치한 뒤 다시 해 주세요");
        }
      }
      const r = await act(() => api("/me/push", "POST", { subscription: sub.toJSON(), kinds, quiet }), "이 기기 알림을 저장했어요");
      if (r) {
        S.data.push = r;
        S.pushId = await pushIdOf(sub.endpoint);
      }
      render({ keepScroll: true });
    },
    "dev-new": async (form) => {
      const body = { title: form.elements.title.value, body: form.elements.body.value };
      if (form.dataset.inquiry) {
        body.inquiry = form.dataset.inquiry;
        body.body = body.body.replace(SCRUB, "").replace(/^\s+/, "");
      }
      const d = await act(() => api("/dev", "POST", body), form.dataset.inquiry ? "개발사항에 추가했어요. 추가한 사람으로 내 이름이 남아요" : "추가했어요");
      if (!d) return;
      dialog.close();
      if (form.dataset.inquiry) load();
      else go("dev", d.id);
    },
    "dev-edit": async (form) => {
      const d = await devSave({ title: form.elements.title.value, body: form.elements.body.value, since: form.dataset.since }, "고쳤어요. 기록에 남았어요");
      // Closed once saved, or when someone else's change came in instead (the screen shows it now).
      if (d || S.data.dev.updatedAt !== form.dataset.since) dialog.close();
    },
    "dev-release": async (form) => {
      const v = form.elements.v.value.trim();
      await devSave({ releasedIn: v || null, since: S.data.dev.updatedAt }, v ? `${v}에 반영으로 적었어요` : "반영 버전을 지웠어요");
    },
    "dev-note": async (form) => {
      const r = await act(() => api(`/dev/${S.route.id}/notes`, "POST", { text: form.elements.text.value }), "메모를 남겼어요");
      if (r) {
        S.data.dev = r;
        render({ keepScroll: true });
      }
    },
    "dev-reject": (form) => {
      const reason = form.elements.reason.value.trim();
      if (!reason) return toast(ERRORS.reason);
      dialog.close();
      if (form.dataset.ids) devBulk("rejected", reason);
      else devSetStatus("rejected", reason);
    },
    "dev-reply": async (form) => {
      const ids = [...form.querySelectorAll('input[name="ids"]:checked')].map((x) => x.value);
      if (!ids.length) return toast(ERRORS.ids);
      const r = await act(() => api(`/dev/${S.route.id}/reply`, "POST", { text: form.elements.text.value, ids, close: form.elements.close.checked }),
        (x) => `문의 ${x.answered}건에 답했어요. 보낸 분들 앱에 보여요`);
      if (r) {
        dialog.close();
        S.data.dev = r.item;
        render({ keepScroll: true });
        load({ quiet: true });
      }
    },
    invite: async (form) => {
      const d = S.data.admins;
      const r = await act(() => api("/invites", "POST", {
        email: form.elements.email.value, note: form.elements.note.value, perms: readPermForm(form, d),
      }));
      if (r) {
        openInviteLink(r);
        load({ quiet: true });
      }
    },
    "admin-perms": async (form) => {
      const d = S.data.admins;
      const body = { perms: readPermForm(form, d) };
      if (form.dataset.approve) body.status = "active";
      const r = await act(() => api(`/admins/${form.dataset.id}`, "POST", body), form.dataset.approve ? "승인했어요. 이제 로그인할 수 있어요" : "권한을 바꿨어요");
      if (r) {
        dialog.close();
        S.data.admins = r;
        render({ keepScroll: true });
        load({ quiet: true });
      }
    },
    password: async (form) => {
      const r = await act(() => api("/me/password", "POST", { current: form.elements.current.value, next: form.elements.next.value }), "비밀번호를 바꿨어요");
      if (r) form.reset();
    },
    join: async (form) => {
      S.error = "";
      const data = new FormData(form);
      const keep = readJoinForm();
      if (data.get("password") !== data.get("password2")) {
        S.error = "두 비밀번호가 달라요.";
      } else {
        try {
          await api("/join", "POST", { token: S.join.token, name: data.get("name"), password: data.get("password"), code: data.get("code") ?? undefined });
          S.join.done = true;
          history.replaceState(null, "", "/admin/join");
        } catch (e) {
          S.error = e.message === "code" ? `확인 코드가 달라요. ${e.data?.left ?? 0}번 더 할 수 있어요.`
            : e.message === "code_expired" ? "확인 코드가 만료됐어요. 코드를 다시 보내 주세요."
            : e.message === "exists" ? "이 메일로 만든 계정이 이미 있어요. 로그인 화면에서 로그인해 주세요."
            : ERRORS[e.message] || "계정을 만들지 못했어요. 잠시 뒤 다시 해 주세요.";
        }
      }
      render();
      if (!S.join.done) fillJoinForm(keep);
    },
  };

  // ---- events ----

  /** [ADMUX-01] A button doing its work shows it and takes no second tap until it is done. */
  async function busy(el, work) {
    if (el?.getAttribute?.("aria-busy") === "true") return;
    const button = el?.tagName === "BUTTON" ? el : null;
    button?.setAttribute("aria-busy", "true");
    try {
      await work();
    } finally {
      button?.removeAttribute("aria-busy");
    }
  }

  document.addEventListener("click", (e) => {
    if (e.target.closest("[data-undo]")) {
      const undo = S.undo;
      S.undo = null;
      toastBox.classList.remove("show", "has-undo");
      if (undo) Promise.resolve(undo()).catch(fail);
      return;
    }
    const el = e.target.closest("[data-act]");
    if (!el || !ACTIONS[el.dataset.act] || el.disabled) return;
    e.preventDefault();
    busy(el, () => ACTIONS[el.dataset.act](el.dataset, el));
  });
  document.addEventListener("submit", (e) => {
    const form = e.target.closest("form[data-form]");
    if (!form) return;
    e.preventDefault();
    if (form.dataset.sending) return;
    form.dataset.sending = "1";
    const submitter = e.submitter ?? form.querySelector("[type=submit]");
    submitter?.setAttribute("aria-busy", "true");
    S.submitting = ["merge-search", "login", "relogin", "confirm"].includes(form.dataset.form) ? null : form;
    Promise.resolve(FORMS[form.dataset.form]?.(form, e.submitter)).catch(fail).finally(() => {
      S.submitting = null;
      delete form.dataset.sending;
      submitter?.removeAttribute("aria-busy");
    });
  });

  /** Changes that are a setting, not a form: [data-change] names which. */
  const CHANGES = {
    hidden: async (t) => {
      if (S.route.name !== "companies") return;
      const c = await act(() => api(`/companies/${S.route.id}`, "POST", { hidden: t.checked }),
        (r) => (r.hidden ? "회사 찾기에서 숨겼어요" : "다시 회사 찾기에 나와요"));
      if (c) S.data.company = c;
      render({ keepScroll: true });
    },
    "inquiry-sort": (t) => {
      S.inquirySort = t.value;
      refilter("inquiries");
    },
    "audit-actor": (t) => {
      S.audit.actor = t.value;
      refilter("audit");
    },
    "audit-kind": (t) => {
      S.audit.kind = t.value;
      refilter("audit");
    },
    "audit-from": (t) => {
      S.audit.from = t.value;
      refilter("audit");
    },
    "audit-to": (t) => {
      S.audit.to = t.value;
      refilter("audit");
    },
    "auto-reward": (t) => {
      S.draft = { adoptAgree: 75, ...(S.draft ?? S.overview?.settings ?? {}), autoReward: t.checked ? 1 : 0 };
      render({ keepScroll: true });
    },
    "pref-theme": (t) => {
      S.prefs.theme = t.value;
      savePrefs();
    },
    "pref-size": (t) => {
      S.prefs.size = t.value;
      savePrefs();
    },
    "pref-sound": (t) => {
      S.prefs.sound = t.checked;
      savePrefs();
      if (t.checked) chime();
    },
    "pref-next": (t) => {
      S.prefs.next = t.checked;
      savePrefs();
    },
    "pref-tab": (t) => {
      // Home and three more: a fourth one in takes the place of the one that has been there longest.
      const items = NAV.filter(visible);
      let now = L.bottomTabs(items, S.prefs.tabs, "home").tabs.filter((k) => k !== "home");
      let out = "";
      if (t.checked) {
        now = [...now.filter((k) => k !== t.value), t.value];
        if (now.length > 3) out = now.shift();
      } else {
        now = now.filter((k) => k !== t.value);
      }
      S.prefs.tabs = ["home", ...now];
      savePrefs();
      const name = (k) => NAV.find((n) => n.key === k)?.label ?? k;
      if (out) toast(`아래 탭은 홈과 3개까지라 ${name(out)} 대신 ${name(t.value)}을(를) 넣었어요`);
      render({ keepScroll: true });
    },
  };

  document.addEventListener("change", (e) => {
    const t = e.target;
    // [ADMIN-10] Picking 개발사항 for 일괄 복사·처리.
    if (t.dataset?.pick && S.devPick) {
      if (t.checked) S.devPick.add(t.dataset.pick);
      else S.devPick.delete(t.dataset.pick);
      const box = document.getElementById("dev-list");
      if (box) box.innerHTML = devRows();
      return;
    }
    // [ADMIN-16] Picking questions.
    if (t.dataset?.pickInquiry && S.inquiryPick) {
      if (t.checked) S.inquiryPick.add(t.dataset.pickInquiry);
      else S.inquiryPick.delete(t.dataset.pickInquiry);
      render({ keepScroll: true });
      return;
    }
    const run = CHANGES[t.dataset?.change];
    if (run) Promise.resolve(run(t)).catch(fail);
  });

  /** [ADMIN-16] Words typed in a list's search box: the list comes again a quarter second after the last key. */
  const SEARCH = {
    dev: () => ["devQuery", "devList", () => loadDev()],
    companies: () => ["query", "list", async () => { S.data.list = await api(`/companies?q=${encodeURIComponent(S.query)}`); }],
    inquiries: () => ["inquiryQuery", "inquiries", () => loadInquiries()],
    community: () => ["postQuery", S.postFilter === "rates" ? "rates" : "posts", () => load({ overview: false, quiet: true })],
    rewards: () => ["rewardQuery", "rewards", () => load({ overview: false, quiet: true })],
  };

  // toggle does not bubble, so it is caught on the way down.
  document.addEventListener("toggle", (e) => {
    if (e.target instanceof HTMLDetailsElement && e.target.dataset.key) S.opened.set(e.target.dataset.key, e.target.open);
  }, true);

  /**
   * [ADMUX-04] a, x and s on 검토: 채택 or 거절 for the card in focus (the first one if none), s to the next
   * card. 채택 still asks first.
   */
  function reviewKey(act) {
    if (!can("review", "edit")) return;
    const cards = [...document.querySelectorAll("#main article.card")].filter((c) => c.querySelector('[data-act="adopt"],[data-act="reject"]'));
    const at = cards.indexOf(document.activeElement?.closest?.("article.card"));
    const card = act ? cards[Math.max(at, 0)] : cards[at + 1] ?? cards[0];
    if (!card) return;
    if (!act) {
      card.tabIndex = -1;
      card.focus();
      card.scrollIntoView?.({ block: "nearest" });
      return;
    }
    card.querySelector(`[data-act="${act}"]`)?.click();
  }

  document.addEventListener("input", (e) => {
    const t = e.target;
    if (t.dataset?.strength) {
      const m = document.getElementById(t.dataset.strength);
      if (m) m.value = L.strength(t.value);
    }
    const nf = t.closest?.('form[data-form="notice"]');
    if (nf) {
      const v = (name) => nf.elements[name]?.value ?? "";
      const box = document.getElementById("nt-preview");
      if (box) box.innerHTML = noticePreview(v("title"), v("body"), nf.querySelector('input[name="level"]:checked')?.value);
    }
    if (KEEP.includes(t.id) && t.closest("#main")) {
      writeDraft(t.id, t.value);
      if (t.id === "reply") {
        const saved = document.getElementById("reply-saved");
        if (saved) saved.textContent = t.value ? "적는 중인 답은 이 기기에 저장돼요" : "";
        pingTyping(Boolean(t.value.trim()));
      }
    }
    if (t.id === "reply") {
      const n = document.getElementById("reply-count");
      if (n) n.textContent = `${t.value.length}/2000`;
      t.style.height = "auto";
      t.style.height = `${Math.min(t.scrollHeight + 4, window.innerHeight * 0.5)}px`;
    }
    if (t.id === "pal" && S.palette) {
      S.palette.q = t.value;
      S.palette.at = 0;
      return paintPalette();
    }
    if (t.id === "dev-find") return showCandidates(t.value);
    if (t.id !== "search") return;
    const plan = SEARCH[S.route.name]?.();
    if (!plan) return;
    const [field, , fetchIt] = plan;
    S[field] = t.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
      syncUrl();
      await act(fetchIt);
      render({ keepScroll: true });
    }, 250);
  });

  // ---- [ADMUX-04] keys ----

  /** The rows a list shows, for j and k. */
  const rowsOnScreen = () => [...document.querySelectorAll("#main .rows > li > .row, #main .rows > li.item, #main .queue-list > li")];

  function markCursor() {
    const rows = rowsOnScreen();
    rows.forEach((r, i) => r.classList.toggle("selected", i === S.cursor));
    const el = rows[S.cursor];
    if (el) el.scrollIntoView({ block: "nearest" });
  }

  function moveCursor(by) {
    const rows = rowsOnScreen();
    if (!rows.length) return;
    S.cursor = Math.max(0, Math.min(rows.length - 1, (S.cursor < 0 ? (by > 0 ? -1 : rows.length) : S.cursor) + by));
    markCursor();
  }

  function openCursor() {
    const el = rowsOnScreen()[S.cursor];
    const link = el?.matches("a,button") ? el : el?.querySelector("a,button");
    link?.click();
  }

  /** What a key does on the screen it was pressed on. */
  function onKey(a, e) {
    const detail = S.route.name === "inquiries" && S.route.id;
    const editInq = can("inquiries", "edit");
    const stop = () => e.preventDefault();
    switch (a.act) {
      case "palette":
        stop();
        return openPalette();
      case "send": {
        const box = document.getElementById("reply");
        if (e.target === box && box.form) {
          stop();
          box.form.requestSubmit(box.form.querySelector(".btn.primary"));
        }
        return;
      }
      case "escape":
        if (S.queue && !dialog.open) return ACTIONS["queue-stop"]();
        return;
      case "pending":
        gKey = "g";
        clearTimeout(gTimer);
        gTimer = setTimeout(() => { gKey = ""; }, 1200);
        return;
      case "go": {
        const n = NAV.find((x) => x.key === a.to);
        if (n && visible(n)) {
          stop();
          go(a.to);
        }
        return;
      }
      case "move":
        stop();
        return moveCursor(a.by);
      case "open":
        if (S.cursor >= 0) {
          stop();
          openCursor();
        }
        return;
      case "back": {
        const back = document.querySelector("#bar a.icon-btn");
        if (back) {
          stop();
          back.click();
        }
        return;
      }
      case "step": {
        if (!detail) return;
        const nb = neighbours();
        const to = a.by > 0 ? nb.next : nb.prev;
        if (to) {
          stop();
          go("inquiries", to);
        }
        return;
      }
      case "search": {
        const s = document.getElementById("search");
        if (s) {
          stop();
          s.focus();
        }
        return;
      }
      case "keys":
        stop();
        return openShortcuts();
      case "assign":
        if (S.route.name === "review") return reviewKey("adopt");
        if (detail && editInq && S.data.inquiry && !S.data.inquiry.assignee) return ACTIONS["inquiry-assign"]({ to: "me" });
        return;
      case "reply":
        // [ADMUX-04] r: the answer box on a question, a fresh list anywhere else.
        stop();
        if (detail) return document.getElementById("reply")?.focus();
        return load();
      case "close":
        if (detail && editInq && S.data.inquiry?.status !== "closed") return ACTIONS["inquiry-state"]({ status: "closed" });
        return;
      case "reject":
        if (S.route.name === "review") return reviewKey("reject");
        if (detail && S.queue) return ACTIONS["inquiry-state"]({ status: "closed" });
        return;
      case "skip":
        if (S.route.name === "review") return reviewKey(null);
        if (S.queue) return queueNext(true);
        return;
      case "queue":
        if (!editInq) return;
        return S.queue ? ACTIONS["queue-stop"]() : ACTIONS["queue-start"]();
      case "template": {
        if (detail && document.getElementById("reply") && templateList()[a.n - 1]) {
          stop();
          return ACTIONS.template({ i: a.n - 1 });
        }
        // 1~9 go to the screens in the menu's order everywhere else.
        const items = NAV.filter(visible);
        if (!detail && items[a.n - 1]) {
          stop();
          go(items[a.n - 1].key);
        }
        return;
      }
      default:
    }
  }

  document.addEventListener("keydown", (e) => {
    if (S.phase !== "app") return;
    // The palette's own keys.
    if (S.palette && dialog.open) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        S.palette.at = Math.max(0, Math.min((S.palette.list?.length ?? 1) - 1, S.palette.at + (e.key === "ArrowDown" ? 1 : -1)));
        return paintPalette();
      }
      if (e.key === "Enter" && e.target.id === "pal") {
        e.preventDefault();
        return paletteGo(S.palette.at);
      }
      return;
    }
    // [ADMUX-03] The level buttons of a permission are a radio group: arrows move and pick.
    if (e.target.getAttribute?.("role") === "radio" && ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      const group = [...e.target.parentElement.querySelectorAll('[role="radio"]:not(:disabled)')];
      const i = group.indexOf(e.target) + (e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : -1);
      const next = group[(i + group.length) % group.length];
      e.preventDefault();
      setLevel(next.dataset.area, next.dataset.level);
      next.focus();
      return;
    }
    if (dialog.open && e.key !== "Escape") return;
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable;
    const a = L.keyAction(e.key, { ctrl: e.ctrlKey, meta: e.metaKey, alt: e.altKey, shift: e.shiftKey }, typing, gKey);
    if (gKey && a?.act !== "pending") gKey = "";
    if (a) onKey(a, e);
  });
  dialog.addEventListener("click", (e) => {
    // A tap outside the box closes it.
    if (e.target === dialog) dialog.close();
  });
  dialog.addEventListener("close", () => {
    S.palette = null;
    S.linkMode = "";
    if (S.relogin) {
      // Closed without signing in: the sign-in screen, since nothing else would work.
      S.relogin = false;
      signedOut();
    }
  });
  window.addEventListener("hashchange", () => {
    if (S.phase !== "app") return;
    const before = S.route;
    const next = readRoute();
    const moved = before.name !== next.name || before.id !== next.id;
    // [ADMUX-01] Settings changed and not saved: asked before they are dropped.
    if (moved && (S.draft || S.slaDraft) && before.name === "settings" && !confirm("저장하지 않은 설정이 있어요. 버리고 나갈까요?")) {
      history.replaceState(null, "", `/admin/${href(before.name, before.id)}`);
      return;
    }
    if (moved && before.name === "inquiries" && before.id) pingTyping(false);
    S.route = next;
    applyParams(next);
    if (moved) {
      S.draft = null;
      S.slaDraft = null;
      S.cursor = -1;
      if (next.name !== "inquiries") S.inquiryPick = null;
      // 큐 모드 ends once the admin goes somewhere else than a question of the queue.
      if (S.queue && !(next.name === "inquiries" && S.queue.ids.includes(next.id))) S.queue = null;
      render();
      window.scrollTo(0, 0);
      document.getElementById("title")?.focus({ preventScroll: true });
    }
    load();
    schedule();
  });
  window.addEventListener("online", () => {
    S.online = true;
    if (S.phase === "app") {
      paintBanners();
      load({ quiet: true });
    }
  });
  window.addEventListener("offline", () => {
    S.online = false;
    if (S.phase === "app") paintBanners();
  });
  window.addEventListener("beforeunload", (e) => {
    // Typed answers are kept in the browser; only settings would be lost.
    if (S.draft || S.slaDraft) {
      e.preventDefault();
      e.returnValue = "";
    }
  });

  /**
   * [ADMUX-08] Fresh numbers while the app is open: every 30 seconds on the home screen and on a question
   * (where someone else may be answering), every 90 seconds elsewhere, never while the tab is hidden.
   * A new question can chime (설정 › 이 기기).
   */
  function schedule() {
    clearInterval(pollTimer);
    const fast = S.route.name === "home" || (S.route.name === "inquiries" && S.route.id);
    pollTimer = setInterval(poll, fast ? 30000 : 90000);
  }

  async function poll() {
    if (document.visibilityState !== "visible" || S.phase !== "app" || !S.online || dialog.open) return;
    const before = S.overview?.inquiries ?? 0;
    const typingNow = document.activeElement?.closest?.("#main") && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    if (S.route.name === "home" || (S.route.name === "inquiries" && S.route.id && !typingNow)) {
      await load({ quiet: true });
    } else {
      try {
        const o = await api("/overview");
        S.overview = o;
        S.me = o.me;
        S.newBuild = Boolean(o.build && o.build !== BUILD);
        paintBanners();
        updateBadges();
      } catch {
        // Tried again next time.
      }
    }
    if (S.prefs.sound && (S.overview?.inquiries ?? 0) > before) chime();
  }

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && S.phase === "app") load({ quiet: true });
    if (document.visibilityState === "hidden") pingTyping(false);
  });
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    S.installPrompt = e;
    if (S.phase !== "boot") render({ keepScroll: true });
  });
  window.addEventListener("appinstalled", () => {
    S.installed = true;
    S.installPrompt = null;
  });

  /** Opens the app: the numbers (with the home screen's recent activity) first, then the screen's own data. */
  async function enter() {
    S.overview = await api("/overview");
    S.me = S.overview.me;
    S.newBuild = Boolean(S.overview.build && S.overview.build !== BUILD);
    S.phase = "app";
    S.route = readRoute();
    applyParams(S.route);
    if (!location.hash) history.replaceState(null, "", `/admin/${href(S.route.name)}`);
    render();
    if (S.route.name !== "home") load({ quiet: true, overview: false });
    schedule();
    if ("serviceWorker" in navigator && "PushManager" in window) currentPush();
  }

  (async () => {
    applyPrefs();
    if ("serviceWorker" in navigator) navigator.serviceWorker.register("/admin/sw.js", { scope: "/admin/" }).catch(() => {});
    const path = location.pathname;
    if (path === "/admin/join") {
      S.phase = "join";
      const token = location.hash.replace(/^#/, "");
      render();
      try {
        // [ADMIN-15] The token goes in the body, so it is in no server log or proxy's address line.
        const info = await api("/join/info", "POST", { token });
        S.join = { ...info, token };
      } catch (e) {
        S.join = { error: e.message === "rate_limited" ? "rate_limited" : "invite" };
      }
      render();
      return;
    }
    if (path === "/admin/install") {
      S.phase = "install";
      render();
      return;
    }
    // What the server still lacks only shows on the login screen, so it is not waited for before signing in.
    const checked = health().then((h) => { S.health = h; return h; });
    try {
      await enter();
    } catch {
      await checked;
      S.phase = "login";
      render();
    }
  })();
}

/**
 * The developer app's service worker. The app's own files open from the phone at once and are refreshed
 * in the background for the next time, so opening the app does not wait on the network; a new version
 * of the app changes [version], and the phone then fetches every file again. [ADMUX-01] What the API
 * says is fetched fresh each time; the last answer of each GET is kept only so that a lost connection
 * still shows something (marked x-admin-cache), and signing out deletes it. [ADMIN-19] Pushes become
 * notifications that open the screen they are about.
 */
function adminWorker(version) {
  const CACHE = `bamnat-admin-${version}`;
  const API = "bamnat-admin-api";
  const SHELL = ["/admin/", "/admin/app.js", "/admin/app.css", "/admin/icon.svg", "/admin/manifest.webmanifest", "/admin/install"];
  self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" })))).then(() => self.skipWaiting()));
  });
  self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== API).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()));
  });
  self.addEventListener("fetch", (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== "GET" || url.origin !== self.location.origin) return;
    if (url.pathname.startsWith("/api/admin/")) {
      // Sign-in state, photos and the setup check are never kept.
      if (/^\/api\/admin\/(health|join|inquiries\/[^/]+\/photos)/.test(url.pathname)) return;
      const key = new Request(url.href);
      event.respondWith(fetch(event.request).then(async (res) => {
        if (res.status === 200) {
          const copy = res.clone();
          event.waitUntil(caches.open(API).then((c) => c.put(key, copy)));
        } else if (res.status === 401) {
          event.waitUntil(caches.delete(API));
        }
        return res;
      }, async () => {
        const hit = await caches.open(API).then((c) => c.match(key));
        if (!hit) throw new TypeError("offline");
        const headers = new Headers(hit.headers);
        headers.set("x-admin-cache", "1");
        headers.delete("etag");
        return new Response(await hit.blob(), { status: 200, headers });
      }));
      return;
    }
    if (!url.pathname.startsWith("/admin/")) return;
    const fresh = fetch(event.request).then(async (res) => {
      if (res.ok) await caches.open(CACHE).then((c) => c.put(event.request, res.clone()));
      return res;
    });
    event.waitUntil(fresh.catch(() => {}));
    event.respondWith(caches.open(CACHE).then((c) => c.match(event.request, { ignoreSearch: true }))
      .then((hit) => hit ?? fresh.catch(() => caches.match("/admin/"))));
  });
  self.addEventListener("message", (event) => {
    if (event.data?.type === "forget") event.waitUntil(caches.delete(API));
  });
  self.addEventListener("push", (event) => {
    let m = {};
    try {
      m = event.data ? event.data.json() : {};
    } catch {
      m = { body: event.data?.text() ?? "" };
    }
    event.waitUntil(self.registration.showNotification(m.title || "밤낮 관리자", {
      body: m.body || "", tag: m.tag || "admin", renotify: Boolean(m.urgent), requireInteraction: Boolean(m.urgent),
      icon: "/admin/icon-192.png", badge: "/admin/icon-192.png", data: { url: m.url || "/admin/" },
    }));
  });
  self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const target = new URL(event.notification.data?.url || "/admin/", self.location.origin);
    if (target.origin !== self.location.origin || !target.pathname.startsWith("/admin/")) return;
    event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      const open = list.find((c) => new URL(c.url).pathname.startsWith("/admin/"));
      if (open) return open.focus().then((c) => c.navigate?.(target.href) ?? c);
      return self.clients.openWindow(target.href);
    }));
  });
}

const ICONS = {
  "/admin/icon-192.png": "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAMAAABlApw1AAAASFBMVEXOrXDMqm/Hp23Do2vCo2vBo2vBomvAoWq/oWq8nmmojmF/bVJHQT0gIi8YGywXGywWGisWGSsVGSsUGSsSFyoNEygKECcIDyast4RLAAAGVklEQVR42u2c23LrKgxAAbtgc68N+P//9EC6mzTNpYCJHc8RT57OtNVCEkICgaaDDwQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAewFYc2AAq83kw3xQAKutD2FWUjl7QIAo/eKUFByLbeVvAmD1HLwSnCKM5GKPZkJWu8VI3iHcj1gs+mhOHMWPkx+lH9iAuTfl/Fqb/QDMFMUfERlY31MyFjuwjb6zLH4y+wBY7WeZxKd9HIzIUGhAxkffEULO3uwBYOZF8m/x+wEVO4CO4ncIIcJlMNsDaK8Fwf/Ej/PP9WwL5z+qjw7DwAiuJqgGsGZRHFHW/xsMlxqQdWokw9cv91hWWhGq9t4gKR6+xe/jClQ6hzoI9P0Hov7cphowzgnUnae/p31XOoUnBdCzAkmlCuoAtNMcsfN/Twoo9uDoAeQyAxVLwAqAk/wX86mbwAiAdwK4I39FDE4aoLsA3MofLag4hqXBLzZEO7WVD5hb+Wl5DPi1Cg2Ie7vNKmRv5a/Uv50cR2OyIjqkbdRGccB68Vv+qIGqNTAupHEnQhkjaKyNY8UAerknf40FnaxxFpxgMgrla/MIVGq3P5eOiwWFuv9vpzBJKdXiNtqNXkfPC4AMtROoJx+80/V5aBmA8Rz/NqBkQ8rXS2CMWZNGozIHkOhW/moX2Dwnvm9AMQwLb6YjANxbgdbsArYGiJuXru/vAdT78MYAdz045WKHMKHr7e8hAfhdANqNG9dz6wCuE6irVfQgAEHc9YCjAKQY0NEDAzyIAUfSwH0XPgqA9ap/MI6xCv3MXw8ZBx4FgaMAPF6DDrIXMkE+VMAhdqPPXOAQ+YDx4rEGDpCR2Vlz8sgF1ubEmwA88+GdvRjlWdATH15TF9oMIDwF2NUJsgAe7+RW1UbfB2DPSJDpA+KZCe1qQ5kA/ClA7QnN+wDUnJG9FUD9Me+7AAx4LzduBFBxUv9eADV3JbaMA+F5HKi7rfJGgazyvtB7AewVjlvsRi9L6Q5GlAtAPv4E2KfClZ3QPMvILivR9ktpXk786Tj524Z2cYMGVYmfVoTl1gS5ADIPoN+cILeskuPFuxBkl9d5hhefCezbAeQ6wRdBmMy7AfxRl7gioEi4mus/L71yZmeVaUORgCGuys1Iu/mVPpBvQ6eINsrgipRgzKK0eyHAw2Pi+wQdFqrAE6z2QQr3Sg1MU14wvpjRKJ03Ok98F5RAdetvNkBuLDsrgSAufZj/9AVj/KJEj/hr40Duhu6HEgZCuDRLVMNDBmv0HELqwqmuCuSbUFZWc50gMIx49IXgrb69FxeFN84vWgqGyFB9VpsPUKyChBANiQmp3BL8bIzW5jRS59VnFD6cev9wN9D6fWzBhaeilfRiSB1GlEeIuNAskSON+OGdkkl6RBhblQsVANhpLlmIfqghMuCe89Rw9TXiFx8JitJ/9RANqHoLWHTpL38/ccPAeoIRQjiN9BUNhw7fPSAM13bQFN4bNTVGdAkNbBjY10gftM0BSRmAU6yjfeuxKhMtu3pcGs0yd07c1JfECm+vrzKiR9d1qPKbNYPa2d6/PFo/GFqVhBY3QHg1dqytA6yq5xW3oKQb7B+0ofxxBbVbApyiQU/bya/dqgS6po9saUawXv66Tr5WBCf5V1aR6nopIwFlbyF/bTdr9ORueAf5a/uJoyeP6+IBo0g0kL+6o1unhvSh3hFivim8a1DAq+6p196KajOiqXK02BYl1PpXDcwc5IAYq5p+xFWj87QVzzJYE5TApNiOWJ96P12jIvyqlz20Cz9exsguVXRCtTGf1QCTMWEWYz7CB6OYcLm4dmcga1+30f9eh6EZDDG7T6+QeN/yAGT9+0LpfR7JybnC8CgjHnqMRiF9ME1PPxq88JRqs+6rxhPFZDcUH+mnHUZ9qtL51s+gNXljy6au4FRlS6UeEk3lqvzQEYzwGKV3izPNz55avXJmohqCTcW2sTsXgPCpABRlF1L5xdtXHP61e2fu0+jZh3AqGQrBT+NUi1PaL8GZFx1dtn3pz2g9uUsN1Pv05WajzcsOXtu/tWi/qs+XOrT9nF444LFIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPi/AfwHwjw/dxGi3n0AAAAASUVORK5CYII=",
  "/admin/icon-512.png": "iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAMAAADDpiTIAAAASFBMVEXPrXDMqm7JqG3FpWzDo2vCo2vBo2vBomvAoWq/oWq0mGaHdFVQSEEmJzEYGywXGywWGisVGisWGSsVGSsUGSsRFioNEigJECcZgtFfAAASH0lEQVR42u2d6ZqbuBZFQZiIUVJKBt7/TVvClaqkkxpsMxzB2n/udXfydZX30pkQUubRqZXxFQAAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQAAAAXwEAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAKA5eSsscMVc88IQPTeD9M0GQMBZwPgJS78cZp837VNN7zg7pkAcMZex2nsu2B+qbJ2uDrcPQ0Awf0Q9k1c+SrLVJ21E/6fBgBn3BCXftsUWV7ouor+e/w/BwAvce0Pwf06y6L5uizrrCH+nwQAa67z2i/D0q/rYH5QrRozWJw9PgAvxo2TiWs/L+vqR3lTpZoe/08AgAuLf+jbJg9rvyrfpAvdjwZfjw6ANUNY/KHkL6q3tf8aALoJ/48OgA2xf178+jXvvyk2APh/cACsjbFfx8Vfln/5P5L/jw2AtePYtSq7/H/x3wpAM9AAHhmAV/tD7C//li4LCsBDA+DMzf6/Y/9rAhBVALogAFhSofLv2/wj+0UVAM5YNwT5+HQaAJax3wf7yw/tl1QABPOnUKgGhf8dvXEAsETpf+2arPzIfkETAOvnhxNtE9V2ffhoAOD56B/sL+oP7RczAXgxk+vaS5blRZDKM9V0/SS3GkgCABujf67+0fj9kQAkPAJ0PpKal3Vd6aCqqrXKmm4Q+3giBQDMOHR1Vn0c/WMLqDoJHaAdhkjqHz+qrousFdufZkks/8+jv5wEYAfT/INUXedNL3REnSWw/Nvi0+g/PwMU0QG46P8/Sa1VLTQGCAfA2bj8y0+jf6wAcgkdgPPDB/7fCBBZB8gGwAxjV+d1+aX/IkZAZmqzD3/WkAWuRIC7w79ps8tXy39+BiBgddmxy6vPyhSROxUEA3AL/19kfzkV4E9/bdQnAOhLSAIOAO4K/6H6+9J+KRWgmbqsTqBTSQWAOfx/Wf0Jiq1ubFX1BagCQ4BUAMwc/n98w3+tQnX1U8AIoL+kQWoKAIT0/53qX9LX+mUGmJsVAPjeanJTlxff8z8EAC8gAIQeoM2rr4sVca8sSQTADqGh1lWZUgAIP/QXJUAEQGAfIBAAM4Tyr9JlUgHADX1TfPUzV6oTt2lZHgCh/G+y74X/ecAmIwAAwHL+903+bf9DAPgpIQAAwILtX62+7b+czgoAdvFf0GyFInAH/yVNV2kDd/C/lPEYkEHQXv4L2QfwmgIYBW++/qX0gLcykIdBT/d/d/ovK6fyOPjJ7y/0Uff5L+wbZUPIkynU3Ot/qVUvqa1mS9hTcxR3x/z311illbWi2BT6VBd9r//ylhTbwtdZOx9LWk7lxZDHC2hV3eu+uAzgeTXscf/zUpepZ4AbAbwc+kD+7+uiKtPPALc6gNfD7/3Kru6ODQC/Hwgg8gvlgIiNCkC5Z4JyRMy9BcAj/ovcXPGGAIdEfd//sdcXfb//Mp+svOc1jon7fgGgHgoAufhTgTkocr0CQOxkPT1lO/v/WAFQlj+KnnPBkwfADn1d6Ef8F/JKOAA8OwJq88cCQAIlAACsmAC4GeQIAMQEcNHlgxGgm4gAqQPwcAKQur0KAO5MAHlVPhgA4iuBKGkA4uaJQj8KADVg8gA8PAKiBjwEAHbs1KPrX9gbIQDwWAvQPlwByH4UCAArjwCkvmcNAPdUgFfTPJEBRL5nDQBbVYA0AckD8K3zVADguAA8GQDYDJA4ACEAlE8JANIG4NkAQBeYNgBzANBPAZADQMIAPB0A2A+WNAD26QDAfrCkAXg+AAh/JwAAvqoAHt4HBAAHAOD5AMAkOGUA4lOA4rkAwCAwZQDiRrAnAwAApAyAHRtVAcBpAYgbgZ71HwASBmCBEhAAEgZggR4QAFIG4LmdYABwgAjQPl8CAECyANixV08nAABIF4BFSkAASBaAn1fbLBEBtGqsZxScHgD2iddBeRh0AAAWygAAkCgAT70PzIaQ9AFYZAz8ikA/UAUmB8BSGYBNoanWAG6RHoBt4YkCYMe+WMZ/XgxJEoBlngMAQMIRoM0XqgHjLcykgMQAePaNYGbBiQPwxb2ad8+C2RacGADLNYGMApME4GVolhoD8XJgggAsWQLcjokDgKQAWHAOzEGRKQKw4BSAPjDNCLDYFOB2WPTwgnkJAbDEK4G0AQkDYId+Oft5HJQgAAu8E8rTgIQBWHIMxDA4TQAWrAE5IyI5ABadA/7aFQYAyQCw7ByQIiA5AOJuoB/lsgAwC0wKgC5fOANQBKQEwLKDYB4IJghAuzQAFAFppYB24RRQVqqlDUgGgOW7QG6PTQmA5btA7g5MDACzPABMg9MBYPkxADkgMQCWHgPQB6QGgFoeAPaFJQPACnOgOQdU5IBUAGhXAIAccHYAKtVccfC8KYCdgUlFgKpcIwfwTDiRLqDN1wCAUcDJAaAMPDsAWjXe/8TF0wLAE6GzAxA6QfqAMwNAJ3h6AHgofG4ACAGnB4AQcG4ACAGnB4AQIB6AlZ4Gvh8ezyxAOgDdmgDEp8KMA88bARgHnh0A7hBKIAWsVwSyLyCFLmCNXcG/hYCSV4WlA7BqBJgPjsRLuQC4oa8uulw1CVAHigZghXcD/94cRhIQHAFWBiC0gswD5QKw7iyYJAAAJAHhAKw8CXpLAkyDxALQrQ4A4yDRKaBbOwXMm8QpA6QCsPB1AVwjkRgAC18YQhmQGgDeDa1aPwdQBogFYIs2gDJANADdNgAUdT9CgDwAtmkDYhJQjWEeJLEG6OuL3oSAvBk4R14cAN7/bNQmAMRCcHQQIA2ArapAWgGxAHRbAcCDQYkArHJc8EetgGafuDgA1t8U9McmUQgQVwRusCUAAiQDsGEVCAEyI0C3WQqAAIEAbPRAEAKkArBtEfCLAMtESAwAW04CXglQ7eh5LiAFADf05bbSVdZOA2lACABbbQr53xYhw9NhKQBsngPmqXDTUwoKASBOg3W5NQF5TSkoBICQBLZ6JPw7AYXqRgoBEQBsOwx8fzSUUQjIACDkgO0jwK9CwNIPCkgB2/cBrxsFCzlpwJ4YgD36gNsRMmXW9jJqQTPKenlp4wiw9lExH+lHSAOdgKGQ9VPXi3p7Kdv295+2fR7wRzeQ7x4EXFj+rbD3F7cFIB4YWO8DQKljENi1EjDXqW+k7VjdFoCtnwn/Kwj4nb5/ayfTFkraiYbZxotgl1HA+9OhvO78Lh1hiP5j12TxQENZY8mNAYhlYLlbCIhBIOaB0bit7R+mvs1VLe9c640BiCFgrzLwtRJQeRv7Abe1/Ze8quKx1tdTR4DVjw3+xkygylUoBTZDYLa/q7OyEvnSUrb5f3FodiagrHRWzgjYrVZ/k13qeY9SKe4Mm80B2LMTfC8FyhmB0a68HO28+pusqPXr/hRxmxM2B2DfTvB3BHTbjdN1vUzgjBvn1f9qfzzUXN6p9tungH07wT8QUG1np8Hal5UW/9C1+t3+eL+ZvLMrtgdgg5Ojv4tAlWdN24/LVwPO+LD4u1ZlZf3+q4q84XB7AMSEgGhJrbKq7cw0+uUYCKF/mHyM/Ur//nvKvOBwBwDkhIB5LnDJ86bt/MyAWyDyB/eHUPipEPv/7HZkXnG6AwCSQsBtMFBkKjDwcwr1gHmiIHDGXsfoflvGxf8/xoXecLoHAKJCwC0MaDUzEOqB8WofiAQu/CU/TJMJkV9neVn//etVeQcA77MASSHgjYGYC3ozhkgQDXXfigUv4Y+GsB/+kg9LP0R+Vdb/GnRJveJ4FwB++mujdFlKY6Aq8qxo2kBBWMzTEDEw1rp/kuCctYESe43ej30XzC+zPET+D8ecMu+z2AUAiSHg9XlxCAR5ltchFIRYMHs7DsPVRrPfFD9dh2EM/3YaTfQ+rvx56euPdyYLPbpsHwDi/uC8KkUqQFAVwc9ch1gQOOj6kBXG2e1fCp9sf3O+ber4Zwv9ifml5JPsdwJg/4eCn+8hreq6uhR5Hta1appIQhvdbt/+T1M387/O1SX+2a/ymdyT63YCQFor+EEsmL0tCpXn0ew3hU+qKEL/WFeV/s7hd4JvuN8LgO0OD17g3bJAQmThTeFT+If3jBrkHlezFwBi68B13k1rJ6lvpmX7/af33xmyVQiRfKXhfgDE4wLOEgAEn1e2YwRIoQ5c5FmD6LvtdwQg7g06QRLQZdELvtd6RwC8kT0MWLACNESA0yaBeIWNdwBw1iQQO4BR8uEkuwIQkkDoBPSxE4DwE4v3BeDw46B4g5Xsw4l2BiAeIX1gAqp4i6EDgE+fCRy4DJg7QOEH1O0NwKHLgBSuL9sdgAOXAfILABEAHLYMqHPxBYAMANzgGnU8Aqo0LjIXAEB8LlgXRysEU7m9UgIAsRDUxcEKwSqR+2tFADCfGnGsECB5E5BAAA7XCkT/07jDXggAB2sF6iw0AGmcTy8FAOcPRECtkvFfDADeXYfmIATUKokGUBgA3g7mGAQk5b8gAI5CQPQ/oZvqBAHgzREISMx/UQAcgYDU/JcFQPoEJOe/MABSJyA9/6UBkDYBof9P7qZiaQCEXmBIdSIUL6hM7opScQB4ex3brNJJ+m/Su6RYHgBxKtzlye0P0FXWugQvqRYIgHd26nSRVhqoStWOQ4L3E2cifyoz9XVSu8TqIu9kXE17DADiHqHQDPxIxv+87qY0b6gXCkAgwLXzPUsppP/5fvo0/RcLQGgHx65IIg1Ul6y1Y6L+ywUgloJJpIFa6W66Wg8AK6QBIz4NvIZ/5wFglbmw9DRQF6H7Tzb8iwfgNQ1UldjlH6v/dMO/fABuaUAJDQJx+Scd/lMAwNvr1DUSK4H5LvpxMN4DwLppwEymDZWAlrb887D8rfUAsH4auMZKQNTDgUpnzQGWfyIAhCAwDl2d6UpO8adak+bsP0kAQiXgp75VuYg8oGuVtd10hOWfDgBxJjB1bSagFAi1f9MNo3EeALbPA7/dxb6T/WXWhOjvrPcAsHkecJPfF4Fgfxlq/6vxHgB2yQOxFNgLgR+v9g/mxQPAjqXAjMDm42Edcv/Nfuc9AOxZCswI5GpTBOIN4/UB7U8QgBsCpmvUP+/oXif26zxvgv3j4exPEoCIwHUaurbaJBNUIfarpjPTYK33ACBDL8aPcybIL3W1aubXKsT+bgiVv/MeACQ1hTZmgrbOlF4pFei6UplquxD73aEq/2MAcMsEU9+1KjJQLe9+kcXMPxw09h8AgJAJYhgYQioIXpX1Yi8U6ipGftW0IfOHxe+8BwDBYcCNkwtxQGd5DAT6efOrIn91P2R+64+t7AC/g40V4RAYaFSWF2Vd6x+Pr/zitvR7N43D4d0/CAAzA26YYk0Ys0GmLlV9XyzQVVj4pYorP7o/TqM31nkPACnlAnsNxpkYCeo8C7FgxqDSn4Ggo/PB+iJ4n+vgfReKvlD1nWHtHw2AuSYMgWCcYkkQY4GOGORFACHE9jrC8Kb69rksovOBlWb23gSAhrMs/SMCMAcCa4wPq3gazIxB2zRVHl2OMLwpfsxUNL5po/X9/Deu9lzmHxKAXxRYN0RTx6GPIHTtDMO7uuh7WPN9jBhx3Z/Q+wMDcEsIMRhEDoYpmhxsftftczA+Om/sOb0/OgC/ooFzNphsos/vev3szuv8aQBAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAPgKAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAAICvAAAQAKCz6j9r5bu6z+vB6gAAAABJRU5ErkJggg==",
};
