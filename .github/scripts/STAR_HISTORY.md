# Star history chart

The **Star Us on GitHub** chart in the READMEs is generated here and published
to the Comet CDN. Nothing external is contacted when someone views the README.

Background: GitHub restricted stargazer *history* to a repository's own admins
and collaborators on 2026-06-30, which broke every third-party embed. The
*total* star count stayed public, so the curve is kept as our own series and
extended one point at a time. See DND-1580.

## Moving parts

| | |
|---|---|
| `star_history.py` | Renders `star-history-{light,dark}.svg` from a series. Standard library only, no embedded font. |
| `star_history_seed.py` | One-off, builds the initial series from a full stargazer pull. Already run. |
| `star_history_seed.json` | That initial series — 1,164 daily points, 2023-06-20 → 2026-08-26. |
| `../workflows/star-history.yml` | Weekly job: fetch series → append today's count → render → publish. |

## Storage and URL mapping

| | |
|---|---|
| Bucket / prefix | `s3://cdn.comet.ml/opik/star-history/` |
| Public origin | `https://cdn.comet.com/opik/star-history/` |
| Objects | `data.json`, `star-history-light.svg`, `star-history-dark.svg` |
| Region | `us-east-1` |
| Cache | `max-age=300`, plus a CloudFront invalidation each run |

`data.json` on the CDN is the **source of truth**. It is not in git — the copy
here is only the bootstrap snapshot.

## Required configuration

Two repository variables, provisioned in `comet-ml/comet-devops`:

| Variable | |
|---|---|
| `CDN_PUBLISH_ROLE_ARN` | Role assumed via OIDC. Trust policy scoped to this repository, `sub` pinned to `refs/heads/main`. |
| `CDN_DISTRIBUTION_ID` | CloudFront distribution fronting `cdn.comet.ml`. |

The role needs exactly:

- `s3:GetObject` and `s3:PutObject` on `arn:aws:s3:::cdn.comet.ml/opik/star-history/*`
- `cloudfront:CreateInvalidation` on that distribution

No long-lived AWS key exists, and the job needs no GitHub token — the star
count is read from the public API unauthenticated.

## First run

The scheduled job deliberately **fails** if it cannot read `data.json`, rather
than rebuilding from the seed. Rebuilding and republishing would overwrite
every point gathered since the seed was taken, and would do it silently.

So seeding is explicit and manual, exactly once:

> Actions → **star-history** → Run workflow → **bootstrap: true**

Every run after that leaves `bootstrap` unset. If one fails, fix the cause and
re-run normally — do **not** reach for `bootstrap` to make the error go away;
that is the data-loss path the flag exists to keep shut.

Failures post to Slack via `ACTION_MONITORING_SLACK`.

## Local development

```bash
# Re-render from the committed seed, no network, no publish
python3 .github/scripts/star_history.py \
  --data .github/scripts/star_history_seed.json --out /tmp/out --no-fetch
```

Check the result by loading the SVG through an `<img>` tag in a browser — the
same context GitHub renders a README image in. Do not use `rsvg-convert`; it
renders SVG text differently from a browser and will mislead you.
