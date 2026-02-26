import { useCallback } from "react";
import isObject from "lodash/isObject";
import { DatasetItem } from "@/types/datasets";
import { useFetchDatasetItem } from "@/api/datasets/useDatasetItemById";

// Matches media placeholders ([image], [video_0], [audio_0]) and
// truncated data URIs (with optional leading " from ClickHouse JSON encoding)
const TRUNCATED_MEDIA_REGEX =
  /\[(image|video|audio)(?:_\d+)?\]|^"?data:(image|video|audio)\//i;

const containsMediaPlaceholder = (value: unknown): boolean => {
  if (typeof value === "string") {
    return TRUNCATED_MEDIA_REGEX.test(value);
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
