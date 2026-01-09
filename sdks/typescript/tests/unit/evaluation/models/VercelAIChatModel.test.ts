import { describe, it, expect, vi, beforeEach } from "vitest";
import { VercelAIChatModel } from "@/evaluation/models/VercelAIChatModel";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";

// Mock the AI SDK
vi.mock("ai", () => ({
  generateText: vi.fn().mockImplementation((params) => {
    // Return structured output when output is specified
    if (params.output) {
      return Promise.resolve({
        text: "",
        output: { score: true, reason: ["test reason"] },
        usage: { inputTokens: 10, outputTokens: 20 },
      });
    }
    // Return text response by default
    return Promise.resolve({
      text: "Generated text response",
      usage: { inputTokens: 10, outputTokens: 20 },
    });
  }),
  Output: {
    object: vi.fn().mockImplementation(({ schema }) => ({ schema })),
  },
}));

describe("VercelAIChatModel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    process.env.OPENAI_API_KEY = "test-key";
  });

  describe("constructor", () => {
    it("should create instance with model ID string", () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId);
      expect(model).toBeInstanceOf(VercelAIChatModel);
      expect(model.modelName).toBe("gpt-4o");
    });

    it("should create instance with LanguageModel", () => {
      const mockLanguageModel = {
        modelId: "custom-model",
      } as LanguageModel;
      const model = new VercelAIChatModel(mockLanguageModel);
      expect(model).toBeInstanceOf(VercelAIChatModel);
      expect(model.modelName).toBe("custom-model");
    });

    it("should accept provider options", () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId, {
        apiKey: "custom-key",
        organization: "org-123",
      });
      expect(model).toBeInstanceOf(VercelAIChatModel);
    });
  });

  describe("generateString", () => {
    it("should generate text response", async () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId);
      const response = await model.generateString("Test prompt");
      expect(response).toBe("Generated text response");
    });

    it("should generate structured output with schema", async () => {
      const { z } = await import("zod");
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId);
      const schema = z.object({
        score: z.boolean(),
        reason: z.array(z.string()),
      });
      const response = await model.generateString("Test prompt", schema);
      expect(response).toBe('{"score":true,"reason":["test reason"]}');
    });

    it("should merge generation options", async () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId, {
        apiKey: "test-key",
        temperature: 0.7,
      });
      const response = await model.generateString("Test prompt", undefined, {
        maxTokens: 100,
      });
      expect(response).toBeDefined();
    });
  });

  describe("generateProviderResponse", () => {
    it("should generate provider response with messages", async () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId);
      const response = await model.generateProviderResponse([
        { role: "user", content: "Hello!" },
      ]);
      expect(response).toBeDefined();
      expect(response).toHaveProperty("text");
    });

    it("should merge generation options", async () => {
      const model = new VercelAIChatModel("gpt-4o" as SupportedModelId, {
        apiKey: "test-key",
        temperature: 0.5,
      });
      const response = await model.generateProviderResponse(
        [{ role: "user", content: "Hello!" }],
        { maxTokens: 50 }
      );
      expect(response).toBeDefined();
    });
  });
});
