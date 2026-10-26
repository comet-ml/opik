# In-product release notes

These scripts build `public/release-notes.json`, the manifest behind the **What's new** panel
(Support hub → What's new).

## Why the notes are shipped, not fetched

The panel must answer "what changed in the Opik I am running?" identically on cloud, STSaaS and
self-hosted. Two constraints follow from that:

- **A self-hosted install may have no egress.** The product cannot fetch notes from
  `comet.com/docs/opik/changelog` at runtime, so the notes ship inside the image.
- **The frontend Docker build context is `apps/opik-frontend` only.** The changelog sources under
  `apps/opik-documentation/documentation/fern/docs-v2/changelog/` are not reachable at image-build
  time, so the manifest cannot be generated during the build — it is generated ahead of time and
  committed.

The manifest lives in `public/`, so it is served as a same-origin static asset and fetched on demand
when the panel opens. It never enters the JS bundle or the app-boot path.

## What changes between the docs entry and the manifest

The dated MDX files stay the authoring format. The generator only adapts them for a renderer that
has no docs-site build behind it:

- `<Frame>` blocks and bare `<img>` / `<video>` tags are dropped. Their `/img/...` sources exist only
  in the docs site, and an air-gapped install could not fetch them anyway.
- Site-relative links such as `](/tracing/...)` are pointed at `https://www.comet.com/docs/opik`.
  They would otherwise resolve against the product's own origin and 404.
- Each entry gets a `url` back to its public page (`canonical-url` frontmatter overrides it).

## Version attribution is derived, not hand-maintained

Changelog entries are dated (`2026-10-19.mdx`); deployments run versions (`2.2.122`). The join rule:

> An entry dated D first shipped in the earliest release published on or after D.

Release dates come from `release-index.json`, refreshed from the GitHub releases API. An entry newer
than every published release is attributed to the version in `version.txt` — on `main` that is the
next version the release workflow will cut — with `released_at: null` until it ships. That is what
lets a release show its own notes: the frontend image is built from the tag before the GitHub
release is published, so at that moment the index cannot yet know the entries belong to it.

An entry can override the derived attribution with an explicit `version:` in its frontmatter — use
that when the date heuristic lands a release early or late.

## Scripts

| Command (run in `apps/opik-frontend`) | What it does | Needs network |
| --- | --- | --- |
| `npm run release-notes:index` | Refreshes `release-index.json` from GitHub releases (needs `gh`) | yes |
| `npm run release-notes` | Regenerates `public/release-notes.json` | no |
| `npm run release-notes:check` | Fails if the manifest is stale — CI runs this | no |

Both generation steps are dependency-free Node scripts, so they run without `npm install`.

## When they run

- **Release** — `.github/workflows/release.yaml` refreshes the index and regenerates the manifest
  *after* the GitHub release is published and `version.txt` is bumped, then commits both alongside
  the version bump. The entries the release shipped move from "pending on `version.txt`" to attributed
  by publish date, and anything written afterwards becomes pending on the next version.
- **Pull request** — `.github/workflows/frontend_unit_tests.yml` runs `release-notes:check`, so a new
  changelog entry fails CI until the manifest is regenerated and committed.
