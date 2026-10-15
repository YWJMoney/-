// 화면. 서버도, 분석 SDK도, 광고도 없다. 모든 데이터는 이 기기의 localStorage에만 있다.
import { createStore } from './store.js';
import { parseMany, findDuplicate, findCancelTarget, guessCategory } from './parser.js';
import { upcoming, summary, categoryTotals, cardPeriod, STATUS_LABEL } from './engine.js';
import { todayStr, fmtMD, fmtMDW, diffDays, parts, ymd, daysInMonth } from './dates.js';
import { toCSV, backupJSON, readBackup, toICS } from './io.js';

export const APP_VERSION = '0.1.0';

const store = createStore();
const $view = document.getElementById('view');
const $sheet = document.getElementById('sheet');
const $sheetTitle = document.getElementById('sheet-title');
const $sheetBody = document.getElementById('sheet-body');

const CATEGORIES = ['식비', '카페', '배달', '장보기·쇼핑', '편의점', '교통', '자동차', '주거', '공과금', '통신', '구독', '보험', '의료', '교육', '경조사', '여가', '기타'];
const INCOME_CATEGORIES = ['수입', '급여', '부수입', '용돈', '환급'];
const OB_KINDS = ['구독', '고정비', '공과금', '보험', '대출', '월세', '통신', '기타'];
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

let tab = 'home';
let historyMonth = todayStr().slice(0, 7);
let pasteDraft = null; // 붙여넣기 미리보기 상태

// ── 공통 도우미 ────────────────────────────────────────────────
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const won = (n) => `${Math.round(Number(n) || 0).toLocaleString('ko-KR')}원`;
const today = () => todayStr();
const opt = (v, label, sel) => `<option value="${esc(v)}"${String(v) === String(sel) ? ' selected' : ''}>${esc(label)}</option>`;
const cardName = (id) => store.state.cards.find((c) => c.id === id)?.name;

function toast(msg) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.setAttribute('role', 'status');
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 2400);
}

function download(name, text, type) {
  const blob = new Blob([text], { type });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(a.href);
    a.remove();
  }, 500);
}

function openSheet(title, html) {
  $sheetTitle.textContent = title;
  $sheetBody.innerHTML = html;
  if (!$sheet.open) $sheet.showModal();
  const first = $sheetBody.querySelector('[autofocus]');
  if (first) setTimeout(() => first.focus(), 50);
}
function closeSheet() {
  if ($sheet.open) $sheet.close();
  pasteDraft = null;
}

function applyTheme() {
  const s = store.state.settings;
  const root = document.documentElement;
  if (s.theme === 'auto') delete root.dataset.theme;
  else root.dataset.theme = s.theme;
  if (s.largeText) root.dataset.large = '1';
  else delete root.dataset.large;
}

// ── 화면: 홈 ──────────────────────────────────────────────────
function txRow(t) {
  const sign = t.kind === 'income' ? '+' : t.kind === 'refund' ? '−' : '';
  const cls = t.kind === 'income' || t.kind === 'refund' ? 'in' : t.kind === 'transfer' ? 'muted' : '';
  const sub = [t.category, t.cardId ? cardName(t.cardId) : null, t.installmentMonths > 1 ? `${t.installmentMonths}개월` : null, t.time]
    .filter(Boolean)
    .join(' · ');
  const amount = t.amount == null ? `<span class="badge bad">원화 입력 필요</span>` : `${sign}${won(t.amount)}`;
  return `<button class="row" data-action="edit-tx" data-id="${esc(t.id)}">
    <div class="main"><div class="title">${esc(t.merchant || t.category || '기록')}${t.kind === 'refund' ? ' <span class="badge good">취소</span>' : ''}</div>
    <div class="desc">${esc(sub)}</div></div>
    <div class="amt ${cls}">${amount}</div></button>`;
}

function upRow(it) {
  const st = it.status;
  const badge = `<span class="badge ${st}">${STATUS_LABEL[st]}</span>`;
  const label = it.label === '예상' ? ' <span class="badge est">예상</span>' : '';
  const dd = diffDays(it.date, today());
  const when = dd === 0 ? '오늘' : dd > 0 ? `D-${dd}` : `${-dd}일 지남`;
  const action = it.kind === 'card' ? 'edit-bill' : 'edit-ob';
  return `<button class="row" data-action="${action}" data-id="${esc(it.refId)}" data-key="${esc(it.billKey || '')}">
    <div class="main"><div class="title">${esc(it.title)} ${badge}</div>
    <div class="desc">${fmtMDW(it.date)} · ${when}${it.kind === 'card' ? ` · ${fmtMD(it.period.start)}~${fmtMD(it.period.end)} 사용분` : ''}</div></div>
    <div class="amt">${won(it.amount)}${label}</div></button>`;
}

function renderHome() {
  const st = store.view();
  const t = today();
  const s = summary(st, t);
  const set = st.settings;
  const pct = s.budget ? Math.min(100, Math.round((s.spent / s.budget) * 100)) : 0;
  const hasSetup = { payday: !!set.onboarded, card: st.cards.length > 0, ob: st.obligations.length > 0, tx: store.txs.length > 0 };

  let html = '';
  if (!hasSetup.payday || !hasSetup.card || !hasSetup.ob || !hasSetup.tx) {
    html += `<div class="card"><strong>3분 시작하기</strong>
      <ol class="steps">
        <li class="${hasSetup.payday ? 'done' : ''}"><span>급여일과 한 달 생활비 정하기</span><button class="linkbtn" data-action="open-basics">설정</button></li>
        <li class="${hasSetup.card ? 'done' : ''}"><span>쓰는 카드의 결제일 등록</span><button class="linkbtn" data-action="new-card">추가</button></li>
        <li class="${hasSetup.ob ? 'done' : ''}"><span>월세·통신비·구독 같은 고정 지출 등록</span><button class="linkbtn" data-action="new-ob">추가</button></li>
        <li class="${hasSetup.tx ? 'done' : ''}"><span>카드 문자 붙여넣어 첫 기록 만들기</span><button class="linkbtn" data-action="open-paste">붙여넣기</button></li>
      </ol></div>`;
  }

  if (s.budget) {
    html += `<div class="card hero">
      <div class="label">다음 급여일(${fmtMD(s.cycle.nextPay)})까지 하루에</div>
      <div class="big">${won(s.perDay)}</div>
      <div class="progress${s.spent > s.budget ? ' over' : ''}"><i style="width:${pct}%"></i></div>
      <div class="meta">생활비 ${won(s.budget)} 중 ${won(s.spent)} 사용 · ${s.daysLeft}일 남음</div>
    </div>`;
  } else {
    html += `<div class="card hero">
      <div class="label">이번 급여 주기(${fmtMD(s.cycle.start)}~${fmtMD(s.cycle.end)}) 지출</div>
      <div class="big">${won(s.spent)}</div>
      <div class="meta">한 달 생활비를 정하면 "하루에 쓸 수 있는 돈"을 보여드려요. <button class="linkbtn" data-action="open-basics">정하기</button></div>
    </div>`;
  }

  if (s.bigNext) {
    const b = s.bigNext;
    html += `<div class="card"><div class="next">
      <div class="dday">${b.dday === 0 ? '오늘' : `D-${b.dday}`}<small>${fmtMD(b.date)}</small></div>
      <div><div class="s">다음 큰 지출</div><div class="t">${esc(b.title)} ${won(b.amount)}${b.label === '예상' ? ' <span class="badge est">예상</span>' : ''}</div>
      <div class="s">이번 급여 주기에 아직 나갈 고정 지출 ${won(s.pendingSum)}</div></div>
    </div></div>`;
  }

  if (s.attention.length) {
    html += `<div class="section-title">확인이 필요해요</div><div class="list">${s.attention.map(upRow).join('')}</div>`;
  }

  html += `<div class="section-title"><span>다음 7일 나갈 돈</span><span>${won(s.weekSum)}</span></div>`;
  html += s.week.length
    ? `<div class="list">${s.week.map(upRow).join('')}</div>`
    : `<div class="list"><div class="empty">7일 안에 예정된 지출이 없어요.</div></div>`;

  const recent = store.txs.filter((x) => !x.deleted).slice(0, 5);
  html += `<div class="section-title"><span>최근 기록</span><button class="linkbtn" data-tab="history">전체</button></div>`;
  html += recent.length ? `<div class="list">${recent.map(txRow).join('')}</div>` : `<div class="list"><div class="empty">아직 기록이 없어요. + 버튼으로 추가하세요.</div></div>`;
  html += privacyNote();
  return html;
}

function privacyNote() {
  return `<p class="note">광고 없음 · 데이터는 이 기기에만 저장 · 외부 전송 0건<br>v${APP_VERSION}</p>`;
}

// ── 화면: 내역 ────────────────────────────────────────────────
function renderHistory() {
  const [y, m] = historyMonth.split('-').map(Number);
  const start = ymd(y, m, 1);
  const end = ymd(y, m, daysInMonth(y, m));
  const txs = store.txs.filter((t) => !t.deleted && t.date >= start && t.date <= end);
  const out = txs.reduce((s, t) => s + (t.kind === 'expense' ? t.amount || 0 : t.kind === 'refund' ? -(t.amount || 0) : 0), 0);
  const inc = txs.reduce((s, t) => s + (t.kind === 'income' ? t.amount || 0 : 0), 0);
  const cats = categoryTotals(txs, start, end);
  const max = cats.length ? cats[0][1] : 1;

  let html = `<div class="monthnav"><button data-action="month" data-d="-1" aria-label="이전 달">‹</button><strong>${y}년 ${m}월</strong><button data-action="month" data-d="1" aria-label="다음 달">›</button></div>
    <div class="stats"><div><small>지출</small><b>${won(out)}</b></div><div><small>수입</small><b>${won(inc)}</b></div><div><small>합계</small><b>${won(inc - out)}</b></div></div>`;
  if (cats.length) {
    html += `<div class="section-title">분류별 지출</div><div class="card">${cats
      .map(([c, v]) => `<div class="catbar"><span>${esc(c)}</span><span class="bar"><i style="width:${Math.max(3, Math.round((v / max) * 100))}%"></i></span><span class="v">${won(v)}</span></div>`)
      .join('')}</div>`;
  }
  html += `<div class="section-title">기록</div>`;
  if (!txs.length) return html + `<div class="list"><div class="empty">이 달의 기록이 없어요.</div></div>`;
  const byDate = new Map();
  for (const t of txs) {
    if (!byDate.has(t.date)) byDate.set(t.date, []);
    byDate.get(t.date).push(t);
  }
  html += `<div class="list">`;
  for (const [d, list] of byDate) {
    const day = list.reduce((s, t) => s + (t.kind === 'expense' ? t.amount || 0 : t.kind === 'refund' ? -(t.amount || 0) : 0), 0);
    html += `<div class="datehead"><span>${fmtMDW(d)}</span><span>${day ? `지출 ${won(day)}` : ''}</span></div>${list.map(txRow).join('')}`;
  }
  return html + `</div>`;
}

// ── 화면: 곧 나갈 돈 ──────────────────────────────────────────
function renderUpcoming() {
  const st = store.view();
  const t = today();
  const items = upcoming(st, t, 60);
  const groups = new Map();
  for (const it of items) {
    if (it.status === 'matched' && it.date < t) continue;
    const key = it.date.slice(0, 7);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  let html = `<div class="callout">구독·고정비·공과금·카드 결제일을 한곳에서 미리 봅니다. 실제 결제가 기록되면 자동으로 <span class="badge matched">나감</span> 처리돼요.</div>
    <div class="btns"><button class="btn small" data-action="new-ob">+ 고정 지출·구독</button><button class="btn small" data-action="new-card">+ 카드</button><button class="btn small" data-action="export-ics">달력 알림으로 내보내기</button></div>`;
  if (!items.length) {
    html += `<div class="section-title">예정</div><div class="list"><div class="empty">아직 예정된 지출이 없어요.<br>고정 지출이나 카드를 등록해 보세요.</div></div>`;
  }
  for (const [ym, list] of groups) {
    const sum = list.filter((i) => i.status !== 'matched').reduce((s, i) => s + i.amount, 0);
    html += `<div class="section-title"><span>${Number(ym.slice(5))}월</span><span>${won(sum)}</span></div><div class="list">${list.map(upRow).join('')}</div>`;
  }
  html += `<div class="section-title">내 카드</div>`;
  html += st.cards.length
    ? `<div class="list">${st.cards
        .map((c) => {
          const tp = parts(t);
          const p = cardPeriod(c, tp.y, tp.m + (tp.d > (c.payDay || 14) ? 1 : 0));
          return `<button class="row" data-action="edit-card" data-id="${esc(c.id)}"><div class="main"><div class="title">${esc(c.name)}${c.last4 ? ` · ${esc(c.last4)}` : ''}</div>
          <div class="desc">매월 ${c.payDay}일 결제 · 다음 청구 이용기간 ${fmtMD(p.start)}~${fmtMD(p.end)}</div></div><div class="amt muted">편집</div></button>`;
        })
        .join('')}</div>`
    : `<div class="list"><div class="empty">등록된 카드가 없어요.</div></div>`;
  html += `<div class="section-title">고정 지출·구독</div>`;
  html += st.obligations.length
    ? `<div class="list">${st.obligations
        .map((o) => `<button class="row" data-action="edit-ob" data-id="${esc(o.id)}"><div class="main"><div class="title">${esc(o.name)}</div>
          <div class="desc">${esc(o.category || '')} · ${esc(cycleText(o))}</div></div><div class="amt">${won(o.amount)}</div></button>`)
        .join('')}</div>`
    : `<div class="list"><div class="empty">등록된 고정 지출이 없어요.</div></div>`;
  return html;
}

function cycleText(o) {
  if (o.cycle === 'weekly') return `매주 ${WEEKDAYS[o.weekday ?? 1]}요일`;
  if (o.cycle === 'yearly') return `매년 ${o.month}월 ${o.day}일`;
  return `매월 ${o.day}일${o.shiftHoliday ? ' (휴일이면 다음 영업일)' : ''}`;
}

// ── 화면: 설정 ────────────────────────────────────────────────
function renderSettings() {
  const s = store.state.settings;
  const notif = 'Notification' in window ? Notification.permission : 'unsupported';
  return `<div class="section-title">기본</div>
  <div class="card">
    <label class="field"><span>급여일</span><select data-setting="payday">${Array.from({ length: 31 }, (_, i) => opt(i + 1, `${i + 1}일`, s.payday)).join('')}</select>
      <div class="hint">한 달을 급여일 기준으로 봅니다. 주말·공휴일이면 앞 영업일로 계산해요.</div></label>
    <label class="field"><span>한 달 생활비(고정 지출 제외)</span><input data-setting="monthlyBudget" inputmode="numeric" value="${s.monthlyBudget ? Number(s.monthlyBudget).toLocaleString('ko-KR') : ''}" placeholder="예: 1,200,000"></label>
  </div>
  <div class="section-title">화면</div>
  <div class="card">
    <label class="field"><span>테마</span><select data-setting="theme">${opt('auto', '시스템 설정 따름', s.theme)}${opt('light', '밝게', s.theme)}${opt('dark', '어둡게', s.theme)}</select></label>
    <label class="check"><input type="checkbox" data-setting="largeText" ${s.largeText ? 'checked' : ''}> 큰 글자</label>
  </div>
  <div class="section-title">알림</div>
  <div class="card">
    <p style="font-size:.9em">앱을 열 때 D-3·당일 지출을 알려드려요. 앱이 꺼져 있어도 알림을 받으려면 <b>달력으로 내보내기</b>를 쓰세요.</p>
    <div class="btns">${
      notif === 'granted'
        ? '<span class="badge good">앱 알림 켜짐</span>'
        : notif === 'unsupported'
          ? '<span class="badge">이 브라우저는 앱 알림 미지원</span>'
          : '<button class="btn small" data-action="enable-notif">앱 알림 켜기</button>'
    }<button class="btn small" data-action="export-ics">달력으로 내보내기 (.ics)</button></div>
  </div>
  <div class="section-title">내 데이터</div>
  <div class="card">
    <p style="font-size:.9em">모든 기록은 이 기기에만 저장돼요. 폰을 바꾸거나 브라우저 데이터를 지우기 전에 백업하세요.</p>
    <div class="btns">
      <button class="btn small" data-action="export-csv">CSV 내보내기</button>
      <button class="btn small" data-action="export-backup">백업 파일 저장</button>
      <label class="btn small">백업에서 복원<input type="file" accept="application/json,.json" data-action="restore" class="sr-only"></label>
    </div>
    <div class="btns"><button class="btn small danger" data-action="reset">모든 데이터 지우기</button></div>
  </div>
  <div class="section-title">정보</div>
  <div class="card" style="font-size:.9em">
    <p>자동 장부 v${APP_VERSION} · 기록 ${store.txs.filter((t) => !t.deleted).length}건 · 학습된 분류 규칙 ${Object.keys(store.state.rules).length}개</p>
    <p style="margin-top:6px">광고·분석 도구가 없고, 입력한 데이터를 외부로 보내지 않습니다. 붙여넣은 문자 원문은 저장하지 않고 읽어낸 결과만 남겨요.</p>
    <p style="margin-top:6px"><a href="privacy.html">개인정보처리방침</a></p>
  </div>`;
}

// ── 시트: 직접 입력 / 수정 ─────────────────────────────────────
function txForm(t = {}) {
  const kind = t.kind || 'expense';
  const cats = kind === 'income' ? INCOME_CATEGORIES : kind === 'transfer' ? ['이체'] : CATEGORIES;
  const cards = store.state.cards;
  return `<form data-form="tx" data-id="${esc(t.id || '')}">
    <div class="seg" role="group" aria-label="구분">
      ${[['expense', '지출'], ['income', '수입'], ['transfer', '이체'], ['refund', '환불']]
        .map(([k, l]) => `<button type="button" data-action="tx-kind" data-kind="${k}" aria-pressed="${k === kind}">${l}</button>`)
        .join('')}
    </div>
    <input type="hidden" name="kind" value="${esc(kind)}">
    <label class="field"><span>금액</span><input class="amount-input" name="amount" inputmode="numeric" autocomplete="off" required value="${t.amount != null ? Number(t.amount).toLocaleString('ko-KR') : ''}" placeholder="0" ${t.id ? '' : 'autofocus'}>
      ${t.fx ? `<div class="hint">해외결제 ${esc(t.fx.currency)} ${esc(t.fx.amount)} · 청구서의 원화 금액을 넣어 주세요.</div>` : ''}</label>
    <div class="grid2">
      <label class="field"><span>날짜</span><input type="date" name="date" required value="${esc(t.date || today())}"></label>
      <label class="field"><span>분류</span><select name="category">${cats.map((c) => opt(c, c, t.category || cats[0])).join('')}</select></label>
    </div>
    <label class="field"><span>내용</span><input name="merchant" value="${esc(t.merchant || '')}" placeholder="가맹점이나 메모" autocomplete="off"></label>
    <div class="grid2">
      <label class="field"><span>결제수단</span><select name="cardId">${opt('', '현금·계좌', t.cardId || '')}${cards.map((c) => opt(c.id, c.name, t.cardId)).join('')}</select></label>
      <label class="field"><span>할부</span><select name="installmentMonths">${[1, 2, 3, 4, 5, 6, 10, 12, 24].map((n) => opt(n, n === 1 ? '일시불' : `${n}개월`, t.installmentMonths || 1)).join('')}</select></label>
    </div>
    <label class="field"><span>메모</span><input name="memo" value="${esc(t.memo || '')}" autocomplete="off"></label>
    <button class="btn primary block" type="submit">${t.id ? '저장' : '기록하기'}</button>
    ${t.id ? `<div class="btns"><button type="button" class="btn danger block" data-action="delete-tx" data-id="${esc(t.id)}">이 기록 삭제</button></div>` : ''}
  </form>`;
}

function openAdd(mode = 'manual', text = '') {
  const tabs = `<div class="seg" role="group" aria-label="입력 방식"><button type="button" data-action="add-mode" data-mode="manual" aria-pressed="${mode === 'manual'}">직접 입력</button><button type="button" data-action="add-mode" data-mode="paste" aria-pressed="${mode === 'paste'}">문자 붙여넣기</button></div>`;
  if (mode === 'manual') openSheet('기록 추가', tabs + txForm());
  else openSheet('기록 추가', tabs + pasteForm(text));
}

// ── 시트: 문자 붙여넣기 ────────────────────────────────────────
function pasteForm(text = '') {
  return `<form data-form="paste">
    <label class="field"><span>카드·은행 문자나 알림을 붙여넣으세요 (여러 건 가능)</span>
    <textarea name="text" placeholder="[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:20 스타벅스 누적1,234,567원" ${text ? '' : 'autofocus'}>${esc(text)}</textarea>
    <div class="hint">문자 앱에서 메시지를 길게 눌러 복사하거나, Android에서는 공유하기 → 자동 장부를 고르세요. 원문은 저장하지 않아요.</div></label>
    <div class="btns"><button type="button" class="btn" data-action="paste-clipboard">클립보드에서 가져오기</button><button class="btn primary" type="submit">읽기</button></div>
  </form><div id="paste-preview"></div>`;
}

function matchCard(r) {
  const cards = store.state.cards;
  if (r.last4) {
    const c = cards.find((x) => x.last4 === r.last4);
    if (c) return c.id;
  }
  if (r.issuer) {
    const same = cards.filter((x) => x.issuer === r.issuer);
    if (same.length === 1) return same[0].id;
  }
  return '';
}

function buildDraft(text) {
  const rules = store.state.rules;
  const parsed = parseMany(text, { rules });
  const existing = store.txs.filter((t) => !t.deleted);
  const pending = [];
  return parsed.map(({ chunk, result: r }, i) => {
    if (!r.ok) return { i, ok: false, reason: r.reason, preview: chunk.slice(0, 60) };
    const tx = {
      kind: r.kind,
      amount: r.amount,
      fx: r.fx,
      date: r.date || today(),
      time: r.time,
      merchant: r.merchant,
      category: r.category,
      cardId: r.channel === 'card' ? matchCard(r) : '',
      installmentMonths: r.installmentMonths,
      last4: r.last4,
      issuer: r.issuer,
      source: 'paste',
      parserVersion: r.parserVersion,
    };
    const dup = findDuplicate(tx, existing.concat(pending));
    let link = null;
    if (tx.kind === 'refund') link = findCancelTarget(tx, existing.concat(pending));
    if (link) tx.linkedTo = link.id;
    pending.push({ ...tx, id: `draft-${i}` });
    return { i, ok: true, tx, dup: !!dup, link: !!link, include: !dup, confidence: r.confidence, noDate: !r.date };
  });
}

function renderPreview() {
  const box = document.getElementById('paste-preview');
  if (!box || !pasteDraft) return;
  const cards = store.state.cards;
  const okCount = pasteDraft.filter((d) => d.ok && d.include).length;
  box.innerHTML =
    `<div class="section-title"><span>읽은 결과 ${pasteDraft.filter((d) => d.ok).length}건</span><span>${pasteDraft.filter((d) => !d.ok).length ? `못 읽음 ${pasteDraft.filter((d) => !d.ok).length}건` : ''}</span></div>` +
    pasteDraft
      .map((d) => {
        if (!d.ok) return `<div class="pitem off"><div class="ph"><span class="badge bad">못 읽음</span><span class="fail">${esc(d.reason)}</span></div><div class="fail">${esc(d.preview.replace(/\d/g, '•'))}…</div></div>`;
        const t = d.tx;
        const badges = [
          d.dup ? '<span class="badge bad">중복 의심</span>' : '',
          d.link ? '<span class="badge good">원거래에 취소 연결</span>' : '',
          t.kind === 'refund' && !d.link ? '<span class="badge good">취소·환불</span>' : '',
          t.kind === 'income' ? '<span class="badge good">입금</span>' : '',
          t.kind === 'transfer' ? '<span class="badge">출금·이체</span>' : '',
          t.amount == null ? '<span class="badge bad">원화 금액 입력</span>' : '',
          t.installmentMonths > 1 ? `<span class="badge">${t.installmentMonths}개월 할부</span>` : '',
          d.noDate ? '<span class="badge bad">날짜 확인</span>' : '',
        ].join('');
        const cats = t.kind === 'income' ? INCOME_CATEGORIES : t.kind === 'transfer' ? ['이체'] : CATEGORIES;
        return `<div class="pitem${d.include ? '' : ' off'}" data-i="${d.i}">
          <div class="ph"><label class="check" style="margin:0"><input type="checkbox" data-p="include" ${d.include ? 'checked' : ''}> 저장</label>${badges}</div>
          <div class="grid2">
            <label class="field"><span>금액(원)</span><input data-p="amount" inputmode="numeric" value="${t.amount != null ? Number(t.amount).toLocaleString('ko-KR') : ''}" placeholder="${t.fx ? esc(`${t.fx.currency} ${t.fx.amount}`) : ''}"></label>
            <label class="field"><span>날짜</span><input type="date" data-p="date" value="${esc(t.date)}"></label>
            <label class="field"><span>내용</span><input data-p="merchant" value="${esc(t.merchant)}"></label>
            <label class="field"><span>분류</span><select data-p="category">${cats.map((c) => opt(c, c, t.category)).join('')}${cats.includes(t.category) ? '' : opt(t.category, t.category, t.category)}</select></label>
          </div>
          ${t.kind === 'expense' || t.kind === 'refund' ? `<label class="field"><span>카드</span><select data-p="cardId">${opt('', t.issuer ? `${t.issuer}${t.last4 ? ` ${t.last4}` : ''} (미등록)` : '현금·계좌', t.cardId)}${cards.map((c) => opt(c.id, c.name, t.cardId)).join('')}</select></label>` : ''}
        </div>`;
      })
      .join('') +
    `<button class="btn primary block" data-action="paste-save" ${okCount ? '' : 'disabled'}>${okCount}건 저장</button>
     <p class="note">못 읽은 문자가 있으면 숫자를 가린 형식을 알려주세요. 다음 버전에서 바로 읽도록 고칩니다.</p>`;
}

// ── 시트: 고정 지출 / 카드 / 카드 청구 확정 / 기본 설정 ─────────
function obForm(o = {}) {
  const cycle = o.cycle || 'monthly';
  return `<form data-form="ob" data-id="${esc(o.id || '')}">
    <label class="field"><span>이름</span><input name="name" required value="${esc(o.name || '')}" placeholder="예: 넷플릭스, 월세, 관리비" autofocus></label>
    <div class="grid2">
      <label class="field"><span>금액</span><input name="amount" inputmode="numeric" required value="${o.amount ? Number(o.amount).toLocaleString('ko-KR') : ''}"></label>
      <label class="field"><span>종류</span><select name="category">${OB_KINDS.map((k) => opt(k, k, o.category || '구독')).join('')}</select></label>
    </div>
    <div class="grid2">
      <label class="field"><span>주기</span><select name="cycle">${opt('monthly', '매월', cycle)}${opt('weekly', '매주', cycle)}${opt('yearly', '매년', cycle)}</select></label>
      <label class="field"><span>금액 유형</span><select name="amountType">${opt('fixed', '매번 같음(확정)', o.amountType || 'fixed')}${opt('variable', '달마다 다름(예상)', o.amountType)}</select></label>
    </div>
    <div class="grid2">
      <label class="field"><span>날짜(일)</span><select name="day">${Array.from({ length: 31 }, (_, i) => opt(i + 1, `${i + 1}일`, o.day || 1)).join('')}</select></label>
      <label class="field"><span>요일(매주) / 월(매년)</span><select name="extra">${
        cycle === 'weekly'
          ? WEEKDAYS.map((w, i) => opt(i, `${w}요일`, o.weekday ?? 1)).join('')
          : Array.from({ length: 12 }, (_, i) => opt(i + 1, `${i + 1}월`, o.month || 1)).join('')
      }</select></label>
    </div>
    <label class="check"><input type="checkbox" name="shiftHoliday" ${o.shiftHoliday ?? !o.id ? 'checked' : ''}> 주말·공휴일이면 다음 영업일로 (자동이체)</label>
    <label class="field"><span>결제 문자에 찍히는 이름 (선택)</span><input name="keyword" value="${esc(o.keyword || '')}" placeholder="예: NETFLIX"><div class="hint">적어 두면 실제 결제와 더 정확히 맞춰요. 비워 두면 금액·날짜로 맞춥니다.</div></label>
    <button class="btn primary block" type="submit">저장</button>
    ${o.id ? `<div class="btns"><button type="button" class="btn danger block" data-action="delete-ob" data-id="${esc(o.id)}">삭제</button></div>` : ''}
  </form>`;
}

function cardForm(c = {}) {
  return `<form data-form="card" data-id="${esc(c.id || '')}">
    <label class="field"><span>카드 이름</span><input name="name" required value="${esc(c.name || '')}" placeholder="예: 신한 생활비카드" autofocus></label>
    <div class="grid2">
      <label class="field"><span>카드사</span><select name="issuer">${['', 'KB국민', '신한', '현대', '삼성', '롯데', '하나', '우리', 'BC', 'NH농협', 'IBK기업', '씨티', '카카오뱅크', '토스뱅크', '기타']
        .map((k) => opt(k, k || '선택', c.issuer || ''))
        .join('')}</select></label>
      <label class="field"><span>카드 끝 4자리</span><input name="last4" inputmode="numeric" maxlength="4" pattern="\\d{4}" value="${esc(c.last4 || '')}" placeholder="문자 자동 연결용"></label>
    </div>
    <div class="grid2">
      <label class="field"><span>결제일</span><select name="payDay">${Array.from({ length: 31 }, (_, i) => opt(i + 1, `${i + 1}일`, c.payDay || 14)).join('')}</select></label>
      <label class="field"><span>이용기간 시작일</span><select name="periodStartDay">${Array.from({ length: 31 }, (_, i) => opt(i + 1, `${i + 1}일부터`, c.periodStartDay || 1)).join('')}</select></label>
    </div>
    <label class="field"><span>이용기간 시작 월</span><select name="periodOffsetMonths">${opt(1, '결제일 전월', c.periodOffsetMonths || 1)}${opt(2, '결제일 전전월', c.periodOffsetMonths)}</select>
      <div class="hint">카드사 앱의 "결제일별 이용기간"을 보고 맞춰 주세요. 예: 결제일 14일 → 전월 1일~전월 말일, 결제일 25일 → 전월 12일~당월 11일(시작 월은 '전월').</div></label>
    <button class="btn primary block" type="submit">저장</button>
    ${c.id ? `<div class="btns"><button type="button" class="btn danger block" data-action="delete-card" data-id="${esc(c.id)}">카드 삭제</button></div>` : ''}
  </form>`;
}

function billForm(card, key, amount) {
  const confirmed = card.confirmed?.[key];
  return `<form data-form="bill" data-id="${esc(card.id)}" data-key="${esc(key)}">
    <p style="font-size:.9em;margin-bottom:12px">${esc(card.name)}의 ${Number(key.slice(5))}월 결제 예상액은 기록된 승인 내역으로 계산한 값이에요. 청구서가 나오면 확정 금액을 넣어 주세요.</p>
    <label class="field"><span>확정 금액</span><input name="amount" inputmode="numeric" value="${confirmed != null ? Number(confirmed).toLocaleString('ko-KR') : ''}" placeholder="예상 ${won(amount)}" autofocus></label>
    <button class="btn primary block" type="submit">확정</button>
    ${confirmed != null ? `<div class="btns"><button type="button" class="btn block" data-action="unconfirm-bill" data-id="${esc(card.id)}" data-key="${esc(key)}">확정 취소(예상으로)</button></div>` : ''}
  </form>`;
}

function basicsForm() {
  const s = store.state.settings;
  return `<form data-form="basics">
    <label class="field"><span>급여일</span><select name="payday">${Array.from({ length: 31 }, (_, i) => opt(i + 1, `${i + 1}일`, s.payday)).join('')}</select></label>
    <label class="field"><span>한 달 생활비(월세·구독 같은 고정 지출 제외)</span><input name="monthlyBudget" inputmode="numeric" value="${s.monthlyBudget ? Number(s.monthlyBudget).toLocaleString('ko-KR') : ''}" placeholder="예: 1,200,000"><div class="hint">비워 두면 지출 합계만 보여드려요.</div></label>
    <button class="btn primary block" type="submit">저장</button>
  </form>`;
}

const toNum = (v) => {
  const n = Number(String(v ?? '').replace(/[^\d.]/g, ''));
  return Number.isFinite(n) && String(v ?? '').trim() !== '' ? n : null;
};

// ── 렌더 ─────────────────────────────────────────────────────
const TITLES = { home: '자동 장부', history: '내역', upcoming: '곧 나갈 돈', settings: '설정' };
function render() {
  document.getElementById('title').textContent = TITLES[tab];
  document.getElementById('subtitle').textContent = tab === 'home' ? `${fmtMDW(today())} · 광고 없는 가계부` : '';
  document.querySelectorAll('.tabbar button').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === tab ? 'page' : 'false'));
  document.querySelector('.fab').hidden = tab === 'settings';
  $view.innerHTML = tab === 'home' ? renderHome() : tab === 'history' ? renderHistory() : tab === 'upcoming' ? renderUpcoming() : renderSettings();
}
store.subscribe(render);

// ── 이벤트 ───────────────────────────────────────────────────
document.addEventListener('click', async (e) => {
  const tabBtn = e.target.closest('[data-tab]');
  if (tabBtn) {
    tab = tabBtn.dataset.tab;
    render();
    window.scrollTo(0, 0);
    return;
  }
  const el = e.target.closest('[data-action]');
  if (!el) return;
  const a = el.dataset.action;
  const id = el.dataset.id;
  const st = store.state;

  if (a === 'close-sheet') closeSheet();
  else if (a === 'open-add') openAdd('manual');
  else if (a === 'open-paste') openAdd('paste');
  else if (a === 'add-mode') openAdd(el.dataset.mode);
  else if (a === 'open-basics') openSheet('급여일과 생활비', basicsForm());
  else if (a === 'tx-kind') {
    const form = el.closest('form');
    const data = Object.fromEntries(new FormData(form));
    const t = { ...data, id: form.dataset.id || undefined, kind: el.dataset.kind, amount: toNum(data.amount), category: undefined };
    const cur = form.dataset.id ? store.txs.find((x) => x.id === form.dataset.id) : null;
    $sheetBody.querySelector('form[data-form="tx"]').outerHTML = txForm({ ...cur, ...t });
  } else if (a === 'edit-tx') {
    const t = store.txs.find((x) => x.id === id);
    if (t) openSheet('기록 수정', txForm(t));
  } else if (a === 'delete-tx') {
    if (confirm('이 기록을 삭제할까요?')) {
      store.deleteTx(id);
      closeSheet();
      toast('삭제했어요');
    }
  } else if (a === 'new-ob') openSheet('고정 지출·구독 추가', obForm());
  else if (a === 'edit-ob') {
    const o = st.obligations.find((x) => x.id === id);
    if (o) openSheet('고정 지출 수정', obForm(o));
  } else if (a === 'delete-ob') {
    if (confirm('이 고정 지출을 삭제할까요?')) {
      store.remove('obligations', id);
      closeSheet();
    }
  } else if (a === 'new-card') openSheet('카드 추가', cardForm());
  else if (a === 'edit-card') {
    const c = st.cards.find((x) => x.id === id);
    if (c) openSheet('카드 수정', cardForm(c));
  } else if (a === 'delete-card') {
    if (confirm('카드를 삭제할까요? 기록은 남아요.')) {
      store.remove('cards', id);
      closeSheet();
    }
  } else if (a === 'edit-bill') {
    const c = st.cards.find((x) => x.id === id);
    const it = upcoming(store.view(), today(), 60).find((i) => i.kind === 'card' && i.refId === id && i.billKey === el.dataset.key);
    if (c) openSheet('카드 결제 금액', billForm(c, el.dataset.key, it ? it.amount : 0));
  } else if (a === 'unconfirm-bill') {
    const c = st.cards.find((x) => x.id === id);
    const confirmed = { ...(c.confirmed || {}) };
    delete confirmed[el.dataset.key];
    store.upsert('cards', { id, confirmed });
    closeSheet();
  } else if (a === 'paste-clipboard') {
    try {
      const text = await navigator.clipboard.readText();
      $sheetBody.querySelector('textarea[name="text"]').value = text;
    } catch {
      toast('클립보드 권한이 없어요. 입력칸을 길게 눌러 붙여넣어 주세요.');
    }
  } else if (a === 'paste-save') {
    const list = pasteDraft.filter((d) => d.ok && d.include).map((d) => d.tx);
    const missing = list.filter((t) => t.amount == null);
    if (missing.length) return toast('원화 금액을 넣지 않은 해외결제가 있어요.');
    store.addTxs(list.map(({ issuer, ...t }) => t));
    for (const t of list) if (t.merchant && t.category && guessCategory(t.merchant) !== t.category) store.setRule(t.merchant, t.category);
    closeSheet();
    toast(`${list.length}건 기록했어요`);
  } else if (a === 'month') {
    const [y, m] = historyMonth.split('-').map(Number);
    historyMonth = ymd(y, m + Number(el.dataset.d), 1).slice(0, 7);
    render();
  } else if (a === 'export-csv') {
    download(`장부_${today()}.csv`, toCSV(store.txs, st.cards), 'text/csv;charset=utf-8');
  } else if (a === 'export-backup') {
    download(`장부_백업_${today()}.json`, backupJSON(st), 'application/json');
    toast('백업 파일을 저장했어요');
  } else if (a === 'export-ics') {
    const items = upcoming(store.view(), today(), 90);
    if (!items.length) return toast('내보낼 예정 지출이 없어요.');
    download('곧_나갈_돈.ics', toICS(items), 'text/calendar;charset=utf-8');
    toast('파일을 열어 달력에 추가하면 D-3과 당일에 알림이 와요');
  } else if (a === 'enable-notif') {
    const p = await Notification.requestPermission();
    if (p === 'granted') checkReminders(true);
    render();
  } else if (a === 'reset') {
    if (confirm('모든 기록·카드·고정 지출을 지울까요? 되돌릴 수 없어요. 먼저 백업을 권해요.') && confirm('정말 지울까요?')) {
      store.reset();
      applyTheme();
      toast('모두 지웠어요');
    }
  } else if (a === 'confirm-restore') {
    store.replaceAll(window.__restore);
    window.__restore = null;
    applyTheme();
    closeSheet();
    toast('복원했어요');
  }
});

document.addEventListener('submit', (e) => {
  const form = e.target.closest('form[data-form]');
  if (!form) return;
  e.preventDefault();
  const kind = form.dataset.form;
  const data = Object.fromEntries(new FormData(form));
  const id = form.dataset.id;

  if (kind === 'tx') {
    const amount = toNum(data.amount);
    if (amount == null) return toast('금액을 넣어 주세요');
    const tx = {
      kind: data.kind,
      amount,
      date: data.date,
      category: data.category,
      merchant: data.merchant.trim(),
      cardId: data.cardId,
      installmentMonths: Number(data.installmentMonths) || 1,
      memo: data.memo.trim(),
    };
    if (id) {
      const prev = store.txs.find((t) => t.id === id);
      store.updateTx(id, tx);
      // 분류를 고치면 같은 가맹점에 계속 적용한다 (학습형 분류).
      if (tx.merchant && prev && prev.category !== tx.category && (tx.kind === 'expense' || tx.kind === 'refund')) store.setRule(tx.merchant, tx.category);
      toast('저장했어요');
    } else {
      store.addTx({ ...tx, source: 'manual' });
      toast('기록했어요');
    }
    closeSheet();
  } else if (kind === 'paste') {
    if (!data.text.trim()) return toast('붙여넣을 내용이 없어요');
    pasteDraft = buildDraft(data.text);
    renderPreview();
    document.getElementById('paste-preview')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } else if (kind === 'ob') {
    const amount = toNum(data.amount);
    if (amount == null) return toast('금액을 넣어 주세요');
    const ob = {
      name: data.name.trim(),
      amount,
      category: data.category,
      cycle: data.cycle,
      amountType: data.amountType,
      day: Number(data.day),
      shiftHoliday: !!data.shiftHoliday,
      keyword: data.keyword.trim(),
      active: true,
    };
    if (data.cycle === 'weekly') ob.weekday = Number(data.extra);
    if (data.cycle === 'yearly') ob.month = Number(data.extra);
    store.upsert('obligations', id ? { id, ...ob } : ob);
    closeSheet();
    toast('저장했어요');
  } else if (kind === 'card') {
    const c = {
      name: data.name.trim(),
      issuer: data.issuer,
      last4: data.last4.trim(),
      payDay: Number(data.payDay),
      periodStartDay: Number(data.periodStartDay),
      periodOffsetMonths: Number(data.periodOffsetMonths),
    };
    store.upsert('cards', id ? { id, ...c } : c);
    closeSheet();
    toast('저장했어요');
  } else if (kind === 'bill') {
    const c = store.state.cards.find((x) => x.id === id);
    const amount = toNum(data.amount);
    if (amount == null) return toast('금액을 넣어 주세요');
    store.upsert('cards', { id, confirmed: { ...(c.confirmed || {}), [form.dataset.key]: amount } });
    closeSheet();
  } else if (kind === 'basics') {
    store.setSettings({ payday: Number(data.payday), monthlyBudget: toNum(data.monthlyBudget) || 0, onboarded: true });
    closeSheet();
  }
});

document.addEventListener('change', async (e) => {
  const el = e.target;
  // 붙여넣기 미리보기 편집
  const item = el.closest('.pitem[data-i]');
  if (item && pasteDraft) {
    const d = pasteDraft.find((x) => String(x.i) === item.dataset.i);
    const p = el.dataset.p;
    if (p === 'include') d.include = el.checked;
    else if (p === 'amount') d.tx.amount = toNum(el.value);
    else d.tx[p] = el.value;
    renderPreview();
    return;
  }
  if (el.matches('form[data-form="ob"] select[name="cycle"]')) {
    const form = el.closest('form');
    const data = Object.fromEntries(new FormData(form));
    const cur = store.state.obligations.find((o) => o.id === form.dataset.id) || {};
    form.outerHTML = obForm({ ...cur, ...data, amount: toNum(data.amount), id: form.dataset.id || undefined, shiftHoliday: !!data.shiftHoliday });
    return;
  }
  if (el.dataset.setting) {
    const k = el.dataset.setting;
    const v = el.type === 'checkbox' ? el.checked : k === 'payday' ? Number(el.value) : k === 'monthlyBudget' ? toNum(el.value) || 0 : el.value;
    store.setSettings({ [k]: v, ...(k === 'payday' || k === 'monthlyBudget' ? { onboarded: true } : {}) });
    applyTheme();
    return;
  }
  if (el.dataset.action === 'restore' && el.files?.[0]) {
    try {
      const next = readBackup(await el.files[0].text());
      const count = next.log.filter((x) => x.op === 'add').length;
      window.__restore = next;
      openSheet(
        '백업 복원',
        `<p>이 백업에는 기록 ${count}건, 카드 ${next.cards?.length || 0}개, 고정 지출 ${next.obligations?.length || 0}개가 있어요.</p>
        <p class="callout warn" style="margin-top:10px">지금 기기의 데이터는 이 백업으로 바뀝니다. 현재 데이터가 필요하면 먼저 백업하세요.</p>
        <div class="btns"><button class="btn primary" data-action="confirm-restore">복원하기</button><button class="btn" data-action="close-sheet">취소</button></div>`,
      );
    } catch (err) {
      toast(err.message || '파일을 읽지 못했어요');
    }
    el.value = '';
  }
});

// 금액 입력칸에 천 단위 쉼표를 넣는다.
document.addEventListener('input', (e) => {
  const el = e.target;
  if (el.matches('input[inputmode="numeric"]:not([name="last4"])')) {
    const digits = el.value.replace(/[^\d]/g, '');
    el.value = digits ? Number(digits).toLocaleString('ko-KR') : '';
  }
});

$sheet.addEventListener('close', () => {
  pasteDraft = null;
});
$sheet.addEventListener('click', (e) => {
  if (e.target === $sheet) closeSheet(); // 바깥(배경) 누르면 닫기
});

// ── 앱 알림: 앱을 열 때 D-3·당일 항목을 하루 한 번 알린다 ─────────
async function checkReminders(force = false) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const t = today();
  const items = upcoming(store.view(), t, 3).filter((i) => i.status === 'soon' || i.status === 'today');
  const reg = 'serviceWorker' in navigator ? await navigator.serviceWorker.getRegistration() : null;
  for (const it of items) {
    const key = `${it.id}:${it.status}`;
    if (!force && store.state.notified[key]) continue;
    const dd = diffDays(it.date, t);
    const body = `${it.title} ${won(it.amount)}${it.label === '예상' ? '(예상)' : ''} · ${dd === 0 ? '오늘' : `${dd}일 뒤`}`;
    try {
      if (reg) await reg.showNotification('곧 나갈 돈', { body, tag: key, icon: 'icons/icon-192.png' });
      else new Notification('곧 나갈 돈', { body, tag: key });
      store.markNotified(key, t);
    } catch {
      /* 알림 실패는 조용히 넘긴다 */
    }
  }
}

// ── 시작 ─────────────────────────────────────────────────────
applyTheme();
render();

// Android 공유하기(Web Share Target)로 들어온 문자를 바로 읽는다.
const params = new URLSearchParams(location.search);
const shared = [params.get('title'), params.get('text'), params.get('url')].filter(Boolean).join('\n');
if (shared) {
  history.replaceState(null, '', location.pathname);
  openAdd('paste', shared);
  const form = $sheetBody.querySelector('form[data-form="paste"]');
  pasteDraft = buildDraft(shared);
  renderPreview();
  form?.querySelector('textarea')?.blur();
} else if (params.get('add') === 'paste') {
  history.replaceState(null, '', location.pathname);
  openAdd('paste');
}

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
checkReminders();
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    render();
    checkReminders();
  }
});
