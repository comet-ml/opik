#!/usr/bin/env python3
"""Render the "Star Us on GitHub" chart for the Opik READMEs.

Reads the running series from data.json, appends today's public star count,
and writes a light and a dark SVG. Standard library only -- no pip install
in CI, and no chart service at page-render time.

No font is embedded. An SVG loaded through <img> cannot fetch external
resources, so a webfont would have to ship inline -- which is why
star-history.com embeds xkcd Script (CC BY-NC 3.0, unusable here). The
hand-drawn character comes from the feTurbulence filter rather than the
typeface, so a system font stack costs almost nothing visually and takes
each SVG from ~225 KB to ~21 KB with no font asset to license.

GitHub restricted stargazer *history* to a repo's admins and collaborators on
2026-06-30, but the *total* count stays public, so this needs no token at all.
The historical curve was seeded once (see star_history_seed.py) and is carried
forward in data.json.

    python3 .github/scripts/star_history.py [--out DIR] [--data data.json]

See DND-1580.
"""
import argparse, datetime as dt, json, pathlib, urllib.request

REPO = "comet-ml/opik"
API = f"https://api.github.com/repos/{REPO}"

# Canvas geometry, lifted from the star-history.com SVG so the chart stays
# visually identical to the one this replaces.
W, H = 800, 533.333
OX, OY = 70, 60           # plot-group origin
PW, PH = 700, 423.333     # plot area
BASE = 423.833            # y of zero stars
STEP = 5000               # y-axis tick interval

HERE = pathlib.Path(__file__).parent
FONT_STACK = "system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"

THEMES = {
    "light": dict(bg="#ffffff", ink="#000000", muted="#666666",
                  line="#dd4528", star="#eac54f"),
    "dark":  dict(bg="#0d1117", ink="#e6edf3", muted="#8b949e",
                  line="#ff6b52", star="#eac54f"),
}


def public_star_count():
    req = urllib.request.Request(API, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": f"{REPO}-star-history",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["stargazers_count"]


def human(n):
    return f"{n/1000:g}k" if n >= 1000 else f"{n:g}"


def render(series, theme):
    c = THEMES[theme]
    xs = [dt.date.fromisoformat(p["date"]).toordinal() for p in series]
    ys = [p["count"] for p in series]
    x0, x1, ymax = min(xs), max(xs), max(ys)
    px = lambda x: (x - x0) / (x1 - x0) * PW
    py = lambda y: BASE - (y / ymax) * BASE

    o = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}"'
        f' style="stroke-width:3;font-family:{FONT_STACK};background:{c["bg"]}">',
        f'<rect width="{W}" height="{H}" fill="{c["bg"]}"/>',
        '<defs>',
        '<filter id="xkcdify" width="100%" height="100%" x="-5" y="-5"'
        ' filterUnits="userSpaceOnUse">'
        '<feTurbulence baseFrequency=".05" result="noise" type="fractalNoise"/>'
        '<feDisplacementMap in="SourceGraphic" in2="noise" scale="5"'
        ' xChannelSelector="R" yChannelSelector="G"/></filter>',
        '</defs>',
        f'<path fill="{c["star"]}" stroke="{c["star"]}" stroke-linejoin="round"'
        ' d="M327 12l3.2 6.6 7.3 1-5.3 5.1 1.3 7.2-6.5-3.4-6.5 3.4 1.3-7.2-5.3-5.1 7.3-1z"'
        ' filter="url(#xkcdify)"/>',
        f'<text x="50%" y="30" text-anchor="middle"'
        f' style="font-size:20px;font-weight:700;fill:{c["ink"]}">Star History</text>',
        f'<text x="50%" y="523.333" text-anchor="middle"'
        f' style="font-size:17px;fill:{c["ink"]}">Date</text>',
        f'<text x="-217" y="8" dy=".75em" text-anchor="end" transform="rotate(-90)"'
        f' style="font-size:17px;fill:{c["ink"]}">GitHub Stars</text>',
        f'<text text-anchor="middle" transform="translate(650 463.333)"'
        f' style="font-size:16px;fill:{c["muted"]}">comet.com</text>',
        f'<g pointer-events="all" transform="translate({OX} {OY})">',
        '<g fill="none" text-anchor="middle">',
        f'<path stroke="{c["ink"]}" d="M.5.5h700" filter="url(#xkcdify)"'
        ' transform="translate(0 423.333)"/>',
    ]
    for yr in range(dt.date.fromordinal(x0).year + 1, dt.date.fromordinal(x1).year + 1):
        t = px(dt.date(yr, 1, 1).toordinal())
        o.append(f'<g transform="translate({t:.3f} 423.333)"><text y="6" dy=".71em"'
                 f' style="font-size:16px;fill:{c["ink"]}">{yr}</text></g>')
    o.append('</g><g fill="none" text-anchor="end">')
    o.append(f'<path stroke="{c["ink"]}" d="M-1 423.833H.5V.5H-1" filter="url(#xkcdify)"/>')
    t = STEP
    while t <= ymax:
        o.append(f'<g transform="translate(0 {py(t):.3f})">'
                 f'<path stroke="{c["ink"]}" d="M0 0h-1"/>'
                 f'<text x="-7" dy=".32em" style="font-size:16px;fill:{c["ink"]}">'
                 f'{human(t)}</text></g>')
        t += STEP
    o.append('</g>')

    pts = " ".join(f"{px(x):.3f} {py(y):.3f}" for x, y in zip(xs, ys))
    o.append(f'<path fill="none" stroke="{c["line"]}" d="M{pts}" filter="url(#xkcdify)"/>')

    w = 29 + len(REPO) * 7.2 + 10
    o.append(f'<rect width="{w:.1f}" height="32" x="8" y="5" fill-opacity=".85"'
             f' stroke="{c["ink"]}" stroke-width="2" filter="url(#xkcdify)" rx="5" ry="5"'
             f' style="fill:{c["bg"]}"/>')
    o.append(f'<rect width="8" height="8" x="15" y="17" filter="url(#xkcdify)"'
             f' rx="2" ry="2" style="fill:{c["line"]}"/>')
    o.append(f'<text x="29" y="25" style="font-size:15px;fill:{c["ink"]}">{REPO}</text>')
    o.append('</g></svg>')
    return "".join(o)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data.json")
    ap.add_argument("--out", default=".")
    ap.add_argument("--no-fetch", action="store_true",
                    help="re-render from existing data without calling GitHub")
    a = ap.parse_args()

    path = pathlib.Path(a.data)
    if not path.exists():
        path = HERE / "star_history_seed.json"
        print(f"no {a.data}; bootstrapping from {path.name}")
    data = json.loads(path.read_text())

    if not a.no_fetch:
        today = dt.date.today().isoformat()
        count = public_star_count()
        if data["series"] and data["series"][-1]["date"] == today:
            data["series"][-1]["count"] = count
        else:
            data["series"].append({"date": today, "count": count})
        print(f"{count:,} stars as of {today}")

    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "data.json").write_text(json.dumps(data))

    for theme in THEMES:
        svg = render(data["series"], theme)
        f = out / f"star-history-{theme}.svg"
        f.write_text(svg)
        print(f"{f.name}  {len(svg):,} bytes")


if __name__ == "__main__":
    main()
