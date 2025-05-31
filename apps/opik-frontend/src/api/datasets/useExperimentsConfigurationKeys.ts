import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { flattie } from "flattie";
import isObject from "lodash/isObject";
import { COLUMN_TYPE, DynamicColumn } from "@/types/shared";
import useExperimentsList from "./useExperimentsList";
import { EXPERIMENT_TYPE } from "@/types/datasets";

export type UseExperimentsConfigurationKeysParams = {
  workspaceName: string;
  datasetId?: string;
  promptId?: string;
};

export type UseExperimentsConfigurationKeysResponse = {
  keys: DynamicColumn[];
};

// Helper function to determine column type based on value
const getColumnType = (value: unknown): COLUMN_TYPE => {
  if (typeof value === "number") return COLUMN_TYPE.number;
  if (typeof value === "boolean") return COLUMN_TYPE.string;
  if (Array.isArray(value)) return COLUMN_TYPE.list;
  if (isObject(value)) return COLUMN_TYPE.dictionary;
  return COLUMN_TYPE.string;
};

export default function useExperimentsConfigurationKeys(
  params: UseExperimentsConfigurationKeysParams,
  options?: Record<string, unknown>,
) {
  // Fetch experiments to extract configuration keys from their metadata
  const { data: experimentsData, isPending } = useExperimentsList(
    {
      workspaceName: params.workspaceName,
      datasetId: params.datasetId,
      promptId: params.promptId,
      types: [EXPERIMENT_TYPE.REGULAR],
      page: 1,
      size: 100, // Get a reasonable sample to extract keys
    },
    {
      placeholderData: keepPreviousData,
      ...options,
    },
  );

  const data = useMemo(() => {
    if (!experimentsData?.content) {
      return { keys: [] };
    }

    // Extract all unique keys from experiment metadata
    const allKeys = new Set<string>();
    const keyTypes = new Map<string, COLUMN_TYPE>();

    experimentsData.content.forEach((experiment) => {
      if (experiment.metadata && isObject(experiment.metadata)) {
        const flattenedMetadata = flattie(experiment.metadata, ".", true);

        Object.entries(flattenedMetadata).forEach(([key, value]) => {
          allKeys.add(key);

          // Determine column type if not already set
          if (!keyTypes.has(key) && value !== null && value !== undefined) {
            keyTypes.set(key, getColumnType(value));
          }
        });
      }
    });

    // Convert to DynamicColumn array
    const keys: DynamicColumn[] = Array.from(allKeys)
      .sort()
      .map((key) => ({
        id: key,
        label: key,
        columnType: keyTypes.get(key) || COLUMN_TYPE.string,
      }));

    return { keys };
  }, [experimentsData]);

  return {
    data,
    isPending,
  };
}
