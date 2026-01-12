# Repository Guidelines

## Rule References
- Repo guardrails: `../../.cursor/rules/` (`project-structure.mdc`, `clean-code.mdc`, `git-workflow.mdc`, `test-workflow.mdc`).
- Optimizer SDK rules: `.cursor/rules/` (`architecture.mdc`, `code-structure.mdc`, `dependencies.mdc`, `documentation-style.mdc`, `error-handling.mdc`, `logging.mdc`, `test-best-practices.mdc`, `test-organization.mdc`).

## Architecture & Purpose
- This SDK is a standalone toolchain for prompt and agent optimization using LLMs; it sits alongside Opik and should reuse Opik Python SDK features when available (LiteLLM, evaluation runners, metrics, etc).
- Core flow: `BaseOptimizer.optimize_prompt` validates inputs, evaluates candidates through `task_evaluator.evaluate`, and returns a normalized `OptimizationResult`.
- `OptimizableAgent` is the boundary for provider-specific behavior; integrations live under `integrations/` and must guard optional deps.
- Be careful with API changes and keep behavior consistent across optimizers.

## Project Structure & Module Organization
- Source lives in `src/opik_optimizer/`, with shared components (`base_optimizer.py`, `optimization_result.py`, `optimizable_agent.py`) and optimizer packages (for example `evolutionary_optimizer/`, `meta_prompt_optimizer/`).
- Shared utilities sit under `optimization_config/`, `utils/`, `metrics/`, `mcp_utils/`, and `integrations/`.
- Tests are in `tests/`, split into `tests/unit/` for fast, deterministic coverage and `tests/e2e/optimizers/` for higher-level optimizer runs.
- Examples and runnable scripts live in `scripts/`; `benchmarks/` is a standalone harness for large optimizer runs.
- Fern docs live in `../../apps/opik-documentation/documentation`, with the agent optimization docs under `../../apps/opik-documentation/documentation/fern/docs/agent_optimization`.

## Build, Test, and Development Commands
- `make install-dev`: install the SDK with dev extras into your active environment.
- `make test`: run pytest with coverage; uses `PYTHONPATH=src` and a memory LiteLLM cache for reliability.
- `make precommit`: run lint/format/type hooks via pre-commit across all files.
- `make build`: build sdist and wheel artifacts.
- `make setup-venv`: optional local virtualenv at `.venv`.
- `python scripts/generate_fern_docs.py`: regenerate Fern API docs after public API changes.

## Coding Style & Naming Conventions
- Python 3.10+ only; keep compatibility with `>=3.10,<3.14`.
- Imports order: stdlib → third-party → `opik_optimizer` modules. Use absolute imports across packages.
- Keep optimizer logic in its package; avoid cross-optimizer imports and avoid `helpers.py` dumps.
- Public optimizers should be re-exported from package `__init__.py`.
- Formatting and checks are enforced via `pre-commit` and `pyproject.toml` tooling (mypy, linting).
- Add docstrings for public classes, lifecycle hooks (`optimize_prompt`, `_initialize_population`, `_evaluate_prompt`), and complex helpers; lead with a one-line summary then behavior/side effects.

## Testing Guidelines
- Test framework: `pytest` with `pytest.ini` markers (notably `integration`).
- Naming: `test_<area>__<behavior>` (for example `test_evolutionary_optimizer__improves_score`).
- Seed randomness (default `42`) and avoid real LLM calls in unit tests; use fakes/patches.
- Integration/E2E tests require provider credentials; ensure env vars are set and do not skip these suites.

## Commit & Pull Request Guidelines
- Commit messages follow `<type>: <summary>` (for example `chore: lint`); keep summaries ≤72 chars.
- PRs should include a clear overview, linked issue/Jira, test evidence, and screenshots for UI changes (if any).

## Security & Configuration
- Do not commit API keys. Use environment variables for provider credentials.
- Guard optional integrations with `try/except ImportError` and document required extras.
