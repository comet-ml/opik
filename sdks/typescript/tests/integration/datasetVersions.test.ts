/**
 * Integration tests for Dataset Versions in the TypeScript SDK.
 * These tests verify version-related functionality against a real Opik instance.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik, evaluate, ExactMatch } from "@/index";
import { Dataset } from "@/dataset/Dataset";
import { DatasetVersion } from "@/dataset/DatasetVersion";
import { EvaluationTask } from "@/evaluation/types";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();
const WAIT_OPTIONS = { timeout: 15000, interval: 1000 };

async function waitForItems(
  client: Opik,
  datasetName: string,
  expectedCount: number
): Promise<void> {
  await searchAndWaitForDone(
    async () => {
      const dataset = await client.getDataset(datasetName);
      return dataset.getItems();
    },
    expectedCount,
    WAIT_OPTIONS.timeout,
    WAIT_OPTIONS.interval
  );
}

describe.skipIf(!shouldRunApiTests)("Dataset Versions Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());
    if (shouldRunApiTests) {
      client = new Opik();
    }
  });

  afterAll(async () => {
    if (!client) return;

    for (const name of createdDatasetNames) {
      try {
        await client.deleteDataset(name);
        await client.flush();
      } catch (error) {
        console.warn(`Failed to cleanup dataset "${name}":`, error);
      }
    }
  });

  it("should create dataset, insert items, and get version info", async () => {
    const testDatasetName = `test-versions-${Date.now()}`;
    createdDatasetNames.push(testDatasetName);

    const dataset = await client.createDataset(
      testDatasetName,
      "Test dataset for version integration tests"
    );
    await client.flush();

    expect(dataset).toBeInstanceOf(Dataset);
    expect(dataset.name).toBe(testDatasetName);

    await dataset.insert([
      { input: "What is 2+2?", expected_output: "4" },
      { input: "What is 3+3?", expected_output: "6" },
    ]);

    await waitForItems(client, testDatasetName, 2);

    const freshDataset = await client.getDataset(testDatasetName);
    const versionInfo = await freshDataset.getVersionInfo();

    expect(versionInfo).toBeDefined();
    expect(versionInfo?.versionName).toBeDefined();
    expect(versionInfo?.itemsTotal).toBeGreaterThanOrEqual(2);

    const versionName = await freshDataset.getCurrentVersionName();
    expect(versionName).toBeDefined();
  });

  it("should get version view by name and retrieve items", async () => {
    const testDatasetName = `test-version-view-${Date.now()}`;
    createdDatasetNames.push(testDatasetName);

    const dataset = await client.createDataset(
      testDatasetName,
      "Test dataset for version view"
    );
    await client.flush();

    await dataset.insert([
      { input: "Question 1", expected_output: "Answer 1" },
      { input: "Question 2", expected_output: "Answer 2" },
      { input: "Question 3", expected_output: "Answer 3" },
    ]);

    await waitForItems(client, testDatasetName, 3);

    const freshDataset = await client.getDataset(testDatasetName);
    const versionName = await freshDataset.getCurrentVersionName();
    expect(versionName).toBeDefined();

    if (!versionName) return;

    const versionView = await freshDataset.getVersionView(versionName);

    expect(versionView).toBeInstanceOf(DatasetVersion);
    expect(versionView.name).toBe(testDatasetName);
    expect(versionView.versionName).toBe(versionName);

    const items = await versionView.getItems();
    expect(items.length).toBe(3);

    const inputs = items.map((item) => item.input);
    expect(inputs).toContain("Question 1");
    expect(inputs).toContain("Question 2");
    expect(inputs).toContain("Question 3");

    const jsonString = await versionView.toJson();
    const parsed = JSON.parse(jsonString);
    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed.length).toBe(3);
  });

  it("should run evaluation with DatasetVersion", async () => {
    const testDatasetName = `test-eval-version-${Date.now()}`;
    createdDatasetNames.push(testDatasetName);

    const dataset = await client.createDataset(
      testDatasetName,
      "Test dataset for evaluation with version"
    );
    await client.flush();

    await dataset.insert([
      { input: "What is the capital of France?", expected_output: "Paris" },
      { input: "What is the capital of Germany?", expected_output: "Berlin" },
    ]);

    await waitForItems(client, testDatasetName, 2);

    const freshDataset = await client.getDataset(testDatasetName);
    const versionName = await freshDataset.getCurrentVersionName();
    expect(versionName).toBeDefined();

    if (!versionName) return;

    const versionView = await freshDataset.getVersionView(versionName);

    const task: EvaluationTask = async (input) => ({
      output: input.expected_output,
    });

    const result = await evaluate({
      dataset: versionView,
      task,
      experimentName: `test-eval-${Date.now()}`,
      scoringMetrics: [new ExactMatch("exact-match")],
      scoringKeyMapping: { expected: "expected_output" },
      client,
    });

    expect(result).toBeDefined();
    expect(result.experimentId).toBeDefined();
    expect(result.testResults).toHaveLength(2);

    for (const testResult of result.testResults) {
      expect(testResult.scoreResults).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            name: "exact-match",
            value: 1,
          }),
        ])
      );
    }
  });
});
