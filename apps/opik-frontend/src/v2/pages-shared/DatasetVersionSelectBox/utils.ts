import { Dataset, DatasetVersion } from "@/types/datasets";

export const formatDisplayValue = (
  datasetId: string | null,
  dataset: Dataset | undefined,
  version: DatasetVersion | undefined,
): string => {
  if (!datasetId) return "";
  const datasetName = dataset?.name || "";
  const versionName = version?.version_name || "";
  return versionName ? `${datasetName} / ${versionName}` : datasetName;
};
