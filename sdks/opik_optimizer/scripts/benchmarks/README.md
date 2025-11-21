# Benchmark Manifest Examples

Two ready-to-run manifests live in this directory. They use `tiny_test` with single trials/samples to stay fast and cheap. Run commands from `sdks/opik_optimizer`:

## Local runs
```bash
PYTHONPATH=. python benchmarks/runners/run_benchmark.py --config scripts/benchmarks/tasks.example.json
PYTHONPATH=. python benchmarks/runners/run_benchmark.py --config scripts/benchmarks/generator.example.json
```

## Modal runs
```bash
PYTHONPATH=. python benchmarks/runners/run_benchmark.py --modal --config scripts/benchmarks/tasks.example.json
PYTHONPATH=. python benchmarks/runners/run_benchmark.py --modal --config scripts/benchmarks/generator.example.json
```

## Manifests
- `tasks.example.json`: two explicit tasks — one runs FewShot on a 10-sample HotpotQA train slice using the Hotpot F1 metric; the other runs Evolutionary on `tiny_test` with a Levenshtein-style metric (`scripts.benchmarks.custom_metrics.levenshtein_on_label`) and a small per-split override.
- `generator.example.json`: generator that expands to 4 tasks (2 dataset variants × FewShot/Evolutionary) with a Hotpot F1 metrics override.
