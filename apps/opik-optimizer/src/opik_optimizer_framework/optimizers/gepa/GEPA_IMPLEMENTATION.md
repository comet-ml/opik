# GEPA Optimizer — Implementation Guide

## Architecture

Six files, six responsibilities:

| File | Class | Role |
|------|-------|------|
| `gepa_optimizer.py` | `GepaOptimizer` | Entry point. Builds the seed candidate, sampler, adapter, and calls `gepa.optimize()`. |
| `gepa_adapter.py` | `FrameworkGEPAAdapter` | Thin facade. Orchestrates evaluation, delegates to collaborators. |
| `gepa_adapter.py` | `GEPAProgressCallback` | Receives GEPA lifecycle events and forwards them to the adapter. |
| `candidate_tracker.py` | `CandidateTracker` | Candidate identity, parent lineage, GEPA-to-framework ID mapping, callback state. |
| `reflective_dataset_builder.py` | `ReflectiveDatasetBuilder` | Builds structured feedback datasets for the reflection LLM. |
| `reflection_proposer.py` | `ReflectionProposer` | Calls the reflection LLM, logs prompts and proposals. |
| `failure_aware_sampler.py` | `FailureAwareBatchSampler` | Controls which dataset items appear in each minibatch, balancing failed and passed items. |

## How GEPA Works (High Level)

GEPA is an iterative prompt optimization library. Each iteration:

1. **Select** a candidate from the population (Pareto front or epsilon-greedy).
2. **Minibatch eval** — evaluate the candidate on a small subset of items (`capture_traces=True`). The batch sampler controls which items are selected.
3. **Reflect** — build a feedback dataset from the minibatch results, then ask a reflection LLM to propose an improved instruction.
4. **Mutation eval** — evaluate the new candidate on the same minibatch (`capture_traces=False`).
5. **Full eval** — if the mutation looks promising, evaluate it on the full dataset.
6. **Accept/reject** — if the new candidate improves the Pareto front, add it to the population.

The library interacts with our code through three adapter methods:

```
adapter.evaluate(batch, candidate, capture_traces)  → EvaluationBatch
adapter.make_reflective_dataset(candidate, eval_batch, components)  → dict
adapter.propose_new_texts(candidate, reflective_dataset, components)  → dict
```

## Key Design Decisions

### No train/val split

`GepaOptimizer` combines `training_set + validation_set` (deduplicated by ID) and passes the same list as both GEPA's `trainset` and `valset`. Minibatches sample from the full dataset, and full evaluations also use the full dataset. The orchestrator still performs a split for backward compatibility with other optimizers, but GEPA ignores it.

### Caller controls what gets optimized

`OptimizationContext.optimizable_keys` lists which baseline config keys the optimizer should optimize. The `_build_seed_candidate()` function extracts only those keys:

```python
def _build_seed_candidate(baseline_config: dict, optimizable_keys: list[str]) -> dict[str, str]:
    return {
        k: str(v) for k, v in baseline_config.items()
        if k in optimizable_keys and isinstance(v, str)
    }
```

The `_make_config_builder()` merges optimized keys back into the full baseline config:

```python
def build(candidate: dict[str, str]) -> dict:
    return {**baseline_config, **candidate}
```

### Flat config, not prompt messages

GEPA candidates are flat `dict[str, str]` (e.g., `{"system_prompt": "...", "user_message": "..."}`). The framework's `EvaluationAdapter` receives the full merged config and handles conversion to whatever format the evaluation needs.

## Balanced Batch Sampling

GEPA's default `EpochShuffledBatchSampler` selects minibatch items uniformly. With small minibatches (size 4-7) from ~20 items, uniform sampling often produces batches dominated by either failures or passes, leading to two problems:

1. **All-failure batches** → the reflection LLM over-corrects, adding rules that fix failures but break passing behaviors (catastrophic regressions with 0.0 scores).
2. **All-pass batches** → wasted iteration, the reflection LLM sees only passes and proposes marginal changes.

`FailureAwareBatchSampler` replaces the default with **balanced 50/50 sampling**:

### Algorithm

1. **Before any full eval** — no failure data exists, sample uniformly at random.
2. **After a full eval** — the adapter calls `sampler.update_scores(per_item_feedback)`, providing per-item scores.
3. **On each minibatch selection**, items are categorized:
   - **Failed**: score < `failure_threshold` in the last full eval
   - **Passed**: everything else
4. **Slot filling**:
   - Target `remaining // 2` failed slots (at least `min_failed_per_batch`)
   - Fill failed slots with worst-scoring items first
   - Fill remaining slots with randomly sampled passed items
   - If not enough passed items, fill randomly from all remaining
5. If a category is exhausted, its slots spill into the next category.

### Why 50/50

The passed items act as **behavioral anchors**. The reflection LLM sees both what's broken AND what's working, so it can:
- Preserve rules that drive passing assertions
- Make targeted fixes for failures without over-correcting
- Avoid the pattern of "fix A, break B" that causes catastrophic regressions

### Minimum batch size

The minimum `reflection_minibatch_size` is clamped to **4** to ensure the 50/50 split is meaningful (at least 2 failed + 2 passed). Default is 4.

### Failure streak tracking

The sampler also tracks per-item **failure streaks** — how many consecutive full evaluations an item has failed, and which specific assertions keep failing. This data is used by the reflection prompt to annotate stuck items with "Failure History" context, encouraging the reflection LLM to try structurally different approaches rather than repeating the same fix.

### Problematic items summary

At the end of optimization, `get_problematic_items_summary()` returns items with `failure_streak >= 2`, sorted by streak length. This is logged to the reflection log for debugging and future UI display.

### ID mapping

GEPA's `DataLoader` uses integer indices (0..N-1). The adapter's per-item feedback uses string dataset_item_ids. The sampler builds a lazy mapping on first call: `loader.fetch(all_ids)` → `{int_idx: str_item_id}`.

### Wiring

- Created in `gepa_optimizer.py`, passed to both `gepa.optimize(batch_sampler=sampler)` and `FrameworkGEPAAdapter(batch_sampler=sampler)`.
- The adapter calls `sampler.update_scores(per_item)` and `sampler.update_assertion_failures(per_item)` after each full eval.

## Callback Flow

GEPA dispatches lifecycle events via `notify_callbacks()`. The `GEPAProgressCallback` forwards them to the adapter.

### Event sequence per iteration

```
on_iteration_start(iteration)
  → adapter._on_new_step(): resets per-iteration state

on_candidate_selected(candidate_idx)
  → adapter._on_candidate_selected(): records selected parent

on_evaluation_start(candidate_idx, parent_ids, capture_traces)
  → adapter._on_evaluation_start(): stores pending metadata
  → GEPA calls adapter.evaluate() immediately after
  → adapter consumes pending metadata during evaluate()

[If mutation produced a new candidate:]
  on_evaluation_start(..., capture_traces=False)
  → adapter.evaluate()

  [If accepted:]
    on_valset_evaluated(candidate_idx, candidate)
      → adapter._on_valset_evaluated(): maps gepa_idx → framework_id
    on_merge_accepted(parent_ids)
      → adapter._on_merge_accepted(): records merge parent IDs

on_iteration_end(state, proposal_accepted)
```

### Seed evaluation (special case)

GEPA's `initialize_gepa_state()` evaluates the seed candidate on the full valset BEFORE the main loop and before callbacks fire. The adapter detects this via `_current_step < 0` and sets `experiment_type=None` (full trial).

## Candidate Tracking

### `_known_candidates: dict[str, str]`

Maps a deterministic candidate key (`json.dumps(candidate, sort_keys=True)`) to a framework `candidate_id` (UUID). When the same prompt content is re-evaluated, the same UUID is reused.

### `_gepa_idx_to_candidate_id: dict[int, str]`

Maps GEPA's integer candidate index to the framework UUID. Updated when `on_valset_evaluated` fires or when the seed candidate is registered via `register_baseline()`.

### `_candidate_parents: dict[str, list[str]]`

Persistent parent mapping. Once set for a `candidate_id`, never overwritten.

### Parent resolution priority

`_resolve_parent_ids()` resolves parents using a fallback chain:

1. Merge parents (`on_merge_accepted`)
2. Pre-eval parents (`on_evaluation_start`) — GEPA's authoritative source
3. Persistent parents (stored for known candidates)
4. Selected parent (`on_candidate_selected`)
5. Baseline (last resort)

## Baseline Caching

The `EvaluationAdapter` caches evaluation results keyed by `SHA256(JSON(config) + "\0" + sorted_item_ids)`. When the orchestrator evaluates the baseline before calling the optimizer, the result is cached. When GEPA's seed eval evaluates the same candidate on the same items, it gets a cache hit — no duplicate experiment.

## Reflection Prompt

The reflection prompt is a custom 4-step template stored in `GENERALIZATION_REFLECTION_TEMPLATE` in `reflection_proposer.py`. It replaces GEPA's default to provide structured reasoning and prevent overfitting. The template is task-agnostic — it uses neutral language ("parameter", "system") and avoids domain-specific examples.

### Template structure

The prompt receives two placeholders from GEPA's `InstructionProposalSignature`:
- `<curr_param>` — the current parameter text, prefixed with a header built by `_build_header()` (see below)
- `<side_info>` — the reflective dataset records (formatted by GEPA)

### Parameter header (`_build_header()`)

The header prepended to `<curr_param>` provides the reflection LLM with context about what it's optimizing:

1. **Parameter name**: always included (e.g., `Parameter: system_prompt`)
2. **Description** (optional): from `OptimizationContext.config_descriptions` — explains the parameter's role in the system (e.g., "Main customer-facing support agent system prompt")
3. **Sibling parameters** (if any): lists other optimizable parameters by name and description, explicitly marked as "for context only — do NOT modify these"

The header is stripped from the LLM's output so it doesn't leak into the proposed text.

### Config descriptions

`OptimizationContext.config_descriptions` is an optional `dict[str, str]` mapping optimizable key names to human-readable descriptions. This helps the reflection LLM understand:
- What part of the agent system each parameter controls
- Whether it's a user-facing prompt or an internal subagent prompt
- How parameters relate to each other

### The 4 steps

**STEP 1 — DIAGNOSE**: Read FAILED assertions and identify what behaviors are missing. Read PASSED assertions — the current parameter already produces these. Preserve the rules that drive successes.

**STEP 2 — CHECK FAILURE HISTORY**: If any example has a "Failure History" section, the current rules for that assertion already failed before. Do NOT add another generic rule of the same kind. Instead embed concrete example phrases or lookup instructions directly, or try a structurally different approach.

**STEP 3 — WRITE TARGETED FIXES**: For each failing assertion, add or modify a specific rule. Every rule must describe an observable action (what to say, include, or avoid) — vague guidance does not reliably work. Rules must generalize to any input in this domain; do NOT reference specific test inputs.

**STEP 4 — STRUCTURE**: Group related rules under short descriptive headers. Merge overlapping rules. Remove redundant ones. Keep the parameter concise — prefer tightening existing rules over appending new ones.

## Reflective Dataset Construction

`make_reflective_dataset()` transforms raw evaluation results into structured feedback for the reflection LLM.

### Per-item feedback extraction

`_extract_per_item_feedback()` processes the raw evaluation result into:

```python
{
    "item-id-1": {
        "runs": [
            {"output": "...", "score": 0.5, "assertions": [
                {"name": "is_polite", "value": 1.0, "reason": ""},
                {"name": "mentions_deadline", "value": 0.0, "reason": "No deadline mentioned"}
            ]},
        ],
        "score": 0.5  # mean across all runs
    },
}
```

When `runs_per_item > 1`, all runs are preserved so the reflection LLM can see what varies across attempts.

### Record building

For each item in the minibatch trajectories:

**Single run** — produces a record with:
- `Inputs`: all dataset item fields except `id`
- `Generated Outputs`: the LLM's response
- `Feedback`: structured text listing FAILED and PASSED assertions

**Multiple runs** — consolidates into one record with:
- `Inputs`: same as above
- `Runs`: each run as `[Run 1/N]` with output and feedback
- `Summary`: e.g., "2/3 runs passed. Consistent failures: mentions_deadline"

### Failure history annotation

For items with `failure_streak >= 1`, a `Failure History` field is appended:
> "This item has failed N consecutive iteration(s). Still-failing assertions: ... The current rules for these assertions are not working."

This warns the reflection LLM to try a structurally different approach.

### Sorting

Records are sorted by difficulty — most failures first (`_max_failed` descending). This ensures the reflection LLM focuses on the hardest items when context is limited.

### Output structure

Returns `{component_name: list[records]}` — one copy of records per component to update. The component name (e.g., "system_prompt") is determined by `components_to_update` from GEPA.

## Custom Reflection via `propose_new_texts()`

The adapter delegates to `ReflectionProposer.propose()`, which overrides GEPA's default reflection:

1. For each component to update, build a header via `_build_header(name, candidate)` containing the parameter name, optional description, and sibling parameter context
2. Prepend the header to the current parameter text as `current_instruction`
3. Call GEPA's `InstructionProposalSignature.prompt_renderer()` to render the full prompt (substituting `<curr_param>` and `<side_info>`)
4. Call `InstructionProposalSignature.run(lm, input_dict)` to get the new parameter text
5. Strip the header from the result so it doesn't leak into the proposed text
6. Log the full reflection call (current instruction, feedback, rendered prompt, proposed text) in `_reflection_log`

The reflection LLM is resolved via `_get_lm_callable()`: string model names are wrapped in a litellm completion call; callables are used directly.

## Experiment Metadata

Each evaluation produces a trial with metadata for the UI:

| Field | Source | Description |
|-------|--------|-------------|
| `candidate_id` | `_known_candidates` or new UUID | Stable across re-evals of same prompt |
| `parent_candidate_ids` | `_resolve_parent_ids()` | `[]` for baseline; derived candidates have parent IDs |
| `batch_index` | `adapter._current_step` | GEPA iteration number |
| `num_items` | `len(batch)` | Distinguishes minibatch from full eval |
| `capture_traces` | `on_evaluation_start` | Whether this was the reflection eval |
| `experiment_type` | Derived | Full eval → `None` (trial); known candidate minibatch → `"mini-batch"`; new candidate → `"mutation"` |

### `experiment_type` values

| Value | When |
|-------|------|
| `None` (trial) | Full eval: initialization, baseline, or validation |
| `"mini-batch"` | Reflection minibatch on a known candidate |
| `"mutation"` | New candidate exploration (not yet known) |

### Full eval detection

The first evaluation sets `_full_dataset_size = len(batch)`. Subsequent evals with `len(batch) >= _full_dataset_size` are considered full evals (experiment_type=None → trial); smaller batches are either `"mini-batch"` (known candidate) or `"mutation"` (new candidate).

## Configuration Parameters

All passed via `context.optimizer_parameters`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `seed` | `42` | Random seed for GEPA and the batch sampler |
| `reflection_minibatch_size` | `4` (min 4) | Items per minibatch for reflection |
| `candidate_selection_strategy` | `"pareto"` | How GEPA selects candidates (`"pareto"` or `"epsilon_greedy"`) |
| `max_candidates` | `5` | Maximum candidates to explore |
| `max_metric_calls` | `max_candidates * len(dataset) * 5` | Budget for total evaluations |
| `score_threshold` | `1.0` | Stop when best full-eval score reaches this |
| `min_failed_per_batch` | `1` | Minimum guaranteed failed items per minibatch |
| `failure_threshold` | `1.0` | Items scoring below this are "failed" |

## Example Experiment Table

```
step  batch  candidate  parents    num  experiment_type
   0      -  AAA        []           5  (trial)
   1      1  AAA        []           4  mini-batch
   1      1  BBB        [AAA]        4  mutation
   1      1  BBB        [AAA]        5  (trial)
   2      2  BBB        [AAA]        4  mini-batch
   2      2  CCC        [BBB]        4  mutation
   2      2  CCC        [BBB]        5  (trial)
```
