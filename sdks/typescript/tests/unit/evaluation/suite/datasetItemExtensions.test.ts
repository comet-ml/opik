import { describe, it, expect } from "vitest";
import { DatasetItem } from "@/dataset/DatasetItem";
import { DatasetItemWriteSource } from "@/rest_api/api";
import { EvaluatorItemWrite } from "@/rest_api/api/types/EvaluatorItemWrite";
import { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";

describe("DatasetItem extensions for evaluation suites", () => {
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
