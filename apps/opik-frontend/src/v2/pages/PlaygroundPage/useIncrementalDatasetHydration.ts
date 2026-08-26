import { useEffect, useState } from "react";
import { DatasetItem } from "@/types/datasets";
import { useHydrateDatasetItemData } from "@/v2/pages/PlaygroundPage/useHydrateDatasetItemData";
import { containsTruncatedMedia } from "@/lib/media";

export function useIncrementalDatasetHydration(
  datasetItems: DatasetItem[],
): DatasetItem[] {
  const hydrateDatasetItemData = useHydrateDatasetItemData();
  const [hydratedItems, setHydratedItems] = useState<DatasetItem[]>([]);

  useEffect(() => {
    // Scoped to this effect run rather than a shared ref. React runs the previous
    // run's cleanup before this body, so an in-flight hydration from an earlier
    // dataset observes its own `cancelled === true` and stops — even when this run
    // returns early below and installs no cleanup of its own. A shared ref instead
    // got reset here on every run, un-cancelling the previous run's loop; its
    // continuation would then write the old dataset's data into the new array at a
    // positional index. Also covers unmount.
    let cancelled = false;

    if (datasetItems.length === 0) {
      setHydratedItems([]);
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
      return;
    }

    const hydrateItems = async () => {
      for (const index of indexesToHydrate) {
        if (cancelled) return;

        const hydratedData = await hydrateDatasetItemData(datasetItems[index]);

        if (cancelled) return;

        setHydratedItems((prev) =>
          prev.map((item, idx) =>
            idx === index ? { ...item, data: hydratedData } : item,
          ),
        );
      }
    };

    hydrateItems();

    return () => {
      cancelled = true;
    };
  }, [datasetItems, hydrateDatasetItemData]);

  return hydratedItems;
}
