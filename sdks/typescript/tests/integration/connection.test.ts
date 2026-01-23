import { describe, it, expect, beforeAll } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Connection Integration Tests", () => {
  // Store original env variables
  const originalEnv = {
    apiKey: process.env.OPIK_API_KEY,
    urlOverride: process.env.OPIK_URL_OVERRIDE,
    workspace: process.env.OPIK_WORKSPACE,
    projectName: process.env.OPIK_PROJECT_NAME,
  };

  beforeAll(() => {
    console.log(getIntegrationTestStatus());
  });

  it("should connect successfully using environment variables", async () => {
    // Ensure env variables are set (they should be in CI/integration test environment)
    expect(
      originalEnv.apiKey || originalEnv.urlOverride
    ).toBeDefined();

    // Create client using environment variables
    const client = new Opik();

    // Verify client is configured
    expect(client).toBeDefined();
    expect(client.config.apiUrl).toBeDefined();

    // Test actual connection by making an API call
    const response = await client.api.isAlive();
    expect(response).toBeDefined();

    await client.flush();
  });

  it("should connect successfully using explicit parameters (overriding env)", async () => {
    // Temporarily unset OPIK_API_KEY to ensure we're using explicit config
    const tempApiKey = process.env.OPIK_API_KEY;
    delete process.env.OPIK_API_KEY;

    try {
      // Use explicit parameters to override environment variables
      const explicitConfig = {
        apiKey: originalEnv.apiKey,
        apiUrl: originalEnv.urlOverride,
        workspaceName: originalEnv.workspace,
        projectName: "connection-test-project",
      };

      const client = new Opik(explicitConfig);

      // Verify client is configured with explicit values
      expect(client).toBeDefined();
      expect(client.config.apiUrl).toBe(explicitConfig.apiUrl);
      expect(client.config.apiKey).toBe(explicitConfig.apiKey);
      expect(client.config.workspaceName).toBe(explicitConfig.workspaceName);
      expect(client.config.projectName).toBe(explicitConfig.projectName);

      // Test actual connection by making an API call
      const response = await client.api.isAlive();
      expect(response).toBeDefined();

      await client.flush();
    } finally {
      // Restore original env variable
      if (tempApiKey) {
        process.env.OPIK_API_KEY = tempApiKey;
      }
    }
  });

  it("should connect with partial explicit parameters (mixing env and explicit)", async () => {
    // Override only some parameters, let others come from env
    const client = new Opik({
      projectName: "partial-override-project",
      workspaceName: originalEnv.workspace,
    });

    // Verify client is configured
    expect(client).toBeDefined();
    expect(client.config.projectName).toBe("partial-override-project");
    
    // API key and URL should come from environment
    expect(client.config.apiKey).toBe(originalEnv.apiKey || "");
    expect(client.config.apiUrl).toBe(
      originalEnv.urlOverride || "https://www.comet.com/opik/api"
    );

    // Test actual connection by making an API call
    const response = await client.api.isAlive();
    expect(response).toBeDefined();

    await client.flush();
  });
});