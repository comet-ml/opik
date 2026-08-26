#!/usr/bin/env python3
"""One-off: build data.json from a full stargazer pull.

Already run for comet-ml/opik on 2026-08-26 (21,616 stargazers). Kept so the
seed is reproducible; it is the only step that ever needed a PAT.

    gh api -H "Accept: application/vnd.github.star+json" \
      "repos/comet-ml/opik/stargazers?per_page=100" --paginate \
      --jq '.[].starred_at' > assets/starred_at.txt
    python3 seed_star_history.py
"""
import json, collections, datetime as dt

REPO = "comet-ml/opik"

daily = collections.Counter()
for line in open("assets/starred_at.txt"):
    if line.strip():
        daily[line.strip()[:10]] += 1

series, total = [], 0
day, end = dt.date.fromisoformat(min(daily)), dt.date.fromisoformat(max(daily))
while day <= end:
    total += daily.get(day.isoformat(), 0)
    series.append({"date": day.isoformat(), "count": total})
    day += dt.timedelta(days=1)

json.dump({"repo": REPO, "series": series}, open("data.json", "w"))
print(f"{len(series)} daily points, {series[0]['date']} -> {series[-1]['date']}, "
      f"final={series[-1]['count']:,}")
