# Playwright E2E Tests - Quick Start

## 🚀 Quick Setup

### 1. Install Dependencies

```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# Install Node.js dependencies
npm install

# Install Playwright browsers
npx playwright install chromium

# Install Python test helper service dependencies
pip install -r ../test-helper-service/requirements.txt
```

### 2. Start Opik Application

```bash
# From the project root
./scripts/dev-runner.sh --start
```

The application will be available at:
- Frontend: `http://localhost:5173` (default) or `http://localhost:5174` (standard dev-runner mode)
- Backend: `http://localhost:8080`

### 3. Run Tests

```bash
cd typescript-tests

# Run all tests
npm test

# Run specific test file
npm test -- tests/projects/projects.spec.ts

# Run tests in UI mode
npm run test:ui

# View test report
npm run test:report
```

## 🤖 AI-Generated Tests with Playwright Agents

### Overview

You can automatically generate E2E tests for new features using Playwright's agent capabilities. The workflow uses three specialized agents:

- **🎭 Planner**: Explores your UI and generates detailed test plans
- **🎭 Generator**: Transforms test plans into executable Playwright tests
- **🎭 Healer**: Automatically fixes failing tests

### Quick Usage

**Step 1: Complete your feature** (frontend + backend code)

**Step 2: Ensure the app is running**
```bash
./scripts/dev-runner.sh --start
# Verify at http://localhost:5173
```

**Step 3: Request test generation from Cursor**

Use one of these example prompts:

```
"Generate an E2E test for the new project metrics dashboard I just built"

"Create automated test for the dataset upload flow"

"Add happy path test for experiment creation"

"Now write an E2E test for the feature I just implemented"
```

**Step 4: Review and commit the generated test**

The agent will:
1. Explore your UI interactively
2. Generate a test plan in `typescript-tests/specs/`
3. Create executable test in `typescript-tests/tests/`
4. Fix any initial failures automatically

```bash
# Review generated files
ls typescript-tests/specs/
ls typescript-tests/tests/

# Run the new test
npm test -- tests/{area}/{your-test}.spec.ts

# Commit with your feature
git add .
git commit -m "[OPIK-1234] [FE] Add feature + E2E test"
```

### Example Agent Workflow

**Scenario**: You just built a new analytics dashboard feature

**Prompt to Cursor:**
```
I just built a project analytics dashboard. Generate an E2E test for it.
The dashboard shows project statistics including total traces, average cost,
and success rate. Users access it from the project details page by clicking
the "Analytics" tab. Please create a happy path test.
```

**What happens:**

1. **Planner agent** explores your running app:
   - Navigates to projects page
   - Clicks on a project
   - Finds the Analytics tab
   - Documents all interactions
   - Saves plan to `specs/project-analytics-dashboard.md`

2. **Generator agent** creates the test:
   - Reads the test plan
   - Generates `tests/projects/project-analytics-dashboard.spec.ts`
   - Uses existing fixtures and page objects
   - Includes proper assertions

3. **Healer agent** fixes any issues:
   - Runs the test
   - Identifies failures
   - Updates selectors/waits as needed
   - Re-runs until passing

4. **You review and commit:**
   ```bash
   npm test -- tests/projects/project-analytics-dashboard.spec.ts
   git add .
   git commit -m "[OPIK-1234] [FE] Add analytics dashboard + E2E test"
   ```

### Generated Test Structure

Agent-generated tests follow the same patterns as manual tests:

```typescript
// spec: specs/feature-name.md
// seed: tests/seed-for-planner.spec.ts

import { test, expect } from '../../fixtures/projects.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';

test.describe('Feature Name', () => {
  test('should perform main user flow', async ({ page, projectName }) => {
    // 1. Navigate to starting point
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();

    // 2. Perform user actions
    await projectsPage.clickProject(projectName);

    // 3. Verify expected outcomes
    await expect(page.locator('[data-testid="result"]')).toBeVisible();
  });
});
```

### Configuration

Agents use:
- **Seed test**: `typescript-tests/tests/seed-for-planner.spec.ts`
- **MCP config**: Root `.cursor/mcp.json` (Model Context Protocol server)
- **Agent definitions**: `.cursor/agents/` directory

For detailed documentation, see:
- [Playwright Agent Workflow Rule](.cursor/rules/playwright-agent-workflow.mdc)
- [Playwright Test Agents Docs](https://playwright.dev/docs/test-agents)

## 📁 Project Structure

```
tests_end_to_end_ts/
├── .cursor/
│   └── agents/                    # Playwright agent definitions
│       ├── playwright-test-planner.md
│       ├── playwright-test-generator.md
│       └── playwright-test-healer.md
├── test-helper-service/           # Flask service (wraps Python SDK)
│   ├── app.py
│   └── routes/
├── typescript-tests/              # TypeScript Playwright tests
│   ├── specs/                     # Test plans (generated by planner)
│   ├── tests/                     # Executable tests
│   │   ├── seed-for-planner.spec.ts  # Seed test for agents
│   │   ├── projects/
│   │   ├── datasets/
│   │   ├── experiments/
│   │   └── ...
│   ├── fixtures/                  # Test fixtures
│   ├── page-objects/              # Page object models
│   ├── helpers/                   # Helper utilities
│   └── config/                    # Environment configuration
└── README.md                      # Full documentation
```

## 🎯 Key Features

✅ **Flask Auto-Start** - Test helper service starts automatically
✅ **Type Safety** - TypeScript catches errors at compile time
✅ **Dual Verification** - Tests verify via both UI (Playwright) and SDK (Python)
✅ **Fixtures** - Clean setup/teardown like pytest
✅ **Page Objects** - Maintainable, reusable UI interactions
✅ **AI Agents** - Automatic test generation with Playwright agents

## 🐛 Troubleshooting

### Opik Not Running

```bash
# Check if accessible
curl http://localhost:5173

# Start if needed
./scripts/dev-runner.sh --start
```

### Test Helper Service Not Starting

```bash
cd test-helper-service
pip install -r requirements.txt
python app.py  # Should start on port 5555
```

### Dependencies Not Installed

```bash
cd typescript-tests
npm install
npx playwright install chromium
```

### Tests Failing

```bash
# Run with UI mode to debug
npm run test:ui

# View detailed traces
npm run test:report

# Use healer agent to auto-fix
# In Cursor: "Use the healer agent to fix tests/path/to/test.spec.ts"
```

## 📖 Next Steps

- **Read full documentation**: `typescript-tests/README.md`
- **Learn agent workflow**: [Playwright Agent Workflow](.cursor/rules/playwright-agent-workflow.mdc)
- **View existing tests**: `typescript-tests/tests/*/`
- **Understand fixtures**: `typescript-tests/fixtures/`
- **Explore page objects**: `typescript-tests/page-objects/`

## 🎉 Success Criteria

After setup, you should be able to:
- [x] Run `npm test` without errors
- [x] Generate tests using Playwright agents
- [x] View HTML reports with traces
- [x] Debug tests in UI mode

## 📞 Questions?

- Check the full [README](typescript-tests/README.md)
- Review [Playwright Agent Workflow](.cursor/rules/playwright-agent-workflow.mdc)
- See [Playwright Agents Documentation](https://playwright.dev/docs/test-agents)
