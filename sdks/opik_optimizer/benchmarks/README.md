# Optimizer Benchmarks

Unified benchmark runner for testing prompt optimizers locally or on Modal cloud.

## Quick Start

### Local Execution

Run benchmarks on your local machine:

```bash
# Single dataset, single optimizer (test mode)
python run_benchmark.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

# Multiple datasets and optimizers
python run_benchmark.py \
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

# Configure secrets using Modal CLI:
modal secret create opik-credentials \
  OPIK_API_KEY=<your-opik-api-key> \
  OPIK_WORKSPACE=<your-workspace-name>

modal secret create llm-api-keys \
  OPENAI_API_KEY=<your-openai-key> \
  ANTHROPIC_API_KEY=<your-anthropic-key>

# 1. Deploy the worker (one time, or when code changes)
modal deploy benchmark_worker.py

# 2. Submit benchmark tasks (note the --modal flag)
python run_benchmark.py --modal \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

# 3. Check results (with clickable logs links)
modal run check_results.py --list-runs
modal run check_results.py --run-id <RUN_ID>
modal run check_results.py --run-id <RUN_ID> --watch --detailed
```

## Commands

### Parameters

All parameters work for both local and Modal execution:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--modal` | Run on Modal cloud (omit for local execution) | `false` |
| `--demo-datasets` | Dataset names (e.g., `gsm8k`, `hotpot_300`) | All datasets |
| `--optimizers` | Optimizer names (e.g., `few_shot`, `meta_prompt`) | All optimizers |
| `--models` | Model names (e.g., `openai/gpt-4o-mini`) | All configured models |
| `--test-mode` | Use only 5 examples per dataset (fast) | `false` |
| `--seed` | Random seed for reproducibility | `42` |
| `--max-concurrent` | Max concurrent workers/containers | `5` |
| `--checkpoint-dir` | [Local only] Results directory | `./benchmark_results` |
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
- `hierarchical_reflective` - Hierarchical reflective optimizer

## Examples

```bash
# Quick local test (1 task, ~5 minutes)
python run_benchmark.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --test-mode \
  --max-concurrent 1

# Full local benchmark (multiple tasks)
python run_benchmark.py \
  --demo-datasets gsm8k hotpot_300 ai2_arc \
  --optimizers few_shot meta_prompt \
  --max-concurrent 4

# Modal cloud execution (high concurrency)
python run_benchmark.py --modal \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt evolutionary_optimizer \
  --max-concurrent 10

# Resume interrupted run
python run_benchmark.py --modal --resume-run-id run_20250423_153045

# Retry only failed tasks
python run_benchmark.py --modal --retry-failed-run-id run_20250423_153045
```

## Results

### Local Results

Local results are saved to `./benchmark_results/<run_id>/`:
- `checkpoint.json` - Task status and results
- Logs in `optimization_*.log` files

### Modal Results

Modal results are stored in Modal Volume and can be checked with:

```bash
# List all runs
modal run check_results.py --list-runs

# View results for a specific run
modal run check_results.py --run-id <RUN_ID>

# Live monitoring (updates every 30 seconds)
modal run check_results.py --run-id <RUN_ID> --watch

# Detailed metrics
modal run check_results.py --run-id <RUN_ID> --detailed
```

## Modal Setup

### Required Secrets

Modal requires two secrets to be configured. You can create them using the Modal CLI:

```bash
# 1. Opik credentials
modal secret create opik-credentials \
  OPIK_API_KEY=<your-opik-api-key> \
  OPIK_WORKSPACE=<your-workspace-name>

# 2. LLM provider API keys
modal secret create llm-api-keys \
  OPENAI_API_KEY=<your-openai-key> \
  ANTHROPIC_API_KEY=<your-anthropic-key>
```

Alternatively, configure them in the [Modal dashboard](https://modal.com/secrets):

1. **`opik-credentials`** - Opik API credentials
   - `OPIK_API_KEY` - Your Opik API key
   - `OPIK_WORKSPACE` - Your Opik workspace name

2. **`llm-api-keys`** - LLM provider API keys
   - `OPENAI_API_KEY` - OpenAI API key (required for GPT models)
   - `ANTHROPIC_API_KEY` - Anthropic API key (if using Claude models)
   - Other LLM provider keys as needed

### Redeploying After Code Changes

If you modify the benchmark code, you must redeploy the worker:

```bash
modal deploy benchmark_worker.py
```

This updates the deployed worker with your latest code changes.

## File Structure

The benchmark system is organized into several modules:

### Entry Points
- **`run_benchmark.py`** - Main unified entry point (routes to local or Modal execution based on `--modal` flag)
  - Calls `run_benchmark_local.py` for local execution
  - Calls `run_benchmark_modal.py` for Modal execution
- **`run_benchmark_local.py`** - Local execution logic
  - Imports `local.runner.BenchmarkRunner`
  - Imports `utils.validation.ask_for_input_confirmation`
- **`run_benchmark_modal.py`** - Modal submission and coordination logic
  - Submits tasks to deployed `benchmark_worker.py` function
- **`benchmark_worker.py`** - Modal worker function (deploy with `modal deploy benchmark_worker.py`)
  - Imports `modal_utils.worker_core.run_optimization_task`
  - Imports `modal_utils.storage.save_result_to_volume`
- **`check_results.py`** - View Modal results with clickable logs links
  - Imports `modal_utils.storage` for loading results
  - Imports `modal_utils.display` for formatting

### Configuration & Core Logic
- **`benchmark_config.py`** - Dataset and optimizer configurations
- **`benchmark_task.py`** - Core task execution logic

### Local Execution (`local/`)
- **`local/runner.py`** - Local benchmark runner implementation
  - Imports `local.checkpoint` and `local.logging`
- **`local/checkpoint.py`** - Checkpoint management for local runs
- **`local/logging.py`** - Local logging utilities

### Modal Execution (`modal_utils/`)
- **`modal_utils/coordinator.py`** - Task coordination utilities (helper functions for task generation)
- **`modal_utils/worker_core.py`** - Core worker execution logic (called by `benchmark_worker.py`)
- **`modal_utils/storage.py`** - Modal Volume storage operations
  - Used by `benchmark_worker.py` and `check_results.py`
  - Imports `utils.serialization.make_serializable`
- **`modal_utils/display.py`** - Results display and formatting (used by `check_results.py`)

### Shared Utilities (`utils/`)
- **`utils/validation.py`** - Input validation and confirmation (used by `run_benchmark_local.py`)
- **`utils/serialization.py`** - Serialization helpers for results (used by `modal_utils/storage.py`)

## Notes

- **Test mode** (`--test-mode`) uses only 5 examples per dataset for quick validation
- **Local execution** runs tasks in parallel using local workers (controlled by `--max-concurrent`)
- **Modal execution** runs tasks in parallel on cloud infrastructure (controlled by `--max-concurrent`)
- Your machine can disconnect after Modal submission - tasks continue in the cloud
- Results are persisted in Modal Volume indefinitely
- The unified `run_benchmark.py` entry point automatically routes to the appropriate execution mode
