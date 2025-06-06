import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Experiment } from "@/experiment/Experiment";
import {
  ExperimentItemContent,
  ExperimentItemReferences,
} from "@/experiment/ExperimentItem";
import {
  FeedbackScore,
  ExperimentItemCompare,
  FeedbackScoreSource,
} from "@/rest_api/api";
import { logger } from "@/utils/logger";
import { mockAPIFunction, mockAPIFunctionWithStream } from "../mockUtils";
import {
  createMockExperimentItemReferences,
  createMockExperimentItemContent,
  createMockExperimentItemCompare,
  verifyExperimentItemContent,
  createMockExperimentItemCompareRaw,
} from "./utils";

describe("Experiment entity operations", () => {
  let opikClient: OpikClient;
  let experiment: Experiment;

  let createExperimentItemsSpy: MockInstance;
  let streamExperimentItemsSpy: MockInstance;

  let loggerInfoSpy: MockInstance<typeof logger.info>;
  let loggerErrorSpy: MockInstance<typeof logger.error>;

  const DEFAULT_MAX_RESULTS = 2000;
  const DEFAULT_BATCH_SIZE = 50;

  beforeEach(() => {
    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    experiment = new Experiment(
      {
        id: "test-experiment-id",
        name: "test-experiment",
        datasetName: "test-dataset",
      },
      opikClient
    );

    createExperimentItemsSpy = vi
      .spyOn(opikClient.api.experiments, "createExperimentItems")
      .mockImplementation(mockAPIFunction);

    streamExperimentItemsSpy = vi
      .spyOn(opikClient.api.experiments, "streamExperimentItems")
      .mockImplementation(() => mockAPIFunctionWithStream(`{}`));

    loggerInfoSpy = vi.spyOn(logger, "info");
    loggerErrorSpy = vi.spyOn(logger, "error");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("ExperimentItemReferences", () => {
    it("should create an ExperimentItemReferences instance with provided values", () => {
      const mockData = createMockExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
      });

      const references = new ExperimentItemReferences(mockData);

      expect(references.datasetItemId).toBe(mockData.datasetItemId);
      expect(references.traceId).toBe(mockData.traceId);
    });

    it("should immutably store values", () => {
      const params = createMockExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
      });

      const references = new ExperimentItemReferences(params);

      // Attempt to modify the original params
      // @ts-expect-error - Intentionally trying to modify read-only properties
      params.datasetItemId = "modified";
      // @ts-expect-error - Intentionally trying to modify read-only properties
      params.traceId = "modified";

      expect(references.datasetItemId).toBe("dataset-item-1");
      expect(references.traceId).toBe("trace-1");
    });

    it("should throw error when datasetItemId is missing", () => {
      expect(
        // @ts-expect-error - Intentionally missing required property
        () => new ExperimentItemReferences({ traceId: "trace-1" })
      ).toThrow("datasetItemId is required");
    });

    it("should throw error when traceId is missing", () => {
      expect(
        // @ts-expect-error - Intentionally missing required property
        () => new ExperimentItemReferences({ datasetItemId: "dataset-item-1" })
      ).toThrow("traceId is required");
    });
  });

  describe("ExperimentItemContent", () => {
    it("should create an ExperimentItemContent instance with provided values", () => {
      const mockData = createMockExperimentItemContent({
        id: "content-1",
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
        datasetItemData: { left: ["data1"], right: ["data2"] },
        evaluationTaskOutput: { left: ["output1"], right: ["output2"] },
      });

      const content = new ExperimentItemContent(mockData);

      verifyExperimentItemContent(content, mockData);
    });

    it("should create a deep copy of feedbackScores", () => {
      const feedbackScores: FeedbackScore[] = [
        {
          categoryName: "category1",
          name: "score1",
          value: 0.8,
          reason: "Good performance",
          source: FeedbackScoreSource.Sdk,
        },
      ];

      const mockData = createMockExperimentItemContent({
        feedbackScores,
      });

      const content = new ExperimentItemContent(mockData);

      feedbackScores.push({
        categoryName: "category2",
        name: "score2",
        value: 0.5,
        reason: "Average performance",
        source: FeedbackScoreSource.Sdk,
      });

      expect(content.feedbackScores).toHaveLength(1);
      expect(content.feedbackScores[0].name).toBe("score1");
    });

    it("should create an instance without optional parameters", () => {
      const mockData = createMockExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
      });

      const content = new ExperimentItemContent({
        ...mockData,
        feedbackScores: [],
      });

      expect(content.id).toBeUndefined();
      expect(content.datasetItemId).toBe(mockData.datasetItemId);
      expect(content.traceId).toBe(mockData.traceId);
      expect(content.datasetItemData).toBeUndefined();
      expect(content.evaluationTaskOutput).toBeUndefined();
      expect(content.feedbackScores).toEqual([]);
    });

    it("should convert REST API ExperimentItemCompare to ExperimentItemContent", () => {
      const restObject = createMockExperimentItemCompare({
        id: "experiment-item-1",
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
        feedbackScores: [
          {
            categoryName: "accuracy",
            name: "correctness",
            value: 0.9,
            reason: "Very accurate response",
            source: FeedbackScoreSource.Sdk,
          },
        ],
      });

      const content =
        ExperimentItemContent.fromRestExperimentItemCompare(restObject);

      expect(content.id).toBe(restObject.id);
      expect(content.datasetItemId).toBe(restObject.datasetItemId);
      expect(content.traceId).toBe(restObject.traceId);
      expect(content.datasetItemData).toEqual(restObject.input);
      expect(content.evaluationTaskOutput).toEqual(restObject.output);
      expect(content.feedbackScores).toHaveLength(1);
      expect(content.feedbackScores[0]).toEqual(restObject.feedbackScores![0]);
    });

    it("should handle REST API object with undefined feedbackScores", () => {
      const restObject = createMockExperimentItemCompare({
        id: "experiment-item-1",
        feedbackScores: undefined,
      });

      const content =
        ExperimentItemContent.fromRestExperimentItemCompare(restObject);

      expect(content.id).toBe(restObject.id);
      expect(content.feedbackScores).toEqual([]);
    });

    it("should handle REST API object with undefined input/output", () => {
      const restObject: ExperimentItemCompare = {
        experimentId: "experiment-123",
        datasetItemId: "dataset-item-test",
        traceId: "trace-test",
      };

      const content =
        ExperimentItemContent.fromRestExperimentItemCompare(restObject);

      expect(content.id).toBe(restObject.id);
      expect(content.datasetItemData).toBeUndefined();
      expect(content.evaluationTaskOutput).toBeUndefined();
    });
  });

  describe("insert", () => {
    it("should insert experiment items with dataset items and traces", async () => {
      const references: ExperimentItemReferences[] = [
        new ExperimentItemReferences(
          createMockExperimentItemReferences({
            datasetItemId: "dataset-item-1",
            traceId: "trace-1",
          })
        ),
        new ExperimentItemReferences(
          createMockExperimentItemReferences({
            datasetItemId: "dataset-item-2",
            traceId: "trace-2",
          })
        ),
      ];

      await experiment.insert(references);

      expect(createExperimentItemsSpy).toHaveBeenCalledWith({
        experimentItems: expect.arrayContaining([
          expect.objectContaining({
            experimentId: experiment.id,
            datasetItemId: "dataset-item-1",
            traceId: "trace-1",
          }),
          expect.objectContaining({
            experimentId: experiment.id,
            datasetItemId: "dataset-item-2",
            traceId: "trace-2",
          }),
        ]),
      });
    });

    it("should do nothing when inserting empty array", async () => {
      await experiment.insert([]);

      expect(createExperimentItemsSpy).not.toHaveBeenCalled();

      expect(loggerInfoSpy).not.toHaveBeenCalledWith(
        expect.stringContaining("Inserted 0 items")
      );
    });

    it("should handle API error during insert", async () => {
      const errorMessage = "API error during insert";
      createExperimentItemsSpy.mockImplementationOnce(() => {
        throw new Error(errorMessage);
      });

      const references = [
        new ExperimentItemReferences(createMockExperimentItemReferences()),
      ];

      await expect(experiment.insert(references)).rejects.toThrow(errorMessage);

      expect(loggerErrorSpy).toHaveBeenCalled();
    });

    it("should batch inserts when exceeding the maximum batch size", async () => {
      const totalItems = 120;
      const references: ExperimentItemReferences[] = Array.from(
        { length: totalItems },
        (_, i) =>
          new ExperimentItemReferences(
            createMockExperimentItemReferences({
              datasetItemId: `dataset-item-${i}`,
              traceId: `trace-${i}`,
            })
          )
      );

      await experiment.insert(references);

      const expectedBatches = Math.ceil(totalItems / DEFAULT_BATCH_SIZE);

      expect(createExperimentItemsSpy).toHaveBeenCalledTimes(expectedBatches);

      expect(
        createExperimentItemsSpy.mock.calls[0][0].experimentItems
      ).toHaveLength(DEFAULT_BATCH_SIZE);
      expect(
        createExperimentItemsSpy.mock.calls[0][0].experimentItems[0]
      ).toEqual(
        expect.objectContaining({
          datasetItemId: "dataset-item-0",
          traceId: "trace-0",
        })
      );

      expect(
        createExperimentItemsSpy.mock.calls[1][0].experimentItems
      ).toHaveLength(DEFAULT_BATCH_SIZE);
      expect(
        createExperimentItemsSpy.mock.calls[1][0].experimentItems[0]
      ).toEqual(
        expect.objectContaining({
          datasetItemId: `dataset-item-${DEFAULT_BATCH_SIZE}`,
          traceId: `trace-${DEFAULT_BATCH_SIZE}`,
        })
      );

      const remainderSize = totalItems - DEFAULT_BATCH_SIZE * 2;
      expect(
        createExperimentItemsSpy.mock.calls[2][0].experimentItems
      ).toHaveLength(remainderSize);
      expect(
        createExperimentItemsSpy.mock.calls[2][0].experimentItems[0]
      ).toEqual(
        expect.objectContaining({
          datasetItemId: `dataset-item-${DEFAULT_BATCH_SIZE * 2}`,
          traceId: `trace-${DEFAULT_BATCH_SIZE * 2}`,
        })
      );
    });

    it("should apply experimentId to all items in the request", async () => {
      const references = Array.from(
        { length: 5 },
        (_, i) =>
          new ExperimentItemReferences(
            createMockExperimentItemReferences({
              datasetItemId: `dataset-${i}`,
              traceId: `trace-${i}`,
            })
          )
      );

      await experiment.insert(references);

      const requestItems =
        createExperimentItemsSpy.mock.calls[0][0].experimentItems;
      requestItems.forEach((item: ExperimentItemCompare) => {
        expect(item.experimentId).toBe(experiment.id);
      });
    });
  });

  describe("getItems", () => {
    it("should request items with correct parameters", async () => {
      const mockExperimentItem = createMockExperimentItemCompareRaw({
        id: "experiment-item-1",
        dataset_item_id: "dataset-item-1",
        trace_id: "trace-1",
        feedback_scores: undefined,
      });

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(JSON.stringify(mockExperimentItem) + "\n")
      );

      const items = await experiment.getItems();

      expect(streamExperimentItemsSpy).toHaveBeenCalledWith({
        experimentName: experiment.name,
        limit: DEFAULT_MAX_RESULTS,
        lastRetrievedId: undefined,
        truncate: false,
      });

      expect(items).toHaveLength(1);
      expect(items[0]).toBeInstanceOf(ExperimentItemContent);
      verifyExperimentItemContent(items[0], {
        id: mockExperimentItem.id ?? undefined,
        datasetItemId: mockExperimentItem.dataset_item_id,
        traceId: mockExperimentItem.trace_id,
        feedbackScores: [],
      });
    });

    it("should respect maxResults parameter", async () => {
      const customLimit = 10;
      await experiment.getItems({ maxResults: customLimit });

      expect(streamExperimentItemsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          limit: customLimit,
        })
      );
    });

    it("should respect truncate parameter", async () => {
      await experiment.getItems({ truncate: true });

      expect(streamExperimentItemsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          truncate: true,
        })
      );
    });

    it("should handle API errors gracefully", async () => {
      const errorMessage = "API error during getItems";
      streamExperimentItemsSpy.mockImplementationOnce(() => {
        throw new Error(errorMessage);
      });

      await expect(experiment.getItems()).rejects.toThrow(errorMessage);

      expect(loggerErrorSpy).toHaveBeenCalled();
    });

    it("should handle pagination with lastRetrievedId", async () => {
      const mockFirstPageItem = createMockExperimentItemCompareRaw({
        id: "experiment-item-1",
        dataset_item_id: "dataset-item-1",
        trace_id: "trace-1",
        feedback_scores: undefined,
      });

      const mockSecondPageItem = createMockExperimentItemCompareRaw({
        id: "experiment-item-2",
        dataset_item_id: "dataset-item-2",
        trace_id: "trace-2",
        feedback_scores: undefined,
      });

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(JSON.stringify(mockFirstPageItem) + "\n")
      );

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(JSON.stringify(mockSecondPageItem) + "\n")
      );

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("")
      );

      const items = await experiment.getItems();

      expect(streamExperimentItemsSpy).toHaveBeenCalledTimes(3);

      expect(
        streamExperimentItemsSpy.mock.calls[0][0].lastRetrievedId
      ).toBeUndefined();

      expect(streamExperimentItemsSpy.mock.calls[1][0].lastRetrievedId).toBe(
        mockFirstPageItem.id
      );

      expect(streamExperimentItemsSpy.mock.calls[2][0].lastRetrievedId).toBe(
        mockSecondPageItem.id
      );

      expect(items).toHaveLength(2);
      verifyExperimentItemContent(items[0], {
        id: mockFirstPageItem.id as string,
        datasetItemId: mockFirstPageItem.dataset_item_id,
        traceId: mockFirstPageItem.trace_id,
        feedbackScores: [],
      });
      verifyExperimentItemContent(items[1], {
        id: mockSecondPageItem.id as string,
        datasetItemId: mockSecondPageItem.dataset_item_id,
        traceId: mockSecondPageItem.trace_id,
        feedbackScores: [],
      });
    });

    it("should stop pagination when maxResults is reached", async () => {
      const mockItems = Array.from({ length: 5 }, (_, i) =>
        createMockExperimentItemCompareRaw({
          id: `experiment-item-${i + 1}`,
          dataset_item_id: `dataset-item-${i + 1}`,
          trace_id: `trace-${i + 1}`,
        })
      );

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(
          mockItems.map((item) => JSON.stringify(item)).join("\n") + "\n"
        )
      );

      const maxResults = 3;
      const items = await experiment.getItems({ maxResults });

      expect(items).toHaveLength(maxResults);
      expect(items[0].id).toBe("experiment-item-1");
      expect(items[2].id).toBe("experiment-item-3");

      expect(loggerInfoSpy).toHaveBeenCalledWith(
        expect.stringContaining(`Retrieved ${maxResults} items`)
      );
    });

    it("should handle empty responses", async () => {
      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("[]")
      );

      const items = await experiment.getItems();

      expect(items).toHaveLength(0);

      expect(loggerInfoSpy).toHaveBeenCalledWith(
        expect.stringContaining("Retrieved 0 items")
      );
    });

    it("should handle malformed JSON in stream response", async () => {
      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("{invalid-json}")
      );

      const items = await experiment.getItems();

      expect(items).toHaveLength(0);
    });

    it("should convert all REST API items to ExperimentItemContent objects", async () => {
      const mockItems = [
        createMockExperimentItemCompareRaw({
          id: "item-1",
          feedback_scores: [
            {
              category_name: "quality",
              name: "relevance",
              value: 0.9,
              reason: "Highly relevant",
              source: FeedbackScoreSource.Sdk,
            },
          ],
        }),

        createMockExperimentItemCompareRaw({
          id: "item-2",
          feedback_scores: undefined,
        }),

        createMockExperimentItemCompareRaw({
          id: "item-3",
          input: { left: [], right: [] },
          output: { left: [], right: [] },
        }),
      ];

      streamExperimentItemsSpy.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(
          mockItems.map((item) => JSON.stringify(item)).join("\n") + "\n"
        )
      );

      const items = await experiment.getItems();

      expect(items).toHaveLength(mockItems.length);

      items.forEach((item, index) => {
        expect(item).toBeInstanceOf(ExperimentItemContent);
        expect(item.id).toBe(mockItems[index].id);
      });

      expect(items[0].feedbackScores).toHaveLength(1);
      expect(items[1].feedbackScores).toEqual([]);
    });
  });
});
