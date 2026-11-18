import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { OpikClient } from "@/client/Client";
import { DatasetItemPublic } from "@/rest_api/api";
import { readAllStreamPages } from "@/utils/stream";
import { serialization } from "@/rest_api";

export async function getDatasetItems<
  T extends DatasetItemData = DatasetItemData,
>(
  opik: OpikClient,
  options: {
    datasetName: string;
    projectName?: string;
    datasetVersion?: string;
    nbSamples?: number;
    lastRetrievedId?: string;
  }
): Promise<DatasetItem<T>[]> {
  const {
    datasetName,
    projectName,
    datasetVersion,
    nbSamples,
    lastRetrievedId: startAfter,
  } = options;

  const rawItems = await readAllStreamPages<DatasetItemPublic>(
    (limit, lastRetrievedId) =>
      opik.api.datasets.streamDatasetItems({
        datasetName,
        projectName,
        lastRetrievedId,
        steamLimit: limit,
        datasetVersion,
      }),
    serialization.DatasetItemPublic,
    "id",
    { maxResults: nbSamples, startAfter }
  );

  return rawItems.map((item) => DatasetItem.fromApiModel<T>(item));
}
