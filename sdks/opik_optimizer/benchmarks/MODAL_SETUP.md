# Running Benchmarks on Modal

Run opik_optimizer benchmarks on Modal's serverless platform without keeping your local machine running.

## Setup

1. Install and authenticate:
```bash
pip install modal
modal setup
```

2. Configure secrets at https://modal.com/secrets:
```bash
modal secret create opik-credentials \
  OPIK_API_KEY=your_key \
  OPIK_WORKSPACE=your_workspace

modal secret create llm-api-keys \
  OPENAI_API_KEY=your_key \
  ANTHROPIC_API_KEY=your_key
```

## Usage

**Deploy worker (once):**
```bash
modal deploy benchmarks/benchmark_worker.py
```

**Submit benchmarks:**
```bash
# Test mode
python benchmarks/submit_benchmarks.py --test-mode

# Custom configuration
python benchmarks/submit_benchmarks.py \
  --demo-datasets gsm8k hotpot_300 \
  --optimizers few_shot meta_prompt \
  --models openai/gpt-4o-mini \
  --max-concurrent 5
```

**Check results (anytime):**
```bash
# List runs
modal run benchmarks/check_results.py --list-runs

# View specific run
modal run benchmarks/check_results.py --run-id <run_id>

# Watch live updates
modal run benchmarks/check_results.py --run-id <run_id> --watch
```

## Monitoring

- Dashboard: https://modal.com/apps
- CLI: `modal app logs opik-optimizer-benchmarks`
