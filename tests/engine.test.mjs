import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cardPeriod, cardPayDate, cardBills, occurrences, upcoming, payCycle, summary } from '../src/engine.js';
import { nextBusinessDay, prevBusinessDay, addMonths } from '../src/dates.js';

test('휴일·주말이면 다음 영업일', () => {
  assert.equal(nextBusinessDay('2026-10-03'), '2026-10-06'); // 토 → 대체공휴일 월 → 화
  assert.equal(nextBusinessDay('2026-10-15'), '2026-10-15');
  assert.equal(prevBusinessDay('2026-10-25'), '2026-10-23'); // 일 → 금
});

test('월말 더하기는 말일로 자른다', () => {
  assert.equal(addMonths('2026-01-31', 1), '2026-02-28');
});

test('카드 이용기간: 결제일 14일 = 전월 1일~말일', () => {
  const card = { id: 'c', payDay: 14, periodStartDay: 1, periodOffsetMonths: 1 };
  assert.deepEqual(cardPeriod(card, 2026, 11), { start: '2026-10-01', end: '2026-10-31' });
});

test('카드 이용기간: 결제일 25일 = 전월 12일~당월 11일', () => {
  const card = { id: 'c', payDay: 25, periodStartDay: 12, periodOffsetMonths: 1 };
  assert.deepEqual(cardPeriod(card, 2026, 11), { start: '2026-10-12', end: '2026-11-11' });
});

test('카드 결제 예상액: 할부는 월 납입분, 취소는 차감', () => {
  const card = { id: 'c', payDay: 14, periodStartDay: 1, periodOffsetMonths: 1 };
  const txs = [
    { id: '1', cardId: 'c', kind: 'expense', amount: 30000, date: '2026-10-05' },
    { id: '2', cardId: 'c', kind: 'expense', amount: 300000, date: '2026-10-10', installmentMonths: 3 },
    { id: '3', cardId: 'c', kind: 'refund', amount: 10000, date: '2026-10-12' },
    { id: '4', cardId: 'x', kind: 'expense', amount: 99999, date: '2026-10-12' },
  ];
  const [nov, dec] = cardBills(card, txs, '2026-10-20');
  assert.equal(nov.date, '2026-11-16'); // 11/14 토 → 11/16 월
  assert.equal(nov.amount, 30000 + 100000 - 10000);
  assert.equal(nov.label, '예상');
  assert.equal(dec.amount, 100000); // 12월 청구에는 할부 2회차만
});

test('할부 2회차는 다음 달 청구로', () => {
  const card = { id: 'c', payDay: 14, periodStartDay: 1, periodOffsetMonths: 1 };
  const txs = [{ id: '2', cardId: 'c', kind: 'expense', amount: 300000, date: '2026-10-10', installmentMonths: 3 }];
  const bills = cardBills(card, txs, '2026-11-20', 2);
  assert.equal(bills[0].billKey, '2026-12');
  assert.equal(bills[0].amount, 100000);
});

test('확정액을 넣으면 확정 라벨', () => {
  const card = { id: 'c', payDay: 14, periodStartDay: 1, confirmed: { '2026-11': 123000 } };
  const [nov] = cardBills(card, [], '2026-10-20');
  assert.equal(nov.amount, 123000);
  assert.equal(nov.label, '확정');
});

test('31일 고정 지출은 짧은 달 말일로', () => {
  const occ = occurrences({ id: 'o', cycle: 'monthly', day: 31 }, '2027-02-01', '2027-02-28');
  assert.deepEqual(occ.map((o) => o.date), ['2027-02-28']);
});

test('매주·매년 주기', () => {
  assert.equal(occurrences({ cycle: 'weekly', weekday: 1 }, '2026-10-15', '2026-10-31').length, 2);
  assert.deepEqual(occurrences({ cycle: 'yearly', month: 3, day: 2 }, '2027-01-01', '2027-12-31').map((o) => o.date), ['2027-03-02']);
});

test('예정 항목은 실제 거래와 매칭되면 나감 처리', () => {
  const state = {
    obligations: [{ id: 'n', name: '넷플릭스', amount: 17000, cycle: 'monthly', day: 18 }],
    cards: [],
    txs: [{ id: 't', kind: 'expense', amount: 17000, date: '2026-10-19', merchant: 'NETFLIX' }],
  };
  const items = upcoming(state, '2026-10-19');
  const oct = items.find((i) => i.date === '2026-10-18');
  assert.equal(oct, undefined); // 지나간 매칭 건은 목록에서 빠짐
  const nov = items.find((i) => i.date === '2026-11-18');
  assert.equal(nov.status, 'planned');
});

test('매칭 안 된 지난 항목은 확인 필요', () => {
  const state = { obligations: [{ id: 'r', name: '월세', amount: 500000, cycle: 'monthly', day: 10 }], cards: [], txs: [] };
  const raw = upcoming(state, '2026-10-15').find((i) => i.date === '2026-10-10');
  assert.equal(raw.status, 'missed');
});

test('급여 주기: 급여일이 주말이면 앞 영업일', () => {
  const c = payCycle(25, '2026-10-15'); // 9/25 추석 연휴 → 9/23 수, 10/25 일 → 10/23 금
  assert.equal(c.start, '2026-09-23');
  assert.equal(c.nextPay, '2026-10-23');
  assert.equal(c.end, '2026-10-22');
});

test('홈 요약: 하루 쓸 수 있는 돈', () => {
  const state = {
    settings: { payday: 25, monthlyBudget: 800000 },
    obligations: [],
    cards: [],
    txs: [{ id: 'a', kind: 'expense', amount: 200000, date: '2026-10-01' }],
  };
  const s = summary(state, '2026-10-15');
  assert.equal(s.spent, 200000);
  assert.equal(s.daysLeft, 8); // 10/15~10/22
  assert.equal(s.perDay, 75000);
});
