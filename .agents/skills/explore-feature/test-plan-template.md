# `test-plan.md` format

The happy-path checklist — the dev↔QA handoff artifact. Emit it exactly in this shape, filled
from the resolved scope. It is posted to Jira (canonical) and written to a scratch path locally to
drive spec generation. **Never committed to the repo.**

## Template

```markdown
# Test plan — <LEAD-TICKET>: <feature title>

**Scope:** <one line: the user-facing behavior this gates>
**Change surface:** PR(s) #<n>, ticket(s) <KEY[, KEY…]>
**Version stamp:** <version> (origin/main)   **Gate spec:** tests_end_to_end/e2e/tests/_release-gate/<lead-ticket>.spec.ts

## Happy path
- [ ] Step 1 — <user action> → <observable result>
- [ ] Step 2 — <user action> → <observable result>

## Preconditions / seed
- <state the test seeds via the SDK/bridge before the browser opens>

## Repro condition (fix PRs only)
- <the exact state that triggered the bug — the shape the seed MUST create. Omit for feat PRs.>

## Not covered (out of scope for the gate)
- <edge cases, error paths, perf, and any equivalent surfaces not gated — flagged for QA's deeper pass>

## Open questions for QA
- <anything the skill was unsure about — the explicit feedback prompt>
```

## Rules

- **Happy path only.** One flow, the thing a user does when everything works. Not error paths,
  not edge cases — those go under "Not covered" for QA.
- **Observable results.** Each step names what the *user sees* (a row appears, a badge turns
  green), not an internal state change — this is what the spec will assert.
- **For fix PRs, "Repro condition" is mandatory *unless the fix has no behavior delta*.** Name the
  exact state that triggered the bug, and make the seed create that shape — not a similar one the
  pre-fix code already handled. A gate that passes against the pre-fix behavior gates nothing —
  **except** for a no-behavior-delta perf/internals fix (see the skip-check in SKILL.md), where a
  gate that passes on both builds is *expected*: it's a generic regression, not a repro. For that
  class, write "N/A — no behavior delta; generic regression, see Gate class" in "Repro condition"
  and state the generic framing explicitly. Do not fabricate a repro that can't exist.
- **"Not covered" and "Open questions" are mandatory sections**, even if short. They are the
  dev↔QA boundary and the feedback surface that improves this skill. An empty "Open questions"
  means "I'm confident"; say that rather than deleting the section.
- **Multi-ticket:** the lead ticket titles the plan; reference the others by key. In any text
  posted to Jira, use the underscore form for tickets this PR does **not** resolve (`OPIK_7168`)
  so the Jira scanner doesn't false-link them; keep the hyphen for resolved tickets.
