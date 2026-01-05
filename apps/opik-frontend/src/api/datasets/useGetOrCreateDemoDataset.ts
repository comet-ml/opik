import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";
import {
  DemoDatasetItem,
  OptimizationTemplate,
} from "@/constants/optimizations";

/**
 * Hook to create a demo dataset for optimization templates.
 * Creates dataset and populates with items. If dataset already exists but is empty,
 * it will be populated with items.
 */
const useGetOrCreateDemoDataset = () => {
  const queryClient = useQueryClient();

  const getOrCreateDataset = useCallback(
    async (
      template: OptimizationTemplate,
      workspaceName: string,
    ): Promise<Dataset | null> => {
      const datasetName = template.studio_config?.dataset_name;
      const datasetItems = template.dataset_items;

      if (!datasetName || !datasetItems?.length) {
        return null;
      }

      try {
        // Try to create dataset (will fail if exists, which is fine)
        let newDataset = null;
        try {
          const { data } = await api.post(DATASETS_REST_ENDPOINT, {
            name: datasetName,
          });
          newDataset = data;
        } catch {
          // Dataset likely already exists, continue to fetch it
        }

        // Get dataset by name to get the ID (works whether we just created it or it existed)
        const { data: datasets } = await api.get(DATASETS_REST_ENDPOINT, {
          params: { workspace_name: workspaceName, name: datasetName, size: 1 },
        });

        const dataset = newDataset || datasets?.content?.[0];
        if (!dataset?.id) return null;

        // add items if dataset was newly created OR if existing dataset is empty
        const shouldAddItems =
          newDataset || (dataset && dataset.dataset_items_count === 0);

        if (shouldAddItems) {
          await api.put(`${DATASETS_REST_ENDPOINT}items`, {
            dataset_id: dataset.id,
            items: datasetItems.map((item: DemoDatasetItem) => ({
              data: item,
              source: DATASET_ITEM_SOURCE.manual,
            })),
          });

          queryClient.invalidateQueries({ queryKey: ["datasets"] });
        }

        return dataset;
      } catch (err) {
        console.error("Error creating demo dataset:", err);
        return null;
      }
    },
    [queryClient],
  );

  return { getOrCreateDataset };
};

export default useGetOrCreateDemoDataset;
