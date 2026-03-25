/**
 * COMPATIBILITY V1 TEST — DO NOT MODIFY
 *
 * This is a frozen copy of the original experiments integration test from before
 * the projectName parameter was added to the SDK API. It ensures backward
 * compatibility: users who never specify projectName should experience zero
 * regressions.
 *
 * If you need to add new experiment tests, add them to the parent directory
 * (tests/integration/experiments.test.ts), not here.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { ExperimentItemReferences } from "@/experiment/ExperimentItem";
import { searchAndWaitForDone } from "@/utils/searchHelpers";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "Compatibility V1: Experiment Management Integration Tests",
  () => {
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

      for (const experimentId of createdExperimentIds) {
        try {
          await client.deleteExperiment(experimentId);
        } catch {
          // Ignore cleanup errors
        }
      }

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
      const datasetName = `compat-v1-ds-create-${testTimestamp}`;
      const experimentName = `compat-v1-exp-create-${testTimestamp}`;

      await client.createDataset(datasetName);
      createdDatasetNames.push(datasetName);

      const experiment = await client.createExperiment({
        name: experimentName,
        datasetName: datasetName,
        experimentConfig: { model: "gpt-4", temperature: 0.7 },
      });

      createdExperimentIds.push(experiment.id);

      expect(experiment).toBeDefined();
      expect(experiment.id).toBeDefined();
      expect(experiment.name).toBe(experimentName);
      expect(experiment.datasetName).toBe(datasetName);
    });

    it("should read an experiment", async () => {
      const datasetName = `compat-v1-ds-read-${testTimestamp}`;
      const experimentName = `compat-v1-exp-read-${testTimestamp}`;

      await client.createDataset(datasetName);
      createdDatasetNames.push(datasetName);

      const experiment = await client.createExperiment({
        name: experimentName,
        datasetName: datasetName,
      });

      createdExperimentIds.push(experiment.id);
      await client.flush();

      await searchAndWaitForDone(
        async () => {
          try {
            const exp = await client.getExperiment(experimentName);
            return exp ? [exp] : [];
          } catch {
            return [];
          }
        },
        1,
        5000,
        500
      );

      const experimentByName = await client.getExperiment(experimentName);
      expect(experimentByName).toBeDefined();
      expect(experimentByName.id).toBe(experiment.id);
      expect(experimentByName.name).toBe(experimentName);

      const experimentById = await client.getExperimentById(experiment.id);
      expect(experimentById).toBeDefined();
      expect(experimentById.id).toBe(experiment.id);
      expect(experimentById.name).toBe(experimentName);
    });

    it("should delete an experiment", async () => {
      const datasetName = `compat-v1-ds-delete-${testTimestamp}`;
      const experimentName = `compat-v1-exp-delete-${testTimestamp}`;

      await client.createDataset(datasetName);
      createdDatasetNames.push(datasetName);

      const experiment = await client.createExperiment({
        name: experimentName,
        datasetName: datasetName,
      });

      await client.flush();

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

      const retrievedBefore = await client.getExperimentById(experiment.id);
      expect(retrievedBefore).toBeDefined();

      await client.deleteExperiment(experiment.id);

      await searchAndWaitForDone(
        async () => {
          try {
            await client.getExperimentById(experiment.id);
            return [];
          } catch {
            return [1];
          }
        },
        1,
        5000,
        500
      );

      await expect(
        client.getExperimentById(experiment.id)
      ).rejects.toThrow();
    });

    it(
      "should manage experiment items",
      async () => {
        const datasetName = `compat-v1-ds-items-${testTimestamp}`;
        const experimentName = `compat-v1-exp-items-${testTimestamp}`;

        const dataset = await client.createDataset(datasetName);
        createdDatasetNames.push(datasetName);

        await dataset.insert([
          {
            input: { question: "What is 2+2?" },
            expectedOutput: { answer: "4" },
          },
          {
            input: { question: "What is 3+3?" },
            expectedOutput: { answer: "6" },
          },
        ]);

        await client.flush();

        const datasetItems = await searchAndWaitForDone(
          async () => await dataset.getItems(),
          2,
          5000,
          500
        );
        const datasetItemIds = datasetItems.map((item) => item.id);

        const experiment = await client.createExperiment({
          name: experimentName,
          datasetName: datasetName,
        });
        createdExperimentIds.push(experiment.id);

        await client.flush();

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

        const items = await searchAndWaitForDone(
          async () => await experiment.getItems(),
          2,
          10000,
          500
        );

        expect(items).toBeDefined();
        expect(items.length).toBeGreaterThanOrEqual(2);
        expect(
          items.some((item) => item.traceId === trace1.data.id)
        ).toBe(true);
        expect(
          items.some((item) => item.traceId === trace2.data.id)
        ).toBe(true);
      },
      15000
    );
  }
);
