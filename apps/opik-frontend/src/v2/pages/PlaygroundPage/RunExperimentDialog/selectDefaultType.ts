import { DATASET_TYPE } from "@/types/datasets";

export interface SelectDefaultTypeArgs {
  hasDatasets: boolean;
  hasTestSuites: boolean;
  currentDatasetType?: DATASET_TYPE | null;
}

export const selectDefaultType = ({
  hasDatasets,
  hasTestSuites,
  currentDatasetType,
}: SelectDefaultTypeArgs): DATASET_TYPE => {
  if (currentDatasetType) return currentDatasetType;
  if (hasDatasets && !hasTestSuites) return DATASET_TYPE.DATASET;
  if (!hasDatasets && hasTestSuites) return DATASET_TYPE.TEST_SUITE;
  return DATASET_TYPE.DATASET;
};
