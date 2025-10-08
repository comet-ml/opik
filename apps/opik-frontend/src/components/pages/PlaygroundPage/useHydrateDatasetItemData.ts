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
