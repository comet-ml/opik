# Optimizer Benchmarks

Unified benchmark runner for testing prompt optimizers locally or on Modal cloud.

## Quick Start

### Local Execution

Run benchmarks on your local machine:

```bash
 # Single dataset, single optimizer (test mode)
 python benchmarks/run_benchmark.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

 # Multiple datasets and optimizers
 python benchmarks/run_benchmark.py \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt \
  --max-concurrent 4
```

### Modal Execution (Cloud)

Run benchmarks on Modal's cloud infrastructure:

```bash
# 0. Setup Modal (first time only)
pip install modal
modal token new  # Authenticate with Modal

# 1. Create/update the unified secret (include whatever keys you have)
modal secret create opik-benchmarks \
  OPIK_API_KEY="$OPIK_API_KEY" \
  OPIK_URL_OVERRIDE="$OPIK_URL_OVERRIDE" \
  OPIK_WORKSPACE="$OPIK_WORKSPACE" \
  OPENAI_API_KEY="$OPENAI_API_KEY" \
  ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  GOOGLE_API_KEY="$GOOGLE_API_KEY" \
  GEMINI_API_KEY="$GEMINI_API_KEY" \
  OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  --force

# 2. Deploy worker + coordinator (redo after code changes)
modal deploy benchmarks/engines/modal/engine.py
modal deploy benchmarks/run_benchmark_modal.py

# 3. Submit benchmark tasks (engine can be selected explicitly)
python benchmarks/run_benchmark.py --engine modal \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

# 4. Check results (summary or raw)
modal run benchmarks/check_results.py --list-runs
modal run benchmarks/check_results.py --run-id <RUN_ID>            # summary
modal run benchmarks/check_results.py --run-id <RUN_ID> --detailed # metrics
modal run benchmarks/check_results.py --run-id <RUN_ID> --raw       # full JSON
```

## Configuration Methods

### Method 1: Command-Line Arguments (Quick & Interactive)

Use CLI arguments for quick, interactive benchmarking:

```bash
python benchmarks/run_benchmark.py --engine local \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt \
  --models openai/gpt-4o-mini \
  --test-mode
```

### Method 2: Manifest Files (Reproducible & Complex)

Use JSON manifest files for reproducible, complex benchmark configurations:

```bash
python benchmarks/run_benchmark.py --config manifest.json
```

**Example Manifest** (`manifest.example.json`):

```json
{
  "seed": 42,
  "test_mode": false,
  "tasks": [
    {
      "dataset": "hotpot",
      "optimizer": "few_shot",
      "model": "openai/gpt-4o-mini",
      "model_parameters": { "temperature": 0.7 },
      "optimizer_prompt_params": { "max_trials": 3, "n_samples": 10 }
    },
    {
      "dataset": "hotpot",
      "datasets": {
        "train": { "loader": "hotpot", "count": 150 },
        "validation": { "loader": "hotpot", "split": "validation", "count": 50 }
      },
      "optimizer": "evolutionary_optimizer",
      "model": "openai/gpt-4o-mini",
      "optimizer_prompt_params": { "max_trials": 2, "population_size": 3, "num_generations": 1 }
    }
  ]
}
```

**Manifest Schema:**

- `seed` (optional): Random seed for reproducibility
- `test_mode` (optional): Default test mode for all tasks
- `tasks` (required): Array of task configurations
  - `dataset` (required): Dataset name from available datasets
  - `datasets` (optional): Per-split dataset kwargs (`train` required when present; `validation`/`test` optional). If omitted, the single `dataset` entry is applied to all splits.
  - `optimizer` (required): Optimizer name from available optimizers
  - `model` (required): Model name from configured models
  - `test_mode` (optional): Override test mode for this specific task
  - `model_parameters` (optional): Dict forwarded to the optimizer constructor (e.g., temperature, max_tokens)
  - `optimizer_params` (optional): Dict merged into the optimizer constructor (per-task overrides)
  - `optimizer_prompt_params` (optional): Dict merged into the optimizer's `optimize_prompt` call (per-task overrides)
  - `metrics` (optional): List of metric callables (module.attr) to override the dataset defaults

**When to use manifests:**

- Reproducing exact benchmark configurations
- Running complex multi-task benchmarks
- Version-controlling benchmark configurations
- Sharing benchmark setups with team members
- CI/CD pipelines

Use the per-task `optimizer_params` and `optimizer_prompt_params` fields to enforce rollout budgets (e.g., `max_trials`, iteration caps) or tweak optimizer seeds without modifying the global defaults.

#### Override Cheat Sheet

- `model_parameters`: constructor overrides for model settings (temperature, max_tokens, reasoning_effort). Forwarded to the optimizer constructor as `model_parameters`.
- `optimizer_params`: constructor overrides for the optimizer itself (e.g., change population size, tweak optimizer-specific random seeds, toggle tracing). These are applied once when we instantiate the optimizer class.
- `optimizer_prompt_params`: prompt-iteration overrides (e.g., `max_trials`, `n_samples`, judge batching). These are merged into the subsequent `optimize_prompt` call. When manifests omit this field, the runners derive an `optimizer_prompt_params_override` from the dataset rollout caps so Modal and local runs stay consistent.
- `datasets`: Optional per-split dataset kwargs. Provide `train` (required when using this field) plus optional `validation`/`test`; missing splits reuse train kwargs. If you pass a single object via `dataset`, it applies to all splits.

The manifest JSON schema lives at `benchmarks/configs/manifest.schema.json`.

## Commands

### Parameters

All parameters work for both local and Modal execution:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--engine` | Execution engine (`local`, `modal`) | `local` |
| `--modal` | Alias for `--engine modal` | `false` |
| `--deploy-engine` | Deploy selected engine infrastructure (if supported) and exit | `false` |
| `--config` | Path to manifest JSON (overrides CLI options) | - |
| `--demo-datasets` | Dataset names (e.g., `gsm8k`, `hotpot_300`) | All datasets |
| `--optimizers` | Optimizer names (e.g., `few_shot`, `meta_prompt`) | All optimizers |
| `--models` | Model names (e.g., `openai/gpt-4o-mini`) | All configured models |
| `--test-mode` | Use only 5 examples per dataset (fast) | `false` |
| `--seed` | Random seed for reproducibility | `42` |
| `--max-concurrent` | Max concurrent workers/containers | `5` |
| `--checkpoint-dir` | [Local only] Results directory | `~/.opik_optimizer/benchmark_results` |
| `--resume-run-id` | Resume incomplete run | - |
| `--retry-failed-run-id` | Retry failed tasks from run | - |

### Available Datasets

- `gsm8k` - Math word problems
- `hotpot_300` - Multi-hop question answering
- `ai2_arc` - Science questions
- `ragbench_sentence_relevance` - RAG relevance
- `election_questions` - US election questions
- `medhallu` - Medical hallucination detection
- `rag_hallucinations` - RAG hallucination detection
- `truthful_qa` - Truthfulness evaluation
- `cnn_dailymail` - Summarization

### Available Optimizers

- `few_shot` - Few-shot Bayesian optimizer
- `meta_prompt` - Meta-prompt optimizer
- `evolutionary_optimizer` - Evolutionary optimizer
- `hierarchical_reflective` - Hierarchical Reflective Prompt Optimizer (HRPO)

## Examples

```bash
# Quick local test (1 task, ~5 minutes)
python benchmarks/run_benchmark.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --test-mode \
  --max-concurrent 1

# Full local benchmark (multiple tasks)
python benchmarks/run_benchmark.py \
  --demo-datasets gsm8k hotpot_300 ai2_arc \
  --optimizers few_shot meta_prompt \
  --max-concurrent 4

# Modal cloud execution (high concurrency)
python benchmarks/run_benchmark.py --engine modal \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt evolutionary_optimizer \
  --max-concurrent 10

# Resume interrupted run
python benchmarks/run_benchmark.py --engine modal --resume-run-id run_20250423_153045

# Retry only failed tasks
python benchmarks/run_benchmark.py --engine modal --retry-failed-run-id run_20250423_153045

# Using a manifest file (local)
python benchmarks/run_benchmark.py --config manifest.json

# Using a manifest file (Modal)
python benchmarks/run_benchmark.py --engine modal --config manifest.json --max-concurrent 10
```

## Results

### Local Results

Local results are saved to `~/.opik_optimizer/benchmark_results/<run_id>/`:

- `checkpoint.json` - Task status and results
- Logs in `optimization_*.log` files

### Modal Results

Modal results are stored in Modal Volume and can be checked with:

```bash
# List all runs
modal run benchmarks/check_results.py --list-runs

# View results for a specific run
modal run benchmarks/check_results.py --run-id <RUN_ID>

# Live monitoring (updates every 30 seconds)
modal run benchmarks/check_results.py --run-id <RUN_ID> --watch

# Detailed metrics
modal run benchmarks/check_results.py --run-id <RUN_ID> --detailed
```

## Modal Setup

### Secret (single)

Use one secret for Opik + providers:

```bash
modal secret create opik-benchmarks \
  OPIK_API_KEY="$OPIK_API_KEY" \
  OPIK_URL_OVERRIDE="$OPIK_URL_OVERRIDE" \
  OPIK_WORKSPACE="$OPIK_WORKSPACE" \
  OPENAI_API_KEY="$OPENAI_API_KEY" \
  ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  GOOGLE_API_KEY="$GOOGLE_API_KEY" \
  GEMINI_API_KEY="$GEMINI_API_KEY" \
  OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  --force
```

### Redeploying After Code Changes

If you modify the benchmark code, redeploy both worker and coordinator:

```bash
modal deploy benchmarks/engines/modal/engine.py
modal deploy benchmarks/run_benchmark_modal.py
```

## File Structure

The benchmark system is organized into several modules:

### Architecture Layers

- **`core/`** - Engine-agnostic runtime flow (`planning`, `runtime`, `state`, `evaluation`, `manifest`, `types`)
- **`engines/`** - Execution backends (`local`, `modal`) with capabilities and storage adapters
- **`packages/`** - Dataset/package-specific wiring (agents/prompts/metrics)
- **`utils/`** - Shared sinks/display/logging/helper modules

### Entry Points

- **`run_benchmark.py`** - Main unified engine-driven entry point
  - Compiles CLI/manifest into a canonical plan (`core/planning.py`)
  - Runs/deploys via engine registry (`engines/registry.py`)
- **`run_benchmark_modal.py`** - Modal submission and coordination logic
  - Submits tasks to deployed `engines/modal/engine.py` function
- **`engines/modal/engine.py`** - Modal worker function (deploy with `modal deploy benchmarks/engines/modal/engine.py`)
  - Imports `engines.modal.engine.run_optimization_task`
  - Imports `engines.modal.volume.save_result_to_volume`
- **`check_results.py`** - View Modal results with clickable log links
  - Imports `engines.modal.volume` for loading results
  - Imports `utils.display` for formatting

### Configuration & Core Logic

- **`configs/`** - Manifest schema and example task/generator json files
- **`packages/registry.py`** - Dataset/optimizer/model config registry and package resolution
- **`core/manifest.py`** - Manifest parsing and task-spec compilation
- **`core/types.py`** - Result/task models and preflight report schema
- **`core/state.py`** - Run state and checkpoint persistence
- **`core/runtime.py`** - Engine run/deploy dispatch
- **`utils/task_runner.py`** - Core benchmark task execution logic shared by local + Modal runners

### Packages (`packages/`)

- **`packages/hotpot/`** - Hotpot benchmark package (agent/prompts/metrics wiring)
- **`packages/hover/`** - HoVer benchmark package wiring
- **`packages/ifbench/`** - IFBench benchmark package wiring
- **`packages/pupa/`** - PUPA benchmark package wiring
- **`packages/registry.py`** - Package resolution + central benchmark registry configuration

### Engines (`engines/`)

- **`engines/local/engine.py`** - Local execution engine and runner implementation
- **`engines/local/volume.py`** - Local engine volume adapter placeholder
- **`engines/modal/engine.py`** - Modal engine + worker task execution logic
- **`engines/modal/volume.py`** - Modal Volume storage operations

### Shared Utilities (`utils/`)

- **`utils/logging.py`** - Benchmark run logging and rich console display
- **`utils/display.py`** - Shared display helpers for runtime and result views
- **`utils/sinks.py`** - Event sink interfaces
- **`utils/helpers.py`** - Generic helpers (including run-output serialization)

## Notes

- **Test mode** (`--test-mode`) uses only 5 examples per dataset for quick validation
- **Local execution** runs tasks in parallel using local workers (controlled by `--max-concurrent`)
- **Modal execution** runs tasks in parallel on cloud infrastructure (controlled by `--max-concurrent`)
- Your machine can disconnect after Modal submission - tasks continue in the cloud
- Results are persisted in Modal Volume indefinitely
- Engines are pluggable via `benchmarks/engines/`; current engines are `local` and `modal`
- The unified `benchmarks/run_benchmark.py` entry point uses `--engine` (or `--modal` alias) to choose execution mode
