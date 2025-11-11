import { useCallback } from "react";
import isObject from "lodash/isObject";
import { DatasetItem } from "@/types/datasets";
import { useFetchDatasetItem } from "@/api/datasets/useDatasetItemById";

const IMAGE_PLACEHOLDER_REGEX = /\[image(?:_\d+)?\]/i;

const containsImagePlaceholder = (value: unknown): boolean => {
  if (typeof value === "string") {
    return IMAGE_PLACEHOLDER_REGEX.test(value);
  }

  if (Array.isArray(value)) {
    return value.some(containsImagePlaceholder);
  }

  if (isObject(value)) {
    return Object.values(value).some(containsImagePlaceholder);
  }

  return false;
};

export function useHydrateDatasetItemData() {
  const fetchDatasetItem = useFetchDatasetItem();

  return useCallback(
    async (datasetItem?: DatasetItem): Promise<DatasetItem["data"]> => {
      if (!datasetItem) {
        return {};
      }

      let hydratedData = datasetItem.data ?? {};

      if (containsImagePlaceholder(hydratedData)) {
        try {
          const fullDatasetItem = await fetchDatasetItem({
            datasetItemId: datasetItem.id,
          });

          if (fullDatasetItem?.data) {
            hydratedData = fullDatasetItem.data;
          }
        } catch (error) {
          // Silently handle network errors during hydration - these are expected for large assets
          // The hydration will retry automatically via React Query's retry mechanism
          // No need to log as these are normal batch networking operations
        }
      }

      return hydratedData;
    },
    [fetchDatasetItem],
  );
}
