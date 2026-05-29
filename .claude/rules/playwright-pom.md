---
---
# Opik 2.0 E2E CUJ test workflow

**Invoke the `playwright-pom-discovery` skill via the `Skill` tool** whenever any of these trigger:

- The user mentions `tests_end_to_end/e2e/`, "CUJ" tests, or "E2E test" in their message.
- The user describes a feature flow they want test coverage for (e.g., "we need automated tests for the agent-config switcher flow", "can you write tests for the experiments page").
- You're about to create or modify any file under `tests_end_to_end/e2e/` — POMs, tests, fixtures, or bridge routes for the Opik 2.0 E2E suite.

Invoke the skill BEFORE writing any code or asking detailed clarifying questions. The skill itself handles scoping (it'll ask up to ~3 questions adaptively if there's no spec or ticket to anchor on).

The skill carries:

- The 10-step UI discovery procedure (live `browser_snapshot`, `data-testid` enumeration, selector preference order, FE testid policy).
- A supplementary `retro-lessons.md` with cumulative lessons from prior CUJ tickets (matchers default off, UI-first assertions, `test.step()` mandatory, cascade verification, fixture-seed-shape rules, and more).
- A "no-ticket entry point" for natural-language requests — the skill scopes the work itself and offers to file a Jira ticket OR ship as `[NA]`.
- Anti-patterns and red flags to avoid.

Does NOT apply to:
- `tests_end_to_end/typescript-tests/**` — the 1.0 suite, owned by the `playwright-e2e` skill.
- Non-test code paths under `tests_end_to_end/e2e/` (e.g., the bridge service's pure Python code).

Read `tests_end_to_end/e2e/AGENTIC.md` for the team workflow overview if you're new to this directory.
