# Repository Guidelines

## Scope & Inheritance
- This file contains Python SDK specifics only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.

## Project Structure & Module Organization
This SDK lives under `sdks/python`.
- `src/opik/`: Python package source.
- `tests/`: test suite, organized into `unit/`, `integration/`, `e2e/`, `e2e_library_integration/`, and `e2e_smoke/`.
- `examples/`: runnable integration examples and recipes.
- `design/` and `outputs/`: design assets and generated artifacts.
- `README.md`: SDK overview and contributor entry points.

## Build, Test, and Development Commands
Run commands from `sdks/python` unless noted.
- `pip install -r tests/test_requirements.txt && pytest tests/unit tests/integration tests/e2e`: install test dependencies and run standard tests.
- `pytest tests/e2e_library_integration tests/e2e_smoke`: run higher-cost integration coverage.
- `pre-commit run --all-files`: run formatting, linting, and mypy hooks.
- `opik configure --use_local` (or `opik configure`): local SDK configuration for local/dev environments.

## Coding Style & Naming Conventions
- Python target is 3.10+ with 4-space indentation and line length 88.
- Primary style tooling: `ruff` and `ruff format` (configured in `.ruff.toml`) plus `mypy` (via pre-commit).
- Prefer explicit names, avoid abbreviations; avoid `utils.py`/`helpers.py` style catch-alls.
- Prefer module-style imports over single-name imports in new code.
- Keep names private with `_` prefix only when not used outside the module.
- Keep comments focused on intent (“why”), not mechanics (“what”).

## Testing Guidelines
- Prefer unit tests (`tests/unit`) for behavior changes.
- Add integration tests when touching backend or integration behavior, and e2e tests for cross-system flows.
- Use existing fixture patterns in `tests/unit` and `tests/library_integration`.
- Run focused suites before PR submission; avoid relying only on broad e2e runs when unit tests suffice.
- File naming: `test_*.py` under `tests/<category>/`.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run relevant formatter and test commands in this file for Python SDK changes before requesting review.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- Python SDK-specific convention: use SDK-prefixed titles (for example `[OPIK-####] [SDK] ...`) when applicable.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- Python SDK-specific rule: configure credentials via `opik configure`/environment variables, never hardcode them.
