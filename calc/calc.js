// 밤낮 공개 계산기 공통: 요율(remote/v1/rates.json), 금액 쓰기, 앱 링크.
(function () {
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
    playLink: function (campaign) {
      var ref = 'utm_source=bamnat_web&utm_medium=calc&utm_campaign=' + campaign;
      return 'https://play.google.com/store/apps/details?id=com.bamnat.app&referrer=' + encodeURIComponent(ref);
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
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('a[data-play]').forEach(function (a) { a.href = B.playLink(a.dataset.play); });
  });
})();
