---
name: opik-integrations
description: Build, update, test, and document Opik SDK integrations (Python & TypeScript). Use when adding a new framework/provider integration under sdks/python/src/opik/integrations or sdks/typescript/src/opik/integrations, updating an existing one, or verifying that an integration logs traces correctly.
---

# Opik SDK Integrations

This skill is for **building integrations into the Opik SDK itself** — the code that ships inside `opik` / `opik-*` packages so that *users* can trace a framework (OpenAI, LangChain, Mistral, …) with one call.

> Do not confuse this with the user-facing `instrument` / `opik` skills, which add Opik tracing to *someone else's* application. This skill is for SDK contributors editing `sdks/python` and `sdks/typescript`.
>
> If the integration lives **outside this repo** — a standalone `opik-*` package, or Opik support contributed into a third-party project (LiteLLM, Dify, a plugin, …) — use the **`opik-external-integrations`** skill instead. This skill assumes the code ships inside `sdks/`.

## Start with the questionnaire

Never assume or suggest a target. Collect, from the user, before doing anything: **what** to integrate (name + reference links), **where** it lives (this repo vs. external — route external requests to `opik-external-integrations`), **language** (python/typescript/both), **mode** (new/update/maintain), and any **specific flows** to cover. Do not present a menu of candidate libraries — the user names the target.

## When to use

- **New integration** — a framework/provider has no dedicated integration yet (today it's reachable only via LiteLLM, the OpenAI-compatible shim, or OpenTelemetry, or not at all).
- **Update** — an integration must track new methods, capture new fields, or follow an upstream SDK change.
- **Maintain / verify** — confirm an existing integration still logs the correct trace/span tree after a dependency bump or refactor.

## The workflow

Integration work is multi-step. By default this skill runs **autonomously**: it makes its own preparations (deps, credentials, backend), runs every phase, self-verifies, and ends with a high-level report — only stopping early on a true blocker. Ask for the interactive variant if you want to approve the design before any code is written. The full playbook — phases, execution modes, the Opik-MCP verification loop, and the report template — lives in **[workflow.md](workflow.md)**. At a glance:

0. **Prepare** — install/resolve the target library, locate credentials (without printing them), pick a backend the MCP can read.
1. **Investigate** the target library (API surface, hooks/callbacks, streaming shape, usage/token format, errors).
2. **Collect** findings + a minimal runnable example script.
3. **Design** — pick the pattern, file layout, entrypoint. (Interactive mode pauses for approval here; autonomous mode records it in the report.)
4. **Implement** by cloning the closest existing same-pattern integration.
5. **Verify** the logged data through the Opik MCP (`read`/`list` the trace & spans).
6. **Test** with the language's integration-test harness.
7. **Document** the Fern page and wire its routing.
8. **Report** — a high-level summary: what was done, what's supported (with evidence), what's not, and how to use it.

## Golden rule: clone the closest sibling

Never build an integration from a blank file. Identify the existing integration that shares the target's mechanism, copy its structure, and adapt. The decision tree:

| Target shape | Python pattern | TS pattern | Clone from |
|---|---|---|---|
| SDK client with methods to wrap (most providers) | Method patching (`BaseTrackDecorator` subclass) | Proxy wrapper | `openai/` · `opik-openai` |
| Framework with a callback/tracer interface | Pure callback (`BaseTracer`) | Callback handler | `langchain/` · `opik-langchain` |
| Framework already emitting OpenTelemetry spans | OTel | OTel exporter | `otel/` · `opik-vercel` |
| Callbacks exist but are unreliable / need method hooks too | Hybrid | (rare) | `adk/` |

If the target exposes an **OpenAI-compatible endpoint**, first check whether `track_openai(..., provider=...)` already covers the need before building a dedicated integration — sometimes the right answer is a docs page, not new code.

**OpenTelemetry is backend-first.** If the target already emits OpenTelemetry spans, the heavy lifting is done by Opik's OTLP ingestion endpoint on the backend — many such integrations are *docs-only* (point the framework's OTLP exporter at Opik with auth headers; no SDK code). Build a client-side piece only when you must shape what the backend receives — set Opik semantics, remap attributes, or bridge a framework that won't export raw OTLP. The client-side building block is a `SpanProcessor` in Python (`integrations/otel/`) or a `SpanExporter` in TypeScript (`opik-vercel`); a framework-specific OTel tracer wrapper (`adk/patchers/adk_otel_tracer/`) is the heavier variant. See the OTel sections in [python.md](python.md) / [typescript.md](typescript.md).

## Language references

- **Python** → [python.md](python.md) — integration anatomy, shared core modules, mechanism templates, dependency/import rules, test specifics.
- **TypeScript** → [typescript.md](typescript.md) — package anatomy, patterns, build/peer-dep rules. Delegates to the canonical `sdks/typescript/design/INTEGRATIONS.md`.

## Skills this one builds on (do not duplicate them)

- `python-sdk` — three-layer architecture, batching, `fake_backend`, `testlib` verifiers, error handling.
- `typescript-sdk` — layered client, flush semantics, testing with vitest.
- `write-docs` — Fern MDX authoring, routing YAML, callouts, images.
