# Extract PR size (shared)

Shared logic for the code-review Slack commands (`send-code-review-slack`,
`generate-code-review-slack-command`) to derive the PR-size field. Kept in one
place so the two commands can't drift.

## How to extract

1. The `📏 Auto Label PR Size` workflow (`.github/workflows/pr-size-labeler.yml`)
   applies exactly one size label to every PR. Read the PR labels and look for a
   `size/*` label: `🔵 size/XS`, `🟢 size/S`, `🟡 size/M`, `🟠 size/L`, or
   `🔴 size/XL`.
2. Store the bucket as `{emoji} {BUCKET}` (e.g. `🟠 L`) — just the bucket, no
   line counts.
3. **Fallback** (only if no `size/*` label is present yet — the workflow may not
   have run): derive the bucket from the PR's changed lines
   (`additions + deletions`), applying **the same ignore list and thresholds as
   the workflow** so the fallback can't land in a different bucket than the
   labeler. The workflow (`.github/workflows/pr-size-labeler.yml`) is the single
   source of truth for both:
   - **Ignore list** — read `IGNORE_GLOBS` in the workflow (lockfiles, generated
     REST clients under `sdks/*/src/opik/rest_api/**`, and snapshot/image files).
     Do not re-list the globs here; they would drift.
   - **Thresholds** — read `BUCKETS` in the workflow: XS `< 20`, S `20–100`,
     M `101–300`, L `301–600`, XL `> 600`.
   Store the result as `{emoji} {BUCKET}`.
4. GitHub is the source of truth; the size at message-publish time is good enough
   (PRs rarely change bucket after review starts).

## Message field

Include a size line in the message template, bucket only (no `+/-` counts):

```
:straight_ruler: pr size: {{pr_size}}
```
