import React, { useCallback, useRef, useState } from "react";
import { z } from "zod";

import {
  BlueprintType,
  BlueprintValue,
  BlueprintValueType,
} from "@/types/agent-configs";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import { BlueprintValuePromptHandle } from "@/v1/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
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

const validateField = (type: string, value: string): string => {
  const schema = FIELD_SCHEMAS[type as BlueprintValueType];
  if (!schema) return "";
  const result = schema.safeParse(value.trim());
  return result.success ? "" : result.error.issues[0].message;
};

type UseConfigurationSaveParams = {
  agentConfig: AgentConfig | undefined;
  draftValues: Record<string, string>;
  originalValues: React.RefObject<Record<string, string>>;
  description: string;
  projectId: string;
  isLatestVersion: boolean;
  onSaved: () => void;
};

export const useConfigurationSave = ({
  agentConfig,
  draftValues,
  originalValues,
  description,
  projectId,
  isLatestVersion,
  onSaved,
}: UseConfigurationSaveParams) => {
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

  const handleSave = useCallback(async () => {
    if (!agentConfig) return;

    // Step 1: Validate all scalar fields (skip prompts and booleans)
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

    // validate prompt fields
    for (const [key, handle] of Object.entries(promptRefs.current)) {
      if (handle) {
        const err = handle.validate();
        if (err) newErrors[key] = err;
      }
    }

    if (Object.values(newErrors).some(Boolean)) {
      setErrors(newErrors);
      return;
    }

    // Step 2: Persist all dirty prompts and collect their new commit hashes
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
      return;
    }

    const newCommits = new Map<string, string>();
    for (const result of promptResults) {
      if (result) {
        newCommits.set(result.key, result.commit);
      }
    }

    // Step 3: Build the payload
    // When editing from a non-latest version, send all non-prompt values
    // (prompts are excluded since they may have diverged in newer versions).
    // When editing from the latest version, send only changed values.
    const values: BlueprintValue[] = agentConfig.values
      .filter((v) => {
        if (v.type === BlueprintValueType.PROMPT) {
          return newCommits.has(v.key);
        }
        if (!isLatestVersion) {
          return true;
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
            ? newCommits.get(v.key)!
            : draftValues[v.key],
        ...(v.description ? { description: v.description } : {}),
      }));

    // Step 4: Submit the new config version
    createConfig(
      {
        agentConfig: {
          project_id: projectId,
          blueprint: {
            description: description || undefined,
            type: BlueprintType.BLUEPRINT,
            values,
          },
        },
      },
      { onSuccess: onSaved },
    );
  }, [
    agentConfig,
    draftValues,
    originalValues,
    description,
    projectId,
    isLatestVersion,
    onSaved,
    createConfig,
    toast,
  ]);

  return {
    handleSave,
    isSaving,
    errors,
    clearError,
    promptRefs,
  };
};
