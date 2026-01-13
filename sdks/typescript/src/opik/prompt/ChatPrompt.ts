import type { OpikClient } from "@/client/Client";
import {
  PromptType,
  ChatMessage,
  SupportedModalities,
  PromptVariables,
  ChatPromptData,
} from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { ChatPromptTemplate } from "./chat/ChatPromptTemplate";

/**
 * Domain object representing a versioned chat prompt template.
 * Provides immutable access to chat message templates and formatting.
 * Integrates with backend for persistence and version management.
 */
export class ChatPrompt {
  public readonly id: string;
  public readonly versionId: string;
  public readonly messages: ChatMessage[];
  public readonly commit: string | undefined;
  public readonly type: PromptType;
  public readonly changeDescription: string | undefined;

  // Mutable fields (can be updated via updateProperties)
  private _name: string;
  private _description: string | undefined;
  private _tags: string[];

  private readonly _metadata: OpikApi.JsonNode | undefined;
  private readonly chatTemplate: ChatPromptTemplate;

  /**
   * Creates a new ChatPrompt instance.
   * This should not be created directly, use OpikClient.createChatPrompt() instead.
   */
  constructor(
    {
      promptId,
      versionId,
      name,
      messages,
      commit,
      metadata,
      type,
      changeDescription,
      description,
      tags = [],
    }: ChatPromptData,
    private opik: OpikClient
  ) {
    this.id = promptId;
    this.versionId = versionId;
    this.messages = messages;
    this.commit = commit;
    this.type = type ?? PromptType.MUSTACHE;
    this.changeDescription = changeDescription;
    this._name = name;
    this._description = description;
    this._tags = [...tags];
    this._metadata = metadata;
    this.chatTemplate = new ChatPromptTemplate(messages, this.type);
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
    supportedModalities?: SupportedModalities
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
    opik: OpikClient
  ): ChatPrompt {
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

    // Parse messages from JSON string
    let messages: ChatMessage[];
    try {
      messages = JSON.parse(apiResponse.template);
      if (!Array.isArray(messages)) {
        throw new PromptValidationError(
          "Invalid chat prompt template: expected array of messages"
        );
      }
    } catch (error) {
      if (error instanceof PromptValidationError) {
        throw error;
      }
      throw new PromptValidationError(
        `Failed to parse chat prompt template: ${error instanceof Error ? error.message : String(error)}`
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`
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
      },
      opik
    );
  }

  /**
   * Updates prompt properties (name, description, and/or tags).
   * Performs immediate update (no batching).
   *
   * @param updates - Partial updates with optional name, description, and tags
   * @returns Promise resolving to this ChatPrompt instance for method chaining
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.getChatPrompt({ name: "my-chat-prompt" });
   * await chatPrompt.updateProperties({
   *   name: "renamed-chat-prompt",
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
   * Deletes this chat prompt from the backend.
   * Performs immediate deletion (no batching).
   */
  async delete(): Promise<void> {
    await this.opik.deletePrompts([this.id]);
  }
}
