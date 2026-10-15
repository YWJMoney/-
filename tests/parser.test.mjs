// 아래 문자 샘플은 실제 형식을 흉내 낸 합성 데이터다(실제 고객 정보 아님).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseMessage, parseMany, splitMessages, findDuplicate, findCancelTarget, guessCategory } from '../src/parser.js';

const now = new Date('2026-10-15T12:00:00');
const p = (s) => parseMessage(s, { now });

test('신한 한 줄 승인 문자', () => {
  const r = p('[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:20 스타벅스 누적1,234,567원');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'expense');
  assert.equal(r.amount, 12500);
  assert.equal(r.issuer, '신한');
  assert.equal(r.last4, '1234');
  assert.equal(r.date, '2026-10-15');
  assert.equal(r.time, '13:20');
  assert.equal(r.merchant, '스타벅스');
  assert.equal(r.cumulative, 1234567);
  assert.equal(r.category, '카페');
});

test('KB 여러 줄 승인 문자', () => {
  const r = p('[Web발신]\nKB국민카드1234승인\n홍*동님\n35,000원 일시불\n10/15 12:30\n이마트\n누적 500,000원');
  assert.equal(r.amount, 35000);
  assert.equal(r.issuer, 'KB국민');
  assert.equal(r.merchant, '이마트');
});

test('할부 개월 수', () => {
  const r = p('[현대카드] 홍길동님 10/14 09:12 쿠팡 450,000원 3개월 승인');
  assert.equal(r.installmentMonths, 3);
  assert.equal(r.amount, 450000);
  assert.equal(r.merchant, '쿠팡');
});

test('취소 문자는 환불로', () => {
  const r = p('신한카드(1234)취소 홍*동 12,500원 10/15 14:00 스타벅스');
  assert.equal(r.type, 'CANCEL');
  assert.equal(r.kind, 'refund');
});

test('카드사 바로 뒤 4자리와 숫자가 섞인 가맹점', () => {
  const r = p('삼성1234승인 홍*동 89,000원 일시불 10/13 18:22 GS25 강남점 누적 1,000,000원');
  assert.equal(r.last4, '1234');
  assert.equal(r.merchant, 'GS25 강남점');
  assert.equal(r.category, '편의점');
});

test('은행 출금은 이체, 잔액은 지출액이 아님', () => {
  const r = p('[Web발신]\n신한 10/15 14:00\n110-***-123456\n출금 50,000\n잔액 1,000,000\n김철수');
  assert.equal(r.kind, 'transfer');
  assert.equal(r.amount, 50000);
  assert.equal(r.balance, 1000000);
  assert.equal(r.merchant, '김철수');
});

test('은행 입금은 수입', () => {
  const r = p('하나 10/15 09:00 123-456***-78 입금 3,200,000 잔액 4,000,000 (주)회사급여');
  assert.equal(r.kind, 'income');
  assert.equal(r.amount, 3200000);
});

test('해외결제는 원화 입력이 필요하다고 표시', () => {
  const r = p('우리(5678)승인 USD 12.99 10/12 08:00 NETFLIX.COM');
  assert.equal(r.needsKrw, true);
  assert.equal(r.amount, null);
  assert.deepEqual(r.fx, { currency: 'USD', amount: 12.99 });
  assert.equal(r.date, '2026-10-12');
  assert.equal(r.category, '구독');
});

test('승인 거절과 금액 없는 문자는 실패', () => {
  assert.equal(p('신한카드 승인거절 잔액부족 10/15').ok, false);
  assert.equal(p('안녕하세요 오늘 회의는 3시입니다').ok, false);
});

test('작년 12월 문자를 1월에 붙여넣으면 작년으로', () => {
  const r = parseMessage('신한카드(1234)승인 10,000원 12/30 10:00 서점', { now: new Date('2027-01-03T10:00:00') });
  assert.equal(r.date, '2026-12-30');
});

test('여러 건 분리: [Web발신] 구분과 한 줄 목록', () => {
  assert.equal(splitMessages('[Web발신]\nA카드 1,000원 승인\n[Web발신]\nB카드 2,000원 승인').length, 2);
  assert.equal(splitMessages('신한 1,000원 승인 스타벅스\n국민 2,000원 승인 이마트\n현대 3,000원 승인 쿠팡').length, 3);
  assert.equal(parseMany('신한 1,000원 승인 스타벅스\n\n국민 2,000원 승인 이마트', { now }).length, 2);
});

test('학습된 분류 규칙이 사전보다 우선', () => {
  assert.equal(guessCategory('스타벅스', { 스타벅스: '식비' }), '식비');
  assert.equal(guessCategory('알 수 없는 가게'), '기타');
});

test('중복 의심과 취소 연결', () => {
  const a = { id: 'a', kind: 'expense', amount: 12500, date: '2026-10-15', time: '13:20', last4: '1234', merchant: '스타벅스' };
  assert.ok(findDuplicate({ ...a, id: 'b', time: '13:25' }, [a]));
  assert.equal(findDuplicate({ ...a, id: 'b', time: '15:00' }, [a]), null);
  const cancel = { kind: 'refund', amount: 12500, date: '2026-10-16', merchant: '스타벅스' };
  assert.equal(findCancelTarget(cancel, [a]).id, 'a');
});
