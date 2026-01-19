import type { OpikClient } from "@/client/Client";
import { PromptType, PromptVariables, PromptTemplateStructure } from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { formatPromptTemplate } from "./formatting";
import { PromptVersion } from "./PromptVersion";
import { BasePrompt, type BasePromptData } from "./BasePrompt";

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
   * This should not be created directly, use OpikClient.createPrompt() instead.
   */
  constructor(data: PromptData, opik: OpikClient) {
    super(
      {
        ...data,
        templateStructure: PromptTemplateStructure.Text,
      },
      opik,
    );
    this.prompt = data.prompt;
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
