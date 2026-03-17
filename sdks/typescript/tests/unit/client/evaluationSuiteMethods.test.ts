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

    it("should create initial version when assertions are provided", async () => {
      const suite = await EvaluationSuite.create(opikClient, {
        name: "my-suite",
        assertions: ["is accurate"],
        executionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(applyChangesSpy).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          override: true,
          body: expect.objectContaining({
            evaluators: [
              expect.objectContaining({
                name: "llm_judge",
                type: "llm_judge",
              }),
            ],
            execution_policy: { runs_per_item: 3, pass_threshold: 2 },
          }),
        })
      );
    });

    it("should create suite with assertions shorthand", async () => {
      const suite = await EvaluationSuite.create(opikClient, {
        name: "my-suite",
        assertions: ["is accurate", "is concise"],
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(applyChangesSpy).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          override: true,
          body: expect.objectContaining({
            evaluators: [
              expect.objectContaining({
                name: "llm_judge",
                type: "llm_judge",
              }),
            ],
            execution_policy: { runs_per_item: 2, pass_threshold: 1 },
          }),
        })
      );
    });

    it("should pass tags to createDataset", async () => {
      const suite = await EvaluationSuite.create(opikClient, {
        name: "my-suite",
        tags: ["prod", "v2"],
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "my-suite",
          tags: ["prod", "v2"],
          type: "evaluation_suite",
        })
      );
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

    it("should call update() when existing suite found and options have assertions/tags/executionPolicy", async () => {
      const updateSpy = vi
        .spyOn(EvaluationSuite.prototype, "update")
        .mockResolvedValue(undefined);

      const suite = await EvaluationSuite.getOrCreate(opikClient, {
        name: "test-suite",
        assertions: ["is accurate"],
        tags: ["prod"],
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      expect(suite).toBeInstanceOf(EvaluationSuite);
      expect(suite.name).toBe("test-suite");
      expect(createDatasetSpy).not.toHaveBeenCalled();
      expect(updateSpy).toHaveBeenCalledWith({
        assertions: ["is accurate"],
        tags: ["prod"],
        executionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });
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
