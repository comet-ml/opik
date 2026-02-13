/**
 * Utility to determine if integration tests should run.
 *
 * Integration tests run in two scenarios:
 * 1. Against a local Opik instance (OPIK_URL_OVERRIDE set to localhost)
 * 2. Against cloud with a real API key
 *
 * Tests are skipped if:
 * - No OPIK_API_KEY is set AND no OPIK_URL_OVERRIDE is set
 * - OPIK_API_KEY is "test-api-key" AND OPIK_URL_OVERRIDE is not localhost
 *
 * This prevents accidental real API calls while allowing easy opt-in
 * by simply setting a valid API key in the .env file.
 */
export function shouldRunIntegrationTests(): boolean {
  const apiKey = process.env.OPIK_API_KEY;
  const urlOverride = process.env.OPIK_URL_OVERRIDE;

  // Check if we're running against a local instance
  // Use regex to match localhost or 127.0.0.1 as a hostname
  const isLocalInstance =
    urlOverride &&
    (/(?:^|\/\/)localhost(?::|\/|$)/.test(urlOverride) ||
      /(?:^|\/\/)127\.0\.0\.1(?::|\/|$)/.test(urlOverride));

  // If running against local instance, always run tests (no API key needed)
  if (isLocalInstance) {
    return true;
  }

  // For cloud instances, require a real API key
  if (!apiKey || apiKey === "test-api-key") {
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
  const urlOverride = process.env.OPIK_URL_OVERRIDE;

  const isLocalInstance =
    urlOverride &&
    (/(?:^|\/\/)localhost(?::|\/|$)/.test(urlOverride) ||
      /(?:^|\/\/)127\.0\.0\.1(?::|\/|$)/.test(urlOverride));

  if (isLocalInstance) {
    return `✅ Running integration tests against local Opik instance (${urlOverride})`;
  }

  if (!apiKey) {
    return "⚠️  Skipping integration tests - OPIK_API_KEY not set and not running against local instance";
  }

  if (apiKey === "test-api-key") {
    return "⚠️  Skipping integration tests - using test API key (set real key in .env or use local instance)";
  }

  return "✅ Running integration tests with real API key against cloud";
}

/**
 * Check if Anthropic API key is available for tests that require it.
 */
export function hasAnthropicApiKey(): boolean {
  return !!process.env.ANTHROPIC_API_KEY;
}
