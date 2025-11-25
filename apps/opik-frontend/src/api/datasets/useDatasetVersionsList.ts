import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";

type UseDatasetVersionsListParams = {
  datasetId: string;
  page: number;
  size: number;
};

export type UseDatasetVersionsListResponse = {
  content: DatasetVersion[];
  total: number;
};

// Feature flag to toggle between mock and real API data
const USE_MOCK_DATA = false;

// Mock data generator
const generateMockVersions = (datasetId: string): DatasetVersion[] => {
  return [
    {
      id: "version-1",
      dataset_id: datasetId,
      version_hash: "v2.0.0",
      items_count: 1500,
      items_added: 50,
      items_modified: 120,
      items_deleted: 10,
      change_description:
        "Major update with new training samples and quality improvements",
      metadata: {
        model_version: "gpt-4",
        quality_score: 0.95,
        reviewed: true,
      },
      tags: ["latest", "production"],
      created_at: new Date("2025-11-20T14:30:00Z").toISOString(),
      created_by: "john.smith@company.com",
      last_updated_at: new Date("2025-11-20T14:30:00Z").toISOString(),
      last_updated_by: "john.smith@company.com",
    },
    {
      id: "version-2",
      dataset_id: datasetId,
      version_hash: "v1.5.2",
      items_count: 1440,
      items_added: 0,
      items_modified: 85,
      items_deleted: 5,
      change_description: "Bug fixes and data cleanup for edge cases",
      metadata: {
        model_version: "gpt-3.5-turbo",
        quality_score: 0.92,
      },
      tags: ["staging"],
      created_at: new Date("2025-11-15T10:15:00Z").toISOString(),
      created_by: "sarah.jones@company.com",
      last_updated_at: new Date("2025-11-15T10:15:00Z").toISOString(),
      last_updated_by: "sarah.jones@company.com",
    },
    {
      id: "version-3",
      dataset_id: datasetId,
      version_hash: "v1.5.1",
      items_count: 1360,
      items_added: 200,
      items_modified: 0,
      items_deleted: 0,
      change_description:
        "Added new conversation examples from production logs",
      metadata: {
        source: "production_logs",
        date_range: "2025-10-01_to_2025-10-31",
      },
      tags: [],
      created_at: new Date("2025-11-10T09:00:00Z").toISOString(),
      created_by: "data-pipeline@company.com",
      last_updated_at: new Date("2025-11-10T09:00:00Z").toISOString(),
      last_updated_by: "data-pipeline@company.com",
    },
    {
      id: "version-4",
      dataset_id: datasetId,
      version_hash: "v1.4.0",
      items_count: 1160,
      items_added: 160,
      items_modified: 45,
      items_deleted: 20,
      change_description: "Quarterly dataset refresh with updated annotations",
      metadata: {
        quarter: "Q4-2025",
        annotation_version: "2.1",
      },
      tags: ["archived"],
      created_at: new Date("2025-11-01T08:00:00Z").toISOString(),
      created_by: "alex.chen@company.com",
      last_updated_at: new Date("2025-11-01T08:00:00Z").toISOString(),
      last_updated_by: "alex.chen@company.com",
    },
    {
      id: "version-5",
      dataset_id: datasetId,
      version_hash: "v1.3.0",
      items_count: 1015,
      items_added: 115,
      items_modified: 0,
      items_deleted: 0,
      change_description: "Initial production release with validated data",
      metadata: {
        validation_passed: true,
        validators: ["validator-1", "validator-2"],
      },
      tags: ["archived"],
      created_at: new Date("2025-10-15T12:00:00Z").toISOString(),
      created_by: "maria.garcia@company.com",
      last_updated_at: new Date("2025-10-15T12:00:00Z").toISOString(),
      last_updated_by: "maria.garcia@company.com",
    },
    {
      id: "version-6",
      dataset_id: datasetId,
      version_hash: "v1.2.0",
      items_count: 900,
      items_added: 0,
      items_modified: 150,
      items_deleted: 50,
      change_description:
        "Quality improvements and removal of duplicate entries",
      metadata: {
        deduplication_run: true,
        duplicates_removed: 50,
      },
      tags: ["archived"],
      created_at: new Date("2025-10-05T16:45:00Z").toISOString(),
      created_by: "john.smith@company.com",
      last_updated_at: new Date("2025-10-05T16:45:00Z").toISOString(),
      last_updated_by: "john.smith@company.com",
    },
    {
      id: "version-7",
      dataset_id: datasetId,
      version_hash: "v1.0.0",
      items_count: 1000,
      items_added: 1000,
      items_modified: 0,
      items_deleted: 0,
      change_description: "Initial dataset version with baseline examples",
      metadata: {
        baseline: true,
        source: "manual_curation",
      },
      tags: ["archived"],
      created_at: new Date("2025-09-20T10:00:00Z").toISOString(),
      created_by: "admin@company.com",
      last_updated_at: new Date("2025-09-20T10:00:00Z").toISOString(),
      last_updated_by: "admin@company.com",
    },
  ];
};

const getDatasetVersionsList = async (
  { signal }: QueryFunctionContext,
  { datasetId, size, page }: UseDatasetVersionsListParams,
) => {
  if (USE_MOCK_DATA) {
    // Simulate API delay
    await new Promise((resolve) => setTimeout(resolve, 300));

    const allVersions = generateMockVersions(datasetId);
    const total = allVersions.length;

    // Apply pagination
    const startIndex = (page - 1) * size;
    const endIndex = startIndex + size;
    const paginatedVersions = allVersions.slice(startIndex, endIndex);

    return {
      content: paginatedVersions,
      total,
    };
  }

  // Real API call (to be used once backend is merged)
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/versions`,
    {
      signal,
      params: {
        size,
        page,
      },
    },
  );

  return data;
};

export default function useDatasetVersionsList(
  params: UseDatasetVersionsListParams,
  options?: QueryConfig<UseDatasetVersionsListResponse>,
) {
  return useQuery({
    queryKey: ["dataset-versions", params],
    queryFn: (context) => getDatasetVersionsList(context, params),
    ...options,
  });
}
