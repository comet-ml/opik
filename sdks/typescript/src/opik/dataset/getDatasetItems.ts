import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { OpikClient } from "@/client/Client";
import { DatasetItemPublic } from "@/rest_api/api";
import { parseNdjsonStreamToArray } from "@/utils/stream";
import { serialization } from "@/rest_api";

const MAX_BATCH_LIMIT = 2000;

export async function getDatasetItems<
  T extends DatasetItemData = DatasetItemData,
>(
  opik: OpikClient,
  options: {
    datasetName: string;
    datasetVersion?: string;
    nbSamples?: number;
    lastRetrievedId?: string;
  }
): Promise<DatasetItem<T>[]> {
  const { datasetName, datasetVersion, nbSamples, lastRetrievedId } = options;

  // Handle edge case: nbSamples = 0 means no items requested
  if (nbSamples === 0) {
    return [];
  }

  const allItems: DatasetItem<T>[] = [];
  let remaining = nbSamples;
  let currentLastId = lastRetrievedId;

  while (true) {
    const streamLimit = Math.min(
      remaining ?? MAX_BATCH_LIMIT,
      MAX_BATCH_LIMIT
    );

    const streamResponse = await opik.api.datasets.streamDatasetItems({
      datasetName,
      lastRetrievedId: currentLastId,
      steamLimit: streamLimit,
      datasetVersion,
    });

    const rawItems = await parseNdjsonStreamToArray<DatasetItemPublic>(
      streamResponse,
      serialization.DatasetItemPublic,
      streamLimit
    );

    if (rawItems.length === 0) {
      break;
    }

    const items = rawItems.map((item) => DatasetItem.fromApiModel<T>(item));
    allItems.push(...items);

    currentLastId = rawItems[rawItems.length - 1].id;

    if (remaining !== undefined) {
      remaining -= rawItems.length;
      if (remaining <= 0) {
        break;
      }
    }

    // If we got fewer items than requested, we've reached the end
    if (rawItems.length < streamLimit) {
      break;
    }
  }

  return allItems;
}
