// 내보내기: CSV(엑셀용), JSON 백업, .ics 달력.
import { addDays } from './dates.js';

const KIND_LABEL = { expense: '지출', income: '수입', transfer: '이체', refund: '환불·취소' };

const csvCell = (v) => {
  const s = v == null ? '' : String(v);
  // 수식 주입 방지: =, +, -, @ 로 시작하면 앞에 작은따옴표를 붙인다.
  const safe = /^[=+\-@]/.test(s) ? `'${s}` : s;
  return /[",\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
};

export function toCSV(txs, cards = []) {
  const cardName = (id) => cards.find((c) => c.id === id)?.name || '';
  const rows = [['날짜', '시간', '구분', '금액', '분류', '내용', '메모', '카드', '할부', '외화', '출처']];
  for (const t of txs) {
    if (t.deleted) continue;
    rows.push([
      t.date, t.time || '', KIND_LABEL[t.kind] || t.kind, t.amount ?? '', t.category || '', t.merchant || '',
      t.memo || '', cardName(t.cardId), t.installmentMonths > 1 ? `${t.installmentMonths}개월` : '',
      t.fx ? `${t.fx.currency} ${t.fx.amount}` : '', t.source || '',
    ]);
  }
  return '﻿' + rows.map((r) => r.map(csvCell).join(',')).join('\r\n');
}

// 못 읽은 알림 원문(inbox)은 기기 밖으로 내보내지 않는다.
export function backupJSON(state) {
  return JSON.stringify({ app: 'jangbu', exportedAt: new Date().toISOString(), state: { ...state, inbox: [] } }, null, 2);
}

// ── 비밀번호 암호화 백업 (AES-GCM 256, 키는 PBKDF2-SHA256으로 비밀번호에서 만든다) ──
const PBKDF2_ITER = 310000;
const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function deriveKey(password, salt, iter) {
  const subtle = globalThis.crypto.subtle;
  const base = await subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  return subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export async function encryptBackup(json, password) {
  if (!password) throw new Error('비밀번호를 넣어 주세요');
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(16));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt, PBKDF2_ITER);
  const data = await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(json));
  // 큰 장부도 처리할 수 있게 나눠서 base64로 바꾼다.
  const bytes = new Uint8Array(data);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return JSON.stringify({
    app: 'jangbu',
    encrypted: { v: 1, alg: 'AES-GCM', kdf: 'PBKDF2-SHA256', iter: PBKDF2_ITER, salt: b64(salt), iv: b64(iv) },
    data: btoa(bin),
  });
}

export function isEncryptedBackup(text) {
  try {
    return !!JSON.parse(text)?.encrypted;
  } catch {
    return false;
  }
}

export async function decryptBackup(text, password) {
  const obj = JSON.parse(text);
  const e = obj?.encrypted;
  if (!e || e.alg !== 'AES-GCM') throw new Error('암호화된 장부 백업이 아닙니다');
  const key = await deriveKey(password, unb64(e.salt), e.iter);
  try {
    const plain = await globalThis.crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(e.iv) }, key, unb64(obj.data));
    return new TextDecoder().decode(plain);
  } catch {
    throw new Error('비밀번호가 맞지 않아요');
  }
}

export function readBackup(text) {
  const obj = JSON.parse(text);
  const state = obj?.app === 'jangbu' ? obj.state : obj;
  if (!state || !Array.isArray(state.log)) throw new Error('장부 백업 파일이 아닙니다');
  return state;
}

const icsText = (s) => String(s).replace(/[\\;,]/g, (c) => `\\${c}`).replace(/\n/g, '\\n');
const icsDate = (d) => d.replace(/-/g, '');

// 곧 나갈 돈을 폰 기본 달력으로 보낸다. 앱이 꺼져 있어도 달력이 D-3·당일 알림을 울린다.
export function toICS(items, now = new Date()) {
  const stamp = now.toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//jangbu//upcoming//KO', 'CALSCALE:GREGORIAN', 'X-WR-CALNAME:곧 나갈 돈'];
  for (const it of items) {
    if (it.status === 'matched') continue;
    const won = `${Number(it.amount || 0).toLocaleString('ko-KR')}원`;
    lines.push(
      'BEGIN:VEVENT',
      `UID:${it.id}@jangbu`,
      `DTSTAMP:${stamp}`,
      `DTSTART;VALUE=DATE:${icsDate(it.date)}`,
      `DTEND;VALUE=DATE:${icsDate(addDays(it.date, 1))}`,
      `SUMMARY:${icsText(`${it.title} ${won}${it.label === '예상' ? ' (예상)' : ''}`)}`,
      `DESCRIPTION:${icsText('살림왕 앱에서 내보낸 예정 지출입니다.')}`,
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsText(`3일 뒤 ${it.title} ${won}`)}`, 'TRIGGER:-P3DT0H0M0S', 'END:VALARM',
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsText(`오늘 ${it.title} ${won}`)}`, 'TRIGGER:PT9H', 'END:VALARM',
      'END:VEVENT',
    );
  }
  lines.push('END:VCALENDAR');
  return lines.join('\r\n');
}
