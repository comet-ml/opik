import { useEffect, useRef, useState } from "react";
import { DatasetItem } from "@/types/datasets";
import { useHydrateDatasetItemData } from "@/v2/pages/PlaygroundPage/useHydrateDatasetItemData";
import { containsTruncatedMedia } from "@/lib/media";

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

    // Only items carrying truncated media need a round-trip — that is the sole
    // condition under which hydrateDatasetItemData fetches anything. Selecting
    // them up front means the loop below runs once per *media* item rather than
    // once per row, and each pass rebuilt the whole array, so a 1000-row text
    // dataset was doing ~1M array copies to arrive back at the data it started
    // with.
    const indexesToHydrate = datasetItems.reduce<number[]>(
      (acc, item, index) => {
        if (containsTruncatedMedia(item.data)) {
          acc.push(index);
        }
        return acc;
      },
      [],
    );

    if (indexesToHydrate.length === 0) {
      setIsHydrating(false);
      return;
    }

    setIsHydrating(true);

    const hydrateItems = async () => {
      for (const index of indexesToHydrate) {
        if (cancelledRef.current) return;

        const hydratedData = await hydrateDatasetItemData(datasetItems[index]);

        if (cancelledRef.current) return;

        setHydratedItems((prev) =>
          prev.map((item, idx) =>
            idx === index ? { ...item, data: hydratedData } : item,
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
