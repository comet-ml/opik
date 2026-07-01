# Build Opik Integration

**Command**: `cursor build-opik-integration`

## Overview

Drive the end-to-end workflow for creating, updating, or maintaining an Opik SDK integration (Python or TypeScript). This command is the entry trigger for the `opik-integrations` skill — it loads that skill and walks the phased playbook with a human approval gate before any integration code is written.

- **Execution model**: Runs **autonomously** by default — makes its own preparations (installs deps, finds credentials, picks the backend), runs every phase without pausing, self-verifies against the live backend, and ends with a high-level report. Stops early only on a true blocker (missing credential with no fallback, unreachable backend, ambiguous product decision), reporting what was completed. Add "let me review the design first" to run interactively with a design gate. Re-runs from scratch each invocation.
- **Scope**: integrations that live **inside this repo** — under `sdks/python/src/opik/integrations/` and `sdks/typescript/src/opik/integrations/`. This is SDK-contributor work — distinct from the user-facing `instrument` skill. For integrations that live in an **external** repo (a standalone `opik-*` package, or Opik support contributed into a third-party project like LiteLLM or Dify), use the `opik-external-integrations` skill / `/comet:build-opik-external-integration` command instead.

---

## Step 0 — Questionnaire (ask first, before any work)

Do **not** propose or pick a library yourself, and do **not** present a menu of candidate integrations. The user decides what to build; your job is to collect it. Ask the questions below in plain language and wait for answers. Use a quick choice only for the genuinely categorical ones (language, mode); keep the target name and links free-form.

1. **What to integrate** — the library / framework / provider name, plus any reference links you have (official docs, SDK source repo, API reference). Ask the user to paste names/links rather than guessing.
2. **Where does it live** — inside this Opik repo, or an external repo? If the answer is external (standalone `opik-*` package, or a PR into a third-party project such as LiteLLM / Dify), **stop and switch to the `opik-external-integrations` skill** (`/comet:build-opik-external-integration`).
3. **Language** — `python`, `typescript`, or `both`.
4. **Mode** — `new` (default), `update`, or `maintain`.
5. **Anything specific** (optional) — particular methods/flows to trace, or special behavior to handle (streaming, async, tools/agents, structured output, multimodal).

Once the answers are in, restate them in one line and proceed.

---

## Steps

### 1. Load the skill

Read `.claude/skills/opik-integrations/SKILL.md` and the matching language reference (`python.md` or `typescript.md`). Read `workflow.md` for the phase definitions.

### 2. Classify (Phase 0)

Confirm mode, language, and the closest existing integration to clone. For `maintain` mode, skip to Investigate → Verify → Test.

### 2.5. Prepare (Phase 0.5)

Autonomously install/resolve the target library in the SDK venv (pinning a known-good version and restoring any disturbed core dep), locate credentials without printing them (`sdks/python/tests/pytest.ini` env block + shell env), and confirm which backend the Opik MCP reads and that it's reachable. Record blockers instead of stopping when a fallback exists.

### 3. Investigate (Phase 1)

Fan out exploration of the target library: entrypoint shape, methods to trace (sync + async), streaming, input/output/usage/error mapping. Read the closest sibling integration in full.

### 4. Collect (Phase 2)

Write a minimal runnable example script (scratchpad) and a field-mapping findings note.

### 5. Design & gate (Phase 3)

Decide the pattern, file layout, entrypoint signature, and field mapping. In autonomous mode, proceed and capture this in the final report; in interactive mode, present it and wait for approval before writing integration code.

### 6. Implement (Phase 4)

Clone the closest sibling and adapt, reusing the shared core utilities. Keep the framework import inside the integration module (Python) / as a peer dependency (TypeScript).

### 7. Verify via Opik MCP (Phase 5)

Configure the example's env to log into the workspace the Opik MCP reads, run it (non-streaming + streaming + each extra method, then `flush()`), and read the trace/spans back through the MCP. Check the trace tree against the field mapping. Loop until correct.

### 8. Test (Phase 6)

Add coverage with the language harness. Python: `tests/library_integration/<name>/` with `fake_backend` + `testlib` trees and an `ensure_<name>_configured` fixture. TypeScript: vitest `*.test.ts` mirroring the sibling package.

### 9. Document (Phase 7)

Author the Fern page at `docs-v2/integrations/<name>.mdx` (Python) / `<name>-typescript.mdx` (TS), route it in `fern/versions/latest.yml`, and add a `<Card>` to the integrations overview.

### 10. Report (Phase 8)

Produce the high-level report using the template in `workflow.md`: what was done, verification evidence (MCP trace ids + test results), a supported/not-supported table, limitations, follow-ups, and a usage snippet. Back every "supported" claim with a passing test or a verified trace.

### 11. Sync

If any files under `.agents/` changed, run `make claude`.

---

## Notes

- Never commit real API keys in examples, tests, or docs — placeholders only.
- Follow `git-workflow` rules for branch/commit naming if asked to commit.
