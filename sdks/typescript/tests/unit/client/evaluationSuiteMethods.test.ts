import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { EvaluationSuite } from "@/evaluation/suite/EvaluationSuite";
import { DatasetNotFoundError } from "@/dataset";
import { OpikApiError } from "@/rest_api";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
  mockAPIFunctionWithStream,
} from "../../mockUtils";

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

vi.mock("@/evaluation/suite_evaluators/validators", () => ({
  validateEvaluators: vi.fn(),
}));

import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import { validateEvaluators } from "@/evaluation/suite_evaluators/validators";

describe("EvaluationSuite static methods", () => {
  let opikClient: OpikClient;
  let createDatasetSpy: MockInstance;
  let applyChangesSpy: MockInstance;
  let getDatasetByIdentifierSpy: MockInstance;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    createDatasetSpy = vi
      .spyOn(opikClient.api.datasets, "createDataset")
      .mockImplementation(mockAPIFunction);

    applyChangesSpy = vi
      .spyOn(opikClient.api.datasets, "applyDatasetItemChanges")
      .mockImplementation(mockAPIFunction);

    getDatasetByIdentifierSpy = vi
      .spyOn(opikClient.api.datasets, "getDatasetByIdentifier")
      .mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-1",
          name: "test-suite",
          description: "A test suite",
        })
      );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("EvaluationSuite.create", () => {
    it("should create dataset with type evaluation_suite and return EvaluationSuite", async () => {
      const suite = await EvaluationSuite.create(opikClient, {
        name: "my-suite",
        description: "My evaluation suite",
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(suite.name).toBe("my-suite");
      expect(suite.description).toBe("My evaluation suite");
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "my-suite",
          description: "My evaluation suite",
          type: "evaluation_suite",
        })
      );
      // No evaluators or policy, so applyDatasetItemChanges should not be called
      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should create initial version when evaluators are provided", async () => {
      const mockEvaluator = new LLMJudge({
        assertions: ["is accurate"],
        name: "accuracy-judge",
      });

      const suite = await EvaluationSuite.create(opikClient, {
        name: "my-suite",
        evaluators: [mockEvaluator],
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(validateEvaluators).toHaveBeenCalledWith(
        [mockEvaluator],
        "suite-level evaluators"
      );
      expect(applyChangesSpy).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          override: true,
          body: expect.objectContaining({
            evaluators: [
              expect.objectContaining({
                name: "accuracy-judge",
                type: "llm_judge",
              }),
            ],
            executionPolicy: { runsPerItem: 3, passThreshold: 2 },
          }),
        })
      );
    });

    it("should validate evaluators before creation", async () => {
      const mockValidateEvaluators = vi.mocked(validateEvaluators);
      mockValidateEvaluators.mockImplementation(() => {
        throw new TypeError("Only LLMJudge evaluators are supported");
      });

      const invalidEvaluator = { name: "invalid" } as unknown as LLMJudge;

      await expect(
        EvaluationSuite.create(opikClient, {
          name: "my-suite",
          evaluators: [invalidEvaluator],
        })
      ).rejects.toThrow(TypeError);

      expect(createDatasetSpy).not.toHaveBeenCalled();
    });
  });

  describe("EvaluationSuite.get", () => {
    let syncHashesSpy: MockInstance;

    beforeEach(() => {
      vi.spyOn(opikClient.datasetBatchQueue, "flush").mockResolvedValue(
        undefined
      );
      syncHashesSpy = vi
        .spyOn(Dataset.prototype, "syncHashes")
        .mockResolvedValue(undefined);
    });

    it("should fetch dataset by name and return EvaluationSuite", async () => {
      const suite = await EvaluationSuite.get(opikClient, "test-suite");

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(suite.name).toBe("test-suite");
      expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
      });
      expect(syncHashesSpy).toHaveBeenCalled();
    });

    it("should throw DatasetNotFoundError on 404", async () => {
      getDatasetByIdentifierSpy.mockImplementation(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
          body: {},
          rawResponse: {
            status: 404,
            headers: {} as Headers,
            statusText: "Not Found",
            url: "https://mock.test",
            redirected: false,
            type: "basic",
          },
        });
      });

      await expect(
        EvaluationSuite.get(opikClient, "nonexistent-suite")
      ).rejects.toThrow(DatasetNotFoundError);
    });
  });

  describe("EvaluationSuite.getOrCreate", () => {
    beforeEach(() => {
      vi.spyOn(opikClient.datasetBatchQueue, "flush").mockResolvedValue(
        undefined
      );
      vi.spyOn(Dataset.prototype, "syncHashes").mockResolvedValue(undefined);
    });

    it("should return existing suite when found", async () => {
      const suite = await EvaluationSuite.getOrCreate(opikClient, {
        name: "test-suite",
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(suite.name).toBe("test-suite");
      // Should not have created a new dataset
      expect(createDatasetSpy).not.toHaveBeenCalled();
    });

    it("should create new suite when not found (404)", async () => {
      getDatasetByIdentifierSpy.mockImplementation(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
          body: {},
          rawResponse: {
            status: 404,
            headers: {} as Headers,
            statusText: "Not Found",
            url: "https://mock.test",
            redirected: false,
            type: "basic",
          },
        });
      });

      const suite = await EvaluationSuite.getOrCreate(opikClient, {
        name: "new-suite",
        description: "New suite",
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(suite.name).toBe("new-suite");
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "new-suite",
          description: "New suite",
          type: "evaluation_suite",
        })
      );
    });
  });
});
