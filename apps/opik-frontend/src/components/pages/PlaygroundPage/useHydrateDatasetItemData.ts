import { useCallback } from "react";
import isObject from "lodash/isObject";
import { DatasetItem } from "@/types/datasets";
import { useFetchDatasetItem } from "@/api/datasets/useDatasetItemById";

const MEDIA_PLACEHOLDER_REGEX = /\[(?:image|video)(?:_\d+)?\]/i;

const containsMediaPlaceholder = (value: unknown): boolean => {
  if (typeof value === "string") {
    return MEDIA_PLACEHOLDER_REGEX.test(value);
  }

  if (Array.isArray(value)) {
    return value.some(containsMediaPlaceholder);
  }

  if (isObject(value)) {
    return Object.values(value).some(containsMediaPlaceholder);
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

      if (containsMediaPlaceholder(hydratedData)) {
        try {
          const fullDatasetItem = await fetchDatasetItem({
            datasetItemId: datasetItem.id,
          });

          if (fullDatasetItem?.data) {
            hydratedData = fullDatasetItem.data;
          }
        } catch (error) {
          console.warn(
            "Failed to hydrate dataset item data with full payload",
            error,
          );
        }
      }

      return hydratedData;
    },
    [fetchDatasetItem],
  );
}
