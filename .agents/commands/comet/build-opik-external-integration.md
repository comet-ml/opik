# Build Opik External Integration

**Command**: `cursor build-opik-external-integration`

## Overview

Drive the end-to-end workflow for building or updating an Opik integration that lives **outside this repo** — a standalone `opik-*` package (e.g. `opik-openclaw`, `opik-claude-code-plugin`) or Opik support contributed into a third-party project (e.g. LiteLLM, Dify). This command is the entry trigger for the `opik-external-integrations` skill.

- **Use this, not `build-opik-integration`, when** the target lives in an external repo/package. For integrations that ship inside `sdks/python/src/opik/integrations/` or `sdks/typescript/src/opik/integrations/`, use `/comet:build-opik-integration`.
- **Execution model**: Runs **autonomously** by default — acquires the repo, finds credentials, picks the backend, runs every phase, self-verifies against the live backend via the Opik MCP, and ends with a high-level report. Stops early only on a true blocker (no repo access, missing credential with no fallback, unreachable backend, ambiguous product decision). Add "let me review the design first" to run interactively with a design gate.
- **Two principles**: follow the **host repo's** conventions (structure, deps, tests, docs, contribution guide), and consume Opik through its **published public API** — never this repo's internals.

---

## Step 0 — Questionnaire (ask first, before any work)

Do **not** propose or pick a target. Ask the user these questions in plain language and wait for answers; keep names/links/paths free-form.

1. **External target** — repo URL / package name + reference links (docs, contribution guide). If standalone, the intended Opik artifact name.
2. **Integration shape** — standalone `opik-*` package/plugin · upstream contribution into a third-party project · tool/IDE plugin.
3. **Host language / stack** — Python, TypeScript/Node, other; package manager + runtime.
4. **Where the Opik code goes** — path inside the host repo, or a new standalone package layout.
5. **Host conventions** — its `CONTRIBUTING`/`AGENTS.md`, test framework, docs location.
6. **Verify + credentials** — how to run the host locally, and whether the provider + Opik API keys are available.

Restate the answers in one line, then proceed.

> Sanity check: if the answers reveal the target actually belongs inside `sdks/`, stop and switch to `/comet:build-opik-integration`.

---

## Run the workflow

Load `.claude/skills/opik-external-integrations/SKILL.md` and its `workflow.md` (the phase playbook, execution modes, MCP-verification loop, and report format). For mechanism patterns (method patching / callback / OTel) and field mapping, also consult `.claude/skills/opik-integrations/python.md` or `typescript.md`, applied against the **published** SDK. Then execute the workflow defined there — at a glance: acquire the repo → investigate the host → investigate the Opik surface → design → implement → verify → test → document → report.

**Do not restate the phases here.** `workflow.md` is the single source of truth for phase detail, the design gate, the verifiability hard gate, and the report format — follow it directly so the two never drift.

When done, if any files under `.agents/` in *this* repo changed, run `make claude` to sync them into `.claude/`.

---

## Notes

- Never commit real API keys — placeholders only.
- Branch/commit/PR naming follows the **host project's** contribution guide, not this repo's `git-workflow` rules.
