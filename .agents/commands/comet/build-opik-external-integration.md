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

## Steps

### 1. Load the skill

Read `.claude/skills/opik-external-integrations/SKILL.md` and `workflow.md`. For mechanism patterns (method patching / callback / OTel) and field mapping, also consult `.claude/skills/opik-integrations/python.md` or `typescript.md`, applied against the published SDK.

### 2. Acquire (Phase 0)

Clone/checkout the external repo into the scratchpad (or add it to the session if supported). Confirm it builds/installs before changing anything.

### 3. Investigate the host (Phase 1)

Find the host's observability extension point, its closest existing integration to clone, and its dependency / test / docs / contribution conventions.

### 4. Investigate the Opik surface (Phase 2)

Pick the public Opik API to use (`@opik.track` / `track_*` / client / REST). Confirm the published SDK version to depend on and how the host configures Opik via env.

### 5. Design & gate (Phase 3)

Decide the extension point, client lifecycle/flush, field mapping, file layout (mirroring the host sibling), and how a host user enables it. Autonomous: record in the report. Interactive: present and wait for approval.

### 6. Implement (Phase 4)

Mirror the host's closest sibling and style. Depend on the published Opik SDK. Keep it transparent (wrap, capture, re-raise; no behavior change when Opik is unconfigured).

### 7. Verify via Opik MCP (Phase 5)

Configure Opik env to log into the workspace the MCP reads, run the host with the integration enabled (main + streaming + extra flows, then flush), and read the trace/spans back. Loop until the tree matches the mapping.

### 8. Test (Phase 6)

Use the **host's** test framework and conventions (not this repo's `fake_backend`/`testlib`). Mirror how the host tests its other integrations. Cover the verified flows.

### 9. Document (Phase 7)

Follow the host's docs conventions. Optionally add/point an Opik-side docs page via the `write-docs` skill. Placeholders only for credentials.

### 10. Report (Phase 8)

Produce the high-level report from `workflow.md`: what was done, MCP + test evidence, a per-flow supported/tested table, limitations, and upstream-PR guidance. Back every "supported" claim with a passing test or a verified trace.

### 11. Sync

If any files under `.agents/` in *this* repo changed, run `make claude`.

---

## Notes

- Never commit real API keys — placeholders only.
- Branch/commit/PR naming follows the **host project's** contribution guide, not this repo's `git-workflow` rules.
