# Real API Integration Tests

These tests run against actual Opik API endpoints and require valid credentials.

## ⚠️ Important

- **NOT run in CI/CD pipelines** - these tests are skipped by default
- **Require manual opt-in** - only run when credentials are provided
- **May incur costs** - tests create real resources
- **Require cleanup** - tests attempt to clean up but may leave artifacts on failure

## Running Tests Locally

### 1. Set up your environment

Create a `.env` file in the `sdks/typescript` directory:

```bash
# From the sdks/typescript directory
touch .env
```

Add your credentials to `.env`:

```bash
# Required
OPIK_API_KEY=your_api_key_here

# Optional - defaults to production
OPIK_URL=https://www.comet.com/opik/api
OPIK_WORKSPACE=your_workspace_name
```

> 💡 **Tip**: The `.env` file is gitignored for security. Get your API key from https://www.comet.com/opik

### 2. Run the tests

```bash
# Run all real API integration tests
npm run test:integration:api

# Run specific test file
npm run test:integration:api -- prompt-api.test.ts

# Run with verbose output
npm run test:integration:api -- --reporter=verbose
```

### 3. What gets tested

- ✅ Prompt lifecycle: create → update → delete
- ✅ Version management: create versions → retrieve history
- ✅ Search and filtering
- ✅ Concurrent operations
- ✅ Error handling with real API
- ✅ Batch queue flushing

## Test Behavior

- Tests are **automatically skipped** if `OPIK_API_KEY` is not set
- Each test uses **unique names** (timestamp-based) to avoid conflicts
- Tests **clean up after themselves** in `afterEach` hooks
- Failed tests may leave orphaned resources - clean up manually if needed

## Troubleshooting

### Tests are skipped

```
SKIP  Prompt Real API Integration > should create and retrieve prompt
```

**Solution**: Make sure `OPIK_API_KEY` is set in your `.env` file

### Authentication errors

```
OpikApiError: Unauthorized (401)
```

**Solution**: Verify your API key is valid and has proper permissions

### Rate limiting

```
OpikApiError: Too Many Requests (429)
```

**Solution**: Wait a few seconds and retry. Consider adding delays between tests.

## Best Practices

1. **Run locally first** - test changes before pushing
2. **Clean up manually** - if tests fail, check for orphaned resources
3. **Use test workspace** - don't run against production data
4. **Monitor costs** - these tests create real API calls
5. **Report issues** - if tests fail unexpectedly, report to the team

## CI/CD Behavior

These tests are **intentionally excluded** from CI/CD pipelines:

- Not part of `npm test` default run
- Require explicit opt-in via npm script
- Skip automatically without credentials
