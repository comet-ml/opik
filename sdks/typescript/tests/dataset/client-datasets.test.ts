import { Opik } from "opik";
import { MockInstance } from "vitest";
import { Dataset } from "@/dataset/Dataset";
import { DatasetNotFoundError } from "@/dataset/errors";
import { OpikApiError } from "@/rest_api";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  mockAPIFunctionWithError,
  createMockHttpResponsePromise,
} from "../mockUtils";

describe("Opik dataset operations", () => {
  let client: Opik;
  let getDatasetByIdentifierSpy: MockInstance<
    typeof client.api.datasets.getDatasetByIdentifier
  >;
  let getDatasetByIdSpy: MockInstance<
    typeof client.api.datasets.getDatasetById
  >;
  let createDatasetSpy: MockInstance<typeof client.api.datasets.createDataset>;
  let findDatasetsSpy: MockInstance<typeof client.api.datasets.findDatasets>;
  let deleteDatasetsBatchSpy: MockInstance<
    typeof client.api.datasets.deleteDatasetsBatch
  >;
  let updateDatasetSpy: MockInstance<typeof client.api.datasets.updateDataset>;
  let datasetBatchQueueFlushSpy: MockInstance;
  let datasetBatchQueueCreateSpy: MockInstance;
  let datasetBatchQueueDeleteSpy: MockInstance;
  let datasetBatchQueueUpdateSpy: MockInstance;
  let loggerErrorSpy: MockInstance<typeof logger.error>;
  let loggerInfoSpy: MockInstance<typeof logger.info>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    // Mock API methods
    getDatasetByIdentifierSpy = vi
      .spyOn(client.api.datasets, "getDatasetByIdentifier")
      .mockImplementation(mockAPIFunction);

    getDatasetByIdSpy = vi
      .spyOn(client.api.datasets, "getDatasetById")
      .mockImplementation(mockAPIFunction);

    createDatasetSpy = vi
      .spyOn(client.api.datasets, "createDataset")
      .mockImplementation(mockAPIFunction);

    findDatasetsSpy = vi
      .spyOn(client.api.datasets, "findDatasets")
      .mockImplementation(mockAPIFunction);

    deleteDatasetsBatchSpy = vi
      .spyOn(client.api.datasets, "deleteDatasetsBatch")
      .mockImplementation(mockAPIFunction);

    updateDatasetSpy = vi
      .spyOn(client.api.datasets, "updateDataset")
      .mockImplementation(mockAPIFunction);

    // Spy on batch queue methods without mocking them
    datasetBatchQueueCreateSpy = vi.spyOn(client.datasetBatchQueue, "create");

    datasetBatchQueueDeleteSpy = vi.spyOn(client.datasetBatchQueue, "delete");

    datasetBatchQueueFlushSpy = vi.spyOn(client.datasetBatchQueue, "flush");

    datasetBatchQueueUpdateSpy = vi.spyOn(client.datasetBatchQueue, "update");

    // Mock logger methods
    loggerErrorSpy = vi.spyOn(logger, "error");
    loggerInfoSpy = vi.spyOn(logger, "info");

    // Using fake timers to control batch queue timing
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();

    getDatasetByIdentifierSpy.mockRestore();
    getDatasetByIdSpy.mockRestore();
    createDatasetSpy.mockRestore();
    findDatasetsSpy.mockRestore();
    deleteDatasetsBatchSpy.mockRestore();
    updateDatasetSpy.mockRestore();
    datasetBatchQueueCreateSpy.mockRestore();
    datasetBatchQueueDeleteSpy.mockRestore();
    datasetBatchQueueFlushSpy.mockRestore();
    datasetBatchQueueUpdateSpy.mockRestore();
    loggerErrorSpy.mockRestore();
    loggerInfoSpy.mockRestore();
  });

  it("should retrieve an existing dataset by name", async () => {
    const mockDataset = {
      id: "dataset-123",
      name: "test-dataset",
      description: "Test dataset description",
    };

    getDatasetByIdentifierSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDataset)
    );

    const result = await client.getDataset("test-dataset");

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "test-dataset",
    });
    expect(result).toBeInstanceOf(Dataset);
    expect(result.id).toBe(mockDataset.id);
    expect(result.name).toBe(mockDataset.name);
    expect(result.description).toBe(mockDataset.description);
  });

  it("should throw DatasetNotFoundError when dataset doesn't exist", async () => {
    // Create an error with the properties the implementation checks for
    const apiError = new Error("Dataset not found");
    Object.defineProperty(apiError, "statusCode", { value: 404 });
    Object.setPrototypeOf(apiError, OpikApiError.prototype);

    getDatasetByIdentifierSpy.mockImplementationOnce(() => {
      throw apiError;
    });

    await expect(
      client.getDataset("non-existent-dataset")
    ).rejects.toBeInstanceOf(DatasetNotFoundError);

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "non-existent-dataset",
    });
  });

  it("should propagate API errors (non-404)", async () => {
    const apiError = new OpikApiError({
      message: "Server error",
      statusCode: 500,
    });

    getDatasetByIdentifierSpy.mockImplementationOnce(() => {
      throw apiError;
    });

    await expect(client.getDataset("test-dataset")).rejects.toThrowError();
    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "test-dataset",
    });
  });

  it("should create a new dataset with name and description", async () => {
    const result = await client.createDataset(
      "new-dataset",
      "New dataset description"
    );

    expect(result).toBeInstanceOf(Dataset);
    expect(result.name).toBe("new-dataset");
    expect(result.description).toBe("New dataset description");

    // Trigger the batch process by advancing timers
    vi.advanceTimersByTime(300); // Default delay is 300ms

    // The API method should be called after the timer
    await vi.runAllTimersAsync();
    expect(createDatasetSpy).toHaveBeenCalled();
  });

  it("should create a dataset with only a name", async () => {
    const result = await client.createDataset("name-only-dataset");

    expect(result).toBeInstanceOf(Dataset);
    expect(result.name).toBe("name-only-dataset");
    expect(result.description).toBeUndefined();
  });

  it("should handle errors when creating datasets", async () => {
    const errorMessage = "Error creating dataset";
    createDatasetSpy.mockImplementationOnce(() => {
      throw new Error(errorMessage);
    });

    await client.createDataset("error-dataset");

    // The dataset should be in the queue
    expect(datasetBatchQueueCreateSpy).toHaveBeenCalled();

    // Flush the batch queue
    vi.advanceTimersByTime(300);

    // Run the timers - note that vi.runAllTimersAsync() doesn't automatically reject
    // even if errors occur during the executed callbacks
    await vi.runAllTimersAsync();

    // Check that the error was logged
    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should return an existing dataset if it exists", async () => {
    const mockDataset = {
      id: "dataset-123",
      name: "existing-dataset",
      description: "Existing dataset description",
    };

    getDatasetByIdentifierSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDataset)
    );

    const result = await client.getOrCreateDataset(
      "existing-dataset",
      "New description"
    );

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "existing-dataset",
    });
    expect(result).toBeInstanceOf(Dataset);
    expect(result.id).toBe(mockDataset.id);
    expect(result.name).toBe(mockDataset.name);
    expect(result.description).toBe(mockDataset.description);

    // Should not attempt to create since dataset already exists
    expect(datasetBatchQueueCreateSpy).not.toHaveBeenCalled();
  });

  it("should create a new dataset if it doesn't exist", async () => {
    // Create a DatasetNotFoundError
    const notFoundError = new DatasetNotFoundError("new-dataset");

    // Mock getDataset to throw DatasetNotFoundError
    getDatasetByIdentifierSpy.mockImplementationOnce(() => {
      throw notFoundError;
    });

    const result = await client.getOrCreateDataset(
      "new-dataset",
      "New dataset description"
    );

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "new-dataset",
    });

    expect(result).toBeInstanceOf(Dataset);
    expect(result.name).toBe("new-dataset");
    expect(result.description).toBe("New dataset description");

    expect(datasetBatchQueueCreateSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "new-dataset",
        description: "New dataset description",
      })
    );
  });

  it("should propagate unexpected errors in getOrCreateDataset", async () => {
    const apiError = new Error("Unexpected error");
    getDatasetByIdentifierSpy.mockImplementationOnce(() => {
      throw apiError;
    });

    await expect(client.getOrCreateDataset("test-dataset")).rejects.toThrow();

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "test-dataset",
    });
    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should retrieve all datasets with default limit", async () => {
    const mockDatasets = {
      content: [
        {
          id: "dataset-1",
          name: "dataset-one",
          description: "First dataset",
        },
        {
          id: "dataset-2",
          name: "dataset-two",
          description: "Second dataset",
        },
      ],
    };

    findDatasetsSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDatasets)
    );

    const results = await client.getDatasets();

    expect(datasetBatchQueueFlushSpy).toHaveBeenCalled();
    expect(findDatasetsSpy).toHaveBeenCalledWith({
      size: 100, // Default limit
    });

    expect(results).toHaveLength(2);
    expect(results[0]).toBeInstanceOf(Dataset);
    expect(results[0].id).toBe("dataset-1");
    expect(results[0].name).toBe("dataset-one");
    expect(results[1]).toBeInstanceOf(Dataset);
    expect(results[1].id).toBe("dataset-2");
  });

  it("should retrieve datasets with custom limit", async () => {
    const mockDatasets = {
      content: [
        {
          id: "dataset-1",
          name: "dataset-one",
          description: "First dataset",
        },
      ],
    };

    findDatasetsSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDatasets)
    );

    const results = await client.getDatasets(10);

    expect(datasetBatchQueueFlushSpy).toHaveBeenCalled();
    expect(findDatasetsSpy).toHaveBeenCalledWith({
      size: 10,
    });

    expect(results).toHaveLength(1);
  });

  it("should handle empty dataset list", async () => {
    const mockDatasets = {
      content: [],
    };

    findDatasetsSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDatasets)
    );

    const results = await client.getDatasets();

    expect(datasetBatchQueueFlushSpy).toHaveBeenCalled();
    expect(results).toHaveLength(0);
    expect(loggerInfoSpy).toHaveBeenCalledWith("Retrieved 0 datasets");
  });

  it("should handle API errors when retrieving datasets", async () => {
    const errorMessage = "Failed to retrieve datasets";
    findDatasetsSpy.mockImplementationOnce(
      mockAPIFunctionWithError(errorMessage)
    );

    await expect(client.getDatasets()).rejects.toThrow(
      "Failed to retrieve datasets"
    );

    expect(datasetBatchQueueFlushSpy).toHaveBeenCalled();
    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should delete an existing dataset by name", async () => {
    const mockDataset = {
      id: "dataset-123",
      name: "dataset-to-delete",
      description: "Dataset to delete",
    };

    getDatasetByIdentifierSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDataset)
    );

    await client.deleteDataset("dataset-to-delete");

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "dataset-to-delete",
    });

    expect(datasetBatchQueueDeleteSpy).toHaveBeenCalledWith(mockDataset.id);
  });

  it("should throw error when dataset to delete doesn't exist", async () => {
    const apiError = new OpikApiError({
      message: "Dataset not found",
      statusCode: 404,
    });
    getDatasetByIdentifierSpy.mockImplementationOnce(() => {
      throw apiError;
    });

    await expect(client.deleteDataset("non-existent-dataset")).rejects.toThrow(
      /Failed to delete dataset "non-existent-dataset"/
    );

    expect(getDatasetByIdentifierSpy).toHaveBeenCalledWith({
      datasetName: "non-existent-dataset",
    });
    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should handle other errors during dataset deletion", async () => {
    const mockDataset = {
      id: "dataset-123",
      name: "dataset-to-delete",
      description: "Dataset to delete",
    };

    getDatasetByIdentifierSpy.mockImplementationOnce(() =>
      createMockHttpResponsePromise(mockDataset)
    );

    const errorMessage = "Failed to delete";
    datasetBatchQueueDeleteSpy.mockImplementationOnce(() => {
      throw new Error(errorMessage);
    });

    await expect(client.deleteDataset("dataset-to-delete")).rejects.toThrow(
      /Failed to delete dataset "dataset-to-delete"/
    );

    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should throw error when dataset ID is not available", async () => {
    // Create a dataset instance with name and description but no ID
    const mockDataset = new Dataset(
      { name: "dataset-to-delete", description: "Dataset to delete" },
      client
    );
    // Override the id property to be null
    Object.defineProperty(mockDataset, "id", {
      get: () => null,
    });

    // Mock getDataset to return our dataset with null ID
    vi.spyOn(client, "getDataset").mockResolvedValueOnce(mockDataset);

    await expect(client.deleteDataset("dataset-to-delete")).rejects.toThrow(
      'Cannot delete dataset "dataset-to-delete": ID not available'
    );
  });

  it("should flush all batch queues including dataset queue", async () => {
    const traceBatchQueueFlushSpy = vi.spyOn(client.traceBatchQueue, "flush");
    const spanBatchQueueFlushSpy = vi.spyOn(client.spanBatchQueue, "flush");
    const traceFeedbackScoresBatchQueueFlushSpy = vi.spyOn(
      client.traceFeedbackScoresBatchQueue,
      "flush"
    );
    const spanFeedbackScoresBatchQueueFlushSpy = vi.spyOn(
      client.spanFeedbackScoresBatchQueue,
      "flush"
    );

    await client.flush();

    expect(traceBatchQueueFlushSpy).toHaveBeenCalled();
    expect(spanBatchQueueFlushSpy).toHaveBeenCalled();
    expect(traceFeedbackScoresBatchQueueFlushSpy).toHaveBeenCalled();
    expect(spanFeedbackScoresBatchQueueFlushSpy).toHaveBeenCalled();
    expect(datasetBatchQueueFlushSpy).toHaveBeenCalled();
    expect(loggerInfoSpy).toHaveBeenCalledWith(
      "Successfully flushed all data to Opik"
    );
  });

  it("should handle errors during flush", async () => {
    // Create a dataset to ensure there's something to flush
    await client.createDataset("error-flush-dataset");

    // Mock the API call to throw an error during flush
    createDatasetSpy.mockImplementationOnce(() => {
      throw new Error("Failed to flush dataset queue");
    });

    await client.flush();

    expect(loggerErrorSpy).toHaveBeenCalled();
  });

  it("should batch multiple dataset operations", async () => {
    // Reset call counts before test
    createDatasetSpy.mockClear();
    datasetBatchQueueCreateSpy.mockClear();

    // Create multiple datasets without flushing
    await client.createDataset("batch-dataset-1", "First dataset");
    await client.createDataset("batch-dataset-2", "Second dataset");
    await client.createDataset("batch-dataset-3", "Third dataset");

    // Verify they're in the queue but not yet sent to API
    expect(datasetBatchQueueCreateSpy).toHaveBeenCalledTimes(3);
    expect(createDatasetSpy).not.toHaveBeenCalled();

    // Manually flush instead of using timers for more reliable testing
    await client.flush();

    // Verify all datasets were created in one batch operation
    expect(createDatasetSpy).toHaveBeenCalledTimes(3);
  });

  it("should manually trigger flush for reliable testing", async () => {
    // Reset call counts before test
    createDatasetSpy.mockClear();
    datasetBatchQueueCreateSpy.mockClear();

    // Create a dataset
    await client.createDataset("timing-test-dataset");

    // Verify dataset was queued but not yet sent
    expect(datasetBatchQueueCreateSpy).toHaveBeenCalled();
    expect(createDatasetSpy).not.toHaveBeenCalled();

    // Manually flush to ensure predictable behavior
    await client.flush();

    // Now the API should have been called
    expect(createDatasetSpy).toHaveBeenCalled();
  });

  // TODO enable once Batch getByName is implemented
  it.skip("should process mixed operations in correct order", async () => {
    // Reset call counts before test
    createDatasetSpy.mockClear();
    deleteDatasetsBatchSpy.mockClear();
    datasetBatchQueueCreateSpy.mockClear();
    datasetBatchQueueDeleteSpy.mockClear();

    // Mock datasets with IDs for operations
    const mockDataset2 = {
      id: "mix-dataset-2",
      name: "Mix Dataset 2",
      description: "Second mixed operation dataset",
    };

    // Mock the get call to return our test dataset
    getDatasetByIdentifierSpy.mockImplementation((params) => {
      if (params.datasetName === "Mix Dataset 2") {
        return createMockHttpResponsePromise(mockDataset2);
      }
      throw new OpikApiError({ message: "Dataset not found", statusCode: 404 });
    });

    // Create a new dataset
    await client.createDataset("mix-dataset-3");

    // Delete another dataset
    await client.deleteDataset("Mix Dataset 2");

    // Before flush, check operations are queued
    expect(datasetBatchQueueCreateSpy).toHaveBeenCalledTimes(1);
    expect(datasetBatchQueueDeleteSpy).toHaveBeenCalledTimes(1);

    // API methods should not have been called yet
    expect(createDatasetSpy).not.toHaveBeenCalled();
    expect(deleteDatasetsBatchSpy).not.toHaveBeenCalled();

    // Manually flush to ensure reliable behavior
    await client.flush();

    // Verify all operations were executed
    expect(createDatasetSpy).toHaveBeenCalledTimes(1);
    expect(deleteDatasetsBatchSpy).toHaveBeenCalledTimes(1);
  });

  it("should respect explicit flush before timer expires", async () => {
    // Add item to batch queue
    await client.createDataset("manual-flush-dataset");

    // Verify it's in the queue
    expect(datasetBatchQueueCreateSpy).toHaveBeenCalledTimes(1);
    expect(createDatasetSpy).not.toHaveBeenCalled();

    // Manually flush before timer expires
    await client.flush();

    // API should be called immediately without waiting for timer
    expect(createDatasetSpy).toHaveBeenCalledTimes(1);
  });
});
