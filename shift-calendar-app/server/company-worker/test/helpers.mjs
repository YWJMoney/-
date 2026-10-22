// [COMM-04] A worker on an in-memory D1 and a company settings file as the app sends it, for the tests.
import worker from "../src/index.js";
import { d1 } from "./d1.mjs";

export const ORIGIN = "https://company.example";
export const PASSWORD = "night-and-day-admin";

/**
 * A worker with its own empty database; [call] sends one request and reads the answer. Tests written
 * before 1.21 adopt with two people and give rewards by hand; [plaza] true keeps the server's own
 * defaults instead (three people, rewards given by themselves, 결정 D32·D33).
 */
export function server({ plaza = false, ...extra } = {}) {
  const DB = d1();
  const env = { DB, RATE_SALT: "test-salt", ADMIN_PASSWORD: PASSWORD, ...extra };
  let seeded = plaza || !env.DB;
  const call = async (method, path, body, headers = {}) => {
    if (!seeded) {
      seeded = true;
      await worker.fetch(new Request(`${ORIGIN}/api/terms`), env);
      for (const [k, v] of [["adopt_persons", "2"], ["amend_persons", "2"], ["auto_reward", "0"]]) {
        await DB.prepare("INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)").bind(k, v).run();
      }
    }
    const res = await worker.fetch(new Request(ORIGIN + path, {
      method,
      headers: {
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        "cf-connecting-ip": "203.0.113.5",
        ...headers,
      },
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      redirect: "manual",
    }), env);
    const text = res.status === 204 ? "" : await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // Not JSON: files of the developer app.
    }
    return { status: res.status, headers: res.headers, data, text };
  };
  return { env, DB, call };
}

/** An install id as the app makes one: random, never tied to a person. */
export const device = (n) => `test-device-${String(n).padStart(8, "0")}`;

/** Another phone: its own install and its own network. */
export const from = (n) => ({ "cf-connecting-ip": `198.51.100.${n}` });

/** Signs in to the developer app; returns the headers its requests carry. */
export async function signIn(call) {
  const res = await call("POST", "/api/admin/login", { password: PASSWORD }, { "x-admin": "1", origin: ORIGIN });
  if (res.status !== 200) throw new Error(`login ${res.status} ${res.text}`);
  const token = res.headers.get("set-cookie").split(";")[0];
  return { cookie: token, "x-admin": "1", origin: ORIGIN };
}

/**
 * A 4조 2교대 company's settings file, as CompanyShare.export writes it with amounts left out.
 * Made up for the tests: no real company's rules.
 */
export function setup() {
  return {
    app: "com.bamnat.app",
    format: "bamnat-company",
    v: 2,
    company: "예시정밀",
    shiftTypes: [
      { name: "주간", label: "주", color: 4280391411, start: "07:00", end: "19:00", break: 60, breakStart: "12:00", off: false },
      { name: "야간", label: "야", color: 4284246976, start: "19:00", end: "07:00", break: 60, off: false, icon: "moon" },
      { name: "휴무", label: "휴", color: 4288585374, break: 0, off: true },
    ],
    extraKinds: "v2\n1|잔업|잔|4293431308|A,120|BY_DAY|0|0\n2|특근|특|4292882737|B,09:00,18:00,60,12:00|BY_DAY|1|0\n" +
      "3|대근|대|4278099822|S,-|HOLIDAY|1|0\n101|조기출근|조|4286611584|P,60|BY_DAY|0|0",
    extraKindShifts: {},
    extraKindShiftSets: { 3: ["주간", "야간"] },
    rotation: { anchor: "2026-10-01", steps: ["주간", "주간", "휴무", "휴무", "야간", "야간", "휴무", "휴무"] },
    pay: {
      fiveOrMore: true,
      monthlyHours: 180,
      holidayMode: "FULL",
      dailyLimit: -1,
      weeklyLimit: -1,
      scheduleIsRegular: true,
      weekStart: "MONDAY",
      period: { endDay: -1, payDay: 25, payNextMonth: false },
      rules: {
        ot: null, night: null, hw: null, ho: null,
        restDay: "HOLIDAY", offDay: "HOLIDAY", weeklyHolidayDay: "FIRST_REST", publicHolidays: true,
        roundUnit: 1, round: "DOWN", weeklyHoliday: true, annualLeave: null, leaveCarry: 0,
        publicHolidayAs: "3", holidayLeaveFree: false, holidayByStart: false, nightStart: "22:00", nightEnd: "06:00",
        extraByShift: {},
        extra: {
          3: {
            basis: "UNIT", multiplier: 1.5, amount: 0, min: 0, night: true, percent: 0.1, cover: -1, premiums: false, bonus: 0,
            dayTiers: [], nightTiers: [], unit: "BASE_SHARE", unitMin: 480,
            adds: [
              { key: "overtime", name: "연장 가산", rate: 0.5, on: true, scope: "over:480", offDays: false },
              { key: "night", name: "야간 가산", rate: 0.5, on: true, scope: "night", offDays: false },
              { key: "holiday", name: "휴일 가산", rate: 0.5, on: false, scope: "holiday", offDays: false },
              { key: "rest", name: "휴무일 가산", rate: 0.5, on: false, scope: "rest", offDays: false },
            ],
          },
          101: { basis: "LEGAL", multiplier: 1.5, amount: 0, min: 0, night: true, percent: 0.1, cover: -1, premiums: false, bonus: 0,
            dayTiers: [], nightTiers: [], unit: "ORDINARY", unitMin: 480, adds: [] },
        },
      },
      withAmounts: false,
      items: [
        { id: "bonus", name: "상여", amount: { entries: [], raise: null }, cadence: "BONUS", types: [], ordinary: false, taxable: true,
          months: [2, 4, 6, 8, 10, 12], percentOfBase: 1, fixedOvertime: 0, festivals: [], festivalBefore: false },
        { id: "license", name: "자격수당", amount: { entries: [], raise: null }, cadence: "MONTHLY", types: [], ordinary: true, taxable: true,
          months: [], fixedOvertime: 0, festivals: [], festivalBefore: false },
        { id: "night", name: "야간수당", amount: { entries: [], raise: null }, cadence: "PER_SHIFT_TYPE", types: ["야간"], ordinary: false,
          taxable: true, months: [], fixedOvertime: 0, festivals: [], festivalBefore: false },
      ],
    },
  };
}

/** A report body: the app's install id, the company as the sender typed it, and the file. */
export function report(n, extra = {}) {
  return { v: 1, device: device(n), company: "예시정밀", dept: "", verified: false, note: "", setup: setup(), ...extra };
}
