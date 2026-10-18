// [ADMIN-01][ADMIN-03][ADMIN-04] The developer app: one installable web app at /admin/ for phones, the
// unfolded Fold and computers. Admins sign in with an account the owner invited and approved, and see
// only the areas the owner gave them; the owner signs in with ADMIN_PASSWORD and manages admins.
//
// This module has no imports, so `npm run bundle` can inline it into one worker.js for the dashboard.

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

/** The app's files with their tags, made once per worker copy. The phone keeps the app's own files under a name that changes with them. */
function adminFiles() {
  if (builtFiles) return builtFiles;
  const app = `(${adminApp})();\n`;
  const version = hashText(app + ADMIN_CSS + ADMIN_HTML + JSON.stringify(MANIFEST));
  const list = {
    "/admin/app.js": [app, "text/javascript; charset=utf-8"],
    "/admin/sw.js": [`(${adminWorker})(${JSON.stringify(version)});\n`, "text/javascript; charset=utf-8"],
    "/admin/app.css": [ADMIN_CSS, "text/css; charset=utf-8"],
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
  display_override: ["window-controls-overlay", "standalone"],
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
<meta name="theme-color" content="#15192B">
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
const ADMIN_CSS = `
:root{--bg:#F5F3EE;--surface:#fff;--raised:#fff;--fg:#14182A;--muted:#5B6075;--faint:#8A8EA0;--line:#E3DFD6;--line-strong:#D2CCBF;
--soft:#EFEBE3;--hover:#F2EFE8;--accent:#2B57E0;--accent-soft:#E6ECFD;--on-accent:#fff;--gold:#B8955A;--gold-soft:#F4EBDC;--nav:#15192B;
--nav-fg:#C9CCDA;--nav-on:#fff;--nav-hi:rgba(255,255,255,.08);--ok:#17784A;--ok-bg:#E1F2E8;--warn:#8A5300;--warn-bg:#FBEED5;--bad:#BE3326;
--bad-bg:#FBE5E2;--info:#2B57E0;--info-bg:#E6ECFD;--shadow:0 1px 2px rgba(20,24,42,.05),0 4px 16px rgba(20,24,42,.05);
--shadow-lg:0 20px 60px rgba(10,12,22,.28);--r:16px;--r-sm:12px;--r-lg:22px;--focus:0 0 0 3px color-mix(in srgb,var(--accent) 40%,transparent)}
@media (prefers-color-scheme:dark){:root{--bg:#0E111C;--surface:#161A28;--raised:#1C2131;--fg:#EEEAE2;--muted:#A3A7B8;--faint:#7A7F93;--line:#272D40;
--line-strong:#343B52;--soft:#1F2435;--hover:#20263A;--accent:#8EA8FF;--accent-soft:#1E2747;--on-accent:#0B1022;--gold:#D2B47A;--gold-soft:#2C2618;
--nav:#0A0D17;--nav-hi:rgba(255,255,255,.06);--ok:#7DD3A3;--ok-bg:#12302A;--warn:#F0C272;--warn-bg:#352A12;--bad:#FF8F84;--bad-bg:#3A1C1B;
--info:#8EA8FF;--info-bg:#1E2747;--shadow:none;--shadow-lg:0 20px 60px rgba(0,0,0,.6)}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;color-scheme:light dark;scroll-padding-top:5rem}
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
color:var(--muted);font-size:.74rem;font-weight:700;cursor:pointer;text-decoration:none;min-height:3.4rem;justify-content:center}
.tab{min-width:0}
.tab .nav-label{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.tab svg{width:1.45rem;height:1.45rem;padding:.25rem min(1.1rem,3vw);box-sizing:content-box;border-radius:999px;transition:background .2s,color .2s}
.tab[aria-current=page]{color:var(--fg)}
.tab[aria-current=page] svg{background:var(--accent-soft);color:var(--accent)}
.badge{min-width:1.25rem;height:1.25rem;padding:0 .4rem;border-radius:.65rem;background:var(--bad);color:#fff;font-size:.7rem;font-weight:800;
line-height:1.25rem;text-align:center;font-style:normal}
.tab .badge{position:absolute;top:.1rem;left:calc(50% + .55rem)}
.badge:empty{display:none}
@media (min-width:45rem){
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
@media (min-width:68rem){
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
.card>h2,.card-head h2{font-size:1rem;font-weight:800}
.card-head{display:flex;align-items:center;justify-content:space-between;gap:.75rem;margin:0 0 .6rem}
.card h3{font-size:1.06rem}
.grid{display:grid;gap:.75rem}
@media (min-width:45rem){.grid.two{grid-template-columns:1fr 1fr}.grid.two>.card{margin:0}}
@media (min-width:68rem){.cols{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:1rem;align-items:start}.cols>*{min-width:0}}
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
font-size:.74rem;font-weight:800;white-space:nowrap;line-height:1.5}
.pill.warn{background:var(--warn-bg);color:var(--warn)}
.pill.ok{background:var(--ok-bg);color:var(--ok)}
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
@media (max-width:44.99rem){.tile{min-height:0;padding:.7rem .85rem;gap:.15rem}.tile .v{font-size:1.5rem}.tile .d{display:none}}
/* The phone lists what waits; wider screens show it as tiles, with the activity beside. */
@media (max-width:44.99rem){.tiles{display:none}}
@media (min-width:45rem){.todo-card{display:none}}
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
@media (min-width:45rem){.composer{bottom:1rem}}
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
@media (max-width:44.99rem){dialog{margin:auto 0 0;width:100vw;max-width:100vw;border-radius:var(--r-lg) var(--r-lg) 0 0;padding-bottom:env(safe-area-inset-bottom)}
@keyframes pop{from{transform:translateY(100%)}to{transform:none}}}
.radios{display:grid;gap:.5rem;margin:.4rem 0}
.radio{display:flex;align-items:center;gap:.6rem;margin:0;padding:.75rem;border:1px solid var(--line-strong);border-radius:var(--r-sm);font-weight:600;cursor:pointer;font-size:.92rem}
.radio input{flex:none;width:1.25rem;height:1.25rem;accent-color:var(--accent)}
.radio:has(input:checked){border-color:var(--accent);background:var(--accent-soft)}
.kbd{display:inline-block;min-width:1.6rem;padding:.05rem .4rem;border:1px solid var(--line-strong);border-bottom-width:2px;border-radius:.4rem;font-size:.8rem;
font-weight:800;text-align:center;background:var(--surface)}
.keys{display:grid;grid-template-columns:auto 1fr;gap:.5rem .9rem;align-items:center;font-size:.9rem;margin:0 0 .5rem}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
@media (forced-colors:active){.btn,.seg,.tab,.card{border:1px solid CanvasText}}
`;

/** The app in the browser. Serialized into /admin/app.js, so it uses nothing from this module. */
function adminApp() {
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
  };
  /** Where each screen lives and who sees it; badges count what waits for someone there. */
  const NAV = [
    { key: "home", label: "홈", group: "main" },
    { key: "review", label: "검토", area: "review", group: "main", badge: (o) => (o.pending ?? 0) + (o.asks ?? 0) + (o.flagged ?? 0) },
    { key: "inquiries", label: "문의", area: "inquiries", group: "main", badge: (o) => o.inquiries ?? 0 },
    { key: "companies", label: "회사", area: "companies", group: "main" },
    // [COMM-06] The talk on rule sets: what people flagged first.
    { key: "community", label: "토론", area: "community", group: "main", badge: (o) => o.flaggedPosts ?? 0 },
    { key: "rewards", label: "보상", area: "rewards", group: "main", badge: (o) => o.owed ?? 0 },
    { key: "admins", label: "관리자", owner: true, group: "manage", badge: (o) => o.admins?.waiting ?? 0 },
    { key: "audit", label: "활동 기록", area: "audit", group: "manage" },
    { key: "settings", label: "설정", group: "manage" },
  ];
  const LEVEL_NAMES = { none: "없음", view: "보기", edit: "고치기" };
  const KIND_NAMES = { question: "질문", bug: "문제 신고", problem: "문제 신고", idea: "제안" };
  const STATUS_NAMES = { open: "답 대기", answered: "답함", closed: "닫힘" };
  const ACTION_NAMES = {
    login: "로그인", login_failed: "로그인 실패", logout_all: "모든 기기에서 로그아웃", password: "비밀번호 바꿈",
    invite: "관리자 초대", invite_remove: "초대 취소", join: "관리자 계정 만듦", admin_update: "관리자 권한·상태 바꿈", admin_remove: "관리자 삭제",
    adopt: "제보 채택", reject: "제보 거절", answer: "앱에 없는 방식에 답함", company_create: "회사 만듦", company_update: "회사 고침",
    alias_add: "다른 이름 넣음", alias_remove: "다른 이름 지움", merge: "회사 합침", rule_withdraw: "규칙 내림", rule_restore: "규칙 다시 올림",
    reward_paid: "보상 지급", reward_void: "보상 취소", reward_owed: "보상 대기로 되돌림", settings: "채택 설정 바꿈",
    post_autohide: "신고로 글이 가려짐", post_show: "글 다시 보이기", post_hide: "글 가림", post_delete: "글 지움", post_mute: "글쓴이 30일 쓰기 막음",
    inquiry_reply: "문의에 답함", inquiry_status: "문의 상태 바꿈", inquiry_close: "문의 닫음", inquiry_open: "문의 다시 엶",
  };
  const ERRORS = {
    exists: "이미 있어요",
    company: "회사 이름을 두 글자 이상, 40자 안으로 적어 주세요",
    not_found: "이미 처리됐거나 없는 항목이에요",
    forbidden: "다시 열어서 해 주세요",
    no_permission: "이 일을 할 권한이 없어요. 최고 관리자에게 부탁해 주세요",
    merge: "합칠 수 없는 회사예요",
    active: "이 부서에는 지금 쓰는 규칙이 있어요. 그 규칙을 먼저 내려 주세요",
    too_large: "내용이 너무 길어요",
    offline: "인터넷에 연결되지 않았어요",
    no_database: "데이터베이스가 연결되지 않았어요",
    email: "메일 주소를 다시 확인해 주세요",
    perms: "권한을 다시 골라 주세요",
    name: "이름을 1~30자로 적어 주세요",
    password: "비밀번호는 10자 이상이어야 해요",
    weak_password: "더 다양한 글자로 비밀번호를 만들어 주세요. 메일 주소와 같으면 안 돼요",
    rate_limited: "잠시 뒤 다시 해 주세요",
    invite: "초대 링크가 만료됐거나 이미 쓰였어요. 최고 관리자에게 새 링크를 부탁해 주세요",
    text: "답을 적어 주세요",
    server: "서버에 문제가 생겼어요. 잠시 뒤 다시 해 주세요",
    adoptAgree: "맞았어요 비율은 50~100% 사이로 정해 주세요",
    action: "이 글에는 할 수 없는 일이에요",
  };
  const TEMPLATES = [
    ["확인 중", "알려 주셔서 고마워요. 지금 확인하고 있어요. 고치는 대로 다시 알려 드릴게요."],
    ["고쳤어요", "말씀하신 문제를 고쳤어요. 앱을 최신 버전으로 업데이트한 뒤 다시 확인해 주세요."],
    ["더 알려 주세요", "정확히 확인하려면 조금 더 알려 주세요. 언제, 어떤 화면에서, 무엇을 눌렀을 때 생겼는지 적어 주시면 큰 도움이 돼요."],
    ["설정 안내", "설정에서 바로 바꿀 수 있어요. 설정 > 해당 항목에서 원하는 값으로 바꿔 보세요."],
  ];

  const S = {
    phase: "boot", // boot, login, app, join, install
    me: null, overview: null, health: null,
    route: { name: "home", id: null },
    data: {}, // per screen
    reviewFilter: "all", inquiryStatus: "open", rewardTab: "owed", query: "", postFilter: "flagged",
    draft: null, loginMode: "account", error: "", busy: 0, online: navigator.onLine,
    join: null, // invite info on the join page
    installPrompt: null, installed: matchMedia("(display-mode: standalone)").matches || navigator.standalone === true,
  };

  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const icon = (paths) => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths}</svg>`;
  const day = (iso) => (iso ? new Date(iso).toLocaleDateString("ko-KR", { month: "long", day: "numeric" }) : "");
  const moment = (iso) => (iso ? new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" }) : "");
  const clock = (iso) => new Date(iso).toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
  function ago(iso) {
    if (!iso) return "";
    const s = (Date.now() - new Date(iso).getTime()) / 1000;
    if (s < 60) return "방금";
    if (s < 3600) return `${Math.floor(s / 60)}분 전`;
    if (s < 86400) return `${Math.floor(s / 3600)}시간 전`;
    if (s < 7 * 86400) return `${Math.floor(s / 86400)}일 전`;
    return day(iso);
  }
  const can = (area, level = "view") => Boolean(S.me && (S.me.owner || ["none", "view", "edit"].indexOf(S.me.perms?.[area] ?? "none") >= ["none", "view", "edit"].indexOf(level)));
  const visible = (n) => (n.owner ? S.me?.owner : n.area ? can(n.area) : true);
  const href = (name, id) => `#/${name}${id ? `/${encodeURIComponent(id)}` : ""}`;
  let toastTimer = 0;
  let searchTimer = 0;
  let pendingConfirm = null;
  let gKey = 0;

  // ---- talking to the server ----

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
      if (res.status === 401 && data.error === "login" && S.phase === "app") {
        S.phase = "login";
        S.error = "로그인이 끝났어요. 다시 로그인해 주세요.";
        render();
        throw Object.assign(new Error("login"), { quiet: true, data });
      }
      if (!res.ok) throw Object.assign(new Error(data.error || "server"), { data, status: res.status });
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
    toastTimer = setTimeout(() => toastBox.classList.remove("show"), 3000);
  }

  function fail(e) {
    if (!e.quiet) toast(ERRORS[e.message] || `문제가 생겼어요 (${e.message})`);
  }

  // ---- routes ----

  function readRoute() {
    const [name, id] = location.hash.replace(/^#\/?/, "").split("/");
    const known = [...NAV.map((n) => n.key), "account", "more"];
    const route = { name: known.includes(name) ? name : "home", id: id ? decodeURIComponent(id) : null };
    const nav = NAV.find((n) => n.key === route.name);
    if (nav && !visible(nav)) route.name = "home";
    return route;
  }

  function go(name, id) {
    const next = href(name, id);
    if (location.hash === next) load();
    else location.hash = next;
  }

  /** Fetches what the current screen shows; the home numbers come along so badges stay right. */
  async function load({ quiet = false, overview = true } = {}) {
    const { name, id } = S.route;
    const key = `${name}/${id ?? ""}`;
    try {
      const jobs = overview ? [api("/overview").then((o) => { S.overview = o; S.me = o.me; })] : [];
      if (name === "home" && can("audit")) jobs.push(api("/audit").then((d) => { S.data.feed = d; }));
      if (name === "review") jobs.push(api("/queue").then((d) => { S.data.queue = d; }));
      if (name === "inquiries" && !id) jobs.push(loadInquiries());
      if (name === "inquiries" && id) jobs.push(api(`/inquiries/${id}`).then((d) => { S.data.inquiry = d; }));
      if (name === "companies" && !id) jobs.push(api(`/companies?q=${encodeURIComponent(S.query)}`).then((d) => { S.data.list = d; }));
      if (name === "companies" && id) jobs.push(api(`/companies/${id}`).then((d) => { S.data.company = d; }));
      if (name === "community") {
        // An id is one talk: company.setup, opened from the review screen.
        const [company, setup] = (id ?? "").split(".");
        jobs.push(api(id ? `/posts?company=${company}&setup=${setup}` : `/posts?status=${S.postFilter}`).then((d) => { S.data.posts = d; }));
      }
      if (name === "rewards") jobs.push(api(`/rewards?status=${S.rewardTab}`).then((d) => { S.data.rewards = d; }));
      if (name === "admins") jobs.push(api("/admins").then((d) => { S.data.admins = d; }));
      if (name === "audit") jobs.push(api("/audit").then((d) => { S.data.audit = d; }));
      if (name === "settings") jobs.push(health().then((h) => { S.health = h; }));
      const results = await Promise.allSettled(jobs);
      const failed = results.find((r) => r.status === "rejected");
      if (failed && !quiet) fail(failed.reason);
    } catch (e) {
      if (!quiet) fail(e);
    }
    // A screen left while it loaded does not repaint over the new one.
    if (`${S.route.name}/${S.route.id ?? ""}` === key && S.phase === "app") render({ keepScroll: true });
  }

  async function loadInquiries() {
    try {
      S.data.inquiries = await api(`/inquiries?status=${S.inquiryStatus}`);
      S.data.inquiriesMissing = false;
    } catch (e) {
      // The server has no inquiry box yet: the screen says so instead of failing.
      if (e.message === "not_found") S.data.inquiriesMissing = true;
      else throw e;
    }
  }

  // ---- the frame around every screen ----

  function render({ keepScroll = false } = {}) {
    const y = window.scrollY;
    if (S.phase === "boot") return;
    if (S.phase === "login") {
      app.innerHTML = loginView();
      document.querySelector(".gate input:not([disabled])")?.focus();
      return;
    }
    if (S.phase === "join") {
      app.innerHTML = joinView();
      return;
    }
    if (S.phase === "install") {
      app.innerHTML = installView();
      return;
    }
    const { title, sub, back } = heading();
    const o = S.overview ?? {};
    const navLink = (n, cls) => {
      const count = n.badge ? n.badge(o) : 0;
      const on = S.route.name === n.key || (n.key === "more" && !NAV.slice(0, 4).some((x) => x.key === S.route.name) && S.route.name !== "home");
      return `<a class="${cls}" href="${href(n.key)}"${on ? ' aria-current="page"' : ""} data-nav="${n.key}">${icon(P[n.key])}
        <span class="nav-label">${esc(n.label)}</span><i class="badge" data-badge="${n.key}">${count || ""}</i></a>`;
    };
    const items = NAV.filter(visible);
    const groups = [["main", "업무"], ["manage", "관리"]];
    // The phone's bottom bar: the first four screens this admin can open, and the rest under 더 보기.
    const bottom = items.slice(0, 4);
    const rest = items.slice(4);
    const moreCount = rest.reduce((n, x) => n + (x.badge ? x.badge(o) : 0), 0);
    app.innerHTML = `
      ${S.online ? "" : '<div class="offline" role="status">인터넷 연결이 끊겼어요. 다시 연결되면 저절로 새로 고쳐요.</div>'}
      <a class="skip" href="#main">본문으로 건너뛰기</a>
      <div class="shell">
        <aside class="side" aria-label="메뉴">
          <div class="brand-row"><img src="/admin/icon.svg" alt=""><b>밤낮 관리자</b></div>
          ${groups.map(([g, label]) => {
            const list = items.filter((n) => n.group === g);
            return list.length ? `<p class="group">${label}</p><ul class="nav">${list.map((n) => `<li>${navLink(n, "")}</li>`).join("")}</ul>` : "";
          }).join("")}
          <div class="foot">
            <a class="who" href="${href("account")}"${S.route.name === "account" ? ' aria-current="page"' : ""}>
              <span class="avatar" aria-hidden="true">${esc((S.me?.name ?? "?").slice(0, 1))}</span>
              <span><b>${esc(S.me?.name ?? "")}</b><small>${S.me?.owner ? "최고 관리자" : esc(S.me?.email ?? "")}</small></span></a>
            <!-- On the narrow rail the account link is an icon; the wide sidebar shows the name card instead. -->
            <ul class="nav compact-only">${navLink({ key: "account", label: "내 계정" }, "")}</ul>
          </div>
        </aside>
        <div class="content">
          <header class="top">
            <div class="bar">
              ${back ? `<a class="icon-btn" href="${back}" aria-label="뒤로">${icon(P.back)}</a>` : '<img class="mark" src="/admin/icon.svg" alt="">'}
              <h1 tabindex="-1" id="title">${esc(title)}${sub ? `<span class="sub">${esc(sub)}</span>` : ""}</h1>
              <button class="icon-btn" data-act="shortcuts" aria-label="단축키" title="단축키 (?)">${icon(P.help)}</button>
              <button class="icon-btn" data-act="refresh" aria-label="새로 고침" title="새로 고침 (R)">${icon(P.refresh)}</button>
            </div>
            <div id="progress" class="progress${S.busy ? " on" : ""}"></div>
          </header>
          <main class="main" id="main" tabindex="-1">${view()}</main>
        </div>
      </div>
      <nav class="tabs" aria-label="메뉴">${bottom.map((n) => navLink(n, "tab")).join("")}
        ${navLink({ key: "more", label: "더 보기", badge: () => moreCount }, "tab")}</nav>`;
    if (keepScroll) window.scrollTo(0, y);
  }

  function updateBadges() {
    const o = S.overview ?? {};
    for (const n of NAV) {
      document.querySelectorAll(`[data-badge="${n.key}"]`).forEach((el) => { el.textContent = (n.badge ? n.badge(o) : 0) || ""; });
    }
  }

  function heading() {
    const { name, id } = S.route;
    if (name === "companies" && id) return { title: S.data.company?.name ?? "회사", sub: "회사·규칙", back: href("companies") };
    if (name === "inquiries" && id) {
      const q = S.data.inquiry;
      return { title: q ? KIND_NAMES[q.kind] ?? "문의" : "문의", sub: q ? `${STATUS_NAMES[q.status] ?? ""} · ${moment(q.createdAt)}` : "", back: href("inquiries") };
    }
    if (name === "community" && id) {
      const first = S.data.posts?.posts?.[0];
      return { title: first ? first.company.name : "토론", sub: first ? `${first.dept || "회사 전체"} 규칙의 토론` : "규칙 토론", back: href("community") };
    }
    if (name === "account") return { title: "내 계정", back: matchMedia("(min-width:45rem)").matches ? null : href("more") };
    if (name === "more") return { title: "더 보기" };
    const n = NAV.find((x) => x.key === name);
    const back = ["rewards", "admins", "audit", "settings"].includes(name) && !matchMedia("(min-width:45rem)").matches && NAV.filter(visible).indexOf(n) >= 4
      ? href("more") : null;
    return { title: name === "home" ? "밤낮 관리자" : n?.label ?? "", back };
  }

  function view() {
    const { name, id } = S.route;
    if (name === "home") return homeView();
    if (name === "review") return reviewView();
    if (name === "inquiries") return id ? inquiryView() : inquiriesView();
    if (name === "companies") return id ? companyView(S.data.company) : companiesView();
    if (name === "community") return communityView();
    if (name === "rewards") return rewardsView();
    if (name === "admins") return adminsView();
    if (name === "audit") return auditView();
    if (name === "settings") return settingsView();
    if (name === "account") return accountView();
    if (name === "more") return moreView();
    return "";
  }

  const empty = (title, text, action = "") => `<div class="empty">${icon(P.moon)}<h2>${esc(title)}</h2>${text ? `<p>${esc(text)}</p>` : ""}${action}</div>`;
  const skeleton = (n = 2) => Array.from({ length: n }, () => '<div class="card skeleton"></div>').join("");
  const lines = (list) => `<dl class="lines">${list.map(([label, text]) => `<div><dt>${esc(label)}</dt><dd>${esc(text)}</dd></div>`).join("")}</dl>`;
  const seg = (act, key, current, label, n) =>
    `<button class="seg" data-act="${act}" data-key="${key}" aria-pressed="${current === key}">${esc(label)}${n ? `<span class="n">${n}</span>` : ""}</button>`;

  // ---- home ----

  function homeView() {
    const o = S.overview;
    if (!o) return skeleton(3);
    const hour = new Date().getHours();
    const hi = hour < 6 ? "늦은 밤이에요" : hour < 12 ? "좋은 아침이에요" : hour < 18 ? "좋은 오후예요" : "좋은 저녁이에요";
    const tiles = [];
    const tile = (k, v, d, to, hot) => tiles.push(`<a class="tile${hot && v ? " hot" : ""}" href="${to}"><span class="k">${esc(k)}</span>
      <span class="v">${v ?? 0}</span><span class="d">${esc(d)}</span></a>`);
    if (can("review")) {
      tile("검토 대기", o.pending, "채택이나 거절을 기다려요", href("review"), true);
      tile("앱에 없는 방식", o.asks, "답을 기다려요", href("review"), true);
      tile("달라요", o.flagged, "다시 볼 규칙", href("review"), true);
    }
    if (can("inquiries")) tile("문의", o.inquiries, "답을 기다려요", href("inquiries"), true);
    if (can("community")) tile("신고된 글", o.flaggedPosts, "토론에서 확인할 글", href("community"), true);
    if (can("rewards")) tile("보상 대기", o.owed, "드릴 프로 기간", href("rewards"), false);
    if (S.me.owner) tile("승인 대기 관리자", o.admins?.waiting, "가입한 관리자", href("admins"), true);
    const stats = [];
    if (can("companies")) stats.push(["회사", o.companies], ["쓰는 규칙", o.rules]);
    if (can("review")) stats.push(["모든 제보", o.reports]);
    if (can("community")) stats.push(["토론 글", o.posts]);
    const todo = [];
    const add = (n, text, to, tone) => n && todo.push(`<li><a href="${to}"><span class="dot ${tone}" aria-hidden="true"></span>${esc(text)}<small>${n}건</small></a></li>`);
    if (S.me.owner) add(o.admins?.waiting, "승인을 기다리는 관리자", href("admins"), "bad");
    if (can("inquiries")) add(o.inquiries, "답을 기다리는 문의", href("inquiries"), "bad");
    if (can("review")) {
      add(o.pending, "채택을 기다리는 제보", href("review"), "");
      add(o.asks, "앱에 없는 방식", href("review"), "warn");
      add(o.flagged, "달라요가 달린 규칙", href("review"), "warn");
    }
    if (can("community")) add(o.flaggedPosts, "신고된 토론 글", href("community"), "warn");
    if (can("rewards")) add(o.owed, "드릴 보상", href("rewards"), "");
    // Sign-ins would crowd out the work; they stay on the activity screen.
    const feed = S.data.feed?.entries?.filter((e) => !e.action.startsWith("login")).slice(0, 8) ?? null;
    return `<div class="hello"><h2>${esc(hi)}, ${esc(S.me.name)}님</h2>
        <p>${S.me.owner ? "최고 관리자로 로그인했어요. 모든 화면과 관리자 관리를 쓸 수 있어요." : `내 권한: ${permSummary(S.me.perms)}`}</p></div>
      <section class="card todo-card"><div class="card-head"><h2>지금 할 일</h2></div>
        ${todo.length ? `<ul class="todo">${todo.join("")}</ul>` : '<p class="hint">기다리는 일이 없어요. 새로 오면 여기와 메뉴에 숫자로 보여요.</p>'}</section>
      <div class="tiles">${tiles.join("")}</div>
      ${stats.length ? `<p class="stats">${stats.map(([k, v]) => `<span>${esc(k)}<b>${v ?? 0}</b></span>`).join("")}</p>` : ""}
      <div class="cols">
        ${can("audit") ? `<section class="card"><div class="card-head"><h2>최근 활동</h2><a class="btn small ghost" href="${href("audit")}">모두 보기</a></div>
          ${feed === null ? '<div class="card skeleton short"></div>' : feed.length ? `<ul class="feed">${feed.map(feedItem).join("")}</ul>` : '<p class="hint">아직 활동이 없어요.</p>'}</section>` : ""}
      </div>`;
  }

  function feedItem(e) {
    return `<li><span class="when">${esc(ago(e.at))}</span><span>${esc(ACTION_NAMES[e.action] ?? e.action)}${e.target ? ` <span class="meta">${esc(e.target)}</span>` : ""}</span>
      <span class="who">${esc(e.actor || "앱 사용자")}</span></li>`;
  }

  function permSummary(perms) {
    const areas = S.overview?.me?.areas ?? S.me?.areas ?? {};
    const on = Object.entries(perms ?? {}).filter(([, l]) => l !== "none");
    return on.length ? on.map(([a, l]) => `${areas[a] ?? a} ${LEVEL_NAMES[l]}`).join(", ") : "아직 없음";
  }

  function permPills(perms, areas) {
    const on = Object.entries(perms ?? {}).filter(([, l]) => l !== "none");
    if (!on.length) return '<span class="pill">권한 없음</span>';
    return on.map(([a, l]) => `<span class="pill ${l === "edit" ? "info" : ""}">${esc(areas[a] ?? a)} ${LEVEL_NAMES[l]}</span>`).join("");
  }

  // ---- review ----

  function reviewView() {
    const q = S.data.queue;
    const points = S.overview?.settings?.adoptPoints ?? 3;
    const editable = can("review", "edit");
    const counts = q ? { all: q.groups.length + q.asks.length + q.flagged.length, groups: q.groups.length, asks: q.asks.length, flagged: q.flagged.length } : {};
    const head = `<div class="segmented" role="group" aria-label="보기">
        ${seg("review-filter", "all", S.reviewFilter, "전체", counts.all)}${seg("review-filter", "groups", S.reviewFilter, "제보", counts.groups)}
        ${seg("review-filter", "asks", S.reviewFilter, "앱에 없는 방식", counts.asks)}${seg("review-filter", "flagged", S.reviewFilter, "달라요", counts.flagged)}</div>
      <p class="hint">같은 규칙을 서로 다른 2명 이상이 보내 ${points}점이 되고, 투표한 분의 ${S.overview?.settings?.adoptAgree ?? 75}% 이상이 맞았어요라고 하면 자동으로 채택돼요. 명세서로 맞춰 본 제보는 2점이에요.${editable ? "" : " 보기 권한이라 채택·거절은 할 수 없어요."}</p>`;
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
    if (!parts.length) return head + empty("검토할 것이 없어요", "새 제보가 오면 여기와 메뉴에 숫자로 보여요.");
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
      <details${open ? " open" : ""}><summary>보낸 설정</summary>${lines(g.lines)}</details>
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
        ${can("companies", "edit") ? `<button class="btn danger" data-act="withdraw-rule" data-id="${f.rule}">규칙 내리기</button>` : ""}</div>
    </article>`;
  }

  // ---- the talk on rule sets [COMM-06] ----

  function communityView() {
    const d = S.data.posts;
    const edit = can("community", "edit");
    const talk = Boolean(S.route.id);
    const head = talk ? `<p class="hint">이 규칙에 남긴 글 전부예요. 가려진 글도 보여요.${edit ? "" : " 보기 권한이라 가리거나 지울 수 없어요."}</p>`
      : `<div class="segmented" role="group" aria-label="보기">${seg("post-filter", "flagged", S.postFilter, "신고됨", S.overview?.flaggedPosts)}
        ${seg("post-filter", "hidden", S.postFilter, "가려짐")}${seg("post-filter", "all", S.postFilter, "모든 글")}</div>
        <p class="hint">앱 사용자가 회사 규칙마다 남긴 글이에요. 서로 다른 세 분이 신고하면 개발자가 볼 때까지 가려져요. 다시 보이게 한 글은 신고로 가려지지 않아요.${edit ? "" : " 보기 권한이라 가리거나 지울 수 없어요."}</p>`;
    if (!d) return head + skeleton();
    if (!d.posts.length) {
      return head + empty(S.postFilter === "flagged" && !talk ? "신고된 글이 없어요" : "글이 없어요", "신고가 오면 여기와 메뉴에 숫자로 보여요.");
    }
    return head + `<div class="grid">${d.posts.map((p) => postCard(p, edit, talk)).join("")}</div>`;
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

  async function moderate(id, body, done) {
    await act(() => api(`/posts/${id}`, "POST", body), done);
    load();
  }

  // ---- inquiries ----

  function inquiriesView() {
    if (S.data.inquiriesMissing) {
      return empty("문의함이 아직 서버에 없어요", "앱 안 문의가 켜진 서버로 업데이트하면 여기서 바로 답할 수 있어요.");
    }
    const d = S.data.inquiries;
    const counts = d?.counts ?? {};
    const head = `<div class="segmented" role="group" aria-label="문의 상태">
      ${seg("inquiry-status", "open", S.inquiryStatus, "답 대기", counts.open)}${seg("inquiry-status", "answered", S.inquiryStatus, "답함", counts.answered)}
      ${seg("inquiry-status", "closed", S.inquiryStatus, "닫힘", counts.closed)}</div>`;
    if (!d) return head + skeleton();
    if (!d.inquiries.length) {
      return head + empty(S.inquiryStatus === "open" ? "답을 기다리는 문의가 없어요" : "목록이 비어 있어요", "앱에서 문의가 오면 여기와 메뉴에 숫자로 보여요.");
    }
    return head + `<ul class="rows">${d.inquiries.map((q) => `<li><a class="row${q.waiting ? " unread" : ""}" href="${href("inquiries", q.id)}">
      <span class="row-main"><b>${esc(KIND_NAMES[q.kind] ?? "문의")}${q.appVersion ? ` <span class="meta">앱 ${esc(q.appVersion)}</span>` : ""}</b>
        <span class="preview">${esc(q.preview)}</span></span>
      <span class="row-side"><span>${esc(ago(q.updatedAt ?? q.createdAt))}</span>${q.count > 1 ? `<span class="pill">${q.count}</span>` : ""}</span>
      ${icon(P.chevron)}</a></li>`).join("")}</ul>`;
  }

  function inquiryView() {
    const q = S.data.inquiry;
    if (!q || q.id !== S.route.id) return skeleton(2);
    const editable = can("inquiries", "edit");
    const ctx = Object.entries(q.context ?? q.info ?? {}).filter(([, v]) => v !== null && v !== undefined && v !== "");
    const thread = q.messages.map((m) => {
      const mine = m.from !== "user";
      return `<div class="bubble ${mine ? "admin" : "user"}">${esc(m.text)}</div>
        <span class="bubble-meta ${mine ? "admin" : ""}">${mine ? esc(m.by && m.by !== "admin" ? m.by : "개발자") : "보낸 분"} · ${esc(moment(m.at))}</span>`;
    }).join("");
    const status = { open: ["warn", "답 대기"], answered: ["ok", "답함"], closed: ["", "닫힘"] }[q.status] ?? ["", q.status];
    return `<div class="cols">
      <section>
        <div class="card"><div class="card-head"><h2>${esc(KIND_NAMES[q.kind] ?? "문의")}</h2><span class="pill ${status[0]}">${status[1]}</span></div>
          <div class="thread">${thread}</div></div>
        ${editable && q.status !== "closed" ? `<form class="composer" data-form="reply">
          <label class="sr" for="reply">답</label>
          <textarea id="reply" name="text" maxlength="2000" placeholder="보낸 분 앱에 보일 답을 적어요. 이름, 연락처, 금액은 묻지 않아요." required></textarea>
          <div class="chips" role="group" aria-label="자주 쓰는 답">${TEMPLATES.map(([label], i) => `<button type="button" class="chip pick" data-act="template" data-i="${i}">${esc(label)}</button>`).join("")}</div>
          <div class="actions"><span class="count" id="reply-count">0/2000</span>
            <button class="btn" type="submit" data-close="1">답하고 닫기</button><button class="btn primary" type="submit">답하기</button></div>
        </form>` : ""}
      </section>
      <aside>
        <section class="card"><h2>함께 온 정보</h2><p class="hint">보낸 분이 보내기 전에 보고 동의한 정보예요.</p>
          ${ctx.length ? `<dl class="kv">${ctx.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(typeof v === "object" ? JSON.stringify(v) : v)}</dd>`).join("")}</dl>` : '<p class="meta">없어요</p>'}</section>
        ${editable ? `<section class="card"><h2>상태</h2>
          <div class="actions start">${q.status === "closed" ? '<button class="btn" data-act="inquiry-state" data-status="open">다시 열기</button>'
            : '<button class="btn" data-act="inquiry-state" data-status="closed">닫기</button>'}</div></section>` : ""}
      </aside></div>`;
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
    const action = !edit ? "" : r.withdrawn ? `<button class="btn" data-act="restore-rule" data-id="${r.id}">다시 올리기</button>`
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
    const segs = [["owed", "지급 대기"], ["paid", "지급함"], ["void", "취소"]].map(([key, label]) => seg("reward-tab", key, S.rewardTab, label, counts[key])).join("");
    const body = !r || r.status !== S.rewardTab ? skeleton() : !r.rewards.length ? empty(S.rewardTab === "owed" ? "드릴 보상이 없어요" : "목록이 비어 있어요", "")
      : `<ul class="rows">${r.rewards.map((w) => `<li class="item">
        <div class="row-main"><b>${esc(w.company.name)}${w.dept ? ` · ${esc(w.dept)}` : ""}</b>
          <small>프로 ${w.months}개월 · ${day(w.createdAt)} ${w.ask ? "앱에 없는 방식" : "채택"}${w.version ? ` · 규칙 v${w.version}` : ""}${w.paidAt ? ` · ${day(w.paidAt)} 지급` : ""}</small>
          ${w.memo ? `<small class="memo">${esc(w.memo)}</small>` : ""}</div>
        ${edit ? `<div class="actions">${w.status === "owed" ? `<button class="btn" data-act="reward" data-id="${w.id}" data-status="void">취소</button>
          <button class="btn primary" data-act="reward" data-id="${w.id}" data-status="paid">지급함</button>`
          : `<button class="btn" data-act="reward" data-id="${w.id}" data-status="owed">대기로 되돌리기</button>`}</div>` : ""}
      </li>`).join("")}</ul>`;
    return `<div class="segmented" role="group" aria-label="보상 상태">${segs}</div>
      <p class="hint">채택된 정보를 보낸 분들께 드릴 프로 이용 기간이에요. 유료 기능이 시작되면 앱에서 받게 할 예정이고, 스토어 리뷰와는 묶지 않아요.</p>${body}`;
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
          <div class="actions"><button class="btn small" data-act="admin-edit" data-id="${a.id}">권한</button>
            ${a.status === "active" ? `<button class="btn small" data-act="admin-status" data-id="${a.id}" data-status="suspended" data-name="${esc(a.name)}">멈추기</button>`
              : `<button class="btn small" data-act="admin-status" data-id="${a.id}" data-status="active">다시 쓰기</button>`}
            <button class="btn small danger" data-act="admin-remove" data-id="${a.id}" data-name="${esc(a.name)}">삭제</button></div></li>`).join("")}
      </ul>
      <h2 class="section">초대한 메일<span class="count">${invites.length}</span></h2>
      ${invites.length ? `<ul class="rows">${invites.map((i) => `<li class="item">
        <div class="row-main"><b>${esc(i.email)}</b><small>${i.state === "expired" ? "링크 만료" : `${esc(day(i.expiresAt))}까지`}${i.note ? ` · ${esc(i.note)}` : ""}</small>
          <div class="perm-sum">${permPills(i.perms, d.areas)}</div></div>
        <span class="pill ${i.state === "expired" ? "bad" : "warn"}">${i.state === "expired" ? "만료" : "가입 전"}</span>
        <div class="actions"><button class="btn small" data-act="invite-again" data-email="${esc(i.email)}">새 링크</button>
          <button class="btn small danger" data-act="invite-remove" data-email="${esc(i.email)}">취소</button></div></li>`).join("")}</ul>`
        : '<p class="hint">가입을 기다리는 초대가 없어요.</p>'}`;
  }

  /** Area by area: nothing, a look, or changes too; presets fill the table in one tap. */
  function permEditor(perms, d) {
    const presets = Object.entries(d.presets ?? {});
    return `<p class="label">빠르게 고르기</p>
      <div class="chips" role="group" aria-label="역할">${presets.map(([key, p]) => `<button type="button" class="chip pick" data-act="preset" data-key="${key}">${esc(p.name)}</button>`).join("")}</div>
      <p class="label">영역별 권한</p>
      <div class="perm-table">${Object.entries(d.areas).map(([area, label]) => `<div class="perm"><b>${esc(label)}</b>
        <span class="levels" role="radiogroup" aria-label="${esc(label)}">${["none", "view", "edit"].map((l) => `<button type="button" data-act="level" data-area="${area}"
          data-level="${l}" role="radio" aria-checked="${perms[area] === l}" aria-pressed="${perms[area] === l}"${l === "edit" && d.viewOnly?.includes(area) ? " disabled" : ""}>${LEVEL_NAMES[l]}</button>`).join("")}</span>
        <input type="hidden" name="perm-${area}" value="${perms[area] ?? "none"}"></div>`).join("")}</div>
      <p class="help">보기는 화면을 보기만 하고, 고치기는 채택·답하기 같은 바꾸는 일까지 해요. 관리자 관리는 최고 관리자만 해요.</p>`;
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

  // ---- activity ----

  function auditView() {
    const d = S.data.audit;
    if (!d) return skeleton(3);
    if (!d.entries.length) return empty("아직 활동이 없어요", "채택, 답하기, 로그인 같은 일이 여기 남아요. 1년 지난 기록은 지워져요.");
    let last = "";
    const out = [];
    for (const e of d.entries) {
      const dd = new Date(e.at).toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric", weekday: "short" });
      if (dd !== last) {
        if (last) out.push("</ul></section>");
        out.push(`<h2 class="day-head">${esc(dd)}</h2><section class="card"><ul class="feed">`);
        last = dd;
      }
      out.push(`<li><span class="when">${esc(clock(e.at))}</span><span><b>${esc(ACTION_NAMES[e.action] ?? e.action)}</b>${e.target ? ` <span class="meta">${esc(e.target)}</span>` : ""}
        ${e.detail ? `<span class="meta">${esc(e.detail)}</span>` : ""}</span><span class="who">${esc(e.actor || "앱 사용자·시스템")}</span></li>`);
    }
    out.push("</ul></section>");
    return `<p class="hint">누가 언제 무엇을 했는지 남아요. 1년 지난 기록은 지워져요.</p>${out.join("")}
      ${d.more ? '<div class="actions start"><button class="btn wide" data-act="audit-more">더 보기</button></div>' : ""}`;
  }

  // ---- settings, account, more ----

  function healthList() {
    const h = S.health;
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
    return `<ul class="checks">${rows.map(([ok, good, todo]) =>
      `<li class="${ok ? "ok" : "todo"}"><span aria-hidden="true">${ok ? "✓" : "!"}</span>${esc(ok ? good : todo)}</li>`).join("")}</ul>`;
  }

  function settingsView() {
    const s = S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3, adoptAgree: 75 };
    const edit = can("settings", "edit");
    return `<div class="grid two">
      ${can("settings") ? `<section class="card">
        <h2>자동 채택</h2>
        ${stepper("adoptPoints", "채택 점수", s.adoptPoints, "점", edit)}
        <p class="hint">같은 규칙을 서로 다른 2명 이상이 보내 이 점수가 되면 자동으로 채택해요. 명세서로 맞춰 본 제보는 2점, 나머지는 1점이에요.</p>
        ${stepper("adoptAgree", "맞았어요 비율", s.adoptAgree ?? 75, "%", edit)}
        <p class="hint">투표한 분 가운데 이만큼이 맞았어요라고 해야 자동으로 채택해요. 모자라면 검토에 남아 개발자가 정해요. 앱의 신뢰도는 이 비율과 점수를 함께 봐요.</p>
        ${stepper("rewardMonths", "채택 보상", s.rewardMonths, "개월", edit)}
        <p class="hint">채택된 정보를 보낸 분께 드릴 프로 이용 기간이에요. 0개월이면 보상을 기록하지 않아요.</p>
        ${edit ? `<button class="btn primary wide" data-act="save-settings"${S.draft ? "" : " disabled"}>저장</button>` : '<p class="note">보기 권한이라 바꿀 수 없어요.</p>'}
      </section>` : ""}
      <section class="card"><h2>앱으로 설치</h2>
        <p class="hint">${S.installed ? "이 기기에는 앱으로 설치돼 있어요." : "홈 화면에 두면 앱처럼 바로 열리고, 주소창 없이 넓게 보여요."}</p>
        <div class="actions start">${S.installPrompt ? `<button class="btn primary" data-act="install">${icon(P.install)}지금 설치</button>` : ""}
          <a class="btn" href="/admin/install">설치 방법 보기</a></div></section>
      ${S.me?.owner ? `<section class="card"><h2>서버 상태</h2>${healthList()}
        <p class="meta">회사 ${S.overview?.companies ?? 0} · 쓰는 규칙 ${S.overview?.rules ?? 0} · 제보 ${S.overview?.reports ?? 0}</p>
        <p class="meta">가입 확인 메일: ${S.health?.mail ? "켜짐 (MAIL_API_KEY)" : "꺼짐, 초대 링크를 메일로 보내 확인해요"}</p></section>` : ""}
      <section class="card"><h2>단축키</h2><p class="hint">키보드가 있으면 <span class="kbd">?</span>로 단축키를 볼 수 있어요.</p></section>
      </div>`;
  }

  function stepper(key, label, value, unit, edit) {
    return `<div class="stepper"><span>${label}</span><div class="stepper-ctl">
      <button class="icon-btn" data-act="step" data-key="${key}" data-d="-1" aria-label="${label} 줄이기"${edit ? "" : " disabled"}>−</button>
      <output aria-live="polite">${value}${unit}</output>
      <button class="icon-btn" data-act="step" data-key="${key}" data-d="1" aria-label="${label} 늘리기"${edit ? "" : " disabled"}>+</button></div></div>`;
  }

  function accountView() {
    const m = S.me;
    if (!m) return skeleton();
    return `<div class="grid two">
      <section class="card"><div class="card-head"><h2>${esc(m.name)}</h2>${m.owner ? '<span class="pill gold">최고 관리자</span>' : ""}</div>
        ${m.owner ? '<p class="hint">Cloudflare의 ADMIN_PASSWORD로 로그인했어요. 모든 화면을 쓰고 관리자를 초대·승인해요.</p>'
          : `<dl class="kv"><dt>메일</dt><dd>${esc(m.email)}</dd></dl><p class="label">권한</p><div class="perm-sum">${permPills(m.perms, m.areas ?? {})}</div>`}
      </section>
      ${m.owner ? `<section class="card"><h2>비밀번호</h2><p class="hint">최고 관리자 비밀번호는 Cloudflare 대시보드의 ADMIN_PASSWORD에서 바꿔요. 바꾸면 이 기기도 다시 로그인해요.</p></section>`
        : `<form class="card" data-form="password"><h2>비밀번호 바꾸기</h2>
          <div class="fieldset"><label for="pw-now">지금 비밀번호</label><input id="pw-now" name="current" type="password" autocomplete="current-password" required></div>
          <div class="fieldset"><label for="pw-new">새 비밀번호</label><input id="pw-new" name="next" type="password" autocomplete="new-password" minlength="10" required data-strength="pw-meter">
            <meter id="pw-meter" class="strength" min="0" max="4" low="2" high="3" optimum="4" value="0" aria-label="비밀번호 안전도"></meter>
            <span class="help">10자 이상. 바꾸면 다른 기기의 로그인은 끝나요.</span></div>
          <div class="actions"><button class="btn primary" type="submit">바꾸기</button></div></form>`}
      <section class="card"><h2>로그인</h2><p class="hint">휴대폰을 잃어버렸다면 모든 기기에서 로그아웃해요.</p>
        <div class="actions start"><button class="btn" data-act="logout">${icon(P.logout)}로그아웃</button>
          <button class="btn danger" data-act="logout-all">모든 기기에서 로그아웃</button></div></section>
      </div>`;
  }

  function moreView() {
    const rest = NAV.filter(visible).slice(4);
    const o = S.overview ?? {};
    return `<ul class="rows">${[...rest, { key: "account", label: "내 계정" }].map((n) => `<li><a class="row" href="${href(n.key)}">
      ${icon(P[n.key])}<span class="row-main"><b>${esc(n.label)}</b></span>${n.badge && n.badge(o) ? `<i class="badge">${n.badge(o)}</i>` : ""}${icon(P.chevron)}</a></li>`).join("")}</ul>
      <div class="actions start"><button class="btn wide" data-act="logout">${icon(P.logout)}로그아웃</button></div>`;
  }

  // ---- sign in, invite and install pages ----

  function loginView() {
    const h = S.health;
    const owner = S.loginMode === "owner";
    const usable = !owner || !h || (h.database && h.password);
    return `<main class="gate">
      <div class="brand"><img src="/admin/icon.svg" alt="" width="76" height="76"><h1>밤낮 관리자</h1><p>제보, 문의, 회사 규칙을 관리해요.</p></div>
      <div class="segmented" role="group" aria-label="로그인 방법">${seg("login-mode", "account", S.loginMode, "관리자 계정")}${seg("login-mode", "owner", S.loginMode, "최고 관리자")}</div>
      <form class="card" data-form="login">
        ${owner ? "" : `<div class="fieldset"><label for="email">메일</label><input id="email" name="email" type="email" autocomplete="username" inputmode="email" required></div>`}
        <div class="fieldset"><label for="password">${owner ? "최고 관리자 비밀번호" : "비밀번호"}</label>
          <input id="password" name="password" type="password" autocomplete="current-password" required${usable ? "" : " disabled"}></div>
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
        <div class="fieldset"><label for="j-pw">비밀번호</label><input id="j-pw" name="password" type="password" minlength="10" required autocomplete="new-password" data-strength="j-meter">
          <meter id="j-meter" class="strength" min="0" max="4" low="2" high="3" optimum="4" value="0" aria-label="비밀번호 안전도"></meter>
          <span class="help">10자 이상, 메일 주소와 다르게요.</span></div>
        <div class="fieldset"><label for="j-pw2">비밀번호 한 번 더</label><input id="j-pw2" name="password2" type="password" minlength="10" required autocomplete="new-password"></div>
        ${j.mail ? `<div class="fieldset"><label for="j-code">메일 확인 코드</label><div class="field"><input id="j-code" name="code" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" required
          autocomplete="one-time-code"><button class="btn" type="button" data-act="join-code">${j.codeSent ? "다시 보내기" : "코드 보내기"}</button></div>
          <span class="help">${j.codeSent ? `${esc(j.email)}로 6자리 코드를 보냈어요. 15분 동안 쓸 수 있어요.` : "버튼을 누르면 초대받은 메일로 코드가 가요."}</span></div>` : ""}
        <p class="error" role="alert">${esc(S.error)}</p>
        <button class="btn primary wide" type="submit">계정 만들기</button>
      </form></main>`;
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

  // ---- dialogs ----

  function openDialog(html, size = "") {
    dialog.className = size;
    dialog.innerHTML = html;
    if (!dialog.open) dialog.showModal();
    dialog.querySelector("input:not([type=radio]):not([type=hidden]):not([readonly]),textarea")?.focus();
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
      ${months && can("rewards", "edit") ? `<label class="switch"><input type="checkbox" name="reward" checked><span class="track" aria-hidden="true"></span>
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

  function openShortcuts() {
    const items = NAV.filter(visible);
    openDialog(`<div class="box"><h2>단축키</h2>
      <div class="keys">${items.map((n, i) => (i < 9 ? `<span class="kbd">${i + 1}</span><span>${esc(n.label)}</span>` : "")).join("")}
        <span class="kbd">/</span><span>찾기 칸으로</span><span class="kbd">R</span><span>새로 고침</span>
        <span class="kbd">Ctrl</span><span>+ Enter로 문의 답 보내기</span><span class="kbd">Esc</span><span>창 닫기</span></div>
      <div class="actions"><button class="btn" data-act="close">닫기</button></div></div>`);
  }

  // ---- what taps do ----

  async function act(work, done) {
    try {
      const r = await work();
      if (done) toast(typeof done === "function" ? done(r) : done);
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

  async function setReward(id, status, memo) {
    await act(() => api(`/rewards/${id}`, "POST", memo === undefined ? { status } : { status, memo }),
      status === "paid" ? "지급을 기록했어요" : status === "void" ? "보상을 취소했어요" : "대기로 되돌렸어요");
    load();
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast("복사했어요");
    } catch {
      document.getElementById("inv-link")?.select();
      toast("링크를 길게 눌러 복사해 주세요");
    }
  }

  const ACTIONS = {
    refresh: () => load(),
    close: () => dialog.close(),
    shortcuts: () => openShortcuts(),
    "login-mode": ({ key }) => {
      S.loginMode = key;
      S.error = "";
      render();
    },
    "review-filter": ({ key }) => {
      S.reviewFilter = key;
      render({ keepScroll: true });
    },
    adopt: ({ company, setup }) => act(() => api(`/groups/${company}/${setup}/adopt`, "POST"), "채택했어요. 이제 회사 찾기에 나와요").then(() => load()),
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
      S.data.posts = null;
      render();
      load();
    },
    "post-show": ({ id }) => moderate(id, { action: "show" }, "다시 보이게 했어요. 신고로는 더 가려지지 않아요"),
    "post-hide": ({ id }) => openHidePost(id, false),
    "post-mute": ({ id }) => openHidePost(id, true),
    "post-delete": ({ id }) => confirmBox("이 글을 지울까요?", "지운 글은 되돌릴 수 없어요. 글쓴이 앱에서도 사라져요.", "지우기", () => moderate(id, { action: "delete" }, "지웠어요")),
    "reward-tab": ({ key }) => {
      S.rewardTab = key;
      render();
      load();
    },
    reward: ({ id, status }) => (status === "paid" ? openPaid(id) : setReward(id, status)),
    step: ({ key, d }) => {
      const s = { adoptAgree: 75, ...(S.draft ?? S.overview?.settings ?? { adoptPoints: 3, rewardMonths: 3 }) };
      const [lo, hi, by] = key === "adoptPoints" ? [2, 20, 1] : key === "adoptAgree" ? [50, 100, 5] : [0, 24, 1];
      s[key] = Math.min(hi, Math.max(lo, s[key] + Number(d) * by));
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
      S.data.inquiries = null;
      render();
      load();
    },
    template: ({ i }) => {
      const box = document.getElementById("reply");
      box.value = (box.value ? `${box.value.trimEnd()}\n\n` : "") + TEMPLATES[Number(i)][1];
      box.dispatchEvent(new Event("input", { bubbles: true }));
      box.focus();
    },
    "inquiry-state": async ({ status }) => {
      const q = await act(() => api(`/inquiries/${S.route.id}/status`, "POST", { status }), status === "closed" ? "문의를 닫았어요" : "문의를 다시 열었어요");
      if (q) S.data.inquiry = q;
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
    "audit-more": async () => {
      const d = S.data.audit;
      const next = await act(() => api(`/audit?before=${d.entries[d.entries.length - 1].id}`));
      if (next) S.data.audit = { entries: [...d.entries, ...next.entries], more: next.more };
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
      signedOut();
    },
    "logout-all": () => confirmBox("모든 기기에서 로그아웃할까요?", "이 기기를 포함해 이 계정으로 로그인한 모든 기기가 다시 로그인해야 해요.", "로그아웃", async () => {
      await api("/me/logout-all", "POST");
      signedOut();
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
    });
  }

  function signedOut() {
    S.phase = "login";
    S.me = null;
    S.overview = null;
    S.data = {};
    S.error = "";
    history.replaceState(null, "", "/admin/");
    render();
  }

  const readJoinForm = () => Object.fromEntries(["j-name", "j-pw", "j-pw2"].map((id) => [id, document.getElementById(id)?.value ?? ""]));
  const fillJoinForm = (v) => Object.entries(v).forEach(([id, value]) => { const el = document.getElementById(id); if (el) el.value = value; });

  const FORMS = {
    login: async (form) => {
      S.error = "";
      const data = new FormData(form);
      const body = S.loginMode === "owner" ? { password: data.get("password") } : { email: data.get("email"), password: data.get("password") };
      try {
        await api("/login", "POST", body);
        await enter();
      } catch (e) {
        const left = e.data?.left;
        S.error = e.message === "wrong" ? `${S.loginMode === "owner" ? "비밀번호가" : "메일이나 비밀번호가"} 달라요. ${left}번 더 틀리면 1시간 동안 잠겨요.`
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
      await act(() => api(`/groups/${form.dataset.company}/${form.dataset.setup}/reject`, "POST", { reason }), "거절했어요. 보낸 분 앱에 이유가 보여요");
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
      dialog.close();
      moderate(form.dataset.id, { action: mute ? "mute" : "hide", reason: new FormData(form).get("reason") },
        mute ? "가리고 30일 동안 쓰기를 막았어요" : "가렸어요. 글쓴이 앱에 이유가 보여요");
    },
    paid: (form) => {
      dialog.close();
      setReward(form.dataset.id, "paid", new FormData(form).get("memo"));
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
      const q = await act(() => api(`/inquiries/${S.route.id}/reply`, "POST", close ? { text, close: true } : { text }),
        close ? "답하고 닫았어요. 보낸 분 앱에 보여요" : "답했어요. 보낸 분 앱에 보여요");
      if (q) {
        S.data.inquiry = q;
        render();
        window.scrollTo(0, document.body.scrollHeight);
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

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act]");
    if (!el || !ACTIONS[el.dataset.act] || el.disabled) return;
    e.preventDefault();
    ACTIONS[el.dataset.act](el.dataset, el);
  });
  document.addEventListener("submit", (e) => {
    const form = e.target.closest("form[data-form]");
    if (!form) return;
    e.preventDefault();
    FORMS[form.dataset.form]?.(form, e.submitter);
  });
  document.addEventListener("change", async (e) => {
    if (e.target.dataset?.change !== "hidden" || S.route.name !== "companies") return;
    const c = await act(() => api(`/companies/${S.route.id}`, "POST", { hidden: e.target.checked }),
      (r) => (r.hidden ? "회사 찾기에서 숨겼어요" : "다시 회사 찾기에 나와요"));
    if (c) S.data.company = c;
    render({ keepScroll: true });
  });
  document.addEventListener("input", (e) => {
    const t = e.target;
    if (t.dataset?.strength) {
      const m = document.getElementById(t.dataset.strength);
      if (m) m.value = strength(t.value);
    }
    if (t.id === "reply") {
      const n = document.getElementById("reply-count");
      if (n) n.textContent = `${t.value.length}/2000`;
      t.style.height = "auto";
      t.style.height = `${Math.min(t.scrollHeight + 4, window.innerHeight * 0.5)}px`;
    }
    if (t.id !== "search") return;
    S.query = t.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
      const r = await act(() => api(`/companies?q=${encodeURIComponent(S.query)}`));
      if (r) S.data.list = r;
      const box = document.getElementById("company-list");
      if (box) box.innerHTML = companyRows();
    }, 250);
  });
  document.addEventListener("keydown", (e) => {
    if (S.phase !== "app") return;
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable;
    if (e.target.id === "reply" && e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      e.target.form.requestSubmit(e.target.form.querySelector(".btn.primary"));
      return;
    }
    if (typing || e.ctrlKey || e.metaKey || e.altKey || dialog.open) return;
    const items = NAV.filter(visible);
    if (/^[1-9]$/.test(e.key) && items[Number(e.key) - 1]) {
      e.preventDefault();
      go(items[Number(e.key) - 1].key);
    } else if (e.key === "/") {
      const s = document.getElementById("search");
      if (s) {
        e.preventDefault();
        s.focus();
      }
    } else if (e.key === "r" || e.key === "R") {
      load();
    } else if (e.key === "?") {
      openShortcuts();
    }
  });
  dialog.addEventListener("click", (e) => {
    // A tap outside the box closes it.
    if (e.target === dialog) dialog.close();
  });
  window.addEventListener("hashchange", () => {
    if (S.phase !== "app") return;
    const before = S.route;
    S.route = readRoute();
    if (before.name !== S.route.name || before.id !== S.route.id) {
      S.draft = null;
      render();
      window.scrollTo(0, 0);
      document.getElementById("title")?.focus({ preventScroll: true });
    }
    load();
  });
  window.addEventListener("online", () => {
    S.online = true;
    if (S.phase === "app") load({ quiet: true });
  });
  window.addEventListener("offline", () => {
    S.online = false;
    if (S.phase === "app") render({ keepScroll: true });
  });
  // Fresh numbers when the app comes back to the front, and every minute while it is open.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && S.phase === "app") load({ quiet: true });
  });
  setInterval(async () => {
    if (document.visibilityState !== "visible" || S.phase !== "app" || !S.online) return;
    try {
      S.overview = await api("/overview");
      updateBadges();
    } catch {
      // Tried again next minute.
    }
  }, 60000);
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    S.installPrompt = e;
    if (S.phase !== "boot") render({ keepScroll: true });
  });
  window.addEventListener("appinstalled", () => {
    S.installed = true;
    S.installPrompt = null;
  });

  /**
   * Opens the app. The home screen's recent activity is asked for together with the numbers rather than
   * after them; an admin who may not see it simply gets no feed. The numbers just came, so the screen's
   * own data is all that is fetched after.
   */
  async function enter() {
    const home = (location.hash.replace(/^#\/?/, "").split("/")[0] || "home") === "home";
    const feed = home ? api("/audit").catch(() => null) : null;
    S.overview = await api("/overview");
    S.me = S.overview.me;
    S.phase = "app";
    S.route = readRoute();
    if (!location.hash) history.replaceState(null, "", `/admin/${href(S.route.name)}`);
    render();
    if (feed && S.route.name === "home") {
      const d = await feed;
      if (d && can("audit")) S.data.feed = d;
      if (S.route.name === "home" && S.phase === "app") render({ keepScroll: true });
    } else {
      load({ quiet: true, overview: false });
    }
  }

  (async () => {
    if ("serviceWorker" in navigator) navigator.serviceWorker.register("/admin/sw.js", { scope: "/admin/" }).catch(() => {});
    const path = location.pathname;
    if (path === "/admin/join") {
      S.phase = "join";
      const token = location.hash.replace(/^#/, "");
      render();
      try {
        const info = await api(`/join?t=${encodeURIComponent(token)}`);
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
 * of the app changes [version], and the phone then fetches every file again. What the API says is never kept.
 */
function adminWorker(version) {
  const CACHE = `bamnat-admin-${version}`;
  const SHELL = ["/admin/", "/admin/app.js", "/admin/app.css", "/admin/icon.svg", "/admin/manifest.webmanifest", "/admin/install"];
  self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" })))).then(() => self.skipWaiting()));
  });
  self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()));
  });
  self.addEventListener("fetch", (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== "GET" || url.origin !== self.location.origin || !url.pathname.startsWith("/admin/")) return;
    const fresh = fetch(event.request).then(async (res) => {
      if (res.ok) await caches.open(CACHE).then((c) => c.put(event.request, res.clone()));
      return res;
    });
    event.waitUntil(fresh.catch(() => {}));
    event.respondWith(caches.open(CACHE).then((c) => c.match(event.request, { ignoreSearch: true }))
      .then((hit) => hit ?? fresh.catch(() => caches.match("/admin/"))));
  });
}

const ICONS = {
  "/admin/icon-192.png": "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",
  "/admin/icon-512.png": "iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAMAAADDpiTIAAAASFBMVEXPrXDMqm7JqG3FpWzDo2vCo2vBo2vBomvAoWq/oWq0mGaHdFVQSEEmJzEYGywXGywWGisVGisWGSsVGSsUGSsRFioNEigJECcZgtFfAAASH0lEQVR42u2d6ZqbuBZFQZiIUVJKBt7/TVvClaqkkxpsMxzB2n/udXfydZX30pkQUubRqZXxFQAAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQAAAAXwEAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAKA5eSsscMVc88IQPTeD9M0GQMBZwPgJS78cZp837VNN7zg7pkAcMZex2nsu2B+qbJ2uDrcPQ0Awf0Q9k1c+SrLVJ21E/6fBgBn3BCXftsUWV7ouor+e/w/BwAvce0Pwf06y6L5uizrrCH+nwQAa67z2i/D0q/rYH5QrRozWJw9PgAvxo2TiWs/L+vqR3lTpZoe/08AgAuLf+jbJg9rvyrfpAvdjwZfjw6ANUNY/KHkL6q3tf8aALoJ/48OgA2xf178+jXvvyk2APh/cACsjbFfx8Vfln/5P5L/jw2AtePYtSq7/H/x3wpAM9AAHhmAV/tD7C//li4LCsBDA+DMzf6/Y/9rAhBVALogAFhSofLv2/wj+0UVAM5YNwT5+HQaAJax3wf7yw/tl1QABPOnUKgGhf8dvXEAsETpf+2arPzIfkETAOvnhxNtE9V2ffhoAOD56B/sL+oP7RczAXgxk+vaS5blRZDKM9V0/SS3GkgCABujf67+0fj9kQAkPAJ0PpKal3Vd6aCqqrXKmm4Q+3giBQDMOHR1Vn0c/WMLqDoJHaAdhkjqHz+qrousFdufZkks/8+jv5wEYAfT/INUXedNL3REnSWw/Nvi0+g/PwMU0QG46P8/Sa1VLTQGCAfA2bj8y0+jf6wAcgkdgPPDB/7fCBBZB8gGwAxjV+d1+aX/IkZAZmqzD3/WkAWuRIC7w79ps8tXy39+BiBgddmxy6vPyhSROxUEA3AL/19kfzkV4E9/bdQnAOhLSAIOAO4K/6H6+9J+KRWgmbqsTqBTSQWAOfx/Wf0Jiq1ubFX1BagCQ4BUAMwc/n98w3+tQnX1U8AIoL+kQWoKAIT0/53qX9LX+mUGmJsVAPjeanJTlxff8z8EAC8gAIQeoM2rr4sVca8sSQTADqGh1lWZUgAIP/QXJUAEQGAfIBAAM4Tyr9JlUgHADX1TfPUzV6oTt2lZHgCh/G+y74X/ecAmIwAAwHL+903+bf9DAPgpIQAAwILtX62+7b+czgoAdvFf0GyFInAH/yVNV2kDd/C/lPEYkEHQXv4L2QfwmgIYBW++/qX0gLcykIdBT/d/d/ovK6fyOPjJ7y/0Uff5L+wbZUPIkynU3Ot/qVUvqa1mS9hTcxR3x/z311illbWi2BT6VBd9r//ylhTbwtdZOx9LWk7lxZDHC2hV3eu+uAzgeTXscf/zUpepZ4AbAbwc+kD+7+uiKtPPALc6gNfD7/3Kru6ODQC/Hwgg8gvlgIiNCkC5Z4JyRMy9BcAj/ovcXPGGAIdEfd//sdcXfb//Mp+svOc1jon7fgGgHgoAufhTgTkocr0CQOxkPT1lO/v/WAFQlj+KnnPBkwfADn1d6Ef8F/JKOAA8OwJq88cCQAIlAACsmAC4GeQIAMQEcNHlgxGgm4gAqQPwcAKQur0KAO5MAHlVPhgA4iuBKGkA4uaJQj8KADVg8gA8PAKiBjwEAHbs1KPrX9gbIQDwWAvQPlwByH4UCAArjwCkvmcNAPdUgFfTPJEBRL5nDQBbVYA0AckD8K3zVADguAA8GQDYDJA4ACEAlE8JANIG4NkAQBeYNgBzANBPAZADQMIAPB0A2A+WNAD26QDAfrCkAXg+AAh/JwAAvqoAHt4HBAAHAOD5AMAkOGUA4lOA4rkAwCAwZQDiRrAnAwAApAyAHRtVAcBpAYgbgZ71HwASBmCBEhAAEgZggR4QAFIG4LmdYABwgAjQPl8CAECyANixV08nAABIF4BFSkAASBaAn1fbLBEBtGqsZxScHgD2iddBeRh0AAAWygAAkCgAT70PzIaQ9AFYZAz8ikA/UAUmB8BSGYBNoanWAG6RHoBt4YkCYMe+WMZ/XgxJEoBlngMAQMIRoM0XqgHjLcykgMQAePaNYGbBiQPwxb2ad8+C2RacGADLNYGMApME4GVolhoD8XJgggAsWQLcjokDgKQAWHAOzEGRKQKw4BSAPjDNCLDYFOB2WPTwgnkJAbDEK4G0AQkDYId+Oft5HJQgAAu8E8rTgIQBWHIMxDA4TQAWrAE5IyI5ABadA/7aFQYAyQCw7ByQIiA5AOJuoB/lsgAwC0wKgC5fOANQBKQEwLKDYB4IJghAuzQAFAFppYB24RRQVqqlDUgGgOW7QG6PTQmA5btA7g5MDACzPABMg9MBYPkxADkgMQCWHgPQB6QGgFoeAPaFJQPACnOgOQdU5IBUAGhXAIAccHYAKtVccfC8KYCdgUlFgKpcIwfwTDiRLqDN1wCAUcDJAaAMPDsAWjXe/8TF0wLAE6GzAxA6QfqAMwNAJ3h6AHgofG4ACAGnB4AQcG4ACAGnB4AQIB6AlZ4Gvh8ezyxAOgDdmgDEp8KMA88bARgHnh0A7hBKIAWsVwSyLyCFLmCNXcG/hYCSV4WlA7BqBJgPjsRLuQC4oa8uulw1CVAHigZghXcD/94cRhIQHAFWBiC0gswD5QKw7iyYJAAAJAHhAKw8CXpLAkyDxALQrQ4A4yDRKaBbOwXMm8QpA6QCsPB1AVwjkRgAC18YQhmQGgDeDa1aPwdQBogFYIs2gDJANADdNgAUdT9CgDwAtmkDYhJQjWEeJLEG6OuL3oSAvBk4R14cAN7/bNQmAMRCcHQQIA2ArapAWgGxAHRbAcCDQYkArHJc8EetgGafuDgA1t8U9McmUQgQVwRusCUAAiQDsGEVCAEyI0C3WQqAAIEAbPRAEAKkArBtEfCLAMtESAwAW04CXglQ7eh5LiAFADf05bbSVdZOA2lACABbbQr53xYhw9NhKQBsngPmqXDTUwoKASBOg3W5NQF5TSkoBICQBLZ6JPw7AYXqRgoBEQBsOwx8fzSUUQjIACDkgO0jwK9CwNIPCkgB2/cBrxsFCzlpwJ4YgD36gNsRMmXW9jJqQTPKenlp4wiw9lExH+lHSAOdgKGQ9VPXi3p7Kdv295+2fR7wRzeQ7x4EXFj+rbD3F7cFIB4YWO8DQKljENi1EjDXqW+k7VjdFoCtnwn/Kwj4nb5/ayfTFkraiYbZxotgl1HA+9OhvO78Lh1hiP5j12TxQENZY8mNAYhlYLlbCIhBIOaB0bit7R+mvs1VLe9c640BiCFgrzLwtRJQeRv7Abe1/Ze8quKx1tdTR4DVjw3+xkygylUoBTZDYLa/q7OyEvnSUrb5f3FodiagrHRWzgjYrVZ/k13qeY9SKe4Mm80B2LMTfC8FyhmB0a68HO28+pusqPXr/hRxmxM2B2DfTvB3BHTbjdN1vUzgjBvn1f9qfzzUXN6p9tungH07wT8QUG1np8Hal5UW/9C1+t3+eL+ZvLMrtgdgg5Ojv4tAlWdN24/LVwPO+LD4u1ZlZf3+q4q84XB7AMSEgGhJrbKq7cw0+uUYCKF/mHyM/Ur//nvKvOBwBwDkhIB5LnDJ86bt/MyAWyDyB/eHUPipEPv/7HZkXnG6AwCSQsBtMFBkKjDwcwr1gHmiIHDGXsfoflvGxf8/xoXecLoHAKJCwC0MaDUzEOqB8WofiAQu/CU/TJMJkV9neVn//etVeQcA77MASSHgjYGYC3ozhkgQDXXfigUv4Y+GsB/+kg9LP0R+Vdb/GnRJveJ4FwB++mujdFlKY6Aq8qxo2kBBWMzTEDEw1rp/kuCctYESe43ej30XzC+zPET+D8ecMu+z2AUAiSHg9XlxCAR5ltchFIRYMHs7DsPVRrPfFD9dh2EM/3YaTfQ+rvx56euPdyYLPbpsHwDi/uC8KkUqQFAVwc9ch1gQOOj6kBXG2e1fCp9sf3O+ber4Zwv9ifml5JPsdwJg/4eCn+8hreq6uhR5Hta1appIQhvdbt/+T1M387/O1SX+2a/ymdyT63YCQFor+EEsmL0tCpXn0ew3hU+qKEL/WFeV/s7hd4JvuN8LgO0OD17g3bJAQmThTeFT+If3jBrkHlezFwBi68B13k1rJ6lvpmX7/af33xmyVQiRfKXhfgDE4wLOEgAEn1e2YwRIoQ5c5FmD6LvtdwQg7g06QRLQZdELvtd6RwC8kT0MWLACNESA0yaBeIWNdwBw1iQQO4BR8uEkuwIQkkDoBPSxE4DwE4v3BeDw46B4g5Xsw4l2BiAeIX1gAqp4i6EDgE+fCRy4DJg7QOEH1O0NwKHLgBSuL9sdgAOXAfILABEAHLYMqHPxBYAMANzgGnU8Aqo0LjIXAEB8LlgXRysEU7m9UgIAsRDUxcEKwSqR+2tFADCfGnGsECB5E5BAAA7XCkT/07jDXggAB2sF6iw0AGmcTy8FAOcPRECtkvFfDADeXYfmIATUKokGUBgA3g7mGAQk5b8gAI5CQPQ/oZvqBAHgzREISMx/UQAcgYDU/JcFQPoEJOe/MABSJyA9/6UBkDYBof9P7qZiaQCEXmBIdSIUL6hM7opScQB4ex3brNJJ+m/Su6RYHgBxKtzlye0P0FXWugQvqRYIgHd26nSRVhqoStWOQ4L3E2cifyoz9XVSu8TqIu9kXE17DADiHqHQDPxIxv+87qY0b6gXCkAgwLXzPUsppP/5fvo0/RcLQGgHx65IIg1Ul6y1Y6L+ywUgloJJpIFa6W66Wg8AK6QBIz4NvIZ/5wFglbmw9DRQF6H7Tzb8iwfgNQ1UldjlH6v/dMO/fABuaUAJDQJx+Scd/lMAwNvr1DUSK4H5LvpxMN4DwLppwEymDZWAlrb887D8rfUAsH4auMZKQNTDgUpnzQGWfyIAhCAwDl2d6UpO8adak+bsP0kAQiXgp75VuYg8oGuVtd10hOWfDgBxJjB1bSagFAi1f9MNo3EeALbPA7/dxb6T/WXWhOjvrPcAsHkecJPfF4Fgfxlq/6vxHgB2yQOxFNgLgR+v9g/mxQPAjqXAjMDm42Edcv/Nfuc9AOxZCswI5GpTBOIN4/UB7U8QgBsCpmvUP+/oXif26zxvgv3j4exPEoCIwHUaurbaJBNUIfarpjPTYK33ACBDL8aPcybIL3W1aubXKsT+bgiVv/MeACQ1hTZmgrbOlF4pFei6UplquxD73aEq/2MAcMsEU9+1KjJQLe9+kcXMPxw09h8AgJAJYhgYQioIXpX1Yi8U6ipGftW0IfOHxe+8BwDBYcCNkwtxQGd5DAT6efOrIn91P2R+64+t7AC/g40V4RAYaFSWF2Vd6x+Pr/zitvR7N43D4d0/CAAzA26YYk0Ys0GmLlV9XyzQVVj4pYorP7o/TqM31nkPACnlAnsNxpkYCeo8C7FgxqDSn4Ggo/PB+iJ4n+vgfReKvlD1nWHtHw2AuSYMgWCcYkkQY4GOGORFACHE9jrC8Kb69rksovOBlWb23gSAhrMs/SMCMAcCa4wPq3gazIxB2zRVHl2OMLwpfsxUNL5po/X9/Deu9lzmHxKAXxRYN0RTx6GPIHTtDMO7uuh7WPN9jBhx3Z/Q+wMDcEsIMRhEDoYpmhxsftftczA+Om/sOb0/OgC/ooFzNphsos/vev3szuv8aQBAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAPgKAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAEAAgAEAAgAAAAQACAAQACAAQACAAQACAAAABAAIABAAIABAAIABAAIAAAAEAAgAEAAgAAICvAAAQAKCz6j9r5bu6z+vB6gAAAABJRU5ErkJggg==",
};
