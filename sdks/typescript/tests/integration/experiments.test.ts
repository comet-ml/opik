import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";
import { ExperimentItemReferences } from "@/experiment/ExperimentItem";
import { searchAndWaitForDone } from "@/utils/searchHelpers";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Experiment Management Integration Tests", () => {
  let client: Opik;
  const createdExperimentIds: string[] = [];
  const createdDatasetNames: string[] = [];
  const testTimestamp = Date.now();

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterAll(async () => {
    if (!client) {
      return;
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
  }, 60000);

  it("should create an experiment", async () => {
    const datasetName = `test-dataset-create-${testTimestamp}`;
    const experimentName = `test-experiment-create-${testTimestamp}`;

    // Create dataset first
    await client.createDataset(datasetName);
    createdDatasetNames.push(datasetName);

    // Create experiment
    const experiment = await client.createExperiment({
      name: experimentName,
      datasetName: datasetName,
      experimentConfig: { model: "gpt-4", temperature: 0.7 },
    });

    createdExperimentIds.push(experiment.id);

    // Verify experiment properties
    expect(experiment).toBeDefined();
    expect(experiment.id).toBeDefined();
    expect(experiment.name).toBe(experimentName);
    expect(experiment.datasetName).toBe(datasetName);
  });

  it("should read an experiment", async () => {
    const datasetName = `test-dataset-read-${testTimestamp}`;
    const experimentName = `test-experiment-read-${testTimestamp}`;

    // Create dataset and experiment
    await client.createDataset(datasetName);
    createdDatasetNames.push(datasetName);

    const experiment = await client.createExperiment({
      name: experimentName,
      datasetName: datasetName,
    });

    createdExperimentIds.push(experiment.id);

    // Flush and wait for experiment to be available
    await client.flush();

    // Wait for experiment to be available using searchAndWaitForDone
    await searchAndWaitForDone(
      async () => {
        try {
          const exp = await client.getExperiment(experimentName);
          return exp ? [exp] : [];
        } catch {
          return [];
        }
      },
      1, // Wait for at least 1 experiment
      5000, // 5 second timeout
      500 // Check every 500ms
    );

    // Read by name
    const experimentByName = await client.getExperiment(experimentName);
    expect(experimentByName).toBeDefined();
    expect(experimentByName.id).toBe(experiment.id);
    expect(experimentByName.name).toBe(experimentName);

    // Read by ID
    const experimentById = await client.getExperimentById(experiment.id);
    expect(experimentById).toBeDefined();
    expect(experimentById.id).toBe(experiment.id);
    expect(experimentById.name).toBe(experimentName);
  });

  it("should update an experiment", async () => {
    const datasetName = `test-dataset-update-${testTimestamp}`;
    const originalName = `test-experiment-update-${testTimestamp}`;
    const updatedName = `test-experiment-updated-${testTimestamp}`;

    // Create dataset and experiment
    await client.createDataset(datasetName);
    createdDatasetNames.push(datasetName);

    const experiment = await client.createExperiment({
      name: originalName,
      datasetName: datasetName,
      experimentConfig: { version: 1 },
    });
    createdExperimentIds.push(experiment.id);

    // Flush and wait for experiment to be available
    await client.flush();

    // Wait for experiment to be available
    await searchAndWaitForDone(
      async () => {
        try {
          const exp = await client.getExperimentById(experiment.id);
          return exp ? [exp] : [];
        } catch {
          return [];
        }
      },
      1,
      5000,
      500
    );

    // Update experiment
    await client.updateExperiment(experiment.id, {
      name: updatedName,
      experimentConfig: { version: 2, updated: true },
    });

    // Wait for update to propagate
    await searchAndWaitForDone(
      async () => {
        try {
          const exp = await client.getExperimentById(experiment.id);
          return exp && exp.name === updatedName ? [exp] : [];
        } catch {
          return [];
        }
      },
      1,
      5000,
      500
    );

    // Verify update
    const updatedExperiment = await client.getExperimentById(experiment.id);
    expect(updatedExperiment.name).toBe(updatedName);
  });

  it("should delete an experiment", async () => {
    const datasetName = `test-dataset-delete-${testTimestamp}`;
    const experimentName = `test-experiment-delete-${testTimestamp}`;

    // Create dataset and experiment
    await client.createDataset(datasetName);
    createdDatasetNames.push(datasetName);

    const experiment = await client.createExperiment({
      name: experimentName,
      datasetName: datasetName,
    });

    // Flush and wait for experiment to be available
    await client.flush();

    // Wait for experiment to be available
    await searchAndWaitForDone(
      async () => {
        try {
          const exp = await client.getExperimentById(experiment.id);
          return exp ? [exp] : [];
        } catch {
          return [];
        }
      },
      1,
      5000,
      500
    );

    // Verify experiment exists
    const retrievedBefore = await client.getExperimentById(experiment.id);
    expect(retrievedBefore).toBeDefined();

    // Delete experiment
    await client.deleteExperiment(experiment.id);

    // Wait for deletion to propagate
    await searchAndWaitForDone(
      async () => {
        try {
          await client.getExperimentById(experiment.id);
          return [1]; // Still exists
        } catch {
          return []; // Deleted successfully
        }
      },
      0, // Wait for 0 results (experiment should not exist)
      5000,
      500
    );

    // Verify experiment is deleted
    await expect(client.getExperimentById(experiment.id)).rejects.toThrow();
  });

  it(
    "should manage experiment items",
    async () => {
      const datasetName = `test-dataset-items-${testTimestamp}`;
      const experimentName = `test-experiment-items-${testTimestamp}`;

      // Create dataset with items
      const dataset = await client.createDataset(datasetName);
      createdDatasetNames.push(datasetName);

      await dataset.insert([
        { input: { question: "What is 2+2?" }, expectedOutput: { answer: "4" } },
        { input: { question: "What is 3+3?" }, expectedOutput: { answer: "6" } },
      ]);

      await client.flush();

      // Wait for dataset items to be available
      const datasetItems = await searchAndWaitForDone(
        async () => await dataset.getItems(),
        2, // Wait for at least 2 items
        5000,
        500
      );
      const datasetItemIds = datasetItems.map((item) => item.id);

      // Create experiment
      const experiment = await client.createExperiment({
        name: experimentName,
        datasetName: datasetName,
      });
      createdExperimentIds.push(experiment.id);

      await client.flush();

      // Wait for experiment to be available
      await searchAndWaitForDone(
        async () => {
          try {
            const exp = await client.getExperimentById(experiment.id);
            return exp ? [exp] : [];
          } catch {
            return [];
          }
        },
        1,
        5000,
        500
      );

      // Create traces
      const trace1 = client.trace({
        name: "test-trace-1",
        input: { question: "What is 2+2?" },
        output: { answer: "4" },
      });
      trace1.end();

      const trace2 = client.trace({
        name: "test-trace-2",
        input: { question: "What is 3+3?" },
        output: { answer: "6" },
      });
      trace2.end();

      await client.flush();

      // Insert experiment items
      const experimentItemReferences: ExperimentItemReferences[] = [
        new ExperimentItemReferences({
          datasetItemId: datasetItemIds[0],
          traceId: trace1.data.id,
        }),
        new ExperimentItemReferences({
          datasetItemId: datasetItemIds[1],
          traceId: trace2.data.id,
        }),
      ];

      await experiment.insert(experimentItemReferences);

      // Wait for experiment items to be available
      const items = await searchAndWaitForDone(
        async () => await experiment.getItems(),
        2, // Wait for at least 2 items
        10000, // 10 second timeout
        500
      );

      // Verify experiment items
      expect(items).toBeDefined();
      expect(items.length).toBeGreaterThanOrEqual(2);
      expect(items.some((item) => item.traceId === trace1.data.id)).toBe(true);
      expect(items.some((item) => item.traceId === trace2.data.id)).toBe(true);
    },
    15000
  ); // 15 second timeout for this test
});
