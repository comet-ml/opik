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
   * Converts JsonListString to an object suitable for merging, or null when
   * the value cannot be represented as a plain object (arrays, non-JSON strings).
   * A null return signals "not mergeable" so callers can preserve the original value.
   *
   * @param metadata - Metadata in JsonListString format
   * @returns Object representation of metadata, null if conversion is not possible (arrays, unparseable strings)
   */
  private static normalizeMetadata(
    metadata: OpikApi.JsonListString | undefined
  ): Record<string, unknown> | null {
    if (!metadata) {
      return {};
    }

    if (typeof metadata === "object" && !Array.isArray(metadata)) {
      return metadata;
    }

    if (typeof metadata === "string") {
      try {
        const parsed = JSON.parse(metadata);
        if (typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)) {
          return parsed as Record<string, unknown>;
        }
      } catch {
        // unparseable string — not mergeable
      }
    }

    return null;
  }

  /**
   * Returns true when the given prompt is already recorded in metadata OR when
   * metadata is not a plain object (in which case injection should be skipped to
   * avoid discarding the original value).
   */
  static promptAlreadyInjected(
    metadata: OpikApi.JsonListString | undefined,
    promptId: string | undefined,
    commit: string | undefined
  ): boolean {
    const obj = this.normalizeMetadata(metadata);
    if (obj === null) {
      // Non-object metadata — skip injection to preserve original value.
      return true;
    }
    const existing = Array.isArray(obj.opik_prompts) ? (obj.opik_prompts as PromptInfoDict[]) : [];
    return existing.some((p) => p.id === promptId && p.version?.commit === commit);
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

    const existingObj = this.normalizeMetadata(existingMetadata) ?? {};
    const newObj = this.normalizeMetadata(newMetadata) ?? {};

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
        const existingObj = this.normalizeMetadata(existingMetadata) ?? {};
        const newObj = this.normalizeMetadata(restUpdates.metadata) ?? {};
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
