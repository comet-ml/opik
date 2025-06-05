import { generateId } from "@/utils/generateId";
import { OpikClient } from "@/client/Client";
import { ExperimentItem } from "@/rest_api/api/types/ExperimentItem";
import {
  ExperimentItemContent,
  ExperimentItemReferences,
} from "./ExperimentItem";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { ExperimentItemCompare } from "@/rest_api/api/types/ExperimentItemCompare";
import { logger } from "@/utils/logger";
import { serialization } from "@/rest_api";
import { getExperimentUrlById } from "@/utils/url";
import { DEFAULT_CONFIG } from "@/config/Config";

export interface ExperimentData {
  id?: string;
  name?: string;
  datasetName: string;
}

/**
 * Represents an Experiment in Opik, linking traces and dataset items
 */
export class Experiment {
  public readonly id: string;
  public readonly name?: string;
  public readonly datasetName: string;

  /**
   * Creates a new Experiment instance.
   * This should not be created directly, use static factory methods instead.
   */
  constructor(
    { id, name, datasetName }: ExperimentData,
    private opik: OpikClient
  ) {
    this.id = id || generateId();
    this.name = name;
    this.datasetName = datasetName;
  }

  /**
   * Creates new experiment items by linking existing traces and dataset items
   *
   * @param experimentItemReferences List of references linking traces with dataset items
   */
  public async insert(
    experimentItemReferences: ExperimentItemReferences[]
  ): Promise<void> {
    if (experimentItemReferences.length === 0) {
      return;
    }

    const restExperimentItems: ExperimentItem[] = experimentItemReferences.map(
      (item) => ({
        id: generateId(),
        experimentId: this.id,
        datasetItemId: item.datasetItemId,
        traceId: item.traceId,
      })
    );

    const batches = splitIntoBatches(restExperimentItems, { maxBatchSize: 50 });

    try {
      for (const batch of batches) {
        await this.opik.api.experiments.createExperimentItems({
          experimentItems: batch,
        });
      }
      logger.debug(
        `Inserted ${experimentItemReferences.length} items into experiment ${this.id}`
      );
    } catch (error) {
      logger.error(
        `Error inserting items into experiment: ${error instanceof Error ? error.message : String(error)}`
      );
      throw error;
    }
  }

  /**
   * Retrieves experiment items with options to limit results and truncate data
   *
   * @param options Options for retrieving items
   * @returns Promise resolving to a list of experiment items
   */
  public async getItems(options?: {
    maxResults?: number;
    truncate?: boolean;
  }): Promise<ExperimentItemContent[]> {
    const result: ExperimentItemContent[] = [];
    const maxEndpointBatchSize = 2000; // Maximum batch size per API request
    const { maxResults, truncate = false } = options || {};

    let lastRetrievedId: string | undefined;
    let shouldContinuePagination = true;

    try {
      while (shouldContinuePagination) {
        if (maxResults !== undefined && result.length >= maxResults) {
          break;
        }

        const currentBatchSize = maxResults
          ? Math.min(maxResults - result.length, maxEndpointBatchSize)
          : maxEndpointBatchSize;

        const itemsStream =
          await this.opik.api.experiments.streamExperimentItems({
            experimentName: this.name!,
            limit: currentBatchSize,
            lastRetrievedId: lastRetrievedId,
            truncate,
          });

        try {
          const experimentItemsCurrentBatch =
            await parseNdjsonStreamToArray<ExperimentItemCompare>(
              itemsStream,
              serialization.ExperimentItemCompare,
              currentBatchSize
            );

          if (experimentItemsCurrentBatch.length === 0) {
            shouldContinuePagination = false;
            break;
          }

          for (const item of experimentItemsCurrentBatch) {
            const convertedItem =
              ExperimentItemContent.fromRestExperimentItemCompare(item);
            result.push(convertedItem);

            if (maxResults !== undefined && result.length >= maxResults) {
              shouldContinuePagination = false;
              break;
            }
          }

          lastRetrievedId =
            experimentItemsCurrentBatch[experimentItemsCurrentBatch.length - 1]
              .id;
        } catch (error) {
          logger.error(
            "Error parsing experiment item: " +
              (error instanceof Error ? error.message : String(error))
          );
          shouldContinuePagination = false;
          break;
        }
      }
    } catch (error) {
      logger.error(
        "Error retrieving experiment items: " +
          (error instanceof Error ? error.message : String(error))
      );
      throw error;
    }

    logger.info(
      `Retrieved ${result.length} items${maxResults ? ` (limited by maxResults=${maxResults})` : ``}`
    );

    return result;
  }

  async getUrl(): Promise<string> {
    const dataset = await this.opik.getDataset(this.datasetName);
    const baseUrl = this.opik.config.apiUrl || DEFAULT_CONFIG.apiUrl;

    return getExperimentUrlById({
      datasetId: dataset.id,
      experimentId: this.id,
      baseUrl,
    });
  }
}
