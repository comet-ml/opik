/**
 * Utility to determine if integration tests should run.
 *
 * Integration tests run only when a REAL API key is provided.
 * Tests are skipped if:
 * - No OPIK_API_KEY is set
 * - OPIK_API_KEY is set to the test fallback value ("test-api-key")
 *
 * This prevents accidental real API calls while allowing easy opt-in
 * by simply setting a valid API key in the .env file.
 */
export function shouldRunIntegrationTests(): boolean {
  const apiKey = process.env.OPIK_API_KEY;

  // Skip if no API key is set
  if (!apiKey) {
    return false;
  }

  // Skip if using the test fallback key (from vitest.config.ts)
  if (apiKey === "test-api-key") {
    return false;
  }

  // Run tests if we have a real API key
  return true;
}

/**
 * Get a descriptive message about why tests are being skipped or run.
 */
export function getIntegrationTestStatus(): string {
  const apiKey = process.env.OPIK_API_KEY;

  if (!apiKey) {
    return "⚠️  Skipping integration tests - OPIK_API_KEY not set";
  }

  if (apiKey === "test-api-key") {
    return "⚠️  Skipping integration tests - using test API key (set real key in .env)";
  }

  return "✅ Running integration tests with real API key";
}
