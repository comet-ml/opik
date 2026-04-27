"""
One-off script to pull raw events from a Sentry issue via REST API and group by
the *actual* exception message (the part Sentry's UI hides behind the issue title).

Reads SENTRY_ACCESS_TOKEN from .env.local. Token never leaves process memory —
not written to argv, not echoed.

Usage: python3 scripts/analyze_sentry_issue.py <issue_id> [region_host]
"""

import json
import re
import sys
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path


def read_token() -> str:
    env_local = Path(__file__).resolve().parent.parent / ".env.local"
    for raw in env_local.read_text().splitlines():
        line = raw.strip()
        if line.startswith("SENTRY_ACCESS_TOKEN="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("SENTRY_ACCESS_TOKEN not found in .env.local")


def fetch_events(issue_id: str, host: str, token: str, max_pages: int = 12) -> list[dict]:
    events: list[dict] = []
    cursor: str | None = None
    for page in range(max_pages):
        url = f"https://{host}/api/0/issues/{issue_id}/events/?full=false&limit=100"
        if cursor:
            url += f"&cursor={cursor}"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = json.loads(resp.read())
                link = resp.headers.get("Link", "")
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code} on page {page + 1}: {e.read().decode()[:200]}", file=sys.stderr)
            break
        if not isinstance(data, list) or not data:
            break
        events.extend(data)
        # Sentry pagination: Link header has rel="next"; results="true" if more
        m = re.search(r'<([^>]+)>;\s*rel="next";\s*results="true";\s*cursor="([^"]+)"', link)
        if not m:
            break
        cursor = m.group(2)
    return events


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    issue_id = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "us.sentry.io"
    token = read_token()
    events = fetch_events(issue_id, host, token)
    print(f"fetched {len(events)} events")

    # Try several places where the KeyError key shows up
    key_counter: Counter[str] = Counter()
    msg_counter: Counter[str] = Counter()
    user_counter: Counter[str] = Counter()
    release_counter: Counter[str] = Counter()
    no_key_count = 0

    pat = re.compile(r"KeyError:\s*'([^']+)'")

    for ev in events:
        msg = ev.get("message") or ev.get("title") or ""
        msg_counter[msg] += 1
        m = pat.search(msg)
        if m:
            key_counter[m.group(1)] += 1
        else:
            no_key_count += 1
        u = (ev.get("user") or {}).get("id") or "<no-user>"
        user_counter[u] += 1
        rel = ev.get("release") or "<no-release>"
        if isinstance(rel, dict):
            rel = rel.get("version", "<no-release>")
        release_counter[rel] += 1

    print("\n=== distinct missing keys (KeyError: '<X>') ===")
    for k, v in key_counter.most_common(30):
        print(f"{v:5d}  {k}")
    if no_key_count:
        print(f"({no_key_count} events did not match the KeyError pattern)")

    print("\n=== distinct event messages (top 15) ===")
    for k, v in msg_counter.most_common(15):
        print(f"{v:5d}  {k[:140]}")

    print("\n=== top users ===")
    for k, v in user_counter.most_common(10):
        print(f"{v:5d}  {k}")

    print("\n=== top releases ===")
    for k, v in release_counter.most_common(10):
        print(f"{v:5d}  {k}")


if __name__ == "__main__":
    main()
