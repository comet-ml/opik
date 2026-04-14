import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetItem } from "@/dataset/DatasetItem";
import { TestSuite } from "@/evaluation/suite/TestSuite";
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


describe("TestSuite", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;
  let suite: TestSuite;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    testDataset = new Dataset(
      { id: "suite-ds-id", name: "test-suite", description: "Test suite" },
      opikClient
    );

    suite = new TestSuite(testDataset, opikClient);
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

    it("should expose projectName from the dataset", () => {
      const datasetWithProject = new Dataset(
        {
          id: "ds-proj-id",
          name: "project-suite",
          projectName: "my-project",
        },
        opikClient
      );
      const suiteWithProject = new TestSuite(
        datasetWithProject,
        opikClient
      );
      expect(suiteWithProject.projectName).toBe("my-project");
    });

    it("should return undefined when dataset has no projectName", () => {
      expect(suite.projectName).toBeUndefined();
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

  describe("getGlobalAssertions", () => {
    it("should return empty array when versionInfo has no evaluators", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        versionName: "v1",
      });

      const assertions = await suite.getGlobalAssertions();

      expect(assertions).toEqual([]);
    });

    it("should return empty array when versionInfo is undefined", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue(undefined);

      const assertions = await suite.getGlobalAssertions();

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

      const assertions = await suite.getGlobalAssertions();

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

      const assertions = await suite.getGlobalAssertions();

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

  describe("getItemsCount", () => {
    it("should delegate to dataset.getItemsCount and return the count", async () => {
      const getItemsCountSpy = vi
        .spyOn(testDataset, "getItemsCount")
        .mockResolvedValue(42);

      const count = await suite.getItemsCount();

      expect(getItemsCountSpy).toHaveBeenCalled();
      expect(count).toBe(42);
    });

    it("should return undefined when dataset has no items count", async () => {
      vi.spyOn(testDataset, "getItemsCount").mockResolvedValue(undefined);

      const count = await suite.getItemsCount();

      expect(count).toBeUndefined();
    });
  });

  describe("getGlobalExecutionPolicy", () => {
    it("should resolve execution policy from versionInfo", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        executionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });

      const policy = await suite.getGlobalExecutionPolicy();

      expect(policy).toEqual({ runsPerItem: 5, passThreshold: 3 });
    });

    it("should return default execution policy when versionInfo is undefined", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue(undefined);

      const policy = await suite.getGlobalExecutionPolicy();

      expect(policy).toEqual(DEFAULT_EXECUTION_POLICY);
    });

    it("should return default execution policy when executionPolicy is missing", async () => {
      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "v1",
        versionName: "v1",
      });

      const policy = await suite.getGlobalExecutionPolicy();

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

  describe("updateItemAssertions", () => {
    it("should call batchUpdateDatasetItems with serialized evaluators", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItemAssertions("item-123", ["is accurate", "is concise"]);

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-123"],
        update: {
          evaluators: [
            expect.objectContaining({ name: "llm_judge", type: "llm_judge" }),
          ],
        },
      });
    });

    it("should send empty evaluators array when assertions is empty", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItemAssertions("item-123", []);

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-123"],
        update: { evaluators: [] },
      });
    });

    it("should throw when itemId is empty", async () => {
      await expect(
        suite.updateItemAssertions("", ["is accurate"])
      ).rejects.toThrow("itemId must be a non-empty string");
    });

    it("should throw when itemId is whitespace-only", async () => {
      await expect(
        suite.updateItemAssertions("   ", ["is accurate"])
      ).rejects.toThrow("itemId must be a non-empty string");
    });
  });

  describe("updateItemExecutionPolicy", () => {
    it("should call batchUpdateDatasetItems with executionPolicy", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItemExecutionPolicy("item-456", {
        runsPerItem: 5,
        passThreshold: 3,
      });

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-456"],
        update: { executionPolicy: { runsPerItem: 5, passThreshold: 3 } },
      });
    });

    it("should call validateExecutionPolicy before API call", async () => {
      vi.spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItemExecutionPolicy("item-456", {
        runsPerItem: 3,
        passThreshold: 2,
      });

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 3, passThreshold: 2 },
        "item-level execution policy update"
      );
    });

    it("should throw when itemId is empty", async () => {
      await expect(
        suite.updateItemExecutionPolicy("", { runsPerItem: 1, passThreshold: 1 })
      ).rejects.toThrow("itemId must be a non-empty string");
    });

    it("should throw when itemId is whitespace-only", async () => {
      await expect(
        suite.updateItemExecutionPolicy("   ", { runsPerItem: 1, passThreshold: 1 })
      ).rejects.toThrow("itemId must be a non-empty string");
    });

    it("should propagate validation errors from validateExecutionPolicy", async () => {
      vi.mocked(validateExecutionPolicy).mockImplementationOnce(() => {
        throw new RangeError("passThreshold (5) cannot exceed runsPerItem (3)");
      });

      await expect(
        suite.updateItemExecutionPolicy("item-456", {
          runsPerItem: 3,
          passThreshold: 5,
        })
      ).rejects.toThrow("passThreshold (5) cannot exceed runsPerItem (3)");
    });
  });

  describe("updateItem", () => {
    it("should update both assertions and executionPolicy in a single call", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItem("item-789", {
        assertions: ["is accurate"],
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-789"],
        update: {
          evaluators: [
            expect.objectContaining({ name: "llm_judge", type: "llm_judge" }),
          ],
          executionPolicy: { runsPerItem: 3, passThreshold: 2 },
        },
      });
    });

    it("should update assertions only when executionPolicy is omitted", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItem("item-789", {
        assertions: ["is concise"],
      });

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-789"],
        update: {
          evaluators: [
            expect.objectContaining({ name: "llm_judge", type: "llm_judge" }),
          ],
        },
      });
    });

    it("should update executionPolicy only when assertions is omitted", async () => {
      const batchUpdateSpy = vi
        .spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItem("item-789", {
        executionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });

      expect(batchUpdateSpy).toHaveBeenCalledWith({
        ids: ["item-789"],
        update: {
          executionPolicy: { runsPerItem: 5, passThreshold: 3 },
        },
      });
    });

    it("should throw when neither assertions nor executionPolicy is provided", async () => {
      await expect(suite.updateItem("item-789", {})).rejects.toThrow(
        "At least one of 'assertions' or 'executionPolicy' must be provided."
      );
    });

    it("should throw when itemId is empty", async () => {
      await expect(
        suite.updateItem("", { assertions: ["is correct"] })
      ).rejects.toThrow("itemId must be a non-empty string");
    });

    it("should validate executionPolicy when provided", async () => {
      vi.spyOn(opikClient.api.datasets, "batchUpdateDatasetItems")
        .mockImplementation(
          () =>
            ({
              then: (cb: (v: unknown) => unknown) => Promise.resolve(cb(undefined)),
              [Symbol.toStringTag]: "HttpResponsePromise",
            }) as never
        );

      await suite.updateItem("item-789", {
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 3, passThreshold: 2 },
        "item-level execution policy update"
      );
    });
  });

  describe("update", () => {
    it("should accept globalAssertions and call applyDatasetItemChanges", async () => {
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
        globalAssertions: ["is correct"],
        globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
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

    it("should support partial update with globalAssertions only (no globalExecutionPolicy)", async () => {
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

      await suite.update({ globalAssertions: ["is correct"] });

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

    it("should support partial update with globalExecutionPolicy only (no globalAssertions)", async () => {
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

      await suite.update({ globalExecutionPolicy: { runsPerItem: 5, passThreshold: 3 } });

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

    it("should throw when none of globalAssertions, globalExecutionPolicy, or tags are provided", async () => {
      await expect(suite.update({})).rejects.toThrow(
        "At least one of 'globalAssertions', 'globalExecutionPolicy', or 'tags' must be provided."
      );
    });

    it("should skip applyDatasetItemChanges when assertions are identical", async () => {
      const existingConfig = {
        name: "llm_judge",
        schema: [{ name: "is correct", type: "BOOLEAN" }],
        model: { name: "gpt-5-nano" },
      };

      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        (config: Record<string, unknown>) => {
          const schema = (config.schema ?? []) as Array<{ name: string }>;
          return new LLMJudge({
            assertions: schema.map((s) => s.name),
          });
        }
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [
          { name: "llm_judge", type: "llm_judge", config: existingConfig },
        ],
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

      await suite.update({ globalAssertions: ["is correct"] });

      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should skip applyDatasetItemChanges when executionPolicy is identical", async () => {
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        () => new LLMJudge({ assertions: ["existing"] })
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [],
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
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

      await suite.update({ globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 } });

      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should skip applyDatasetItemChanges when both assertions and executionPolicy are identical", async () => {
      const existingConfig = {
        name: "llm_judge",
        schema: [{ name: "is accurate", type: "BOOLEAN" }],
        model: { name: "gpt-5-nano" },
      };

      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        (config: Record<string, unknown>) => {
          const schema = (config.schema ?? []) as Array<{ name: string }>;
          return new LLMJudge({
            assertions: schema.map((s) => s.name),
          });
        }
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [
          { name: "llm_judge", type: "llm_judge", config: existingConfig },
        ],
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
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
        globalAssertions: ["is accurate"],
        globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should call applyDatasetItemChanges when assertions differ", async () => {
      const existingConfig = {
        name: "llm_judge",
        schema: [{ name: "is correct", type: "BOOLEAN" }],
        model: { name: "gpt-5-nano" },
      };

      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        (config: Record<string, unknown>) => {
          const schema = (config.schema ?? []) as Array<{ name: string }>;
          return new LLMJudge({
            assertions: schema.map((s) => s.name),
          });
        }
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [
          { name: "llm_judge", type: "llm_judge", config: existingConfig },
        ],
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

      await suite.update({ globalAssertions: ["is accurate"] });

      expect(applyChangesSpy).toHaveBeenCalled();
    });

    it("should call applyDatasetItemChanges when executionPolicy differs", async () => {
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        () => new LLMJudge({ assertions: ["existing"] })
      );

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

      await suite.update({ globalExecutionPolicy: { runsPerItem: 5, passThreshold: 3 } });

      expect(applyChangesSpy).toHaveBeenCalled();
    });

    it("should merge partial executionPolicy with current values instead of defaults", async () => {
      (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
        () => new LLMJudge({ assertions: ["existing"] })
      );

      vi.spyOn(testDataset, "getVersionInfo").mockResolvedValue({
        id: "version-1",
        versionName: "v1",
        evaluators: [],
        executionPolicy: { runsPerItem: 3, passThreshold: 5 },
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

      // Only provide runsPerItem — passThreshold should inherit from current (5), not default (1)
      await suite.update({ globalExecutionPolicy: { runsPerItem: 7 } });

      expect(applyChangesSpy).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          body: expect.objectContaining({
            execution_policy: { runs_per_item: 7, pass_threshold: 5 },
          }),
        })
      );
    });
  });

  describe("input validation", () => {
    it("should throw Error when creating suite with empty name", async () => {
      await expect(
        TestSuite.create(opikClient, { name: "" })
      ).rejects.toThrow("Test suite name must be a non-empty string");
    });

    it("should throw Error when creating suite with whitespace-only name", async () => {
      await expect(
        TestSuite.create(opikClient, { name: "   " })
      ).rejects.toThrow("Test suite name must be a non-empty string");
    });

    it("should throw Error in getOrCreate with empty name", async () => {
      await expect(
        TestSuite.getOrCreate(opikClient, { name: "" })
      ).rejects.toThrow("Test suite name must be a non-empty string");
    });

    it("should call validateExecutionPolicy when globalExecutionPolicy is provided in create", async () => {
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

      await TestSuite.create(opikClient, {
        name: "valid-suite",
        globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
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
        globalAssertions: ["is correct"],
        globalExecutionPolicy: { runsPerItem: 5, passThreshold: 3 },
      });

      expect(validateExecutionPolicy).toHaveBeenCalledWith(
        { runsPerItem: 5, passThreshold: 3 },
        "suite update"
      );
    });
  });
});
