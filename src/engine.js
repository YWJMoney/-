// 곧 나갈 돈 엔진 + 급여 주기 요약. 화면과 분리된 순수 함수만 둔다.
import {
  ymd, parts, addDays, addMonths, clampDay, diffDays, weekday, nextBusinessDay, prevBusinessDay,
} from './dates.js';

// ── 카드 결제 주기 ─────────────────────────────────────────────
// card = { id, name, payDay, periodStartDay, periodOffsetMonths }
// 결제월 M의 이용기간 = (M - offset)월 startDay일 ~ 그다음 달 startDay-1일.
// 예) 결제일 14일, 시작일 1일, offset 1 → 전월 1일 ~ 전월 말일.
export function cardPeriod(card, payYear, payMonth) {
  const offset = card.periodOffsetMonths ?? 1;
  const startDay = card.periodStartDay ?? 1;
  const start = clampDay(payYear, payMonth - offset, startDay);
  const nextStart = clampDay(payYear, payMonth - offset + 1, startDay);
  return { start, end: addDays(nextStart, -1) };
}

export function cardPayDate(card, y, m) {
  return nextBusinessDay(clampDay(y, m, card.payDay || 14));
}

// 할부는 월 납입분을 각 달 청구에 나눠 넣는다(이자·수수료는 문자에 없으므로 제외).
export function cardBillAmount(card, period, txs) {
  let sum = 0;
  for (const t of txs) {
    if (t.deleted || t.cardId !== card.id || !t.date || t.amount == null) continue;
    const sign = t.kind === 'expense' ? 1 : t.kind === 'refund' ? -1 : 0;
    if (!sign) continue;
    const n = Math.max(1, t.installmentMonths || 1);
    for (let i = 0; i < n; i++) {
      const d = addMonths(t.date, i);
      if (d >= period.start && d <= period.end) sum += sign * Math.round(t.amount / n);
    }
  }
  return Math.max(0, sum);
}

export function cardBills(card, txs, today, count = 2) {
  const out = [];
  const { y, m } = parts(today);
  for (let i = -1; out.length < count && i < 14; i++) {
    const pm = parts(ymd(y, m + i, 1));
    const payDate = cardPayDate(card, pm.y, pm.m);
    if (payDate < today) continue;
    const period = cardPeriod(card, pm.y, pm.m);
    const key = `${pm.y}-${String(pm.m).padStart(2, '0')}`;
    const confirmed = card.confirmed?.[key];
    out.push({
      kind: 'card',
      id: `card-${card.id}-${key}`,
      refId: card.id,
      billKey: key,
      title: `${card.name} 결제`,
      date: payDate,
      amount: confirmed ?? cardBillAmount(card, period, txs),
      label: confirmed != null ? '확정' : '예상',
      period,
      ongoing: today <= period.end,
    });
  }
  return out;
}

// ── 고정·구독·공과금 규칙 ──────────────────────────────────────
// ob = { id, name, amount, cycle: 'monthly'|'weekly'|'yearly', day, weekday, month, shiftHoliday, keyword, active }
export function occurrences(ob, from, to) {
  const out = [];
  if (ob.active === false) return out;
  const push = (d) => {
    const due = ob.shiftHoliday ? nextBusinessDay(d) : d;
    if (due >= from && due <= to) out.push({ nominal: d, date: due });
  };
  const f = parts(from);
  if (ob.cycle === 'weekly') {
    let d = from;
    while (weekday(d) !== (ob.weekday ?? 1)) d = addDays(d, 1);
    for (; d <= to; d = addDays(d, 7)) push(d);
  } else if (ob.cycle === 'yearly') {
    for (let y = f.y - 1; y <= parts(to).y + 1; y++) push(clampDay(y, ob.month || 1, ob.day || 1));
  } else {
    for (let i = -1; i < 26; i++) {
      const pm = parts(ymd(f.y, f.m + i, 1));
      const d = clampDay(pm.y, pm.m, ob.day || 1);
      if (d > addDays(to, 7)) break;
      push(d);
    }
  }
  return out.sort((a, b) => (a.date < b.date ? -1 : 1));
}

// 예정 ↔ 실제 거래 매칭: 날짜 ±3일, 금액 ±10%(최소 1,000원), 키워드가 있으면 가맹점 포함.
export function matchOccurrence(ob, date, txs, used = new Set()) {
  const tol = Math.max(1000, Math.round((ob.amount || 0) * 0.1));
  return (
    txs.find(
      (t) =>
        !t.deleted &&
        !used.has(t.id) &&
        (t.kind === 'expense' || t.kind === 'transfer') &&
        t.date &&
        Math.abs(diffDays(t.date, date)) <= 3 &&
        t.amount != null &&
        Math.abs(t.amount - (ob.amount || 0)) <= tol &&
        (!ob.keyword || (t.merchant || '').includes(ob.keyword)),
    ) || null
  );
}

function statusOf(date, today, matched) {
  if (matched) return 'matched';
  const dd = diffDays(date, today);
  if (dd < -3) return 'missed';
  if (dd < 0) return 'late';
  if (dd === 0) return 'today';
  if (dd <= 3) return 'soon';
  return 'planned';
}

export const STATUS_LABEL = {
  matched: '나감',
  missed: '놓침?',
  late: '확인 필요',
  today: '오늘',
  soon: '임박',
  planned: '예정',
};

// 다가오는 유출 전체 목록(최근 7일 미확인 건 포함).
export function upcoming(state, today, horizonDays = 45) {
  const txs = state.txs || [];
  const from = addDays(today, -7);
  const to = addDays(today, horizonDays);
  const used = new Set();
  const items = [];
  for (const ob of state.obligations || []) {
    for (const occ of occurrences(ob, from, to)) {
      const m = matchOccurrence(ob, occ.date, txs, used);
      if (m) used.add(m.id);
      const status = statusOf(occ.date, today, m);
      if (occ.date < today && status === 'matched') continue;
      items.push({
        kind: 'obligation',
        id: `ob-${ob.id}-${occ.nominal}`,
        refId: ob.id,
        title: ob.name,
        category: ob.category,
        date: occ.date,
        amount: ob.amount || 0,
        label: ob.amountType === 'variable' ? '예상' : '확정',
        status,
        matchedTxId: m ? m.id : null,
      });
    }
  }
  for (const card of state.cards || []) {
    for (const bill of cardBills(card, txs, today)) {
      if (bill.date > to) continue;
      items.push({ ...bill, status: statusOf(bill.date, today, false) });
    }
  }
  return items.sort((a, b) => (a.date === b.date ? b.amount - a.amount : a.date < b.date ? -1 : 1));
}

// ── 급여일 기준 주기와 "한 숫자 + 한 날짜" ─────────────────────
export function payCycle(payday, today) {
  const { y, m } = parts(today);
  const pd = (yy, mm) => prevBusinessDay(clampDay(yy, mm, payday || 25));
  let start = pd(y, m);
  if (start > today) {
    const p = parts(ymd(y, m - 1, 1));
    start = pd(p.y, p.m);
  }
  const s = parts(start);
  const n = parts(ymd(s.y, s.m + 1, 1));
  let nextPay = pd(n.y, n.m);
  if (nextPay <= start) {
    const nn = parts(ymd(n.y, n.m + 1, 1));
    nextPay = pd(nn.y, nn.m);
  }
  return { start, end: addDays(nextPay, -1), nextPay };
}

export function summary(state, today) {
  const settings = state.settings || {};
  const cycle = payCycle(settings.payday, today);
  const txs = (state.txs || []).filter((t) => !t.deleted && t.date >= cycle.start && t.date <= cycle.end && t.amount != null);
  const spent = txs.reduce((s, t) => s + (t.kind === 'expense' ? t.amount : t.kind === 'refund' ? -t.amount : 0), 0);
  const income = txs.reduce((s, t) => s + (t.kind === 'income' ? t.amount : 0), 0);
  const daysLeft = diffDays(cycle.end, today) + 1;
  const items = upcoming(state, today);
  // 이번 주기 안에 아직 안 나간 고정 유출(카드 결제 포함)
  const pending = items.filter((i) => i.status !== 'matched' && i.date >= today && i.date <= cycle.end);
  const pendingSum = pending.reduce((s, i) => s + i.amount, 0);
  const budget = Number(settings.monthlyBudget) || 0;
  const perDay = budget ? Math.floor(Math.max(0, budget - spent) / Math.max(1, daysLeft)) : null;
  const next14 = items.filter((i) => i.status !== 'matched' && i.date >= today && diffDays(i.date, today) <= 14);
  const big = next14.length ? next14.reduce((a, b) => (b.amount > a.amount ? b : a)) : items.find((i) => i.date >= today && i.status !== 'matched') || null;
  const week = items.filter((i) => i.status !== 'matched' && i.date >= today && diffDays(i.date, today) <= 6);
  return {
    cycle,
    spent,
    income,
    daysLeft,
    budget,
    perDay,
    pendingSum,
    bigNext: big ? { ...big, dday: diffDays(big.date, today) } : null,
    week,
    weekSum: week.reduce((s, i) => s + i.amount, 0),
    attention: items.filter((i) => i.status === 'missed' || i.status === 'late'),
  };
}

export function categoryTotals(txs, start, end) {
  const map = new Map();
  for (const t of txs) {
    if (t.deleted || t.amount == null || t.date < start || t.date > end) continue;
    const sign = t.kind === 'expense' ? 1 : t.kind === 'refund' ? -1 : 0;
    if (!sign) continue;
    map.set(t.category || '기타', (map.get(t.category || '기타') || 0) + sign * t.amount);
  }
  return [...map.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
}
