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
See also `../../AGENTS.md#build-test-and-development-commands` for full monorepo commands.
Run commands from `sdks/python` unless noted.
- `pip install -r tests/test_requirements.txt && pytest tests/unit tests/integration tests/e2e`: install test dependencies and run standard tests.
- `pytest tests/e2e_library_integration tests/e2e_smoke`: run higher-cost integration coverage.
- `cd "$(git rev-parse --show-toplevel)" && make precommit-sdks`: run formatting, linting, and mypy hooks on changed files via the repo-level SDK entrypoint.
- `opik configure --use_local` (or `opik configure`): local SDK configuration for local/dev environments.

## Coding Style & Naming Conventions
- Python target matches the module’s supported versions in `pyproject.toml` (currently 3.10+) with 4-space indentation and line length 88.
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

### E2E test isolation contract (`tests/e2e/`)

The e2e suite runs under `pytest-xdist` with `--dist=loadfile`: each test file is dispatched to one worker, and multiple files run in parallel against a shared backend. Resource names must therefore not collide across files.

- **Backend project name** for a test module comes from `generate_project_name("e2e", __name__)` (helper in `tests/testlib/project_naming.py`, re-exported from `tests.testlib`). Files that need to reference the project (verifier fallback, `search_traces`, etc.) declare at module top:
  ```python
  from ..testlib import generate_project_name
  PROJECT_NAME = generate_project_name("e2e", __name__)
  ```
  Reference `PROJECT_NAME` directly in test bodies — do not introduce a `project_name = PROJECT_NAME` indirection. The autouse `configure_e2e_tests_env` fixture reads `PROJECT_NAME` from each test module and patches `OPIK_PROJECT_NAME`, so the constant is the single source of truth. Files that don't reference the project name in Python don't need to declare anything; the fixture falls back to deriving a name from the module.
- **Alternative projects** — used to exercise the `project_name=` override path — must not embed `generate_project_name(...)` as a `@pytest.mark.parametrize` decorator value. Every worker collects every parametrize id, and xdist's collection-consistency check fails when ids differ across workers; `generate_project_name` returns a different value per process. Parametrize on a boolean and compute the project name inside the test body:
  ```python
  @pytest.mark.parametrize("override_project_name", [True, False])
  def test_xxx(opik_client, override_project_name):
      project_name = (
          generate_project_name("e2e", "anonymization", "override")
          if override_project_name else None
      )
      ...
  ```
  Each CI job has its own backend stack, and `--dist=loadfile` keeps each file on a single worker, so different workers computing different names is not a collision risk in practice.
- **Per-test resources** — datasets, experiments, prompts, temporary projects — already use unique names via the `dataset_name`, `experiment_name`, `prompt_name`, `temporary_project_name` fixtures. Use them; do not invent your own per-test name.
- **No raw `random_chars()` calls for project names.** Reach for it directly only when you need a non-project resource name and there is no fixture for it.
- **No bare hardcoded literals for project / dataset / experiment / prompt / suite / annotation-queue / optimization names anywhere under `tests/e2e/**`.** Strings derived from a unique-per-test fixture (e.g. `f"test_optimization_{dataset_name}"`) are fine — `dataset_name` already injects a random suffix.
- **`configure_e2e_tests_env` is autouse and module-scoped.** Do not narrow it; teardown ordering under xdist will surface narrower scopes as flake.
- **xdist + classes**: with `--dist=loadfile` test classes are *not* split across workers — every test in a file (including those inside `class Test…`) runs on the same worker. Module-level constants and module-scoped fixtures span both module-level and class-level tests in that file. If you switch a file to `--dist=loadscope`, revisit the scope contract.

If you find a hardcoded resource name during code review, treat it as a defect on the same severity as a missing teardown.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run relevant formatter and test commands in this file for Python SDK changes before requesting review.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- Python SDK-specific convention: use SDK-prefixed titles (for example `[OPIK-####] [SDK] ...`) when applicable.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- Python SDK-specific rule: configure credentials via `opik configure`/environment variables, never hardcode them.
