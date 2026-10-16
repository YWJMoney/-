import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toCSV, toICS, backupJSON, readBackup } from '../src/io.js';
import { project, migrate, createStore } from '../src/store.js';

test('CSV는 BOM과 수식 주입 방지', () => {
  const csv = toCSV([{ date: '2026-10-15', kind: 'expense', amount: 1000, merchant: '=HYPERLINK("x")', category: '기타' }]);
  assert.ok(csv.startsWith('﻿날짜'));
  assert.ok(csv.includes(`"'=HYPERLINK(""x"")"`));
});

test('ICS에 D-3 알림 포함, 나간 항목은 제외', () => {
  const ics = toICS([
    { id: 'a', title: '월세', amount: 500000, date: '2026-10-25', status: 'planned', label: '확정' },
    { id: 'b', title: '끝난 것', amount: 1, date: '2026-10-01', status: 'matched' },
  ]);
  assert.ok(ics.includes('DTSTART;VALUE=DATE:20261025'));
  assert.ok(ics.includes('TRIGGER:-P3DT0H0M0S'));
  assert.ok(!ics.includes('끝난 것'));
});

test('백업 왕복', () => {
  const state = { ...migrate({}), log: [{ op: 'add', data: { id: '1', amount: 1 } }] };
  assert.deepEqual(readBackup(backupJSON(state)), state);
  assert.throws(() => readBackup('{"foo":1}'));
});

test('이벤트 로그 투영: 수정·삭제는 기록을 덮지 않고 쌓인다', () => {
  const txs = project([
    { op: 'add', data: { id: '1', amount: 1000, date: '2026-10-01' } },
    { op: 'update', id: '1', data: { amount: 2000 } },
    { op: 'add', data: { id: '2', amount: 5, date: '2026-10-02' } },
    { op: 'delete', id: '2' },
  ]);
  assert.equal(txs.find((t) => t.id === '1').amount, 2000);
  assert.equal(txs.find((t) => t.id === '2').deleted, true);
});

test('저장소는 주입한 storage에 저장', () => {
  const mem = new Map();
  const storage = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v) };
  const s = createStore(storage);
  s.addTx({ kind: 'expense', amount: 3000, date: '2026-10-15' });
  const again = createStore(storage);
  assert.equal(again.txs.length, 1);
  assert.equal(again.state.log.length, 1);
});

test('암호화 백업은 같은 비밀번호로만 열린다', async () => {
  const { encryptBackup, decryptBackup, isEncryptedBackup, readBackup, backupJSON } = await import('../src/io.js');
  const json = backupJSON({ schema: 1, log: [{ op: 'add', data: { id: 'a', amount: 1000 } }], inbox: [{ id: 'raw', text: '원문' }] });
  assert.ok(!json.includes('원문'), '못 읽은 알림 원문은 백업에서 뺀다');
  const enc = await encryptBackup(json, '비밀번호123');
  assert.ok(isEncryptedBackup(enc));
  assert.ok(!enc.includes('amount'));
  const plain = await decryptBackup(enc, '비밀번호123');
  assert.equal(readBackup(plain).log[0].data.amount, 1000);
  await assert.rejects(() => decryptBackup(enc, '틀림'), /비밀번호가 맞지 않아요/);
  assert.equal(isEncryptedBackup(json), false);
});
