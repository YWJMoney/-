#!/usr/bin/env python3
"""[QA-06] 330: 43 key screenshots kept as small fingerprints, so a change nobody meant shows in CI.

A fingerprint is the shot shrunk to 32x48 grey pixels (1.5 KB of hex), not the picture itself: no PNGs in git,
and anti-aliasing or a moved clock digit stays under the threshold while a moved card, a missing button or a
different colour scheme does not.

  python3 tools/shot_hashes.py check  app/build/screenshots   # CI: compare the shots this run made
  python3 tools/shot_hashes.py update app/build/screenshots   # after a change that was meant: write new ones

`check` compares only the shots present (CI makes them in two shards) and fails when one differs by more than
THRESHOLD on average (0~255) or when none of the list was found. Since 1.24 (QA-08) every shot is drawn on a fixed
day and time (ScreenshotBase, 2026-10-14 10:30 KST), so shots built around today's date or the hour are in the list
too; random numbers still are not.
"""
import json
import os
import sys

from PIL import Image

GOLDEN = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "golden", "shots.json")
SIZE = (32, 48)
THRESHOLD = 6.0

# 43 screens across the app. 23_widget_today, store_1_calendar (오늘의 흐름 changes with the hour) and 352_folded_teams
# (오늘 N월 N일, the 14 days from today) came back with the fixed clock of 1.24 (QA-08).
SHOTS = [
    "2_folded_day_sheet", "3_folded_stats", "4_folded_settings", "8_unfolded_settings", "9_folded_memos_calendar",
    "13_folded_shift_types", "14_folded_pattern", "24_widget_week", "27_folded_pay_result",
    "28_unfolded_pay_result", "51_folded_money", "53_unfolded_money", "61_folded_setup_style",
    "76_folded_extra_kinds", "82_folded_backup_password", "117_folded_company_detail",
    "134_folded_received_calendar", "140_folded_settings_alarm", "164_phone_calendar_huge_text",
    "190_folded_company_board", "213_folded_calendars_list", "230_folded_ledger_summary", "250_folded_ledger_dark",
    "297_folded_business_calendar", "327_folded_settings_hub", "334_folded_help_home",
    "369_phone_year", "376_phone_pro_launched", "385_phone_pay_report", "430_folded_rest_rules",
    "460_folded_leave", "468_folded_leave_dark", "482_folded_money_customize", "494_folded_settings_backup_photos",
    "520_folded_recommended", "528_phone_settings_seven_groups", "530_folded_settings_privacy_about", "570_j_components_folded",
    "695_n_profile_folded", "store_tablet_year", "23_widget_today", "store_1_calendar", "352_folded_teams",
]


def fingerprint(path):
    im = Image.open(path).convert("L").resize(SIZE, Image.BILINEAR)
    return im.tobytes().hex()


def distance(a, b):
    x, y = bytes.fromhex(a), bytes.fromhex(b)
    return sum(abs(p - q) for p, q in zip(x, y)) / len(x)


def main(argv):
    if len(argv) != 2 or argv[0] not in ("check", "update"):
        print(__doc__)
        return 2
    mode, folder = argv
    if not os.path.isdir(folder):
        print(f"no screenshots folder at {folder}: the screenshot tests did not run (or Gradle took them from the "
              "build cache without their output folder — app/build.gradle.kts declares it as an output)")
        return 1
    present = {n[:-4]: os.path.join(folder, n) for n in os.listdir(folder) if n.endswith(".png")}
    if mode == "update":
        golden = {}
        if os.path.exists(GOLDEN):
            golden = json.load(open(GOLDEN, encoding="utf-8"))
        for name in SHOTS:
            if name in present:
                golden[name] = fingerprint(present[name])
        golden = {k: golden[k] for k in SHOTS if k in golden}
        os.makedirs(os.path.dirname(GOLDEN), exist_ok=True)
        with open(GOLDEN, "w", encoding="utf-8") as out:
            json.dump(golden, out, indent=0, sort_keys=True)
            out.write("\n")
        missing = [s for s in SHOTS if s not in golden]
        print(f"wrote {len(golden)} fingerprints" + (f"; not found: {missing}" if missing else ""))
        return 0
    golden = json.load(open(GOLDEN, encoding="utf-8"))
    seen, bad = 0, []
    print("| shot | difference |\n| --- | ---: |")
    for name, want in sorted(golden.items()):
        if name not in present:
            continue
        seen += 1
        d = distance(want, fingerprint(present[name]))
        print(f"| {name} | {d:.2f}{' **changed**' if d > THRESHOLD else ''} |")
        if d > THRESHOLD:
            bad.append(name)
    if seen == 0:
        print("none of the key screenshots was found", file=sys.stderr)
        return 1
    for name in bad:
        print(f"changed: {name} (if it was meant, run: python3 tools/shot_hashes.py update app/build/screenshots)", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
