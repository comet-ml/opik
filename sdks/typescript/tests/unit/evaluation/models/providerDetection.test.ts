import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  detectProvider,
  type SupportedModelId,
} from "@/evaluation/models/providerDetection";
import { ModelConfigurationError } from "@/evaluation/models/errors";

describe("providerDetection", () => {
  const originalEnv = process.env;

  beforeEach(() => {
    vi.resetModules();
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  describe("OpenAI detection", () => {
    it("should detect gpt models", () => {
      process.env.OPENAI_API_KEY = "test-key";
      const model = detectProvider("gpt-4o" as SupportedModelId);
      expect(model).toBeDefined();
    });

    it("should detect o1 models", () => {
      process.env.OPENAI_API_KEY = "test-key";
      const model = detectProvider("o1" as SupportedModelId);
      expect(model).toBeDefined();
    });

    it("should detect chatgpt models", () => {
      process.env.OPENAI_API_KEY = "test-key";
      const model = detectProvider("chatgpt-4o-latest" as SupportedModelId);
      expect(model).toBeDefined();
    });

    it("should throw error when API key is missing", () => {
      delete process.env.OPENAI_API_KEY;
      expect(() => detectProvider("gpt-4o" as SupportedModelId)).toThrow(
        ModelConfigurationError
      );
      expect(() => detectProvider("gpt-4o" as SupportedModelId)).toThrow(
        /API key for OpenAI is not configured/
      );
    });

    it("should accept API key from options", () => {
      delete process.env.OPENAI_API_KEY;
      const model = detectProvider("gpt-4o" as SupportedModelId, {
        apiKey: "test-key",
      });
      expect(model).toBeDefined();
    });
  });

  describe("Anthropic detection", () => {
    it("should detect claude models", () => {
      process.env.ANTHROPIC_API_KEY = "test-key";
      const model = detectProvider(
        "claude-3-5-sonnet-latest" as SupportedModelId
      );
      expect(model).toBeDefined();
    });

    it("should throw error when API key is missing", () => {
      delete process.env.ANTHROPIC_API_KEY;
      expect(() =>
        detectProvider("claude-3-5-sonnet-latest" as SupportedModelId)
      ).toThrow(ModelConfigurationError);
      expect(() =>
        detectProvider("claude-3-5-sonnet-latest" as SupportedModelId)
      ).toThrow(/API key for Anthropic is not configured/);
    });

    it("should accept API key from options", () => {
      delete process.env.ANTHROPIC_API_KEY;
      const model = detectProvider(
        "claude-3-5-sonnet-latest" as SupportedModelId,
        { apiKey: "test-key" }
      );
      expect(model).toBeDefined();
    });
  });

  describe("Google Gemini detection", () => {
    it("should detect gemini models", () => {
      process.env.GOOGLE_API_KEY = "test-key";
      const model = detectProvider("gemini-2.0-flash" as SupportedModelId);
      expect(model).toBeDefined();
    });

    it("should detect gemma models", () => {
      process.env.GOOGLE_API_KEY = "test-key";
      const model = detectProvider("gemma-3-12b-it" as SupportedModelId);
      expect(model).toBeDefined();
    });

    it("should throw error when API key is missing", () => {
      delete process.env.GOOGLE_API_KEY;
      expect(() =>
        detectProvider("gemini-2.0-flash" as SupportedModelId)
      ).toThrow(ModelConfigurationError);
      expect(() =>
        detectProvider("gemini-2.0-flash" as SupportedModelId)
      ).toThrow(/API key for Google Gemini is not configured/);
    });

    it("should accept API key from options", () => {
      delete process.env.GOOGLE_API_KEY;
      const model = detectProvider("gemini-2.0-flash" as SupportedModelId, {
        apiKey: "test-key",
      });
      expect(model).toBeDefined();
    });
  });

  describe("Unknown provider", () => {
    it("should throw error for unknown model ID", () => {
      expect(() => detectProvider("unknown-model" as SupportedModelId)).toThrow(
        ModelConfigurationError
      );
      expect(() => detectProvider("unknown-model" as SupportedModelId)).toThrow(
        /Unable to detect provider for model ID/
      );
    });
  });
});
