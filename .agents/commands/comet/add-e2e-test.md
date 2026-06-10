# Add E2E Test

## Overview

Add a working, locally-verified Playwright end-to-end test for an Opik feature in `tests_end_to_end/e2e/`. The command runs the full loop — analyze the feature and frontend code, explore the live UI, write the Page Object Model + spec, and run it locally until it passes.

---

## Inputs

- **Feature to test** (required): a plain-English description of the flow. The more specific, the better — name the page, the actions, and what should be true at the end. Examples:
  - "Add an e2e test for the dataset items page: SDK-seed a dataset, open it, verify the items render."
  - "Cover the experiments comparison page — two experiments on one dataset, open the comparison view, verify both columns show."
  - "Write a test for the feature I just built on this branch."
- **Optional context** that helps the agent focus (it explores the live UI regardless):
  - A specific page URL (e.g. `localhost:5173/default/projects/<id>/experiments`).
  - A PR or branch to read the diff from.
  - An existing test to extend.

---

## Safety: verify local config

The default target is local OSS (`http://localhost:5173`). The Python SDK behind the bridge reads `~/.opik.config`; if it points at a cloud environment, seeding would create real data there.

```bash
cat ~/.opik.config
```

If `url_override` is anything other than `http://localhost:5173/api`, back it up and point it local, then restore it when done:

```bash
cp ~/.opik.config ~/.opik.config.bak 2>/dev/null || true
cat > ~/.opik.config << 'EOF'
[opik]
url_override = http://localhost:5173/api
workspace = default
EOF
```

Restore afterward: `cp ~/.opik.config.bak ~/.opik.config`. If it already points local, skip this.

---

## Instructions

**Invoke the `writing-e2e-tests` skill and follow it exactly.** It carries the full procedure — scope, analyze the feature + frontend code, explore the live UI with the Playwright MCP (delegating selector discovery to `playwright-pom-discovery`), write the POM + spec, and run until green — plus the suite's conventions.

---

## Success criteria

1. A POM (`pom/*.page.ts`) and spec (`tests/<feature>/<name>.spec.ts`) added under `tests_end_to_end/e2e/`.
2. Correct tier + feature tags on the spec; `test.step()` wrapping in the spec and POM methods.
3. The test runs **green locally**, with the actual run output reported.
4. Any brittle selector backed by a `data-testid` added to the frontend component in the same change.
