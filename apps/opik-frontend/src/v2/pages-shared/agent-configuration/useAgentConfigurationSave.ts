import React, { useCallback, useRef, useState } from "react";

import {
  BlueprintCreate,
  BlueprintType,
  BlueprintValue,
  BlueprintValueType,
} from "@/types/agent-configs";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { BlueprintValuePromptHandle } from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
import { useToast } from "@/ui/use-toast";
import {
  PROMPT_TEMPLATE_STRUCTURE,
  PromptWithLatestVersion,
} from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import { serializeChatTemplate } from "@/lib/chatTemplate";
import { NewFieldDraft } from "./NewBlueprintFieldEditor";
import {
  BLUEPRINT_FIELD_NAME_PATTERN,
  validateBlueprintFieldValue,
} from "./blueprintFieldValidation";

import type useAgentConfigById from "@/api/agent-configs/useAgentConfigById";

type AgentConfig = NonNullable<ReturnType<typeof useAgentConfigById>["data"]>;

export type AgentConfigPayload = {
  project_id: string;
  blueprint: BlueprintCreate;
};

export const isMessageEmpty = (message: LLMMessage): boolean => {
  const { content } = message;
  if (typeof content === "string") return !content.trim();
  if (Array.isArray(content)) {
    return content.every(
      (part) =>
        part.type === "text" && !(part as { text?: string }).text?.trim(),
    );
  }
  return true;
};

export const validateNewField = (
  field: NewFieldDraft,
  existingKeys: ReadonlySet<string>,
  siblingKeys: ReadonlySet<string>,
): string => {
  const key = field.key.trim();
  if (!key) return "Field name is required";
  if (!BLUEPRINT_FIELD_NAME_PATTERN.test(key))
    return "Use letters, digits and underscore; start with a letter or underscore";
  if (existingKeys.has(key)) return "A field with this name already exists";
  if (siblingKeys.has(key)) return "Duplicate field name in the new fields";
  if (field.type === BlueprintValueType.PROMPT) {
    if (field.promptStructure === PROMPT_TEMPLATE_STRUCTURE.TEXT) {
      return field.value.trim() ? "" : "Prompt must not be empty";
    }
    if (field.messages.length === 0) return "Add at least one message";
    if (field.messages.every(isMessageEmpty))
      return "Messages must not be empty";
    return "";
  }
  if (field.type !== BlueprintValueType.BOOLEAN) {
    return validateBlueprintFieldValue(field.type, field.value);
  }
  return "";
};

type UseAgentConfigurationSaveParams = {
  agentConfig: AgentConfig | undefined;
  draftValues: Record<string, string>;
  originalValues: React.RefObject<Record<string, string>>;
  description: string;
  projectId: string;
  onSaved: (newBlueprintId?: string) => void;
  dirtyPromptKeys?: Record<string, boolean>;
  removedKeys?: Set<string>;
  newFields?: NewFieldDraft[];
};

export const useAgentConfigurationSave = ({
  agentConfig,
  draftValues,
  originalValues,
  description,
  projectId,
  onSaved,
  dirtyPromptKeys,
  removedKeys,
  newFields,
}: UseAgentConfigurationSaveParams) => {
  const { toast } = useToast();
  const { mutate: createConfig, isPending: isSaving } =
    useAgentConfigCreateMutation();
  const { mutateAsync: createPrompt } = usePromptCreateMutation();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const promptRefs = useRef<Record<string, BlueprintValuePromptHandle | null>>(
    {},
  );

  const clearError = useCallback((key: string) => {
    setErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }, []);

  const hasChanges = useCallback(() => {
    const hasScalarChanges = Object.keys(draftValues).some(
      (key) =>
        originalValues.current !== null &&
        draftValues[key] !== originalValues.current[key],
    );
    const hasPromptChanges =
      !!dirtyPromptKeys && Object.values(dirtyPromptKeys).some(Boolean);
    const hasRemovals = (removedKeys?.size ?? 0) > 0;
    const hasAdditions = (newFields?.length ?? 0) > 0;
    return hasScalarChanges || hasPromptChanges || hasRemovals || hasAdditions;
  }, [draftValues, originalValues, dirtyPromptKeys, removedKeys, newFields]);

  // Collect errors for every editable field. Returns the error map; the
  // caller decides whether to abort the save.
  const collectValidationErrors = useCallback(
    (removed: ReadonlySet<string>, added: NewFieldDraft[]) => {
      if (!agentConfig) return {};

      const newErrors: Record<string, string> = {};

      // Existing scalar fields (PROMPT and BOOLEAN don't need value parsing)
      for (const v of agentConfig.values) {
        if (
          removed.has(v.key) ||
          v.type === BlueprintValueType.PROMPT ||
          v.type === BlueprintValueType.BOOLEAN
        ) {
          continue;
        }
        const err = validateBlueprintFieldValue(
          v.type,
          draftValues[v.key] ?? "",
        );
        if (err) newErrors[v.key] = err;
      }

      // Existing prompt fields delegate validation to the editor handle
      for (const [key, handle] of Object.entries(promptRefs.current)) {
        if (!handle || removed.has(key)) continue;
        const err = handle.validate();
        if (err) newErrors[key] = err;
      }

      // New fields: key uniqueness + per-type value validation
      const existingKeys = new Set(
        agentConfig.values.filter((v) => !removed.has(v.key)).map((v) => v.key),
      );
      const seenNewKeys = new Set<string>();
      for (const field of added) {
        const err = validateNewField(field, existingKeys, seenNewKeys);
        if (err) newErrors[field.id] = err;
        else seenNewKeys.add(field.key.trim());
      }

      return newErrors;
    },
    [agentConfig, draftValues],
  );

  // Save dirty existing prompts. Returns a key→commit map of any new commits.
  // Throws on failure (caught by caller).
  const saveDirtyPromptVersions = useCallback(
    async (removed: ReadonlySet<string>) => {
      const results = await Promise.all(
        Object.entries(promptRefs.current)
          .filter(([key, handle]) => handle && !removed.has(key))
          .map(([, handle]) => handle!.saveVersion()),
      );

      const commits = new Map<string, string>();
      for (const r of results) {
        if (r) commits.set(r.key, r.commit);
      }
      return commits;
    },
    [],
  );

  // Materialize new PROMPT fields by creating brand-new prompts in the
  // library. Scalar fields pass through with their entered value. Returns
  // null if any prompt creation fails.
  const materializeNewFields = useCallback(
    async (added: NewFieldDraft[]): Promise<BlueprintValue[] | null> => {
      const out: BlueprintValue[] = [];
      for (const field of added) {
        const key = field.key.trim();
        if (field.type !== BlueprintValueType.PROMPT) {
          out.push({ key, type: field.type, value: field.value });
          continue;
        }
        const isTextPrompt =
          field.promptStructure === PROMPT_TEMPLATE_STRUCTURE.TEXT;
        const template = isTextPrompt
          ? field.value
          : serializeChatTemplate(field.messages);
        try {
          const created = (await createPrompt({
            prompt: {
              name: key,
              template,
              template_structure: isTextPrompt
                ? PROMPT_TEMPLATE_STRUCTURE.TEXT
                : PROMPT_TEMPLATE_STRUCTURE.CHAT,
              project_id: projectId,
            },
            withResponse: true,
          })) as PromptWithLatestVersion;
          const commit = created?.latest_version?.commit;
          if (!commit) return null;
          out.push({ key, type: BlueprintValueType.PROMPT, value: commit });
        } catch {
          return null;
        }
      }
      return out;
    },
    [createPrompt, projectId],
  );

  // Build a self-contained list of values for the new blueprint version.
  // Removed keys are omitted; new fields are appended; PROMPT entries use
  // newly-created commits when available; scalar entries use the latest
  // draft value (falling back to the original).
  const buildBlueprintValues = useCallback(
    (
      removed: ReadonlySet<string>,
      newCommits: ReadonlyMap<string, string>,
      addedValues: BlueprintValue[],
    ): BlueprintValue[] => {
      if (!agentConfig) return addedValues;
      const kept = agentConfig.values
        .filter((v) => !removed.has(v.key))
        .map((v) => {
          const value =
            v.type === BlueprintValueType.PROMPT
              ? newCommits.get(v.key) ?? v.value
              : draftValues[v.key] ?? v.value;
          return {
            key: v.key,
            type: v.type,
            value,
            ...(v.description ? { description: v.description } : {}),
          };
        });
      return [...kept, ...addedValues];
    },
    [agentConfig, draftValues],
  );

  const validateAndBuildPayload = useCallback(
    async (type: BlueprintType): Promise<AgentConfigPayload | null> => {
      if (!agentConfig) return null;

      const removed = removedKeys ?? new Set<string>();
      const added = newFields ?? [];

      const validationErrors = collectValidationErrors(removed, added);
      if (Object.values(validationErrors).some(Boolean)) {
        setErrors(validationErrors);
        return null;
      }

      let newCommits: Map<string, string>;
      try {
        newCommits = await saveDirtyPromptVersions(removed);
      } catch {
        toast({
          title: "Failed to save prompt versions",
          description: "Please try again",
          variant: "destructive",
        });
        return null;
      }

      const addedValues = await materializeNewFields(added);
      if (addedValues === null) return null;

      return {
        project_id: projectId,
        blueprint: {
          description: description || undefined,
          type,
          values: buildBlueprintValues(removed, newCommits, addedValues),
        },
      };
    },
    [
      agentConfig,
      removedKeys,
      newFields,
      collectValidationErrors,
      saveDirtyPromptVersions,
      materializeNewFields,
      buildBlueprintValues,
      projectId,
      description,
      toast,
    ],
  );

  const handleSave = useCallback(async () => {
    const payload = await validateAndBuildPayload(BlueprintType.BLUEPRINT);
    if (!payload) return;

    createConfig(
      { agentConfig: payload },
      { onSuccess: ({ id }) => onSaved(id) },
    );
  }, [validateAndBuildPayload, createConfig, onSaved]);

  const buildMaskPayload = useCallback(
    () => validateAndBuildPayload(BlueprintType.MASK),
    [validateAndBuildPayload],
  );

  return {
    handleSave,
    buildMaskPayload,
    hasChanges,
    isSaving,
    errors,
    clearError,
    promptRefs,
  };
};
