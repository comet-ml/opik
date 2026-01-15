# Repository Guidelines

## Rule References
- Repo guardrails: `.cursor/rules/` (`project-structure.mdc`, `clean-code.mdc`, `git-workflow.mdc`, `test-workflow.mdc`).
- Backend: `apps/opik-backend/.cursor/rules/` (e.g., `tech_stack.mdc`, `code_quality.mdc`, `api_design.mdc`).
- Frontend: `apps/opik-frontend/.cursor/rules/` (`tech-stack.mdc`, `code-quality.mdc`, `unit-testing.mdc`, `responsive-design.mdc`).
- Python SDK: `sdks/python/.cursor/rules/` (`code-structure.mdc`, `test-best-practices.mdc`, `documentation-style.mdc`).
- Opik Optimizer SDK: `sdks/opik_optimizer/.cursor/rules/` (`architecture.mdc`, `code-structure.mdc`, `dependencies.mdc`, `documentation-style.mdc`, `error-handling.mdc`, `logging.mdc`, `test-best-practices.mdc`, `test-organization.mdc`).
- TypeScript SDK: `sdks/typescript/.cursor/rules/` (`overview.mdc`, `code-structure.mdc`, `test-best-practices.mdc`).

## Project Structure & Module Organization
- `apps/opik-backend`: Dropwizard services; migrations in `data-migrations`; see `apps/opik-backend/.cursor/rules/architecture.mdc`.
- `apps/opik-frontend`: Vite + React UI; modules under `src/features`; align with `apps/opik-frontend/.cursor/rules/frontend_rules.mdc`.
- SDKs (`sdks/python`, `sdks/typescript`, `sdks/opik_optimizer`): language-specific code with co-located `tests/` and rules in `sdks/*/.cursor/rules/`.
- `tests_end_to_end`: Playwright E2E TypeScript tests (`typescript-tests/`); helper service (`test-helper-service/`); installer utilities (`installer_utils/`).
- `scripts/` and `opik.sh`: Orchestrate local stacks, builds, linting.

## Build, Test, and Development Commands
- Stack via Docker: `./opik.sh --build` (first run) then `./opik.sh`.
- Hot-reload loop: `scripts/dev-runner.sh` with `--start`, `--build-fe`, `--build-be` as needed.
- Backend build/tests: `mvn verify` in `apps/opik-backend`; respect `apps/opik-backend/.cursor/rules/code_quality.mdc`.
- Frontend checks: `npm install`, `npm lint`, `npm test`, `npm build`; follow `apps/opik-frontend/.cursor/rules/code-quality.mdc`, `apps/opik-frontend/.cursor/rules/unit-testing.mdc`.
- Python SDK: `pip install -e .[dev]`, `ruff check`, `pytest`; align with `sdks/python/.cursor/rules/test-best-practices.mdc`.
- Opik Optimizer SDK: `make install-dev`, `make test`, `make precommit` from `sdks/opik_optimizer`; follow the optimizer rules in `sdks/opik_optimizer/.cursor/rules/` (architecture, code structure, dependencies, testing).
- TypeScript SDK: `npm install`, `npm lint`, `npm test`; observe `sdks/typescript/.cursor/rules/code-structure.mdc`.

## Coding Style & Naming Conventions
- Java backend: Spotless (Google style); run `mvn spotless:apply`; check `apps/opik-backend/.cursor/rules/code_style.mdc`, `apps/opik-backend/.cursor/rules/logging.mdc`, `apps/opik-backend/.cursor/rules/api_design.mdc`.
- Frontend: ESLint + Prettier; PascalCase components, camelCase hooks, kebab-case styles; consult `apps/opik-frontend/.cursor/rules/ui-components.mdc`, `apps/opik-frontend/.cursor/rules/state-management.mdc`, `apps/opik-frontend/.cursor/rules/forms.mdc`.
- Python SDK: PEP 8 enforced by Ruff; snake_case tests/functions; review `sdks/python/.cursor/rules/code-structure.mdc`, `sdks/python/.cursor/rules/error-handling.mdc`, `sdks/python/.cursor/rules/logging.mdc`.
- Prefer environment variables over committed secrets; follow `.cursor/rules/tech-stack.mdc`.

## Testing Guidelines
- Backend tests: `mvn test`; follow `apps/opik-backend/.cursor/rules/testing.mdc`, `apps/opik-backend/.cursor/rules/test_assertions.mdc`.
- Frontend tests: `npm test`; UI smoke via `npm e2e`; include `apps/opik-frontend/.cursor/rules/accessibility-testing.mdc`.
- End-to-end: from `tests_end_to_end`, set `PYTHONPATH='.'`, run `pytest`; sanity subset `pytest -m sanity`.
- Capture coverage decisions in PRs and refresh fixtures only for API changes per `.cursor/rules/test-workflow.mdc`.

## Documentation Guidelines
- Fern docs (`apps/opik-documentation/documentation`): `npm install`, `npm dev`; apply `.cursor/rules/feature-documentation-workflow.mdc`, `.cursor/rules/integration-documentation.mdc`.
- Python SDK docs (`apps/opik-documentation/python-sdk-docs`): build with `make html`; use `sdks/python/.cursor/rules/documentation-style.mdc` and mirror Fern updates.

## Commit & Pull Request Guidelines
- Commits follow `<type>: <summary>` (≤72 chars, imperative) per `.cursor/rules/git-workflow.mdc`.
- Squash format-only noise; separate backend/frontend/SDK commits when practical.
- PRs require overview, test evidence, linked issue/Jira, UI screenshots; see `.cursor/rules/feature-documentation-workflow.mdc`.
- Run lint/tests before review and note skips with rule references.
