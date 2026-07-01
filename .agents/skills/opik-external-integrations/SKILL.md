---
name: opik-external-integrations
description: Build or update an Opik integration that lives OUTSIDE this repo — a standalone opik-* package (e.g. opik-openclaw, opik-claude-code-plugin) or Opik support contributed into a third-party project (e.g. LiteLLM, Dify). Use ONLY when the user names an external repository or external package as the target; for integrations under sdks/ use the opik-integrations skill instead.
---

# Opik External Integrations

This skill builds Opik integrations whose code **does not live in this repository**. Two shapes:

- **Standalone `opik-*` package / plugin** — its own repo or package that depends on the *published* Opik SDK. Examples: `opik-openclaw`, `opik-claude-code-plugin`.
- **Upstream contribution** — Opik support added into a third-party project that already has its own logging/callback/plugin system. Examples: an Opik callback in **LiteLLM**, an Opik integration in **Dify**.

> **Activation gate — read this first.** Use this skill **only** when the target lives in an external repo or external package. If the user wants an integration that ships inside `sdks/python/src/opik/integrations/` or `sdks/typescript/src/opik/integrations/`, this is the wrong skill — use **`opik-integrations`**. When in doubt, ask the "where does it live" question (below) and route accordingly.

## Two principles that make external work different

1. **Follow the HOST repo's conventions, not this repo's.** Structure, dependency management, lint/format, test framework, docs, and the contribution guide are dictated by the target project. Do not impose `sdks/` layout, `fake_backend`, or Fern docs on an external repo. Read its `CONTRIBUTING`/`AGENTS.md` and mirror its closest existing integration/plugin.
2. **Consume Opik through its PUBLISHED public API.** Use the released `opik` (Python) / `opik` (TypeScript) SDK or the REST API — the same surface end users get. Never import this repo's internal modules (`opik.decorator.*`, `rest_api`, message-processing internals); they are not available outside this repo and are not a stable contract.

## Start with the questionnaire

Collect everything up front, free-form — do not propose a target. See [workflow.md](workflow.md) Phase 0 for the full list. The essentials: **which external repo/package** (URL + links), **integration shape** (standalone package vs. upstream contribution vs. plugin), **host language/stack**, **where the Opik code goes**, **how the host tests and documents**, and **how to run it to verify**.

Once you know the target, check **[references.md](references.md)** — a curated list of known external integrations (standalone `opik-*` plugins incl. the cookiecutter template, and third-party host repos like LiteLLM / Dify / n8n) with their repo and Opik-docs URLs. If the target is listed or resembles one, start from its links and clone the closest sibling instead of researching blind.

## The workflow

Multi-step and autonomous by default (front-load preparation, run through, self-verify, end with a report); ask for the interactive variant to approve the design first. Full playbook in **[workflow.md](workflow.md)**. At a glance:

0. **Questionnaire + acquire** the external repo (clone/checkout into the scratchpad).
1. **Investigate the host** — its integration/plugin conventions, dep management, test framework, docs, contribution guide. Find the closest existing integration to clone.
2. **Investigate the Opik surface** — pick the public API to use: `@opik.track` / `track_*` wrappers, the low-level client, or REST. Lean on the `opik` and `instrument` skills for the public API.
3. **Design** how Opik plugs into the host's extension point (callback hook, plugin entrypoint, middleware). Map host data → Opik trace/span fields.
4. **Implement** in the host checkout, mirroring its closest sibling and its style.
5. **Verify via the Opik MCP** — run the host with the integration active, log to the workspace the MCP reads, and read the trace/spans back. Loop until correct.
6. **Test** using the host's test framework and conventions.
7. **Document** following the host's docs conventions; optionally add/point an Opik docs page (`write-docs`).
8. **Report** — what was done, verification evidence, a per-flow supported/tested table, limitations, and how to open the upstream PR.

## How this maps to the internal skill

The *mechanism* knowledge is shared — how a provider/framework exposes hooks, streaming, and usage is the same problem as in `opik-integrations`. Read that skill's [python.md](../opik-integrations/python.md) / [typescript.md](../opik-integrations/typescript.md) for the patterns (method patching, callback handler, OTel), but apply them against the **published** SDK surface and inside the **host repo's** structure.

## Skills this one builds on (do not duplicate them)

- `opik` / `instrument` — the user-facing public API surface (the right way to consume Opik from outside).
- `opik-integrations` — the integration mechanism patterns (patching / callback / OTel) and field mapping.
- `write-docs` — only if you also add an Opik-side docs page pointing at the external integration.
