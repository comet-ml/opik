import type { OpikClient } from "@/client/Client";
import {
  PromptType,
  ChatMessage,
  SupportedModalities,
  PromptVariables,
  PromptTemplateStructure,
} from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { ChatPromptTemplate } from "./chat/ChatPromptTemplate";
import { BasePrompt, type BasePromptData } from "./BasePrompt";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

export interface ChatPromptData extends BasePromptData {
  messages: ChatMessage[];
}

/**
 * Domain object representing a versioned chat prompt template.
 * Provides immutable access to chat message templates and formatting.
 * Integrates with backend for persistence and version management.
 */
export class ChatPrompt extends BasePrompt {
  public readonly messages: ChatMessage[];
  private readonly chatTemplate: ChatPromptTemplate;

  /**
   * Creates a new ChatPrompt instance.
   * All operations work seamlessly without requiring manual configuration.
   */
  constructor(data: ChatPromptData);
  /** @deprecated Passing an opik client is deprecated. */
  constructor(data: ChatPromptData, opik: OpikClient);
  constructor(data: ChatPromptData, opik?: OpikClient) {
    super(
      {
        ...data,
        templateStructure: PromptTemplateStructure.Chat,
      },
      opik,
    );
    this.messages = data.messages;
    this.chatTemplate = new ChatPromptTemplate(data.messages, this.type);

    if (opik === undefined && !data.synced) {
      this._pendingSync = this._performSync();
    }
  }

  private _performSync(): Promise<void> {
    return this._syncViaCreate(() =>
      this.opik.createChatPrompt({
        name: this._name,
        messages: structuredClone(this.messages),
        metadata: this._metadata,
        type: this.type,
        description: this._description,
        tags: this._tags.length ? Array.from(this._tags) : undefined,
      }),
    );
  }

  /**
   * Returns the template messages for this chat prompt.
   * Alias for the `messages` property for consistency with Prompt.
   */
  get template(): ChatMessage[] {
    return structuredClone(this.messages);
  }

  /**
   * Formats chat template by substituting variables in messages.
   *
   * @param variables - Object with values to substitute into template
   * @param supportedModalities - Optional specification of which modalities are supported.
   *   When a modality is not supported (false or not specified), structured content
   *   parts (e.g., images, videos) are replaced with text placeholders like
   *   "<<<image>>>" or "<<<video>>>". When supported (true), the structured content
   *   is preserved as-is. Defaults to all modalities supported.
   * @returns Array of formatted chat messages with variables substituted
   * @throws PromptValidationError if template processing fails
   *
   * @example
   * ```typescript
   * const chatPrompt = new ChatPrompt({
   *   name: "assistant",
   *   messages: [
   *     { role: "system", content: "You are a {{role}}" },
   *     { role: "user", content: "Help me with {{task}}" }
   *   ],
   *   type: "mustache"
   * }, client);
   *
   * // Format with all modalities supported
   * const messages = chatPrompt.format({
   *   role: "helpful assistant",
   *   task: "coding"
   * });
   *
   * // Format with limited modalities
   * const textOnly = chatPrompt.format(
   *   { role: "assistant", task: "coding" },
   *   { vision: false, video: false }
   * );
   * ```
   */
  format(
    variables: PromptVariables,
    supportedModalities?: SupportedModalities,
  ): ChatMessage[] {
    return this.chatTemplate.format(variables, supportedModalities);
  }

  /**
   * Static factory method to create ChatPrompt from backend API response.
   *
   * @param promptData - PromptPublic data containing name, description, tags
   * @param apiResponse - REST API PromptVersionDetail response
   * @param opik - OpikClient instance
   * @returns ChatPrompt instance constructed from response data
   * @throws PromptValidationError if response structure invalid
   */
  static fromApiResponse(
    promptData: OpikApi.PromptPublic,
    apiResponse: OpikApi.PromptVersionDetail,
    opik: OpikClient,
    projectName?: string,
  ): ChatPrompt {
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

    // Parse messages from JSON string
    let messages: ChatMessage[];
    try {
      messages = JSON.parse(apiResponse.template);
      if (!Array.isArray(messages)) {
        throw new PromptValidationError(
          "Invalid chat prompt template: expected array of messages",
        );
      }
    } catch (error) {
      if (error instanceof PromptValidationError) {
        throw error;
      }
      throw new PromptValidationError(
        `Failed to parse chat prompt template: ${error instanceof Error ? error.message : String(error)}`,
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`,
      );
    }

    // Create ChatPrompt instance
    return new ChatPrompt(
      {
        promptId: apiResponse.promptId,
        versionId: apiResponse.id,
        name: promptData.name,
        messages,
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
   * Returns a new ChatPrompt instance with the restored content as the latest version.
   *
   * @param version - PromptVersion object to restore (must be from backend)
   * @returns Promise resolving to a new ChatPrompt instance with the restored version
   * @throws OpikApiError if REST API call fails
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.getChatPrompt({ name: "my-chat-prompt" });
   *
   * // Get all versions
   * const versions = await chatPrompt.getVersions();
   *
   * // Restore a specific version
   * const targetVersion = versions.find(v => v.commit === "abc123de");
   * if (targetVersion) {
   *   const restoredPrompt = await chatPrompt.useVersion(targetVersion);
   *   console.log(`Restored to commit: ${restoredPrompt.commit}`);
   *
   *   // Continue using the restored prompt
   *   const formatted = restoredPrompt.format({ name: "World" });
   * }
   * ```
   */
  async useVersion(version: PromptVersion): Promise<ChatPrompt> {
    const restoredVersionResponse = await this.restoreVersion(version);

    // Return a new ChatPrompt instance with the restored version
    return ChatPrompt.fromApiResponse(
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
   * Synchronize the chat prompt with the backend.
   *
   * Creates or updates the chat prompt on the Opik server. If the sync fails,
   * a warning is logged and the same (unsynced) instance is returned.
   *
   * @returns Promise resolving to a new synced ChatPrompt instance, or this instance if sync fails
   */
  async syncWithBackend(): Promise<ChatPrompt> {
    try {
      return await this.opik.createChatPrompt({
        name: this.name,
        messages: structuredClone(this.messages),
        metadata: this.metadata,
        type: this.type,
        description: this.description,
        tags: this.tags ? Array.from(this.tags) : undefined,
      });
    } catch (error) {
      logger.warn(
        `Failed to sync chat prompt '${this.name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
      return this;
    }
  }

  /**
   * Get a ChatPrompt with a specific version by commit hash.
   *
   * @param commit - Commit hash (8-char short form or full)
   * @returns ChatPrompt instance representing that version, or null if not found
   */
  async getVersion(commit: string): Promise<ChatPrompt | null> {
    const response = await this.retrieveVersionByCommit(commit);
    if (!response) {
      return null;
    }

    // Return a ChatPrompt instance representing this version
    return ChatPrompt.fromApiResponse(
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
