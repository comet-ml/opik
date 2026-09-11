# Benchmark Manifest Examples

Two ready-to-run manifests live in this directory. They use `tiny_test` with single trials/samples to stay fast and cheap. Run commands from `sdks/opik_optimizer`:

## Local runs

```bash
PYTHONPATH=. python benchmarks/run_benchmark.py --config scripts/benchmarks/tasks.example.json
PYTHONPATH=. python benchmarks/run_benchmark.py --config scripts/benchmarks/generator.example.json
```

## Modal runs

```bash
PYTHONPATH=. python benchmarks/run_benchmark.py --modal --config scripts/benchmarks/tasks.example.json
PYTHONPATH=. python benchmarks/run_benchmark.py --modal --config scripts/benchmarks/generator.example.json
# Deploy worker (once, after setting secrets):
# modal deploy benchmarks/engines/modal/engine.py
# Deploy coordinator (redo after coordinator code changes):
# modal deploy benchmarks/run_benchmark_modal.py
```

## Manifests

- `tasks.example.json`: explicit tasks including a FewShot run on a 10-sample HotpotQA train slice using `benchmarks.packages.hotpot.metrics.hotpot_f1`, and an Evolutionary run on `tiny_test` using `benchmarks.configs.registry.create_levenshtein_ratio_metric`.
- `generator.example.json`: generator that expands to 4 tasks (2 dataset variants × FewShot/Evolutionary) with a Hotpot F1 metrics override.
