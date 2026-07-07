# Integration Workflow

The end-to-end playbook for building or updating an Opik SDK integration. Run the phases in order.

Pick the language reference up front — [python.md](python.md) or [typescript.md](typescript.md) — and keep it open throughout.

## Execution modes

- **Autonomous (default for this command)** — make every preparation yourself (install deps, find credentials, pick the backend), run all phases without stopping, self-verify, and finish with the **Phase 8 report**. Do not pause at the design gate; record the design in the report instead. Only stop early if you hit a true blocker you cannot resolve (missing API key with no fallback, an ambiguous product decision, a backend you cannot reach) — report what you completed and what's blocked.
- **Interactive** — same phases, but pause at Phase 3 to get the design approved before writing code. Use this when the user asks to review the plan first.

Either way, **always end with the high-level report** (Phase 8). Never invent results — every "supported" claim must be backed by a passing test or an MCP-verified trace; everything unverified goes under "not supported / not verified".

## Phase 0 — Classify

- **Mode**: `new` (no integration exists), `update` (extend an existing one), or `maintain` (verify/repair an existing one). Maintain mode skips Phases 2–4 and runs Investigate → Verify → Test against current code to catch drift.
- **Language**: Python, TypeScript, or both. They are independent code paths; if both are requested, run the whole workflow once per language.
- **Closest sibling**: name the existing integration you will clone (see the decision tree in [SKILL.md](SKILL.md)).

## Phase 0.5 — Prepare (autonomous)

Get the environment ready yourself before investigating. Record what you did for the report; never print secret values.

- **Install / resolve the target library** into the SDK venv (`sdks/python/.venv` for Python; `npm i` in the integration package for TS). Capture the resolved version. If the install is broken or pulls an incompatible core dep (e.g. it bumps `pydantic` and breaks `litellm`), pin to a known-good version and restore the disturbed dep.
- **Locate credentials** without printing them: Python tests read the env block in `sdks/python/tests/pytest.ini` (e.g. `MISTRALAI_API_KEY`); also check the shell env. If the library's own default env var differs from the test var (e.g. SDK wants `MISTRAL_API_KEY` but the test sets `MISTRALAI_API_KEY`), read the test var explicitly and pass it to the client.
- **Pick the verification backend**: the connected Opik MCP reads one backend — confirm which (list its projects) and that it's reachable. Log the verification run there so the MCP can read it back. Use a dedicated scratch `OPIK_PROJECT_NAME` (e.g. `<name>-integration-demo`).
- **Note blockers**: if a credential or backend is missing and has no fallback, record it — the run can still produce code + offline (`fake_backend`) tests, with live MCP verification marked "not verified".

## Phase 1 — Investigate

Understand the target library before touching Opik. Prefer fanning out parallel explore agents over the library's installed source / docs.

Answer all of these:

- **Entrypoint shape** — Is it a client object with methods (→ patching/proxy), a callback/tracer interface (→ callback), or already OpenTelemetry-instrumented? If it emits OTel, first decide whether a **docs-only OTLP-to-Opik** setup suffices (backend does the mapping) before committing to a client-side processor/exporter — see the OTel notes in the language reference.
- **Methods to trace** — the specific calls users invoke (e.g. `chat.complete`, `embed`, `rerank`). List sync *and* async variants, and **treat structured-output methods (`parse`, `response_format=…`) and their streaming variants as in-scope by default** — don't defer them to "follow-ups" unless the user says so. Enumerate the full cross-product up front (complete/parse × sync/async × stream/non-stream).
- **Streaming** — does a streamed call return an iterator/async-iterator, a context manager, or emit events? How are chunks shaped, and where does usage land (final chunk? separate event?)?
- **Input** — the request fields worth logging (messages, prompt, tools/functions, model params).
- **Output** — where the completion text / choices / tool calls live in the response object.
- **Usage** — the token-count field names, and whether the provider is one Opik recognizes for cost tracking (`opik.types.LLMProvider`).
- **Errors** — what exceptions the library raises.

Read the closest sibling integration in full — it is the template, and most decisions are already made there.

**Clone-ability checkpoint (do this before designing).** "Clone the closest sibling" only holds if the target is actually a clean analog. Before committing, confirm it — and if any of these are true, surface it as a design decision **even in autonomous mode** instead of silently picking:

- The library exposes **multiple incompatible client classes or versions** (e.g. a v1 `Client` and a v2 `ClientV2` with different method signatures and response shapes). Decide which to support, and whether both are in scope.
- The **usage/token shape differs** from the sibling's (so the sibling's usage parser won't just work and you need custom mapping).
- The **response/streaming shape** is materially different from the sibling's.
- **Methods delegate to each other.** Check whether a higher-level method calls a lower-level one you also patch (e.g. Mistral's `chat.parse` calls `chat.complete`; some SDKs' `stream` calls `create`). Patching both naively **double-logs the call and double-counts cost**. Read the delegating method's source to find this. The idiomatic fix is to patch **only the primitive** and name the span after that primitive (see [python.md](python.md)) — verify the span count and cost in Phase 5.

A target that looks like "just another provider" but has any of the above is *not* a clean clone — say so before writing code.

## Phase 2 — Collect

Produce two artifacts in the scratchpad before designing:

1. **A minimal runnable example script** that exercises the target library directly (no Opik yet) — one non-streaming call, one streaming call, and any second method you plan to trace. This is what you'll later run in Phase 5 to verify logging.
2. **A findings note** — a short mapping table:

   | Opik span field | Source in the library's request/response |
   |---|---|
   | input | … |
   | output | … |
   | usage | … |
   | model | … |
   | provider | … |
   | span type | `llm` / `tool` / `general` |

## Phase 3 — Design

Decide and write down:

- The **pattern** (from the decision tree) and **why** it fits.
- The **file layout** (use the skeleton in the language reference).
- The **public entrypoint** name and signature — match sibling conventions (`track_<name>` for Python patching; `trackXxx` / `XxxCallbackHandler` / `XxxExporter` for TS).
- The methods to patch/handle and the field mapping from Phase 2.

In **interactive mode**, present this and wait for approval before writing code. In **autonomous mode**, proceed — but capture the design and any open questions (e.g. dedicated integration vs. an OpenAI-compatible docs page) in the Phase 8 report so the reviewer sees the decisions.

## Phase 4 — Implement

- Copy the closest sibling's structure and adapt it. Reuse the shared core utilities listed in the language reference — never re-derive span/trace creation, error collection, or usage parsing.
- Keep the framework import inside the integration module (Python) or as a `peerDependency` (TS); never import it at SDK package top level.
- Preserve the library's original behavior: wrap, capture, re-raise. The integration must be transparent when tracing is disabled.

## Phase 5 — Verify via Opik MCP

This is the proof that the integration actually logs correctly. Do not rely on reading code.

**Verifiability is a hard gate for `new`/`update`.** If you cannot run this phase — no API credential for the target, or no reachable backend the MCP can read — **stop and surface it before writing integration code**, in autonomous mode too. Do not produce an integration and then present it as done with verification "skipped": unverified integration code is the exact failure this skill exists to prevent. Offer the user the choice to supply a credential, proceed explicitly-unverified (clearly labelled, tests key-gated and skipped), or pick a different target. Only `maintain` mode on already-passing code may relax this.

1. **Point at a backend the MCP can read — and confirm it.** The connected Opik MCP reads one specific backend/workspace, which is often **not** the one in `~/.opik.config` (e.g. MCP → a hosted `*.dev.comet.com`, local config → `localhost`). Before relying on the MCP, confirm they match: log a trace, then try to `read` its project through the MCP. If the MCP can't see it, the backends differ. Either reconfigure the script's env (`OPIK_API_KEY`, `OPIK_URL_OVERRIDE`/base URL, `OPIK_WORKSPACE`, `OPIK_PROJECT_NAME`) to log into the MCP's backend, **or** fall back to **SDK read-back** (next note). Don't silently assume the MCP sees your trace.
2. **Run the Phase 2 example** through the new integration (wrap the client / attach the handler), exercising the non-streaming call, the streaming call, and each extra method. Call `flush()` before exit.
3. **Read it back through the MCP** — use the opik-mcp `list` (entity_type `trace`, filtered by the project) then `read` (entity_type `trace`, which inlines spans; `read` `span` for detail).
4. **Check the trace/span tree against the Phase 2 mapping:**
   - one trace per top-level call; span hierarchy matches the call structure
   - span `type` is correct (`llm` for model calls)
   - `input` / `output` captured and well-shaped (not empty, not the raw object dump)
   - `usage` present with prompt/completion/total tokens
   - `model` and `provider` set correctly
   - streamed calls produce the same shape as non-streamed (aggregated output + usage)
   - an induced error records `error_info` and still re-raises
   - **no duplicate spans / double cost** — a call to a delegating method (e.g. `parse`) produces exactly one span, and its `total_estimated_cost` is not doubled
   - **nesting** — a traced call made inside an `@track` function attaches as a child span of that function's span
5. **Loop** until the logged data matches. Fix the integration, re-run, re-read.

**SDK read-back fallback (equivalent evidence).** When the MCP can't read the backend you can write to, verify against that backend over REST instead: `client = opik.Opik(); client.search_traces(project_name=...)` then `client.search_spans(trace_id=...)`, and assert the same checklist (type, input/output, usage, model, provider). This is a real backend round-trip — note in the report that read-back was via the SDK, not the MCP tool, and why.

## Phase 6 — Test

Add coverage with the language's harness — see the test section of the language reference, which delegates to the `python-sdk` / `typescript-sdk` testing skills.

- **Python**: `sdks/python/tests/library_integration/<name>/`, using `fake_backend` and `testlib` `TraceModel`/`SpanModel` trees with `ANY_*` matchers. Assert input/output/usage/model/provider. Gate real API calls behind an `ensure_<name>_configured` fixture. Name tests `test_<what>__<case>__<expected>`. Cover every enumerated flow — including `parse`/structured-output variants, the delegating-method single-span case, and one **nested-under-`@track`** case (asserts the LLM span attaches as a child).
- **TypeScript**: `*.test.ts` with vitest, mocking the API layer (or MSW), `await flush()` before asserting, fake timers for batching. Mirror the sibling integration's test file.

**Register the tests in CI (Python) — this is part of "done", not optional.** The `tests/library_integration/<name>/` files run only if wired into GitHub Actions:

1. Create `.github/workflows/lib-<name>-tests.yml` by cloning the closest sibling (e.g. `lib-anthropic-tests.yml`): set the provider's API-key env from `secrets.<PROVIDER>_API_KEY`, install `library_integration/<name>/requirements.txt`, run `pytest -vv .` in the test dir. **Unless asked otherwise, pin a single Python version** (`matrix.python_version: ["3.12"]`) instead of the full `PYTHON_VERSIONS` matrix.
2. Register it in `.github/workflows/lib-integration-tests-runner.yml`: add `<name>` to the `libs` `workflow_dispatch` choices, add a `<name>_tests` job (`if: contains(fromJSON('["<name>", "all"]'), …)` + `uses:` the new file + `secrets: inherit`), and add it to the `notify-slack` job's `needs` list and `SUITE_RESULTS` payload.
3. Flag that the run needs a `<PROVIDER>_API_KEY` repository secret to exist, and validate both YAML files parse.

## Phase 7 — Document

Author the Fern page following the `write-docs` skill for MDX/components, plus these integration-specific conventions:

- **Check for an existing page first.** A provider often already has a docs page describing a *workaround* (OpenAI-compatibility endpoint via `track_openai`, or LiteLLM) and an entry already in `fern/versions/latest.yml`. If so, this is an **update**: lead the page with the new native integration and demote the workaround to an "Alternative" section (see how `mistral.mdx` keeps LiteLLM). Don't create a duplicate page or a second routing entry.
- **File**: `apps/opik-documentation/documentation/fern/docs-v2/integrations/<name>.mdx` for Python, `<name>-typescript.mdx` for TypeScript.
- **Title frontmatter** distinguishes language: `Observability for <Lib> (Python) with Opik` vs `(TypeScript)`.
- **Page shape** (follow `openai.mdx` / `langchain.mdx`): intro/tips → account setup → getting started (install, configure Opik, configure the library) → basic usage (the wrap/handler call + a screenshot) → advanced usage → cost tracking → supported methods.
- **Routing**: add a `- page:` entry under the right language → category section (`Frameworks`, `Model Providers`, …) in `fern/versions/latest.yml`. Do not edit `docs.yml`.
- **Overview grid**: add a `<Card>` to `docs-v2/integrations/overview.mdx` under the matching section. Cards are title + href only; section icons are Font Awesome — there is no per-integration icon to create.
- Use credential placeholders only (`<API_KEY>`), never real keys.

## Phase 8 — Report (always)

End every run with a high-level report. Keep it scannable — it's for a reviewer deciding whether to ship, not a changelog. Use this template:

```markdown
## <Library> integration — report

**Mode:** new | update | maintain   **Language:** Python | TypeScript
**Pattern:** method-patching | proxy | callback | OTel exporter   **Entrypoint:** `track_<name>(...)`
**Library version prepared:** <name>==<version>

### What was done
- <files created/changed — bullets, grouped by integration / tests / docs>
- <prep actions: deps installed, version pins, fixtures/env added>

### Verification
- **MCP:** <project name + trace ids read back, or "not verified — <reason>">
- **Tests:** <N passing — list cases>; ruff/mypy <clean | issues>

### Flows supported & test coverage

Always enumerate **every user-facing flow** the integration handles and map each to its coverage — don't collapse this into a one-line "supported". A flow is a distinct way a user invokes the library; enumerate the cross-product that applies: each traced method, sync vs async, streaming vs non-streaming, nested under `@track`, the error path, and any option that changes behavior (custom `project_name`, `provider` override, tool/function calls, structured output). For each flow state whether it's implemented, which test covers it (by name), and whether it was MCP-verified.

| Flow | Implemented | Test | MCP-verified |
|---|---|---|---|
| `chat.complete` (sync, non-stream) | ✅ | `test_<name>_complete__happyflow` | ✅ trace `<id>` |
| `chat.complete_async` | ✅ | `test_<name>_complete_async__happyflow` | — |
| `chat.stream` (sync) | ✅ | `test_<name>_stream__happyflow` | ✅ trace `<id>` |
| `chat.stream_async` | ✅/❌ | … | … |
| nested under `@track` | ✅/❌ | … | … |
| error → `error_info` logged | ✅ | `test_<name>__error...` | — |
| token usage captured | ✅ | asserted in above | ✅ |
| custom `project_name` | ✅/❌ | param case | — |

Explicitly flag the gaps: any flow **implemented but not covered by a test**, and any flow **not implemented at all** (list it in the next section). The goal is that a reviewer can see, per flow, exactly what was proven.

### What's NOT supported / limitations
- <methods intentionally not patched, flows implemented-but-untested, known gaps, provider-not-in-LLMProvider caveats, env/backend blockers>

### Follow-ups
- <suggested next steps: more methods, TS counterpart, PR split, etc.>

### How to use
<minimal code snippet>
```

## Definition of done

Implementation merged-quality, MCP verification passed (Phase 5 checklist) or its absence reported, tests added and passing **and registered in CI** (Python: `lib-<name>-tests.yml` + runner wiring), docs page authored and routed, and the **Phase 8 report delivered**. Then run `make claude` if you added/edited files under `.agents/`.
