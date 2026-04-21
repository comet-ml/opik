import type { OpikClient } from "@/client/Client";
import { getGlobalClient } from "@/client/globalClient";
import type { PromptType, PromptTemplateStructure } from "./types";
import type * as OpikApi from "@/rest_api/api";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

export const PROMPT_SYNC_TIMEOUT_MS = 5000;

/**
 * Base data interface for all prompt types
 */
export interface BasePromptData {
  promptId?: string;
  versionId?: string;
  name: string;
  commit?: string;
  metadata?: OpikApi.JsonNode;
  type?: PromptType;
  changeDescription?: string;
  description?: string;
  tags?: string[];
  templateStructure?: PromptTemplateStructure;
  synced?: boolean;
  projectName?: string;
}

/**
 * Abstract base class for all prompt types.
 * Provides common functionality for versioning, property updates, and deletion.
 */
export abstract class BasePrompt {
  private _id: string | undefined;
  private _versionId: string | undefined;
  private _commit: string | undefined;
  private _synced: boolean;
  private _changeDescription: string | undefined;

  private _projectName: string | undefined;

  public readonly type: PromptType;
  public readonly templateStructure: PromptTemplateStructure;

  // Mutable fields (can be updated via updateProperties)
  protected _name: string;
  protected _description: string | undefined;
  protected _tags: string[];

  protected readonly _metadata: OpikApi.JsonNode | undefined;
  protected readonly opik: OpikClient;

  /** Pending background sync promise, set when constructed without synced:true. */
  protected _pendingSync: Promise<void> | null = null;

  get id(): string | undefined { return this._id; }
  get versionId(): string | undefined { return this._versionId; }
  get commit(): string | undefined { return this._commit; }
  /** Whether the prompt has been successfully synced with the backend. */
  get synced(): boolean { return this._synced; }
  get changeDescription(): string | undefined { return this._changeDescription; }
  get projectName(): string | undefined { return this._projectName; }


  constructor(data: BasePromptData, opik?: OpikClient) {
    this._id = data.promptId;
    this._versionId = data.versionId;
    this._commit = data.commit;
    this.type = data.type ?? "mustache";
    this._changeDescription = data.changeDescription;
    this.templateStructure = data.templateStructure ?? "text";
    this._synced = data.synced ?? false;
    this._name = data.name;
    this._description = data.description;
    this._tags = data.tags ? [...data.tags] : [];
    this._metadata = data.metadata;
    this.opik = opik ?? getGlobalClient();
    this._projectName = data.projectName;
  }

  /**
   * Updates internal state after a successful background sync.
   */
  protected updateSyncState(result: {
    promptId?: string;
    versionId?: string;
    commit?: string;
    changeDescription?: string;
    tags?: string[];
    projectName?: string;
  }): void {
    this._id = result.promptId;
    this._versionId = result.versionId;
    this._commit = result.commit;
    this._changeDescription = result.changeDescription;
    if (result.tags) {
      this._tags = result.tags;
    }
    if (result.projectName !== undefined) {
      this._projectName = result.projectName;
    }
    this._synced = true;
  }

  /**
   * Shared background-sync helper for subclass constructors.
   * Calls the provided create function, then only updates sync state when the
   * returned instance is truly synced (has id, versionId, and commit populated).
   * If the create call throws, or returns an unsynced instance, _synced stays false.
   */
  protected async _syncViaCreate<T extends BasePrompt>(
    create: () => Promise<T>,
  ): Promise<void> {
    const TIMED_OUT = Symbol();
    let timerId: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<typeof TIMED_OUT>((resolve) => {
      timerId = setTimeout(() => resolve(TIMED_OUT), PROMPT_SYNC_TIMEOUT_MS);
    });
    try {
      const createPromise = create().catch((error) => {
        // Swallow late rejection after timeout wins the race, so it does not become unhandled.
        // The catch block below already logs the real failure.
        logger.debug(`Prompt '${this._name}' sync rejected after timeout`, { error });
        return undefined;
      });
      const result = await Promise.race([createPromise, timeout]);
      if (result === TIMED_OUT) {
        logger.warn(
          `Prompt '${this._name}' sync timed out after ${PROMPT_SYNC_TIMEOUT_MS}ms. ` +
            "The prompt will work locally but is not persisted on the server. " +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
        return;
      }
      if (!result) {
        logger.warn(
          `Prompt '${this._name}' sync failed (rejected after timeout). ` +
            "The prompt will work locally but is not persisted on the server. " +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
        return;
      }
      if (result.synced && result.id && result.versionId && result.commit) {
        this.updateSyncState({
          promptId: result.id,
          versionId: result.versionId,
          commit: result.commit,
          changeDescription: result.changeDescription,
          tags: result.tags ? Array.from(result.tags) : undefined,
          projectName: result.projectName,
        });
      } else {
        logger.warn(
          `Prompt '${this._name}' was not persisted on the server. ` +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
      }
    } catch (error) {
      logger.warn(
        `Failed to sync prompt '${this._name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
    } finally {
      clearTimeout(timerId);
    }
  }

  /**
   * Resolves when initialization completes.
   * Returns immediately if the prompt was already persisted (e.g. retrieved from backend).
   */
  ready(): Promise<void> {
    return this._pendingSync ?? Promise.resolve();
  }

  // Public getters for mutable fields
  get name(): string {
    return this._name;
  }

  get description(): string | undefined {
    return this._description;
  }

  get tags(): readonly string[] | undefined {
    return Object.freeze([...this._tags]);
  }

  /**
   * Read-only metadata property.
   * Returns deep copy to prevent external mutation.
   */
  get metadata(): OpikApi.JsonNode | undefined {
    if (!this._metadata) {
      return undefined;
    }
    return structuredClone(this._metadata);
  }

  /**
   * Updates prompt properties (name, description, and/or tags).
   * Performs immediate update (no batching).
   *
   * @param updates - Partial updates with optional name, description, and tags
   * @returns Promise resolving to this prompt instance for method chaining
   */
  async updateProperties(updates: {
    name?: string;
    description?: string;
    tags?: string[];
  }): Promise<this> {
    await this.ready();
    this.ensureSynced("updateProperties");
    await this.opik.api.prompts.updatePrompt(
      this.id!,
      {
        name: updates.name ?? this._name,
        description: updates.description,
        tags: updates.tags,
      },
      this.opik.api.requestOptions,
    );

    // Update local state after successful backend update
    this._name = updates.name ?? this._name;
    this._description = updates.description ?? this._description;
    this._tags = updates.tags ?? this._tags;

    return this;
  }

  /**
   * Deletes this prompt from the backend.
   * Performs immediate deletion (no batching).
   */
  async delete(): Promise<void> {
    await this.ready();
    this.ensureSynced("delete");
    await this.opik.deletePrompts([this.id!]);
  }

  /**
   * Retrieves all version history for this prompt.
   * Fetches and returns complete version history, sorted by creation date (newest first).
   * Automatically handles pagination to fetch all versions.
   *
   * @param options - Optional filtering, sorting, and search parameters
   * @returns Promise resolving to array of all PromptVersion instances for this prompt
   */
  async getVersions(options?: {
    search?: string;
    sorting?: string;
    filters?: string;
  }): Promise<PromptVersion[]> {
    await this.ready();
    this.ensureSynced("getVersions");
    logger.debug("Getting versions for prompt", {
      promptId: this.id,
      name: this.name,
    });

    try {
      const allVersions: OpikApi.PromptVersionDetail[] = [];
      let page = 1;
      const pageSize = 100;

      while (true) {
        const versionsResponse = await this.opik.api.prompts.getPromptVersions(
          this.id!,
          {
            page,
            size: pageSize,
            search: options?.search,
            sorting: options?.sorting,
            filters: options?.filters,
          },
          this.opik.api.requestOptions,
        );

        const versions = versionsResponse.content ?? [];
        allVersions.push(...versions);

        if (versions.length < pageSize) {
          break;
        }
        page++;
      }

      logger.debug("Successfully retrieved prompt versions", {
        promptId: this.id,
        name: this.name,
        totalVersions: allVersions.length,
      });

      return allVersions.map((version: OpikApi.PromptVersionDetail) =>
        PromptVersion.fromApiResponse(this.name, version),
      );
    } catch (error) {
      logger.error("Failed to get prompt versions", {
        promptId: this.id,
        name: this.name,
        error,
      });
      throw error;
    }
  }

  /**
   * Helper method to restore a version.
   * Used by subclasses in their useVersion implementations.
   *
   * @param version - PromptVersion object to restore
   * @returns Promise resolving to the API response for the restored version
   */
  protected async restoreVersion(
    version: PromptVersion,
  ): Promise<OpikApi.PromptVersionDetail> {
    await this.ready();
    this.ensureSynced("restoreVersion");
    logger.debug("Restoring prompt version", {
      promptId: this.id,
      name: this.name,
      versionId: version.id,
      versionCommit: version.commit,
    });

    try {
      const restoredVersionResponse =
        await this.opik.api.prompts.restorePromptVersion(
          this.id!,
          version.id,
          this.opik.api.requestOptions,
        );

      logger.debug("Successfully restored prompt version", {
        promptId: this.id,
        name: this.name,
        restoredVersionId: restoredVersionResponse.id,
        restoredCommit: restoredVersionResponse.commit,
      });

      return restoredVersionResponse;
    } catch (error) {
      logger.error("Failed to restore prompt version", {
        promptId: this.id,
        name: this.name,
        versionId: version.id,
        versionCommit: version.commit,
        error,
      });
      throw error;
    }
  }

  /**
   * Helper method to retrieve a version by commit hash.
   * Used by subclasses in their getVersion implementations.
   *
   * @param commit - Commit hash (8-char short form or full)
   * @returns Promise resolving to the API response or null if not found
   */
  protected async retrieveVersionByCommit(
    commit: string,
  ): Promise<OpikApi.PromptVersionDetail | null> {
    try {
      const response = await this.opik.api.prompts.retrievePromptVersion(
        { name: this.name, commit },
        this.opik.api.requestOptions,
      );
      return response;
    } catch (error) {
      // If version not found (404), return null instead of throwing
      if (
        error &&
        typeof error === "object" &&
        "statusCode" in error &&
        error.statusCode === 404
      ) {
        return null;
      }

      logger.error("Failed to retrieve prompt version", {
        promptName: this.name,
        commit,
        error,
      });
      throw error;
    }
  }

  /**
   * Throws an error if the prompt has not been successfully synced with the backend.
   * Used internally before backend operations to ensure we have a valid prompt ID.
   */
  protected ensureSynced(operation: string): void {
    if (!this.synced) {
      throw new Error(
        `Cannot call ${operation}() on a prompt that failed to persist. ` +
          "Call .syncWithBackend() to retry persisting the prompt.",
      );
    }
  }

  /**
   * Get a specific version by commit hash.
   * Returns a new instance of the appropriate prompt type.
   *
   * @param commit - Commit hash (8-char short form or full)
   * @returns Promise resolving to prompt instance or null if not found
   */
  abstract getVersion(commit: string): Promise<BasePrompt | null>;

  /**
   * Restores a specific version by creating a new version with content from the specified version.
   *
   * @param version - PromptVersion object to restore
   * @returns Promise resolving to a new prompt instance with the restored version
   */
  abstract useVersion(version: PromptVersion): Promise<BasePrompt>;

  /**
   * Synchronize the prompt with the backend.
   *
   * Creates or updates the prompt on the Opik server. If the sync fails,
   * a warning is logged and the prompt continues to work locally.
   *
   * @returns Promise resolving to a new synced instance, or the same instance if sync fails
   */
  abstract syncWithBackend(): Promise<BasePrompt>;
}
