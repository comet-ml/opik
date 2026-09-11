# Golden agents

Small, dependency-free, offline agents used as known fixtures for the Ollie
Local-Runner flows (OPIK-6125, consumed by OPIK-6951). Each is a self-contained
`agent.py` with a typed `run(...)` entrypoint so `opik connect` can discover its
schema.

They are deterministic and make no real LLM call — the point is the *shape*
(call tree, instrumentation state, pass-rate direction), not model output, so
they behave identically wherever the E2E suite runs.

## `uninstrumented/`

A plain multi-step agent with **no** Opik instrumentation (no `@opik.track`, no
`opik` import). Target for the Ollie `/instrument` flow: a `run` entrypoint that
fans out to a `retrieve` (tool-shaped) step and a `generate` (llm-shaped) step.
After Ollie instruments it, the test asserts the agent source gained an `opik`
import and `@track` decorators (`/instrument` edits the code but doesn't
necessarily run it, so the source is the reliable signal).

## `known-failing/`

An instrumented agent whose answer format is governed by an externalized
`SYSTEM_PROMPT`, plus a fixed evaluation suite (`suite.json`). The baseline
prompt omits units, so it fails the unit-bearing suite cases — baseline pass
rate ≈ 33%. The Ollie `/improve` flow tunes the prompt (e.g. "always include
units"), which lifts the pass rate (→ 100% with the obvious fix). The /improve
test asserts the **direction** of the pass rate (after > before), never an exact
value, because Ollie may propose different fixes on different runs.

`agent.py` is self-contained (its own question→value and question→unit data) so
it reads like a real agent Ollie can instrument. `suite.json` holds the test's
**independent** expected answers — the oracle the eval checks against, kept
separate from the agent's data on purpose.

`harness.ts` (beside the agent) holds the test helpers specific to this agent:
`evaluatePassRate` and `seedFailingTraces`, which run `agent.py` through the
bridge venv and read `suite.json`. Generic `opik connect` plumbing lives in
`core/local-runner/connect.ts`, not here.
