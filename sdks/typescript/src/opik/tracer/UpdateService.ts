import type { BasePrompt } from "@/prompt/BasePrompt";
import type { Prompt } from "@/prompt/Prompt";
import type { ChatPrompt } from "@/prompt/ChatPrompt";
import type * as OpikApi from "@/rest_api/api";
import type { PromptInfoDict, TraceUpdateData, SpanUpdateData } from "./types";

function isTextPrompt(p: BasePrompt): p is Prompt {
  return p.templateStructure === "text";
}

function isChatPrompt(p: BasePrompt): p is ChatPrompt {
  return p.templateStructure === "chat";
}

/**
 * Service for processing trace and span updates with prompts support.
 * Handles serialization of prompts and merging into metadata.
 */
export class UpdateService {
  private static serializePromptToInfoDict(prompt: BasePrompt): PromptInfoDict {
    let template: unknown;
    if (isTextPrompt(prompt)) {
      template = prompt.prompt;
    } else if (isChatPrompt(prompt)) {
      template = prompt.messages;
    } else {
      template = "";
    }

    return {
      name: prompt.name,
      ...(prompt.id && { id: prompt.id }),
      template_structure: prompt.templateStructure,
      version: {
        ...(prompt.versionId && { id: prompt.versionId }),
        ...(prompt.commit && { commit: prompt.commit }),
        template,
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
    prompts: BasePrompt[],
    append: boolean
  ): OpikApi.JsonListString {
    const serializedPrompts = prompts.map((p) =>
      this.serializePromptToInfoDict(p)
    );

    const existingObj = this.normalizeMetadata(existingMetadata);
    const newObj = this.normalizeMetadata(newMetadata);

    const existingPrompts = append && Array.isArray(existingObj.opik_prompts)
      ? existingObj.opik_prompts as PromptInfoDict[]
      : [];

    return {
      ...existingObj,
      ...newObj,
      opik_prompts: [...existingPrompts, ...serializedPrompts],
    };
  }

  private static processUpdate<T extends { metadata?: OpikApi.JsonListString; prompts?: BasePrompt[]; appendPrompts?: boolean }>(
    updates: T,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<T, "prompts" | "appendPrompts"> {
    const { prompts, appendPrompts, ...restUpdates } = updates;

    if (!prompts || prompts.length === 0) {
      // Even without prompts, merge existing metadata with new metadata
      // so that update({ metadata: {...} }) preserves prior metadata
      if (restUpdates.metadata && existingMetadata) {
        const existingObj = this.normalizeMetadata(existingMetadata);
        const newObj = this.normalizeMetadata(restUpdates.metadata);
        return { ...restUpdates, metadata: { ...existingObj, ...newObj } };
      }
      return restUpdates as Omit<T, "prompts" | "appendPrompts">;
    }

    return {
      ...restUpdates,
      metadata: this.mergePromptsIntoMetadata(existingMetadata, restUpdates.metadata, prompts, appendPrompts ?? false),
    };
  }

  static processTraceUpdate(
    updates: TraceUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<OpikApi.TraceUpdate, "projectId"> {
    return this.processUpdate(updates, existingMetadata);
  }

  static processSpanUpdate(
    updates: SpanUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<OpikApi.SpanUpdate, "traceId" | "parentSpanId" | "projectId" | "projectName"> {
    return this.processUpdate(updates, existingMetadata);
  }
}
