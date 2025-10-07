import { BatchQueue } from "./BatchQueue";
import type { CreatePromptOptions } from "@/prompt/types";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { ConflictError } from "@/rest_api/api/errors";
import { logger } from "@/utils/logger";
import type * as OpikApi from "@/rest_api/api";

/**
 * Specialized batch queue for prompt creation and update operations.
 * Handles non-blocking persistence of prompts to backend.
 * Integrates with existing BatchQueue infrastructure.
 *
 * @internal - Not exposed in public API
 */
export class PromptBatchQueue extends BatchQueue<CreatePromptOptions> {
  /**
   * Creates prompt batch queue with debouncing and batching.
   *
   * @param api - OpikApiClient for REST calls
   * @param delay - Optional delay in milliseconds before flushing the queue
   */
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      createBatchSize: 10,
      name: "PromptBatchQueue",
    });
  }

  /**
   * Gets the ID of a prompt entity (uses prompt name as ID).
   *
   * @param entity The prompt entity
   * @returns The name of the prompt (used as ID)
   */
  protected getId(entity: CreatePromptOptions): string {
    return entity.name;
  }

  /**
   * Creates multiple prompt entities in a batch.
   * Processes prompts sequentially to maintain version ordering.
   * Handles 409 conflicts gracefully (duplicate detection).
   *
   * @param prompts The array of prompts to create
   */
  protected async createEntities(
    prompts: CreatePromptOptions[]
  ): Promise<void> {
    for (const prompt of prompts) {
      try {
        await this.api.prompts.createPromptVersion(
          {
            name: prompt.name,
            version: {
              template: prompt.prompt,
              metadata: prompt.metadata,
              type: prompt.type,
            },
          },
          this.api.requestOptions
        );

        logger.debug("Successfully created prompt version", {
          name: prompt.name,
        });
      } catch (error) {
        if (error instanceof ConflictError) {
          // 409 means duplicate - backend already has this version, safe to ignore
          logger.debug(
            "Prompt version already exists (409 conflict), skipping",
            { name: prompt.name }
          );
        } else {
          // Re-throw other errors
          logger.error("Failed to create prompt version", {
            name: prompt.name,
            error,
          });
          throw error;
        }
      }
    }
  }

  /**
   * Retrieves a prompt by its name.
   * Not implemented as prompts don't support updates through this queue.
   *
   * @param name The name of the prompt to retrieve
   * @returns undefined (not supported)
   */
  protected async getEntity(): Promise<undefined> {
    return undefined;
  }

  /**
   * Updates a prompt's properties (description, tags).
   * Updates are applied to the parent prompt entity.
   *
   * @param id The ID of the prompt to update
   * @param updates Partial prompt properties to update
   */
  protected async updateEntity(
    id: string,
    updates: Partial<CreatePromptOptions>
  ): Promise<void> {
    const updateData: OpikApi.PromptUpdatable = {
      name: updates.name!,
      description: updates.description,
      tags: updates.tags,
    };

    await this.api.prompts.updatePrompt(
      id,
      updateData,
      this.api.requestOptions
    );

    logger.debug("Successfully updated prompt properties", {
      id,
      description: updates.description,
      tags: updates.tags,
    });
  }

  /**
   * Deletes multiple prompts by their IDs.
   * Uses batch delete endpoint for efficient deletion.
   *
   * @param ids Array of prompt IDs to delete
   */
  protected async deleteEntities(ids: string[]): Promise<void> {
    await this.api.prompts.deletePromptsBatch({ ids }, this.api.requestOptions);

    logger.debug("Successfully deleted prompts batch", {
      count: ids.length,
    });
  }
}
