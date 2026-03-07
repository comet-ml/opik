# Adding a New Optimizer

This guide explains how to add a new optimizer to the optimization framework.

## Architecture Overview

The framework uses a **protocol + factory** pattern:

- `OptimizerProtocol` (in `protocol.py`) — defines the interface every optimizer must satisfy.
- `OptimizerFactory` (in `factory.py`) — registry that maps optimizer names to classes.
- `orchestrator.py` — the entry point that creates optimizers via the factory and manages the optimization lifecycle (baseline evaluation, train/validation split, result aggregation).

The orchestrator handles everything outside the optimization loop: dataset splitting, baseline evaluation, state management, and result collection. Your optimizer only needs to implement the search strategy.

## Step 1: Implement the `run()` Method

Create a new file (e.g., `my_optimizer.py`) and implement a class with this signature:

```python
from __future__ import annotations

from typing import Any

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
    TrialResult,
)


class MyOptimizer:
    def run(
        self,
        context: OptimizationContext,
        training_set: list[dict[str, Any]],
        validation_set: list[dict[str, Any]],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        baseline_trial: TrialResult | None = None,
    ) -> None:
        # Your optimization logic here
        ...
```

No base class is needed — the framework uses Python's structural typing (`Protocol`).

## Step 2: Register in the Factory

Add your optimizer to `_load_registry()` in `factory.py`:

```python
def _load_registry() -> dict[str, type]:
    from opik_optimizer_framework.optimizers.gepa.gepa_optimizer import GepaOptimizer
    from opik_optimizer_framework.optimizers.my_optimizer import MyOptimizer

    return {
        "GepaOptimizer": GepaOptimizer,
        "MyOptimizer": MyOptimizer,
    }
```

The dict key is the `optimizer_type` value that the frontend/API uses.

## Key Objects Your Optimizer Receives

### `OptimizationContext`

Contains the optimization configuration:

| Field | Type | Description |
|-------|------|-------------|
| `model` | `str` | LiteLLM model identifier (e.g., `"gpt-4o-mini"`) |
| `baseline_config` | `dict[str, Any]` | Full config dict for baseline evaluation — includes optimizable keys plus `model`, `model_parameters`, etc. |
| `optimizable_keys` | `list[str]` | Config keys the optimizer is allowed to modify — any string values in `baseline_config` |
| `config_descriptions` | `dict[str, str]` | Descriptions of each optimizable key for the reflection LLM |
| `optimizer_parameters` | `dict` | Algorithm-specific parameters from the UI |
| `optimization_id` | `str` | Unique ID for this optimization run |
| `dataset_name` | `str` | Name of the evaluation suite dataset |

### `EvaluationAdapter`

The main interface for evaluating candidates. Call `evaluate()` to score a prompt variant:

```python
# Build config by copying baseline and replacing optimizable keys
config = {**context.baseline_config}
for key in context.optimizable_keys:
    config[key] = improved_texts[key]  # your algorithm produces these

trial = evaluation_adapter.evaluate(
    config=config,
    dataset_item_ids=[str(item["id"]) for item in training_set],
    parent_candidate_ids=["parent-uuid"],   # Lineage (optional)
)

if trial is not None:
    print(f"Score: {trial.score}")
```

Each `evaluate()` call creates an experiment visible in the UI. The `experiment_type`
is set by the adapter based on the evaluation context (full trial, mini-batch, or mutation).

### `OptimizationState`

Shared state that accumulates trial results:

- `state.trials` — list of all `TrialResult` objects
- `state.best_trial` — the trial with the highest score so far

Trials are automatically added by `EvaluationAdapter.evaluate()` — you don't need to manage this manually.

### Progress Reporting

Step progress is reported automatically by the `EvaluationAdapter` — it detects when the `step_index` changes between evaluations and emits the event. No explicit calls needed from your optimizer.

### `baseline_trial`

The orchestrator evaluates the original prompt on the **full dataset** before calling `run()`. The result is passed as `baseline_trial`. Use it to:

- Skip re-evaluating the original prompt
- Compare candidate scores against the baseline
- Pre-seed candidate tracking (see GEPA implementation for an example)

## Minimal Example

The core pattern is:

```python
def run(self, context, training_set, validation_set,
        evaluation_adapter, state, baseline_trial=None):
    for step in range(num_steps):
        # 1. Generate improved texts for each optimizable key
        improved = {}
        for key in context.optimizable_keys:
            improved[key] = generate_improved(context.baseline_config[key])

        # 2. Evaluate — copy baseline config, replace optimizable keys
        config = {**context.baseline_config, **improved}
        trial = evaluation_adapter.evaluate(
            config=config,
            dataset_item_ids=[str(item["id"]) for item in training_set],
        )

        # 3. state.best_trial is updated automatically
```

## Testing

Add tests in `tests/unit/`. Mock `EvaluationAdapter` to test your optimizer's logic without hitting real APIs.

If your optimizer depends on an optional library (like `gepa`), place tests in `tests/library_integration/<library>/` and guard with `pytest.importorskip("<library>")` at the top of the file so the unit suite stays fast and dependency-free.

Run tests:

```bash
cd apps/opik-optimizer
python -m pytest tests/unit/ -v                       # fast, no optional deps
python -m pytest tests/library_integration/ -v         # requires optional deps
```
