import type { OpikClient } from "@/client/Client";
import { PromptType, PromptVariables } from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { formatPromptTemplate } from "./formatting";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

export interface PromptData {
  promptId: string;
  versionId: string;
  name: string;
  prompt: string;
  commit?: string;
  metadata?: OpikApi.JsonNode;
  type?: PromptType;
  changeDescription?: string;
  description?: string;
  tags?: string[];
}

/**
 * Domain object representing a versioned prompt template.
 * Provides immutable access to prompt properties and template formatting.
 * Integrates with backend for persistence and version management.
 */
export class Prompt {
  public readonly id: string;
  public readonly versionId: string;
  public readonly prompt: string;
  public readonly commit: string | undefined;
  public readonly type: PromptType;
  public readonly changeDescription: string | undefined;

  // Mutable fields (can be updated via updateProperties)
  private _name: string;
  private _description: string | undefined;
  private _tags: string[];

  private readonly _metadata: OpikApi.JsonNode | undefined;

  /**
   * Creates a new Prompt instance.
   * This should not be created directly, use OpikClient.createPrompt() instead.
   */
  constructor(
    {
      promptId,
      versionId,
      name,
      prompt,
      commit,
      metadata,
      type,
      changeDescription,
      description,
      tags = [],
    }: PromptData,
    private opik: OpikClient
  ) {
    this.id = promptId;
    this.versionId = versionId;
    this.prompt = prompt;
    this.commit = commit;
    this.type = type ?? PromptType.MUSTACHE;
    this.changeDescription = changeDescription;
    this._name = name;
    this._description = description;
    this._tags = [...tags];
    this._metadata = metadata;
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
   * Formats prompt template by substituting variables.
   * Validates that all template placeholders are provided (for Mustache templates).
   *
   * @param variables - Object with values to substitute into template
   * @returns Formatted prompt text with variables substituted
   * @throws PromptValidationError if template processing or validation fails
   *
   * @example
   * ```typescript
   * const prompt = new Prompt({
   *   name: "greeting",
   *   prompt: "Hello {{name}}, your score is {{score}}",
   *   type: "mustache"
   * }, client);
   *
   * // Valid - all placeholders provided
   * prompt.format({ name: "Alice", score: 95 });
   * // Returns: "Hello Alice, your score is 95"
   *
   * // Invalid - missing 'score' placeholder
   * prompt.format({ name: "Alice" });
   * // Throws: PromptValidationError
   * ```
   */
  format(variables: PromptVariables): string {
    return formatPromptTemplate(this.prompt, variables, this.type);
  }

  /**
   * Static factory method to create Prompt from backend API response.
   *
   * @param name - Name of the prompt
   * @param apiResponse - REST API PromptVersionDetail response
   * @param opik - OpikClient instance
   * @param promptPublicData - Optional PromptPublic data containing description and tags
   * @returns Prompt instance constructed from response data
   * @throws PromptValidationError if response structure invalid
   */
  static fromApiResponse(
    promptData: OpikApi.PromptPublic,
    apiResponse: OpikApi.PromptVersionDetail,
    opik: OpikClient
  ): Prompt {
    // Validate required fields
    if (!apiResponse.template) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'template'"
      );
    }

    if (!apiResponse.commit) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'commit'"
      );
    }

    if (!apiResponse.promptId) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'promptId'"
      );
    }

    if (!apiResponse.id) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'id' (version ID)"
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`
      );
    }

    // Create Prompt instance (no enqueueing - already persisted)
    // Type assertion safe due to validation above
    return new Prompt(
      {
        promptId: apiResponse.promptId,
        versionId: apiResponse.id,
        name: promptData.name,
        prompt: apiResponse.template,
        commit: apiResponse.commit,
        metadata: apiResponse.metadata,
        type: promptType,
        changeDescription: apiResponse.changeDescription,
        description: promptData.description,
        tags: promptData.tags,
      },
      opik
    );
  }

  /**
   * Updates prompt properties (name, description, and/or tags).
   * Performs immediate update (no batching).
   *
   * @param updates - Partial updates with optional name, description, and tags
   * @returns Promise resolving to this Prompt instance for method chaining
   *
   * @example
   * ```typescript
   * const prompt = await client.getPrompt({ name: "my-prompt" });
   * await prompt.updateProperties({
   *   name: "renamed-prompt",
   *   description: "Updated description",
   *   tags: ["tag1", "tag2"]
   * });
   * ```
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
      this.opik.api.requestOptions
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
   * @returns Promise resolving to array of all PromptVersion instances for this prompt
   * @throws OpikApiError if REST API call fails
   *
   * @example
   * ```typescript
   * const prompt = await client.getPrompt({ name: "my-prompt" });
   * const versions = await prompt.getVersions();
   *
   * console.log(`Found ${versions.length} versions`);
   * versions.forEach(v => {
   *   console.log(`Commit: ${v.commit}, Age: ${v.getVersionAge()}`);
   * });
   * ```
   */
  async getVersions(): Promise<PromptVersion[]> {
    logger.debug("Getting versions for prompt", {
      promptId: this.id,
      name: this.name,
    });

    try {
      // Paginate through all versions (page size 100 to match Python SDK)
      const allVersions: OpikApi.PromptVersionDetail[] = [];
      let page = 1;
      const pageSize = 100;

      while (true) {
        const versionsResponse = await this.opik.api.prompts.getPromptVersions(
          this.id,
          { page, size: pageSize },
          this.opik.api.requestOptions
        );

        const versions = versionsResponse.content ?? [];
        allVersions.push(...versions);

        // Break if we got fewer results than page size (last page)
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

      // Map API responses to PromptVersion instances
      return allVersions.map((version: OpikApi.PromptVersionDetail) =>
        PromptVersion.fromApiResponse(this.name, version)
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
   * Restores a specific version by creating a new version with content from the specified version.
   * The version must be obtained from the backend (e.g., via getVersions()).
   * Returns a new Prompt instance with the restored content as the latest version.
   *
   * @param version - PromptVersion object to restore (must be from backend)
   * @returns Promise resolving to a new Prompt instance with the restored version
   * @throws OpikApiError if REST API call fails
   *
   * @example
   * ```typescript
   * const prompt = await client.getPrompt({ name: "my-prompt" });
   *
   * // Get all versions
   * const versions = await prompt.getVersions();
   *
   * // Restore a specific version
   * const targetVersion = versions.find(v => v.commit === "abc123de");
   * if (targetVersion) {
   *   const restoredPrompt = await prompt.useVersion(targetVersion);
   *   console.log(`Restored to commit: ${restoredPrompt.commit}`);
   *   console.log(`New template: ${restoredPrompt.prompt}`);
   *
   *   // Continue using the restored prompt
   *   const formatted = restoredPrompt.format({ name: "World" });
   * }
   * ```
   */
  async useVersion(version: PromptVersion): Promise<Prompt> {
    logger.debug("Restoring prompt version", {
      promptId: this.id,
      name: this.name,
      versionId: version.id,
      versionCommit: version.commit,
    });

    try {
      // Call restore endpoint with the version ID
      const restoredVersionResponse =
        await this.opik.api.prompts.restorePromptVersion(
          this.id,
          version.id,
          this.opik.api.requestOptions
        );

      logger.debug("Successfully restored prompt version", {
        promptId: this.id,
        name: this.name,
        restoredVersionId: restoredVersionResponse.id,
        restoredCommit: restoredVersionResponse.commit,
      });

      // Return a new Prompt instance with the restored version
      return Prompt.fromApiResponse(
        {
          name: this.name,
          description: this.description,
          tags: this._tags,
        },
        restoredVersionResponse,
        this.opik
      );
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
   * Get a Prompt with a specific version by commit hash.
   *
   * @param commit - Commit hash (8-char short form or full)
   * @returns Prompt instance representing that version, or null if not found
   *
   * @example
   * ```typescript
   * const prompt = await client.getPrompt({ name: "greeting" });
   *
   * // Get a specific version directly as a Prompt
   * const versionedPrompt = await prompt.getVersion("abc123de");
   * if (versionedPrompt) {
   *   const text = versionedPrompt.format({ name: "Alice" });
   * }
   * ```
   */
  async getVersion(commit: string): Promise<Prompt | null> {
    try {
      const response = await this.opik.api.prompts.retrievePromptVersion(
        { name: this.name, commit },
        this.opik.api.requestOptions
      );

      // Return a Prompt instance representing this version
      return Prompt.fromApiResponse(
        {
          name: this.name,
          description: this.description,
          tags: this._tags,
        },
        response,
        this.opik
      );
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
}
