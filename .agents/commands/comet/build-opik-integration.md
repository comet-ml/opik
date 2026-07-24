# Build Opik Integration

**Command**: `cursor build-opik-integration`

## Overview

Entry trigger for the `opik-integrations` skill — create, update, or maintain an Opik SDK integration (Python or TypeScript) that ships **inside this repo**. This command collects the inputs (below) and then runs the skill's phased workflow; the workflow files are the single source of truth for the phases, gates, verification, and report format.

- **Execution model**: Runs **autonomously** by default — makes its own preparations, runs every phase without pausing, self-verifies against the backend, and ends with a high-level report. The design/approval step (Phase 3) **only pauses in interactive mode** (ask "let me review the design first"); an autonomous run does not stop there — it records the design in the final report and proceeds. **Verifiability is a hard gate for `new`/`update`**: if there is no backend/credential path that can verify the integration (MCP or SDK read-back) and the user hasn't opted into an explicit unverified path, the run stops before writing integration code and reports what's blocked. Re-runs from scratch each invocation.
- **Scope**: integrations under `sdks/python/src/opik/integrations/` and `sdks/typescript/src/opik/integrations/`. SDK-contributor work — distinct from the user-facing `instrument` skill. For integrations that live in an **external** repo (a standalone `opik-*` package, or Opik support contributed into a third-party project like LiteLLM or Dify), use `/comet:build-opik-external-integration` instead.

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

## Run the workflow

Load `.claude/skills/opik-integrations/SKILL.md`, its `workflow.md` (the phase playbook, execution modes, MCP-verification loop, and report template), and the matching language reference (`python.md` / `typescript.md`). Then execute the workflow defined there — at a glance: prepare → investigate → collect → design → implement → verify → test → document → report.

**Do not restate the phases here.** `workflow.md` is the single source of truth for phase detail, the design gate, the verifiability hard gate, and the report format — follow it directly so the two never drift.

When done, if any files under `.agents/` changed, run `make claude` to sync them into `.claude/`.

---

## Notes

- Never commit real API keys in examples, tests, or docs — placeholders only.
- Follow `git-workflow` rules for branch/commit naming if asked to commit.
