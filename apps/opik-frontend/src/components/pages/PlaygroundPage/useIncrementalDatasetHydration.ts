import { useEffect, useRef, useState } from "react";
import { DatasetItem } from "@/types/datasets";
import { useHydrateDatasetItemData } from "@/components/pages/PlaygroundPage/useHydrateDatasetItemData";

export function useIncrementalDatasetHydration(datasetItems: DatasetItem[]): {
  hydratedItems: DatasetItem[];
  isHydrating: boolean;
} {
  const hydrateDatasetItemData = useHydrateDatasetItemData();
  const [hydratedItems, setHydratedItems] = useState<DatasetItem[]>([]);
  const [isHydrating, setIsHydrating] = useState(false);
  const cancelledRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;

    if (datasetItems.length === 0) {
      setHydratedItems([]);
      setIsHydrating(false);
      return;
    }

    const hydrateItems = async () => {
      setIsHydrating(true);
      setHydratedItems([]);

      for (let i = 0; i < datasetItems.length; i++) {
        if (cancelledRef.current) return;

        const hydratedData = await hydrateDatasetItemData(datasetItems[i]);

        if (cancelledRef.current) return;

        setHydratedItems((prev) => [
          ...prev,
          { ...datasetItems[i], data: hydratedData },
        ]);
      }

      setIsHydrating(false);
    };

    hydrateItems();

    return () => {
      cancelledRef.current = true;
    };
  }, [datasetItems, hydrateDatasetItemData]);

  return { hydratedItems, isHydrating };
}
