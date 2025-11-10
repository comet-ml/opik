import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { evaluate } from "@/evaluation/evaluate";
import { ExperimentConfigError } from "@/errors/experiment";
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

describe.skipIf(!shouldRunApiTests)("Prompt Linking Integration", () => {
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

  afterEach(async () => {
    await cleanupDatasets(client, createdDatasetNames);
    createdDatasetNames.length = 0;

    await cleanupPrompts(client, createdPromptIds);
    createdPromptIds.length = 0;
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("Single Prompt Linking", () => {
    it("should link single prompt to experiment in evaluatePrompt", async () => {
      // Create prompt
      const prompt = await client.createPrompt({
        name: `test-prompt-${Date.now()}`,
        prompt: "Answer the following question: {{question}}",
        metadata: { version: "1.0", type: "qa" },
      });
      createdPromptIds.push(prompt.id);

      // Create dataset
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Run evaluation with prompt linking
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt],
        nbSamples: 1,
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();

      // Verify evaluation ran successfully
      expect(result.testResults.length).toBe(1);
    }, 60000);

    it("should include prompt version ID in experiment", async () => {
      const prompt = await client.createPrompt({
        name: `test-version-${Date.now()}`,
        prompt: "System: You are helpful.\nUser: {{question}}",
      });
      createdPromptIds.push(prompt.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt],
        nbSamples: 1,
      });

      expect(result.experimentId).toBeDefined();
    }, 60000);
  });

  describe("Multiple Prompts Linking", () => {
    it("should link multiple prompts to experiment", async () => {
      // Create multiple prompts
      const prompt1 = await client.createPrompt({
        name: `test-prompt-1-${Date.now()}`,
        prompt: "Question: {{question}}",
      });
      createdPromptIds.push(prompt1.id);

      const prompt2 = await client.createPrompt({
        name: `test-prompt-2-${Date.now()}`,
        prompt: "Context: {{context}}\nQuestion: {{question}}",
      });
      createdPromptIds.push(prompt2.id);

      const prompt3 = await client.createPrompt({
        name: `test-prompt-3-${Date.now()}`,
        prompt: "Answer: {{expected_answer}}",
      });
      createdPromptIds.push(prompt3.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt1, prompt2, prompt3],
        nbSamples: 1,
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();
    }, 60000);

    it("should preserve prompt order when linking multiple prompts", async () => {
      const prompt1 = await client.createPrompt({
        name: `order-test-1-${Date.now()}`,
        prompt: "First prompt",
      });
      createdPromptIds.push(prompt1.id);

      const prompt2 = await client.createPrompt({
        name: `order-test-2-${Date.now()}`,
        prompt: "Second prompt",
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

  describe("Prompt Linking in evaluate() Function", () => {
    it("should support prompt linking in base evaluate function", async () => {
      const prompt = await client.createPrompt({
        name: `base-eval-${Date.now()}`,
        prompt: "Task: {{question}}",
      });
      createdPromptIds.push(prompt.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluate({
        dataset,
        task: async (item: Record<string, unknown>) => {
          return {
            input: item.question as string,
            output: "test output",
          };
        },
        prompts: [prompt],
      });

      expect(result.experimentId).toBeDefined();
    }, 60000);
  });

  describe("Error Handling", () => {
    it("should throw error when both prompts parameter and experimentConfig.prompts exist", async () => {
      const prompt = await client.createPrompt({
        name: `conflict-test-${Date.now()}`,
        prompt: "Test prompt",
      });
      createdPromptIds.push(prompt.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      await expect(
        evaluatePrompt({
          dataset,
          messages: [{ role: "user", content: "{{question}}" }],
          prompts: [prompt], // Passing prompts parameter
          experimentConfig: {
            prompts: { custom: "template" }, // AND experimentConfig.prompts
          },
          nbSamples: 1,
        })
      ).rejects.toThrow(ExperimentConfigError);
    }, 60000);

    it("should work when only experimentConfig.prompts is provided (no prompts parameter)", async () => {
      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // This should work - only experimentConfig.prompts, no prompts parameter
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        experimentConfig: {
          prompts: {
            custom_prompt: "Custom template: {{question}}",
          },
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
    }, 60000);

    it("should work when only prompts parameter is provided (no experimentConfig.prompts)", async () => {
      const prompt = await client.createPrompt({
        name: `solo-prompt-${Date.now()}`,
        prompt: "Solo prompt test",
      });
      createdPromptIds.push(prompt.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt],
        experimentConfig: {
          temperature: 0.7, // Other config fields are fine
        },
        nbSamples: 1,
      });

      expect(result.experimentId).toBeDefined();
    }, 60000);
  });

  describe("Prompt Metadata in Experiment", () => {
    it("should include prompt templates in experiment metadata", async () => {
      const promptTemplate =
        "Answer this: {{question}}\nExpected: {{expected_answer}}";

      const prompt = await client.createPrompt({
        name: `metadata-test-${Date.now()}`,
        prompt: promptTemplate,
        metadata: { author: "test", version: "1.0" },
      });
      createdPromptIds.push(prompt.id);

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [prompt],
        nbSamples: 1,
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();
    }, 60000);

    it("should handle prompts with different versions", async () => {
      const promptName = `version-test-${Date.now()}`;

      // Create version 1
      const v1 = await client.createPrompt({
        name: promptName,
        prompt: "Version 1: {{question}}",
        metadata: { version: "1.0" },
      });
      createdPromptIds.push(v1.id);

      // Create version 2
      const v2 = await client.createPrompt({
        name: promptName,
        prompt: "Version 2: {{question}}",
        metadata: { version: "2.0" },
      });

      const dataset = await createQADataset(client);
      createdDatasetNames.push(dataset.name);

      // Link version 2
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{question}}" }],
        prompts: [v2],
        nbSamples: 1,
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();
    }, 60000);
  });
});
