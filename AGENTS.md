# Repository Guidelines

## Rule References
- Repo guardrails: `.cursor/rules/` (`project-structure.mdc`, `clean-code.mdc`, `git-workflow.mdc`, `test-workflow.mdc`).
- Backend: `apps/opik-backend/.cursor/rules/` (e.g., `tech_stack.mdc`, `code_quality.mdc`, `api_design.mdc`).
- Frontend: `apps/opik-frontend/.cursor/rules/` (`tech-stack.mdc`, `code-quality.mdc`, `unit-testing.mdc`).
- Python SDK: `sdks/python/.cursor/rules/` (`code-structure.mdc`, `test-best-practices.mdc`, `documentation-style.mdc`).
- TypeScript SDK: `sdks/typescript/.cursor/rules/` (`overview.mdc`, `code-structure.mdc`, `test-best-practices.mdc`).

## Project Structure & Module Organization
- `apps/opik-backend`: Dropwizard services; migrations in `data-migrations`; see `.cursor/rules/architecture.mdc`.
- `apps/opik-frontend`: Vite + React UI; modules under `src/features`; align with `.cursor/rules/frontend_rules.mdc`.
- SDKs (`sdks/python`, `sdks/typescript`, `sdks/opik_optimizer`): language-specific code with co-located `tests/` and rules in `sdks/*/.cursor/rules/`.
- `tests_end_to_end`: Pytest suites (`tests/`) and Playwright flows (`tests_end_to_end_ts/`); fixtures in `installer_utils/`, `page_objects/`.
- `scripts/` and `opik.sh`: Orchestrate local stacks, builds, linting.

## Build, Test, and Development Commands
- Stack via Docker: `./opik.sh --build` (first run) then `./opik.sh`.
- Hot-reload loop: `scripts/dev-runner.sh` with `--start`, `--build-fe`, `--build-be` as needed.
- Backend build/tests: `mvn verify` in `apps/opik-backend`; respect `code_quality.mdc`.
- Frontend checks: `pnpm install`, `pnpm lint`, `pnpm test`, `pnpm build`; follow `code-quality.mdc`, `unit-testing.mdc`.
- Python SDK: `uv pip install -e .[dev]`, `ruff check`, `pytest`; align with `test-best-practices.mdc`.
- TypeScript SDK: `pnpm install`, `pnpm lint`, `pnpm test`; observe `code-structure.mdc`.

## Coding Style & Naming Conventions
- Java backend: Spotless (Google style); run `mvn spotless:apply`; check `code_style.mdc`, `logging.mdc`, `api_design.mdc`.
- Frontend: ESLint + Prettier; PascalCase components, camelCase hooks, kebab-case styles; consult `ui-components.mdc`, `state-management.mdc`, `forms.mdc`.
- Python SDK: PEP 8 enforced by Ruff; snake_case tests/functions; review `code-structure.mdc`, `error-handling.mdc`, `logging.mdc`.
- Prefer environment variables over committed secrets; follow `tech-stack.mdc`.

## Testing Guidelines
- Backend tests: `mvn test`; follow `testing.mdc`, `test_assertions.mdc`.
- Frontend tests: `npm test`; UI smoke via `npm e2e`; include `accessibility-testing.mdc`.
- End-to-end: from `tests_end_to_end`, set `PYTHONPATH='.'`, run `pytest`; sanity subset `pytest -m sanity`.
- Capture coverage decisions in PRs and refresh fixtures only for API changes per `test-workflow.mdc`.

## Documentation Guidelines
- Fern docs (`apps/opik-documentation/documentation`): `pnpm install`, `pnpm dev`; apply `feature-documentation-workflow.mdc`, `integration-documentation.mdc`.
- Python SDK docs (`apps/opik-documentation/python-sdk-docs`): build with `make html`; use `documentation-style.mdc` and mirror Fern updates.

## Commit & Pull Request Guidelines
- Commits follow `<type>: <summary>` (≤72 chars, imperative) per `git-workflow.mdc`.
- Squash format-only noise; separate backend/frontend/SDK commits when practical.
- PRs require overview, test evidence, linked issue/Jira, UI screenshots; see `feature-documentation-workflow.mdc`.
- Run lint/tests before review and note skips with rule references.
