// 날짜 유틸: 모든 날짜는 'YYYY-MM-DD' 문자열(현지 날짜)로 다룬다.
import { HOLIDAYS } from './holidays.js';

const pad = (n) => String(n).padStart(2, '0');

export function ymd(y, m, d) {
  // m은 1~12. 범위를 넘으면 Date가 알아서 넘겨준다.
  const dt = new Date(Date.UTC(y, m - 1, d));
  return `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}`;
}

export function parts(date) {
  return { y: Number(date.slice(0, 4)), m: Number(date.slice(5, 7)), d: Number(date.slice(8, 10)) };
}

export function todayStr(now = new Date()) {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function daysInMonth(y, m) {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function addDays(date, n) {
  const { y, m, d } = parts(date);
  return ymd(y, m, d + n);
}

// 월을 더하되 말일을 넘지 않게 자른다 (1/31 + 1개월 = 2/28).
export function addMonths(date, n) {
  const { y, m, d } = parts(date);
  const first = parts(ymd(y, m + n, 1));
  return ymd(first.y, first.m, Math.min(d, daysInMonth(first.y, first.m)));
}

// 해당 월의 d일. 말일보다 크면 말일로 자른다 (31일 결제 → 2월은 28/29일).
export function clampDay(y, m, d) {
  const first = parts(ymd(y, m, 1));
  return ymd(first.y, first.m, Math.min(d, daysInMonth(first.y, first.m)));
}

export function diffDays(a, b) {
  return Math.round((Date.parse(a) - Date.parse(b)) / 864e5);
}

export function weekday(date) {
  return new Date(Date.parse(date)).getUTCDay(); // 0=일
}

export function isHoliday(date) {
  return HOLIDAYS.has(date);
}

export function isBusinessDay(date) {
  const w = weekday(date);
  return w !== 0 && w !== 6 && !isHoliday(date);
}

// 결제일이 주말·공휴일이면 다음 영업일로 미룬다.
export function nextBusinessDay(date) {
  let d = date;
  while (!isBusinessDay(d)) d = addDays(d, 1);
  return d;
}

// 급여일은 보통 앞당겨 지급되므로 이전 영업일로 당긴다.
export function prevBusinessDay(date) {
  let d = date;
  while (!isBusinessDay(d)) d = addDays(d, -1);
  return d;
}

export function fmtMD(date) {
  const { m, d } = parts(date);
  return `${m}/${d}`;
}

const WD = ['일', '월', '화', '수', '목', '금', '토'];
export function fmtMDW(date) {
  return `${fmtMD(date)}(${WD[weekday(date)]})`;
}
