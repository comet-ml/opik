import { describe, expect, beforeEach, afterEach, it } from "vitest";

describe("Lazy loading config", () => {
  const originalEnvironmentVariables = { ...process.env };

  beforeEach(async () => {
    // Clear environment variables to simulate missing config
    delete process.env.OPIK_URL_OVERRIDE;
    delete process.env.OPIK_API_KEY;
    delete process.env.OPIK_WORKSPACE;
    delete process.env.OPIK_PROJECT_NAME;
    delete process.env.OPIK_CONFIG_PATH;

    // Clear the cached client
    const { _resetTrackOpikClientCache } = await import("@/decorators/track");
    _resetTrackOpikClientCache();
  });

  afterEach(async () => {
    // Restore original environment
    process.env = { ...originalEnvironmentVariables };

    // Clear the cached client
    const { _resetTrackOpikClientCache } = await import("@/decorators/track");
    _resetTrackOpikClientCache();
  });

  it("should not throw error when importing opik module without config", async () => {
    // This test verifies the fix for https://github.com/comet-ml/opik/issues/3663
    // Before fix: import would throw "Error: OPIK_API_KEY is not set"
    // After fix: import should succeed, error only thrown when client is used

    // Should not throw during import
    const opikModule = await import("opik");
    expect(opikModule).toBeDefined();
    expect(opikModule.Opik).toBeDefined();
    expect(opikModule.track).toBeDefined();
  });

  it("should throw error when using internal getTrackOpikClient without valid config", async () => {
    // Set cloud URL but no API key to trigger validation error
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/opik/api";
    delete process.env.OPIK_API_KEY;

    // Import from internal path since it's not publicly exported
    const { getTrackOpikClient } = await import("@/decorators/track");

    expect(() => {
      getTrackOpikClient();
    }).toThrow("OPIK_API_KEY is not set");
  });

  it("should throw error when using track decorator function without valid config", async () => {
    // Set cloud URL but no API key to trigger validation error
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/opik/api";
    delete process.env.OPIK_API_KEY;

    const { track } = await import("opik");

    // Wrap a function with track decorator
    const trackedFunction = track(function testFunction() {
      return "test";
    });

    // The decorator wrapping itself should not throw
    expect(trackedFunction).toBeDefined();

    // But calling the wrapped function should throw when it tries to initialize the client
    expect(() => {
      trackedFunction();
    }).toThrow("OPIK_API_KEY is not set");
  });

  it("should work correctly with valid local config", async () => {
    // Set valid local config (local doesn't require API key)
    process.env.OPIK_URL_OVERRIDE = "http://localhost:5173/api";
    delete process.env.OPIK_API_KEY;
    delete process.env.OPIK_WORKSPACE;

    const { getTrackOpikClient } = await import("@/decorators/track");
    const { Opik } = await import("opik");

    // Should not throw with valid local config
    const client = getTrackOpikClient();
    expect(client).toBeDefined();
    expect(client.config.apiUrl).toBe("http://localhost:5173/api");

    // Regular Opik client should also work
    const opik = new Opik();
    expect(opik).toBeDefined();
    expect(opik.config.apiUrl).toBe("http://localhost:5173/api");
  });

  it("should use flushAll to flush the track client", async () => {
    // Set valid local config
    process.env.OPIK_URL_OVERRIDE = "http://localhost:5173/api";
    delete process.env.OPIK_API_KEY;

    const { flushAll } = await import("opik");
    const { getTrackOpikClient } = await import("@/decorators/track");

    // Initialize the client
    const client = getTrackOpikClient();
    expect(client).toBeDefined();

    // flushAll should flush the track client without throwing
    await expect(flushAll()).resolves.not.toThrow();
  });

  it("should cache the client instance across multiple calls", async () => {
    // Set valid local config
    process.env.OPIK_URL_OVERRIDE = "http://localhost:5173/api";

    // Import from internal path
    const { getTrackOpikClient } = await import("@/decorators/track");

    const client1 = getTrackOpikClient();
    const client2 = getTrackOpikClient();

    // Should return the same instance
    expect(client1).toBe(client2);
  });
});
