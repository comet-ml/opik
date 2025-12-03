import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset, DatasetVersion } from "@/types/datasets";

// Toggle for UI debugging - set to true to mock latest_version data
const USE_MOCK_LATEST_VERSION = true;

const generateMockLatestVersion = (datasetId: string): DatasetVersion => ({
  id: "mock-version-id",
  dataset_id: datasetId,
  version_hash: "v6",
  items_total: 100,
  items_added: 10,
  items_modified: 5,
  items_deleted: 2,
  change_description: "Mock version for UI debugging",
  tags: ["prod"],
  created_at: new Date().toISOString(),
  created_by: "mock-user@example.com",
  last_updated_at: new Date().toISOString(),
  last_updated_by: "mock-user@example.com",
});

const getDatasetById = async (
  { signal }: QueryFunctionContext,
  { datasetId }: UseDatasetByIdParams,
): Promise<Dataset> => {
  const { data } = await api.get(DATASETS_REST_ENDPOINT + datasetId, {
    signal,
  });

  // Add mock latest_version for UI debugging (will be removed when BE implements)
  if (USE_MOCK_LATEST_VERSION && !data.latest_version) {
    return {
      ...data,
      latest_version: generateMockLatestVersion(datasetId),
    };
  }

  return data;
};

type UseDatasetByIdParams = {
  datasetId: string;
};

export default function useDatasetById(
  params: UseDatasetByIdParams,
  options?: QueryConfig<Dataset>,
) {
  return useQuery({
    queryKey: ["dataset", params],
    queryFn: (context) => getDatasetById(context, params),
    ...options,
  });
}
