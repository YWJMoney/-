// 밤낮 첫 화면 움직임 (웹 2판, 2026-11-10). 입력값·방문 기록은 어디에도 보내지 않아요.
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };
  var NAME = { D: '주', E: '오', N: '야', O: '휴', X: '당', B: '비' };
  var LONG = { D: '주간', E: '오후', N: '야간', O: '휴무', X: '당번', B: '비번' };
  var ICON = {
    alarm: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13" r="7.5"/><path d="M12 9.5V13l2.2 1.6M4.5 4.8 7 2.8M19.5 4.8 17 2.8"/></svg>',
    moon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>',
    cal: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/></svg>',
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.6 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/></svg>',
    won: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6l3.5 12L12 8l4.5 10L20 6M3 11h18"/></svg>',
    check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 12.5 4.2 4L19 7"/></svg>'
  };

  /* ---------- 1. 밤하늘 ---------- */
  (function sky() {
    var c = $('sky'); if (!c || !c.getContext) return;
    var ctx = c.getContext('2d'), stars = [], shoot = null, w = 0, h = 0, dpr = Math.min(window.devicePixelRatio || 1, 2), on = true, raf = 0, t0 = 0;
    function size() {
      w = c.clientWidth; h = c.clientHeight; c.width = w * dpr; c.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      var n = Math.round(w * h / 5200); stars = [];
      for (var i = 0; i < n; i++) stars.push({ x: Math.random() * w, y: Math.random() * h * 0.92, r: Math.random() * 1.15 + 0.25, p: Math.random() * 6.28, s: 0.6 + Math.random() * 1.6, g: Math.random() < 0.08 });
    }
    function frame(t) {
      ctx.clearRect(0, 0, w, h);
      var k = t / 1000;
      for (var i = 0; i < stars.length; i++) {
        var s = stars[i], a = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(k * s.s + s.p));
        ctx.globalAlpha = a; ctx.fillStyle = s.g ? '#f0d9a6' : '#ffffff';
        ctx.beginPath(); ctx.arc(s.x, s.y, s.r, 0, 6.283); ctx.fill();
      }
      if (!reduce) {
        if (!shoot && Math.random() < 0.004) shoot = { x: Math.random() * w * 0.7 + w * 0.2, y: Math.random() * h * 0.35, l: 0 };
        if (shoot) {
          shoot.l += 0.018; var L = shoot.l, x = shoot.x - L * 260, y = shoot.y + L * 120;
          var g = ctx.createLinearGradient(x, y, x + 90, y - 42); g.addColorStop(0, 'rgba(255,255,255,0.9)'); g.addColorStop(1, 'rgba(255,255,255,0)');
          ctx.globalAlpha = Math.max(0, 1 - L); ctx.strokeStyle = g; ctx.lineWidth = 1.4;
          ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + 90, y - 42); ctx.stroke();
          if (L > 1) shoot = null;
        }
      }
      ctx.globalAlpha = 1;
      if (on && !reduce) raf = requestAnimationFrame(frame);
    }
    size(); frame(0);
    var rs; window.addEventListener('resize', function () { clearTimeout(rs); rs = setTimeout(function () { size(); frame(performance.now()); }, 150); });
    if ('IntersectionObserver' in window) new IntersectionObserver(function (e) {
      on = e[0].isIntersecting; cancelAnimationFrame(raf); if (on && !reduce) raf = requestAnimationFrame(frame);
    }).observe(c);
    else if (!reduce) raf = requestAnimationFrame(frame);
  })();

  /* ---------- 2. 시각에 맞춘 인사 ---------- */
  function greet() {
    var d = new Date(), h = d.getHours(), t = pad(h) + ':' + pad(d.getMinutes());
    var msg = h >= 22 || h < 5 ? '밤새 일하는 분들, 오늘도 수고 많아요'
      : h < 9 ? '야간 마치고 퇴근길이라면, 푹 쉬어요'
      : h < 12 ? '오늘 근무가 뭐였더라 싶을 때'
      : h < 18 ? '낮 근무 중인 분들, 조금만 더 힘내요'
      : '내일 근무, 미리 보고 마음 편히';
    if ($('clock')) $('clock').textContent = t;
    if ($('greet')) $('greet').textContent = msg;
    if ($('ptime')) $('ptime').textContent = h + ':' + pad(d.getMinutes());
  }
  greet(); setInterval(greet, 20000);

  /* ---------- 3. 살아 있는 근무 달력 ---------- */
  var PATS = [
    { k: '4조 2교대', c: 'DNOO', teams: 4, step: 1, t: { D: [7, 19], N: [19, 7] } },
    { k: '3조 2교대', c: 'DDNNOO', teams: 3, step: 2, t: { D: [7, 19], N: [19, 7] } },
    { k: '4조 3교대', c: 'DDEENNOO', teams: 4, step: 2, t: { D: [6, 14], E: [14, 22], N: [22, 6] } },
    { k: '주야비휴', c: 'DNBO', teams: 4, step: 1, t: { D: [9, 18], N: [18, 9] } },
    { k: '24시간 맞교대', c: 'XBO', teams: 3, step: 1, t: { X: [9, 9] } },
    { k: '일근 월~금', c: 'DDDDDOO', teams: 0, step: 0, t: { D: [9, 18] } }
  ];
  var ANCHOR = new Date(2026, 0, 5); // 월요일. 1조가 순서 첫날을 하는 날
  // 출근 알람은 출근 1시간 20분 전. 폰 그림·다이얼·말풍선이 모두 이 값 하나로 셈해요.
  var LEAD = 4 / 3;
  var state = { p: 0, team: 0 }, holidays = {};
  function hm(x) { var h = Math.floor(x), m = Math.round((x - h) * 60); return pad((h + 24) % 24) + ':' + pad(m); }
  function codeOn(p, team, d) {
    var diff = Math.round((d - ANCHOR) / 864e5) - team * p.step, n = p.c.length, k = ((diff % n) + n) % n;
    return p.c[k];
  }
  function setV(id, txt) {
    var el = $(id); if (!el || el.textContent === txt) return;
    el.textContent = txt; el.classList.remove('flip'); void el.offsetWidth; el.classList.add('flip');
  }
  function renderPhone(animate) {
    var p = PATS[state.p], now = new Date(), today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    var first = new Date(today.getFullYear(), today.getMonth(), 1), last = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    $('pmonth').textContent = (first.getMonth() + 1) + '월'; $('pyear').textContent = first.getFullYear() + '년';
    var out = '', nights = 0, offs = 0, work = 0, idx = 0;
    for (var i = 0; i < first.getDay(); i++) out += '<div class="day empty"></div>';
    for (var d = 1; d <= last.getDate(); d++) {
      var day = new Date(first.getFullYear(), first.getMonth(), d), s = codeOn(p, state.team, day), iso = day.getFullYear() + '-' + pad(day.getMonth() + 1) + '-' + pad(d);
      if (s === 'N' || s === 'X') nights++; if (s === 'O' || s === 'B') offs++; else work++;
      var cls = 'day' + (+day === +today ? ' today' : '') + (day.getDay() === 0 ? ' sun' : '') + (holidays[iso] ? ' hol' : '');
      out += '<div class="' + cls + '"' + (holidays[iso] ? ' title="' + holidays[iso] + '"' : '') + '><span class="n">' + d + '</span><span class="chipx ' + s + (animate ? ' pop' : '') + '" style="--i:' + (idx++) + '">' + NAME[s] + '</span></div>';
    }
    $('pdays').innerHTML = out;
    var ts = codeOn(p, state.team, today), tt = p.t[ts];
    var ic = $('tic'); ic.textContent = NAME[ts]; ic.className = 'ic chipx ' + ts; ic.style.minWidth = '0';
    var nextWork = null, nextOff = null;
    for (var j = 0; j < 60 && (nextWork === null || nextOff === null); j++) {
      var dd = new Date(today.getFullYear(), today.getMonth(), today.getDate() + j), cc = codeOn(p, state.team, dd);
      if (nextWork === null && p.t[cc]) nextWork = { j: j, c: cc };
      if (nextOff === null && !p.t[cc] && j > 0) nextOff = j;
    }
    if (tt) {
      $('ttitle').textContent = '오늘 ' + LONG[ts] + ' ' + hm(tt[0]) + '–' + (ts === 'X' ? '내일 ' : '') + hm(tt[1]);
      $('tsub').textContent = '출근 알람 ' + hm(tt[0] - LEAD) + ' · 근무 ' + (ts === 'X' ? 24 : ((tt[1] - tt[0] + 24) % 24)) + '시간';
    } else {
      $('ttitle').textContent = '오늘은 쉬는 날';
      $('tsub').textContent = '근무 알람은 조용해요' + (nextWork ? ' · ' + (nextWork.j === 1 ? '내일' : nextWork.j + '일 뒤') + ' ' + LONG[nextWork.c] : '');
    }
    var ac = tt ? ts : nextWork && nextWork.c, at = ac && p.t[ac];
    setV('bAlarm', at ? LONG[ac] + ' 날 ' + hm(at[0] - LEAD) : '쉬는 날엔 조용');
    setV('bCount', p.c.indexOf('N') >= 0 ? '이번 달 야간 ' + nights + '번' : p.c.indexOf('X') >= 0 ? '이번 달 당번 ' + nights + '번' : '이번 달 근무 ' + work + '일');
    setV('bOff', '쉬는 날 ' + offs + '일');
    setV('bNext', !tt ? '오늘은 쉬는 날' : nextOff ? '다음 쉬는 날 D-' + nextOff : '다음 쉬는 날');
  }
  function chips() {
    var box = $('pats'); if (!box) return;
    box.innerHTML = PATS.map(function (p, i) { return '<button type="button" data-i="' + i + '" aria-pressed="' + (i === state.p) + '">' + p.k + '</button>'; }).join('');
    box.onclick = function (e) {
      var b = e.target.closest('button'); if (!b) return;
      state.p = +b.dataset.i; state.team = 0;
      box.querySelectorAll('button').forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
      teams(); renderPhone(true);
    };
    teams();
  }
  function teams() {
    var p = PATS[state.p], box = $('teams');
    if (!p.teams) { box.innerHTML = ''; box.style.display = 'none'; return; }
    box.style.display = '';
    var h = ''; for (var i = 0; i < p.teams; i++) h += '<button type="button" data-t="' + i + '" aria-pressed="' + (i === state.team) + '">' + (i + 1) + '조</button>';
    box.innerHTML = h;
    box.onclick = function (e) {
      var b = e.target.closest('button'); if (!b) return;
      state.team = +b.dataset.t;
      box.querySelectorAll('button').forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
      renderPhone(true);
    };
  }
  if ($('pdays')) {
    chips(); renderPhone(!reduce);
    var base = (window.Bamnat && Bamnat.base) || '';
    fetch(base + 'remote/v1/holidays.json').then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
      if (j && j.data && j.data.days) { j.data.days.forEach(function (x) { holidays[x.date] = x.name; }); renderPhone(false); }
    }).catch(function () {});
    // 손가락·마우스를 따라 살짝 기울기
    var phone = $('phone'), hero = document.querySelector('.hero');
    if (phone && hero && !reduce && window.matchMedia('(hover: hover)').matches) {
      hero.addEventListener('pointermove', function (e) {
        var r = hero.getBoundingClientRect(), x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5;
        phone.style.setProperty('--ry', (-9 + x * 14).toFixed(2) + 'deg'); phone.style.setProperty('--rx', (4 - y * 10).toFixed(2) + 'deg');
      });
      hero.addEventListener('pointerleave', function () { phone.style.removeProperty('--ry'); phone.style.removeProperty('--rx'); });
    }
    var tl = $('try-link');
    if (tl) tl.addEventListener('click', function (e) {
      e.preventDefault(); var t = $('try'); t.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'center' });
      var b = $('pats').querySelector('button'); if (b) setTimeout(function () { b.focus({ preventScroll: true }); }, 500);
    });
  }

  /* ---------- 4. 24시간 다이얼 ---------- */
  var DAY_IN = 7, NIGHT_IN = 19, dayAlarm = DAY_IN - LEAD, nightAlarm = NIGHT_IN - LEAD;
  var MODES = {
    day: { name: '주간 근무 날', head: hm(DAY_IN) + ' 출근', sub: '알람 ' + hm(dayAlarm) + ' · 잠 7시간', items: [
      { from: dayAlarm - 7, to: dayAlarm, color: '#e9dcc0', icon: 'moon', title: '잠 ' + hm(dayAlarm - 7) + ' – ' + hm(dayAlarm), text: '근무에 맞춘 잘 시간을 오늘 하루 줄에 보여 줘요.' },
      { at: dayAlarm, icon: 'alarm', title: '출근 알람 ' + hm(dayAlarm), text: '주간 근무 날 시각에 울려요. 점점 커지는 소리와 다시 울림까지.' },
      { from: DAY_IN, to: NIGHT_IN, color: '#5c8cff', icon: 'cal', title: '주간 근무 ' + hm(DAY_IN) + ' – ' + hm(NIGHT_IN), text: '달력에 이미 채워져 있어요. 야근·대근도 그 날짜를 눌러 적어요.' }] },
    night: { name: '야간 근무 날', head: hm(NIGHT_IN) + ' 출근', sub: '알람 ' + hm(nightAlarm) + ' · 아침잠 6시간 반', items: [
      { from: NIGHT_IN, to: DAY_IN, color: '#9a83ff', icon: 'cal', title: '야간 근무 ' + hm(NIGHT_IN) + ' – ' + hm(DAY_IN), text: '야간·연장 수당까지 이번 달 예상 월급에 들어가요.' },
      { from: 8.5, to: 15, color: '#e9dcc0', icon: 'moon', title: '아침잠 08:30 – 15:00', text: '야간 뒤 잘 시간도 계획에 넣고, 자는 동안 방해 금지를 켤 수 있어요.' },
      { at: nightAlarm, icon: 'alarm', title: '출근 알람 ' + hm(nightAlarm), text: '야간 날은 저녁 시각에. 주간 날 알람과 따로 맞춰져요.' },
      { ref: 1, icon: 'heart', title: '가족에게 \'지금 자는 중\'', text: '근무표를 나눈 가족이 몇 시쯤 깨는지 알아요. 링크마다 켜고 꺼요.' }] },
    off: { name: '쉬는 날', head: '알람 없음', sub: '근무 알람도 쉬어요', items: [
      { from: 0.5, to: 9, color: '#e9dcc0', icon: 'moon', title: '푹 자기 00:30 – 09:00', text: '쉬는 날엔 근무 알람이 울리지 않아요.' },
      { from: 11, to: 20, color: '#4cc38a', icon: 'heart', title: '같이 쉬는 날', text: '가족·동료와 근무표를 나누면 서로 같이 쉬는 날이 보여요.' },
      { ref: -1, icon: 'won', title: '이번 달 쉬는 날과 남은 연차', text: '달력에서 쉬는 날 수와 남은 연차를 바로 봐요.' }] }
  };
  var C = 200, R = 150;
  function ang(h) { return (h / 24) * Math.PI * 2 + Math.PI / 2; }
  function pt(h, r) { var a = ang(h); return [C + r * Math.cos(a), C + r * Math.sin(a)]; }
  function arcPath(h1, h2, r) {
    var span = ((h2 - h1) % 24 + 24) % 24 || 24, a = pt(h1, r), b = pt(h1 + span, r);
    return 'M' + a[0].toFixed(2) + ' ' + a[1].toFixed(2) + 'A' + r + ' ' + r + ' 0 ' + (span > 12 ? 1 : 0) + ' 1 ' + b[0].toFixed(2) + ' ' + b[1].toFixed(2);
  }
  function dialBase() {
    var s = '<defs><radialGradient id="dg" cx="50%" cy="50%" r="50%"><stop offset="60%" stop-color="#e2c48a" stop-opacity=".9"/><stop offset="100%" stop-color="#e2c48a" stop-opacity="0"/></radialGradient></defs>';
    s += '<circle cx="200" cy="200" r="150" fill="none" stroke="rgba(255,255,255,.06)" stroke-width="26"/>';
    s += '<path d="' + arcPath(6, 18, 150) + '" fill="none" stroke="rgba(242,236,225,.07)" stroke-width="26"/>';
    s += '<path d="M78 200a122 122 0 0 1 244 0z" fill="rgba(242,236,225,.035)"/>';
    for (var i = 0; i < 24; i++) {
      var big = i % 6 === 0, a = pt(i, 176), b = pt(i, big ? 190 : 184);
      s += '<line x1="' + a[0].toFixed(1) + '" y1="' + a[1].toFixed(1) + '" x2="' + b[0].toFixed(1) + '" y2="' + b[1].toFixed(1) + '" stroke="#f2ece1" stroke-opacity="' + (big ? 0.9 : 0.35) + '" stroke-width="' + (big ? 2.4 : 1.4) + '" stroke-linecap="round"/>';
    }
    [[0, '0시'], [6, '6시'], [12, '12시'], [18, '18시']].forEach(function (x) {
      var p = pt(x[0], 210); s += '<text x="' + p[0].toFixed(1) + '" y="' + (p[1] + 5).toFixed(1) + '" text-anchor="middle" fill="rgba(242,236,225,.55)" font-size="14" font-weight="600">' + x[1] + '</text>';
    });
    s += '<g id="arcs"></g><g id="hand"></g>';
    return s;
  }
  var dial = $('dial'), legend = $('legend'), dialc = $('dialc'), mode = 'night', pinned = null;
  function center(small, strong, span, time) {
    dialc.innerHTML = '<div class="swap"><small>' + small + '</small><strong' + (time ? ' class="time"' : '') + '>' + strong + '</strong><span>' + span + '</span></div>';
  }
  function hi(i) {
    var m = MODES[mode], target = i == null ? null : (m.items[i].ref != null ? m.items[i].ref : i);
    dial.querySelectorAll('.arc').forEach(function (a) {
      var k = +a.dataset.i; a.classList.toggle('dim', target != null && target >= 0 && k !== target); a.classList.toggle('hi', k === target);
    });
    legend.querySelectorAll('li').forEach(function (li, k) { li.classList.toggle('hi', k === i); var b = li.querySelector('button'); if (b) b.setAttribute('aria-pressed', k === pinned ? 'true' : 'false'); });
    // 고른 칸: 이름은 위 작은 글, 시각은 큰 글 한 줄('야간 근무' / '19:00 – 07:00'). 시각이 없는 칸은 이름을 큰 글로.
    var t = i == null ? null : m.items[i].title, tm = t && /^(.*?)\s(\d\d:\d\d.*)$/.exec(t);
    if (i == null) center(m.name, m.head, m.sub); else if (tm) center(tm[1], tm[2], m.name, true); else center(m.name, t, '');
  }
  function drawMode(name) {
    mode = name; var m = MODES[name], g = dial.querySelector('#arcs'), s = '';
    m.items.forEach(function (it, i) {
      if (it.at != null) {
        var p = pt(it.at, 150);
        s += '<g class="arc" data-i="' + i + '"><circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="22" fill="url(#dg)" opacity=".55"/><circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="9" fill="#e2c48a" stroke="#15192b" stroke-width="3"/></g>';
      } else if (it.from != null) {
        s += '<path class="arc" data-i="' + i + '" d="' + arcPath(it.from, it.to, 150) + '" stroke="' + it.color + '" stroke-width="22" pathLength="1" stroke-dasharray="1" stroke-dashoffset="' + (reduce ? 0 : 1) + '"/>';
      }
    });
    g.innerHTML = s;
    if (!reduce) requestAnimationFrame(function () { requestAnimationFrame(function () { g.querySelectorAll('path.arc').forEach(function (p, k) { p.style.transitionDelay = (k * 0.15) + 's'; p.setAttribute('stroke-dashoffset', '0'); }); }); });
    // 범례 한 줄은 단추: 마우스를 올리거나, 누르거나, 자판으로 골라도 다이얼의 그 칸이 밝아져요.
    legend.innerHTML = m.items.map(function (it, i) {
      var col = it.color || '#e2c48a';
      return '<li style="--i:' + i + '" data-i="' + i + '"><button type="button" aria-pressed="false"><span class="dot" style="background:' + col + '22;color:' + col + '" aria-hidden="true">' + ICON[it.icon] + '</span><span class="txt"><strong>' + it.title + '</strong><span>' + it.text + '</span></span></button></li>';
    }).join('');
    legend.querySelectorAll('li').forEach(function (li) {
      var i = +li.dataset.i, b = li.querySelector('button');
      li.addEventListener('mouseenter', function () { hi(i); }); li.addEventListener('mouseleave', function () { hi(pinned); });
      b.addEventListener('click', function () { pinned = pinned === i ? null : i; hi(pinned); });
    });
    pinned = null;
    hi(null);
  }
  function hand() {
    // 지금 시각: 눈금 바깥에 작은 표시와 '지금'
    var d = new Date(), h = d.getHours() + d.getMinutes() / 60, a = pt(h, 168), b = pt(h, 194), l = pt(h, 226);
    // '지금' 글은 다이얼 안쪽으로 붙여 좁은 화면에서 잘리지 않게, 0·6·12·18시 글과도 겹치지 않게 비켜요.
    var lx = l[0], ly = l[1] + 5, anchor = lx > 330 ? 'end' : lx < 70 ? 'start' : 'middle';
    if (Math.abs(l[1] - C) < 26) ly = C + (l[1] >= C ? 32 : -22);
    if (Math.abs(l[0] - C) < 26) { lx = C + (l[0] >= C ? 30 : -30); anchor = l[0] >= C ? 'start' : 'end'; }
    dial.querySelector('#hand').innerHTML = '<line x1="' + a[0].toFixed(1) + '" y1="' + a[1].toFixed(1) + '" x2="' + b[0].toFixed(1) + '" y2="' + b[1].toFixed(1) + '" stroke="#ffffff" stroke-width="3.2" stroke-linecap="round"/><circle cx="' + a[0].toFixed(1) + '" cy="' + a[1].toFixed(1) + '" r="4.5" fill="#fff"/><text x="' + lx.toFixed(1) + '" y="' + ly.toFixed(1) + '" text-anchor="' + anchor + '" fill="#fff" font-size="13" font-weight="800">지금 ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + '</text>';
  }
  if (dial) {
    dial.innerHTML = dialBase(); hand(); setInterval(hand, 60000);
    var started = false, startMode = function () { if (!started) { started = true; drawMode('night'); } };
    if ('IntersectionObserver' in window && !reduce) {
      var io = new IntersectionObserver(function (e) { if (e[0].isIntersecting) { startMode(); io.disconnect(); } }, { threshold: 0.3 }); io.observe(dial);
    } else startMode();
    document.querySelectorAll('.modes button').forEach(function (b) {
      b.addEventListener('click', function () {
        document.querySelectorAll('.modes button').forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
        started = true; drawMode(b.dataset.mode);
      });
    });
  }

  /* ---------- 5. 누구의 근무든 ---------- */
  // 알바와 현장은 따로(알바는 주휴·알바 월급, 현장은 일당), 혼자 하는 가게, 투자도 한 자리씩.
  var WHO = [
    { tab: '교대 근무자', tag: '교대 근무자', h: '근무 순서만 고르면<br>몇 년치가 한 번에', img: '01-calendar', alt: '밤낮 앱의 한 달 근무 달력', li: [['3조2교대·4조2교대·4조3교대·주야비휴', '순서와 첫날만 고르면 달력이 채워져요.'], ['근무마다 다른 알람', '주간·야간마다 다른 시각, 쉬는 날엔 조용해요.'], ['야간·휴일 수당까지', '이번 달 예상 월급을 미리 봐요.']], more: ['shift/', '내 교대 순서로 달력 만들어 보기'] },
    { tab: '간호사·병원', tag: '매달 바뀌는 듀티표', h: '근무표 나오는 날,<br>찍으면 한 달 끝', img: '02-roster', alt: '밤낮 앱의 근무표 사진 넣기 화면', li: [['사진·엑셀·PDF 근무표', '열면 내 줄을 찾아 달력에 넣어요.'], ['헷갈리는 날은 따로', '바로 고칠 수 있게 짚어 줘요.'], ['나이트 뒤 잘 시간', '오늘 하루 줄에 낮잠까지 들어가요.']], more: ['form/duty/', '듀티표 엑셀 양식 받기'] },
    { tab: '직장인', tag: '일근·사무직', h: '야근·연차·공휴일을<br>한 달력에', img: '12-leave', alt: '밤낮 앱의 남은 연차 화면', li: [['연차는 저절로', '입사일만 넣으면 생기는 날·없어지는 날까지.'], ['야근·특근은 그 자리에서', '날짜를 눌러 적으면 이번 달 월급에 들어가요.'], ['공휴일·대체공휴일', '내 달력에 함께 보여요.']], more: ['calc/salary/', '월급 실수령액 계산하기'] },
    { tab: '알바', tag: '알바·시급', h: '일한 시간만 적으면<br>주휴까지 이번 달 월급', img: '05-pay', alt: '밤낮 앱의 이번 달 예상 월급 화면', li: [['일한 날과 시간', '출퇴근 시각을 적으면 이번 달 시간이 쌓여요.'], ['주휴수당은 주마다', '주 15시간을 넘긴 주를 따져 더해요.'], ['3.3%·4대보험 뺀 금액', '통장에 들어올 돈을 미리 봐요.']], more: ['calc/alba/', '알바 월급 계산하기'] },
    { tab: '현장', tag: '현장·프리랜서', h: '어느 현장 며칠,<br>일당까지 달력에', img: '09-field', alt: '밤낮 앱의 현장 일정·일당 화면', li: [['일한 날·현장·일당', '칸마다 적으면 이번 달 번 돈이 한눈에.'], ['받을 돈 챙기기', '아직 못 받은 돈을 따로 보여 줘요.'], ['계약금·잔금까지', '건마다 받은 돈과 남은 돈을 나눠 적어요.']], more: ['calc/daily/', '일당 세금 계산하기'] },
    { tab: '가게', tag: '가게를 하는 분', h: '오늘 매출과 쉬는 날을<br>한 달력에', img: '14-ledger', alt: '밤낮 앱의 가계부 화면', li: [['결제 수단별 매출', '카드·현금·배달로 나눠 적고, 마감 시재도 맞춰 봐요.'], ['카드 대금 들어온 날', '그날 카드 매출이 입금됐는지 표시해요.'], ['부가세·종합소득세', '세금 낼 달을 달력에서 미리 알려 줘요.']], more: ['holiday/', '공휴일 보고 쉬는 날 정하기'] },
    { tab: '투자', tag: '주식·ETF·코인', h: '사고판 날과 그날 손익을<br>달력 한 칸에', img: '01-calendar', alt: '밤낮 앱의 한 달 달력', li: [['매매 기록', '국내·해외 주식, ETF, 코인을 사고판 날에 적어요.'], ['그날 손익', '달력 칸마다 수익과 손실이 보여요.'], ['넣은 돈도 함께', '입금한 날도 같은 달력에 남아요.']], more: null },
    { tab: '팀 리더', tag: '근무표를 짜는 리더', h: '팀 근무표,<br>링크 하나로 공유', img: '06-team', alt: '밤낮 앱의 팀 근무표 화면', li: [['팀원마다 자기 근무만', '팀원은 앱 없이도 링크로 열어 봐요.'], ['근무 신청도 링크로', '날마다 몇 명이 신청했는지 한눈에.'], ['무리한 배치 짚기', '연속 근무, 야간 바로 뒤 주간을 알려 줘요.']], more: ['shift/print/', '조 나란히 근무표 인쇄하기'] },
    { tab: '사장님', tag: '직원을 둔 사장님', h: '알바 스케줄부터<br>급여 대장까지', img: '07-boss', alt: '밤낮 앱의 사장님 직원 근무 화면', li: [['요일별 직원 근무표', '출퇴근은 직원에게 보낸 링크로 기록해요.'], ['주휴·가산까지 급여 대장', '근무 시간과 시급으로 달마다 만들어요.'], ['근로계약서 링크 서명', '만들어 보내면 직원이 링크로 서명해요.']], more: ['form/alba-week/', '알바 주간 스케줄표 받기'] },
    { tab: '가족', tag: '가족과 근무 나누기', h: '아빠 지금 자요?<br>링크 하나로 알아요', img: '10-family', alt: '밤낮 앱의 가족과 나눈 근무 화면', li: [['같이 쉬는 날', '받은 근무표로 서로 겹치는 쉬는 날을 보여 줘요.'], ['지금 자는 중 · 몇 시쯤 깸', '보내는 사람이 링크마다 켜고 꺼요.'], ['내 달력과 오가며', '받은 달력과 내 달력을 한 앱에서 봐요.']], more: null }
  ];
  var whoTabs = $('whoTabs'), whoPanel = $('whoPanel');
  function who(i, focus) {
    var w = WHO[i];
    whoTabs.querySelectorAll('button').forEach(function (b, k) { b.setAttribute('aria-selected', k === i ? 'true' : 'false'); b.tabIndex = k === i ? 0 : -1; if (k === i && focus) b.focus(); });
    whoPanel.innerHTML = '<div class="txt"><span class="tag">' + w.tag + '</span><h3>' + w.h + '</h3><ul>' +
      w.li.map(function (x) { return '<li>' + ICON.check + '<span><b>' + x[0] + '</b><br>' + x[1] + '</span></li>'; }).join('') + '</ul>' +
      (w.more ? '<a class="more" href="' + w.more[0] + '">' + w.more[1] + ' →</a>' : '') + '</div>' +
      '<div class="pic"><img src="assets/app/' + w.img + '-360.webp" srcset="assets/app/' + w.img + '-360.webp 360w, assets/app/' + w.img + '-720.webp 720w" sizes="(min-width: 900px) 440px, 330px" width="360" height="640" alt="' + w.alt + '" decoding="async"></div>';
  }
  if (whoTabs) {
    whoTabs.innerHTML = WHO.map(function (w, i) { return '<button type="button" role="tab" id="who-' + i + '" aria-controls="whoPanel">' + w.tab + '</button>'; }).join('');
    whoTabs.addEventListener('click', function (e) { var b = e.target.closest('button'); if (b) who([].indexOf.call(whoTabs.children, b)); });
    whoTabs.addEventListener('keydown', function (e) {
      var cur = [].findIndex.call(whoTabs.children, function (b) { return b.getAttribute('aria-selected') === 'true'; });
      if (e.key === 'ArrowRight') { e.preventDefault(); who((cur + 1) % WHO.length, true); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); who((cur + WHO.length - 1) % WHO.length, true); }
      if (e.key === 'Home') { e.preventDefault(); who(0, true); }
      if (e.key === 'End') { e.preventDefault(); who(WHO.length - 1, true); }
    });
    who(0);
    WHO.forEach(function (w) { var im = new Image(); im.src = 'assets/app/' + w.img + '-360.webp'; });
  }

  /* ---------- 6. 가로로 흐르는 화면들 ---------- */
  var rail = $('rail'), dots = $('dots');
  if (rail) {
    var figs = rail.querySelectorAll('figure');
    dots.innerHTML = [].map.call(figs, function () { return '<i></i>'; }).join('');
    var step = function () { var f = figs[0]; return f ? f.getBoundingClientRect().width + 18 : 300; };
    $('gPrev').onclick = function () { rail.scrollBy({ left: -step(), behavior: reduce ? 'auto' : 'smooth' }); };
    $('gNext').onclick = function () { rail.scrollBy({ left: step(), behavior: reduce ? 'auto' : 'smooth' }); };
    var mark = function () {
      var r = rail.getBoundingClientRect(), mid = r.left + r.width / 2, best = 0, bd = 1e9;
      figs.forEach(function (f, i) { var b = f.getBoundingClientRect(), d = Math.abs(b.left + b.width / 2 - mid); if (d < bd) { bd = d; best = i; } });
      dots.querySelectorAll('i').forEach(function (d, i) { d.classList.toggle('on', i === best); });
    };
    rail.addEventListener('scroll', function () { requestAnimationFrame(mark); }, { passive: true }); mark();
    // 마우스로 끌어서 넘기기
    var down = false, sx = 0, sl = 0, moved = false;
    rail.addEventListener('pointerdown', function (e) { if (e.pointerType !== 'mouse') return; down = true; moved = false; sx = e.clientX; sl = rail.scrollLeft; rail.style.scrollSnapType = 'none'; });
    window.addEventListener('pointermove', function (e) { if (!down) return; var dx = e.clientX - sx; if (Math.abs(dx) > 4) moved = true; rail.scrollLeft = sl - dx; });
    window.addEventListener('pointerup', function () { if (!down) return; down = false; rail.style.scrollSnapType = ''; });
    rail.addEventListener('click', function (e) { if (moved) { e.preventDefault(); e.stopPropagation(); } }, true);
    rail.addEventListener('dragstart', function (e) { e.preventDefault(); });
  }

  /* ---------- 7. 약속 카드 빛 ---------- */
  document.querySelectorAll('.pcard').forEach(function (c) {
    c.addEventListener('pointermove', function (e) { var r = c.getBoundingClientRect(); c.style.setProperty('--mx', (e.clientX - r.left) + 'px'); c.style.setProperty('--my', (e.clientY - r.top) + 'px'); });
  });

  /* ---------- 8. 무료 도구 ---------- */
  var mini = $('miniCal');
  if (mini) { var mh = ''; for (var i = 0; i < 28; i++) mh += '<i class="' + 'DNOO'[i % 4] + '" style="--i:' + i + '"></i>'; mini.innerHTML = mh; }
  var qH = $('qHours');
  if (qH && window.Bamnat) {
    var B = window.Bamnat, wage = 10320;
    // 주휴는 계산기 쪽과 같은 식(Bamnat.f.juhyu, 앱과 같은 사례로 시험)으로만 셈해요.
    var qcalc = function () {
      var hrs = +qH.value, r = B.f.juhyu(wage, hrs * 60);
      qH.style.setProperty('--p', ((hrs - qH.min) / (qH.max - qH.min) * 100) + '%');
      $('qH').textContent = hrs; $('qOut').textContent = B.won(r.pay);
      $('qMonth').textContent = r.holMin ? '한 달 약 ' + B.won(r.monthPay) : '주 15시간부터 생겨요';
    };
    qH.addEventListener('input', qcalc);
    B.ready.then(function () {
      var y = B.years()[0] || new Date().getFullYear(); wage = B.rateFor(y).minimumHourlyWage || wage;
      B.yearChips($('qYears'), function (yy) { wage = B.rateFor(yy).minimumHourlyWage; qcalc(); }); qcalc();
    });
    qcalc();
  }

  /* ---------- 9. 휴대폰 아래 받기 띠 ---------- */
  var dock = $('dock'), heroEl = document.querySelector('.hero'), finalEl = document.querySelector('.final'), foot = document.querySelector('.site-footer');
  if (dock && 'IntersectionObserver' in window) {
    var vis = { hero: true, final: false, foot: false };
    var upd = function () { var on = !vis.hero && !vis.final && !vis.foot; dock.classList.toggle('on', on); dock.setAttribute('aria-hidden', on ? 'false' : 'true'); dock.querySelector('a').tabIndex = on ? 0 : -1; };
    var watch = function (el, key) { if (el) new IntersectionObserver(function (e) { vis[key] = e[0].isIntersecting; upd(); }).observe(el); };
    watch(heroEl, 'hero'); watch(finalEl, 'final'); watch(foot, 'foot');
  }
})();
