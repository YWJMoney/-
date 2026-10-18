// [COMM-01][COMM-02][COMM-03] Company search, reports and adoption on an in-memory D1: `npm test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { normName, personal, cleanSetup, readReport, fingerprint, describe as words } from "../src/index.js";
import { server, setup, report, device, from } from "./helpers.mjs";

const sha = (s) => createHash("sha256").update(s).digest("hex");

/** The same setup under other names, colors and rotation phase. */
function renamed(s) {
  const names = { 주간: "A조 주", 야간: "A조 야", 휴무: "쉼" };
  const n = (x) => (x === null ? null : names[x]);
  s.shiftTypes = s.shiftTypes.map((t) => ({ ...t, name: names[t.name], label: "x", color: 0, icon: null })).reverse();
  s.rotation = { anchor: "2027-01-03", steps: [...s.rotation.steps.slice(3), ...s.rotation.steps.slice(0, 3)].map(n) };
  s.extraKindShiftSets = { 3: s.extraKindShiftSets[3].map(n) };
  s.pay.items = s.pay.items.map((i) => ({ ...i, types: i.types.map(n) })).reverse();
  // A kind of the company's own has another id on another phone.
  s.extraKinds = s.extraKinds.replace("101|조기출근|조|", "205|조출|출|");
  s.pay.rules.extra = { 205: s.pay.rules.extra[101], 3: s.pay.rules.extra[3] };
  return s;
}

test("names are found however they are written", () => {
  assert.equal(normName("(주)예시정밀"), "예시정밀");
  assert.equal(normName("주식회사 예시 정밀"), "예시정밀");
  assert.equal(normName("예시정밀 (株)"), "예시정밀");
  assert.equal(normName("Yesi Precision Co., Ltd."), "yesiprecision");
  assert.equal(normName("ＹＥＳＩ　정밀"), "yesi정밀");
});

test("contacts and money in text are caught; shift words are not", () => {
  for (const s of ["me@example.com", "연락 주세요 a.b@mail.co.kr"]) assert.equal(personal(s), "email", s);
  for (const s of ["010-1234-5678", "01012345678", "+82 10 1234 5678", "02-123-4567"]) assert.equal(personal(s), "phone", s);
  for (const s of ["기본급 250만원", "3,000,000", "2500000", "이백만 원", "300 won", "시급 9860원"]) assert.equal(personal(s), "amount", s);
  for (const s of ["4조 2교대", "주주야야휴휴", "야간 19:00~07:00", "8시간에 기본급 10%", "2026년 10월", "생산 2팀", "180시간"]) {
    assert.equal(personal(s), null, s);
  }
});

test("a report keeps only what everyone at the company shares", () => {
  const raw = setup();
  raw.pay.base = { entries: [{ from: "2026-01-01", value: 2600000 }] };
  raw.pay.hireDate = "2020-03-02";
  raw.pay.rules.annualLeave = 15;
  raw.pay.rules.leaveCarry = 3;
  raw.alarms = [{ at: "05:30" }];
  const r = readReport(report(1, { company: " (주)예시정밀 ", dept: "생산 2팀", note: "주주야야휴휴로 돌아요", setup: raw }));
  assert.equal(r.company, "(주)예시정밀");
  assert.equal(r.deptNorm, "생산2팀");
  assert.equal(r.setup.company, "");
  assert.equal(r.setup.pay.base, undefined);
  assert.equal(r.setup.pay.hireDate, undefined);
  assert.equal(r.setup.alarms, undefined);
  assert.equal(r.setup.pay.rules.annualLeave, null);
  assert.equal(r.setup.pay.rules.leaveCarry, 0);
  // What the app reads back is all there.
  assert.deepEqual(r.setup.rotation, raw.rotation);
  assert.deepEqual(r.setup.pay.items.map((i) => i.name), ["상여", "자격수당", "야간수당"]);
  assert.equal(r.setup.pay.items[0].percentOfBase, 1);
  assert.equal("percentOfBase" in r.setup.pay.items[1], false);
  assert.deepEqual(r.setup.extraKindShiftSets, { 3: ["주간", "야간"] });
  assert.deepEqual(Object.keys(r.setup.pay.rules.extra).sort(), ["101", "3"]);
  assert.equal(r.setup.pay.rules.extra[3].adds[0].scope, "over:480");
});

test("anything carrying money is refused, not dropped", () => {
  const refused = (change, code, field) => {
    const s = setup();
    change(s);
    assert.throws(() => readReport(report(1, { setup: s })), (e) => e.code === code && e.field === field);
  };
  refused((s) => { s.pay.withAmounts = true; }, "amount", "pay");
  refused((s) => { s.pay.rules.extra[3].amount = 50000; }, "amount", "rules");
  refused((s) => { s.pay.rules.extra[3].bonus = 10000; }, "amount", "rules");
  refused((s) => { s.pay.items[1].amount = { entries: [{ from: "2026-01-01", value: 30000 }], raise: null }; }, "amount", "items");
  refused((s) => { s.pay.items[1].amount = { entries: [], raise: { month: 3, percent: 0.03 } }; }, "amount", "items");
  refused((s) => { s.shiftTypes.push({ name: "교육 2,500,000", start: "09:00", end: "18:00", break: 60, off: false }); }, "amount", "shiftTypes");
  refused((s) => { s.pay.items[1].name = "010-1234-5678"; }, "phone", "items");
  refused((s) => { s.v = 4; }, "setup_version", "v");
  refused((s) => { s.format = "bamnat-backup"; }, "setup", "format");
  refused((s) => { s.rotation.steps[0] = "오후"; }, "setup", "rotation");
  refused((s) => { s.shiftTypes[0].start = "7시"; }, "setup", "shiftTypes");
  refused((s) => { s.pay.rules.extra[3].adds[0].scope = "sometimes"; }, "setup", "rules");
});

test("newer pay choices travel when set, and a file without them stays version 2", async () => {
  // [PAYS-03] 감시·단속 승인, 5인 미만 공휴일, 유급 휴게 and an allowance paid instead of the night premium.
  const s = setup();
  s.v = 3;
  s.pay.fiveOrMore = false;
  Object.assign(s.pay.rules, { supervisory: true, holidaysU5: true, breakPay: "AS_WORK" });
  s.pay.items[2].covers = ["NIGHT"];
  const clean = cleanSetup(s);
  assert.equal(clean.v, 3);
  assert.equal(clean.pay.rules.supervisory, true);
  assert.equal(clean.pay.rules.holidaysU5, true);
  assert.equal(clean.pay.rules.breakPay, "AS_WORK");
  assert.deepEqual(clean.pay.items[2].covers, ["NIGHT"]);
  const lines = Object.fromEntries(words(clean));
  assert.equal(lines["사업장"], "5인 미만 · 월 180시간 · 감시·단속 승인");
  assert.equal(lines["공휴일"], "유급휴일로 줌 (5인 미만)");
  assert.equal(lines["휴게"], "일한 시간으로 침");
  assert.match(lines["수당"], /야간수당\(야간마다 야간 가산 대신\)/);

  // Choices left as the app assumes anyway are dropped: the file reads as version 2, with the same fingerprint.
  const plain = setup();
  plain.v = 3;
  Object.assign(plain.pay.rules, { supervisory: false, holidaysU5: false, breakPay: "UNPAID" });
  plain.pay.items[1].covers = [];
  const same = cleanSetup(plain);
  assert.equal(same.v, 2);
  for (const k of ["supervisory", "holidaysU5", "breakPay"]) assert.equal(k in same.pay.rules, false, k);
  assert.equal("covers" in same.pay.items[1], false);
  assert.equal(await fingerprint(same), await fingerprint(cleanSetup(setup())));
  assert.notEqual(await fingerprint(clean), await fingerprint(same));

  const bad = (change, field) => {
    const t = setup();
    change(t);
    assert.throws(() => cleanSetup(t), (e) => e.code === "setup" && e.field === field);
  };
  bad((t) => { t.pay.rules.breakPay = "SOMETIMES"; }, "rules");
  bad((t) => { t.pay.items[0].covers = ["BASE"]; }, "items");
  bad((t) => { t.pay.rules.supervisory = "yes"; }, "rules");
});

test("kinds of extra work are kept only when the file lists them", () => {
  const s = setup();
  s.pay.rules.extra = { COVER: s.pay.rules.extra[3], 777: s.pay.rules.extra[101] };
  s.pay.rules.publicHolidayAs = "777";
  s.extraKinds += "\n";
  const clean = cleanSetup(s);
  assert.deepEqual(Object.keys(clean.pay.rules.extra), ["3"]);
  assert.equal(clean.pay.rules.publicHolidayAs, undefined);
  // The shifts a 대근 covers travel by name; ids on the sender's phone do not.
  const t = setup();
  t.extraKinds = t.extraKinds.replace("S,-", "S,4;5");
  assert.match(cleanSetup(t).extraKinds, /\|S,-\|/);
});

test("the same rule is the same however it is named or wherever the rotation starts", async () => {
  const a = await fingerprint(cleanSetup(setup()));
  assert.equal(await fingerprint(cleanSetup(renamed(setup()))), a);
  const later = setup();
  later.shiftTypes[0].end = "18:00";
  assert.notEqual(await fingerprint(cleanSetup(later)), a);
  const cover = setup();
  cover.pay.rules.extra[3].percent = 0.12;
  assert.notEqual(await fingerprint(cleanSetup(cover)), a);
  const order = setup();
  order.rotation.steps = ["주간", "주간", "야간", "야간", "휴무", "휴무", "휴무", "휴무"];
  assert.notEqual(await fingerprint(cleanSetup(order)), a);
});

test("a setup reads as plain Korean for the developer", () => {
  const lines = Object.fromEntries(words(cleanSetup(setup())));
  assert.equal(lines["근무"], "주간 07:00~19:00 휴게 60분 · 야간 19:00~07:00 휴게 60분 · 휴무(쉬는 날)");
  assert.equal(lines["순서 8일"], "주간 주간 휴무 휴무 야간 야간 휴무 휴무");
  assert.equal(lines["연장·휴무일 근무"], "잔업(근무 뒤 2시간) · 특근(09:00~18:00) · 대근(주간·야간 대신) · 조기출근(근무 앞 1시간)");
  assert.equal(lines["대근"], "8시간에 기본급 10% + 연장 가산 50% (8시간 넘게) + 야간 가산 50%");
  assert.equal(lines["공휴일 근무"], "대근 방식");
  assert.equal(lines["수당"], "상여(2·4·6·8·10·12월 기본급 100%) · 자격수당(매달) · 야간수당(야간마다)");
});

test("a report waits for coworkers, then is adopted by two people", async () => {
  const { call } = server();
  const first = await call("POST", "/api/reports", report(1, { company: "(주)예시정밀", verified: true }), from(1));
  assert.equal(first.status, 201);
  assert.equal(first.data.status, "pending");
  assert.equal(first.data.points, 2);
  assert.equal(first.data.persons, 1);
  assert.equal(first.data.threshold, 3);
  assert.equal(first.data.reward, null);
  assert.equal(first.data.rewardMonths, 3);
  assert.match(first.data.id, /^[A-Za-z0-9]{22}$/);
  assert.match(first.data.setup, /^[0-9a-f]{16}$/);

  const second = await call("POST", "/api/reports", report(2, { company: "예시 정밀" }), from(2));
  assert.equal(second.data.status, "adopted");
  assert.equal(second.data.company.id, first.data.company.id);
  assert.equal(second.data.points, 3);
  assert.equal(second.data.persons, 2);
  assert.deepEqual(second.data.reward, { months: 3, status: "owed" });

  const mine = await call("GET", `/api/reports/${first.data.id}`);
  assert.equal(mine.data.status, "adopted");
  assert.deepEqual(mine.data.reward, { months: 3, status: "owed" });
  assert.ok(mine.data.decidedAt);

  // The next coworker joins the rule at once.
  const third = await call("POST", "/api/reports", report(3, { setup: renamed(setup()) }), from(3));
  assert.equal(third.data.status, "adopted");
  assert.equal(third.data.persons, 3);
});

test("one person alone never adopts, however often they send", async () => {
  const { call } = server();
  const a = await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  const again = await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  assert.equal(again.status, 200);
  assert.equal(again.data.again, true);
  assert.equal(again.data.id, a.data.id);
  assert.equal(again.data.points, 2);
  assert.equal(again.data.persons, 1);
});

test("coworkers on one network count as people, but only another network adopts", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T10:59:00Z") });
  const { call } = server();
  await call("POST", "/api/reports", report(1, { verified: true }), from(7));
  t.mock.timers.tick(2 * 60 * 1000);
  const b = await call("POST", "/api/reports", report(2), from(7));
  assert.deepEqual([b.data.persons, b.data.points, b.data.status, b.data.review], [2, 3, "pending", true]);
  const c = await call("POST", "/api/reports", report(3), from(8));
  assert.deepEqual([c.data.persons, c.data.status], [3, "adopted"]);
});

test("network links are forgotten within two hours", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T10:00:00Z") });
  const { call, DB } = server();
  await call("POST", "/api/reports", report(1, { verified: true }), from(7));
  t.mock.timers.tick(3 * 60 * 60 * 1000);
  const b = await call("POST", "/api/reports", report(2), from(7));
  assert.equal(b.data.persons, 2);
  assert.equal(b.data.status, "adopted");
  const nets = DB.db.prepare("SELECT net FROM reports ORDER BY created_at").all().map((r) => r.net);
  assert.equal(nets[0], null);
  assert.match(nets[1], /^[0-9a-f]{32}$/);
});

test("nothing that points at a person is stored", async () => {
  const { call, DB } = server();
  await call("POST", "/api/reports", report(1), { "cf-connecting-ip": "203.0.113.77" });
  await call("POST", "/api/votes", { device: device(2), company: "AAAAAAAAAAAA", setup: "0123456789abcdef", ok: true },
    { "cf-connecting-ip": "203.0.113.78" });
  const all = DB.dump();
  assert.ok(!all.includes("203.0.113"), "no address");
  assert.ok(!all.includes(device(1)), "no install id");
  assert.ok(all.includes(sha(`device|${device(1)}`).slice(0, 40)), "the install as a hash");
});

test("search shows a setup still being checked only for its exact name", async () => {
  const { call } = server();
  await call("POST", "/api/reports", report(1, { company: "(주)예시정밀" }), from(1));
  assert.deepEqual((await call("GET", "/api/companies?q=예시")).data.companies, []);
  const exact = (await call("GET", `/api/companies?q=${encodeURIComponent("예시 정밀 주식회사")}`)).data.companies;
  assert.equal(exact.length, 1);
  assert.equal(exact[0].name, "(주)예시정밀");
  assert.equal(exact[0].adopted, 0);
  assert.equal(exact[0].pending, 1);
  assert.deepEqual((await call("GET", "/api/companies?q=예")).data.companies, []);

  await call("POST", "/api/reports", report(2, { verified: true }), from(2));
  const partial = (await call("GET", "/api/companies?q=예시")).data.companies;
  assert.equal(partial.length, 1);
  assert.equal(partial[0].adopted, 1);
});

test("a company shows its adopted rule first and what is still being checked", async () => {
  const { call } = server();
  const a = await call("POST", "/api/reports", report(1, { company: "(주)예시정밀", verified: true }), from(1));
  await call("POST", "/api/reports", report(2), from(2));
  const other = setup();
  other.shiftTypes[0].end = "18:00";
  await call("POST", "/api/reports", report(3, { setup: other, dept: "물류" }), from(3));

  const c = await call("GET", `/api/companies/${a.data.company.id}`);
  assert.equal(c.status, 200);
  assert.equal(c.data.name, "(주)예시정밀");
  assert.deepEqual(c.data.setups.map((s) => [s.status, s.dept, s.persons]), [["adopted", "", 2], ["pending", "물류", 1]]);
  const adopted = c.data.setups[0];
  assert.equal(adopted.version, 1);
  assert.equal(adopted.setup.company, "(주)예시정밀");
  assert.equal(adopted.setup.shiftTypes[0].name, "주간");
  assert.equal(adopted.id, a.data.setup);
  // In plain words, as the app shows it before anyone applies it.
  assert.deepEqual(adopted.lines[1], ["순서 8일", "주간 주간 휴무 휴무 야간 야간 휴무 휴무"]);
  assert.deepEqual(c.data.setups[1].lines[0], ["근무", "주간 07:00~18:00 휴게 60분 · 야간 19:00~07:00 휴게 60분 · 휴무(쉬는 날)"]);
  assert.equal((await call("GET", "/api/companies/AAAAAAAAAAAA")).status, 404);
});

test("a rule in use is changed only by the developer", async () => {
  const { call } = server();
  await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  await call("POST", "/api/reports", report(2), from(2));
  const other = setup();
  other.pay.rules.extra[3].percent = 0.12;
  const sent = [];
  for (const n of [3, 4, 5]) sent.push(await call("POST", "/api/reports", report(n, { setup: other, verified: true }), from(n)));
  assert.equal(sent[2].data.points, 6);
  assert.equal(sent[2].data.status, "pending");
  const c = await call("GET", `/api/companies/${sent[0].data.company.id}`);
  assert.deepEqual(c.data.setups.map((s) => s.status), ["adopted", "pending"]);
});

test("a sender's newer setup takes the place of their older one", async () => {
  const { call } = server();
  const a = await call("POST", "/api/reports", report(1), from(1));
  const other = setup();
  other.pay.monthlyHours = 209;
  const b = await call("POST", "/api/reports", report(1, { setup: other }), from(1));
  assert.notEqual(b.data.setup, a.data.setup);
  assert.equal((await call("GET", `/api/reports/${a.data.id}`)).data.status, "replaced");
  assert.equal((await call("GET", `/api/reports/${b.data.id}`)).data.status, "pending");
});

test("맞았어요 adds a coworker; 달라요 takes theirs away", async () => {
  const { call } = server();
  const a = await call("POST", "/api/reports", report(1), from(1));
  const body = (n, extra) => ({ device: device(n), company: a.data.company.id, setup: a.data.setup, ...extra });

  let v = await call("POST", "/api/votes", body(2, { ok: true }), from(2));
  assert.equal(v.status, 200);
  assert.deepEqual([v.data.status, v.data.persons, v.data.points], ["pending", 2, 2]);
  // A 달라요 from someone who said 맞았어요 replaces it.
  v = await call("POST", "/api/votes", body(2, { ok: false, why: "old" }), from(2));
  assert.deepEqual([v.data.persons, v.data.points, v.data.bad], [1, 1, 1]);
  v = await call("POST", "/api/votes", body(3, { ok: true, verified: true }), from(3));
  // [COMM-07] 3 points, but 2 of 3 voters (67%) are fewer than the 75% it takes on its own.
  assert.deepEqual([v.data.status, v.data.persons, v.data.points, v.data.agree, v.data.trust], ["pending", 2, 3, 67, 67]);
  v = await call("POST", "/api/votes", body(6, { ok: true }), from(6));
  assert.deepEqual([v.data.status, v.data.persons, v.data.points, v.data.agree, v.data.trust], ["adopted", 3, 4, 75, 75]);
  // Votes on a rule in use are kept for the developer.
  v = await call("POST", "/api/votes", body(4, { ok: false, why: "nonsense" }), from(4));
  assert.deepEqual([v.data.status, v.data.bad], ["adopted", 2]);

  assert.equal((await call("POST", "/api/votes", body(5, { ok: true, setup: "0123456789abcdef" }), from(5))).status, 404);
  assert.equal((await call("POST", "/api/votes", body(5, { ok: true, setup: "nope" }), from(5))).data.error, "setup");
  assert.equal((await call("POST", "/api/votes", body(5, {}), from(5))).data.error, "ok");
  assert.equal((await call("POST", "/api/votes", { ...body(5, { ok: true }), device: "short" }, from(5))).data.error, "device");
});

test("a sender takes a report back, with its reward", async () => {
  const { call, DB } = server();
  const a = await call("POST", "/api/reports", report(1, { verified: true }), from(1));
  await call("POST", "/api/reports", report(2), from(2));
  const path = `/api/reports/${a.data.id}`;
  assert.equal((await call("DELETE", path)).status, 403);
  assert.equal((await call("DELETE", path, undefined, { "x-device": device(2) })).status, 403);
  assert.equal((await call("DELETE", path, undefined, { "x-device": device(1) })).status, 204);
  assert.equal((await call("GET", path)).status, 404);
  assert.equal(DB.db.prepare("SELECT COUNT(*) AS n FROM rewards").get().n, 1);
  assert.equal((await call("DELETE", path, undefined, { "x-device": device(1) })).status, 404);
});

test("everything one install sent can be deleted", async () => {
  const { call, DB } = server();
  const a = await call("POST", "/api/reports", report(1), from(1));
  await call("POST", "/api/reports", report(1, { company: "다른회사" }), from(1));
  const b = await call("POST", "/api/reports", report(2, { company: "세번째회사" }), from(2));
  await call("POST", "/api/votes", { device: device(1), company: b.data.company.id, setup: b.data.setup, ok: true }, from(1));
  assert.equal((await call("DELETE", "/api/mine")).status, 400);
  const gone = await call("DELETE", "/api/mine", undefined, { "x-device": device(1) });
  assert.deepEqual(gone.data, { reports: 2, votes: 1, posts: 0 });
  assert.ok(!DB.dump().includes(sha(`device|${device(1)}`).slice(0, 40)));
  assert.equal((await call("GET", `/api/reports/${a.data.id}`)).status, 404);
});

test("contacts and amounts in what is typed are refused with the field", async () => {
  const { call } = server();
  const cases = [
    [{ note: "궁금하면 010-1234-5678로 연락 주세요" }, "phone", "note"],
    [{ note: "me@example.com" }, "email", "note"],
    [{ note: "기본급 250만원 기준이에요" }, "amount", "note"],
    [{ dept: "3,000,000" }, "amount", "dept"],
    [{ company: "(주)" }, "company", "company"],
    [{ company: "가".repeat(41) }, "company", "company"],
    [{ note: "가".repeat(501) }, "note", "note"],
    [{ verified: "yes" }, "verified", "verified"],
    [{ v: 2 }, "version", "v"],
    [{ device: "x" }, "device", "device"],
  ];
  for (const [change, error, field] of cases) {
    const res = await call("POST", "/api/reports", report(1, change));
    assert.equal(res.status, 400, JSON.stringify(change));
    assert.deepEqual(res.data, { error, field }, JSON.stringify(change));
  }
  const ok = await call("POST", "/api/reports", report(1, { note: "주주야야휴휴, 야간 19~07시. 대근은 8시간에 기본급 10%예요" }));
  assert.equal(ok.status, 201);
});

test("a way the app cannot set yet goes to the developer only", async () => {
  const { call } = server();
  const note = "어떻게: 대근 8시간에 기본급의 10%, 8시간을 넘기면 0.5 가산\n예시: 야간 대근 12시간이면 기본급의 15%";
  const ask = await call("POST", "/api/reports", report(1, { ask: true, note }), from(1));
  assert.equal(ask.status, 201);
  assert.deepEqual([ask.data.status, ask.data.ask, ask.data.points, ask.data.persons, ask.data.review, ask.data.reason],
    ["ask", true, 0, 0, false, null]);
  // Sent again as it is, it is the same one.
  const again = await call("POST", "/api/reports", report(1, { ask: true, note }), from(1));
  assert.deepEqual([again.status, again.data.id, again.data.again], [200, ask.data.id, true]);
  // It never counts for the setup it carries, and coworkers are not offered it.
  const b = await call("POST", "/api/reports", report(2, { verified: true }), from(2));
  assert.deepEqual([b.data.status, b.data.persons, b.data.points], ["pending", 1, 2]);
  const found = (await call("GET", "/api/companies?q=예시정밀")).data.companies;
  assert.deepEqual(found.map((c) => [c.adopted, c.pending]), [[0, 1]]);
  assert.equal((await call("GET", `/api/companies/${b.data.company.id}`)).data.setups.length, 1);
  // The sender's own report of the setup is separate from what they asked.
  const own = await call("POST", "/api/reports", report(1), from(1));
  assert.deepEqual([own.data.status, own.data.persons], ["adopted", 2]);
  assert.equal((await call("GET", `/api/reports/${ask.data.id}`)).data.status, "ask");
  // Without a word on what is missing there is nothing to read; money is refused as in any note.
  assert.deepEqual((await call("POST", "/api/reports", report(3, { ask: true }), from(3))).data, { error: "note", field: "note" });
  assert.deepEqual((await call("POST", "/api/reports", report(3, { ask: "yes", note }), from(3))).data, { error: "ask", field: "ask" });
  assert.equal((await call("POST", "/api/reports", report(3, { ask: true, note: "가".repeat(1500) }), from(3))).status, 201);
  assert.equal((await call("POST", "/api/reports", report(4, { ask: true, note: "가".repeat(1501) }), from(4))).data.error, "note");
  assert.equal((await call("POST", "/api/reports", report(4, { note: "가".repeat(501) }), from(4))).data.error, "note");
  assert.deepEqual((await call("POST", "/api/reports", report(4, { ask: true, note: "대근 8시간이면 25만원이에요" }), from(4))).data,
    { error: "amount", field: "note" });
});

test("the app can say what adoption takes and earns", async () => {
  const { call } = server();
  assert.deepEqual((await call("GET", "/api/terms")).data, { threshold: 3, rewardMonths: 3, agree: 75 });
});

test("bodies that are not a report are refused", async () => {
  const { call } = server();
  assert.equal((await call("POST", "/api/reports", report(1, { note: "가".repeat(33 * 1024) }))).status, 413);
  assert.equal((await call("POST", "/api/reports", "{", { "content-type": "application/json" })).data.error, "bad_json");
  assert.equal((await call("POST", "/api/reports", "[]", { "content-type": "application/json" })).data.error, "bad_json");
  assert.equal((await call("POST", "/api/reports", JSON.stringify(report(1)), { "content-type": "text/plain" })).data.error, "content_type");
  assert.equal((await call("GET", "/api/reports/short")).status, 404);
  assert.equal((await call("GET", "/nope")).status, 404);
  const robots = await call("GET", "/robots.txt");
  assert.match(robots.text, /Disallow: \//);
});

test("an install sends at most ten reports a day, a network thirty", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: Date.parse("2026-10-17T01:00:00Z") });
  const { call } = server();
  for (let i = 0; i < 10; i++) {
    assert.equal((await call("POST", "/api/reports", report(1, { company: `회사${i}번` }), from(1))).status, 201);
  }
  assert.equal((await call("POST", "/api/reports", report(1, { company: "회사열한번" }), from(1))).status, 429);
  t.mock.timers.tick(24 * 60 * 60 * 1000);
  assert.equal((await call("POST", "/api/reports", report(1, { company: "회사열한번" }), from(1))).status, 201);

  for (let i = 0; i < 30; i++) {
    assert.equal((await call("POST", "/api/reports", report(100 + i, { company: `망${i}번` }), from(9))).status, 201);
  }
  assert.equal((await call("POST", "/api/reports", report(200, { company: "망서른한번" }), from(9))).status, 429);
});

test("without a database the server says so", async () => {
  const { call } = server({ DB: undefined });
  assert.equal((await call("GET", "/api/companies?q=예시")).status, 503);
  const health = await call("GET", "/api/admin/health");
  assert.deepEqual(health.data, { database: false, password: true, salt: true, mail: false, why: { password: null, salt: null } });
});
