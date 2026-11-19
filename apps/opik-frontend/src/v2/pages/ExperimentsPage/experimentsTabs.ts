import { EXPERIMENTS_PAGE_TAB } from "@/constants/experiments";
import { EVALUATION_METHOD } from "@/types/datasets";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";

export const EXPERIMENTS_TABS = [
  {
    key: EXPERIMENTS_PAGE_TAB.TEST_SUITES,
    label: "Test suites",
    evaluationMethod: EVALUATION_METHOD.TEST_SUITE,
  },
  {
    key: EXPERIMENTS_PAGE_TAB.DATASETS,
    label: "Datasets",
    evaluationMethod: EVALUATION_METHOD.DATASET,
  },
];

export type ExperimentsTab = (typeof EXPERIMENTS_TABS)[number];

export const findExperimentsTab = (key: unknown) =>
  EXPERIMENTS_TABS.find((tab) => tab.key === key);

export const getEvaluationMethodFilter = (
  evaluationMethod: EVALUATION_METHOD,
): Filter => ({
  id: "evaluation_method",
  field: "evaluation_method",
  type: COLUMN_TYPE.string,
  // Legacy experiments store evaluation_method 'unknown', so the Datasets tab
  // matches everything that is not a test suite rather than = 'dataset'.
  operator: evaluationMethod === EVALUATION_METHOD.TEST_SUITE ? "=" : "!=",
  value: EVALUATION_METHOD.TEST_SUITE,
});
