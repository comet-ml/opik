# GEPA v2 Optimizer — Implementation Guide

## Architecture

Three files, three responsibilities:

| File | Class | Role |
|------|-------|------|
| `gepa_optimizer.py` | `GepaV2Optimizer` | Entry point. Builds the seed candidate, sampler, adapter, and calls `gepa.optimize()`. |
| `gepa_adapter.py` | `FrameworkGEPAAdapter` | Bridges GEPA's evaluate/reflect interface to the framework's `EvaluationAdapter`. Tracks candidates, parents, per-item scores. |
| `gepa_adapter.py` | `GEPAProgressCallback` | Receives GEPA lifecycle events and forwards them to the adapter for state management. |
| `failure_aware_sampler.py` | `FailureAwareBatchSampler` | Controls which dataset items appear in each minibatch, prioritizing failed items. |

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

`GepaV2Optimizer` combines `training_set + validation_set` (deduplicated by ID) and passes the same list as both GEPA's `trainset` and `valset`. Minibatches sample from the full dataset, and full evaluations also use the full dataset. The orchestrator still performs a split for backward compatibility with other optimizers, but GEPA ignores it.

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

## Failure-Aware Batch Sampling

GEPA's default `EpochShuffledBatchSampler` selects minibatch items uniformly. With small minibatches (size 2-3) from ~15 items, there's a significant chance the minibatch contains zero failed items — wasting an iteration because the reflection LLM sees only passes and proposes marginal changes.

`FailureAwareBatchSampler` replaces the default with slot-based guarantees:

### Algorithm

1. **Before any full eval** — no failure data exists, sample uniformly at random.
2. **After a full eval** — the adapter calls `sampler.update_scores(per_item_feedback)`, providing per-item scores.
3. **On each minibatch selection**, items are categorized:
   - **Failed**: score < `failure_threshold` in the last full eval
   - **Unseen**: never appeared in any minibatch (tracked via `mark_seen()`)
   - **Other**: everything else
4. **Slot filling** (priority order):
   - Fill `min_failed_per_batch` slots from failed items (random among them)
   - Fill `min_unseen_per_batch` slots from unseen items
   - Fill remaining slots randomly from all unselected items
5. If a category is exhausted, its slots spill into the next category.

### ID mapping

GEPA's `DataLoader` uses integer indices (0..N-1). The adapter's per-item feedback uses string dataset_item_ids. The sampler builds a lazy mapping on first call: `loader.fetch(all_ids)` → `{int_idx: str_item_id}`.

### Wiring

- Created in `gepa_optimizer.py`, passed to both `gepa.optimize(batch_sampler=sampler)` and `FrameworkGEPAAdapter(batch_sampler=sampler)`.
- The adapter calls `sampler.update_scores(per_item)` after each full eval and `sampler.mark_seen(dataset_item_ids)` after each minibatch eval.

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

GEPA's `initialize_gepa_state()` evaluates the seed candidate on the full valset BEFORE the main loop and before callbacks fire. The adapter detects this via `_current_step < 0` and labels it `eval_purpose="initialization"`.

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

The reflection prompt is a custom 4-step template stored in `GENERALIZATION_REFLECTION_TEMPLATE`. It replaces GEPA's default to provide structured reasoning and prevent overfitting.

### Template structure

The prompt receives two placeholders from GEPA's `InstructionProposalSignature`:
- `<curr_param>` — the current instruction text (prefixed with "Parameter: {name}")
- `<side_info>` — the reflective dataset records (formatted by GEPA)

### The 4 steps

**STEP 1 — DIAGNOSE**: Read FAILED assertions. Identify patterns across failures — what categories of behavior keep failing.

**STEP 2 — KEEP WHAT WORKS**: Look at PASSED assertions. Copy rules from the current instruction that drive successes verbatim, unless they conflict with a fix.

**STEP 3 — WRITE RULES THAT MATCH THE ASSERTION**: Match rule specificity to what the assertion checks for:
- Specific behavior assertion → specific rule to guarantee it
- General quality assertion → broader rule with clear boundary
- Key constraint: every rule must describe an observable action, not abstract advice

**STEP 4 — GENERALIZE ACROSS INPUTS**: Rules must work for any input, not just the examples shown. Turn patterns into general triggers. Include domain facts the assistant wouldn't know on its own (company policies, product details, etc.).

### Full template

```
I provided an assistant with the following instructions to perform a task for me:
\```
<curr_param>
\```

The following are examples of different task inputs provided to the assistant
along with the assistant's response for each of them, and feedback showing
which assertions PASSED and which FAILED.
Examples are sorted by priority — the ones with the most failures come first:
\```
<side_info>
\```

Your task is to write an improved instruction for the assistant.

STEP 1 — DIAGNOSE: Read the FAILED assertions. Each one names a specific
behavior the assistant's response was missing. Identify the *patterns* across
failures — what categories of behavior keep failing?

STEP 2 — KEEP WHAT WORKS: Look at the PASSED assertions. The current
instruction already produces these behaviors. Copy the specific rules from
the current instruction that drive these successes into your new version
verbatim, unless they directly conflict with a fix.

STEP 3 — WRITE RULES THAT MATCH THE ASSERTION: Read each failing assertion
carefully. The assertion itself tells you how specific your rule needs to be:

- If the assertion checks for a SPECIFIC behavior (e.g., "includes a
code example", "mentions the deadline"), write a rule specific enough to
guarantee that behavior.
Example: "When the user's question includes a code snippet, always
include a corrected version in your response."

- If the assertion checks for a GENERAL quality (e.g., "clear and concise",
"factually accurate"), write a broader rule with a clear boundary.
Example: "Never state uncertain information as fact — say 'this may
vary' instead of asserting a specific value."

The assistant is a language model that executes literal instructions. Abstract
advice like "be empathetic" does NOT reliably produce the right behavior.
Every rule must describe an observable action (what to say, what to include,
what to avoid).

STEP 4 — GENERALIZE ACROSS INPUTS: Your rules must work for any input in
this domain, not just the examples shown. Do NOT reference specific test
inputs (e.g., "if the input is about topic X, say Y"). Instead, try to turn
the pattern into a general trigger but always mind the balance between
SPECIFIC and GENERAL (e.g., "when the user references a specific entity,
always confirm it back in your response").

If the feedback reveals domain facts the assistant wouldn't know on its own
(e.g., company policies, product details), include those facts as rules.

Provide the new instructions within \``` blocks.
```

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

### Sorting

Records are sorted by difficulty — most failures first (`_max_failed` descending). This ensures the reflection LLM focuses on the hardest items when context is limited.

### Output structure

Returns `{component_name: list[records]}` — one copy of records per component to update. The component name (e.g., "system_prompt") is determined by `components_to_update` from GEPA.

## Custom Reflection via `propose_new_texts()`

The adapter overrides GEPA's default reflection with `propose_new_texts()`:

1. For each component to update, prepend "Parameter: {name}" to the current instruction
2. Call GEPA's `InstructionProposalSignature.prompt_renderer()` to render the full prompt
3. Call `InstructionProposalSignature.run(lm, input_dict)` to get the new instruction
4. Strip the "Parameter: {name}" prefix from the result
5. Log the full reflection call (current instruction, feedback, rendered prompt, proposed text) in `_reflection_log`

The reflection LLM is resolved via `_get_reflection_lm_callable()`: string model names are wrapped in a litellm completion call; callables are used directly.

## Experiment Metadata

Each evaluation produces a trial with metadata for the UI:

| Field | Source | Description |
|-------|--------|-------------|
| `candidate_id` | `_known_candidates` or new UUID | Stable across re-evals of same prompt |
| `parent_candidate_ids` | `_resolve_parent_ids()` | `[]` for baseline; derived candidates have parent IDs |
| `batch_index` | `adapter._current_step` | GEPA iteration number |
| `num_items` | `len(batch)` | Distinguishes minibatch from full eval |
| `capture_traces` | `on_evaluation_start` | Whether this was the reflection eval |
| `experiment_type` | `None` or `"mini-batch"` | Full eval → `None`; minibatch → `"mini-batch"` |
| `eval_purpose` | Derived | See below |

### `eval_purpose` values

| Value | When |
|-------|------|
| `"initialization"` | Seed eval before iteration loop (`_current_step < 0`) |
| `"exploration:minibatch"` | Minibatch eval with `capture_traces=True` |
| `"exploration:mutation"` | New candidate eval with `capture_traces=False` |
| `"validation"` | Known candidate, no capture_traces |

### Full eval detection

The first evaluation sets `_full_dataset_size = len(batch)`. Subsequent evals with `len(batch) >= _full_dataset_size` are considered full evals; smaller batches are minibatches tagged with `experiment_type="mini-batch"`.

## Configuration Parameters

All passed via `context.optimizer_parameters`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `seed` | `42` | Random seed for GEPA and the batch sampler |
| `reflection_minibatch_size` | `3` | Items per minibatch for reflection |
| `candidate_selection_strategy` | `"pareto"` | How GEPA selects candidates (`"pareto"` or `"epsilon_greedy"`) |
| `max_candidates` | `5` | Maximum candidates to explore |
| `max_metric_calls` | `max_candidates * len(dataset) * 5` | Budget for total evaluations |
| `score_threshold` | `1.0` | Stop when best full-eval score reaches this |
| `min_failed_per_batch` | `reflection_minibatch_size - 1` | Guaranteed failed items per minibatch |
| `min_unseen_per_batch` | `0` | Guaranteed unseen items per minibatch |
| `failure_threshold` | `1.0` | Items scoring below this are "failed" |

## Example Experiment Table

```
step  batch  candidate  parents    num  type        eval_purpose
   0      -  AAA        []           5  (full)      initialization
   1      1  AAA        []           3  mini-batch  exploration:minibatch
   1      1  BBB        [AAA]        3  mini-batch  exploration:mutation
   1      1  BBB        [AAA]        5  (full)      validation
   2      2  BBB        [AAA]        3  mini-batch  exploration:minibatch
   2      2  CCC        [BBB]        3  mini-batch  exploration:mutation
   2      2  CCC        [BBB]        5  (full)      validation
```
