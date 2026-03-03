# GEPA Optimizer Implementation

This document explains how the GEPA (Genetic-Pareto Algorithm) optimizer integrates with the optimization framework.

## Overview

The GEPA optimizer delegates to an external `gepa` library for the optimization loop. The framework provides two adapter layers:

- **`GepaOptimizer`** (`gepa_optimizer.py`) — Thin wrapper that satisfies the `OptimizerProtocol`. Configures GEPA parameters and launches the optimization.
- **`FrameworkGEPAAdapter`** (`gepa_adapter.py`) — Bridges GEPA's `GEPAAdapter` interface to the framework's `EvaluationAdapter`. Handles candidate tracking, lineage, and metadata enrichment.
- **`GEPAProgressCallback`** (`gepa_adapter.py`) — Bridges GEPA's lifecycle events to the framework's `EventEmitter` and adapter state management.

## GEPA's Evaluation Pattern

Each GEPA iteration can trigger 1–3 `evaluate()` calls:

```
GEPA Iteration i:
  1. evaluate(minibatch, selected_candidate, capture_traces=True)
     → 2-3 items, reflection source

  2. evaluate(minibatch, mutated_candidate, capture_traces=False)
     → same 2-3 items (only if mutation succeeded)

  3. evaluate(valset, accepted_candidate, capture_traces=False)
     → all validation items (only if candidate was accepted)
```

When mutation fails (e.g., "all subsample scores perfect"), only step 1 happens — a re-evaluation of the same candidate on a different minibatch.

## Callback Flow

GEPA dispatches lifecycle events via `notify_callbacks(callbacks, method_name, event)`. The `GEPAProgressCallback` receives these and forwards them to the adapter and event emitter.

### Event sequence per iteration:

```
on_iteration_start(iteration)
  → adapter._on_new_step(): resets per-iteration state

on_candidate_selected(candidate_idx, score)
  → adapter._on_candidate_selected(): records selected parent

on_minibatch_sampled(...)
  (informational — not handled)

on_evaluation_start(candidate_idx, parent_ids, capture_traces, batch_size)
  → adapter._on_evaluation_start(): stores _pending_eval_* metadata
  → GEPA calls adapter.evaluate() immediately after
  → adapter consumes _pending_eval_* fields during evaluate()

on_evaluation_end(scores, has_trajectories)
  (informational — logged only)

[If mutation produced a new candidate:]
  on_evaluation_start(..., capture_traces=False)
  → adapter.evaluate()
  on_evaluation_end(...)

  [If accepted:]
    on_candidate_accepted(new_candidate_idx, new_score, parent_ids)
    on_valset_evaluated(candidate_idx, candidate, average_score)
      → adapter._on_valset_evaluated(): maps gepa_idx to framework_id
    on_merge_accepted(new_candidate_idx, parent_ids)
      → adapter._on_merge_accepted(): records merge parent IDs

  [If rejected:]
    on_candidate_rejected(old_score, new_score, reason)

on_optimization_end(total_iterations, total_metric_calls)
```

### Key detail: `on_evaluation_start` as the metadata source

`on_evaluation_start` fires RIGHT BEFORE `adapter.evaluate()`. It provides:

| Field | Description |
|-------|-------------|
| `candidate_idx` | GEPA's internal index (`None` for new/mutated candidates) |
| `parent_ids` | List of GEPA integer indices that are parents |
| `capture_traces` | Whether this eval captures reasoning traces for reflection |
| `batch_size` | Number of items in the evaluation batch |
| `is_seed_candidate` | Whether this is a re-eval of the seed candidate |

The adapter stores these as `_pending_eval_*` fields and consumes them in `evaluate()`.

### Special case: initialization eval

GEPA's `initialize_gepa_state()` evaluates the seed candidate on the validation set BEFORE the iteration loop starts. This call goes through `engine.evaluator` directly, **without** firing `on_evaluation_start`. The adapter detects this via `_current_step < 0` and labels it `eval_purpose="initialization"`.

## Candidate Tracking

The adapter maintains several mappings to track candidates across GEPA's internal numbering and the framework's UUID-based system:

### `_known_candidates: dict[str, str]`

Maps a deterministic candidate key (JSON-serialized prompt content) to a framework `candidate_id`. When the same prompt is re-evaluated (e.g., on a different minibatch), the same UUID is reused.

### `_gepa_idx_to_candidate_id: dict[int, str]`

Maps GEPA's integer candidate index to the framework UUID. Updated when:
- `on_valset_evaluated` fires (maps a newly accepted candidate's GEPA index)
- The seed candidate is first evaluated (maps index 0)

### `_candidate_parents: dict[str, list[str]]`

Persistent parent mapping. Once set for a `candidate_id`, never overwritten. This ensures every experiment for a derived candidate always has the correct `parent_candidate_ids`.

### `register_baseline(seed_candidate, baseline_candidate_id)`

Called by `GepaOptimizer` before GEPA starts. Pre-seeds `_known_candidates` so the initialization eval (before iterations) reuses the baseline's `candidate_id` at step 0. When the first iteration starts, `_on_new_step` clears the pre-seeded entry so that exploration evals get a new candidate_id at step 1 with `parents=[baseline_id]`.

## Parent ID Resolution

The `_resolve_parent_ids()` method resolves parents using a priority chain:

1. **Merge parents** (`_pending_merge_parent_ids`) — from `on_merge_accepted`, for merged candidates
2. **Pre-eval parents** (`_pending_eval_parent_ids`) — from `on_evaluation_start`, GEPA's authoritative source. If the resolved list is non-empty, it is returned. If it resolves to empty (all GEPA indices unknown), falls through to step 3.
3. **Persistent parents** (`_candidate_parents[known_id]`) — stored parents for re-evaluations of known candidates (baseline has `[]`)
4. **Selected parent** (`_selected_parent_id`) — fallback from `on_candidate_selected`
5. **Baseline** (`_baseline_candidate_id`) — last resort for candidates with no other parent resolution

## Experiment Metadata

Each experiment created by the adapter includes these metadata fields:

| Field | Source | Description |
|-------|--------|-------------|
| `step_index` | Parent lineage (max parent step + 1) | Derived from parents; re-evals reuse cached step |
| `batch_index` | `adapter._current_step` | GEPA iteration number |
| `candidate_id` | `_known_candidates` or new UUID | Stable across re-evals of same prompt |
| `parent_candidate_ids` | `_resolve_parent_ids()` | `[]` for baseline; exploration and derived candidates have parent IDs |
| `num_items` | `len(batch)` | Distinguishes minibatch (small) from valset (large) |
| `capture_traces` | `on_evaluation_start` | Whether this was the reflection eval |
| `eval_purpose` | Derived from state | See below |

### `eval_purpose` values for GEPA:

| Value | When |
|-------|------|
| `"initialization"` | Seed eval before iteration loop (`_current_step < 0`) |
| `"exploration:minibatch"` | Minibatch eval with `capture_traces=True` |
| `"exploration:mutation"` | Mutation eval with `capture_traces=False` |
| `"validation"` | Valset eval (no callback metadata, `_pending_eval_*` is `None`) |

## Example Experiment Table

With 4 GEPA iterations, mutation succeeding at iteration 3:

```
step  batch  candidate  parents    num  traces  eval_purpose
   0      -  AAA        []           5  -       baseline
   0      -  AAA        []           1  false   initialization
   1      0  BBB        [AAA]        2  true    exploration:minibatch
   1      1  BBB        [AAA]        2  true    exploration:minibatch
   1      2  BBB        [AAA]        2  true    exploration:minibatch
   1      3  BBB        [AAA]        2  true    exploration:minibatch
   2      3  CCC        [BBB]        2  false   exploration:mutation
   2      3  CCC        [BBB]        1  false   validation
```

Key observations:
- Step 0 is the baseline (AAA). The initialization eval (GEPA's internal seed eval before iterations) also reuses AAA at step 0.
- Once iterations start, the seed prompt gets a new candidate_id (BBB) at step 1 with `parents=[AAA]`. Same prompt content, but now part of the optimization process.
- Mutations (CCC) get their own candidate_id with `parents=[BBB]` and `step_index=2`.
- `step_index` is derived from parent lineage (max parent step + 1). Re-evals of the same candidate reuse its cached step.
- `batch_index` groups experiments by GEPA iteration.
- Multiple experiments can share the same `step_index` — use creation time for ordering within a step.

## Configuration Parameters

Passed via `context.optimizer_parameters`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `seed` | `42` | Random seed |
| `reflection_minibatch_size` | `3` | Items per minibatch for reflection |
| `candidate_selection_strategy` | `"pareto"` | How GEPA selects candidates |
| `max_candidates` | `5` | Maximum candidates to explore |
| `max_metric_calls` | `max_candidates * len(train)` | Budget for total evaluations |
