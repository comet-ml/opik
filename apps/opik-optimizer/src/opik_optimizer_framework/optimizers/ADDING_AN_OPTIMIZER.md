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

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationContext,
    OptimizationState,
    TrialResult,
)


class MyOptimizer:
    def run(
        self,
        context: OptimizationContext,
        training_set: list[str],
        validation_set: list[str],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        event_emitter: EventEmitter,
        baseline_trial: TrialResult | None = None,
    ) -> None:
        # Your optimization logic here
        ...
```

No base class is needed — the framework uses Python's structural typing (`Protocol`).

## Step 2: Register in the Factory

Add your optimizer to `_register_builtins()` in `factory.py`:

```python
def _register_builtins() -> None:
    from opik_optimizer_framework.optimizers.simple_optimizer import SimpleOptimizer
    from opik_optimizer_framework.optimizers.gepa.gepa_optimizer import GepaOptimizer
    from opik_optimizer_framework.optimizers.my_optimizer import MyOptimizer
    OptimizerFactory.register("SimpleOptimizer", SimpleOptimizer)
    OptimizerFactory.register("GepaOptimizer", GepaOptimizer)
    OptimizerFactory.register("MyOptimizer", MyOptimizer)
```

The registered name is what the frontend/API uses in `optimizer_type`.

## Key Objects Your Optimizer Receives

### `OptimizationContext`

Contains the optimization configuration:

| Field | Type | Description |
|-------|------|-------------|
| `prompt_messages` | `list[dict]` | The original prompt (list of `{"role": ..., "content": ...}`) |
| `model` | `str` | LiteLLM model identifier (e.g., `"gpt-4o-mini"`) |
| `model_parameters` | `dict` | Model call parameters (temperature, etc.) |
| `optimizer_parameters` | `dict` | Algorithm-specific parameters from the UI |
| `optimization_id` | `str` | Unique ID for this optimization run |
| `dataset_name` | `str` | Name of the evaluation suite dataset |

### `EvaluationAdapter`

The main interface for evaluating candidates. Call `evaluate()` to score a prompt variant:

```python
config = CandidateConfig(
    prompt_messages=[{"role": "system", "content": "improved prompt..."}],
    model=context.model,
    model_parameters=context.model_parameters,
)

trial = evaluation_adapter.evaluate(
    config=config,
    dataset_item_ids=training_set,          # Which items to evaluate on
    parent_candidate_ids=["parent-uuid"],   # Lineage (optional)
    eval_purpose="exploration",             # See eval_purpose values below
)

if trial is not None:
    print(f"Score: {trial.score}")
```

Each `evaluate()` call creates an experiment visible in the UI.

### `eval_purpose` Values

Use these standard values so the UI can categorize experiments:

| Value | When to use |
|-------|-------------|
| `"baseline"` | Reserved — set by the orchestrator for the original prompt |
| `"initialization"` | One-time setup evals before the main loop |
| `"exploration"` | Main optimization loop evaluations |
| `"validation"` | Final validation of accepted candidates |

Algorithms may use colon-separated subtypes for finer granularity (e.g., `"exploration:minibatch"`, `"exploration:mutation"`). The UI groups by the prefix before the colon.

### `OptimizationState`

Shared state that accumulates trial results:

- `state.trials` — list of all `TrialResult` objects
- `state.best_trial` — the trial with the highest score so far

Trials are automatically added by `EvaluationAdapter.evaluate()` — you don't need to manage this manually.

### `EventEmitter`

Report progress to the UI:

```python
event_emitter.on_step_started(step_index=0, total_steps=5)
```

### `baseline_trial`

The orchestrator evaluates the original prompt on the **full dataset** before calling `run()`. The result is passed as `baseline_trial`. Use it to:

- Skip re-evaluating the original prompt
- Compare candidate scores against the baseline
- Pre-seed candidate tracking (see GEPA implementation for an example)

## Minimal Example

See `simple_optimizer.py` for a complete working example. The core pattern is:

```python
def run(self, context, training_set, validation_set,
        evaluation_adapter, state, event_emitter, baseline_trial=None):
    for step in range(num_steps):
        event_emitter.on_step_started(step, num_steps)

        # 1. Generate candidate prompt (your algorithm's logic)
        new_messages = generate_improved_prompt(context.prompt_messages)

        # 2. Evaluate it
        config = CandidateConfig(
            prompt_messages=new_messages,
            model=context.model,
            model_parameters=context.model_parameters,
        )
        trial = evaluation_adapter.evaluate(
            config=config,
            dataset_item_ids=training_set,
            eval_purpose="exploration",
        )

        # 3. state.best_trial is updated automatically
```

## Testing

Add tests in `tests/unit/`. Mock `EvaluationAdapter` and `EventEmitter` to test your optimizer's logic without hitting real APIs. See `test_simple_optimizer.py` for patterns.

Run tests:

```bash
cd apps/opik-optimizer
python -m pytest tests/unit/ -v
```
