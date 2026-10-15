// 카드·은행·간편결제 문자/알림 텍스트 파서 (기기 안에서만 동작, 원문은 저장하지 않는다).
// v0.2에서 카드사별 규칙을 원격 룰팩으로 분리할 예정이라, 여기서는 범용 휴리스틱 + 사전으로 시작한다.
import { ymd, parts, todayStr } from './dates.js';

export const PARSER_VERSION = '0.1.0';

const ISSUERS = [
  ['KB국민', 'KB국민'], ['국민', 'KB국민'], ['신한', '신한'], ['현대', '현대'], ['삼성', '삼성'],
  ['롯데', '롯데'], ['하나', '하나'], ['우리', '우리'], ['비씨', 'BC'], ['BC', 'BC'],
  ['NH농협', 'NH농협'], ['농협', 'NH농협'], ['IBK기업', 'IBK기업'], ['기업', 'IBK기업'], ['씨티', '씨티'],
  ['카카오뱅크', '카카오뱅크'], ['토스뱅크', '토스뱅크'], ['케이뱅크', '케이뱅크'],
  ['카카오페이', '카카오페이'], ['네이버페이', '네이버페이'], ['토스페이', '토스페이'], ['토스', '토스'],
  ['페이코', '페이코'], ['우체국', '우체국'], ['새마을', '새마을금고'], ['수협', '수협'], ['SC제일', 'SC제일'],
  ['iM', 'iM뱅크'], ['대구', 'iM뱅크'], ['부산', '부산'], ['경남', '경남'], ['광주', '광주'], ['전북', '전북'],
];
const ISSUER_RE = new RegExp(`(${ISSUERS.map(([k]) => k).join('|')})(카드|은행|뱅크|체크카드|체크)?`);

const STOPWORDS = new Set([
  '승인', '취소', '사용', '결제', '결제완료', '완료', '일시불', '할부', '체크', '신용', '해외', '국내',
  '카드', '님', '원', '누적', '잔액', '출금', '입금', '이체', '자동이체', '안내', '알림', '이용', '금액',
  'Web발신', '국외발신', '국제발신', '광고', '승인거절', '체크카드', '신용카드',
]);

// 기본 분류 사전. 사용자가 고친 분류는 rules로 덮어쓴다.
const CATEGORY_HINTS = [
  [/스타벅스|커피|카페|이디야|투썸|메가|빽다방|컴포즈|폴바셋|할리스|블루보틀/, '카페'],
  [/GS25|CU|씨유|세븐일레븐|이마트24|미니스톱|편의점/, '편의점'],
  [/배달의민족|우아한형제들|배민|요기요|쿠팡이츠|땡겨요/, '배달'],
  [/넷플릭스|NETFLIX|유튜브|YouTube|YOUTUBE|멜론|스포티파이|Spotify|디즈니|티빙|웨이브|왓챠|쿠팡와우|네이버플러스|애플|Apple|APPLE|구글|Google|GOOGLE|ChatGPT|OpenAI|Claude|Anthropic/, '구독'],
  [/이마트|홈플러스|롯데마트|코스트코|쿠팡|컬리|마켓|농협하나로|트레이더스|다이소|올리브영/, '장보기·쇼핑'],
  [/택시|카카오T|카카오모빌리티|티머니|코레일|지하철|버스|SRT|고속|교통/, '교통'],
  [/주유|칼텍스|SK에너지|S-OIL|에쓰오일|현대오일뱅크|주차|하이패스/, '자동차'],
  [/약국|병원|의원|치과|한의원|안과|내과/, '의료'],
  [/통신|SKT|KT|LG유플러스|U\+|알뜰폰/, '통신'],
  [/관리비|전기|가스|수도|한전|도시가스/, '공과금'],
  [/식당|김밥|치킨|피자|버거|맥도날드|롯데리아|맘스터치|분식|국밥|고기|횟집/, '식비'],
];

export function guessCategory(merchant, rules = {}) {
  if (!merchant) return '기타';
  if (rules[merchant]) return rules[merchant];
  for (const [re, cat] of CATEGORY_HINTS) if (re.test(merchant)) return cat;
  return '기타';
}

const num = (s) => Number(String(s).replace(/,/g, ''));

// 여러 건이 한 번에 붙여넣어졌을 때 메시지 단위로 자른다.
export function splitMessages(text) {
  const norm = String(text || '').replace(/\r\n?/g, '\n').trim();
  if (!norm) return [];
  let chunks = norm
    .split(/(?=\[(?:Web발신|국외발신|국제발신)\])|\n\s*\n/)
    .map((c) => c.trim())
    .filter(Boolean);
  // 한 줄에 한 건씩 붙여넣은 목록이면 줄 단위로 자른다.
  const out = [];
  for (const c of chunks) {
    const lines = c.split('\n').map((l) => l.trim()).filter(Boolean);
    const each = lines.length > 1 && lines.every((l) => /[\d,]+\s*원/.test(l) && /(승인|취소|결제|출금|입금|사용)/.test(l));
    if (each) out.push(...lines);
    else out.push(c);
  }
  return out;
}

function findAmount(text) {
  // '누적', '잔액', '한도' 등 바로 뒤에 오는 금액은 지출액이 아니다.
  const re = /([\d]{1,3}(?:,\d{3})+|\d+)\s*원/g;
  let m;
  while ((m = re.exec(text))) {
    const before = text.slice(Math.max(0, m.index - 6), m.index);
    if (/(누적|잔액|한도|잔여|포인트|적립|가능)\s*:?\s*$/.test(before)) continue;
    return { value: num(m[1]), raw: m[0] };
  }
  // 은행 문자는 '출금 50,000'처럼 '원'이 없는 경우가 많다.
  const bank = /(출금|입금|이체)\s*:?\s*([\d]{1,3}(?:,\d{3})+|\d{3,})/.exec(text);
  if (bank) return { value: num(bank[2]), raw: bank[2] };
  return null;
}

function findForeign(text) {
  const a = /(USD|US\$|EUR|JPY|CNY|GBP|HKD|SGD|AUD|CAD|VND|THB)\s*([\d,]+(?:\.\d+)?)/i.exec(text);
  if (a) return { currency: a[1].toUpperCase().replace('US$', 'USD'), amount: num(a[2]), raw: a[0] };
  const b = /([\d,]+(?:\.\d+)?)\s*(USD|달러|EUR|유로|JPY|엔|위안|파운드)/.exec(text);
  if (b) {
    const map = { 달러: 'USD', 유로: 'EUR', 엔: 'JPY', 위안: 'CNY', 파운드: 'GBP' };
    return { currency: map[b[2]] || b[2].toUpperCase(), amount: num(b[1]), raw: b[0] };
  }
  const c = /\$\s*([\d,]+(?:\.\d+)?)/.exec(text);
  if (c) return { currency: 'USD', amount: num(c[1]), raw: c[0] };
  return null;
}

function inferYear(month, now) {
  const t = parts(todayStr(now));
  // 1월에 받은 12월 문자처럼, 미래 월이면 작년으로 본다.
  return month > t.m + 1 ? t.y - 1 : t.y;
}

export function parseMessage(text, { now = new Date(), rules = {} } = {}) {
  const src = String(text || '').trim();
  if (!src) return { ok: false, reason: '빈 텍스트' };

  const fx = findForeign(src);
  const amt = findAmount(src);
  if (!amt && !fx) return { ok: false, reason: '금액을 찾지 못했습니다' };

  // 거래 종류
  let type = 'APPROVAL';
  if (/승인\s*거절|거절/.test(src)) return { ok: false, reason: '승인 거절 문자' };
  if (/취소/.test(src)) type = 'CANCEL';
  else if (/입금/.test(src)) type = 'DEPOSIT';
  else if (/출금|이체/.test(src)) type = 'WITHDRAW';

  // 카드사·은행
  const iss = ISSUER_RE.exec(src);
  const issuer = iss ? ISSUERS.find(([k]) => k === iss[1])[1] : null;
  const isCard = /카드|체크|승인|일시불|할부|개월/.test(src) && type !== 'DEPOSIT' && type !== 'WITHDRAW';

  // 카드 끝 4자리: 신한카드(1234), KB국민카드1234승인, *1234
  let last4 = null;
  const l4 =
    /(?:카드|체크)\s*\(?\s*[*xX]?(\d{4})\s*\)?/.exec(src) ||
    /\(\s*[*xX]?(\d{4})\s*\)/.exec(src) ||
    /[*xX](\d{4})(?!\d)/.exec(src) ||
    (iss ? /^\s*\(?\s*(\d{4})(?!\d)/.exec(src.slice(iss.index + iss[0].length)) : null);
  if (l4) last4 = l4[1];

  // 날짜·시간
  let date = null;
  let dm = null;
  const dateRe = /(?<![\d,.])(\d{1,2})\s*[/.\-월]\s*(\d{1,2})\s*일?(?![\d,.])/g;
  for (let m; (m = dateRe.exec(src)); ) {
    const mo = Number(m[1]);
    const da = Number(m[2]);
    if (mo >= 1 && mo <= 12 && da >= 1 && da <= 31) {
      date = ymd(inferYear(mo, now), mo, da);
      dm = m;
      break;
    }
  }
  const tm = /(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)/.exec(src);
  const time = tm ? `${tm[1].padStart(2, '0')}:${tm[2]}` : null;

  // 할부
  let installmentMonths = 1;
  const inst = /(\d{1,2})\s*개월/.exec(src) || /할부\s*\(?\s*(\d{1,2})/.exec(src);
  if (inst && Number(inst[1]) > 1) installmentMonths = Number(inst[1]);

  const cum = /누적\s*:?\s*([\d,]+)/.exec(src);
  const bal = /잔액\s*:?\s*([\d,]+)/.exec(src);

  // 가맹점: 알려진 조각을 지우고 남은 조각 중 마지막 것을 고른다.
  let rest = src;
  const cut = (s) => {
    if (s) rest = rest.split(s).join('|');
  };
  rest = rest.replace(/\[[^\]]*\]/g, '|'); // [Web발신], [현대카드]
  if (iss) cut(iss[0]);
  if (amt) cut(amt.raw);
  if (fx) cut(fx.raw);
  if (dm) cut(dm[0]);
  if (tm) cut(tm[0]);
  rest = rest
    .replace(/(누적|잔액|한도|잔여)\s*:?\s*[\d,]+\s*원?/g, '|')
    .replace(/\(?\s*[*xX]?\d{4}\s*\)?/g, '|')
    .replace(/\d{1,2}\s*개월|일시불|할부/g, '|')
    .replace(/\(주\)|㈜/g, '')
    .replace(/(?<![A-Za-z가-힣\d])\d[\d,.\-]*/g, '|');
  const pieces = rest
    .split(/[|\n]/)
    .flatMap((p) => p.split(/\s{2,}/))
    .map((p) => p.replace(/^[\s:()·,.\-]+|[\s:()·,.\-]+$/g, '').trim())
    .filter((p) => p && !p.includes('*') && !/님$/.test(p) && !STOPWORDS.has(p) && !/^(승인|취소|결제|출금|입금)+$/.test(p));
  const cleaned = pieces
    .map((p) => p.split(/\s+/).filter((w) => !STOPWORDS.has(w) && !/님$/.test(w)).join(' '))
    .filter(Boolean);
  const merchant = cleaned.length ? cleaned[cleaned.length - 1].slice(0, 40) : '';

  let confidence = 0.4;
  if (amt) confidence += 0.2;
  if (date) confidence += 0.15;
  if (merchant) confidence += 0.15;
  if (issuer) confidence += 0.1;

  const kind =
    type === 'DEPOSIT' ? 'income' : type === 'WITHDRAW' ? 'transfer' : type === 'CANCEL' ? 'refund' : 'expense';

  return {
    ok: true,
    parserVersion: PARSER_VERSION,
    type,
    kind,
    channel: isCard ? 'card' : issuer ? 'bank' : 'unknown',
    amount: amt ? amt.value : null,
    fx: fx ? { currency: fx.currency, amount: fx.amount } : null,
    needsKrw: !amt && !!fx,
    issuer,
    last4,
    date,
    time,
    installmentMonths,
    cumulative: cum ? num(cum[1]) : null,
    balance: bal ? num(bal[1]) : null,
    merchant,
    category: kind === 'expense' || kind === 'refund' ? guessCategory(merchant, rules) : kind === 'income' ? '수입' : '이체',
    confidence: Math.min(1, Math.round(confidence * 100) / 100),
  };
}

export function parseMany(text, opts) {
  return splitMessages(text).map((chunk) => ({ chunk, result: parseMessage(chunk, opts) }));
}

// 같은 결제가 카드 문자 + 페이 앱 알림으로 두 번 들어오는 경우를 표시한다 (병합은 사용자가 고른다).
export function findDuplicate(tx, existing) {
  return (
    existing.find((e) => {
      if (e.deleted || e.amount !== tx.amount || e.date !== tx.date || e.kind !== tx.kind) return false;
      if (e.time && tx.time) {
        const toMin = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));
        if (Math.abs(toMin(e.time) - toMin(tx.time)) > 10) return false;
      }
      return (e.last4 && tx.last4 && e.last4 === tx.last4) || (e.merchant && e.merchant === tx.merchant) || (!e.time && !tx.time);
    }) || null
  );
}

// 취소 문자를 원거래에 연결한다: 같은 금액·가맹점(또는 카드), 30일 이내 이전 승인.
export function findCancelTarget(cancel, existing) {
  const cands = existing
    .filter(
      (e) =>
        !e.deleted &&
        e.kind === 'expense' &&
        e.amount === cancel.amount &&
        (!cancel.date || !e.date || (e.date <= cancel.date && Date.parse(cancel.date) - Date.parse(e.date) <= 30 * 864e5)) &&
        ((cancel.merchant && e.merchant === cancel.merchant) || (cancel.last4 && e.last4 === cancel.last4)),
    )
    .sort((a, b) => (a.date < b.date ? 1 : -1));
  return cands[0] || null;
}
