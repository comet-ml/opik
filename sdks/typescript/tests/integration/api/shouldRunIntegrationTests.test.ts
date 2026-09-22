import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
  hasAnthropicApiKey,
  hasOpenAiApiKey,
} from "./shouldRunIntegrationTests";

describe("shouldRunIntegrationTests", () => {
  let originalApiKey: string | undefined;
  let originalUrlOverride: string | undefined;

  beforeEach(() => {
    // Save original values
    originalApiKey = process.env.OPIK_API_KEY;
    originalUrlOverride = process.env.OPIK_URL_OVERRIDE;
  });

  afterEach(() => {
    // Restore original values
    if (originalApiKey !== undefined) {
      process.env.OPIK_API_KEY = originalApiKey;
    } else {
      delete process.env.OPIK_API_KEY;
    }

    if (originalUrlOverride !== undefined) {
      process.env.OPIK_URL_OVERRIDE = originalUrlOverride;
    } else {
      delete process.env.OPIK_URL_OVERRIDE;
    }
  });

  describe("local instance detection", () => {
    it("should run tests when OPIK_URL_OVERRIDE points to localhost", () => {
      process.env.OPIK_URL_OVERRIDE = "http://localhost:5173/api";
      process.env.OPIK_API_KEY = "test-api-key";

      expect(shouldRunIntegrationTests()).toBe(true);
      expect(getIntegrationTestStatus()).toContain("local Opik instance");
    });

    it("should run tests when OPIK_URL_OVERRIDE points to 127.0.0.1", () => {
      process.env.OPIK_URL_OVERRIDE = "http://127.0.0.1:5173/api";
      process.env.OPIK_API_KEY = "test-api-key";

      expect(shouldRunIntegrationTests()).toBe(true);
      expect(getIntegrationTestStatus()).toContain("local Opik instance");
    });

    it("should run tests against local instance even without API key", () => {
      process.env.OPIK_URL_OVERRIDE = "http://localhost:5173/api";
      delete process.env.OPIK_API_KEY;

      expect(shouldRunIntegrationTests()).toBe(true);
      expect(getIntegrationTestStatus()).toContain("local Opik instance");
    });
  });

  describe("cloud instance with API key", () => {
    it("should run tests when real API key is provided", () => {
      process.env.OPIK_API_KEY = "real-api-key-123";
      delete process.env.OPIK_URL_OVERRIDE;

      expect(shouldRunIntegrationTests()).toBe(true);
      expect(getIntegrationTestStatus()).toContain("real API key against cloud");
    });

    it("should run tests when real API key is provided with non-local URL", () => {
      process.env.OPIK_API_KEY = "real-api-key-123";
      process.env.OPIK_URL_OVERRIDE = "https://api.comet.com/opik";

      expect(shouldRunIntegrationTests()).toBe(true);
      expect(getIntegrationTestStatus()).toContain("real API key against cloud");
    });
  });

  describe("skip scenarios", () => {
    it("should skip tests when no API key and no local URL", () => {
      delete process.env.OPIK_API_KEY;
      delete process.env.OPIK_URL_OVERRIDE;

      expect(shouldRunIntegrationTests()).toBe(false);
      expect(getIntegrationTestStatus()).toContain("Skipping");
    });

    it("should skip tests when using test-api-key without local URL", () => {
      process.env.OPIK_API_KEY = "test-api-key";
      delete process.env.OPIK_URL_OVERRIDE;

      expect(shouldRunIntegrationTests()).toBe(false);
      expect(getIntegrationTestStatus()).toContain("Skipping");
    });

    it("should skip tests when using test-api-key with cloud URL", () => {
      process.env.OPIK_API_KEY = "test-api-key";
      process.env.OPIK_URL_OVERRIDE = "https://api.comet.com/opik";

      expect(shouldRunIntegrationTests()).toBe(false);
      expect(getIntegrationTestStatus()).toContain("Skipping");
    });
  });

  describe("edge cases", () => {
    it("should handle localhost with port variations", () => {
      process.env.OPIK_URL_OVERRIDE = "http://localhost:8080/api";
      process.env.OPIK_API_KEY = "test-api-key";

      expect(shouldRunIntegrationTests()).toBe(true);
    });

    it("should handle localhost without protocol", () => {
      process.env.OPIK_URL_OVERRIDE = "localhost:5173/api";
      process.env.OPIK_API_KEY = "test-api-key";

      expect(shouldRunIntegrationTests()).toBe(true);
    });

    it("should not treat similar domain names as localhost", () => {
      process.env.OPIK_API_KEY = "test-api-key";
      process.env.OPIK_URL_OVERRIDE = "https://my-localhost.com/api";

      expect(shouldRunIntegrationTests()).toBe(false);
    });
  });
});

describe("provider key detection", () => {
  let originalAnthropicKey: string | undefined;
  let originalOpenAiKey: string | undefined;

  beforeEach(() => {
    originalAnthropicKey = process.env.ANTHROPIC_API_KEY;
    originalOpenAiKey = process.env.OPENAI_API_KEY;
  });

  afterEach(() => {
    if (originalAnthropicKey !== undefined) {
      process.env.ANTHROPIC_API_KEY = originalAnthropicKey;
    } else {
      delete process.env.ANTHROPIC_API_KEY;
    }

    if (originalOpenAiKey !== undefined) {
      process.env.OPENAI_API_KEY = originalOpenAiKey;
    } else {
      delete process.env.OPENAI_API_KEY;
    }
  });

  describe("hasAnthropicApiKey", () => {
    it("reports a key that is present", () => {
      process.env.ANTHROPIC_API_KEY = "sk-ant-test";

      expect(hasAnthropicApiKey()).toBe(true);
    });

    it("reports no key when the variable is unset", () => {
      delete process.env.ANTHROPIC_API_KEY;

      expect(hasAnthropicApiKey()).toBe(false);
    });

    // A GitHub secret that does not exist expands to "" rather than being
    // absent, so this is the shape a missing repo secret actually takes, and
    // the reason the workflow fails the job before the suite can skip.
    it("reports no key when set to an empty string", () => {
      process.env.ANTHROPIC_API_KEY = "";

      expect(hasAnthropicApiKey()).toBe(false);
    });
  });

  describe("hasOpenAiApiKey", () => {
    it("reports a key that is present", () => {
      process.env.OPENAI_API_KEY = "sk-test";

      expect(hasOpenAiApiKey()).toBe(true);
    });

    it("reports no key when the variable is unset", () => {
      delete process.env.OPENAI_API_KEY;

      expect(hasOpenAiApiKey()).toBe(false);
    });

    it("reports no key when set to an empty string", () => {
      process.env.OPENAI_API_KEY = "";

      expect(hasOpenAiApiKey()).toBe(false);
    });
  });
});
