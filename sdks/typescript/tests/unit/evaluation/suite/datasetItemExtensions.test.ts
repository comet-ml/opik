import { describe, it, expect } from "vitest";
import { DatasetItem } from "@/dataset/DatasetItem";
import { DatasetItemWriteSource } from "@/rest_api/api";
import { EvaluatorItemWrite } from "@/rest_api/api/types/EvaluatorItemWrite";
import { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";

describe("DatasetItem extensions for test suites", () => {
  describe("toApiModel", () => {
    it("should include evaluators field when set on the DatasetItem", () => {
      const evaluators: EvaluatorItemWrite[] = [
        {
          name: "relevance-judge",
          type: "llm_judge",
          config: {
            schema: [{ name: "relevance" }],
            model: { name: "gpt-4o" },
          },
        },
      ];

      const item = new DatasetItem({
        id: "item-1",
        input: "test input",
        evaluators,
      });

      const apiModel = item.toApiModel();

      expect(apiModel.evaluators).toEqual(evaluators);
      expect(apiModel.data).toEqual({ input: "test input" });
    });

    it("should include executionPolicy field when set", () => {
      const executionPolicy: ExecutionPolicyWrite = {
        runsPerItem: 3,
        passThreshold: 2,
      };

      const item = new DatasetItem({
        id: "item-2",
        input: "test input",
        executionPolicy,
      });

      const apiModel = item.toApiModel();

      expect(apiModel.executionPolicy).toEqual(executionPolicy);
      expect(apiModel.data).toEqual({ input: "test input" });
    });

    it("should omit evaluators and executionPolicy when not set (backward compatible)", () => {
      const item = new DatasetItem({
        id: "item-3",
        input: "test input",
        output: "test output",
      });

      const apiModel = item.toApiModel();

      expect(apiModel).not.toHaveProperty("evaluators");
      expect(apiModel).not.toHaveProperty("executionPolicy");
      expect(apiModel.id).toBe("item-3");
      expect(apiModel.data).toEqual({
        input: "test input",
        output: "test output",
      });
      expect(apiModel.source).toBe(DatasetItemWriteSource.Sdk);
    });
  });

  describe("description field collision", () => {
    it("should preserve user data description (empty string) in getContent and toApiModel", () => {
      const item = new DatasetItem({
        id: "desc-1",
        input: "test",
        description: "",
      });

      expect(item.getContent()).toEqual({ input: "test", description: "" });
      expect(item.description).toBe("");
      const api = item.toApiModel();
      expect(api.data).toEqual({ input: "test", description: "" });
    });

    it("should not leak metadata-only description into getContent via fromApiModel", () => {
      const item = DatasetItem.fromApiModel({
        id: "desc-2",
        source: DatasetItemWriteSource.Sdk,
        description: "metadata desc",
        data: { input: "hello" },
      });

      expect(item.description).toBe("metadata desc");
      expect(item.getContent()).toEqual({ input: "hello" });
      expect(item.getContent()).not.toHaveProperty("description");
    });

    it("should let user data description win over metadata description in fromApiModel", () => {
      const item = DatasetItem.fromApiModel({
        id: "desc-3",
        source: DatasetItemWriteSource.Sdk,
        description: "metadata desc",
        data: { input: "x", description: "user desc" },
      });

      expect(item.description).toBe("user desc");
      expect(item.getContent()).toEqual({ input: "x", description: "user desc" });
    });

    it("should round-trip correctly via toApiModel → fromApiModel", () => {
      const original = new DatasetItem({
        id: "desc-4",
        input: "test",
        description: "user val",
      });

      const apiModel = original.toApiModel();
      const restored = DatasetItem.fromApiModel(apiModel);

      expect(restored.description).toBe("user val");
      expect(restored.getContent()).toEqual({ input: "test", description: "user val" });
    });

    it("should produce the same contentHash regardless of metadata description", async () => {
      const itemA = new DatasetItem({ id: "desc-5a", input: "same" });
      const itemB = DatasetItem.fromApiModel({
        id: "desc-5b",
        source: DatasetItemWriteSource.Sdk,
        description: "some metadata",
        data: { input: "same" },
      });

      expect(await itemA.contentHash()).toBe(await itemB.contentHash());
    });
  });

  describe("contentHash", () => {
    it("should produce different hashes when evaluators differ", async () => {
      const itemA = new DatasetItem({
        id: "hash-1a",
        input: "same",
        evaluators: [
          { name: "judge-a", type: "llm_judge", config: { model: "gpt-4o" } },
        ],
      });
      const itemB = new DatasetItem({
        id: "hash-1b",
        input: "same",
        evaluators: [
          { name: "judge-b", type: "code_metric", config: { threshold: 0.5 } },
        ],
      });

      expect(await itemA.contentHash()).not.toBe(await itemB.contentHash());
    });

    it("should produce different hashes when executionPolicy differs", async () => {
      const itemA = new DatasetItem({
        id: "hash-2a",
        input: "same",
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });
      const itemB = new DatasetItem({
        id: "hash-2b",
        input: "same",
        executionPolicy: { runsPerItem: 5, passThreshold: 4 },
      });

      expect(await itemA.contentHash()).not.toBe(await itemB.contentHash());
    });

    it("should produce the same hash for identical evaluators and executionPolicy", async () => {
      const evaluators: EvaluatorItemWrite[] = [
        { name: "judge-1", type: "llm_judge", config: { model: "gpt-4o" } },
      ];
      const executionPolicy: ExecutionPolicyWrite = {
        runsPerItem: 3,
        passThreshold: 2,
      };

      const itemA = new DatasetItem({
        id: "hash-3a",
        input: "same",
        evaluators,
        executionPolicy,
      });
      const itemB = new DatasetItem({
        id: "hash-3b",
        input: "same",
        evaluators,
        executionPolicy,
      });

      expect(await itemA.contentHash()).toBe(await itemB.contentHash());
    });

    it("should produce unchanged hash when no evaluators or executionPolicy are set", async () => {
      const itemA = new DatasetItem({ id: "hash-4a", input: "test" });
      const itemB = new DatasetItem({ id: "hash-4b", input: "test" });

      expect(await itemA.contentHash()).toBe(await itemB.contentHash());
    });

    it("should treat empty evaluators/executionPolicy the same as omitted", async () => {
      const itemOmitted = new DatasetItem({ id: "hash-5a", input: "test" });
      const itemEmpty = new DatasetItem({
        id: "hash-5b",
        input: "test",
        evaluators: [],
        executionPolicy: {} as ExecutionPolicyWrite,
      });

      expect(await itemEmpty.contentHash()).toBe(
        await itemOmitted.contentHash()
      );
    });
  });

  describe("fromApiModel", () => {
    it("should preserve evaluators and executionPolicy", () => {
      const evaluators: EvaluatorItemWrite[] = [
        {
          name: "judge-1",
          type: "llm_judge",
          config: { schema: [{ name: "accuracy" }] },
        },
      ];
      const executionPolicy: ExecutionPolicyWrite = {
        runsPerItem: 5,
        passThreshold: 3,
      };

      const apiModel = {
        id: "item-4",
        source: DatasetItemWriteSource.Sdk,
        data: { input: "hello", output: "world" },
        evaluators,
        executionPolicy,
      };

      const item = DatasetItem.fromApiModel(apiModel);

      expect(item.evaluators).toEqual(evaluators);
      expect(item.executionPolicy).toEqual(executionPolicy);
      expect(item.id).toBe("item-4");
      expect(item.getContent()).toEqual({ input: "hello", output: "world" });
    });

    it("should handle fromApiModel without evaluators and executionPolicy", () => {
      const apiModel = {
        id: "item-5",
        source: DatasetItemWriteSource.Sdk,
        data: { input: "test" },
      };

      const item = DatasetItem.fromApiModel(apiModel);

      expect(item.evaluators).toBeUndefined();
      expect(item.executionPolicy).toBeUndefined();
      expect(item.id).toBe("item-5");
    });
  });
});
