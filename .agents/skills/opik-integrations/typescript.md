# TypeScript Integration Anatomy

Reference for building integrations under `sdks/typescript/src/opik/integrations/opik-<name>/`. Read this alongside the `typescript-sdk` skill (layered client, flush semantics, testing).

## The canonical guide comes first

`sdks/typescript/design/INTEGRATIONS.md` is the authoritative, maintained guide: it has the three patterns with full code, the package structure, a step-by-step "Creating New Integrations" walkthrough, and streaming helpers. **Read it before writing anything.** This file only records the conventions that guide does not stress and that bite newcomers.

Companion design docs: `design/API_AND_DATA_FLOW.md` (client/batching/context), `design/TESTING.md` (vitest + MSW), `design/README.md` (navigation).

## Each integration is its own npm package

Unlike Python (one package, many integration modules), every TS integration is a **separate published package** (`opik-openai`, `opik-langchain`, `opik-vercel`, `opik-gemini`). That means:

- Its own `package.json`, `tsconfig.json`, `tsup.config.ts`, `vitest.config.ts`, `README.md`.
- `opik` and the wrapped framework are **`peerDependencies`**, not `dependencies` — pin sane ranges and keep the `opik` range current.
- Dual **CJS + ESM** build via `tsup`, emitting `dist/index.js` (ESM), `dist/index.cjs` (CJS), `dist/index.d.ts`.
- A lazy `OpikClient` singleton (`singleton.ts`) so the integration doesn't spin up multiple clients.

Clone the closest sibling package wholesale (its configs are correct) and rename, rather than hand-writing build config.

## Patterns

| Pattern | When | Template package |
|---|---|---|
| **Proxy wrapper** | SDK client with methods (most providers) | `opik-openai`, `opik-gemini` |
| **Callback handler** | framework with a callback interface | `opik-langchain` |
| **OTel exporter** | framework emitting OpenTelemetry spans | `opik-vercel` |

See `design/INTEGRATIONS.md` for each pattern's code. Shared imports come from `opik`: `Opik`, `Trace`, `Span`, `OpikSpanType`, `generateId`, `logger`.

**OpenTelemetry is backend-first.** Opik's backend ingests OTLP directly and maps spans to traces, so an OTel-instrumented framework can often be integrated with **docs alone** (point its exporter at Opik). Write a client-side `SpanExporter` — the `opik-vercel` pattern — only when you need to remap attributes/semantics, accumulate spans into trace trees, or bridge a framework that won't emit raw OTLP. Check the docs-only path first.

## Conventions that bite

- **Never leak `rest_api`.** Integrations wrap the public `opik` API only — never import generated clients from `opik/rest_api`.
- **Provide a `flush()` escape hatch.** Proxy wrappers expose `flush` on the returned object; handlers/exporters expose a `flush()` method. Required for CLIs/tests where the process exits before the async queue drains.
- **Keep adapters thin and non-blocking** — domain objects enqueue, they don't do HTTP.
- **Version references in prose.** When you change a peer-dep range or minimum version, update the integration's `README.md` and the root `README.md` in the same change — the `typescript-sdk` skill calls this out as a recurring miss.

## Tests

`tests/*.test.ts` with **vitest**, per `design/TESTING.md` and the `typescript-sdk` testing skill. Integration-specific shape:

- Mock the **API layer** (`vi.spyOn(client.api.spans, "createSpans")`) or use **MSW** to intercept HTTP — mirror the sibling package's `mockUtils.ts`.
- `await client.flush()` (or advance fake timers) **before** asserting; data only persists after the queue drains.
- Assert on captured spans with `toMatchObject({...})` — name, input, output, parentSpanId hierarchy, usage, model.
- Keep `tests/setup.ts` (it disables the logger). Don't let ERROR logs leak in tests.

## Examples

Add a runnable example to `sdks/typescript/examples/` mirroring `track-decorator.ts` / `langchain-with-thread-id.ts`. This doubles as the script you run during Phase 5 MCP verification.
