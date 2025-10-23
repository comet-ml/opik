# Optimizer Benchmarks

Unified benchmark runner for testing prompt optimizers locally or on Modal cloud.

## Quick Start

### Local Execution

Run benchmarks on your local machine:

```bash
# Single dataset, single optimizer (test mode)
python run.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

# Multiple datasets and optimizers
python run.py \
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

# 2. Submit benchmark tasks
python run.py --modal \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --models openai/gpt-4o-mini \
  --test-mode \
  --max-concurrent 1

# 3. Check results
modal run check_results.py --list-runs
modal run check_results.py --run-id <RUN_ID>
modal run check_results.py --run-id <RUN_ID> --watch --detailed
```

## Commands

### Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--modal` | Run on Modal cloud instead of locally | `false` |
| `--demo-datasets` | Dataset names (e.g., `gsm8k`, `hotpot_300`) | All datasets |
| `--optimizers` | Optimizer names (e.g., `few_shot`, `meta_prompt`) | All optimizers |
| `--models` | Model names (e.g., `openai/gpt-4o-mini`) | All configured models |
| `--test-mode` | Use only 5 examples per dataset (fast) | `false` |
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
python run.py \
  --demo-datasets gsm8k \
  --optimizers few_shot \
  --test-mode \
  --max-concurrent 1

# Full local benchmark (multiple tasks)
python run.py \
  --demo-datasets gsm8k hotpot_300 ai2_arc \
  --optimizers few_shot meta_prompt \
  --max-concurrent 4

# Modal cloud execution (high concurrency)
python run.py --modal \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt evolutionary_optimizer \
  --max-concurrent 10

# Resume interrupted run
python run.py --modal --resume-run-id run_20250423_153045

# Retry only failed tasks
python run.py --modal --retry-failed-run-id run_20250423_153045
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

## Notes

- **Test mode** (`--test-mode`) uses only 5 examples per dataset for quick validation
- **Local execution** runs tasks sequentially on your machine
- **Modal execution** runs tasks in parallel on cloud infrastructure
- Your machine can disconnect after Modal submission - tasks continue in the cloud
- Results are persisted in Modal Volume indefinitely
- See [MODAL_DEPLOYMENT.md](MODAL_DEPLOYMENT.md) for detailed Modal setup instructions
