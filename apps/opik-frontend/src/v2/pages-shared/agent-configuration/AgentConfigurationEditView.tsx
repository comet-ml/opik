import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ArrowUpRight, Info, Save } from "lucide-react";
import { Tag } from "@/ui/tag";

import { BlueprintValueType, ConfigHistoryItem } from "@/types/agent-configs";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Switch } from "@/ui/switch";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BlueprintTypeIcon from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintTypeIcon";
import BlueprintValuePrompt from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintValuePrompt";
import { useAgentConfigurationSave } from "./useAgentConfigurationSave";
import SaveVersionDialog from "./SaveVersionDialog";

type AgentConfigurationEditViewProps = {
  item: ConfigHistoryItem;
  projectId: string;
  onSaved: () => void;
  headerLeft?: React.ReactNode;
};

const AgentConfigurationEditView: React.FC<AgentConfigurationEditViewProps> = ({
  item,
  projectId,
  onSaved,
  headerLeft,
}) => {
  const { data: agentConfig, isPending } = useAgentConfigById({
    blueprintId: item.id,
  });

  const [description, setDescription] = useState("");
  const [draftValues, setDraftValues] = useState<Record<string, string>>({});
  const [dirtyPromptKeys, setDirtyPromptKeys] = useState<
    Record<string, boolean>
  >({});
  const originalValues = useRef<Record<string, string>>({});
  const initialized = useRef(false);

  const handleSaveComplete = useCallback(() => {
    originalValues.current = { ...draftValues };
    setDirtyPromptKeys({});
    setDescription("");
    onSaved();
  }, [draftValues, onSaved]);

  const { handleSave, isSaving, errors, clearError, promptRefs } =
    useAgentConfigurationSave({
      agentConfig,
      draftValues,
      originalValues,
      description,
      projectId,
      onSaved: handleSaveComplete,
    });

  useEffect(() => {
    if (agentConfig && !initialized.current) {
      initialized.current = true;
      const initial: Record<string, string> = {};
      agentConfig.values
        .filter((v) => v.type !== BlueprintValueType.PROMPT)
        .forEach((v) => {
          initial[v.key] = v.value;
        });
      originalValues.current = initial;
      setDraftValues(initial);
    }
  }, [agentConfig]);

  const handleFieldChange = (key: string, value: string) => {
    setDraftValues((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      clearError(key);
    }
  };

  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const [diffPromptTemplates, setDiffPromptTemplates] = useState<
    Record<string, string>
  >({});

  // Snapshot current prompt templates from editors before opening diff
  const handleOpenSaveDialog = () => {
    const templates: Record<string, string> = {};
    for (const [key, handle] of Object.entries(promptRefs.current)) {
      if (handle && dirtyPromptKeys[key]) {
        templates[key] = handle.getCurrentTemplate();
      }
    }
    setDiffPromptTemplates(templates);
    setSaveDialogOpen(true);
  };

  const currentValues = useMemo(() => {
    if (!agentConfig) return [];
    return agentConfig.values.map((v) =>
      v.type === BlueprintValueType.PROMPT
        ? v
        : { ...v, value: draftValues[v.key] ?? v.value },
    );
  }, [agentConfig, draftValues]);

  const hasErrors = Object.values(errors).some(Boolean);

  const hasChanges =
    Object.keys(draftValues).some(
      (key) => draftValues[key] !== originalValues.current[key],
    ) || Object.values(dirtyPromptKeys).some(Boolean);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        {headerLeft}
        <div className="ml-auto flex items-center gap-2">
          {hasChanges && (
            <Tag variant="orange" size="sm">
              Unsaved changes
            </Tag>
          )}
          <Button
            variant="outline"
            size="2xs"
            onClick={handleOpenSaveDialog}
            disabled={isSaving || hasErrors || !hasChanges}
          >
            <Save className="mr-1 size-3.5" />
            Save as new version
            <ArrowUpRight className="ml-1 size-3.5" />
          </Button>
        </div>
      </div>

      <div className="flex flex-col">
        {(agentConfig?.values ?? []).map((v) => {
          const isChanged =
            v.type === BlueprintValueType.PROMPT
              ? !!dirtyPromptKeys[v.key]
              : draftValues[v.key] !== undefined &&
                draftValues[v.key] !== originalValues.current[v.key];
          return (
            <div key={v.key} className="flex flex-col gap-2 py-3">
              <div className="flex items-center gap-2">
                <BlueprintTypeIcon type={v.type} variant="secondary" />
                <span className="comet-body-s-accented text-foreground">
                  {v.key}
                </span>
                {v.description && (
                  <TooltipWrapper content={v.description}>
                    <Info className="size-3.5 cursor-help text-light-slate" />
                  </TooltipWrapper>
                )}
                {isChanged && (
                  <span className="size-1.5 rounded-full bg-amber-400" />
                )}
              </div>
              {v.type === BlueprintValueType.PROMPT ? (
                <div className="flex flex-col gap-1">
                  <BlueprintValuePrompt
                    key={v.value}
                    value={v}
                    projectId={projectId}
                    isEditing
                    compact
                    ref={(el) => {
                      promptRefs.current[v.key] = el;
                    }}
                    onDirtyChange={(isDirty) => {
                      setDirtyPromptKeys((prev) => ({
                        ...prev,
                        [v.key]: isDirty,
                      }));
                      clearError(v.key);
                    }}
                  />
                  {errors[v.key] && (
                    <span className="comet-body-xs text-destructive">
                      {errors[v.key]}
                    </span>
                  )}
                </div>
              ) : v.type === BlueprintValueType.BOOLEAN ? (
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
                    inputMode={
                      v.type === BlueprintValueType.INT
                        ? "numeric"
                        : v.type === BlueprintValueType.FLOAT
                          ? "decimal"
                          : "text"
                    }
                    value={draftValues[v.key] ?? ""}
                    onChange={(e) => handleFieldChange(v.key, e.target.value)}
                  />
                  {errors[v.key] && (
                    <span className="comet-body-xs text-destructive">
                      {errors[v.key]}
                    </span>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <SaveVersionDialog
        open={saveDialogOpen}
        onOpenChange={setSaveDialogOpen}
        description={description}
        onDescriptionChange={setDescription}
        onSave={handleSave}
        isSaving={isSaving}
        base={{
          label: `${item.name} (original)`,
          blueprintId: item.id,
          values: agentConfig?.values,
        }}
        diff={{
          label: "Current changes",
          blueprintId: item.id,
          values: currentValues,
          promptTemplates: diffPromptTemplates,
        }}
      />
    </div>
  );
};

export default AgentConfigurationEditView;
