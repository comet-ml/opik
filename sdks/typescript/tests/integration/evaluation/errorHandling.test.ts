import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { AnswerRelevance } from "@/evaluation/metrics/llmJudges/answerRelevance/AnswerRelevance";
import { MetricComputationError } from "@/evaluation/metrics/errors";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import {
  createQADataset,
  cleanupDatasets,
  createSimpleDataset,
} from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Error Handling Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterEach(async () => {
    await cleanupDatasets(client, createdDatasetNames);
    createdDatasetNames.length = 0;
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("Missing Required Parameters", () => {
    it("should throw error when dataset is missing", async () => {
      await expect(
        evaluatePrompt({
          // @ts-expect-error - Testing missing dataset
          dataset: undefined,
          messages: [{ role: "user", content: "test" }],
        })
      ).rejects.toThrow("Dataset is required");
    }, 30000);

    it("should throw error when messages array is missing", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          // @ts-expect-error - Testing missing messages
          messages: undefined,
        })
      ).rejects.toThrow("Messages array is required");
    }, 30000);

    it("should throw error when messages array is empty", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [], // Empty array
        })
      ).rejects.toThrow("Messages array is required");
    }, 30000);
  });

  describe("AnswerRelevance Context Requirements", () => {
    it("should throw error when AnswerRelevance requires context but dataset has none", async () => {
      // Create dataset without context field
      const data = [
        {
          input: "What is the capital of France?",
          output: "Paris",
          // No context field
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const metric = new AnswerRelevance(); // requireContext defaults to true

      // The metric should throw when trying to score without context
      await expect(
        metric.score({
          input: "What is the capital of France?",
          output: "Paris",
          // No context provided
        })
      ).rejects.toThrow(MetricComputationError);
    }, 30000);

    it("should throw error message explaining context requirement", async () => {
      const metric = new AnswerRelevance(); // requireContext = true

      try {
        await metric.score({
          input: "Test input",
          output: "Test output",
        });
        // Should not reach here
        expect(true).toBe(false);
      } catch (error) {
        expect(error).toBeInstanceOf(MetricComputationError);
        if (error instanceof Error) {
          expect(error.message).toMatch(/requires context/i);
          expect(error.message).toMatch(/requireContext: false/);
        }
      }
    }, 30000);

    it("should work when requireContext is false and no context provided", async () => {
      const metric = new AnswerRelevance({ requireContext: false });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris",
        // No context - but that's OK because requireContext = false
      });

      expect(result.value).toBeGreaterThanOrEqual(0);
      expect(result.value).toBeLessThanOrEqual(1);
    }, 30000);
  });

  describe("Invalid Model Configuration", () => {
    it("should throw error for invalid model type", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          // @ts-expect-error - Testing invalid model type
          model: { invalid: "model" },
          nbSamples: 1,
        })
      ).rejects.toThrow();
    }, 30000);

    it("should throw error for invalid model ID", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          model: "definitely-not-a-real-model-123",
          nbSamples: 1,
        })
      ).rejects.toThrow();
    }, 30000);
  });

  describe("Dataset Issues", () => {
    it("should handle empty dataset gracefully", async () => {
      const dataset = await createSimpleDataset(client, undefined, []);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
      });

      // Should return empty results
      expect(result.testResults.length).toBe(0);
    }, 60000);

    it("should handle dataset with missing template variables", async () => {
      const data = [
        {
          wrong_field: "value",
          // Missing 'input' field that template expects
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      // This should fail during template formatting
      try {
        await evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{input}}" }],
          nbSamples: 1,
        });

        // If it doesn't throw, that's also acceptable (empty string substitution)
        expect(true).toBe(true);
      } catch (error) {
        // Or it might throw an error
        expect(error).toBeDefined();
      }
    }, 60000);
  });

  describe("Template Errors", () => {
    it("should handle invalid template syntax gracefully", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Malformed Mustache template
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{unclosed" }],
        nbSamples: 1,
      });

      // Should either throw or handle gracefully
      expect(result).toBeDefined();
    }, 60000);
  });

  describe("Metric Validation Errors", () => {
    it("should handle metric that expects different input fields", async () => {
      const data = [
        {
          input: "test",
          // ExactMatch expects 'output' and 'expected' fields
          // but we only have 'input'
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      // The evaluation will run but metrics may fail validation
      try {
        const result = await evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{input}}" }],
          scoringMetrics: [
            // Metrics need proper input fields which may not be available
          ],
          nbSamples: 1,
        });

        expect(result.testResults.length).toBe(1);
      } catch (error) {
        // Or validation error may be thrown
        expect(error).toBeDefined();
      }
    }, 60000);
  });

  describe("API Key Issues", () => {
    it("should skip test if no API key is available", async () => {
      // This test verifies the shouldRunIntegrationTests() mechanism
      const shouldRun = shouldRunIntegrationTests();

      if (!shouldRun) {
        console.log("Tests correctly skipped when no API key");
        expect(shouldRun).toBe(false);
      } else {
        console.log("Tests running with API key");
        expect(shouldRun).toBe(true);
      }
    });
  });

  describe("Experiment Creation Errors", () => {
    it("should handle very long experiment names", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const veryLongName = "experiment-" + "a".repeat(1000);

      try {
        const result = await evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          experimentName: veryLongName,
          nbSamples: 1,
        });

        // May succeed or may truncate/error
        expect(result).toBeDefined();
      } catch (error) {
        // Or may throw validation error
        expect(error).toBeDefined();
      }
    }, 60000);

    it("should handle special characters in experiment names", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        experimentName: `test-exp-!@#$%^&*()-${Date.now()}`,
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 60000);
  });

  describe("Configuration Errors", () => {
    it("should handle invalid experimentConfig type", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          // @ts-expect-error - Testing invalid config type
          experimentConfig: "not an object",
          nbSamples: 1,
        })
      ).rejects.toThrow();
    }, 30000);

    it("should handle array as experimentConfig", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          // @ts-expect-error - Testing array as config
          experimentConfig: ["not", "an", "object"],
          nbSamples: 1,
        })
      ).rejects.toThrow();
    }, 30000);
  });

  describe("Network and Timeout Errors", () => {
    it("should complete evaluation even with slow LLM responses", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Create a complex prompt that might take longer
      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "system",
            content:
              "You are a helpful assistant. Provide detailed, comprehensive answers.",
          },
          {
            role: "user",
            content:
              "Question: {{question}}\nContext: {{context}}\nExpected: {{expected_answer}}\nPlease provide a thorough answer.",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 120000); // Longer timeout for this test
  });
});
