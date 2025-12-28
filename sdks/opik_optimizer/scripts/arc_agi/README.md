# ARC-AGI HRPO Utilities

This package houses the shared components for running ARC-AGI optimizers:

- `utils/logging_utils.py` – Rich console + debug helpers.
- `utils/visualization.py` – Grid preview helpers.
- `utils/metrics.py` – Metric registry + ScoreResult builders.
- `utils/code_evaluator.py` – Sandbox execution and evaluation cache.
- `utils/prompt_loader.py` – Loads the ARC prompt templates stored under `prompts/`.
- `tasks_optimizer.py` – Entry point that wires everything into HRPO.

The legacy `arc_agi2_hrpo_solver.py` remains as a compatibility shim and just calls into `tasks_optimizer.main()`.
