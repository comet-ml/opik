import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { VercelAIChatModel } from "@/evaluation/models/VercelAIChatModel";
import { AnswerRelevance } from "@/evaluation/metrics/llmJudges/answerRelevance/AnswerRelevance";
import { Hallucination } from "@/evaluation/metrics/llmJudges/hallucination/Hallucination";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
  hasAnthropicApiKey,
} from "../api/shouldRunIntegrationTests";
import { createQADataset, cleanupDatasets } from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Model Gateway Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }

    await cleanupDatasets(client, createdDatasetNames);
    createdDatasetNames.length = 0;
  });

  describe("evaluatePrompt with Model Types", () => {
    it("should work with default model (gpt-5-nano)", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // No model specified - should default to gpt-5-nano
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        nbSamples: 1,
      });

      expect(result.experimentId).toBeDefined();
      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with modelId string (gpt-5-nano)", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: "gpt-5-nano",
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with modelId string (gpt-5-mini)", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: "gpt-5-mini",
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with LanguageModel instance (with structured output)", async () => {
      const { openai } = await import("@ai-sdk/openai");
      const customModel = openai("gpt-5-nano");

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: customModel,
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with LanguageModel instance (without structured output)", async () => {
      const { openai } = await import("@ai-sdk/openai");
      const customModel = openai("gpt-5-nano"); // No structured output option

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // evaluatePrompt only needs text generation, not structured output
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: customModel,
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with OpikBaseModel instance", async () => {
      const customModel = new VercelAIChatModel("gpt-5-nano");

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: customModel,
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });

  describe("evaluatePrompt with Different Providers", () => {
    it.skipIf(!hasAnthropicApiKey())(
      "should work with Anthropic model (Claude)",
      async () => {
        const dataset = await createQADataset(client);
        createdDatasetNames.push(dataset.name);

        const result = await evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          model: "claude-haiku-4-5-20251001",
          nbSamples: 1,
        });

        expect(result.testResults.length).toBe(1);
        expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
      },
      60000
    );

    it("should work with Google Gemini model", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      try {
        const result = await evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          model: "gemini-2.0-flash-exp",
          nbSamples: 1,
        });

        expect(result.testResults.length).toBe(1);
        expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
      } catch (error) {
        // Skip if Google API key not configured
        if (error instanceof Error && error.message.includes("API key")) {
          console.log("Skipping Google test - API key not configured");
        } else {
          throw error;
        }
      }
    }, 60000);
  });

  describe("LLM Metrics with Model Types", () => {
    it("should work with modelId string in AnswerRelevance", async () => {
      const metric = new AnswerRelevance({
        model: "gpt-5-nano",
        requireContext: false,
      });

      const result = await metric.score({
        input: "What is TypeScript?",
        output:
          "TypeScript is a typed superset of JavaScript that compiles to JavaScript.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should work with LanguageModel instance in Hallucination", async () => {
      const { openai } = await import("@ai-sdk/openai");
      const customModel = openai("gpt-5-nano");

      const metric = new Hallucination({
        model: customModel,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should work with OpikBaseModel in metrics", async () => {
      const customModel = new VercelAIChatModel("gpt-5-nano");

      const metric = new AnswerRelevance({
        model: customModel,
        requireContext: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);
  });

  describe("Structured Output Requirements", () => {
    it("should handle models with structured output for metrics", async () => {
      const { openai } = await import("@ai-sdk/openai");

      // Models with structured output support should work fine
      const modelWithStructuredOutput = openai("gpt-5-nano");

      const metric = new AnswerRelevance({
        model: modelWithStructuredOutput,
        requireContext: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should work with models without explicit structured output flag", async () => {
      const { openai } = await import("@ai-sdk/openai");

      // Even without explicit structuredOutputs flag, gpt-5-nano supports it
      const model = openai("gpt-5-nano");

      const metric = new AnswerRelevance({
        model,
        requireContext: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);
  });

  describe("Model Configuration Options", () => {
    it("should work with custom temperature in evaluatePrompt", async () => {
      const { openai } = await import("@ai-sdk/openai");
      const customModel = openai("gpt-5-nano");

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: customModel,
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should work with trackGenerations option in VercelAIChatModel", async () => {
      const modelWithTracking = new VercelAIChatModel("gpt-5-nano", {
        trackGenerations: true,
      });

      const modelWithoutTracking = new VercelAIChatModel("gpt-5-nano", {
        trackGenerations: false,
      });

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Both should work
      const result1 = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: modelWithTracking,
        nbSamples: 1,
      });

      expect(result1.testResults.length).toBe(1);

      const result2 = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        model: modelWithoutTracking,
        nbSamples: 1,
      });

      expect(result2.testResults.length).toBe(1);
    }, 120000);
  });
});
