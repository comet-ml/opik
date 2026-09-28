import { useMemo } from "react";

import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";

type UseDatasetColumnsParams = {
  datasetId?: string;
  enabled: boolean;
};

const useDatasetColumns = ({ datasetId, enabled }: UseDatasetColumnsParams) => {
  const { data } = useDatasetItemsList(
    { datasetId: datasetId ?? "", page: 1, size: 1, truncate: true },
    { enabled: enabled && Boolean(datasetId) },
  );

  const columnNames = useMemo(
    () => (data?.columns ?? []).map((column) => column.name),
    [data?.columns],
  );

  return { columnNames, itemCount: data?.total ?? 0 };
};

export default useDatasetColumns;
