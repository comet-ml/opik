# Investigate E2E Failure

## Overview

Investigate a failed Opik E2E test and propose a fix. The command gathers the evidence (trace, error, history), classifies the failure as a real regression, a flake, or environment/selector drift, and proposes a specific fix. **Read-only** — it diagnoses and proposes; it does not edit tests.

---

## Inputs

- **Failure reference** (required) — any one of:
  - A GitHub Actions run or a red `Run v2 suite` check (URL or run id).
  - An Allure TestOps launch.
  - A test name (e.g. `dataset-crud-smoke`) when you want its history / flake status.
  - "The failure I just hit locally" — a test that failed in your local run.
- **Optional** — the PR or branch involved, so the diagnosis can correlate the failure to recent changes.

---

## Instructions

**Invoke the `debugging-e2e-tests` skill and follow it exactly.** It carries the loop — resolve the entry point → gather the trace + history → classify regression vs. flake → diagnose → report and propose — along with the evidence sources (the `allure-testops` MCP, `gh` artifacts, `npx playwright show-trace`) and the classification heuristics.

---

## Success criteria

1. A **verdict**: regression / flake / environment-or-selector, with a confidence level.
2. **Cited evidence**: the failing trace step, the error, the history pattern, and the correlated change (if any).
3. A **specific proposed fix** (or, for a known flake, a poll/quarantine/no-fix recommendation).
4. **No edits made** — to apply the fix, hand off to the `writing-e2e-tests` skill.
