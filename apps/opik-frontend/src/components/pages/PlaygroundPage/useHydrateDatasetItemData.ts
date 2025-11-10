import { useCallback } from "react";
import isObject from "lodash/isObject";
import { DatasetItem } from "@/types/datasets";
import { useFetchDatasetItem } from "@/api/datasets/useDatasetItemById";

const MEDIA_PLACEHOLDER_REGEX = /\[(?:image|video)(?:_\d+)?\]/i;
const BASE64_MEDIA_REGEX = /data:(?:image|video)\/[^;]+;base64,/i;

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

const containsBase64Media = (value: unknown): boolean => {
  if (typeof value === "string") {
    return BASE64_MEDIA_REGEX.test(value);
  }

  if (Array.isArray(value)) {
    return value.some(containsBase64Media);
  }

  if (isObject(value)) {
    return Object.values(value).some(containsBase64Media);
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

      // Fetch full dataset item if it contains media placeholders or base64 media content
      // Base64 media content may be truncated, so we need to fetch the full version
      if (
        containsMediaPlaceholder(hydratedData) ||
        containsBase64Media(hydratedData)
      ) {
        try {
          const fullDatasetItem = await fetchDatasetItem({
            datasetItemId: datasetItem.id,
            truncate: false,
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
