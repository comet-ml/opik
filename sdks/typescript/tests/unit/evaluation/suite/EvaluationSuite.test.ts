import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetItem } from "@/dataset/DatasetItem";
import { EvaluationSuite } from "@/evaluation/suite/EvaluationSuite";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import {
  validateEvaluators,
  validateExecutionPolicy,
} from "@/evaluation/suite_evaluators/validators";
import { DEFAULT_EXECUTION_POLICY } from "@/evaluation/suite/types";

vi.mock("@/evaluation/suite_evaluators/LLMJudge", () => {
  class MockLLMJudge {
    assertions: string[];
    name: string;
    modelName: string;
    constructor(opts: { assertions: string[]; name?: string }) {
      this.assertions = opts.assertions;
      this.name = opts.name ?? "llm_judge";
      this.modelName = "gpt-5-nano";
    }
    toConfig() {
      return {
        name: this.name,
        schema: this.assertions.map((a: string) => ({ name: a, type: "BOOLEAN" })),
        model: { name: this.modelName },
      };
    }
    static fromConfig = vi.fn();
  }
  return { LLMJudge: MockLLMJudge };
});

vi.mock("@/evaluation/suite_evaluators/validators", async (importOriginal) => {
  const original = await importOriginal<
    typeof import("@/evaluation/suite_evaluators/validators")
  >();
  return {
    ...original,
    validateEvaluators: vi.fn(),
    validateExecutionPolicy: vi.fn(),
  };
});

vi.mock("@/evaluation/suite/evaluateSuite", () => ({
  evaluateSuite: vi.fn().mockResolvedValue({
    experimentId: "exp-1",
    experimentName: "test-exp",
    testResults: [
      {
        testCase: {
          traceId: "trace-1",
          datasetItemId: "item-1",
          scoringInputs: {},
          taskOutput: { output: "hello" },
        },
        scoreResults: [{ name: "quality", value: 1 }],
        resolvedExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
      },
    ],
  }),
}));

describe("EvaluationSuite", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;
  let suite: EvaluationSuite;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    testDataset = new Dataset(
      { id: "suite-ds-id", name: "test-suite", description: "Test suite" },
      opikClient
    );

    suite = new EvaluationSuite(testDataset, opikClient);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("constructor and properties", () => {
    it("should expose name, description, and id from the dataset", () => {
      expect(suite.name).toBe("test-suite");
      expect(suite.description).toBe("Test suite");
      expect(suite.id).toBe("suite-ds-id");
    });
  });

  describe("addItem", () => {
    it("should insert item via dataset with plain data", async () => {
      const insertSpy = vi
        .spyOn(testDataset, "insert")
        .mockResolvedValue(undefined);

      await suite.addItem({ input: "hello", expected: "world" });

      expect(insertSpy).toHaveBeenCalledWith([
        { input: "hello", expected: "world" },
      ]);
      expect(validateEvaluators).not.toHaveBeenCalled();
    });

    it("should include execution policy in item data", async () => {
      const insertSpy = vi
        .spyOn(testDataset, "insert")
        .mockResolvedValue(undefined);

      await suite.addItem(
        { input: "test" },
        { executionPolicy: { runsPerItem: 3, passThreshold: 2 } }
      );

      expect(insertSpy).toHaveBeenCalledWith([
        expect.objectContaining({
          input: "test",
          executionPolicy: { runsPerItem: 3, passThreshold: 2 },
        }),
      ]);
    });

    it("should convert assertions shorthand to evaluator in item data", async () => {
      const insertSpy = vi
        .spyOn(testDataset, "insert")
        .mockResolvedValue(undefined);

      await suite.addItem(
        { input: "test" },
        { assertions: ["is accurate", "is concise"] }
      );

      expect(insertSpy).toHaveBeenCalledWith([
        expect.objectContaining({
          input: "test",
          evaluators: [
            expect.objectContaining({
              name: "llm_judge",
              type: "llm_judge",
            }),
          ],
        }),
      ]);
    });
  });

  describe("addItems", () => {
    it("should batch insert items via dataset", async () => {
      const insertSpy = vi
        .spyOn(testDataset, "insert")
        .mockResolvedValue(undefined);

      await suite.addItems([
        { data: { input: "hello", expected: "world" } },
        { data: { input: "foo" }, assertions: ["is correct"] },
      ]);

      expect(insertSpy).toHaveBeenCalledTimes(1);
      const insertedItems = insertSpy.mock.calls[0][0] as unknown[];
      expect(insertedItems).toHaveLength(2);
      expect(insertedItems[0]).toEqual(
        expect.objectContaining({ input: "hello", expected: "world" })
      );
      expect(insertedItems[1]).toEqual(
        expect.objectContaining({
          input: "foo",
          evaluators: [
            expect.objectContaining({ name: "llm_judge", type: "llm_judge" }),
          ],
        })
      );
    });

    it("should pass executionPolicy per item", async () => {
      const insertSpy = vi
        .spyOn(testDataset, "insert")
        .mockResolvedValue(undefined);

      await suite.addItems([
        {
          data: { input: "test" },
          executionPolicy: { runsPerItem: 3, passThreshold: 2 },
        },
      ]);

      const insertedItems = insertSpy.mock.calls[0][0] as unknown[];
      expect(insertedItems[0]).toEqual(
        expect.objectContaining({
          input: "test",
          executionPolicy: { runsPerItem: 3, passThreshold: 2 },
        })
      );
    });
  });

  describe("run", () => {
    it("should delegate to evaluateSuite and return EvaluationSuiteResult", async () => {
      const { evaluateSuite } = await import(
        "@/evaluation/suite/evaluateSuite"
      );
      vi.mocked(evaluateSuite).mockResolvedValue({
        experimentId: "exp-1",
        experimentName: "test-exp",
        testResults: [
          {
            testCase: {
              traceId: "trace-1",
              datasetItemId: "item-1",
              scoringInputs: {},
              taskOutput: { output: "hello" },
            },
            scoreResults: [{ name: "quality", value: 1 }],
            resolvedExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
          },
        ],
        errors: [],
      });

      const mockTask = vi.fn().mockResolvedValue({ output: "hello" });

      const result = await suite.run(mockTask, {
        experimentName: "test-experiment",
      });

      // Verify the result structure
      expect(result).toEqual(
        expect.objectContaining({
          experimentId: "exp-1",
          experimentName: "test-exp",
          allItemsPassed: true,
          itemsPassed: 1,
          itemsTotal: 1,
          passRate: 1.0,
        })
      );
      expect(result.itemResults).toBeInstanceOf(Map);
      expect(result.itemResults.has("item-1")).toBe(true);
    });

    it("should pass model option to evaluateSuite as evaluatorModel", async () => {
      const { evaluateSuite } = await import(
        "@/evaluation/suite/evaluateSuite"
      );
      vi.mocked(evaluateSuite).mockResolvedValue({
        experimentId: "exp-2",
        experimentName: "test-exp-2",
        testResults: [],
        errors: [],
      });

      const mockTask = vi.fn().mockResolvedValue({ output: "hello" });

      await suite.run(mockTask, {
        experimentName: "test-experiment",
        model: "claude-sonnet-4",
      });

      expect(evaluateSuite).toHaveBeenCalledWith(
        expect.objectContaining({ evaluatorModel: "claude-sonnet-4" })
      );
    });

    it("should not call getVersionInfo separately (evaluateSuite handles everything)", async () => {
      const { evaluateSuite } = await import(
        "@/evaluation/suite/evaluateSuite"
      );
      vi.mocked(evaluateSuite).mockResolvedValue({
        experimentId: "exp-2",
        experimentName: "test-exp-2",
        testResults: [
          {
            testCase: {
              traceId: "trace-2",
              datasetItemId: "item-2",
              scoringInputs: {},
              taskOutput: { output: "world" },
            },
            scoreResults: [{ name: "quality", value: 1 }],
            resolvedExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
          },
        ],
        errors: [],
      });

      const getVersionInfoSpy = vi
        .spyOn(testDataset, "getVersionInfo")
        .mockResolvedValue({
          id: "v1",
          executionPolicy: { runsPerItem: 1, passThreshold: 1 },
        });

      const mockTask = vi.fn().mockResolvedValue({ output: "hello" });

      await suite.run(mockTask, { experimentName: "test-experiment" });

      // getVersionInfo should NOT be called by run() since evaluateSuite handles everything
      expect(getVersionInfoSpy).not.toHaveBeenCalled();
    });
  });

  describe("getAssertions", () => {
    it("should return empty array when versionInfo has no evaluators", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        versionName: "v1",
      });

      const assertions = await suite.getAssertions();

      expect(assertions).toEqual([]);
    });

    it("should return empty array when versionInfo is undefined", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue(undefined);

      const assertions = await suite.getAssertions();

      expect(assertions).toEqual([]);
    });

    it("should deserialize evaluators from versionInfo and return assertion strings", async () => {
      const mockJudge = {
        name: "judge",
        assertions: ["is correct", "is concise"],
      } as unknown as LLMJudge;
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockReturnValue(
        mockJudge
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        evaluators: [
          {
            name: "quality-judge",
            type: "llm_judge",
            config: {
              schema: [
                { name: "is correct", type: "BOOLEAN" },
                { name: "is concise", type: "BOOLEAN" },
              ],
            },
          },
        ],
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });

      const assertions = await suite.getAssertions();

      expect(assertions).toEqual(["is correct", "is concise"]);
    });

    it("should return a string[] (not LLMJudge[])", async () => {
      const mockJudge = {
        name: "judge",
        assertions: ["is helpful"],
      } as unknown as LLMJudge;
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockReturnValue(
        mockJudge
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        evaluators: [
          {
            name: "helpfulness-judge",
            type: "llm_judge",
            config: { schema: [{ name: "is helpful", type: "BOOLEAN" }] },
          },
        ],
      });

      const assertions = await suite.getAssertions();

      expect(Array.isArray(assertions)).toBe(true);
      for (const a of assertions) {
        expect(typeof a).toBe("string");
      }
    });
  });

  describe("getTags", () => {
    it("should delegate to dataset.getTags and return tags", async () => {
      const getTagsSpy = vi
        .spyOn(testDataset, "getTags")
        .mockResolvedValue(["tag-a", "tag-b"]);

      const tags = await suite.getTags();

      expect(getTagsSpy).toHaveBeenCalled();
      expect(tags).toEqual(["tag-a", "tag-b"]);
    });

    it("should return empty array when dataset has no tags", async () => {
      vi.spyOn(testDataset, "getTags").mockResolvedValue([]);

      const tags = await suite.getTags();

      expect(tags).toEqual([]);
    });
  });

  describe("getExecutionPolicy", () => {
    it("should resolve execution policy from versionInfo", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        executionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });

      const policy = await suite.getExecutionPolicy();

      expect(policy).toEqual({ runsPerItem: 5, passThreshold: 3 });
    });

    it("should return default execution policy when versionInfo is undefined", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue(undefined);

      const policy = await suite.getExecutionPolicy();

      expect(policy).toEqual(DEFAULT_EXECUTION_POLICY);
    });

    it("should return default execution policy when executionPolicy is missing", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        versionName: "v1",
      });

      const policy = await suite.getExecutionPolicy();

      expect(policy).toEqual(DEFAULT_EXECUTION_POLICY);
    });
  });

  describe("getItems", () => {
    it("should return items with assertions (string[]) and resolved policy", async () => {
      const mockJudge = {
        name: "item-judge",
        assertions: ["is quality", "is accurate"],
      } as unknown as LLMJudge;
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockReturnValue(
        mockJudge
      );

      const rawItem1 = new DatasetItem({
        id: "item-1",
        input: "hello",
        expected: "world",
        evaluators: [
          {
            name: "item-judge",
            type: "llm_judge" as const,
            config: {
              schema: [
                { name: "is quality", type: "BOOLEAN" },
                { name: "is accurate", type: "BOOLEAN" },
              ],
            },
          },
        ],
        executionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });
      const rawItem2 = new DatasetItem({
        id: "item-2",
        input: "foo",
        expected: "bar",
      });

      vi.spyOn(testDataset, "getRawItems").mockResolvedValue([
        rawItem1,
        rawItem2,
      ]);
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });

      const items = await suite.getItems();

      expect(items).toHaveLength(2);

      // Item 1: has assertions and custom policy
      expect(items[0].id).toBe("item-1");
      expect(items[0].data).toEqual({ input: "hello", expected: "world" });
      expect(items[0].assertions).toEqual(["is quality", "is accurate"]);
      expect(items[0].executionPolicy).toEqual({
        runsPerItem: 5,
        passThreshold: 3,
      });

      // Item 2: no assertions, falls back to suite-level policy
      expect(items[1].id).toBe("item-2");
      expect(items[1].data).toEqual({ input: "foo", expected: "bar" });
      expect(items[1].assertions).toEqual([]);
      expect(items[1].executionPolicy).toEqual({
        runsPerItem: 1,
        passThreshold: 1,
      });
    });

    it("should return empty assertions for items without them", async () => {
      const rawItem = new DatasetItem({
        id: "item-1",
        input: "test",
      });

      vi.spyOn(testDataset, "getRawItems").mockResolvedValue([rawItem]);
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      const items = await suite.getItems();

      expect(items).toHaveLength(1);
      expect(items[0].assertions).toEqual([]);
      expect(items[0].executionPolicy).toEqual({
        runsPerItem: 2,
        passThreshold: 1,
      });
    });

    it("should not take an evaluatorModel param (getItems has no model param)", async () => {
      vi.spyOn(testDataset, "getRawItems").mockResolvedValue([]);
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({ id: "v1" });

      // getItems() should be callable without any arguments
      const items = await suite.getItems();

      expect(items).toEqual([]);
    });
  });

  describe("deleteItems", () => {
    it("should delegate to dataset.delete", async () => {
      const deleteSpy = vi
        .spyOn(testDataset, "delete")
        .mockResolvedValue(undefined);

      await suite.deleteItems(["item-1", "item-2"]);

      expect(deleteSpy).toHaveBeenCalledWith(["item-1", "item-2"]);
    });
  });

  describe("update", () => {
    it("should accept assertions and call applyDatasetItemChanges", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [],
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });

      const applyChangesSpy = vi
        .spyOn(opikClient.api.datasets, "applyDatasetItemChanges")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.update({
        assertions: ["is correct"],
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(applyChangesSpy).toHaveBeenCalledWith("suite-ds-id", {
        override: false,
        body: {
          base_version: "version-1",
          evaluators: [
            expect.objectContaining({
              name: "llm_judge",
              type: "llm_judge",
            }),
          ],
          execution_policy: { runs_per_item: 3, pass_threshold: 2 },
        },
      });
    });

    it("should support partial update with assertions only (no executionPolicy)", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [],
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      const applyChangesSpy = vi
        .spyOn(opikClient.api.datasets, "applyDatasetItemChanges")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) =>
                Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.update({ assertions: ["is correct"] });

      // Should use current executionPolicy from versionInfo as fallback
      expect(applyChangesSpy).toHaveBeenCalledWith("suite-ds-id", {
        override: false,
        body: expect.objectContaining({
          base_version: "version-1",
          evaluators: [
            expect.objectContaining({ name: "llm_judge", type: "llm_judge" }),
          ],
        }),
      });
    });

    it("should support partial update with executionPolicy only (no assertions)", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [],
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });

      const applyChangesSpy = vi
        .spyOn(opikClient.api.datasets, "applyDatasetItemChanges")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) =>
                Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.update({ executionPolicy: { runsPerItem: 5, passThreshold: 3 } });

      expect(applyChangesSpy).toHaveBeenCalledWith("suite-ds-id", {
        override: false,
        body: expect.objectContaining({
          base_version: "version-1",
          execution_policy: { runs_per_item: 5, pass_threshold: 3 },
        }),
      });
    });

    it("should support partial update with tags only (calls updateDataset, not applyDatasetItemChanges)", async () => {
      const updateDatasetSpy = vi
        .spyOn(opikClient.api.datasets, "updateDataset")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) =>
                Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      const applyChangesSpy = vi
        .spyOn(opikClient.api.datasets, "applyDatasetItemChanges")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) =>
                Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.update({ tags: ["ci", "nightly"] });

      expect(updateDatasetSpy).toHaveBeenCalledWith("suite-ds-id", {
        name: "test-suite",
        tags: ["ci", "nightly"],
      });
      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should throw when none of assertions, executionPolicy, or tags are provided", async () => {
      await expect(suite.update({})).rejects.toThrow(
        "At least one of 'assertions', 'executionPolicy', or 'tags' must be provided."
      );
    });
  });

  describe("input validation", () => {
    it("should throw Error when creating suite with empty name", async () => {
      await expect(
        EvaluationSuite.create(opikClient, { name: "" })
      ).rejects.toThrow("Evaluation suite name must be a non-empty string");
    });

    it("should throw Error when creating suite with whitespace-only name", async () => {
      await expect(
        EvaluationSuite.create(opikClient, { name: "   " })
      ).rejects.toThrow("Evaluation suite name must be a non-empty string");
    });

    it("should throw Error in getOrCreate with empty name", async () => {
      await expect(
        EvaluationSuite.getOrCreate(opikClient, { name: "" })
      ).rejects.toThrow("Evaluation suite name must be a non-empty string");
    });

    it("should call validateExecutionPolicy when executionPolicy is provided in create", async () => {
      vi.spyOn(opikClient.api.datasets, "createDataset").mockImplementation(
        () =>
          ({
            then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
            [Symbol.toStringTag]: "HttpResponsePromise",
          }) as never
      );
      vi.spyOn(
        opikClient.api.datasets,
        "applyDatasetItemChanges"
      ).mockImplementation(
        () =>
          ({
            then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
            [Symbol.toStringTag]: "HttpResponsePromise",
          }) as never
      );

      await EvaluationSuite.create(opikClient, {
        name: "valid-suite",
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 3, passThreshold: 2 },
        "suite creation"
      );
    });

    it("should call validateExecutionPolicy when executionPolicy is provided in addItem", async () => {
      vi.spyOn(testDataset, "insert").mockResolvedValue(undefined);

      await suite.addItem(
        { input: "test" },
        { executionPolicy: { runsPerItem: 2, passThreshold: 1 } }
      );

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 2, passThreshold: 1 },
        "item-level execution policy"
      );
    });

    it("should call validateExecutionPolicy in update", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        evaluators: [],
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });
      vi.spyOn(
        opikClient.api.datasets,
        "applyDatasetItemChanges"
      ).mockImplementation(
        () =>
          ({
            then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
            [Symbol.toStringTag]: "HttpResponsePromise",
          }) as never
      );

      await suite.update({
        assertions: ["is correct"],
        executionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 5, passThreshold: 3 },
        "suite update"
      );
    });
  });
});
