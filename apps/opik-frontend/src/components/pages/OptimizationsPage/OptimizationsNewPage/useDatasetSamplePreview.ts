import { useMemo } from "react";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";

type UseDatasetSamplePreviewParams = {
  datasetId: string | null | undefined;
};

type UseDatasetSamplePreviewReturn = {
  datasetSample: Record<string, unknown> | null;
  datasetVariables: string[];
  variablesHint: string;
  isLoading: boolean;
};

const useDatasetSamplePreview = ({
  datasetId,
}: UseDatasetSamplePreviewParams): UseDatasetSamplePreviewReturn => {
  const { data: datasetItemsData, isLoading } = useDatasetItemsList(
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

  const variablesHint = useMemo(() => {
    if (datasetVariables.length === 0) return "";
    return `Use {{variable_name}} syntax to reference dataset variables in your prompt: ${datasetVariables
      .map((key) => `{{${key}}}`)
      .join(", ")}`;
  }, [datasetVariables]);

  return {
    datasetSample,
    datasetVariables,
    variablesHint,
    isLoading,
  };
};

export default useDatasetSamplePreview;
