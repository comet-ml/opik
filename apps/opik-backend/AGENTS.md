# Repository Guidelines

## Scope & Inheritance
- This file contains backend-specific guidance only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.

## Project Structure & Module Organization
`apps/opik-backend` is the Java backend module in the Opik monorepo.
- Source: `apps/opik-backend/src/main/java`
- Tests: `apps/opik-backend/src/test/java`
- Resources: `apps/opik-backend/src/main/resources`
- Migrations/config: `apps/opik-backend/data-migrations` and local runtime scripts (`scripts/`)
- Related modules in the repository: `apps/opik-frontend`, `apps/opik-documentation`, `sdks/*`, `tests_end_to_end`, `deployment`

## Build, Test, and Development Commands
- `./opik.sh --build` — launch full stack in Docker (full verification environment).
- `./opik.sh --verify` / `./opik.sh --stop` — health check and shutdown.
- `scripts/dev-runner.sh --be-only-restart` — local Java backend process mode with hot workflow.
- `scripts/dev-runner.sh --be-only-start` — start backend quickly without restart.
- `scripts/dev-runner.sh --build-be` — rebuild backend dependencies/artifacts only.
- `scripts/dev-runner.sh --lint-be` — run backend lint/format checks.
- `scripts/dev-runner.sh --migrate` — run DB migrations (MySQL + ClickHouse).
- `cd apps/opik-backend && mvn test` — unit/integration tests (includes testcontainers-backed suites).
- `cd apps/opik-backend && mvn spotless:apply` — apply Java formatting.

## Coding Style & Naming Conventions
- Backend follows a layered design: resource → service → DAO.
- Use constructor DI (`@Inject`) and existing Guice modules; keep layer boundaries intact.
- Java style follows Spotless defaults (format only changed files, avoid blanket reformatting).
- Use clear names, camelCase for methods/variables, PascalCase for classes.
- Prefer immutable collections (`List.of`, `Set.of`, `Map.of`) and logging with quoted values in structured logs.

## Testing Guidelines
- Frameworks: JUnit, Mockito, and Testcontainers under Maven test lifecycle.
- Test naming: `*Test.java` in `src/test/java` (use descriptive class names for integration boundaries).
- Add/adjust tests for changed behavior before PR.
- For cross-stack changes, validate locally with the backend running and required services (MySQL, ClickHouse, Redis) available.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run backend format and test commands in this file before requesting review.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- Backend-specific convention: use `[OPIK-####] [BE]` in commit/PR titles when applicable.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- Backend-specific check: run migrations and verify `/healthcheck` after major backend changes.
