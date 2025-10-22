# TypeScript E2E Tests for Opik

This is a proof-of-concept migration of the Opik E2E test suite from Python to TypeScript with Playwright, while maintaining the ability to test the Python SDK.

## Architecture

```
┌─────────────────────────────────────────────────┐
│   TypeScript Test Suite (Playwright)           │
│   • UI automation and assertions               │
│   • Type-safe test code                        │
│   • Playwright AI self-healing                 │
└────────────────┬────────────────────────────────┘
                 │
                 │ HTTP API calls (axios)
                 ↓
┌─────────────────────────────────────────────────┐
│   Flask Test Helper Service (Python)            │
│   • Standalone service (no old test deps)      │
│   • Uses Python Opik SDK directly              │
│   • Provides HTTP endpoints for TypeScript     │
└────────────────┬────────────────────────────────┘
                 │
                 │ Python Opik SDK
                 ↓
┌─────────────────────────────────────────────────┐
│   Opik Backend (API + Database)                 │
└─────────────────────────────────────────────────┘
```

## Features

✅ **Tests Python SDK** - All data generation via Python SDK (through Flask)
✅ **Type Safety** - TypeScript catches errors at compile time
✅ **Better Tooling** - Playwright trace viewer, video recording, screenshots
✅ **Auto-Start Flask** - Playwright automatically starts and stops Flask service
✅ **Dual Verification** - Tests verify functionality via both UI and SDK
✅ **Gradual Migration** - Can run Python and TypeScript tests in parallel

## Quick Start

### Prerequisites

- Node.js 18+ and npm
- Python 3.9+
- Opik running locally at `http://localhost:5173` (or configure via env vars)

### Installation

```bash
# Navigate to TypeScript tests directory
cd tests_end_to_end_ts/typescript-tests

# Install Node dependencies
npm install

# Install Playwright browsers
npx playwright install chromium

# Install Flask dependencies for test helper service
cd ../test-helper-service
pip install -r requirements.txt
cd ../typescript-tests
```

### Running Tests

**Single command (recommended):**
```bash
npm test
```

This automatically:
1. Starts the Flask test helper service
2. Waits for it to be ready
3. Runs all tests
4. Shuts down the Flask service

**Other useful commands:**
```bash
# Run tests with UI mode (interactive)
npm run test:ui

# Run tests in headed mode (see browser)
npm run test:headed

# Debug tests step-by-step
npm run test:debug

# View HTML test report
npm run test:report

# View trace for debugging
npm run test:trace
```

## Project Structure

```
tests_end_to_end_ts/
├── test-helper-service/          # Flask service (Python SDK wrapper)
│   ├── app.py                     # Main Flask app
│   ├── routes/
│   │   └── projects.py            # Project endpoints
│   └── requirements.txt
│
├── typescript-tests/              # TypeScript Playwright tests
│   ├── config/
│   │   └── env.config.ts          # Environment configuration
│   ├── helpers/
│   │   ├── test-helper-client.ts  # HTTP client for Flask
│   │   ├── wait-helpers.ts        # Retry/wait utilities
│   │   └── random.ts              # Random string generation
│   ├── page-objects/
│   │   ├── base.page.ts           # Base page class
│   │   └── projects.page.ts       # Projects page
│   ├── fixtures/
│   │   ├── base.fixture.ts        # Core fixtures
│   │   └── projects.fixture.ts    # Project fixtures
│   ├── tests/
│   │   └── projects/
│   │       └── projects.spec.ts   # Project tests
│   ├── playwright.config.ts       # Playwright config (auto-starts Flask)
│   ├── global-setup.ts            # Global setup & auth
│   ├── package.json
│   ├── tsconfig.json
│   └── .env.example
│
└── README.md                      # This file
```

## Environment Configuration

The test suite uses the **same environment variables** as the Python tests for seamless transition.

### Local Development (default)

No configuration needed! Tests will use these defaults:
- `OPIK_BASE_URL=http://localhost:5173`
- `OPIK_WORKSPACE=default`

### Non-Local Environments

For non-local environments, create a `.env` file (copy from `.env.example`):

```bash
# Required for non-local environments
OPIK_BASE_URL=https://your-environment.comet.com/opik
OPIK_TEST_USER_EMAIL=test_user@example.com
OPIK_TEST_USER_NAME=test_user
OPIK_TEST_USER_PASSWORD=test_password
OPIK_API_KEY=your_api_key
```

**Note:** If `OPIK_BASE_URL` does not start with `http://localhost`, the user credentials are **required** and validated on startup.

## Current Test Coverage

### Projects Tests (6 tests)

- ✅ Project visibility (API-created)
- ✅ Project visibility (UI-created)
- ✅ Project name update (API-created)
- ✅ Project name update (UI-created)
- ✅ Project deletion via SDK (API-created)
- ✅ Project deletion via SDK (UI-created)
- ⏭️ Project deletion via UI (skipped - for live demo)

## Extending the Test Suite

### Adding a New Test

1. Use existing fixtures for setup/teardown:
```typescript
test('my new test', async ({ page, helperClient, createProjectApi }) => {
  const projectName = createProjectApi;
  // Your test logic here
});
```

2. Use page objects for UI interactions:
```typescript
const projectsPage = new ProjectsPage(page);
await projectsPage.goto();
await projectsPage.checkProjectExists(projectName);
```

3. Use helper client for SDK operations:
```typescript
const projects = await helperClient.findProject(projectName);
expect(projects[0].name).toBe(projectName);
```

### Adding New Endpoints to Flask Service

1. Create a new route file in `test-helper-service/routes/`
2. Import SDK helpers from `tests_end_to_end`
3. Register blueprint in `app.py`
4. Add corresponding methods to `TestHelperClient`

## Debugging

### Failed Tests

Playwright automatically captures on failure:
- **Screenshots** - Visual state when test failed
- **Videos** - Full test execution recording
- **Traces** - Complete timeline with network, console, etc.

View artifacts:
```bash
npm run test:report  # HTML report with artifacts
npm run test:trace   # Interactive trace viewer
```

### Flask Service Issues

Check Flask logs (shown in test output):
```bash
# Flask service outputs to stdout/stderr during test run
# Look for startup messages and error logs
```

Manually test Flask service:
```bash
cd test-helper-service
python3 app.py
# In another terminal:
curl http://localhost:5555/health
```

## CI/CD Integration

The test suite is CI-ready:

```yaml
# Example GitHub Actions workflow
- name: Install dependencies
  run: |
    cd tests_end_to_end_ts/typescript-tests
    npm ci
    npx playwright install --with-deps chromium

- name: Install Flask dependencies
  run: |
    cd tests_end_to_end_ts/test-helper-service
    pip install -r requirements.txt

- name: Run tests
  run: |
    cd tests_end_to_end_ts/typescript-tests
    npm test
```

## Comparison with Python Tests

| Feature | Python Tests | TypeScript Tests |
|---------|-------------|------------------|
| Test Framework | pytest | Playwright Test |
| Browser Automation | Playwright Python | Playwright TS |
| SDK Testing | Direct Python SDK | Python SDK via Flask |
| Type Safety | ❌ | ✅ |
| Auto-complete | Limited | Excellent |
| Trace Viewer | Basic | Advanced |
| AI Self-healing | ❌ | ✅ |
| Fixtures | pytest fixtures | Playwright fixtures |
| Parametrization | @pytest.mark.parametrize | Multiple test cases |

## Next Steps

This demo covers the **Projects** test suite. To complete the migration:

1. **Add more endpoints to Flask service:**
   - Datasets (create, insert items, delete)
   - Traces (create with spans, attachments, threads)
   - Experiments (create from dataset)
   - Prompts (create, get with retries)

2. **Port more test suites:**
   - Datasets tests
   - Traces tests
   - Experiments tests
   - Prompts tests

3. **Enhance fixtures:**
   - Dataset fixtures with items
   - Trace fixtures with spans
   - Experiment fixtures

4. **CI/CD integration:**
   - Add to existing CI pipeline
   - Run in parallel with Python tests
   - Gradual replacement strategy

## Troubleshooting

### "Flask test helper service is not responding"

The Flask service failed to start. Check:
1. Python 3.9+ is installed
2. Flask dependencies are installed: `pip install -r test-helper-service/requirements.txt`
3. Port 5555 is not in use
4. Check Flask logs in test output

### "Cannot find module 'axios'"

Node dependencies not installed:
```bash
cd typescript-tests
npm install
```

### "Authentication failed"

For non-local environments, ensure all required env vars are set:
- `OPIK_TEST_USER_EMAIL`
- `OPIK_TEST_USER_NAME`
- `OPIK_TEST_USER_PASSWORD`

### Tests fail with timeout

Increase timeouts in `playwright.config.ts`:
```typescript
timeout: 120000,  // Increase to 2 minutes
```

## Contributing

When adding new tests:
1. Follow existing patterns (fixtures, page objects)
2. Add type annotations
3. Include both UI and SDK verification
4. Clean up resources in fixtures
5. Add descriptive test names and comments

## License

Same license as the main Opik project.
