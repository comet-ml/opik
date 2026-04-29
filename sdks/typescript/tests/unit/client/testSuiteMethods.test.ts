import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { TestSuite } from "@/evaluation/suite/TestSuite";
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

describe("TestSuite static methods", () => {
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

  describe("TestSuite.create", () => {
    it("should create dataset with type evaluation_suite and return TestSuite", async () => {
      const suite = await TestSuite.create(opikClient, {
        name: "my-suite",
        description: "My test suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("my-suite");
      expect(suite.description).toBe("My test suite");
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "my-suite",
          description: "My test suite",
          // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
          type: "evaluation_suite",
        })
      );
      // No evaluators or policy, so applyDatasetItemChanges should not be called
      expect(applyChangesSpy).not.toHaveBeenCalled();
    });

    it("should create initial version when assertions are provided", async () => {
      const suite = await TestSuite.create(opikClient, {
        name: "my-suite",
        globalAssertions: ["is accurate"],
        globalExecutionPolicy: { runsPerItem: 3, passThreshold: 2 },
      });

      expect(suite).toBeInstanceOf(TestSuite);
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
      const suite = await TestSuite.create(opikClient, {
        name: "my-suite",
        globalAssertions: ["is accurate", "is concise"],
        globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      expect(suite).toBeInstanceOf(TestSuite);
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
      const suite = await TestSuite.create(opikClient, {
        name: "my-suite",
        tags: ["prod", "v2"],
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "my-suite",
          tags: ["prod", "v2"],
          // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
          type: "evaluation_suite",
        })
      );
    });
  });

  describe("TestSuite.get", () => {
    let syncHashesSpy: MockInstance;

    beforeEach(() => {
      vi.spyOn(opikClient.datasetBatchQueue, "flush").mockResolvedValue(
        undefined
      );
      syncHashesSpy = vi
        .spyOn(Dataset.prototype, "syncHashes")
        .mockResolvedValue(undefined);
    });

    it("should fetch dataset by name and return TestSuite", async () => {
      const suite = await TestSuite.get(opikClient, "test-suite");

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("test-suite");
      expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "opik-sdk-typescript-test",
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
        TestSuite.get(opikClient, "nonexistent-suite")
      ).rejects.toThrow(DatasetNotFoundError);
    });
  });

  describe("TestSuite.getOrCreate", () => {
    beforeEach(() => {
      vi.spyOn(opikClient.datasetBatchQueue, "flush").mockResolvedValue(
        undefined
      );
      vi.spyOn(Dataset.prototype, "syncHashes").mockResolvedValue(undefined);
    });

    it("should return existing suite when found", async () => {
      const suite = await TestSuite.getOrCreate(opikClient, {
        name: "test-suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("test-suite");
      // Should not have created a new dataset
      expect(createDatasetSpy).not.toHaveBeenCalled();
    });

    it("should NOT call update() when existing suite found, even with globalAssertions/tags/globalExecutionPolicy", async () => {
      const updateSpy = vi
        .spyOn(TestSuite.prototype, "update")
        .mockResolvedValue(undefined);

      const suite = await TestSuite.getOrCreate(opikClient, {
        name: "test-suite",
        globalAssertions: ["is accurate"],
        tags: ["prod"],
        globalExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("test-suite");
      expect(createDatasetSpy).not.toHaveBeenCalled();
      expect(updateSpy).not.toHaveBeenCalled();
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

      const suite = await TestSuite.getOrCreate(opikClient, {
        name: "new-suite",
        description: "New suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("new-suite");
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "new-suite",
          description: "New suite",
          // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
          type: "evaluation_suite",
        })
      );
    });
  });

  describe("TestSuite.delete", () => {
    let deleteDatasetByNameSpy: MockInstance;

    beforeEach(() => {
      deleteDatasetByNameSpy = vi
        .spyOn(opikClient.api.datasets, "deleteDatasetByName")
        .mockImplementation(mockAPIFunction);
    });

    it("should delete a test suite by name", async () => {
      await TestSuite.delete(opikClient, "test-suite");

      expect(deleteDatasetByNameSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: undefined,
      });
    });

    it("should delete a test suite with custom project name", async () => {
      await TestSuite.delete(opikClient, "test-suite", "custom-project");

      expect(deleteDatasetByNameSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "custom-project",
      });
    });

    it("should throw error for empty suite name", async () => {
      await expect(
        TestSuite.delete(opikClient, "")
      ).rejects.toThrow("Test suite name must be a non-empty string");
    });

    it("should throw error for whitespace-only suite name", async () => {
      await expect(
        TestSuite.delete(opikClient, "   ")
      ).rejects.toThrow("Test suite name must be a non-empty string");
    });
  });
});

describe("OpikClient TestSuite methods", () => {
  let opikClient: OpikClient;
  let createDatasetSpy: MockInstance;
  let getDatasetByIdentifierSpy: MockInstance;
  let deleteDatasetByNameSpy: MockInstance;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    createDatasetSpy = vi
      .spyOn(opikClient.api.datasets, "createDataset")
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

    deleteDatasetByNameSpy = vi
      .spyOn(opikClient.api.datasets, "deleteDatasetByName")
      .mockImplementation(mockAPIFunction);

    vi.spyOn(opikClient.datasetBatchQueue, "flush").mockResolvedValue(undefined);
    vi.spyOn(Dataset.prototype, "syncHashes").mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("createTestSuite", () => {
    it("should create a test suite via client", async () => {
      const suite = await opikClient.createTestSuite({
        name: "my-suite",
        description: "My test suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("my-suite");
      expect(suite.description).toBe("My test suite");
      expect(createDatasetSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "my-suite",
          description: "My test suite",
          type: "evaluation_suite",
        })
      );
    });
  });

  describe("getTestSuite", () => {
    it("should get a test suite by name via client", async () => {
      const suite = await opikClient.getTestSuite("test-suite");

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("test-suite");
      expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "opik-sdk-typescript-test",
      });
    });

    it("should get test suite with custom project name", async () => {
      const suite = await opikClient.getTestSuite("test-suite", "custom-project");

      expect(suite).toBeInstanceOf(TestSuite);
      expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "custom-project",
      });
    });
  });

  describe("getOrCreateTestSuite", () => {
    it("should return existing test suite via client", async () => {
      const suite = await opikClient.getOrCreateTestSuite({
        name: "test-suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
      expect(suite.name).toBe("test-suite");
      expect(createDatasetSpy).not.toHaveBeenCalled();
    });

    it("should create new test suite when not found via client", async () => {
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

      const suite = await opikClient.getOrCreateTestSuite({
        name: "new-suite",
        description: "New suite",
      });

      expect(suite).toBeInstanceOf(TestSuite);
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

  describe("deleteTestSuite", () => {
    it("should delete a test suite via client", async () => {
      await opikClient.deleteTestSuite("test-suite");

      expect(deleteDatasetByNameSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "opik-sdk-typescript-test",
      });
    });

    it("should delete test suite with custom project name via client", async () => {
      await opikClient.deleteTestSuite("test-suite", "custom-project");

      expect(deleteDatasetByNameSpy).toHaveBeenCalledWith({
        datasetName: "test-suite",
        projectName: "custom-project",
      });
    });
  });

  describe("getTestSuites", () => {
    let findDatasetsSpy: MockInstance;

    beforeEach(() => {
      vi.spyOn(opikClient.api.projects, "retrieveProject").mockImplementation(() =>
        createMockHttpResponsePromise({ id: "project-123", name: "opik-sdk-typescript-test" })
      );

      // Return two suites on page 1, empty page on page 2 to stop pagination
      findDatasetsSpy = vi
        .spyOn(opikClient.api.datasets, "findDatasets")
        .mockImplementation((req) =>
          createMockHttpResponsePromise(
            (req as { page?: number }).page === 1 || (req as { page?: number }).page === undefined
              ? {
                  content: [
                    { id: "ds-1", name: "suite-1", description: "Suite 1", type: "evaluation_suite" },
                    { id: "ds-2", name: "suite-2", description: "Suite 2", type: "evaluation_suite" },
                  ],
                }
              : { content: [] }
          )
        );
    });

    it("should get all test suites via client", async () => {
      const suites = await opikClient.getTestSuites();

      expect(suites).toHaveLength(2);
      expect(suites[0]).toBeInstanceOf(TestSuite);
      expect(suites[0].name).toBe("suite-1");
      expect(suites[1].name).toBe("suite-2");
      // pagination: always uses PAGE_SIZE=100, includes page number
      expect(findDatasetsSpy).toHaveBeenCalledWith({
        page: 1,
        size: 100,
        projectId: "project-123",
      });
    });

    it("should filter out non-suite datasets", async () => {
      findDatasetsSpy.mockImplementation((req) =>
        createMockHttpResponsePromise(
          (req as { page?: number }).page === 1 || (req as { page?: number }).page === undefined
            ? {
                content: [
                  { id: "ds-1", name: "suite-1", type: "evaluation_suite" },
                  { id: "ds-2", name: "plain-dataset", type: "dataset" },
                  { id: "ds-3", name: "suite-2", type: "evaluation_suite" },
                ],
              }
            : { content: [] }
        )
      );

      const suites = await opikClient.getTestSuites();

      expect(suites).toHaveLength(2);
      expect(suites[0].name).toBe("suite-1");
      expect(suites[1].name).toBe("suite-2");
    });

    it("should respect maxResults cap across pages", async () => {
      const suites = await opikClient.getTestSuites(1);

      expect(suites).toHaveLength(1);
      expect(suites[0].name).toBe("suite-1");
    });

    it("should paginate until empty page is returned", async () => {
      findDatasetsSpy.mockImplementation((req) => {
        const page = (req as { page?: number }).page ?? 1;
        return createMockHttpResponsePromise(
          page === 1
            ? { content: [{ id: "ds-1", name: "suite-1", type: "evaluation_suite" }] }
            : page === 2
              ? { content: [{ id: "ds-2", name: "suite-2", type: "evaluation_suite" }] }
              : { content: [] }
        );
      });

      const suites = await opikClient.getTestSuites();

      expect(suites).toHaveLength(2);
      expect(findDatasetsSpy).toHaveBeenCalledTimes(3); // page 1, 2, 3 (empty)
    });

    it("should get test suites with custom project name", async () => {
      vi.spyOn(opikClient.api.projects, "retrieveProject").mockImplementation(
        () =>
          createMockHttpResponsePromise({
            id: "project-custom",
            name: "custom-project",
          })
      );

      await opikClient.getTestSuites(1000, "custom-project");

      expect(findDatasetsSpy).toHaveBeenCalledWith({
        page: 1,
        size: 100,
        projectId: "project-custom",
      });
    });
  });
});
