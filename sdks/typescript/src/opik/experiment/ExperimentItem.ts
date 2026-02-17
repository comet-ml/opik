import {
  FeedbackScore,
  JsonListStringCompare,
  ExperimentItemCompare,
} from "@/rest_api/api";

/**
 * References to a dataset item and trace in an experiment.
 */
export class ExperimentItemReferences {
  public readonly datasetItemId: string;
  public readonly traceId: string;
  public readonly projectName?: string;

  constructor(params: {
    datasetItemId: string;
    traceId: string;
    projectName?: string;
  }) {
    if (!params.datasetItemId) {
      throw new Error("datasetItemId is required");
    }
    if (!params.traceId) {
      throw new Error("traceId is required");
    }
    this.datasetItemId = params.datasetItemId;
    this.traceId = params.traceId;
    this.projectName = params.projectName;
  }
}

/**
 * Content of an experiment item including evaluation data and feedback scores.
 */
export class ExperimentItemContent {
  public readonly id?: string;
  public readonly datasetItemId: string;
  public readonly traceId: string;
  public readonly datasetItemData?: JsonListStringCompare;
  public readonly evaluationTaskOutput?: JsonListStringCompare;
  public readonly feedbackScores: FeedbackScore[];

  constructor(params: {
    id?: string;
    datasetItemId: string;
    traceId: string;
    datasetItemData?: JsonListStringCompare;
    evaluationTaskOutput?: JsonListStringCompare;
    feedbackScores: FeedbackScore[];
  }) {
    this.id = params.id;
    this.datasetItemId = params.datasetItemId;
    this.traceId = params.traceId;
    this.datasetItemData = params.datasetItemData;
    this.evaluationTaskOutput = params.evaluationTaskOutput;
    this.feedbackScores = [...params.feedbackScores];
  }

  /**
   * Creates an ExperimentItemContent from a REST API ExperimentItemCompare object.
   *
   * @param value The REST API ExperimentItemCompare object
   * @returns A new ExperimentItemContent instance
   */
  public static fromRestExperimentItemCompare(
    value: ExperimentItemCompare
  ): ExperimentItemContent {
    const feedbackScores: FeedbackScore[] =
      value.feedbackScores?.map((restFeedbackScore) => ({
        categoryName: restFeedbackScore.categoryName,
        name: restFeedbackScore.name,
        reason: restFeedbackScore.reason,
        value: restFeedbackScore.value,
        source: restFeedbackScore.source,
      })) ?? [];

    return new ExperimentItemContent({
      id: value.id,
      traceId: value.traceId,
      datasetItemId: value.datasetItemId,
      datasetItemData: value.input,
      evaluationTaskOutput: value.output,
      feedbackScores: feedbackScores,
    });
  }
}
