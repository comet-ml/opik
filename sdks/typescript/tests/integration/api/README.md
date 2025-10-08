# Real API Integration Tests

## Running Tests Locally

1. **Create `.env` file** in `sdks/typescript` with your API key:

```bash
OPIK_API_KEY=your_real_api_key_here
```

2. **Run tests**:

```bash
npm run test:integration:api
```

Tests automatically run when a real API key is detected.
