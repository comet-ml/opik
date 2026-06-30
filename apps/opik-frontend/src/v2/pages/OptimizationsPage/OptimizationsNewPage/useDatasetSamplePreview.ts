import { useMemo } from "react";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";

type UseDatasetSamplePreviewParams = {
  datasetId: string | null | undefined;
};

type UseDatasetSamplePreviewReturn = {
  datasetSample: Record<string, unknown> | null;
  datasetVariables: string[];
};

const useDatasetSamplePreview = ({
  datasetId,
}: UseDatasetSamplePreviewParams): UseDatasetSamplePreviewReturn => {
  const { data: datasetItemsData } = useDatasetItemsList(
    {
      datasetId: datasetId || "",
      page: 1,
      size: 1,
      truncate: true,
    },
    {
      enabled: Boolean(datasetId),
    },
  );

  const datasetSample = useMemo(() => {
    if (!datasetItemsData?.content?.[0]) return null;
    return datasetItemsData.content[0].data as Record<string, unknown>;
  }, [datasetItemsData]);

  // Use the dataset's full column set (computed server-side across every row),
  // not the keys of the single sampled item — otherwise a column missing from
  // row 1 but present in others would be wrongly flagged as a missing variable.
  const datasetVariables = useMemo(
    () => (datasetItemsData?.columns ?? []).map((column) => column.name),
    [datasetItemsData?.columns],
  );

  return {
    datasetSample,
    datasetVariables,
  };
};

export default useDatasetSamplePreview;
