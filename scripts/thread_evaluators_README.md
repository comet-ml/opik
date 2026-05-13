# Thread online-evaluators smoke scripts

Two scripts that exercise the trace-thread agentic-tools path end-to-end
against a running Opik backend.

- [`setup_thread_evaluators.py`](setup_thread_evaluators.py) creates two
  automation rules on a project:
  - **Thread LLM-as-judge** with a `{{context}}` prompt. Routes through the
    `read / jq / search / get_trace_spans` loop when the rendered context
    exceeds the backend's `agenticToolsThresholdTokens`.
  - **Thread Python** with `arguments={"spans": "spans"}`. Triggers the
    kwargs-shaped payload — the scorer pre-fetches every span across the
    thread's traces and unpacks the request as kwargs.
- [`run_thread_traces.py`](run_thread_traces.py) generates threads in five
  shapes (small / medium / huge / large-messages / large-spans), closes
  each one to fire the online-scoring event, then polls thread feedback
  scores until both expected scores land or the wait budget expires.

## Prereqs

1. Opik backend running (local or remote) with:
   - `serviceToggles.agenticToolsEnabled = true` if you want the huge shape
     to actually flip to the agentic-tools path (otherwise it falls back to
     the inline path and you only verify that scoring runs at all).
   - `serviceToggles.traceThreadPythonEvaluatorEnabled = true` for the
     Python rule's consumer to start.
   - An LLM provider configured (matching the `--model` you pass to the
     setup script). Skip this if you only want to exercise the Python rule
     — pass `--python-only` to setup and `--skip-llm` to the runner.
2. `opik` Python SDK installed and configured. The scripts pick up the
   standard `~/.opik.config` / `OPIK_URL_OVERRIDE` / `OPIK_API_KEY` /
   `OPIK_WORKSPACE` chain.

## Typical flow

```bash
# 1. Stand up the rules on a fresh smoke-test project.
python scripts/setup_thread_evaluators.py \
    --project-name thread-scoring-smoke \
    --model gpt-4o-mini \
    --cleanup

# 2. Generate threads + poll for scores.
python scripts/run_thread_traces.py \
    --project-name thread-scoring-smoke

# 3. Re-run as needed. The setup script's --cleanup flag deletes its own
# rules (matched by name + tag) before re-creating, so you can iterate.
```

## What success looks like

Each shape should print a line with both scores landing within the wait
budget. The Python score value equals the number of spans logged for that
thread — if that match fails, the opt-in spans kwarg didn't flow:

```
  small          thread=smoke-small-abc123…  relevance=4  |  thread_spans_probe=2
  medium         thread=smoke-medium-...     relevance=3  |  thread_spans_probe=18
  huge           thread=smoke-huge-...       relevance=3  |  thread_spans_probe=16
  ...
PASS: all 5 threads scored within 120s.
```

If `relevance=MISSING` shows up on the huge shape but not the others,
inspect the backend log for the diagnostic line
`"Thread context exceeds 'X' tokens; switching to agentic-tools mode"` —
present means routing flipped, absent means the size estimate didn't
clear the threshold (bump `text_chars` in `SHAPES["huge"]` or lower
`agenticToolsThresholdTokens` on the backend).

If `thread_spans_probe` shows `=N (expected M)`, the metric ran but the
span count didn't match — pre-fetch wired up wrong somewhere. Usually
this means the rule was created without `arguments={"spans": "spans"}`,
i.e. you're hitting an older backend that hasn't picked up the new field
yet.

## Useful flags

`setup_thread_evaluators.py`:

- `--model` — LLM judge model. Must match a provider configured in the
  backend (default `gpt-4o-mini`).
- `--sampling-rate` — float 0-1. Default 1.0 for smoke tests (every
  thread sampled).
- `--llm-only` / `--python-only` — create just one of the two rules.
- `--cleanup` — delete previously-created rules with matching names
  before re-creating them.

`run_thread_traces.py`:

- `--shapes small,huge` — pick a subset. Default is all five.
- `--wait` — per-thread polling timeout in seconds (default 120). Bump
  this for cold-start LLM providers.
- `--poll-interval` — seconds between score polls (default 3).
- `--skip-llm` / `--skip-python` — drop one score from the expectations.
  Use `--skip-llm` if no LLM provider is configured.

## Cleanup

The setup script only ever creates rules tagged with the literal
`thread-evaluators-smoke` string in the name. `--cleanup` matches on
that. To wipe everything manually:

```bash
opik delete project thread-scoring-smoke   # wipes traces + threads
# Or via the UI: Automation rules → filter by name "thread-evaluators-smoke" → delete.
```
