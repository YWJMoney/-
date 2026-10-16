import { test } from 'node:test';
import assert from 'node:assert/strict';
import { captureToTxs, matchCard } from '../src/capture.js';
import { maskForReport } from '../src/parser.js';

const at = new Date(2026, 9, 15, 13, 21).getTime();

test('알림 문구를 자동 기록으로 바꾸고 확인 대기 표시를 붙인다', () => {
  const items = [{ id: 'a', app: '메시지', text: '[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:20 스타벅스 누적1,234,567원', at }];
  const r = captureToTxs(items, { cards: [{ id: 'c1', last4: '1234', issuer: '신한' }], now: new Date(at) });
  assert.equal(r.txs.length, 1);
  const t = r.txs[0];
  assert.equal(t.amount, 12500);
  assert.equal(t.source, 'notif');
  assert.equal(t.review, true);
  assert.equal(t.cardId, 'c1');
  assert.deepEqual(r.ack, ['a']);
});

test('같은 결제가 문자와 카드앱 알림으로 두 번 와도 한 번만 기록한다', () => {
  const items = [
    { id: 'sms', app: '메시지', text: '신한카드(1234)승인 홍*동 12,500원 일시불 10/15 13:20 스타벅스', at },
    { id: 'app', app: '신한 SOL페이', text: '신한카드(1234) 12,500원 승인 10/15 13:20 스타벅스', at },
  ];
  const r = captureToTxs(items, { now: new Date(at) });
  assert.equal(r.txs.length, 1);
  assert.equal(r.dup, 1);
  assert.deepEqual(r.ack, ['sms', 'app']);
});

test('이미 장부에 있는 결제는 건너뛴다', () => {
  const existing = [{ id: 'x', kind: 'expense', amount: 12500, date: '2026-10-15', time: '13:20', merchant: '스타벅스', last4: '1234' }];
  const r = captureToTxs([{ id: 'a', text: '신한카드(1234)승인 12,500원 10/15 13:20 스타벅스', at }], { existing, now: new Date(at) });
  assert.equal(r.txs.length, 0);
  assert.equal(r.dup, 1);
});

test('날짜가 없는 알림은 알림 받은 날짜·시각으로 기록한다', () => {
  const r = captureToTxs([{ id: 'a', text: '카카오페이 결제 8,900원 GS25 역삼점', at }], { now: new Date(at) });
  assert.equal(r.txs[0].date, '2026-10-15');
  assert.equal(r.txs[0].time, '13:21');
});

test('원화 금액이 없는 해외결제는 못 읽은 목록으로 보낸다', () => {
  const r = captureToTxs([{ id: 'a', app: '현대카드', text: '현대카드 승인 USD 12.99 10/15 NETFLIX.COM', at }], { now: new Date(at) });
  assert.equal(r.txs.length, 0);
  assert.equal(r.inbox.length, 1);
  assert.equal(r.inbox[0].app, '현대카드');
});

test('승인 거절 문자는 기록도, 못 읽은 목록도 만들지 않는다', () => {
  const r = captureToTxs([{ id: 'a', text: '신한카드 승인거절 12,500원 한도초과', at }], { now: new Date(at) });
  assert.equal(r.txs.length, 0);
  assert.equal(r.inbox.length, 0);
  assert.deepEqual(r.ack, ['a']);
});

test('취소 알림은 원거래에 연결한다', () => {
  const existing = [{ id: 'orig', kind: 'expense', amount: 30000, date: '2026-10-12', merchant: '쿠팡', last4: '1234' }];
  const r = captureToTxs([{ id: 'a', text: '신한카드(1234) 취소 30,000원 10/15 14:00 쿠팡', at }], { existing, now: new Date(at) });
  assert.equal(r.txs[0].kind, 'refund');
  assert.equal(r.txs[0].linkedTo, 'orig');
});

test('카드사만 맞으면 그 카드사 카드가 하나일 때만 연결한다', () => {
  assert.equal(matchCard({ issuer: '신한' }, [{ id: 'a', issuer: '신한' }]), 'a');
  assert.equal(matchCard({ issuer: '신한' }, [{ id: 'a', issuer: '신한' }, { id: 'b', issuer: '신한' }]), '');
});

test('형식 제보용 가림 처리는 숫자와 가려진 이름을 지운다', () => {
  const m = maskForReport('신한카드(1234)승인 홍*동 12,500원 10/15 13:20 스타벅스');
  assert.equal(m, '신한카드(0000)승인 *** 00,000원 00/00 00:00 스타벅스');
});
