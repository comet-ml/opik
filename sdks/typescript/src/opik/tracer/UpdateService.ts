import type { Prompt } from "@/prompt/Prompt";
import type * as OpikApi from "@/rest_api/api";
import type { PromptInfoDict, TraceUpdateData, SpanUpdateData } from "./types";

/**
 * Service for processing trace and span updates with prompts support.
 * Handles serialization of prompts and merging into metadata.
 */
export class UpdateService {
  /**
   * Serializes a Prompt object to info dict format.
   * Matches Python SDK serialization format.
   *
   * @param prompt - Prompt instance to serialize
   * @returns Serialized prompt in info dict format
   */
  private static serializePromptToInfoDict(prompt: Prompt): PromptInfoDict {
    return {
      name: prompt.name,
      ...(prompt.id && { id: prompt.id }),
      version: {
        ...(prompt.versionId && { id: prompt.versionId }),
        ...(prompt.commit && { commit: prompt.commit }),
        template: prompt.prompt,
      },
    };
  }

  /**
   * Converts JsonListString to an object suitable for merging.
   * Handles string (JSON parsing), array, and object types.
   *
   * @param metadata - Metadata in JsonListString format
   * @returns Object representation of metadata, or empty object if conversion fails
   */
  private static normalizeMetadata(
    metadata: OpikApi.JsonListString | undefined
  ): Record<string, unknown> {
    if (!metadata) {
      return {};
    }

    // If it's already an object (not an array), use it directly
    if (typeof metadata === "object" && !Array.isArray(metadata)) {
      return metadata;
    }

    // If it's a string, try to parse it as JSON
    if (typeof metadata === "string") {
      try {
        const parsed = JSON.parse(metadata);
        // If parsed result is an object (not array), use it
        if (typeof parsed === "object" && !Array.isArray(parsed)) {
          return parsed;
        }
      } catch {
        // If parsing fails, we can't merge - return empty object
        // The original string will be lost, but we can't merge into a string
      }
    }

    // For arrays or unparseable strings, we can't merge prompts into them
    // Return empty object - prompts will be the only metadata
    // Note: This means non-object metadata will be replaced when prompts are added
    return {};
  }

  /**
   * Merges prompts into metadata under "opik_prompts" key.
   * Preserves existing metadata and new metadata fields when they are objects.
   * Non-object metadata (strings/arrays) will be replaced with prompt metadata.
   *
   * @param existingMetadata - Current metadata from trace/span
   * @param newMetadata - New metadata from update call
   * @param prompts - Array of Prompt objects to serialize
   * @returns Merged metadata with prompts
   */
  private static mergePromptsIntoMetadata(
    existingMetadata: OpikApi.JsonListString | undefined,
    newMetadata: OpikApi.JsonListString | undefined,
    prompts: Prompt[]
  ): OpikApi.JsonListString {
    const serializedPrompts = prompts.map((p) =>
      this.serializePromptToInfoDict(p)
    );

    const existingObj = this.normalizeMetadata(existingMetadata);
    const newObj = this.normalizeMetadata(newMetadata);

    return {
      ...existingObj,
      ...newObj,
      opik_prompts: serializedPrompts,
    };
  }

  /**
   * Processes trace update by extracting prompts and merging into metadata.
   * Returns processed update ready for batch queue.
   *
   * @param updates - Trace update with optional prompts
   * @param existingMetadata - Current trace metadata
   * @returns Processed trace update without prompts field
   */
  static processTraceUpdate(
    updates: TraceUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<OpikApi.TraceUpdate, "projectId"> {
    const { prompts, ...restUpdates } = updates;

    if (!prompts || prompts.length === 0) {
      // Even without prompts, merge existing metadata with new metadata
      // so that trace.update({ metadata: {...} }) preserves prior metadata
      if (restUpdates.metadata && existingMetadata) {
        const existingObj = this.normalizeMetadata(existingMetadata);
        const newObj = this.normalizeMetadata(restUpdates.metadata);
        return {
          ...restUpdates,
          metadata: { ...existingObj, ...newObj },
        };
      }
      return restUpdates;
    }

    const mergedMetadata = this.mergePromptsIntoMetadata(
      existingMetadata,
      restUpdates.metadata,
      prompts
    );

    return {
      ...restUpdates,
      metadata: mergedMetadata,
    };
  }

  /**
   * Processes span update by extracting prompts and merging into metadata.
   * Returns processed update ready for batch queue.
   *
   * @param updates - Span update with optional prompts
   * @param existingMetadata - Current span metadata
   * @returns Processed span update without prompts field
   */
  static processSpanUpdate(
    updates: SpanUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<
    OpikApi.SpanUpdate,
    "traceId" | "parentSpanId" | "projectId" | "projectName"
  > {
    const { prompts, ...restUpdates } = updates;

    if (!prompts || prompts.length === 0) {
      // Even without prompts, merge existing metadata with new metadata
      // so that span.update({ metadata: {...} }) preserves prior metadata
      if (restUpdates.metadata && existingMetadata) {
        const existingObj = this.normalizeMetadata(existingMetadata);
        const newObj = this.normalizeMetadata(restUpdates.metadata);
        return {
          ...restUpdates,
          metadata: { ...existingObj, ...newObj },
        };
      }
      return restUpdates;
    }

    const mergedMetadata = this.mergePromptsIntoMetadata(
      existingMetadata,
      restUpdates.metadata,
      prompts
    );

    return {
      ...restUpdates,
      metadata: mergedMetadata,
    };
  }
}
