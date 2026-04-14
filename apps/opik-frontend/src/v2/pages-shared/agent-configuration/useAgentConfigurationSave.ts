import React, { useCallback, useRef, useState } from "react";
import { z } from "zod";

import {
  BlueprintCreate,
  BlueprintType,
  BlueprintValue,
  BlueprintValueType,
} from "@/types/agent-configs";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import { BlueprintValuePromptHandle } from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
import { useToast } from "@/ui/use-toast";

import type useAgentConfigById from "@/api/agent-configs/useAgentConfigById";

type AgentConfig = NonNullable<ReturnType<typeof useAgentConfigById>["data"]>;

const nonEmptyString = z.string().min(1, "Must not be empty");

const FIELD_SCHEMAS: Partial<Record<BlueprintValueType, z.ZodType>> = {
  [BlueprintValueType.INT]: nonEmptyString.pipe(
    z.coerce.number().int("Must be an integer"),
  ),
  [BlueprintValueType.FLOAT]: nonEmptyString.pipe(
    z.coerce.number({ message: "Must be a valid number" }),
  ),
  [BlueprintValueType.STRING]: nonEmptyString,
};

export type AgentConfigPayload = {
  project_id: string;
  blueprint: BlueprintCreate;
};

const validateField = (type: string, value: string): string => {
  const schema = FIELD_SCHEMAS[type as BlueprintValueType];
  if (!schema) return "";
  const result = schema.safeParse(value.trim());
  return result.success ? "" : result.error.issues[0].message;
};

type UseAgentConfigurationSaveParams = {
  agentConfig: AgentConfig | undefined;
  draftValues: Record<string, string>;
  originalValues: React.RefObject<Record<string, string>>;
  description: string;
  projectId: string;
  onSaved: (newBlueprintId?: string) => void;
  dirtyPromptKeys?: Record<string, boolean>;
};

export const useAgentConfigurationSave = ({
  agentConfig,
  draftValues,
  originalValues,
  description,
  projectId,
  onSaved,
  dirtyPromptKeys,
}: UseAgentConfigurationSaveParams) => {
  const { toast } = useToast();
  const { mutate: createConfig, isPending: isSaving } =
    useAgentConfigCreateMutation();
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
    const hasPromptChanges = dirtyPromptKeys
      ? Object.values(dirtyPromptKeys).some(Boolean)
      : false;
    return hasScalarChanges || hasPromptChanges;
  }, [draftValues, originalValues, dirtyPromptKeys]);

  const validateAndBuildPayload = useCallback(
    async (type: BlueprintType): Promise<AgentConfigPayload | null> => {
      if (!agentConfig) return null;

      const newErrors: Record<string, string> = {};
      agentConfig.values
        .filter(
          (v) =>
            v.type !== BlueprintValueType.PROMPT &&
            v.type !== BlueprintValueType.BOOLEAN,
        )
        .forEach((v) => {
          const err = validateField(v.type, draftValues[v.key] ?? "");
          if (err) newErrors[v.key] = err;
        });

      for (const [key, handle] of Object.entries(promptRefs.current)) {
        if (handle) {
          const err = handle.validate();
          if (err) newErrors[key] = err;
        }
      }

      if (Object.values(newErrors).some(Boolean)) {
        setErrors(newErrors);
        return null;
      }

      let promptResults: Awaited<
        ReturnType<BlueprintValuePromptHandle["saveVersion"]>
      >[];
      try {
        promptResults = await Promise.all(
          Object.values(promptRefs.current)
            .filter(Boolean)
            .map((handle) => handle!.saveVersion()),
        );
      } catch {
        toast({
          title: "Failed to save prompt versions",
          description: "Please try again",
          variant: "destructive",
        });
        return null;
      }

      const newCommits = new Map<string, string>();
      for (const result of promptResults) {
        if (result) {
          newCommits.set(result.key, result.commit);
        }
      }

      const values: BlueprintValue[] = agentConfig.values
        .filter((v) => {
          if (v.type === BlueprintValueType.PROMPT) {
            return newCommits.has(v.key);
          }
          return (
            originalValues.current !== null &&
            draftValues[v.key] !== originalValues.current[v.key]
          );
        })
        .map((v) => ({
          key: v.key,
          type: v.type,
          value:
            v.type === BlueprintValueType.PROMPT
              ? newCommits.get(v.key) ?? v.value
              : draftValues[v.key],
          ...(v.description ? { description: v.description } : {}),
        }));

      return {
        project_id: projectId,
        blueprint: {
          description: description || undefined,
          type,
          values,
        },
      };
    },
    [agentConfig, draftValues, originalValues, description, projectId, toast],
  );

  const handleSave = useCallback(async () => {
    const payload = await validateAndBuildPayload(BlueprintType.BLUEPRINT);
    if (!payload) return;

    createConfig(
      { agentConfig: payload },
      { onSuccess: ({ id }) => onSaved(id) },
    );
  }, [validateAndBuildPayload, createConfig, onSaved]);

  const buildMaskPayload = useCallback(async () => {
    return validateAndBuildPayload(BlueprintType.MASK);
  }, [validateAndBuildPayload]);

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
