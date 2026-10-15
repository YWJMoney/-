// 결제 알림 자동 기록: 안드로이드가 건진 알림 문구를 장부 기록으로 바꾼다. 화면과 분리된 순수 함수.
import { parseMany, findDuplicate, findCancelTarget } from './parser.js';
import { todayStr } from './dates.js';

const pad = (n) => String(n).padStart(2, '0');

// 문자 속 카드 끝 4자리·카드사로 등록된 카드를 찾는다.
export function matchCard(r, cards = []) {
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

// 파서 결과 하나를 기록 초안으로 바꾼다.
export function resultToTx(r, { cards = [], source = 'paste', fallbackDate, fallbackTime } = {}) {
  return {
    kind: r.kind,
    amount: r.amount,
    fx: r.fx,
    date: r.date || fallbackDate || todayStr(),
    time: r.time || fallbackTime || null,
    merchant: r.merchant,
    category: r.category,
    cardId: r.channel === 'card' ? matchCard(r, cards) : '',
    installmentMonths: r.installmentMonths,
    last4: r.last4,
    issuer: r.issuer,
    source,
    parserVersion: r.parserVersion,
  };
}

// 거래가 아닌 문자(승인 거절)는 장부에도, 못 읽은 목록에도 넣지 않는다.
const IGNORE_REASONS = new Set(['승인 거절 문자', '빈 텍스트']);

/**
 * items: [{id, app, text, at(ms)}]
 * 반환: { txs: 저장할 기록, inbox: 못 읽은 알림, dup: 이미 있는 결제라 건너뛴 수, ack: 처리한 id }
 * 같은 결제가 문자 + 카드앱 알림으로 두 번 와도 한 번만 기록한다.
 */
export function captureToTxs(items, { existing = [], rules = {}, cards = [], now = new Date() } = {}) {
  const txs = [];
  const inbox = [];
  let dup = 0;
  const live = existing.filter((t) => !t.deleted);
  for (const it of items) {
    const at = new Date(it.at || now.getTime());
    const fallbackDate = todayStr(at);
    const fallbackTime = `${pad(at.getHours())}:${pad(at.getMinutes())}`;
    for (const { chunk, result: r } of parseMany(it.text, { rules, now: at })) {
      if (!r.ok) {
        if (!IGNORE_REASONS.has(r.reason)) inbox.push({ id: `${it.id}-${inbox.length}`, app: it.app || '', text: chunk, at: at.getTime(), reason: r.reason });
        continue;
      }
      // 해외결제처럼 원화 금액이 없으면 사람이 넣어야 하므로 못 읽은 목록으로 보낸다.
      if (r.amount == null) {
        inbox.push({ id: `${it.id}-${inbox.length}`, app: it.app || '', text: chunk, at: at.getTime(), reason: '원화 금액이 없어요' });
        continue;
      }
      const tx = { ...resultToTx(r, { cards, source: 'notif', fallbackDate, fallbackTime }), review: true };
      const pool = live.concat(txs);
      if (findDuplicate(tx, pool)) {
        dup += 1;
        continue;
      }
      if (tx.kind === 'refund') {
        const link = findCancelTarget(tx, pool);
        if (link) tx.linkedTo = link.id;
      }
      txs.push(tx);
    }
  }
  return { txs, inbox, dup, ack: items.map((i) => i.id) };
}
