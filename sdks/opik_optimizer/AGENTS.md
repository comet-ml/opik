# Repository Guidelines

## Project Structure & Module Organization
- `src/opik_optimizer/`: Core library (optimizers, prompt models, utils).
- `tests/`: Pytest suite (`unit/` and `e2e/`). E2E tests may require LLM/API keys.
- `scripts/`: Runnable examples for ADK, LangGraph, LiteLLM, PydanticAI.
- `notebooks/`: Exploratory notebooks; keep outputs out of commits.
- `benchmarks/` and `benchmark_results/`: Performance experiments and outputs.
- `build/` and `dist/`: Generated artifacts; not hand-edited.

## Build, Test, and Development Commands
- Install (editable): `pip install -e .[dev]`
- Lint: `ruff check .`  Format: `ruff format .`
- Types: `mypy --config-file pyproject.toml src`
- Tests (quick): `pytest -q`
- Tests + coverage: `pytest --cov=src/opik_optimizer -q`
- Pre-commit: `pre-commit install && pre-commit run -a`
- Build wheel/sdist: `python -m build` (or `python setup.py sdist bdist_wheel`)

## Coding Style & Naming Conventions
- Python 3.9–3.12. Line length 88, 4-space indent, double quotes (ruff/Black-like).
- Module/files: `snake_case.py`; classes `CamelCase`; constants `UPPER_SNAKE`.
- Keep functions small and typed. Unused variables should be `_prefixed` (ruff).
- Static checks: ruff for lint/format; mypy configured in `pyproject.toml` (note: `mipro_optimizer/` is currently excluded from mypy).

## Testing Guidelines
- Framework: Pytest with `pytest-asyncio` defaults (see `pytest.ini`).
- Naming: tests in `tests/**/test_*.py`; one feature per test module.
- Running E2E: export provider keys (e.g., `OPENAI_API_KEY`) and, if logging to Opik, run `opik configure` and set `OPIK_PROJECT_NAME`.
- Aim for meaningful assertions and fast unit tests; gate new features with tests.

## Commit & Pull Request Guidelines
- Commits: imperative mood and scoped subject (e.g., "opt: speed up FewShot scoring").
- Include rationale in body and reference issues (`Fixes #123`).
- PRs: clear description, reproduction or example, tests, and docs/README updates.
- CI hygiene: run `ruff`, `mypy`, and `pytest` locally; ensure examples in `scripts/` still run.

## Security & Configuration Tips
- Never commit API keys. Use environment variables (e.g., `OPENAI_API_KEY`, `OPIK_API_KEY`).
- Prefer per-example `.env` loading only in local notebooks; exclude sensitive files.
- Network-heavy tests should be marked or isolated; default to mocks for unit tests.
