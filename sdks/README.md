# SDKs

`sdks/` contains all client/automation SDK packages for Opik integrations and publishing.

## Module map
- `python`: Python SDK (`setup.py`, `src/`, `tests/`) for tracing, datasets, evaluations.
- `typescript`: TypeScript/JavaScript SDK with Playwright-compatible test coverage.
- `opik_optimizer`: Prompt/agent optimization tooling and utilities.
- `code_generation`: OpenAPI/SDK generation artifacts and templates.

## Quick commands
- Python SDK:
  - Install deps: `pip install -e sdks/python`
  - Configure local backend: `opik configure --use_local`
  - Run tests: `cd sdks/python && pip install -r tests/test_requirements.txt && pip install -r tests/unit/test_requirements.txt && pytest tests/unit tests/e2e`
- TypeScript SDK:
  - Install: `cd sdks/typescript && npm install`
  - Lint/test/build: `npm run lint`, `npm run test`, `npm run build`
- Optimizer:
  - See `sdks/opik_optimizer/README.md` for its setup and scripts.

## Contributor notes
- Prefer the most specific command for your scope:
  - Python behavior: `sdks/python` tests and docs.
  - TS behavior: `sdks/typescript` tests and lint rules.
  - Shared generation changes: `code_generation`.
- Keep API compatibility in mind when changing public APIs, and update docs/examples in the matching package.
