/**
 * Integration tests for TestSuite in the TypeScript SDK.
 * These tests verify the full suite lifecycle against a real Opik instance.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik, TestSuite, runTests } from "@/index";
import type { ItemResult } from "@/index";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();
const WAIT_OPTIONS = { timeout: 15000, interval: 1000 };

const echoTask = async (item: Record<string, unknown>) => ({
  input: item.input,
  output: `Echo: ${item.input}`,
});

describe.skipIf(!shouldRunApiTests)("TestSuite Integration", () => {
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
    if (!client) return;

    for (const name of createdDatasetNames) {
      try {
        await client.deleteDataset(name);
      } catch { /* cleanup errors ignored */ }
    }

    await client.flush();
  });

  async function waitForSuiteItems(
    suite: TestSuite,
    expectedCount: number
  ) {
    const items = await searchAndWaitForDone(
      async () => suite.getItems(),
      expectedCount,
      WAIT_OPTIONS.timeout,
      WAIT_OPTIONS.interval
    );

    if (items.length < expectedCount) {
      throw new Error(
        `Timed out waiting for ${expectedCount} suite items, got ${items.length}`
      );
    }
  }

  describe("Suite CRUD Lifecycle", () => {
    it(
      "should create suite, add items, and retrieve them with evaluators preserved",
      async () => {
        const suiteName = `test-suite-crud-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        await suite.insert([{ data: { input: "Q1", expected: "A1" } }]);
        await suite.insert([{ data: { input: "Q2", expected: "A2" } }]);

        await waitForSuiteItems(suite, 2);

        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is helpful");

        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 2, passThreshold: 1 });

        const items = await suite.getItems();
        expect(items).toHaveLength(2);

        for (const item of items) {
          expect(item.id).toBeDefined();
          expect(item.data).toBeDefined();
          expect(item.executionPolicy).toEqual({
            runsPerItem: 2,
            passThreshold: 1,
          });
        }
      },
      60000
    );
  });

  describe("Assertions Shorthand", () => {
    it(
      "should create suite with assertions shorthand and run evaluation",
      async () => {
        const suiteName = `test-suite-assertions-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is helpful");

        await suite.insert([{ data: { input: "Hello", expected: "Hi" } }]);
        await waitForSuiteItems(suite, 1);

        const result = await runTests({ testSuite: suite, task: echoTask });

        expect(result.experimentId).toBeDefined();
        expect(result.itemsTotal).toBe(1);
      },
      60000
    );

    it(
      "should add item with assertions shorthand",
      async () => {
        const suiteName = `test-suite-item-assertions-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "Explain gravity", expected: "A force" },
            assertions: ["Response is concise"],
          },
        ]);

        await waitForSuiteItems(suite, 1);

        const items = await suite.getItems();
        expect(items).toHaveLength(1);
        expect(items[0].assertions).toHaveLength(1);
        expect(items[0].assertions[0]).toBe("Response is concise");
      },
      60000
    );
  });

  describe("Get and GetOrCreate", () => {
    it(
      "should get existing suite by name",
      async () => {
        const suiteName = `test-suite-get-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const created = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
        });

        expect(created.projectName).toBeDefined();

        const fetched = await TestSuite.get(client, suiteName);

        expect(fetched.id).toBe(created.id);
        expect(fetched.name).toBe(suiteName);
        expect(fetched.projectName).toBe(created.projectName);
      },
      60000
    );

    it(
      "should create suite via getOrCreate when it doesn't exist",
      async () => {
        const suiteName = `test-suite-getorcreate-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite1 = await TestSuite.getOrCreate(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
        });

        expect(suite1.name).toBe(suiteName);

        const suite2 = await TestSuite.getOrCreate(client, {
          name: suiteName,
        });

        expect(suite2.id).toBe(suite1.id);
      },
      60000
    );
  });

  describe("getOrCreate does not modify existing suite", () => {
    it(
      "should return existing suite as-is when called with different assertions and policy",
      async () => {
        const suiteName = `test-suite-getorcreate-nomod-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        // 1. Create a suite with known assertions/policy and add an item so a version exists
        const original = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });
        await original.insert([{ data: { input: "seed item" } }]);
        await waitForSuiteItems(original, 1);

        // Record version ID before getOrCreate
        const datasetBefore = await client.getDataset(suiteName);
        const versionBefore = await datasetBefore.getVersionInfo();
        expect(versionBefore).toBeDefined();
        const versionIdBefore = versionBefore!.id;

        // 2. Call getOrCreate with DIFFERENT assertions and policy
        const fetched = await TestSuite.getOrCreate(client, {
          name: suiteName,
          globalAssertions: ["Completely different assertion"],
          globalExecutionPolicy: { runsPerItem: 5, passThreshold: 4 },
          tags: ["should-be-ignored"],
        });

        // 3. Should return the same suite
        expect(fetched.id).toBe(original.id);

        // 4. Assertions should NOT have been overwritten
        const assertions = await fetched.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is helpful");

        // 5. Execution policy should NOT have been overwritten
        const policy = await fetched.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 2, passThreshold: 1 });

        // 6. No new version should have been created
        const versionAfter = await datasetBefore.getVersionInfo();
        expect(versionAfter!.id).toBe(versionIdBefore);
      },
      60000
    );
  });

  describe("Update skips when values unchanged", () => {
    it(
      "should not create a new version when update is called with identical values",
      async () => {
        const suiteName = `test-suite-update-noop-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        await suite.insert([{ data: { input: "seed item" } }]);
        await waitForSuiteItems(suite, 1);

        // Record version ID before the no-op update
        const datasetRef = await client.getDataset(suiteName);
        const versionBefore = await datasetRef.getVersionInfo();
        expect(versionBefore).toBeDefined();
        const versionIdBefore = versionBefore!.id;

        // Call update with the exact same values
        await suite.update({
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        // No new version should exist
        const versionAfter = await datasetRef.getVersionInfo();
        expect(versionAfter!.id).toBe(versionIdBefore);

        // Values should remain unchanged
        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toEqual(["Response is helpful"]);
        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 2, passThreshold: 1 });
      },
      60000
    );

    it(
      "should create a new version when update changes assertions",
      async () => {
        const suiteName = `test-suite-update-diff-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([{ data: { input: "seed item" } }]);
        await waitForSuiteItems(suite, 1);

        const datasetRef = await client.getDataset(suiteName);
        const versionBefore = await datasetRef.getVersionInfo();
        expect(versionBefore).toBeDefined();
        const versionIdBefore = versionBefore!.id;

        // Update with different assertions
        await suite.update({
          globalAssertions: ["Response is concise", "Response is accurate"],
          globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
        });

        // A new version should have been created (different version ID)
        const versionAfter = await datasetRef.getVersionInfo();
        expect(versionAfter).toBeDefined();
        expect(versionAfter!.id).not.toBe(versionIdBefore);

        // Verify new values are persisted
        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(2);
        expect(assertions).toContain("Response is concise");
        expect(assertions).toContain("Response is accurate");

        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 3, passThreshold: 2 });
      },
      60000
    );
  });

  describe("Partial update preserves unchanged fields", () => {
    it(
      "should retain existing assertions when only executionPolicy is updated",
      async () => {
        const suiteName = `test-suite-partial-policy-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([{ data: { input: "seed item" } }]);
        await waitForSuiteItems(suite, 1);

        // Update only executionPolicy, omit assertions
        await suite.update({
          globalExecutionPolicy: { runsPerItem: 5, passThreshold: 3 },
        });

        // Assertions should be preserved
        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is helpful");

        // Policy should be updated
        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 5, passThreshold: 3 });
      },
      60000
    );

    it(
      "should retain existing executionPolicy when only assertions are updated",
      async () => {
        const suiteName = `test-suite-partial-assertions-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
        });

        await suite.insert([{ data: { input: "seed item" } }]);
        await waitForSuiteItems(suite, 1);

        // Update only assertions, omit executionPolicy
        await suite.update({
          globalAssertions: ["Response is accurate"],
        });

        // Assertions should be updated
        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is accurate");

        // Policy should be preserved
        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 3, passThreshold: 2 });
      },
      60000
    );
  });

  describe("Full Evaluation Run", () => {
    it(
      "should run suite evaluation end-to-end and return correct result structure",
      async () => {
        const suiteName = `test-suite-run-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const projectName = `test-project-${Date.now()}`;

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
          projectName,
        });

        await suite.insert([
          { data: { input: "What is 2+2?", expected: "4" } },
        ]);
        await suite.insert([
          {
            data: {
              input: "What is the capital of France?",
              expected: "Paris",
            },
          },
        ]);

        await waitForSuiteItems(suite, 2);

        const result = await runTests({ testSuite: suite, task: echoTask });

        expect(result.experimentId).toBeDefined();
        expect(result.itemsTotal).toBe(2);
        expect(result.itemResults.size).toBe(2);

        for (const [, itemResult] of result.itemResults) {
          expect(itemResult.datasetItemId).toBeDefined();
          expect(typeof itemResult.passed).toBe("boolean");
          expect(typeof itemResult.runsPassed).toBe("number");
          expect(itemResult.runsTotal).toBe(1);
          expect(itemResult.passThreshold).toBe(1);
          expect(itemResult.testResults).toBeDefined();
          expect(itemResult.testResults.length).toBeGreaterThanOrEqual(1);
        }

        expect(typeof result.allItemsPassed).toBe("boolean");
        expect(result.itemsPassed).toBeGreaterThanOrEqual(0);
        expect(result.itemsPassed).toBeLessThanOrEqual(2);
        expect(result.passRate).toBeGreaterThanOrEqual(0.0);
        expect(result.passRate).toBeLessThanOrEqual(1.0);
      },
      60000
    );
  });

  describe("Multiple Runs Per Item", () => {
    it(
      "should execute multiple runs per item with correct trial counts",
      async () => {
        const suiteName = `test-suite-multi-run-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        await suite.insert([{ data: { input: "Hello world", expected: "Hi" } }]);

        await waitForSuiteItems(suite, 1);

        const result = await runTests({ testSuite: suite, task: echoTask });

        expect(result.itemResults.size).toBe(1);

        const itemResult: ItemResult = result.itemResults.values().next().value!;
        expect(itemResult.runsTotal).toBe(2);
        expect(itemResult.testResults).toHaveLength(2);
      },
      60000
    );
  });

  describe("Suite Update", () => {
    it(
      "should update suite evaluators and execution policy",
      async () => {
        const suiteName = `test-suite-update-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.update({
          globalAssertions: ["Response is concise"],
          globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
        });

        const assertions = await suite.getGlobalAssertions();
        expect(assertions).toHaveLength(1);
        expect(assertions[0]).toBe("Response is concise");

        const policy = await suite.getGlobalExecutionPolicy();
        expect(policy).toEqual({ runsPerItem: 3, passThreshold: 2 });
      },
      60000
    );
  });

  describe("Item-Level Evaluators", () => {
    it(
      "should merge item-level evaluators with suite-level evaluators",
      async () => {
        const suiteName = `test-suite-item-eval-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "Explain gravity", expected: "A force of attraction" },
            assertions: ["Response is concise"],
          },
        ]);

        await waitForSuiteItems(suite, 1);

        const result = await runTests({ testSuite: suite, task: echoTask });

        expect(result.itemResults.size).toBe(1);

        const itemResult: ItemResult = result.itemResults.values().next().value!;
        expect(itemResult.testResults.length).toBeGreaterThanOrEqual(1);

        const allScoreNames = itemResult.testResults.flatMap((tr) =>
          tr.scoreResults.map((sr) => sr.name)
        );

        expect(
          allScoreNames.some((n) => n.includes("Response is helpful"))
        ).toBe(true);
        expect(
          allScoreNames.some((n) => n.includes("Response is concise"))
        ).toBe(true);
      },
      60000
    );
  });

  describe("Delete Items", () => {
    it(
      "should delete items from suite",
      async () => {
        const suiteName = `test-suite-delete-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
        });

        await suite.insert([{ data: { input: "Item 1" } }]);
        await suite.insert([{ data: { input: "Item 2" } }]);

        await waitForSuiteItems(suite, 2);

        const items = await suite.getItems();
        expect(items).toHaveLength(2);

        // Find items by content since API order is non-deterministic
        const item1 = items.find((i) => i.data.input === "Item 1")!;
        expect(item1).toBeDefined();

        await suite.delete([item1.id]);

        // Wait for deletion to propagate
        await searchAndWaitForDone(
          async () => {
            const remaining = await suite.getItems();
            return remaining.length === 1 ? remaining : [];
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        const remaining = await suite.getItems();
        expect(remaining).toHaveLength(1);
        // IDs change across versions, so verify by content
        expect(remaining[0].data.input).toBe("Item 2");
      },
      60000
    );
  });

  describe("Item-Level Updates", () => {
    it(
      "should update item assertions and verify they are persisted",
      async () => {
        const suiteName = `test-suite-update-assertions-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "Explain gravity", expected: "A force" },
            assertions: ["Response is accurate"],
          },
        ]);

        await waitForSuiteItems(suite, 1);

        const itemsBefore = await suite.getItems();
        expect(itemsBefore).toHaveLength(1);
        expect(itemsBefore[0].assertions).toContain("Response is accurate");

        // Update the item's assertions
        await suite.updateItemAssertions(itemsBefore[0].id, [
          "Response is concise",
          "Response is clear",
        ]);

        // Wait for the update to propagate
        const updatedItems = await searchAndWaitForDone(
          async () => {
            const items = await suite.getItems();
            const item = items.find((i) => i.data.input === "Explain gravity");
            if (item && item.assertions.includes("Response is concise")) {
              return items;
            }
            return [];
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        const updatedItem = updatedItems.find(
          (i) => i.data.input === "Explain gravity"
        )!;
        expect(updatedItem).toBeDefined();
        expect(updatedItem.assertions).toHaveLength(2);
        expect(updatedItem.assertions).toContain("Response is concise");
        expect(updatedItem.assertions).toContain("Response is clear");
        expect(updatedItem.assertions).not.toContain("Response is accurate");
      },
      60000
    );

    it(
      "should update item execution policy and verify it is persisted",
      async () => {
        const suiteName = `test-suite-update-policy-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "What is AI?", expected: "Artificial Intelligence" },
            executionPolicy: { runsPerItem: 2, passThreshold: 1 },
          },
        ]);

        await waitForSuiteItems(suite, 1);

        const itemsBefore = await suite.getItems();
        expect(itemsBefore).toHaveLength(1);
        expect(itemsBefore[0].executionPolicy).toEqual({
          runsPerItem: 2,
          passThreshold: 1,
        });

        // Update the item's execution policy
        await suite.updateItemExecutionPolicy(itemsBefore[0].id, {
          runsPerItem: 5,
          passThreshold: 3,
        });

        // Wait for the update to propagate
        const updatedItems = await searchAndWaitForDone(
          async () => {
            const items = await suite.getItems();
            const item = items.find((i) => i.data.input === "What is AI?");
            if (item && item.executionPolicy.runsPerItem === 5) {
              return items;
            }
            return [];
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        const updatedItem = updatedItems.find(
          (i) => i.data.input === "What is AI?"
        )!;
        expect(updatedItem).toBeDefined();
        expect(updatedItem.executionPolicy).toEqual({
          runsPerItem: 5,
          passThreshold: 3,
        });
      },
      60000
    );

    it(
      "should clear item assertions by passing an empty array",
      async () => {
        const suiteName = `test-suite-clear-assertions-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "Test input", expected: "Test output" },
            assertions: ["Response is correct"],
          },
        ]);

        await waitForSuiteItems(suite, 1);

        const itemsBefore = await suite.getItems();
        expect(itemsBefore[0].assertions).toHaveLength(1);

        // Clear assertions by passing empty array
        await suite.updateItemAssertions(itemsBefore[0].id, []);

        // Wait for the update to propagate
        const updatedItems = await searchAndWaitForDone(
          async () => {
            const items = await suite.getItems();
            const item = items.find((i) => i.data.input === "Test input");
            if (item && item.assertions.length === 0) {
              return items;
            }
            return [];
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        const updatedItem = updatedItems.find(
          (i) => i.data.input === "Test input"
        )!;
        expect(updatedItem).toBeDefined();
        expect(updatedItem.assertions).toHaveLength(0);
      },
      60000
    );

    it(
      "should update both assertions and execution policy in a single updateItem call",
      async () => {
        const suiteName = `test-suite-update-item-combined-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite = await TestSuite.create(client, {
          name: suiteName,
          globalAssertions: ["Response is helpful"],
          globalExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.insert([
          {
            data: { input: "Describe the sun", expected: "A star" },
            assertions: ["Response is factual"],
            executionPolicy: { runsPerItem: 2, passThreshold: 1 },
          },
        ]
        );

        await waitForSuiteItems(suite, 1);

        const itemsBefore = await suite.getItems();
        expect(itemsBefore).toHaveLength(1);
        expect(itemsBefore[0].assertions).toContain("Response is factual");
        expect(itemsBefore[0].executionPolicy).toEqual({
          runsPerItem: 2,
          passThreshold: 1,
        });

        // Update both assertions and execution policy in one call
        await suite.updateItem(itemsBefore[0].id, {
          assertions: ["Response is brief"],
          executionPolicy: { runsPerItem: 4, passThreshold: 2 },
        });

        // Wait for the update to propagate
        const updatedItems = await searchAndWaitForDone(
          async () => {
            const items = await suite.getItems();
            const item = items.find(
              (i) => i.data.input === "Describe the sun"
            );
            if (
              item &&
              item.assertions.includes("Response is brief") &&
              item.executionPolicy.runsPerItem === 4
            ) {
              return items;
            }
            return [];
          },
          1,
          WAIT_OPTIONS.timeout,
          WAIT_OPTIONS.interval
        );

        const updatedItem = updatedItems.find(
          (i) => i.data.input === "Describe the sun"
        )!;
        expect(updatedItem).toBeDefined();
        expect(updatedItem.assertions).toHaveLength(1);
        expect(updatedItem.assertions).toContain("Response is brief");
        expect(updatedItem.assertions).not.toContain("Response is factual");
        expect(updatedItem.executionPolicy).toEqual({
          runsPerItem: 4,
          passThreshold: 2,
        });
      },
      60000
    );
  });
});
