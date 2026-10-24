#!/usr/bin/env python3
"""[PERF-04] How big the release files are, what makes them big, and whether they stay inside the budget.

Prints a Markdown table (for $GITHUB_STEP_SUMMARY) with each file's size and its largest entries, and
fails (exit code 1) when a file is over its budget. The budgets are the 1.23.0 sizes plus about 20 %:
a larger file is a decision (write it here), not an accident.

Usage: python3 tools/app_size.py <file.apk|file.aab> … [--top 15] [--json out.json]
"""
import json
import os
import sys
import zipfile

# Bytes. Measured 2026-10-23 on 1.23 (OCR model no longer bundled, Korean only): APK 7.75 MB, AAB 19.4 MB.
BUDGETS = {".apk": 9_500_000, ".aab": 23_500_000}


def entries(path):
    with zipfile.ZipFile(path) as z:
        return sorted(((i.compress_size, i.file_size, i.filename) for i in z.infolist()), reverse=True)


def main(argv):
    top, out_json, files = 15, None, []
    i = 0
    while i < len(argv):
        if argv[i] == "--top":
            top = int(argv[i + 1])
            i += 2
        elif argv[i] == "--json":
            out_json = argv[i + 1]
            i += 2
        else:
            files.append(argv[i])
            i += 1
    if not files:
        print(__doc__)
        return 2
    over, report = [], {}
    for f in files:
        size = os.path.getsize(f)
        budget = BUDGETS.get(os.path.splitext(f)[1])
        report[os.path.basename(f)] = size
        mark = "" if budget is None or size <= budget else " **over budget**"
        print(f"### {os.path.basename(f)}: {size / 1e6:.2f} MB" + (f" (budget {budget / 1e6:.1f} MB)" if budget else "") + mark)
        print("\n| entry | packed KB | raw KB |\n| --- | ---: | ---: |")
        for packed, raw, name in entries(f)[:top]:
            print(f"| {name} | {packed / 1e3:.0f} | {raw / 1e3:.0f} |")
        print()
        if mark:
            over.append(f"{f} {size} > {budget}")
    if out_json:
        with open(out_json, "w", encoding="utf-8") as out:
            json.dump(report, out, indent=1)
    for o in over:
        print("over budget:", o, file=sys.stderr)
    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
