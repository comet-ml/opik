# Repository Guidelines

## Scope & Inheritance
- This is the canonical monorepo guide for shared contribution policy.
- Module-level `AGENTS.md` files should keep only module-specific guidance and reference this file for shared rules.

## Project Structure & Module Organization
This repository is a multi-module Opik codebase. Main areas:
- `apps/opik-backend`: Java backend (source in `src/main/java`, tests in `src/test/java`).
- `apps/opik-frontend`: React/TypeScript frontend (`src` and related assets/config).
- `apps/opik-documentation`: documentation website and generated API docs.
- `sdks/python`, `sdks/typescript`, `sdks/opik_optimizer`: SDK packages and examples.
- `deployment`, `scripts`, `extensions`: infra, tooling, and integration extension points.
- `tests_end_to_end`, `tests_load`: cross-stack and performance test suites.

## Build, Test, and Development Commands
- `./opik.sh` — run default local stack via Docker.
- `./opik.sh --build` — rebuild images and run the full stack.
- `./opik.sh --verify` — run stack health checks.
- `./opik.sh --stop` — stop local services.
- `scripts/dev-runner.sh` — fast local process mode (BE + FE).
- `scripts/dev-runner.sh --be-only-restart` — backend-focused local development.
- `scripts/dev-runner.sh --build-be` / `--build-fe` — build one side only.
- `scripts/dev-runner.sh --lint-be` — Java lint/format checks.
- `scripts/dev-runner.sh --lint-fe` — frontend lint/type checks via `npm`.
- `cd apps/opik-frontend && npm run lint && npm run test` — run ESLint + Vitest.
- `cd apps/opik-frontend && npm run build` — production frontend build.
- `cd apps/opik-backend && mvn test` — backend unit/integration tests.
- `cd apps/opik-backend && mvn spotless:apply` — apply Java formatting.
- `cd sdks/python && pip install -r tests/test_requirements.txt && pip install -r tests/unit/test_requirements.txt && pytest tests/unit tests/e2e` — Python SDK test suites.
- `cd sdks/python && pre-commit run --all-files` — lint/format gate for Python changes.
- `cd sdks/typescript && npm run lint && npm run test && npm run build` — TS SDK checks.
- `cd tests_end_to_end/typescript-tests && TEST_SUITE=sanity npm test` — cross-stack Playwright sanity suite.

## Coding Style & Naming Conventions
- Use existing formatters: Prettier/ESLint/Stylelint (frontend), Spotless (backend), and repository Python tooling.
- Do not run blanket reformatting; prefer minimal, scoped edits.
- Frontend: TypeScript + React with descriptive names (`camelCase` variables/functions, `PascalCase` components).
- Backend: follow Java conventions, clear package boundaries, and existing test/data patterns.
- Python: avoid abbreviations, use explicit naming, and keep private helpers clearly prefixed (`_name`).

## Testing Guidelines
- Frameworks: Vitest (frontend + TS SDK), Playwright (frontend E2E), Maven/JUnit (backend), pytest (Python SDK).
- Naming conventions:
  - Java: `*Test.java` in `apps/opik-backend/src/test/java`.
  - Python: `test_*.py` grouped under `tests/unit`, `tests/integration`, `tests/e2e`.
  - TypeScript/JS: `*.test.ts` under `tests`.
- Cover changed behavior with unit tests first; add integration/E2E when cross-layer behavior changes.
- For SDK integration tests requiring external services, document any required keys in the PR.
- For end-to-end execution across backend/frontend, use:
  - `./opik.sh` or `scripts/dev-runner.sh` to start local services
  - `tests_end_to_end/README.md` for suite-specific commands and helper-service setup

## Agent Contribution Workflow
- This repository is a monorepo; submodule `AGENTS.md` files inherit this workflow by default.
- Read `CONTRIBUTING.md` and `.github/pull_request_template.md` before editing or opening a PR.
- Link tracked work in PRs with `Fixes #<id>` or `Resolves #<id>`.
- Use GitHub CLI for PR flow and prefer draft PRs first (`gh pr create --draft`).
- Prefer worktrees for parallel workstreams when touching multiple components.
- Run relevant unit tests and formatters for the touched area before requesting review.

## Commit & Pull Request Guidelines
- Recent history uses ticket/component prefixes such as `[OPIK-1234] [BE] ...`, `[FE]`, `[SDK]`, `[DOCS]`, `[NA]`, `[INFRA]`.
- PR descriptions should include: change summary, test coverage run, and linked issue references (`Resolves #...`).
- Follow `pull_request_template.md` sections: Details, checklist, Issues, Testing, Documentation.
- Include screenshots or short recordings for user-visible UI changes when possible.

## Security & Configuration Tips
- Keep secrets and API keys out of source control; use local `.env` or shell variables.
- For local self-hosted testing, ensure dependencies are configured (MySQL, ClickHouse, Redis) before running backend tests.
- Prefer `opik configure --use_local` when running SDK examples against your local deployment.
