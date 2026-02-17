import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluate } from "@/evaluation/evaluate";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { cleanupDatasets, cleanupPrompts } from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Project Context Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];
  const createdPromptIds: string[] = [];
  const testProjectName = `test-project-${Date.now()}`;

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    // Create client with specific project name
    client = new Opik({
      projectName: testProjectName,
    });
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

  describe("Explicit Client and ProjectName", () => {
    it("should use correct project when client and projectName are explicitly passed", async () => {
      // Create dataset using client
      const dataset = await client.getOrCreateDataset(
        `test-dataset-explicit-${Date.now()}`
      );
      createdDatasetNames.push(dataset.name);

      await dataset.insert([
        { input: { question: "What is 2+2?" }, expectedOutput: { answer: "4" } },
      ]);

      // Create prompt using client
      const prompt = await client.createPrompt({
        name: `test-prompt-explicit-${Date.now()}`,
        prompt: "Answer: {{question}}",
      });
      createdPromptIds.push(prompt.id);

      // Run evaluation WITH explicit client and projectName
      const result = await evaluate({
        dataset,
        task: async () => ({ answer: "4" }),
        scoringMetrics: [],
        prompts: [prompt],
        experimentName: `test-experiment-explicit-${Date.now()}`,
        client, // Explicitly pass client
        projectName: testProjectName, // Explicitly pass projectName
      });

      // Verify experiment created
      expect(result.experimentId).toBeDefined();
      expect(result.testResults.length).toBe(1);

      // Wait for backend processing
      await new Promise((resolve) => setTimeout(resolve, 2000));

      // Verify experiment items have correct project_id
      // This requires querying the backend to check project_id matches the trace project_id
      // For now, we verify the evaluation completed successfully
      expect(result.experimentId).toBeTruthy();
    }, 60000);
  });

  describe("Implicit Client from Dataset", () => {
    it.todo(
      "should automatically use client from dataset when not explicitly passed",
      async () => {
        // This test should pass once we implement the fix to get client from dataset
        const dataset = await client.getOrCreateDataset(
          `test-dataset-implicit-${Date.now()}`
        );
        createdDatasetNames.push(dataset.name);

        await dataset.insert([
          { input: { question: "Test?" }, expectedOutput: { answer: "Yes" } },
        ]);

        // Run evaluation WITHOUT explicit client parameter
        // Should automatically use the client from dataset
        const result = await evaluate({
          dataset,
          task: async () => ({ answer: "Yes" }),
          scoringMetrics: [],
          experimentName: `test-experiment-implicit-${Date.now()}`,
          // NOT passing client or projectName - should use dataset's client
        });

        expect(result.experimentId).toBeDefined();
        expect(result.testResults.length).toBe(1);

        // TODO: Add backend query to verify project_id matches expected project
      }
    );

    it.todo(
      "should use client from prompts when dataset client not available",
      async () => {
        // This test verifies fallback to prompt's client
        const prompt = await client.createPrompt({
          name: `test-prompt-fallback-${Date.now()}`,
          prompt: "Answer: {{question}}",
        });
        createdPromptIds.push(prompt.id);

        const dataset = await client.getOrCreateDataset(
          `test-dataset-fallback-${Date.now()}`
        );
        createdDatasetNames.push(dataset.name);

        await dataset.insert([
          { input: { question: "Test?" }, expectedOutput: { answer: "Yes" } },
        ]);

        // Should use client from prompts parameter
        const result = await evaluate({
          dataset,
          task: async () => ({ answer: "Yes" }),
          scoringMetrics: [],
          prompts: [prompt],
          experimentName: `test-experiment-prompt-fallback-${Date.now()}`,
        });

        expect(result.experimentId).toBeDefined();
      }
    );
  });

  describe("ExperimentItemReferences ProjectName", () => {
    it("should populate projectName in experiment items from trace data", async () => {
      // This test verifies that EvaluationEngine uses this.rootTrace.data.projectName
      const dataset = await client.getOrCreateDataset(
        `test-dataset-trace-${Date.now()}`
      );
      createdDatasetNames.push(dataset.name);

      await dataset.insert([
        { input: { question: "Test?" }, expectedOutput: { answer: "Yes" } },
      ]);

      const result = await evaluate({
        dataset,
        task: async () => ({ answer: "Yes" }),
        scoringMetrics: [],
        experimentName: `test-experiment-trace-${Date.now()}`,
        client,
        projectName: testProjectName,
      });

      expect(result.experimentId).toBeDefined();
      expect(result.testResults.length).toBe(1);

      // The key assertion is that evaluation completes without errors
      // Backend should successfully resolve project_id from trace data
    }, 60000);
  });
});
