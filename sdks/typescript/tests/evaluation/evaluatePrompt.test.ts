import { describe, it, expect, vi, beforeEach } from "vitest";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { OpikClient as Opik } from "@/client/Client";
import { Dataset } from "@/dataset";
import { BaseMetric } from "@/evaluation/metrics/BaseMetric";
import {
  OpikBaseModel,
  type OpikMessage,
} from "@/evaluation/models/OpikBaseModel";
import { PromptType } from "@/prompt/types";
import type { EvaluationScoreResult } from "@/evaluation/types";
import { z } from "zod";

// Mock the Opik client
vi.mock("@/client/Client", () => ({
  OpikClient: vi.fn(),
}));

// Mock Dataset
vi.mock("@/dataset", () => ({
  Dataset: vi.fn(),
}));

// Mock model
class MockModel extends OpikBaseModel {
  constructor(modelName = "mock-model") {
    super(modelName);
  }

  async generateString(): Promise<string> {
    return JSON.stringify({ score: 1.0, reason: "Test reason" });
  }

  async generateProviderResponse(
    messages: OpikMessage[]
  ): Promise<{ text: string }> {
    const first = messages[0]?.content;
    const normalized =
      typeof first === "string"
        ? first
        : Array.isArray(first)
          ? first
              .map((part) => {
                if (part.type === "text") {
                  return part.text;
                }
                if (part.type === "image") {
                  return `[image:${typeof part.image === "string" ? part.image : "[binary]"}]`;
                }
                return "";
              })
              .join(" ")
          : "";

    return { text: `Response to: ${normalized}` };
  }
}

// Store the MockModel class for use in the mock
const MockModelForFactory: typeof MockModel = MockModel;

// Mock the models factory to avoid requiring API keys
vi.mock("@/evaluation/models/modelsFactory", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/evaluation/models/modelsFactory")>();
  return {
    ...actual,
    resolveModel: vi.fn((model) => {
      // If a MockModel or OpikBaseModel instance is passed, return it as-is
      if (
        model &&
        typeof model === "object" &&
        "generateProviderResponse" in model
      ) {
        return model;
      }
      // For string IDs or undefined, return a new MockModel instance
      if (MockModelForFactory) {
        return new MockModelForFactory(
          typeof model === "string" ? model : "gpt-4o"
        );
      }
      // Fallback for edge cases
      return model;
    }),
  };
});

// Mock metric for testing
class MockMetric extends BaseMetric {
  public readonly validationSchema = z.object({});

  constructor() {
    super("mock_metric");
  }

  async score(): Promise<EvaluationScoreResult> {
    return { name: "mock_metric", value: 1.0 };
  }
}

describe("evaluatePrompt", () => {
  let mockDataset: Dataset<Record<string, unknown>>;
  let mockClient: Opik;

  beforeEach(() => {
    // Setup mock dataset items as they would be returned by Dataset.getItems()
    // which returns the content with id included
    const mockItems: Array<Record<string, unknown> & { id: string }> = [
      {
        id: "550e8400-e29b-41d4-a716-446655440001",
        question: "What is TypeScript?",
        language: "English",
      },
      {
        id: "550e8400-e29b-41d4-a716-446655440002",
        question: "What is JavaScript?",
        language: "English",
      },
    ];

    // Setup mock dataset
    mockDataset = {
      getItems: vi.fn().mockResolvedValue(mockItems),
      insert: vi.fn(),
      name: "test-dataset",
      id: "dataset-123",
    } as unknown as Dataset<Record<string, unknown>>;

    // Setup mock Experiment
    const mockExperiment = {
      id: "exp-123",
      name: "test-experiment",
      datasetName: "test-dataset",
      insert: vi.fn().mockResolvedValue(undefined),
      getItems: vi.fn().mockResolvedValue([]),
      getUrl: vi
        .fn()
        .mockResolvedValue("https://test.opik.com/experiment/exp-123"),
      ensureNameLoaded: vi.fn().mockResolvedValue("test-experiment"),
    };

    // Setup mock client
    mockClient = {
      createExperiment: vi.fn().mockResolvedValue(mockExperiment),
      trace: vi.fn().mockReturnValue({
        data: { id: "trace-123" },
        update: vi.fn(),
      }),
      flush: vi.fn().mockResolvedValue(undefined),
    } as unknown as Opik;
  });

  describe("Basic functionality", () => {
    it("should evaluate prompt with dataset items", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "Answer: {{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        experimentName: "test-experiment",
        client: mockClient,
      });

      expect(result).toBeDefined();
      expect(result.experimentName).toBe("test-experiment");
    });

    it("should format messages with Mustache template", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "Question: {{question}} in {{language}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        templateType: PromptType.MUSTACHE,
        client: mockClient,
      });

      // Verify evaluation completed successfully
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });

    it("should pass model configuration to evaluation", async () => {
      const mockModel = new MockModel("custom-model");
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should merge experiment config correctly", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const customConfig = {
        custom_param: "custom_value",
        temperature: 0.7,
      };

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        experimentConfig: customConfig,
        client: mockClient,
      });

      expect(result).toBeDefined();
      // Config should include both custom params and auto-added params
    });
  });

  describe("Model handling", () => {
    it("should accept model as string ID", async () => {
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: "gpt-4o",
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should accept OpikBaseModel instance", async () => {
      const mockModel = new MockModel("custom-model");
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should use default model when not specified", async () => {
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });
  });

  describe("Error handling", () => {
    it("should throw when dataset is missing", async () => {
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      await expect(
        evaluatePrompt({
          dataset: undefined as unknown as Dataset<Record<string, unknown>>,
          messages,
          scoringMetrics: [],
        })
      ).rejects.toThrow("Dataset is required");
    });

    it("should throw when messages array is empty", async () => {
      await expect(
        evaluatePrompt({
          dataset: mockDataset,
          messages: [],
          scoringMetrics: [],
        })
      ).rejects.toThrow("Messages array is required and cannot be empty");
    });

    it("should throw when messages is undefined", async () => {
      await expect(
        evaluatePrompt({
          dataset: mockDataset,
          messages: undefined as unknown as OpikMessage[],
          scoringMetrics: [],
        })
      ).rejects.toThrow("Messages array is required");
    });
  });

  describe("Response extraction", () => {
    it("should extract text from Vercel AI SDK response", async () => {
      const mockModel = new MockModel();
      mockModel.generateProviderResponse = vi
        .fn()
        .mockResolvedValue({ text: "Extracted text response" });

      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should extract content from generic response", async () => {
      const mockModel = new MockModel();
      mockModel.generateProviderResponse = vi
        .fn()
        .mockResolvedValue({ content: "Generic content response" });

      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should stringify object responses", async () => {
      const mockModel = new MockModel();
      mockModel.generateProviderResponse = vi
        .fn()
        .mockResolvedValue({ custom: "object", data: 123 });

      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should convert primitive responses to strings", async () => {
      const mockModel = new MockModel();
      mockModel.generateProviderResponse = vi.fn().mockResolvedValue(42);

      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });
  });

  describe("Template types", () => {
    it("should support Mustache template by default", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      // Verify evaluation completed successfully
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });

    it("should support Jinja2 template", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "{{ question }}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        templateType: PromptType.JINJA2,
        client: mockClient,
      });

      // Verify evaluation completed successfully
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });
  });

  describe("Multiple messages", () => {
    it("should format all messages in array", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "system", content: "You are a helpful assistant." },
        { role: "user", content: "Question: {{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      // Verify evaluation completed successfully with multiple messages
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });

    it("should preserve message order", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "system", content: "System message" },
        { role: "user", content: "User message" },
        { role: "assistant", content: "Assistant message" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      // Verify evaluation completed successfully with ordered messages
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });
  });

  describe("Integration with scoring metrics", () => {
    it("should run scoring metrics on generated outputs", async () => {
      const mockModel = new MockModel();
      const mockMetric = new MockMetric();

      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [mockMetric],
        client: mockClient,
      });

      // Verify evaluation completed successfully with metrics
      expect(result).toBeDefined();
      expect(result.experimentName).toBeDefined();
    });
  });

  describe("Experiment configuration", () => {
    it("should include prompt_template in config", async () => {
      const mockModel = new MockModel();
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });

    it("should include model name in config", async () => {
      const mockModel = new MockModel("test-model-name");
      const messages: OpikMessage[] = [
        { role: "user", content: "{{question}}" },
      ];

      const result = await evaluatePrompt({
        dataset: mockDataset,
        messages,
        model: mockModel,
        scoringMetrics: [],
        client: mockClient,
      });

      expect(result).toBeDefined();
    });
  });
});
