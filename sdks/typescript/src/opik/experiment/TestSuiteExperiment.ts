import { OpikClient } from "@/client/Client";
import { Experiment, ExperimentData } from "./Experiment";
import type { AssertionScoreAveragePublic } from "@/rest_api/api/types/AssertionScoreAveragePublic";

export interface TestSuiteExperimentData extends ExperimentData {
  passRate?: number;
  passedCount?: number;
  totalCount?: number;
  assertionScores?: AssertionScoreAveragePublic[];
}

/**
 * Represents an experiment run against a test suite. Extends `Experiment`
 * with the aggregate assertion statistics the backend populates only for
 * evaluation-suite experiments (null/undefined for regular dataset experiments).
 */
export class TestSuiteExperiment extends Experiment {
  public readonly passRate?: number;
  public readonly passedCount?: number;
  public readonly totalCount?: number;
  public readonly assertionScores?: AssertionScoreAveragePublic[];

  constructor(data: TestSuiteExperimentData, opik: OpikClient) {
    super(data, opik);
    this.passRate = data.passRate;
    this.passedCount = data.passedCount;
    this.totalCount = data.totalCount;
    this.assertionScores = data.assertionScores;
  }
}
