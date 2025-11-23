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
python scripts/benchmarks/modal_setup_helper.py  # prints secret/deploy instructions
# Deploy worker (once, after setting secrets):
# modal deploy benchmarks/runners/benchmark_worker.py
```

## Manifests

- `tasks.example.json`: two explicit tasks — one runs FewShot on a 10-sample HotpotQA train slice using the Hotpot F1 metric; the other runs Evolutionary on `tiny_test` with a Levenshtein-style metric (`scripts.benchmarks.custom_metrics.levenshtein_on_label`) and a small per-split override.
- `generator.example.json`: generator that expands to 4 tasks (2 dataset variants × FewShot/Evolutionary) with a Hotpot F1 metrics override.
- `gepa_smoke.manifest.json`: GEPA-style smoke (small counts) across Hotpot/HoVer/IFBench/PUPA with FewShot + a Hierarchical check.
- `gepa_smoke.generator.json`: GEPA-style smoke generator expanding to all core optimizers (FewShot, Evolutionary, GEPA, Hierarchical, MetaPrompt) on the smoke splits.
- `gepa_smoke.generator.json`: generator that expands to smoke-scale tasks (train/val/test samples) across Hotpot/HoVer/PUPA with multiple optimizers; test-mode true.
- `gepa_full.generator.json`: generator that expands to full-scale tasks (train/val/test splits per paper) across Hotpot/HoVer/IFBench/PUPA with multiple optimizers and models (gpt-4.1-mini + openrouter/qwen-3-8b).
