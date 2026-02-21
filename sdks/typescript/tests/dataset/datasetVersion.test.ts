import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { DatasetVersion } from "@/dataset/DatasetVersion";
import { Dataset } from "@/dataset/Dataset";
import { OpikClient } from "@/client/Client";
import type { DatasetVersionPublic } from "opik";
import { DatasetVersionNotFoundError } from "@/errors";
import {
  createMockHttpResponsePromise,
  mockAPIFunctionWithStream,
} from "../mockUtils";
import { OpikApiError } from "@/rest_api/errors";

const MOCK_VERSION_INFO: DatasetVersionPublic = {
  id: "version-id-1",
  versionHash: "hash-abc123",
  versionName: "v1",
  tags: ["production", "stable"],
  isLatest: true,
  itemsTotal: 100,
  itemsAdded: 10,
  itemsModified: 5,
  itemsDeleted: 2,
  changeDescription: "Initial version",
  createdAt: new Date("2024-01-01T00:00:00Z"),
  createdBy: "test-user",
};

function createTestClient(): OpikClient {
  return new OpikClient({ projectName: "opik-sdk-typescript-test" });
}

function createTestDatasetVersion(
  opikClient: OpikClient,
  versionInfo: DatasetVersionPublic = MOCK_VERSION_INFO
): DatasetVersion {
  return new DatasetVersion(
    "test-dataset",
    "dataset-id-123",
    versionInfo,
    opikClient
  );
}

describe("DatasetVersion", () => {
  let opikClient: OpikClient;
  let datasetVersion: DatasetVersion;

  beforeEach(() => {
    opikClient = createTestClient();
    datasetVersion = createTestDatasetVersion(opikClient);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("constructor and properties", () => {
    it("should expose all properties from versionInfo", () => {
      expect(datasetVersion.datasetName).toBe("test-dataset");
      expect(datasetVersion.datasetId).toBe("dataset-id-123");
      expect(datasetVersion.name).toBe("test-dataset");
      expect(datasetVersion.id).toBe("dataset-id-123");
      expect(datasetVersion.versionId).toBe("version-id-1");
      expect(datasetVersion.versionHash).toBe("hash-abc123");
      expect(datasetVersion.versionName).toBe("v1");
      expect(datasetVersion.tags).toEqual(["production", "stable"]);
      expect(datasetVersion.isLatest).toBe(true);
      expect(datasetVersion.itemsTotal).toBe(100);
      expect(datasetVersion.itemsAdded).toBe(10);
      expect(datasetVersion.itemsModified).toBe(5);
      expect(datasetVersion.itemsDeleted).toBe(2);
      expect(datasetVersion.changeDescription).toBe("Initial version");
      expect(datasetVersion.createdAt).toEqual(new Date("2024-01-01T00:00:00Z"));
      expect(datasetVersion.createdBy).toBe("test-user");
    });

    it("should handle undefined optional fields in versionInfo", () => {
      const minimalVersion = createTestDatasetVersion(opikClient, {});

      expect(minimalVersion.versionId).toBeUndefined();
      expect(minimalVersion.versionHash).toBeUndefined();
      expect(minimalVersion.versionName).toBeUndefined();
      expect(minimalVersion.tags).toBeUndefined();
      expect(minimalVersion.isLatest).toBeUndefined();
      expect(minimalVersion.itemsTotal).toBeUndefined();
      expect(minimalVersion.itemsAdded).toBeUndefined();
      expect(minimalVersion.itemsModified).toBeUndefined();
      expect(minimalVersion.itemsDeleted).toBeUndefined();
      expect(minimalVersion.changeDescription).toBeUndefined();
      expect(minimalVersion.createdAt).toBeUndefined();
      expect(minimalVersion.createdBy).toBeUndefined();
    });
  });

  describe("getVersionInfo", () => {
    it("should return the full DatasetVersionPublic object", () => {
      const versionInfo = datasetVersion.getVersionInfo();

      expect(versionInfo).toBe(MOCK_VERSION_INFO);
      expect(versionInfo.id).toBe("version-id-1");
      expect(versionInfo.versionName).toBe("v1");
      expect(versionInfo.tags).toEqual(["production", "stable"]);
    });
  });

  describe("getItems", () => {
    let streamDatasetItemsSpy: MockInstance;

    beforeEach(() => {
      streamDatasetItemsSpy = vi.spyOn(
        opikClient.api.datasets,
        "streamDatasetItems"
      );
    });

    it("should call streamDatasetItems with datasetVersion parameter", async () => {
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: "item-1",
            data: { input: "test input" },
            source: "sdk",
          }) + "\n"
        )
      );

      await datasetVersion.getItems();

      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: undefined,
        steamLimit: 2000,
        datasetVersion: "hash-abc123",
      });
    });

    it("should return items with id included", async () => {
      const mockDatasetItemId = "019c6c79-9bfe-73d1-ad1a-4b7ec5b84371";
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: mockDatasetItemId,
            data: { input: "test input", output: "test output" },
            source: "sdk",
          }) + "\n"
        )
      );

      const items = await datasetVersion.getItems();

      expect(items).toHaveLength(1);
      expect(items[0]).toEqual({
        id: mockDatasetItemId,
        input: "test input",
        output: "test output",
      });
    });

    it("should respect nbSamples parameter", async () => {
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: "item-1",
            data: { input: "test input" },
            source: "sdk",
          }) + "\n"
        )
      );

      await datasetVersion.getItems(50);

      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: undefined,
        steamLimit: 50,
        datasetVersion: "hash-abc123",
      });
    });

    it("should cap request limit to 2000", async () => {
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: "item-1",
            data: { input: "test input" },
            source: "sdk",
          }) + "\n"
        )
      );

      await datasetVersion.getItems(5000);

      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: undefined,
        steamLimit: 2000, // Should be capped at 2000
        datasetVersion: "hash-abc123",
      });
    });

    it("should handle multiple items", async () => {
      const mockItemIds = [
        "019c6c79-9c02-719c-bd07-a566592034aa",
        "019c6c79-9c03-719c-bd07-a566592034bb",
        "019c6c79-9c04-719c-bd07-a566592034cc",
      ];
      const items = [
        { dataset_item_id: mockItemIds[0], data: { input: "input 1" }, source: "sdk" },
        { dataset_item_id: mockItemIds[1], data: { input: "input 2" }, source: "sdk" },
        { dataset_item_id: mockItemIds[2], data: { input: "input 3" }, source: "sdk" },
      ];

      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          items.map((item) => JSON.stringify(item)).join("\n") + "\n"
        )
      );

      const result = await datasetVersion.getItems();

      expect(result).toHaveLength(3);
      expect(result[0].id).toBe(mockItemIds[0]);
      expect(result[1].id).toBe(mockItemIds[1]);
      expect(result[2].id).toBe(mockItemIds[2]);
    });
  });

  describe("toJson", () => {
    let streamDatasetItemsSpy: MockInstance;

    beforeEach(() => {
      streamDatasetItemsSpy = vi.spyOn(
        opikClient.api.datasets,
        "streamDatasetItems"
      );
    });

    it("should convert items to JSON string", async () => {
      const mockDatasetItemId = "019c6c79-9d5e-760b-aca7-2d8013169209";
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: mockDatasetItemId,
            data: { input: "test input", output: "test output" },
            source: "sdk",
          }) + "\n"
        )
      );

      const jsonString = await datasetVersion.toJson();
      const parsed = JSON.parse(jsonString);

      expect(Array.isArray(parsed)).toBe(true);
      expect(parsed).toHaveLength(1);
      expect(parsed[0]).toEqual({
        id: mockDatasetItemId,
        input: "test input",
        output: "test output",
      });
    });

    it("should apply key mappings when provided", async () => {
      const mockDatasetItemId = "019c6c79-9d5f-760b-aca7-2d8013169210";
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: mockDatasetItemId,
            data: { input: "test input", output: "test output" },
            source: "sdk",
          }) + "\n"
        )
      );

      const jsonString = await datasetVersion.toJson({
        input: "question",
        output: "answer",
      });
      const parsed = JSON.parse(jsonString);

      expect(parsed[0]).toHaveProperty("question", "test input");
      expect(parsed[0]).toHaveProperty("answer", "test output");
      expect(parsed[0]).not.toHaveProperty("input");
      expect(parsed[0]).not.toHaveProperty("output");
      expect(parsed[0]).toHaveProperty("id", mockDatasetItemId);
    });

    it("should handle empty items list", async () => {
      streamDatasetItemsSpy.mockImplementation(() =>
        mockAPIFunctionWithStream("")
      );

      const jsonString = await datasetVersion.toJson();
      const parsed = JSON.parse(jsonString);

      expect(Array.isArray(parsed)).toBe(true);
      expect(parsed).toHaveLength(0);
    });
  });
});

describe("Dataset version methods", () => {
  let opikClient: OpikClient;
  let dataset: Dataset;
  let listDatasetVersionsSpy: MockInstance;
  let retrieveDatasetVersionSpy: MockInstance;

  beforeEach(() => {
    opikClient = createTestClient();

    dataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset",
      },
      opikClient
    );

    listDatasetVersionsSpy = vi.spyOn(
      opikClient.api.datasets,
      "listDatasetVersions"
    );

    retrieveDatasetVersionSpy = vi.spyOn(
      opikClient.api.datasets,
      "retrieveDatasetVersion"
    );

    vi.spyOn(opikClient.api.datasets, "streamDatasetItems").mockImplementation(
      () => mockAPIFunctionWithStream("")
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("getVersionView", () => {
    it("should return DatasetVersion for existing version", async () => {
      retrieveDatasetVersionSpy.mockImplementation(() =>
        createMockHttpResponsePromise(MOCK_VERSION_INFO)
      );

      const versionView = await dataset.getVersionView("v1");

      expect(versionView).toBeInstanceOf(DatasetVersion);
      expect(versionView.versionName).toBe("v1");
      expect(versionView.datasetName).toBe("test-dataset");
      expect(versionView.datasetId).toBe("test-dataset-id");
      expect(versionView.versionId).toBe("version-id-1");
      expect(versionView.versionHash).toBe("hash-abc123");
      expect(versionView.tags).toEqual(["production", "stable"]);
    });

    it("should throw DatasetVersionNotFoundError when version not found", async () => {
      retrieveDatasetVersionSpy.mockImplementation(() =>
        Promise.reject(
          new OpikApiError({ message: "Not found", statusCode: 404 })
        )
      );

      await expect(dataset.getVersionView("v99")).rejects.toThrow(
        DatasetVersionNotFoundError
      );
    });

    it("should propagate non-404 errors", async () => {
      retrieveDatasetVersionSpy.mockImplementation(() =>
        Promise.reject(
          new OpikApiError({ message: "Server error", statusCode: 500 })
        )
      );

      await expect(dataset.getVersionView("v1")).rejects.toThrow(OpikApiError);
    });
  });

  describe("getCurrentVersionName", () => {
    it("should return latest version name when versions exist", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [MOCK_VERSION_INFO],
          page: 1,
          size: 1,
          total: 1,
        })
      );

      const versionName = await dataset.getCurrentVersionName();

      expect(versionName).toBe("v1");
    });

    it("should return undefined when no versions exist", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        createMockHttpResponsePromise({ content: [], page: 1, size: 1, total: 0 })
      );

      expect(await dataset.getCurrentVersionName()).toBeUndefined();
    });

    it("should handle 404 error gracefully", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        Promise.reject(new OpikApiError({ message: "Not found", statusCode: 404 }))
      );

      expect(await dataset.getCurrentVersionName()).toBeUndefined();
    });
  });

  describe("getVersionInfo", () => {
    it("should return latest version info with correct API parameters", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [MOCK_VERSION_INFO],
          page: 1,
          size: 1,
          total: 1,
        })
      );

      const versionInfo = await dataset.getVersionInfo();

      expect(versionInfo).toEqual(MOCK_VERSION_INFO);
      expect(listDatasetVersionsSpy).toHaveBeenCalledWith("test-dataset-id", {
        page: 1,
        size: 1,
      });
    });

    it("should return undefined when no versions exist", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        createMockHttpResponsePromise({ content: [], page: 1, size: 1, total: 0 })
      );

      expect(await dataset.getVersionInfo()).toBeUndefined();
    });

    it("should handle 404 error gracefully", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        Promise.reject(new OpikApiError({ message: "Not found", statusCode: 404 }))
      );

      expect(await dataset.getVersionInfo()).toBeUndefined();
    });

    it("should propagate non-404 errors", async () => {
      listDatasetVersionsSpy.mockImplementation(() =>
        Promise.reject(new OpikApiError({ message: "Server error", statusCode: 500 }))
      );

      await expect(dataset.getVersionInfo()).rejects.toThrow(OpikApiError);
    });
  });
});

describe("DatasetVersionNotFoundError", () => {
  it("should create error with correct message format", () => {
    const error = new DatasetVersionNotFoundError("v3", "my-dataset");

    expect(error.message).toBe(
      "Dataset version 'v3' not found in dataset 'my-dataset'"
    );
    expect(error.code).toBe("DATASET_VERSION_NOT_FOUND");
  });

  it("should be an instance of Error", () => {
    const error = new DatasetVersionNotFoundError("v1", "test-dataset");

    expect(error).toBeInstanceOf(Error);
  });
});
