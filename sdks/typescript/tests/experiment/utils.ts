import { Experiment } from "@/experiment/Experiment";
import {
  ExperimentItemCompare,
  ExperimentType,
  FeedbackScoreSource,
} from "@/rest_api/api/types";
import { serialization } from "@/rest_api";
import {
  ExperimentItemContent,
  ExperimentItemReferences,
} from "@/experiment/ExperimentItem";

export interface MockExperimentData {
  id: string;
  name: string;
  datasetName: string;
  type?: ExperimentType;
}

export const createMockExperiment = (
  overrides: Partial<serialization.ExperimentPublic.Raw> = {}
): serialization.ExperimentPublic.Raw => ({
  id: "experiment-123",
  name: "test-experiment",
  dataset_name: "test-dataset",
  type: "regular" as ExperimentType,
  ...overrides,
});

export const verifyExperiment = (
  actual: Experiment,
  expected: serialization.ExperimentPublic.Raw
) => {
  expect(actual).toBeInstanceOf(Experiment);
  expect(actual.id).toBe(expected.id);
  expect(actual.name).toBe(expected.name);
  expect(actual.datasetName).toBe(expected.dataset_name);
};

export const createMockExperimentItemReferences = (
  overrides: Partial<ExperimentItemReferences> = {}
): ExperimentItemReferences => ({
  datasetItemId: "dataset-item-test",
  traceId: "trace-test",
  ...overrides,
});

export const createMockExperimentItemContent = (
  overrides: Partial<ExperimentItemContent> = {}
): ExperimentItemContent => ({
  id: "content-test",
  datasetItemId: "dataset-item-test",
  traceId: "trace-test",
  datasetItemData: { left: ["test-input-left"], right: ["test-input-right"] },
  evaluationTaskOutput: {
    left: ["test-output-left"],
    right: ["test-output-right"],
  },
  feedbackScores: [
    {
      categoryName: "test-category",
      name: "test-score",
      value: 0.9,
      reason: "Test reason",
      source: FeedbackScoreSource.Sdk,
    },
  ],
  ...overrides,
});

export const createMockExperimentItemCompare = (
  overrides: Partial<ExperimentItemCompare> = {}
): ExperimentItemCompare => ({
  experimentId: "experiment-item-test",
  datasetItemId: "dataset-item-test",
  traceId: "trace-test",
  input: { left: ["test-input-left"], right: ["test-input-right"] },
  output: { left: ["test-output-left"], right: ["test-output-right"] },
  feedbackScores: [
    {
      categoryName: "test-category",
      name: "test-score",
      value: 0.9,
      reason: "Test reason",
      source: FeedbackScoreSource.Sdk,
    },
  ],
  ...overrides,
});

export const createMockExperimentItemCompareRaw = (
  overrides: Partial<serialization.ExperimentItemCompare.Raw> = {}
): serialization.ExperimentItemCompare.Raw => ({
  experiment_id: "experiment-item-test",
  dataset_item_id: "dataset-item-test",
  trace_id: "trace-test",
  input: { left: ["test-input-left"], right: ["test-input-right"] },
  output: { left: ["test-output-left"], right: ["test-output-right"] },
  feedback_scores: [
    {
      category_name: "test-category",
      name: "test-score",
      value: 0.9,
      reason: "Test reason",
      source: FeedbackScoreSource.Sdk,
    },
  ],
  ...overrides,
});

export const verifyExperimentItemContent = (
  actual: ExperimentItemContent,
  expected: ExperimentItemContent
) => {
  expect(actual).toBeInstanceOf(ExperimentItemContent);
  if (expected.id) expect(actual.id).toBe(expected.id);
  expect(actual.datasetItemId).toBe(expected.datasetItemId);
  expect(actual.traceId).toBe(expected.traceId);

  if (expected.datasetItemData) {
    expect(actual.datasetItemData).toEqual(expected.datasetItemData);
  }

  if (expected.evaluationTaskOutput) {
    expect(actual.evaluationTaskOutput).toEqual(expected.evaluationTaskOutput);
  }

  if (expected.feedbackScores) {
    expect(actual.feedbackScores).toEqual(expected.feedbackScores);
  }
};
