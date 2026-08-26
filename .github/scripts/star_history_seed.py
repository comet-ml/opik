#!/usr/bin/env python3
"""One-off: build the initial star-history series from a full stargazer pull.

Already run for comet-ml/opik on 2026-08-26 (21,616 stargazers, 1,164 daily
points). Kept so the seed is reproducible; the weekly job needs no token.

Reading stargazer history needs a fine-grained PAT on this repository with
Contents: Read and write. Revoke it once the seed is built.

    gh api -H "Accept: application/vnd.github.star+json" \
      "repos/comet-ml/opik/stargazers?per_page=100" --paginate \
      --jq '.[].starred_at' > /tmp/starred_at.txt

    python3 .github/scripts/star_history_seed.py /tmp/starred_at.txt

Writes star_history_seed.json next to this script, overwriting it.
"""
import argparse, collections, datetime as dt, json, pathlib

REPO = "comet-ml/opik"
OUT = pathlib.Path(__file__).parent / "star_history_seed.json"

ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
ap.add_argument("starred_at",
                help="file of ISO-8601 starred_at timestamps, one per line "
                     "(see the gh command in this module's docstring)")
args = ap.parse_args()

src = pathlib.Path(args.starred_at)
if not src.exists():
    raise SystemExit(f"error: {src} not found -- generate it with the gh command "
                     "in this script's docstring first.")

daily = collections.Counter()
for line in src.read_text().splitlines():
    line = line.strip()
    if line:
        daily[line[:10]] += 1

if not daily:
    raise SystemExit(f"error: {src} contained no timestamps.")

series, total = [], 0
day, end = dt.date.fromisoformat(min(daily)), dt.date.fromisoformat(max(daily))
while day <= end:
    total += daily.get(day.isoformat(), 0)
    series.append({"date": day.isoformat(), "count": total})
    day += dt.timedelta(days=1)

OUT.write_text(json.dumps({"repo": REPO, "series": series}))
print(f"{OUT.name}: {len(series)} daily points, "
      f"{series[0]['date']} -> {series[-1]['date']}, final={series[-1]['count']:,}")
