import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { ExactMatch } from "@/evaluation/metrics/heuristics/ExactMatch";
import { Contains } from "@/evaluation/metrics/heuristics/Contains";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { cleanupDatasets, createSimpleDataset } from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Evaluation Parameters Integration", () => {
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

  describe("nbSamples Parameter", () => {
    it("should evaluate only specified number of samples", async () => {
      // Create dataset with 10 items
      const data = Array.from({ length: 10 }, (_, i) => ({
        input: `Input ${i}`,
        output: `Output ${i}`,
      }));

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      // Evaluate only 3 samples
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        nbSamples: 3,
      });

      expect(result.testResults.length).toBe(3);
    }, 90000);

    it("should evaluate all samples when nbSamples not specified", async () => {
      const data = Array.from({ length: 5 }, (_, i) => ({
        input: `Input ${i}`,
        output: `Output ${i}`,
      }));

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        // No nbSamples specified
      });

      expect(result.testResults.length).toBe(5);
    }, 120000);

    it("should handle nbSamples larger than dataset size", async () => {
      const data = Array.from({ length: 3 }, (_, i) => ({
        input: `Input ${i}`,
        output: `Output ${i}`,
      }));

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        nbSamples: 10, // More than available
      });

      // Should evaluate all available samples (3)
      expect(result.testResults.length).toBe(3);
    }, 90000);

    it("should handle nbSamples = 1", async () => {
      const data = Array.from({ length: 5 }, (_, i) => ({
        input: `Input ${i}`,
        output: `Output ${i}`,
      }));

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 60000);
  });

  describe("scoringKeyMapping Parameter", () => {
    it("should map dataset keys to metric inputs", async () => {
      const data = [
        {
          question: "What is the capital of France?",
          answer: "Paris",
          expected: "Paris",
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Return: {{answer}}" }],
        scoringMetrics: [new ExactMatch()],
        scoringKeyMapping: {
          expected: "expected", // 'expected' field is already correctly named
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].scoreResults.length).toBeGreaterThan(0);
    }, 60000);

    it("should work with Contains metric using scoringKeyMapping", async () => {
      const data = [
        {
          text: "Paris",
          search_term: "Paris",
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Say exactly: {{text}}" }],
        scoringMetrics: [new Contains()],
        scoringKeyMapping: {
          substring: "search_term", // Map substring (for Contains) from search_term (in dataset)
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      // Verify metric ran (may or may not find substring depending on LLM response)
      expect(result.testResults[0].scoreResults).toBeDefined();
    }, 60000);

    it("should handle multiple key mappings for different metrics", async () => {
      const data = [
        {
          response: "Paris",
          expected_value: "Paris",
          search_in: "The answer is Paris",
          find_this: "Paris",
        },
      ];

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Return: {{response}}" }],
        scoringMetrics: [new ExactMatch(), new Contains("contains_check")],
        scoringKeyMapping: {
          expected: "expected_value", // Map expected (for ExactMatch) from expected_value (in dataset)
          substring: "find_this", // Map substring (for Contains) from find_this (in dataset)
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 60000);
  });

  describe("Custom experimentName and projectName", () => {
    it("should use custom experimentName", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const customName = `custom-experiment-${Date.now()}`;

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        experimentName: customName,
        nbSamples: 1,
      });

      expect(result.experimentName).toBe(customName);
    }, 60000);

    it("should use custom projectName", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const customProject = `custom-project-${Date.now()}`;

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        projectName: customProject,
        nbSamples: 1,
      });

      // Experiment should be created successfully
      expect(result.experimentId).toBeDefined();
    }, 60000);

    it("should use both custom experimentName and projectName", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const customExperiment = `exp-${Date.now()}`;
      const customProject = `proj-${Date.now()}`;

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        experimentName: customExperiment,
        projectName: customProject,
        nbSamples: 1,
      });

      expect(result.experimentName).toBe(customExperiment);
    }, 60000);

    it("should generate experimentName when not provided", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        // No experimentName provided
        nbSamples: 1,
      });

      // Should generate a name
      expect(result.experimentName).toBeDefined();
    }, 60000);
  });

  describe("Combined Parameters", () => {
    it("should work with nbSamples, scoringKeyMapping, and custom names together", async () => {
      const data = Array.from({ length: 10 }, (_, i) => ({
        query: `Query ${i}`,
        response: `Response ${i}`,
        expected_response: `Response ${i}`,
      }));

      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Return: {{response}}" }],
        scoringMetrics: [new ExactMatch()],
        nbSamples: 3,
        scoringKeyMapping: {
          expected: "expected_response", // Map expected (for ExactMatch) from expected_response (in dataset)
        },
        experimentName: `combined-test-${Date.now()}`,
        projectName: `combined-project-${Date.now()}`,
      });

      expect(result.testResults.length).toBe(3);
      expect(result.experimentName).toContain("combined-test-");
    }, 90000);
  });

  describe("Parameter Validation", () => {
    it("should handle nbSamples = 0", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        nbSamples: 0,
      });

      // Should evaluate 0 samples
      expect(result.testResults.length).toBe(0);
    }, 60000);

    it("should handle empty scoringKeyMapping", async () => {
      const data = [{ input: "test", output: "result" }];
      const dataset = await createSimpleDataset(client, undefined, data);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringKeyMapping: {}, // Empty mapping
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 60000);
  });
});
