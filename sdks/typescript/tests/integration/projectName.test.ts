/**
 * Integration tests for project_name parameter support across the TypeScript SDK.
 *
 * Verifies that datasets, experiments, and prompts can be created and retrieved
 * within a specific project scope, matching the behavior added in the Python SDK
 * (PR #5684 / OPIK-4961).
 *
 * These tests require a running Opik server (local or cloud).
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { Dataset } from "@/dataset/Dataset";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "Project Name Parameter Integration Tests",
  () => {
    let client: Opik;
    const testTimestamp = Date.now();
    const testProjectName = `test-project-name-${testTimestamp}`;
    const createdDatasetNames: string[] = [];
    const createdExperimentIds: string[] = [];
    const createdPromptIds: string[] = [];

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik({
        projectName: testProjectName,
      });
    });

    afterAll(async () => {
      if (!client) {
        return;
      }

      // Cleanup prompts
      for (const promptId of createdPromptIds) {
        try {
          await client.deletePrompts([promptId]);
        } catch {
          // Ignore cleanup errors
        }
      }

      // Cleanup experiments
      for (const experimentId of createdExperimentIds) {
        try {
          await client.deleteExperiment(experimentId);
        } catch {
          // Ignore cleanup errors
        }
      }

      // Cleanup datasets
      for (const datasetName of createdDatasetNames) {
        try {
          await client.deleteDataset(datasetName);
        } catch {
          // Ignore cleanup errors
        }
      }

      await client.flush();
    }, 30000);

    describe("Dataset with projectName", () => {
      it("should create and retrieve a dataset with default project", async () => {
        const datasetName = `test-ds-default-project-${testTimestamp}`;

        // Create dataset (uses client's default project)
        const dataset = await client.createDataset(datasetName, "Test dataset");
        createdDatasetNames.push(datasetName);
        await client.flush();

        expect(dataset).toBeInstanceOf(Dataset);
        expect(dataset.name).toBe(datasetName);
        expect(dataset.projectName).toBe(testProjectName);

        // Retrieve dataset (should use same project)
        const retrieved = await client.getDataset(datasetName);
        expect(retrieved.name).toBe(datasetName);
        expect(retrieved.projectName).toBe(testProjectName);
      });

      it("should create and retrieve a dataset with explicit project", async () => {
        const datasetName = `test-ds-explicit-project-${testTimestamp}`;
        const explicitProject = `explicit-project-${testTimestamp}`;

        // Create dataset with explicit project name
        const dataset = await client.createDataset(
          datasetName,
          "Test dataset with explicit project",
          explicitProject
        );
        createdDatasetNames.push(datasetName);
        await client.flush();

        expect(dataset).toBeInstanceOf(Dataset);
        expect(dataset.name).toBe(datasetName);
        expect(dataset.projectName).toBe(explicitProject);

        // Retrieve dataset with same explicit project
        const retrieved = await client.getDataset(datasetName, explicitProject);
        expect(retrieved.name).toBe(datasetName);
        expect(retrieved.projectName).toBe(explicitProject);
      });

      it("should getOrCreateDataset with projectName", async () => {
        const datasetName = `test-ds-get-or-create-${testTimestamp}`;

        // First call creates
        const created = await client.getOrCreateDataset(
          datasetName,
          "Test getOrCreate"
        );
        createdDatasetNames.push(datasetName);
        await client.flush();

        expect(created.name).toBe(datasetName);
        expect(created.projectName).toBe(testProjectName);

        // Second call retrieves existing
        const retrieved = await client.getOrCreateDataset(datasetName);
        expect(retrieved.name).toBe(datasetName);
        expect(retrieved.projectName).toBe(testProjectName);
        expect(retrieved.id).toBe(created.id);
      });

      it("should insert and read items from project-scoped dataset", async () => {
        const datasetName = `test-ds-items-project-${testTimestamp}`;

        const dataset = await client.createDataset(datasetName);
        createdDatasetNames.push(datasetName);
        await client.flush();

        // Insert items
        await dataset.insert([
          { input: "Hello", output: "Hi" },
          { input: "Goodbye", output: "Bye" },
        ]);

        // Wait for items to be available
        const items = await searchAndWaitForDone(
          async () => {
            const freshDataset = await client.getDataset(datasetName);
            return await freshDataset.getItems();
          },
          2,
          10000,
          1000
        );

        expect(items.length).toBe(2);
      });

      it("should delete a project-scoped dataset", async () => {
        const datasetName = `test-ds-delete-project-${testTimestamp}`;

        await client.createDataset(datasetName);
        await client.flush();

        // Wait for dataset to exist
        await searchAndWaitForDone(
          async () => {
            try {
              const ds = await client.getDataset(datasetName);
              return [ds];
            } catch {
              return [];
            }
          },
          1,
          5000,
          500
        );

        // Delete it
        await client.deleteDataset(datasetName);
        await client.flush();

        // Verify it's gone
        await expect(client.getDataset(datasetName)).rejects.toThrow();
      });
    });

    describe("Experiment with projectName", () => {
      it("should create an experiment with projectName", async () => {
        const datasetName = `test-ds-exp-project-${testTimestamp}`;
        const experimentName = `test-exp-project-${testTimestamp}`;

        await client.createDataset(datasetName);
        createdDatasetNames.push(datasetName);

        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName,
          experimentConfig: { model: "test-model" },
        });
        createdExperimentIds.push(experiment.id);

        expect(experiment).toBeDefined();
        expect(experiment.id).toBeDefined();
        expect(experiment.name).toBe(experimentName);
        expect(experiment.projectName).toBe(testProjectName);
      });

      it("should create an experiment with explicit projectName", async () => {
        const datasetName = `test-ds-exp-explicit-${testTimestamp}`;
        const experimentName = `test-exp-explicit-${testTimestamp}`;
        const explicitProject = `explicit-exp-project-${testTimestamp}`;

        await client.createDataset(datasetName, undefined, explicitProject);
        createdDatasetNames.push(datasetName);

        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName,
          projectName: explicitProject,
        });
        createdExperimentIds.push(experiment.id);

        expect(experiment.projectName).toBe(explicitProject);
      });

      it("should retrieve experiments by name with projectName", async () => {
        const datasetName = `test-ds-exp-retrieve-${testTimestamp}`;
        const experimentName = `test-exp-retrieve-${testTimestamp}`;

        await client.createDataset(datasetName);
        createdDatasetNames.push(datasetName);

        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName,
        });
        createdExperimentIds.push(experiment.id);

        await client.flush();

        // Wait for experiment to be available
        const experiments = await searchAndWaitForDone(
          async () => {
            try {
              return await client.getExperimentsByName(experimentName);
            } catch {
              return [];
            }
          },
          1,
          5000,
          500
        );

        expect(experiments.length).toBeGreaterThanOrEqual(1);
        expect(experiments[0].name).toBe(experimentName);
      });

      it("should get dataset experiments with projectName", async () => {
        const datasetName = `test-ds-exp-list-${testTimestamp}`;
        const experimentName = `test-exp-list-${testTimestamp}`;

        await client.createDataset(datasetName);
        createdDatasetNames.push(datasetName);

        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName,
        });
        createdExperimentIds.push(experiment.id);

        await client.flush();

        // Wait for experiment to be available via getDatasetExperiments
        const experiments = await searchAndWaitForDone(
          async () => {
            try {
              return await client.getDatasetExperiments(datasetName);
            } catch {
              return [];
            }
          },
          1,
          10000,
          1000
        );

        expect(experiments.length).toBeGreaterThanOrEqual(1);
      });
    });

    describe("Prompt with projectName", () => {
      it("should create and retrieve a text prompt with projectName", async () => {
        const promptName = `test-prompt-project-${testTimestamp}`;

        const prompt = await client.createPrompt({
          name: promptName,
          prompt: "Hello {{name}}, welcome to {{place}}!",
        });
        createdPromptIds.push(prompt.id!);

        expect(prompt).toBeDefined();
        expect(prompt.name).toBe(promptName);

        // Retrieve prompt
        const retrieved = await client.getPrompt({ name: promptName });
        expect(retrieved).not.toBeNull();
        expect(retrieved!.name).toBe(promptName);
      });

      it("should create and retrieve a chat prompt with projectName", async () => {
        const promptName = `test-chat-prompt-project-${testTimestamp}`;

        const chatPrompt = await client.createChatPrompt({
          name: promptName,
          messages: [
            { role: "system", content: "You are a helpful assistant." },
            { role: "user", content: "Help me with {{task}}" },
          ],
        });
        createdPromptIds.push(chatPrompt.id!);

        expect(chatPrompt).toBeDefined();
        expect(chatPrompt.name).toBe(promptName);

        // Retrieve chat prompt
        const retrieved = await client.getChatPrompt({ name: promptName });
        expect(retrieved).not.toBeNull();
        expect(retrieved!.name).toBe(promptName);
      });

      it("should create a prompt with explicit projectName", async () => {
        const promptName = `test-prompt-explicit-project-${testTimestamp}`;
        const explicitProject = `explicit-prompt-project-${testTimestamp}`;

        const prompt = await client.createPrompt({
          name: promptName,
          prompt: "Hello {{name}}!",
          projectName: explicitProject,
        });
        createdPromptIds.push(prompt.id!);

        expect(prompt).toBeDefined();
        expect(prompt.name).toBe(promptName);
      });
    });

    describe("End-to-end: dataset + experiment + prompt with same project", () => {
      it("should create dataset, prompt, and experiment all in the same project", async () => {
        const datasetName = `test-ds-e2e-${testTimestamp}`;
        const promptName = `test-prompt-e2e-${testTimestamp}`;
        const experimentName = `test-exp-e2e-${testTimestamp}`;

        // Create dataset in project
        const dataset = await client.createDataset(datasetName, "E2E test dataset");
        createdDatasetNames.push(datasetName);

        await dataset.insert([
          { input: "What is 2+2?", expectedOutput: "4" },
          { input: "What is 3+3?", expectedOutput: "6" },
        ]);

        // Create prompt in same project
        const prompt = await client.createPrompt({
          name: promptName,
          prompt: "Answer: {{input}}",
        });
        createdPromptIds.push(prompt.id!);

        // Create experiment linked to dataset and prompt
        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName,
          prompts: [prompt],
          experimentConfig: { model: "test-model" },
        });
        createdExperimentIds.push(experiment.id);

        await client.flush();

        // Verify all entities are in the same project
        expect(dataset.projectName).toBe(testProjectName);
        expect(experiment.projectName).toBe(testProjectName);

        // Verify we can retrieve them
        const retrievedDataset = await client.getDataset(datasetName);
        expect(retrievedDataset.id).toBe(dataset.id);

        const retrievedExperiment = await client.getExperimentById(experiment.id);
        expect(retrievedExperiment.id).toBe(experiment.id);

        const retrievedPrompt = await client.getPrompt({ name: promptName });
        expect(retrievedPrompt).not.toBeNull();
        expect(retrievedPrompt!.name).toBe(promptName);
      }, 30000);
    });
  }
);
