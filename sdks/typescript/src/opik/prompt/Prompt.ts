import type { OpikClient } from "@/client/Client";
import { PromptType, PromptVariables, PromptTemplateStructure } from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { formatPromptTemplate } from "./formatting";
import { PromptVersion } from "./PromptVersion";
import { BasePrompt, type BasePromptData } from "./BasePrompt";
import { logger } from "@/utils/logger";

export interface PromptData extends BasePromptData {
  prompt: string;
}

/**
 * Domain object representing a versioned text prompt template.
 * Provides immutable access to prompt properties and template formatting.
 * Integrates with backend for persistence and version management.
 */
export class Prompt extends BasePrompt {
  public readonly prompt: string;

  /**
   * Creates a new Prompt instance.
   * All operations work seamlessly without requiring manual configuration.
   */
  constructor(data: PromptData);
  /** @deprecated Passing an opik client is deprecated. */
  constructor(data: PromptData, opik: OpikClient);
  constructor(data: PromptData, opik?: OpikClient) {
    super(
      {
        ...data,
        templateStructure: PromptTemplateStructure.Text,
      },
      opik,
    );
    this.prompt = data.prompt;

    if (opik === undefined && !data.synced) {
      this._pendingSync = this._performSync();
    }
  }

  private _performSync(): Promise<void> {
    return this._syncViaCreate(() =>
      this.opik.createPrompt({
        name: this._name,
        prompt: this.prompt,
        metadata: this._metadata,
        type: this.type,
        description: this._description,
        tags: this._tags.length ? Array.from(this._tags) : undefined,
      }),
    );
  }

  /**
   * Returns the template string for this text prompt.
   * Alias for the `prompt` property for consistency with ChatPrompt.
   */
  get template(): string {
    return this.prompt;
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
    opik: OpikClient,
    projectName?: string,
  ): Prompt {
    // Validate required fields
    if (!apiResponse.template) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'template'",
      );
    }

    if (!apiResponse.commit) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'commit'",
      );
    }

    if (!apiResponse.promptId) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'promptId'",
      );
    }

    if (!apiResponse.id) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'id' (version ID)",
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`,
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
        synced: true,
        projectName,
      },
      opik,
    );
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
    const restoredVersionResponse = await this.restoreVersion(version);

    // Return a new Prompt instance with the restored version
    return Prompt.fromApiResponse(
      {
        name: this.name,
        description: this.description,
        tags: Array.from(this.tags ?? []),
      },
      restoredVersionResponse,
      this.opik,
    );
  }

  /**
   * Synchronize the prompt with the backend.
   *
   * Creates or updates the prompt on the Opik server. If the sync fails,
   * a warning is logged and the same (unsynced) instance is returned.
   *
   * @returns Promise resolving to a new synced Prompt instance, or this instance if sync fails
   */
  async syncWithBackend(): Promise<Prompt> {
    try {
      return await this.opik.createPrompt({
        name: this.name,
        prompt: this.prompt,
        metadata: this.metadata,
        type: this.type,
        description: this.description,
        tags: this.tags ? Array.from(this.tags) : undefined,
      });
    } catch (error) {
      logger.warn(
        `Failed to sync prompt '${this.name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
      return this;
    }
  }

  /**
   * Get a Prompt with a specific version by commit hash.
   *
   * @param commit - Commit hash (8-char short form or full)
   * @returns Prompt instance representing that version, or null if not found
   */
  async getVersion(commit: string): Promise<Prompt | null> {
    const response = await this.retrieveVersionByCommit(commit);
    if (!response) {
      return null;
    }

    // Return a Prompt instance representing this version
    return Prompt.fromApiResponse(
      {
        name: this.name,
        description: this.description,
        tags: Array.from(this.tags ?? []),
      },
      response,
      this.opik,
    );
  }
}
