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

export function backupJSON(state) {
  return JSON.stringify({ app: 'jangbu', exportedAt: new Date().toISOString(), state }, null, 2);
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
      `DESCRIPTION:${icsText('자동 장부 앱에서 내보낸 예정 지출입니다.')}`,
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsText(`3일 뒤 ${it.title} ${won}`)}`, 'TRIGGER:-P3DT0H0M0S', 'END:VALARM',
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:${icsText(`오늘 ${it.title} ${won}`)}`, 'TRIGGER:PT9H', 'END:VALARM',
      'END:VEVENT',
    );
  }
  lines.push('END:VCALENDAR');
  return lines.join('\r\n');
}
