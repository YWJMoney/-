#!/usr/bin/env python3
"""Checks docs/tracker.csv so nothing in the development plan is dropped [PROC-02].

Rules
  1. Every row has a unique ID like PAY-03 and a known status.
  2. A row marked 완료 names its evidence (file, test, screenshot or commit).
  3. A conditional row (조건부) names the gate that decides it, e.g. G-PAY-OCR.
  4. Every [ID] tag in the code or commit messages exists in the tracker.
  5. With --phase N, lists that phase's rows that are not finished yet.

Usage: python3 tools/check_tracker.py [--phase 0]   (exit code 1 when a rule fails)
"""
import csv
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TRACKER = ROOT / "docs" / "tracker.csv"
STATUSES = {"대기", "진행 중", "완료", "조건부", "보류", "하지 않음", "이관"}
ID_RE = re.compile(r"^[A-Z][A-Z0-9]*-\d{2}$")
TAG_RE = re.compile(r"\[((?:[A-Z][A-Z0-9]*-\d{2})(?:,\s*[A-Z][A-Z0-9]*-\d{2})*)\]")
SOURCE_GLOBS = ("app/src/**/*.kt", "core/src/**/*.kt", "app/src/**/*.xml", "*.kts", "app/*.kts")


def tags_in(text):
    for group in TAG_RE.findall(text):
        for tag in re.split(r",\s*", group):
            yield tag


def main(argv):
    phase = argv[argv.index("--phase") + 1] if "--phase" in argv else None
    rows = list(csv.DictReader(TRACKER.open(encoding="utf-8")))
    errors = []
    seen = set()
    for r in rows:
        rid = r["id"].strip()
        if not ID_RE.match(rid):
            errors.append(f"{rid or '(빈 ID)'}: ID 형식이 아니에요")
        if rid in seen:
            errors.append(f"{rid}: ID가 두 번 있어요")
        seen.add(rid)
        if r["status"] not in STATUSES:
            errors.append(f"{rid}: 알 수 없는 상태 '{r['status']}'")
        if r["status"] == "완료" and not r["evidence"].strip():
            errors.append(f"{rid}: 완료인데 근거가 없어요")
        if r["status"] == "조건부" and not r["gate"].strip():
            errors.append(f"{rid}: 조건부인데 게이트가 없어요")

    used = {}
    for pattern in SOURCE_GLOBS:
        for path in ROOT.glob(pattern):
            if "/build/" in str(path):
                continue
            for tag in tags_in(path.read_text(encoding="utf-8", errors="ignore")):
                used.setdefault(tag, str(path.relative_to(ROOT)))
    try:
        log = subprocess.run(["git", "log", "--format=%B"], cwd=ROOT, capture_output=True, text=True).stdout
        for tag in tags_in(log):
            used.setdefault(tag, "git log")
    except OSError:
        pass
    for tag, where in sorted(used.items()):
        if tag not in seen:
            errors.append(f"{tag}: {where}에서 쓰는데 추적표에 없어요")

    by_status = {}
    for r in rows:
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1
    print(f"추적표 {len(rows)}행: " + ", ".join(f"{k} {v}" for k, v in sorted(by_status.items())))
    if phase is not None:
        open_rows = [r for r in rows if r["phase"].split("(")[0].strip().startswith(phase)
                     and r["status"] not in ("완료", "하지 않음", "이관")]
        print(f"Phase {phase} 남은 항목 {len(open_rows)}개")
        for r in open_rows:
            print(f"  {r['id']:9} {r['status']:5} {r['requirement'][:60]}")
    for e in errors:
        print("오류:", e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
