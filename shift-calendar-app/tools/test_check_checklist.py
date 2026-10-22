#!/usr/bin/env python3
"""Tests for tools/check_checklist.py [PROC-03]. Run: python3 tools/test_check_checklist.py"""
import contextlib
import io
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import check_checklist as cc  # noqa: E402

DOC = """# 체크리스트

## 0. 지금 숫자

| 구분 | 끝남 | 옮김 | 남음 | 메모 |
| --- | --- | --- | --- | --- |
| Phase 0 (R, M14) | 1 | 1 | 2 | |
| 상시 첫 준비 (M27) | 0 | 0 | 1 | |
| **합계** | **1** | **1** | **3** | |

## 2. Phase 0: 신뢰성

### M14: 출시

- [x] **21** 알람 점검 (1.15.0)
- [ ] **25** 성능 예산 (1.22.0 · C) — 실기기
- [→ 171] **16** Play 계정 (U)
- [ ] **23** 알람 기기 매트릭스 (U 실기기)

## 5. 상시 운영 (M27)

- [ ] **163** 단계 종료 점검 (1.22.0 · C)
- 158 달마다 점검 — 체크 칸이 아니라 세지 않음

## 7. Phase 3 — 진입 금지

- M23 건강·가족: 119~125
"""


def run(text, *args):
    with tempfile.TemporaryDirectory() as d:
        path = pathlib.Path(d, "list.md")
        path.write_text(text, encoding="utf-8")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cc.main([str(path), *args])
        return code, out.getvalue()


class CheckChecklistTest(unittest.TestCase):
    def test_counts_each_chapter_and_the_table_matches(self):
        chapters, table = cc.parse(DOC)
        by_title = {c["title"]: cc.counts(c["boxes"]) for c in chapters}
        self.assertEqual({"done": 1, "moved": 1, "open": 2}, by_title["2. Phase 0: 신뢰성"])
        self.assertEqual({"done": 0, "moved": 0, "open": 1}, by_title["5. 상시 운영 (M27)"])
        self.assertEqual([], cc.check_table(table, chapters))
        code, out = run(DOC)
        self.assertEqual(0, code)
        self.assertIn("끝남 1, 옮김 1, 남음 3", out)

    def test_reads_who_and_version_from_the_first_brackets(self):
        chapters, _ = cc.parse(DOC)
        box = {b["num"]: b for c in chapters for b in c["boxes"]}
        self.assertEqual(["C"], box["25"]["who"])
        self.assertEqual(["1.22.0"], box["25"]["versions"])
        self.assertEqual(["U"], box["23"]["who"])
        self.assertEqual("moved", box["16"]["kind"])
        self.assertEqual("성능 예산 (1.22.0 · C) — 실기기", box["25"]["text"])

    def test_filters_the_open_lines(self):
        _, out = run(DOC, "--who", "C")
        self.assertIn("남은 줄 (누가 C) 2개", out)
        self.assertNotIn("알람 기기 매트릭스", out)
        _, out = run(DOC, "--who", "U", "--version", "1.22.0")
        self.assertIn("0개", out)

    def test_a_stage_with_open_lines_cannot_end(self):
        self.assertEqual(1, run(DOC, "--fail-open")[0])
        done = DOC.replace("- [ ]", "- [x]").replace("| 1 | 1 | 2 |", "| 3 | 1 | 0 |") \
            .replace("| 0 | 0 | 1 |", "| 1 | 0 | 0 |").replace("**1** | **1** | **3**", "**4** | **1** | **0**")
        code, out = run(done, "--fail-open")
        self.assertEqual(0, code, out)

    def test_a_table_that_drifted_from_the_boxes_fails(self):
        code, out = run(DOC.replace("| Phase 0 (R, M14) | 1 | 1 | 2 |", "| Phase 0 (R, M14) | 2 | 1 | 1 |"))
        self.assertEqual(1, code)
        self.assertIn("'Phase 0 (R, M14)' 끝남 2 ≠ 체크 칸 1", out)
        self.assertIn("'Phase 0 (R, M14)' 남음 1 ≠ 체크 칸 2", out)

    def test_a_plan_without_a_table_is_counted_too(self):
        code, out = run("## 1단계\n\n- [x] **286** 고침\n- [ ] **25** 성능\n", "--quiet")
        self.assertEqual(0, code)
        self.assertIn("끝남 1, 옮김 0, 남음 1", out)
        self.assertNotIn("남은 줄", out.split("\n", 2)[-1])


if __name__ == "__main__":
    unittest.main()
