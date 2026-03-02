import { describe, it, expect, vi, beforeEach } from "vitest";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import type { LLMJudgeConfig } from "@/evaluation/suite_evaluators/llmJudgeConfig";

// Mock model for testing
class MockModel extends OpikBaseModel {
  public lastMessages: unknown[] = [];
  public lastOptions: Record<string, unknown> = {};
  private mockResponse: string;

  constructor(mockResponse: string) {
    super("mock-model");
    this.mockResponse = mockResponse;
  }

  async generateString(
    _input: string,
    _responseFormat?: unknown,
    _options?: Record<string, unknown>
  ): Promise<string> {
    return this.mockResponse;
  }

  async generateProviderResponse(
    messages: unknown[],
    options?: Record<string, unknown>
  ): Promise<unknown> {
    this.lastMessages = messages;
    this.lastOptions = options ?? {};
    return {
      output: JSON.parse(this.mockResponse),
    };
  }
}

// Mock resolveModel so LLMJudge doesn't need real API keys
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(
    (model?: unknown) =>
      new MockModel(
        JSON.stringify({
          "assertion-a": {
            score: true,
            reason: "Looks good",
            confidence: 0.95,
          },
        })
      )
  ),
}));

describe("LLMJudge", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("constructor", () => {
    it("should set default name to 'llm_judge'", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        track: false,
      });
      expect(judge.name).toBe("llm_judge");
    });

    it("should set default model to 'gpt-5-nano'", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        track: false,
      });
      expect(judge.modelName).toBe("gpt-5-nano");
    });

    it("should accept custom name", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        name: "my_judge",
        track: false,
      });
      expect(judge.name).toBe("my_judge");
    });

    it("should accept custom model name", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        model: "gpt-4o",
        track: false,
      });
      expect(judge.modelName).toBe("gpt-4o");
    });

    it("should store seed and temperature", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        seed: 42,
        temperature: 0.5,
        track: false,
      });
      expect(judge.seed).toBe(42);
      expect(judge.temperature).toBe(0.5);
    });

    it("should store projectName", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        projectName: "my_project",
        track: false,
      });
      expect(judge.projectName).toBe("my_project");
    });

    it("should throw Error for empty assertions array", () => {
      expect(
        () => new LLMJudge({ assertions: [], track: false })
      ).toThrow("LLMJudge requires at least one assertion");
    });

    it("should throw Error for assertions containing empty string", () => {
      expect(
        () => new LLMJudge({ assertions: ["valid", ""], track: false })
      ).toThrow("LLMJudge assertions must be non-empty strings");
    });

    it("should throw Error for assertions containing whitespace-only string", () => {
      expect(
        () => new LLMJudge({ assertions: ["  "], track: false })
      ).toThrow("LLMJudge assertions must be non-empty strings");
    });
  });

  describe("toConfig", () => {
    it("should return correct config structure", () => {
      const judge = new LLMJudge({
        assertions: ["Output is relevant", "Output is concise"],
        name: "test_judge",
        model: "gpt-4o",
        temperature: 0.3,
        seed: 42,
        track: false,
      });

      const config = judge.toConfig();

      expect(config).toMatchObject({
        version: "1.0.0",
        name: "test_judge",
        model: {
          name: "gpt-4o",
          temperature: 0.3,
          seed: 42,
        },
      });

      // Check messages
      expect(config.messages).toBeInstanceOf(Array);
      const messages = config.messages as Array<{
        role: string;
        content: string;
      }>;
      expect(messages).toHaveLength(2);
      expect(messages[0].role).toBe("SYSTEM");
      expect(messages[1].role).toBe("USER");

      // Check schema
      expect(config.schema).toBeInstanceOf(Array);
      const schema = config.schema as Array<{
        name: string;
        type: string;
        description: string;
      }>;
      expect(schema).toHaveLength(2);
      expect(schema[0]).toEqual({
        name: "Output is relevant",
        type: "BOOLEAN",
        description: "Output is relevant",
      });
      expect(schema[1]).toEqual({
        name: "Output is concise",
        type: "BOOLEAN",
        description: "Output is concise",
      });
    });

    it("should include variables with input, output, and assertions", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        track: false,
      });

      const config = judge.toConfig();
      const variables = config.variables as Record<string, string>;

      expect(variables).toHaveProperty("input");
      expect(variables).toHaveProperty("output");
      expect(variables).toHaveProperty("assertions");
    });

    it("should omit undefined temperature and seed from model config", () => {
      const judge = new LLMJudge({
        assertions: ["Is correct"],
        track: false,
      });

      const config = judge.toConfig();
      const model = config.model as Record<string, unknown>;

      expect(model.temperature).toBeUndefined();
      expect(model.seed).toBeUndefined();
    });
  });

  describe("fromConfig", () => {
    it("should reconstruct LLMJudge from config", () => {
      const config: LLMJudgeConfig = {
        version: "1.0.0",
        name: "restored_judge",
        model: {
          name: "gpt-4o",
          temperature: 0.5,
          seed: 123,
        },
        messages: [
          { role: "SYSTEM", content: "system prompt" },
          { role: "USER", content: "user prompt" },
        ],
        variables: { input: "string", output: "string", assertions: "string" },
        schema: [
          {
            name: "Output is relevant",
            type: "BOOLEAN",
            description: "Output is relevant",
          },
          {
            name: "Output is concise",
            type: "BOOLEAN",
            description: "Output is concise",
          },
        ],
      };

      const judge = LLMJudge.fromConfig(config);

      expect(judge.name).toBe("restored_judge");
      expect(judge.modelName).toBe("gpt-4o");
      expect(judge.temperature).toBe(0.5);
      expect(judge.seed).toBe(123);
      expect(judge.assertions).toEqual([
        "Output is relevant",
        "Output is concise",
      ]);
    });

    it("should support model override via options", () => {
      const config: LLMJudgeConfig = {
        version: "1.0.0",
        name: "judge",
        model: { name: "gpt-5-nano" },
        messages: [
          { role: "SYSTEM", content: "sys" },
          { role: "USER", content: "usr" },
        ],
        variables: {},
        schema: [
          { name: "Is correct", type: "BOOLEAN", description: "Is correct" },
        ],
      };

      const judge = LLMJudge.fromConfig(config, { model: "gpt-4o" });

      expect(judge.modelName).toBe("gpt-4o");
    });
  });

  describe("round-trip (toConfig -> fromConfig)", () => {
    it("should preserve all fields through round-trip", () => {
      const original = new LLMJudge({
        assertions: ["Assertion A", "Assertion B"],
        name: "round_trip_judge",
        model: "gpt-4o",
        temperature: 0.7,
        seed: 99,
        track: false,
      });

      const config = original.toConfig();
      const restored = LLMJudge.fromConfig(
        config as LLMJudgeConfig,
        { track: false }
      );

      expect(restored.name).toBe(original.name);
      expect(restored.modelName).toBe(original.modelName);
      expect(restored.temperature).toBe(original.temperature);
      expect(restored.seed).toBe(original.seed);
      expect(restored.assertions).toEqual(original.assertions);
    });
  });

  describe("score", () => {
    it("should return ScoreResult[] with one entry per assertion", async () => {
      const mockModel = new MockModel(
        JSON.stringify({
          "Output is relevant": {
            score: true,
            reason: "Relevant",
            confidence: 0.9,
          },
          "Output is concise": {
            score: false,
            reason: "Too long",
            confidence: 0.8,
          },
        })
      );

      // Need to re-mock resolveModel for this specific test
      const modelsFactory = await import(
        "@/evaluation/models/modelsFactory"
      );
      vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);

      const judge = new LLMJudge({
        assertions: ["Output is relevant", "Output is concise"],
        track: false,
      });

      const results = await judge.score({
        input: "What is 2+2?",
        output: "Four",
      });

      expect(results).toHaveLength(2);
      expect(results[0]).toEqual({
        name: "Output is relevant",
        value: 1,
        reason: "Relevant",
        categoryName: "suite_assertion",
      });
      expect(results[1]).toEqual({
        name: "Output is concise",
        value: 0,
        reason: "Too long",
        categoryName: "suite_assertion",
      });
    });

    it("should return scoringFailed: true on LLM error", async () => {
      const failingModel = new MockModel("{}");
      failingModel.generateProviderResponse = async () => {
        throw new Error("LLM API error");
      };

      const modelsFactory = await import(
        "@/evaluation/models/modelsFactory"
      );
      vi.mocked(modelsFactory.resolveModel).mockReturnValue(failingModel);

      const judge = new LLMJudge({
        assertions: ["Is correct"],
        track: false,
      });

      const results = await judge.score({
        input: "test",
        output: "test",
      });

      expect(results).toHaveLength(1);
      expect(results[0].scoringFailed).toBe(true);
      expect(results[0].value).toBe(0);
      expect(results[0].name).toBe("Is correct");
      expect(results[0].categoryName).toBe("suite_assertion");
    });
  });
});
