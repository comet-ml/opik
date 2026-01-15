import type { OpikClient } from "@/client/Client";
import type { PromptType, PromptTemplateStructure } from "./types";
import type * as OpikApi from "@/rest_api/api";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

/**
 * Base data interface for all prompt types
 */
export interface BasePromptData {
  promptId: string;
  versionId: string;
  name: string;
  commit?: string;
  metadata?: OpikApi.JsonNode;
  type?: PromptType;
  changeDescription?: string;
  description?: string;
  tags?: string[];
  templateStructure?: PromptTemplateStructure;
}

/**
 * Abstract base class for all prompt types.
 * Provides common functionality for versioning, property updates, and deletion.
 */
export abstract class BasePrompt {
  public readonly id: string;
  public readonly versionId: string;
  public readonly commit: string | undefined;
  public readonly type: PromptType;
  public readonly changeDescription: string | undefined;
  public readonly templateStructure: PromptTemplateStructure;

  // Mutable fields (can be updated via updateProperties)
  protected _name: string;
  protected _description: string | undefined;
  protected _tags: string[];

  protected readonly _metadata: OpikApi.JsonNode | undefined;
  protected readonly opik: OpikClient;

  constructor(data: BasePromptData, opik: OpikClient) {
    this.id = data.promptId;
    this.versionId = data.versionId;
    this.commit = data.commit;
    this.type = data.type ?? "mustache";
    this.changeDescription = data.changeDescription;
    this.templateStructure = data.templateStructure ?? "text";
    this._name = data.name;
    this._description = data.description;
    this._tags = data.tags ? [...data.tags] : [];
    this._metadata = data.metadata;
    this.opik = opik;
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
    await this.opik.api.prompts.updatePrompt(
      this.id,
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
    await this.opik.deletePrompts([this.id]);
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
          this.id,
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
    logger.debug("Restoring prompt version", {
      promptId: this.id,
      name: this.name,
      versionId: version.id,
      versionCommit: version.commit,
    });

    try {
      const restoredVersionResponse =
        await this.opik.api.prompts.restorePromptVersion(
          this.id,
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
}
