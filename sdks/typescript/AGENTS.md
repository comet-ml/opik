# Repository Guidelines

## Scope & Inheritance
- This file contains TypeScript SDK specifics only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.

## Project Structure & Module Organization
`sdks/typescript` contains the TypeScript/JavaScript Opik SDK.

- `src/opik/...` holds the SDK implementation, entry exports, and generated API client types.
- `tests/...` contains test suites split by area (`unit`, `integration`, `evaluation`, `experiment`, `dataset`), with test files ending in `*.test.ts`.
- `examples/...` has runnable usage examples and integration snippets.
- `design/...` stores architecture notes, testing guidance, and API design docs.
- Config files (`tsconfig.json`, `vite.config.ts`, `vitest.config.ts`, `tsup.config.js`, `eslint.config.js`) define local build/lint/test behavior.

## Build, Test, and Development Commands
See also `../../AGENTS.md#build-test-and-development-commands` for full monorepo commands.

- `npm install` installs dependencies.
- `npm run lint` runs ESLint across TypeScript source and tests.
- `npm run format` applies Prettier formatting.
- `npm run typecheck` performs a compile-only TypeScript check.
- `npm run test` runs the standard unit test suite.
- `npm run test:integration` runs integration tests that may hit real Opik endpoints.
- `npm run build` compiles the distributable with tsup.
- `npm run watch` starts incremental rebuild mode during local development.

For integration tests, create `.env` with `OPIK_API_KEY` and follow `tests/integration/api/README.md`.

## Coding Style & Naming Conventions
- Use existing conventions: 2-space indentation, semicolon-terminated TypeScript where already used.
- Prefer `camelCase` for variables/functions, `PascalCase` for types/classes, and `kebab-case` file names.
- Keep tests and production code aligned to existing module boundaries (`src/opik/...`, `tests/...`).
- Add `*.test.ts` files for behavior changes and keep them close to the feature area.

## Testing Guidelines
- Primary framework is Vitest.
- Default command excludes integration tests; run both `npm run test` and `npm run test:integration` when you modify core client, transport, or API behavior.
- Use `tests/integration/api/README.md` for environment expectations before running networked tests.
- Keep fixture/config files deterministic and avoid committing secrets or private API URLs.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run relevant formatter and test commands in this file for TypeScript SDK changes before requesting review.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- TypeScript SDK-specific convention: use SDK-tagged commit/PR titles when applicable.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- TypeScript SDK-specific checks: respect `engines.node >= 18` and use package scripts over ad hoc commands.
