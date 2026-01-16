import type * as OpikApi from "@/rest_api/api";
import { formatDistanceToNow } from "date-fns";
import { diffStringsUnified } from "jest-diff";
import { logger } from "@/utils/logger";
import {
  PromptType,
  type PromptVariables,
  type PromptVersionData,
  type ChatMessage,
} from "./types";
import { PromptValidationError } from "./errors";
import { formatPromptTemplate } from "./formatting";
import { formatChatMessagesForComparison } from "./formatting/chatMessageFormatter";

/**
 * Represents a specific immutable snapshot of a prompt template at a point in time.
 * Pure data object with formatting capabilities.
 */
export class PromptVersion {
  // Public readonly properties
  public readonly id: string;
  public readonly name: string;
  public readonly prompt: string;
  public readonly commit: string;
  public readonly type: PromptType;
  public readonly metadata?: OpikApi.JsonNode;
  public readonly changeDescription?: string;
  public readonly tags?: string[];
  public readonly createdAt?: Date;
  public readonly createdBy?: string;

  constructor(data: PromptVersionData) {
    this.id = data.versionId;
    this.name = data.name;
    this.prompt = data.prompt;
    this.commit = data.commit;
    this.type = data.type;
    this.metadata = data.metadata;
    this.changeDescription = data.changeDescription;
    this.tags = data.tags;
    this.createdAt = data.createdAt;
    this.createdBy = data.createdBy;
  }

  /**
   * Format the prompt template with the provided variables
   */
  format(variables: PromptVariables): string {
    return formatPromptTemplate(this.prompt, variables, this.type);
  }

  /**
   * Get human-readable version age (e.g., "2 days ago", "Today")
   */
  getVersionAge(): string {
    if (!this.createdAt) {
      return "Unknown";
    }

    return formatDistanceToNow(new Date(this.createdAt), { addSuffix: true });
  }

  /**
   * Get formatted version information string
   * Format: "[commitHash] YYYY-MM-DD by user@email.com - Change description"
   */
  getVersionInfo(): string {
    const parts: string[] = [`[${this.commit}]`];

    if (this.createdAt) {
      const date = new Date(this.createdAt);
      parts.push(date.toISOString().split("T")[0]);
    }

    if (this.createdBy) {
      parts.push(`by ${this.createdBy}`);
    }

    if (this.changeDescription) {
      parts.push(`- ${this.changeDescription}`);
    }

    return parts.join(" ");
  }

  /**
   * Compare this version's template with another version and return a formatted diff.
   * Displays a git-style unified diff showing additions, deletions, and changes.
   * For chat prompts, provides intelligent formatting with structured message display.
   * The diff is automatically logged to the terminal and also returned as a string.
   * The output is colored and formatted for terminal display.
   *
   * @param other - The version to compare against
   * @returns A formatted string showing the differences between versions
   *
   * @example
   * ```typescript
   * const currentVersion = await prompt.getVersion("commit123");
   * const previousVersion = await prompt.getVersion("commit456");
   *
   * // Logs diff to terminal and returns it
   * const diff = currentVersion.compareTo(previousVersion);
   * ```
   */
  compareTo(other: PromptVersion): string {
    // Use descriptive labels that include version identifiers
    const thisLabel = `Current version [${this.commit}]`;
    const otherLabel = `Other version [${other.commit}]`;

    // Check if this is a chat prompt (template structure is chat)
    let thisFormatted = this.prompt;
    let otherFormatted = other.prompt;

    // Try to detect and format chat prompts
    if (this.isChatPrompt(this.prompt)) {
      thisFormatted = this.formatChatPromptString(this.prompt);
    }
    if (this.isChatPrompt(other.prompt)) {
      otherFormatted = this.formatChatPromptString(other.prompt);
    }

    const diffOutput = diffStringsUnified(otherFormatted, thisFormatted, {
      aAnnotation: otherLabel,
      bAnnotation: thisLabel,
      includeChangeCounts: true,
      contextLines: 3,
      expand: false,
    });

    // Log the diff to terminal for immediate visibility
    logger.info(`\nPrompt version comparison:\n${diffOutput}`);

    return diffOutput;
  }

  /**
   * Check if a prompt string is a chat prompt (JSON array of messages)
   */
  private isChatPrompt(prompt: string): boolean {
    try {
      const parsed = JSON.parse(prompt);
      return (
        Array.isArray(parsed) &&
        parsed.length > 0 &&
        typeof parsed[0] === "object" &&
        "role" in parsed[0] &&
        "content" in parsed[0]
      );
    } catch {
      return false;
    }
  }

  /**
   * Format chat prompt string (JSON) for human-readable comparison.
   */
  private formatChatPromptString(prompt: string): string {
    try {
      const messages: ChatMessage[] = JSON.parse(prompt);
      return formatChatMessagesForComparison(messages);
    } catch {
      // If parsing fails, return original prompt
      return prompt;
    }
  }

  /**
   * Factory method to create PromptVersion from API response
   */
  static fromApiResponse(
    name: string,
    apiResponse: OpikApi.PromptVersionDetail
  ): PromptVersion {
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
        "Invalid API response: missing required field 'id'"
      );
    }

    return new PromptVersion({
      name,
      prompt: apiResponse.template,
      commit: apiResponse.commit,
      promptId: apiResponse.promptId,
      versionId: apiResponse.id,
      type: apiResponse.type ?? PromptType.MUSTACHE,
      metadata: apiResponse.metadata,
      changeDescription: apiResponse.changeDescription,
      tags: apiResponse.tags,
      createdAt: apiResponse.createdAt
        ? new Date(apiResponse.createdAt)
        : undefined,
      createdBy: apiResponse.createdBy,
    });
  }
}
