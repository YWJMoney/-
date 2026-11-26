// 밤낮 공개 계산기 공통: 요율(remote/v1/rates.json), 금액 쓰기, 앱 링크.
(function () {
  document.documentElement.className += ' js';
  var base = document.currentScript.src.replace(/calc\/calc\.js.*$/, '');
  var fallback = [
    { from: '2026-01-01', minimumHourlyWage: 10320, pensionRate: 0.095, healthRate: 0.0719, longTermCareRate: 0.009448, unemploymentRate: 0.018 },
    { from: '2027-01-01', minimumHourlyWage: 10700, pensionRate: 0.1, healthRate: 0.0719, longTermCareRate: null, unemploymentRate: 0.018 }
  ];
  var B = window.Bamnat = {
    base: base,
    rates: fallback,
    pensionBases: [{ from: '2026-07-01', min: 410000, max: 6590000 }],
    ready: null,
    won: function (n) { return Math.round(n).toLocaleString('ko-KR') + '원'; },
    num: function (v) { var n = parseFloat(String(v).replace(/[^0-9.]/g, '')); return isFinite(n) ? n : 0; },
    floor10: function (n) { return Math.floor(Math.round(n) / 10) * 10; },
    // 그 해 1월 1일 기준 요율. 빈 칸(null)은 앞 해 값을 이어 써요.
    rateFor: function (year) {
      var day = year + '-12-31', pick = {};
      B.rates.slice().sort(function (a, b) { return a.from < b.from ? -1 : 1; }).forEach(function (r) {
        if (r.from <= day) Object.keys(r).forEach(function (k) { if (r[k] !== null && r[k] !== undefined) pick[k] = r[k]; });
      });
      return pick;
    },
    pensionBaseFor: function (year) {
      var day = year + '-12-31', pick = B.pensionBases[0];
      B.pensionBases.forEach(function (p) { if (p.from <= day) pick = p; });
      return pick;
    },
    // 원 × 요율을 10원 미만 버림 (공단 고지 방식). 소수 오차(26,999.999…)를 먼저 다듬어요.
    cut10: function (base, rate) { return Math.max(0, Math.floor(Math.floor(Math.round(base * rate * 1000) / 1000) / 10) * 10); },
    // 근로자 몫 4대보험 (앱 TakeHome과 같은 방식): 국민연금은 천 원 미만 버린 기준소득월액을 하한·상한 안에서,
    // 건강보험은 보수 하한 28만 원, 장기요양은 건강보험료 × 장기요양률 ÷ 건강보험률.
    insurance: function (taxable, year) {
      var r = B.rateFor(year), pb = B.pensionBaseFor(year);
      if (taxable <= 0) return { pension: 0, health: 0, care: 0, employment: 0 };
      var pension = B.cut10(Math.min(Math.max(Math.floor(taxable / 1000) * 1000, pb.min), pb.max), r.pensionRate / 2);
      var health = B.cut10(Math.max(taxable, 280000), r.healthRate / 2);
      var care = Math.floor(Math.floor(Math.round(health * r.longTermCareRate / r.healthRate * 1e6) / 1e6) / 10) * 10;
      var employment = B.cut10(taxable, r.unemploymentRate / 2);
      return { pension: pension, health: health, care: care, employment: employment, rates: r };
    },
    // 근로소득 간이세액표 (remote/v1/taxtable.json, 소득세법 시행령 별표 2). 앱 WithholdingTable과 같은 읽기.
    taxTable: null,
    loadTaxTable: function () {
      return fetch(base + 'remote/v1/taxtable.json', { cache: 'no-cache' }).then(function (r) { return r.json(); })
        .then(function (j) { var t = j.data.tables.slice().sort(function (a, b) { return a.from < b.from ? -1 : 1; }); B.taxTable = t[t.length - 1]; return B.taxTable; });
    },
    incomeTax: function (pay, family, children) {
      var t = B.taxTable; if (!t) return null;
      var f = Math.max(1, family | 0);
      var col = function (r, n) { return n <= 11 ? r[n - 1] : Math.max(0, r[10] - (r[9] - r[10]) * (n - 11)); };
      var tax;
      if (pay < t.rows[0][0] * 1000) tax = 0;
      else if (pay < 10000000) {
        var row = null; for (var i = 0; i < t.rows.length; i++) if (pay >= t.rows[i][0] * 1000) row = t.rows[i];
        tax = row ? col(row.slice(2, 13), f) : 0;
      } else {
        tax = col(t.top, f); var o = null;
        t.over.slice().sort(function (a, b) { return a.above - b.above; }).forEach(function (x) { if (pay > x.above) o = x; });
        if (o) tax += o.add + B.cut10(pay - o.above, o.factor * o.rate);
      }
      var k = Math.max(0, children | 0);
      var kid = k === 0 ? 0 : k === 1 ? t.children.one : t.children.two + t.children.more * (k - 2);
      return Math.max(0, tax - kid);
    },
    years: function () {
      var ys = {}; B.rates.forEach(function (r) { ys[r.from.slice(0, 4)] = true; });
      var now = new Date().getFullYear();
      return Object.keys(ys).map(Number).filter(function (y) { return y >= now; }).sort();
    },
    // Play 설치 링크: 어디서 왔는지(utm_campaign)를 남겨 Play Console 획득 보고서에서 봐요.
    // 커뮤니티 글 등에서 ?utm_source=…로 들어온 방문이면 그 출처를 이 방문 동안 이어 붙여요(이 브라우저 안에만 둠).
    playLink: function (campaign, medium) {
      var src = 'bamnat_web', from = B.from();
      if (from) { src = from.s; campaign = from.c ? from.c + '_' + campaign : campaign; }
      var ref = 'utm_source=' + src + '&utm_medium=' + (medium || 'calc') + '&utm_campaign=' + campaign;
      return 'https://play.google.com/store/apps/details?id=com.bamnat.app&referrer=' + encodeURIComponent(ref);
    },
    from: function () {
      var clean = function (v) { return String(v || '').replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 40); };
      try {
        var q = new URLSearchParams(location.search), s = clean(q.get('utm_source')), c = clean(q.get('utm_campaign'));
        if (s) { sessionStorage.setItem('bn_from', JSON.stringify({ s: s, c: c })); return { s: s, c: c }; }
        var j = JSON.parse(sessionStorage.getItem('bn_from') || 'null');
        return j && j.s ? { s: clean(j.s), c: clean(j.c) } : null;
      } catch (e) { return null; }
    },
    // 최저시급 칩: 올해와 내년(고시된 해)만.
    yearChips: function (box, onPick) {
      box.innerHTML = '';
      B.years().forEach(function (y, i) {
        var b = document.createElement('button');
        b.type = 'button'; b.className = 'chip'; b.dataset.year = y;
        b.textContent = y + '년 최저 ' + B.rateFor(y).minimumHourlyWage.toLocaleString('ko-KR') + '원';
        b.setAttribute('aria-pressed', i === 0 ? 'true' : 'false');
        b.onclick = function () {
          box.querySelectorAll('.chip').forEach(function (c) { c.setAttribute('aria-pressed', 'false'); });
          b.setAttribute('aria-pressed', 'true'); onPick(y);
        };
        box.appendChild(b);
      });
    }
  };
  B.ready = fetch(base + 'remote/v1/rates.json', { cache: 'no-cache' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      if (j && j.data && j.data.rates && j.data.rates.length) B.rates = j.data.rates;
      if (j && j.data && j.data.pensionBases && j.data.pensionBases.length) B.pensionBases = j.data.pensionBases;
    })
    .catch(function () {});
  // [U50-15] 밤낮은 Android 앱이에요. 아이폰·아이패드에서는 받기 단추 자리에 한 줄로 알리고, 이 사이트에서 바로 쓰는 곳으로 이어요.
  // 머리 띠의 '앱 받기'와 아래 받기 띠는 숨기고, 나머지 받기 단추는 한 줄 안내로 바꿔요.
  B.ios = function (ua, touch) { return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && touch > 1); };
  B.iosNote = function (a) {
    var onShift = /\/shift\/$/.test(location.pathname), p = document.createElement('p');
    p.className = 'ios-note';
    var dev = /iPad|Macintosh/.test(navigator.userAgent) ? '아이패드' : '아이폰';
    p.innerHTML = '밤낮 앱은 Android(Google Play)용이에요. ' + dev + '에서는 ' + (onShift
      ? '이 달력을 여기서 바로 써요. 공유 단추(네모에 위 화살표) › 홈 화면에 추가를 누르면 내 순서 그대로 다시 열려요.'
      : '<a href="' + base + 'shift/">교대 근무 달력</a>과 계산기를 여기서 바로 써요.');
    a.replaceWith(p);
    // 받기 단추 바로 아래 '로그인·회원가입 없이 무료예요' 같은 앱 이야기는 함께 숨겨요.
    var nx = p.nextElementSibling; if (nx && nx.classList.contains('note')) nx.hidden = true;
  };
  document.addEventListener('DOMContentLoaded', function () {
    if (B.ios(navigator.userAgent, navigator.maxTouchPoints || 0)) {
      document.documentElement.classList.add('ios');
      document.querySelectorAll('a[data-play]').forEach(function (a) {
        // 달력 바로 아래(app-next)에서 이미 알렸으면, 이어지는 앱 카드(cta)에서는 같은 말을 다시 하지 않아요.
        if (a.closest('.dock') || a.classList.contains('get') || (a.closest('.cta') && document.querySelector('.app-next .ios-note'))) a.hidden = true; else B.iosNote(a);
      });
      var dk = document.getElementById('dock'); if (dk) dk.hidden = true;
    }
    document.querySelectorAll('a[data-play]').forEach(function (a) { a.href = B.playLink(a.dataset.play, a.dataset.medium); });
    B.ui();
  });
  // 머리 띠(스크롤하면 진하게, 휴대폰 메뉴), 결과 숫자가 바뀌면 살짝 올라오기, 아래 카드 나타나기.
  B.ui = function () {
    var head = document.querySelector('.site-header');
    if (head) {
      var onScroll = function () { head.classList.toggle('scrolled', window.scrollY > 8); };
      onScroll(); window.addEventListener('scroll', onScroll, { passive: true });
      var btn = head.querySelector('.menu-btn');
      if (btn) btn.addEventListener('click', function () {
        var open = head.classList.toggle('open'); btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      });
      document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && head.classList.contains('open')) btn.click(); });
    }
    if (window.MutationObserver) document.querySelectorAll('.big').forEach(function (el) {
      var last = el.textContent;
      new MutationObserver(function () {
        if (el.textContent === last) return; last = el.textContent;
        el.classList.remove('bump'); void el.offsetWidth; el.classList.add('bump');
      }).observe(el, { childList: true, characterData: true, subtree: true });
    });
    // 휴대폰 아래 받기 띠: 머리 그림을 지나 내려가면 나오고, 아래 앱 카드·앱에서 이어지는 것·바닥이 보이거나 입력 중(자판이 열림)이면 숨어요.
    // 첫 화면은 assets/home.js가 따로 다뤄요.
    var dock = document.getElementById('dock');
    if (dock && !document.body.classList.contains('home') && 'IntersectionObserver' in window) {
      var vis = { hero: true, cta: false, next: false, foot: false }, typing = false;
      var upd = function () {
        var on = !vis.hero && !vis.cta && !vis.next && !vis.foot && !typing;
        dock.classList.toggle('on', on); dock.setAttribute('aria-hidden', on ? 'false' : 'true');
        dock.querySelector('a').tabIndex = on ? 0 : -1;
      };
      var watch = function (el, key) { if (el) new IntersectionObserver(function (e) { vis[key] = e[0].isIntersecting; upd(); }).observe(el); };
      watch(document.querySelector('.page-hero'), 'hero'); watch(document.querySelector('.cta'), 'cta'); watch(document.querySelector('.app-next'), 'next'); watch(document.querySelector('.site-footer'), 'foot');
      document.addEventListener('focusin', function (e) { if (/^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) { typing = true; upd(); } });
      document.addEventListener('focusout', function () { typing = false; setTimeout(upd, 150); });
    }
    var items = document.querySelectorAll('.reveal');
    if (!('IntersectionObserver' in window)) { items.forEach(function (el) { el.classList.add('in'); }); return; }
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } });
    }, { rootMargin: '0px 0px -8% 0px' });
    items.forEach(function (el) { io.observe(el); });
  };
})();

// 롤샷 광고로 온 방문 알리기는 밤낮 처리방침 3.15 시행일까지 멈춰요. 보내기 시작하는 날(한국 날짜) RS_FROM이 비어 있으면 보내지 않아요.
// 시작한 뒤에는 출처에 rollshot이 들거나 bamnat_photo인 방문만, 출처·매체·광고 이름·매장 코드 넷(영문·숫자·_-. 64자 모양만)을 한 번 알려요.
(function () {
  var RS_FROM = '2026-12-31';
  try {
    if (!RS_FROM || new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10) < RS_FROM) return;
    var q = new URLSearchParams(location.search), out = [];
    ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term'].forEach(function (k) {
      var v = String(q.get(k) || '').trim();
      if (/^[A-Za-z0-9_.-]{1,64}$/.test(v)) out.push(k + '=' + encodeURIComponent(v));
    });
    var src = out.length && /^utm_source=/.test(out[0]) ? out[0].slice(11) : '';
    if (!(src === 'bamnat_photo' || src.indexOf('rollshot') >= 0)) return;
    var ref = out.join('&'), key = 'rs_attrib:' + ref;
    try { if (sessionStorage.getItem(key)) return; sessionStorage.setItem(key, '1'); } catch (e) {}
    fetch('https://p.bamnat.app/api/attrib', {
      method: 'POST', headers: { 'content-type': 'application/json' }, credentials: 'omit', keepalive: true,
      body: JSON.stringify({ app: 'bamnat', kind: 'web', referrer: ref })
    }).catch(function () {});
  } catch (e) {}
})();
