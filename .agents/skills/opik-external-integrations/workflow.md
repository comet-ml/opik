# External Integration Workflow

End-to-end playbook for building an Opik integration that lives in an external repo or package. Runs autonomously by default; pause at the design gate only if the user asks to review first. **Always end with the Phase 8 report**, and back every "supported" claim with a passing test or an MCP-verified trace.

## Execution modes

- **Autonomous (default)** — make every preparation yourself (acquire the repo, find credentials, pick the backend), run all phases, self-verify, and report. Stop early only on a true blocker (no repo access, missing credential with no fallback, unreachable backend, an ambiguous product decision), reporting what you completed.
- **Interactive** — pause at Phase 3 for design approval before writing code.

## Phase 0 — Questionnaire + acquire

Ask the user, in plain language, and wait for answers. Do **not** propose a target.

1. **External target** — the repo URL / package name, plus reference links (docs, the host project's contribution guide). If it's a standalone package, the intended Opik artifact name (e.g. `opik-openclaw`).
2. **Integration shape** — standalone `opik-*` package/plugin · upstream contribution into a third-party project (LiteLLM, Dify, …) · tool/IDE plugin.
3. **Host language / stack** — Python, TypeScript/Node, other; package manager and runtime.
4. **Where the Opik code goes** — path inside the host repo (which plugin/integration dir), or a new standalone package layout.
5. **Host conventions** — link to its `CONTRIBUTING`/`AGENTS.md`; its test framework; its docs location.
6. **Verify + credentials** — how to run the host locally to exercise the integration, and whether the needed API keys (provider + Opik) are available.

Before acquiring, check **[references.md](references.md)** — known external integrations (standalone `opik-*` plugins + the cookiecutter template, and third-party hosts like LiteLLM / Dify / n8n / Flowise / Langflow) with their repo and Opik-docs URLs. If the target is listed or resembles one, start from those links and pick the closest as the clone source; a brand-new standalone package starts from `opik-project-template`.

Then **acquire the repo**: clone/checkout into the scratchpad (or add it to the session if supported). Confirm it builds/installs before changing anything. Restate the answers in one line and proceed.

## Phase 1 — Investigate the host

- The host's **extension point** for observability: a callback/hook interface, a plugin/entrypoint registry, middleware, or an env-driven logger. This is where Opik attaches.
- The **closest existing integration** in the host (e.g. how it integrates another observability/logging vendor) — clone its structure, registration, and style.
- The host's **dependency rules** (how it declares optional/extra deps), **test framework**, **lint/format**, and **docs** conventions.
- What host data is available at the hook (inputs, outputs, model, usage, errors, timing) and its shape.

## Phase 2 — Investigate the Opik surface

Pick the **public** Opik API to use — never this repo's internals:

- **Python**: `import opik`, `@opik.track`, `track_*` wrappers, or `opik.Opik()` client; `opik.opik_context` for span data; `client.flush()`.
- **TypeScript**: the `opik` package — `Opik` client, `track`, domain objects; `flushAll()`.
- **REST** when no SDK fits the host runtime.

Confirm the published SDK version to depend on, and how the host will configure Opik (env vars: `OPIK_API_KEY`, base URL, workspace, project).

## Phase 3 — Design (gate)

Write down: the host extension point used; how the Opik client is created/configured and flushed; the mapping from host data → Opik trace/span fields (input/output/model/provider/usage/error/span-type); the file layout inside the host (mirroring its sibling); and the public registration the host user performs to enable it. In autonomous mode, record this in the report; in interactive mode, present and wait for approval.

## Phase 4 — Implement

Mirror the host's closest sibling and its style. Depend on the **published** Opik SDK. Keep the integration transparent (wrap, capture, re-raise; no behavior change when Opik is unconfigured). Flush appropriately for the host's lifecycle (request end, process exit, explicit hook).

## Phase 5 — Verify via Opik MCP

The proof the integration logs correctly — do not rely on reading code.

1. Configure Opik env to log into the workspace the connected Opik MCP reads.
2. Run the host with the integration enabled, exercising the main flow(s) — non-streaming, streaming, and any extra path — then flush.
3. Read the trace/spans back through the MCP (`list` then `read`).
4. Check the tree against the Phase 3 mapping: one trace per top-level call; correct span hierarchy and `type`; input/output well-shaped; usage with token counts; model/provider set; errors recorded and re-raised.
5. Loop until it matches.

## Phase 6 — Test

Use the **host's** test framework and conventions (not `fake_backend`/`testlib`, which are this repo's). Mirror how the host tests its other integrations — mock the Opik client/HTTP at the host's boundary where the host does, or assert against captured calls. Cover the same flows you verified in Phase 5.

## Phase 7 — Document

Follow the **host's** docs conventions (its README / docs site / examples dir). Optionally add or update an Opik-side docs page that points to the external integration, using the `write-docs` skill. Credential placeholders only.

## Phase 8 — Report

Produce a high-level report:

- **Integration** — external target, shape, host extension point, Opik public API used, published SDK version depended on.
- **What was done** — repo acquired, files added/changed (with paths in the host), how a host user enables it.
- **Verification** — MCP trace ids + project, and host test results.
- **Flows supported & test coverage** — a table: every user-facing flow × implemented? × host test (by name) × MCP-verified? Flag any flow implemented but untested, and any not implemented.
- **What's NOT supported / limitations** — out-of-scope hooks, host-version constraints, env/backend blockers.
- **Follow-ups & PR** — how to open the upstream PR (branch/commit per the host's contribution guide), and any maintainer review notes.

## Definition of done

Implementation merged-quality in the host checkout, MCP verification passed, tests added per host conventions and passing, host docs updated, report produced. If you changed files under `.agents/` in *this* repo, run `make claude`.
