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

  const datasetVariables = useMemo(() => {
    if (!datasetSample) return [];
    return Object.keys(datasetSample);
  }, [datasetSample]);

  return {
    datasetSample,
    datasetVariables,
  };
};

export default useDatasetSamplePreview;
