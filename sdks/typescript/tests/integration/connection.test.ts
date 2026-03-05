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
    // Step 1: Get the resolved config through the public API
    const referenceClient = new Opik();

    // Step 2: Clear ALL env vars so only explicit config can provide values
    const savedEnv = {
      OPIK_API_KEY: process.env.OPIK_API_KEY,
      OPIK_URL_OVERRIDE: process.env.OPIK_URL_OVERRIDE,
      OPIK_WORKSPACE: process.env.OPIK_WORKSPACE,
    };
    delete process.env.OPIK_API_KEY;
    delete process.env.OPIK_URL_OVERRIDE;
    delete process.env.OPIK_WORKSPACE;

    try {
      // Step 3: Pass resolved values as explicit config
      const explicitConfig = {
        apiKey: referenceClient.config.apiKey,
        apiUrl: referenceClient.config.apiUrl,
        workspaceName: referenceClient.config.workspaceName,
        projectName: "connection-test-project",
      };

      const client = new Opik(explicitConfig);

      // Step 4: Verify explicit values are used (not defaults, not env)
      expect(client).toBeDefined();
      expect(client.config.apiUrl).toBe(explicitConfig.apiUrl);
      expect(client.config.apiKey).toBe(explicitConfig.apiKey);
      expect(client.config.workspaceName).toBe(explicitConfig.workspaceName);
      expect(client.config.projectName).toBe(explicitConfig.projectName);

      const response = await client.api.isAlive();
      expect(response).toBeDefined();
      await client.flush();
    } finally {
      // Step 5: Restore env vars
      for (const [key, value] of Object.entries(savedEnv)) {
        if (value !== undefined) process.env[key] = value;
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