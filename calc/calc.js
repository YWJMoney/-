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
