# End-to-End Test Suite

This folder contains Opik end-to-end testing assets. The active suite is the TypeScript Playwright suite in `typescript-tests`, with a Flask-based test helper service in `test-helper-service`.

## Prerequisites
- Opik running locally (`./opik.sh --build` or equivalent backend/frontend run).
- Node.js 18+.
- Python 3.11+.
- Optional: LLM keys for playground/online-scoring suites (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`).

## Setup
- Install TypeScript test deps:

```bash
cd tests_end_to_end/typescript-tests
npm ci
npx playwright install chromium
```

- Install test helper dependencies:

```bash
pip install -r tests_end_to_end/test-helper-service/requirements.txt
```

- Configure environment:
  - Copy `tests_end_to_end/typescript-tests/.env.example` to `tests_end_to_end/typescript-tests/.env`
  - For local runs, `OPIK_BASE_URL=http://localhost:5173` is usually sufficient.
  - If you run local process mode (`scripts/dev-runner.sh`), use `OPIK_BASE_URL=http://localhost:5174`.
  - For non-local environments, set `OPIK_TEST_USER_EMAIL`, `OPIK_TEST_USER_NAME`, and `OPIK_TEST_USER_PASSWORD`.

## Running tests
- From repo root, ensure Opik is up, then run any suite:

```bash
cd tests_end_to_end/typescript-tests
TEST_SUITE=sanity npm test
```

- Available suite values (via `TEST_SUITE` env var or scripts): `sanity`, `happypaths`, `fullregression`, `projects`, `datasets`, `experiments`, `prompts`, `playground`, `tracing`, `threads`, `attachments`, `feedbackscores`, `onlinescores`.

- Useful commands:
  - `npm run test:sanity`
  - `npm run test:projects`
  - `npm run test:ui`
  - `npm run test:headed`
  - `npm run test:report`
  - `npm run allure:report`

- The Playwright config auto-starts the helper service (`python app.py`) during test runs. If needed manually:

```bash
cd tests_end_to_end/test-helper-service
python app.py
```

## Helper files
- `typescript-tests/.env.example` and `env.local.example`: environment variable templates.
- `installer_utils/`: stack health checks used before executing E2E suites.
- `test-helper-service/`: Flask service used by browser tests to call Opik via Python SDK.
