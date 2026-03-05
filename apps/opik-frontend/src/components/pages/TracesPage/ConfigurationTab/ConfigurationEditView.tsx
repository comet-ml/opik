import React, { useEffect, useRef, useState } from "react";
import { Pencil } from "lucide-react";

import {
  BlueprintType,
  BlueprintValue,
  ConfigHistoryItem,
} from "@/types/agent-configs";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import useAgentConfigCreateMutation from "@/api/agent-configs/useAgentConfigCreateMutation";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import Loader from "@/components/shared/Loader/Loader";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import BlueprintValuePrompt, {
  BlueprintValuePromptHandle,
} from "./BlueprintValuePrompt";

type ConfigurationEditViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  version: number;
  onCancel: () => void;
  onSaved: () => void;
};

const ConfigurationEditView: React.FC<ConfigurationEditViewProps> = ({
  item,
  projectId,
  version,
  onCancel,
  onSaved,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });
  const { mutate: createConfig, isPending: isSaving } =
    useAgentConfigCreateMutation();

  const [description, setDescription] = useState("");
  const [draftValues, setDraftValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const originalValues = useRef<Record<string, string>>({});
  const initialized = useRef(false);
  const promptRefs = useRef<Record<string, BlueprintValuePromptHandle | null>>(
    {},
  );

  useEffect(() => {
    if (agentConfig && !initialized.current) {
      initialized.current = true;
      const initial: Record<string, string> = {};
      agentConfig.values
        .filter((v) => v.type !== "prompt")
        .forEach((v) => {
          initial[v.key] = v.value;
        });
      originalValues.current = initial;
      setDraftValues(initial);
    }
  }, [agentConfig]);

  const validateField = (type: string, value: string): string => {
    if (type === "int") {
      return /^-?\d+$/.test(value.trim()) ? "" : "Must be an integer";
    }
    if (type === "float") {
      return value.trim() !== "" && !isNaN(Number(value))
        ? ""
        : "Must be a valid number";
    }
    return "";
  };

  const handleFieldChange = (key: string, type: string, value: string) => {
    setDraftValues((prev) => ({ ...prev, [key]: value }));
    setErrors((prev) => ({ ...prev, [key]: validateField(type, value) }));
  };

  const hasErrors = Object.values(errors).some(Boolean);

  const handleSave = async () => {
    if (!agentConfig) return;

    const newErrors: Record<string, string> = {};
    agentConfig.values
      .filter((v) => v.type !== "prompt" && v.type !== "boolean")
      .forEach((v) => {
        const err = validateField(v.type, draftValues[v.key] ?? "");
        if (err) newErrors[v.key] = err;
      });

    if (Object.values(newErrors).some(Boolean)) {
      setErrors(newErrors);
      return;
    }

    await Promise.all(
      Object.values(promptRefs.current)
        .filter(Boolean)
        .map((handle) => handle!.saveVersion()),
    );

    const values: BlueprintValue[] = agentConfig.values.map((v) => ({
      key: v.key,
      type: v.type,
      value: v.type !== "prompt" ? draftValues[v.key] ?? v.value : v.value,
      ...(v.description ? { description: v.description } : {}),
    }));

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
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <Card className="mx-6 my-4 p-6">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="comet-title-s">Create new version</h2>
          <div className="comet-body-xs flex items-center gap-1 rounded bg-[#FF5A3C] px-2 py-0.5 text-white">
            <Pencil className="size-2.5" />
            From v{version}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={onCancel}
            disabled={isSaving}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={isSaving || hasErrors}
          >
            {isSaving ? "Saving…" : "Save as new version"}
          </Button>
        </div>
      </div>

      <div className="mb-4">
        <label className="comet-body-xs-accented mb-1.5 block text-foreground">
          Description
        </label>
        <Input
          placeholder="Describe what changed in this version…"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <div className="flex flex-col divide-y">
        {(agentConfig?.values ?? []).map((v) => {
          const isChanged =
            v.type !== "prompt" &&
            draftValues[v.key] !== undefined &&
            draftValues[v.key] !== originalValues.current[v.key];
          return (
            <div key={v.key} className="flex flex-col gap-2 py-3">
              <div className="flex items-center gap-2">
                <BlueprintTypeIcon type={v.type} variant="secondary" />
                <span className="comet-body-xs-accented text-foreground">
                  {v.key}
                </span>
                {isChanged && (
                  <span className="size-1.5 rounded-full bg-amber-400" />
                )}
              </div>
              {v.description && (
                <span className="comet-body-xs text-light-slate">
                  {v.description}
                </span>
              )}
              {v.type === "prompt" ? (
                <BlueprintValuePrompt
                  value={v}
                  isEditing
                  ref={(el) => {
                    promptRefs.current[v.key] = el;
                  }}
                />
              ) : v.type === "boolean" ? (
                <Switch
                  checked={draftValues[v.key] === "true"}
                  onCheckedChange={(checked) =>
                    setDraftValues((prev) => ({
                      ...prev,
                      [v.key]: String(checked),
                    }))
                  }
                />
              ) : (
                <div className="flex flex-col gap-1">
                  <Input
                    type={
                      v.type === "int" || v.type === "float" ? "number" : "text"
                    }
                    value={draftValues[v.key] ?? ""}
                    onChange={(e) =>
                      handleFieldChange(v.key, v.type, e.target.value)
                    }
                  />
                  {errors[v.key] && (
                    <span className="comet-body-xs text-red-500">
                      {errors[v.key]}
                    </span>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </Card>
  );
};

export default ConfigurationEditView;
