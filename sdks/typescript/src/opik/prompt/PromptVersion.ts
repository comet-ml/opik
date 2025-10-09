import type * as OpikApi from "@/rest_api/api";
import { formatDistanceToNow } from "date-fns";
import { diffStringsUnified } from "jest-diff";
import { logger } from "@/utils/logger";
import {
  PromptType,
  type PromptVariables,
  type PromptVersionData,
} from "./types";
import { PromptValidationError } from "./errors";
import { formatPromptTemplate } from "./formatting";

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

    const diffOutput = diffStringsUnified(other.prompt, this.prompt, {
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
      createdAt: apiResponse.createdAt
        ? new Date(apiResponse.createdAt)
        : undefined,
      createdBy: apiResponse.createdBy,
    });
  }
}
