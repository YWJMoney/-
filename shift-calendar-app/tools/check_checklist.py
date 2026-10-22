#!/usr/bin/env python3
"""Reports what is left in a checklist document before a stage ends [PROC-03].

The checklist (계획/Phase2-완료-체크리스트.md, or a version plan 계획/<버전>-…개발계획.md) keeps one
line per task:
  - [x] **25** …     finished
  - [ ] **25** …     open
  - [→ 171] **16** … moved to another line (counts as closed for the stage)
Lines are counted under the ## chapter they sit in. When the document starts with a "지금 숫자"
table (구분 | 끝남 | 옮김 | 남음), each of its rows is compared with the chapters it names and a
mismatch is reported, so the numbers on top never drift from the boxes below.

Usage
  python3 tools/check_checklist.py <checklist.md> [--who C] [--version 1.22.0] [--fail-open] [--quiet]
    --who C          list only open lines whose (판 · 누가) names C (or U, E)
    --version 1.22.0 list only open lines planned for that version
    --fail-open      exit code 1 while any (listed) open line remains: a stage cannot end with one
    --quiet          counts only, no list of open lines
Exit code 1 also when the table on top does not match the boxes.
"""
import pathlib
import re
import sys

BOX_RE = re.compile(r"^\s*- \[(?P<mark>[^\]]*)\]\s*(?P<text>.*)$")
NUM_RE = re.compile(r"\*\*(?P<num>[^*]+)\*\*")
CHAPTER_RE = re.compile(r"^##\s+(?P<title>.+?)\s*$")
WHO_RE = re.compile(r"(?<![A-Za-z])([CUE])(?![A-Za-z])")
VERSION_RE = re.compile(r"\b\d+\.\d+\.\d+\b")
TABLE_ROW_RE = re.compile(r"^\|(?P<cells>.+)\|\s*$")


def kind_of(mark):
    """'done', 'open' or 'moved' for what is inside the [ ]; None when it is not a checklist box."""
    m = mark.strip()
    if m.lower() == "x":
        return "done"
    if m == "":
        return "open"
    if m.startswith("→") or m.startswith("->"):
        return "moved"
    return None


def first_paren(text):
    """The first (…) after the line's number: where the checklist keeps 판 and 누가."""
    depth, start = 0, None
    for i, ch in enumerate(text):
        if ch == "(":
            if depth == 0:
                start = i + 1
            depth += 1
        elif ch == ")" and depth:
            depth -= 1
            if depth == 0:
                return text[start:i]
    return ""


def parse(text):
    """Chapters in order, each with its boxes; and the rows of the 지금 숫자 table if there is one."""
    chapters = [{"title": "(장 없음)", "boxes": []}]
    table = []
    in_numbers = False
    for no, line in enumerate(text.splitlines(), start=1):
        ch = CHAPTER_RE.match(line)
        if ch and not line.startswith("###"):
            title = ch.group("title")
            chapters.append({"title": title, "boxes": []})
            in_numbers = "지금 숫자" in title
            continue
        if in_numbers:
            row = TABLE_ROW_RE.match(line)
            if row:
                cells = [c.strip() for c in row.group("cells").split("|")]
                nums = [c.strip("* ") for c in cells[1:4]]
                if len(cells) >= 4 and all(n.isdigit() for n in nums):
                    table.append({"label": cells[0].strip("* "), "done": int(nums[0]), "moved": int(nums[1]), "open": int(nums[2])})
            continue
        box = BOX_RE.match(line)
        if not box:
            continue
        kind = kind_of(box.group("mark"))
        if kind is None:
            continue
        rest = box.group("text")
        num = NUM_RE.search(rest)
        after = rest[num.end():] if num else rest
        paren = first_paren(after)
        chapters[-1]["boxes"].append({
            "line": no,
            "kind": kind,
            "num": num.group("num").strip() if num else "",
            "text": (rest[:num.start()] + after).strip() if num else rest,
            "who": sorted(set(WHO_RE.findall(paren))),
            "versions": VERSION_RE.findall(paren),
        })
    return [c for c in chapters if c["boxes"] or c["title"] != "(장 없음)"], table


def counts(boxes):
    out = {"done": 0, "moved": 0, "open": 0}
    for b in boxes:
        out[b["kind"]] += 1
    return out


def chapters_for(row, chapters):
    """The chapters a table row speaks for: 'Phase 0 (R, R2, M14)' → the chapter titled 'Phase 0: …'."""
    label = row["label"]
    if label.startswith("합계"):
        return [c for c in chapters if c["boxes"]]
    stem = label.split("(")[0].strip()
    found = [c for c in chapters if stem and stem in c["title"]]
    if found:
        return found
    tags = [t.strip() for t in first_paren(label).split(",") if t.strip()]
    return [c for c in chapters if any(re.search(rf"(?<![A-Za-z0-9]){re.escape(t)}(?![0-9])", c["title"]) for t in tags)]


def check_table(table, chapters):
    """Rows of the 지금 숫자 table whose numbers differ from the boxes, as messages."""
    problems = []
    for row in table:
        found = chapters_for(row, chapters)
        if not found:
            problems.append(f"표의 '{row['label']}' 줄이 가리키는 장을 찾지 못했어요")
            continue
        real = counts([b for c in found for b in c["boxes"]])
        for key, name in (("done", "끝남"), ("moved", "옮김"), ("open", "남음")):
            if real[key] != row[key]:
                problems.append(f"표의 '{row['label']}' {name} {row[key]} ≠ 체크 칸 {real[key]}")
    return problems


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    def opt(name):
        return argv[argv.index(name) + 1] if name in argv and argv.index(name) + 1 < len(argv) else None
    who, version = opt("--who"), opt("--version")
    paths = [a for a in args if a not in (who, version)]
    if not paths:
        print(__doc__)
        return 2
    path = pathlib.Path(paths[0])
    chapters, table = parse(path.read_text(encoding="utf-8"))
    every = [b for c in chapters for b in c["boxes"]]
    total = counts(every)
    print(f"{path.name}: 끝남 {total['done']}, 옮김 {total['moved']}, 남음 {total['open']} (체크 칸 {len(every)}줄)")
    for c in chapters:
        if c["boxes"]:
            n = counts(c["boxes"])
            print(f"  {c['title'][:40]:40} 끝남 {n['done']:3}  옮김 {n['moved']:2}  남음 {n['open']:3}")

    listed = [b for b in every if b["kind"] == "open"
              and (who is None or who in b["who"])
              and (version is None or version in b["versions"])]
    if "--quiet" not in argv:
        what = " · ".join(x for x in (version, who and f"누가 {who}") if x)
        print(f"남은 줄{f' ({what})' if what else ''} {len(listed)}개")
        for b in listed:
            label = f"{b['num']:>8}" if b["num"] else " " * 8
            print(f"  {b['line']:4}행 {label}  {re.sub(r'[*`]', '', b['text'])[:90]}")

    problems = check_table(table, chapters)
    for p in problems:
        print("오류:", p)
    if problems:
        return 1
    if "--fail-open" in argv and listed:
        print(f"남은 줄이 {len(listed)}개라 단계를 끝낼 수 없어요")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
