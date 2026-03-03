/**
 * Integration tests for EvaluationSuite in the TypeScript SDK.
 * These tests verify the full suite lifecycle against a real Opik instance.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { EvaluationSuite } from "@/evaluation/suite/EvaluationSuite";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();
const WAIT_OPTIONS = { timeout: 15000, interval: 1000 };

function createTestEvaluator(name = "test-judge"): LLMJudge {
  return new LLMJudge({
    name,
    assertions: ["Response is helpful"],
    model: "gpt-5-nano",
  });
}

const echoTask = async (item: Record<string, unknown>) => ({
  output: `Echo: ${item.input}`,
});

describe.skipIf(!shouldRunApiTests)("EvaluationSuite Integration", () => {
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
    suite: EvaluationSuite,
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

        const judge = createTestEvaluator();

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [judge],
          executionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        await suite.addItem({ input: "Q1", expected: "A1" });
        await suite.addItem({ input: "Q2", expected: "A2" });

        await waitForSuiteItems(suite, 2);

        const evaluators = await suite.getEvaluators();
        expect(evaluators).toHaveLength(1);
        expect(evaluators[0].name).toBe("test-judge");

        const policy = await suite.getExecutionPolicy();
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

  describe("Get and GetOrCreate", () => {
    it(
      "should get existing suite by name",
      async () => {
        const suiteName = `test-suite-get-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const created = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [createTestEvaluator()],
        });

        const fetched = await EvaluationSuite.get(client, suiteName);

        expect(fetched.id).toBe(created.id);
        expect(fetched.name).toBe(suiteName);
      },
      60000
    );

    it(
      "should create suite via getOrCreate when it doesn't exist",
      async () => {
        const suiteName = `test-suite-getorcreate-${Date.now()}`;
        createdDatasetNames.push(suiteName);

        const suite1 = await EvaluationSuite.getOrCreate(client, {
          name: suiteName,
          evaluators: [createTestEvaluator()],
        });

        expect(suite1.name).toBe(suiteName);

        const suite2 = await EvaluationSuite.getOrCreate(client, {
          name: suiteName,
        });

        expect(suite2.id).toBe(suite1.id);
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

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [createTestEvaluator()],
          executionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        await suite.addItem({ input: "What is 2+2?", expected: "4" });
        await suite.addItem({
          input: "What is the capital of France?",
          expected: "Paris",
        });

        await waitForSuiteItems(suite, 2);

        const result = await suite.run(echoTask);

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

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [createTestEvaluator()],
          executionPolicy: { runsPerItem: 2, passThreshold: 1 },
        });

        await suite.addItem({ input: "Hello world", expected: "Hi" });

        await waitForSuiteItems(suite, 1);

        const result = await suite.run(echoTask);

        expect(result.itemResults.size).toBe(1);

        const itemResult = result.itemResults.values().next().value!;
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

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [createTestEvaluator("judge-a")],
          executionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        const evaluatorB = new LLMJudge({
          name: "judge-b",
          assertions: ["Response is concise"],
          model: "gpt-5-nano",
        });

        await suite.update({
          evaluators: [evaluatorB],
          executionPolicy: { runsPerItem: 3, passThreshold: 2 },
        });

        const evaluators = await suite.getEvaluators();
        expect(evaluators).toHaveLength(1);
        expect(evaluators[0].name).toBe("judge-b");
        expect(evaluators[0].assertions).toEqual(["Response is concise"]);

        const policy = await suite.getExecutionPolicy();
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

        const suiteJudge = new LLMJudge({
          name: "suite-judge",
          assertions: ["Response is helpful"],
          model: "gpt-5-nano",
        });

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
          evaluators: [suiteJudge],
          executionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

        const itemJudge = new LLMJudge({
          name: "item-judge",
          assertions: ["Response is concise"],
          model: "gpt-5-nano",
        });

        await suite.addItem(
          { input: "Explain gravity", expected: "A force of attraction" },
          { evaluators: [itemJudge] }
        );

        await waitForSuiteItems(suite, 1);

        const result = await suite.run(echoTask);

        expect(result.itemResults.size).toBe(1);

        const itemResult = result.itemResults.values().next().value!;
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

        const suite = await EvaluationSuite.create(client, {
          name: suiteName,
        });

        await suite.addItem({ input: "Item 1" });
        await suite.addItem({ input: "Item 2" });

        await waitForSuiteItems(suite, 2);

        const items = await suite.getItems();
        expect(items).toHaveLength(2);

        // Find items by content since API order is non-deterministic
        const item1 = items.find((i) => i.data.input === "Item 1")!;
        expect(item1).toBeDefined();

        await suite.deleteItems([item1.id]);

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
});
