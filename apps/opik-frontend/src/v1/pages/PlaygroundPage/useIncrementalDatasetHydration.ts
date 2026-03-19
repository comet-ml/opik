import { useEffect, useRef, useState } from "react";
import { DatasetItem } from "@/types/datasets";
import { useHydrateDatasetItemData } from "@/v1/pages/PlaygroundPage/useHydrateDatasetItemData";

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

    setHydratedItems(datasetItems);
    setIsHydrating(true);

    const hydrateItems = async () => {
      for (let i = 0; i < datasetItems.length; i++) {
        if (cancelledRef.current) return;

        const hydratedData = await hydrateDatasetItemData(datasetItems[i]);

        if (cancelledRef.current) return;

        setHydratedItems((prev) =>
          prev.map((item, idx) =>
            idx === i ? { ...item, data: hydratedData } : item,
          ),
        );
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
