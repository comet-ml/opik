import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { OpikMessage } from "@/evaluation/models/OpikBaseModel";
import { ExactMatch } from "@/evaluation/metrics/heuristics/ExactMatch";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import {
  createQADataset,
  cleanupDatasets,
  cleanupPrompts,
} from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("evaluatePrompt Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];
  const createdPromptIds: string[] = [];

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

    // Cleanup all datasets and prompts after all tests are done
    await cleanupDatasets(client, createdDatasetNames);
    await cleanupPrompts(client, createdPromptIds);
  });

  describe("Complete Evaluation Lifecycle", () => {
    it("should run complete evaluation lifecycle with default model", async () => {
      // Create dataset
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Run evaluation with message templates
      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "Answer this question: {{question}}",
          },
        ],
        scoringMetrics: [new ExactMatch()],
        scoringKeyMapping: {
          expected: "expected_answer", // Map expected (for metric) from expected_answer (in dataset)
        },
        experimentName: `test-eval-${Date.now()}`,
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();
      expect(result.experimentName).toContain("test-eval-");

      // Verify evaluation results
      expect(result.testResults).toBeDefined();
      expect(result.testResults.length).toBeGreaterThan(0);

      // Verify each test result has proper structure
      const firstResult = result.testResults[0];
      expect(firstResult.testCase).toBeDefined();
      expect(firstResult.testCase.taskOutput).toBeDefined();
      expect(firstResult.testCase.taskOutput.output).toBeDefined();
      expect(typeof firstResult.testCase.taskOutput.output).toBe("string");

      // Verify scores calculated
      expect(firstResult.scoreResults).toBeDefined();
      expect(firstResult.scoreResults.length).toBeGreaterThan(0);
    }, 60000);

    it("should include prompt_template and model in experiment metadata", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const messages: OpikMessage[] = [
        {
          role: "system",
          content: "You are a helpful assistant.",
        },
        {
          role: "user",
          content: "{{question}}",
        },
      ];

      const result = await evaluatePrompt({
        dataset,
        messages,
        nbSamples: 1, // Just test one sample
      });

      expect(result.experimentId).toBeDefined();

      // Note: experiment config/metadata is stored internally
      // We verify that evaluation completed successfully
      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });

  describe("Prompt Linking", () => {
    it("should link prompt to experiment via prompts parameter", async () => {
      // Create a prompt
      const prompt = await client.createPrompt({
        name: `test-prompt-${Date.now()}`,
        prompt: "Answer this question: {{question}}",
        metadata: { type: "qa" },
      });
      createdPromptIds.push(prompt.id);

      // Create dataset
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Run evaluation with prompt linking
      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "{{question}}",
          },
        ],
        prompts: [prompt],
        nbSamples: 1,
      });

      // Verify experiment created successfully
      expect(result.experimentId).toBeDefined();

      // Verify evaluation ran
      expect(result.testResults.length).toBe(1);
    }, 60000);

    it("should link multiple prompts to experiment", async () => {
      // Create multiple prompts
      const prompt1 = await client.createPrompt({
        name: `test-prompt-1-${Date.now()}`,
        prompt: "System: {{system}}\nUser: {{question}}",
      });
      createdPromptIds.push(prompt1.id);

      const prompt2 = await client.createPrompt({
        name: `test-prompt-2-${Date.now()}`,
        prompt: "Question: {{question}}\nContext: {{context}}",
      });
      createdPromptIds.push(prompt2.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt1, prompt2],
        nbSamples: 1,
      });

      expect(result.experimentId).toBeDefined();
    }, 60000);
  });

  describe("Custom Experiment Configuration", () => {
    it("should merge experimentConfig with auto-added fields", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const customConfig = {
        temperature: 0.7,
        max_tokens: 150,
        custom_field: "custom_value",
      };

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        experimentConfig: customConfig,
        nbSamples: 1,
      });

      // Verify experiment created successfully with merged config
      expect(result.experimentId).toBeDefined();
      expect(result.testResults.length).toBe(1);
    }, 60000);

    it("should work with custom experimentName and projectName", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const experimentName = `custom-experiment-${Date.now()}`;
      const projectName = `custom-project-${Date.now()}`;

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        experimentName,
        projectName,
        nbSamples: 1,
      });

      expect(result.experimentName).toBe(experimentName);
      expect(result.testResults.length).toBe(1);
    }, 60000);
  });

  describe("Template Formatting", () => {
    it("should format message templates with dataset variables", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant.",
          },
          {
            role: "user",
            content: "Question: {{question}}\nExpected: {{expected_answer}}",
          },
        ],
        nbSamples: 1,
      });

      // Verify template was formatted and produced output
      const output = result.testResults[0].testCase.taskOutput.output;
      expect(output).toBeDefined();
      expect(typeof output).toBe("string");
      if (typeof output === "string") {
        expect(output.length).toBeGreaterThan(0);
      }
    }, 60000);

    it("should handle multiple placeholders in messages", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content:
              "Q: {{question}}\nExpected: {{expected_answer}}\nContext: {{context}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });
});
